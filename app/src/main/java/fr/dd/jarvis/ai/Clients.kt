package fr.dd.jarvis.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Groq (API compatible OpenAI) : le « cerveau » principal, avec appel d'outils. */
class GroqClient(private val ring: KeyRing, private val model: () -> String) {

    suspend fun chat(messages: JSONArray, tools: JSONArray?, out: Int = 2048, forceText: Boolean = false): JSONObject =
        ring.run { key -> withContext(Dispatchers.IO) { call(key, messages, tools, 1024, Http.client, out, forceText) } }

    suspend fun plain(system: String, user: String): String {
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return ring.run { key -> withContext(Dispatchers.IO) { call(key, msgs, null, 1024, Http.fast) } }.optString("content", "").trim()
    }

    /** Groq configuré (au moins une clé) : sert aussi à la dictée vocale (Whisper) en voiture. */
    fun disponible() = ring.count() > 0

    /**
     * Transcription d'une phrase enregistrée (WAV) avec Whisper sur Groq, en français. Essaie le modèle rapide puis le
     * modèle complet si le premier n'est plus proposé.
     */
    suspend fun transcrire(wav: ByteArray, langue: String = "fr"): String = ring.run { key ->
        withContext(Dispatchers.IO) {
            var derniere: ApiException? = null
            for (modele in listOf("whisper-large-v3-turbo", "whisper-large-v3")) {
                val corps = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", "voix.wav", wav.toRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("model", modele).addFormDataPart("language", langue)
                    .addFormDataPart("response_format", "json").addFormDataPart("temperature", "0").build()
                val req = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .header("Authorization", "Bearer $key").post(corps).build()
                Http.client.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    if (r.isSuccessful) return@withContext JSONObject(t).optString("text").trim()
                    val e = ApiException(r.code, "Groq ${r.code} : ${t.take(200)}")
                    // Modèle retiré ou refusé : on tente le suivant ; quota / clé : on laisse l'anneau changer de clé.
                    if (r.code != 400 && r.code != 404) throw e
                    derniere = e
                }
            }
            throw derniere ?: ApiException(500, "Transcription impossible")
        }
    }

    /** Test d'une clé précise (bouton « Tester »). */
    suspend fun ping(key: String) = withContext(Dispatchers.IO) {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
        call(key, msgs, null, 1)
    }

    /** Modèles de chat disponibles pour cette clé. */
    suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.groq.com/openai/v1/models").header("Authorization", "Bearer $key").build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, "Groq ${resp.code} : ${text.take(200)}")
            val arr = JSONObject(text).getJSONArray("data")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
                .filter { id -> listOf("whisper", "orpheus", "guard", "safeguard", "tts", "embed").none { id.contains(it, true) } }
                .sorted()
        }
    }

    private fun call(key: String, messages: JSONArray, tools: JSONArray?, maxTokens: Int, client: okhttp3.OkHttpClient = Http.client, out: Int = 2048, forceText: Boolean = false): JSONObject {
        val mdl = model()
        val body = JSONObject()
            .put("model", mdl)
            .put("messages", messages)
            .put("temperature", 0.4)
            .put("max_tokens", if (maxTokens == 1) 1 else out)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            // forceText=true : garde les définitions d'outils visibles (évite au modèle de se sentir « coupé » du contexte
            // précédent où il appelait des outils) mais lui interdit explicitement d'en appeler un ce tour-ci — plus fiable
            // que de retirer complètement « tools », ce qui provoquait parfois une erreur Groq « Tool choice is none, but
            // model called a tool » (le modèle tentait quand même un appel malgré l'absence de outils déclarés).
            body.put("tool_choice", if (forceText) "none" else "auto")
        }
        // Modèles « de raisonnement » (gpt-oss, deepseek-r1, qwen3…) : par défaut Groq laisse leur réflexion interne (chain-of-
        // thought) dans le texte de réponse — on demande explicitement de ne garder QUE la réponse finale, pour que JARVIS
        // n'affiche jamais ce raisonnement brut à l'utilisateur. Les deux paramètres sont mutuellement exclusifs selon Groq.
        if (Regex("gpt-oss|deepseek|qwen3|-r1\\b|reasoning|minimax", RegexOption.IGNORE_CASE).containsMatchIn(mdl)) {
            if (mdl.contains("gpt-oss", true)) body.put("include_reasoning", false) else body.put("reasoning_format", "hidden")
        }
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, "Groq ${resp.code} : ${text.take(300)}")
            val msg = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            if (msg.has("content") && !msg.isNull("content")) msg.put("content", nettoyerRaisonnement(msg.optString("content")))
            msg
        }
    }
}

/** Sécurité commune (Groq + Cloudflare) : si un modèle de raisonnement laisse quand même fuiter sa réflexion interne dans le
 *  texte malgré les paramètres ci-dessus, on la retire ici avant affichage — balises <think>…</think>, ou tout ce qui suit
 *  une balise <think> restée ouverte (réponse coupée en pleine réflexion). */
internal fun nettoyerRaisonnement(s: String): String {
    var r = s.replace(Regex("(?is)<think>.*?</think>"), "")
    r = r.replace(Regex("(?is)<think>.*$"), "")
    return r.trim().ifBlank { s.trim() }   // ne jamais renvoyer une réponse vide si tout a été reconnu comme « raisonnement »
}

/** Cloudflare Workers AI (point d'accès compatible OpenAI) : cerveau de tout dernier recours, utilisé seulement quand
 *  toutes les clés Groq sont épuisées. Réutilise les mêmes comptes Cloudflare (« idCompte:jeton ») que generer_image. */
class CloudflareChatClient(private val accounts: () -> List<String>, private val model: () -> String) {
    private var next = 0
    private val cooldown = mutableMapOf<String, Long>()

    suspend fun chat(messages: JSONArray, tools: JSONArray?): JSONObject = withContext(Dispatchers.IO) {
        val list = accounts().filter { it.contains(':') }
        if (list.isEmpty()) throw IllegalStateException("Aucun compte Cloudflare configuré (Réglages › Images).")
        val now = System.currentTimeMillis()
        val order = list.indices.map { (next + it) % list.size }
        val (ready, cooling) = order.partition { (cooldown[list[it]] ?: 0L) <= now }
        var last: ApiException? = null
        for (i in ready + cooling) {
            val (id, token) = list[i].split(":", limit = 2)
            try {
                val body = JSONObject().put("model", model().ifBlank { "@cf/meta/llama-3.3-70b-instruct-fp8-fast" })
                    .put("messages", messages).put("temperature", 0.4)
                if (tools != null && tools.length() > 0) { body.put("tools", tools); body.put("tool_choice", "auto") }
                val req = Request.Builder().url("https://api.cloudflare.com/client/v4/accounts/$id/ai/v1/chat/completions")
                    .header("Authorization", "Bearer $token").post(body.toString().toRequestBody(Http.JSON)).build()
                val msg = Http.client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiException(resp.code, "Cloudflare ${resp.code} : ${text.take(300)}")
                    val m = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    if (m.has("content") && !m.isNull("content")) m.put("content", nettoyerRaisonnement(m.optString("content")))
                    m
                }
                next = (i + 1) % list.size
                return@withContext msg
            } catch (e: ApiException) {
                last = e
                cooldown[list[i]] = System.currentTimeMillis() + when (e.code) { 429 -> 60_000L; 401, 403 -> 600_000L; else -> 15_000L }
            }
        }
        throw last ?: IllegalStateException("Cloudflare : toutes les requêtes ont échoué.")
    }
}

/** Gemini (REST) : les agents spécialisés (planning, mails, contacts). */
class GeminiClient(private val ring: KeyRing, private val model: () -> String) {

    suspend fun generate(system: String, user: String): String =
        ring.run { key -> withContext(Dispatchers.IO) { call(key, system, user, 4096) } }

    /** Analyse une image (description, lecture de texte dedans, etc.) avec un modèle Gemini multimodal. */
    suspend fun analyserImage(bytes: ByteArray, mimeType: String, question: String): String =
        ring.run { key -> withContext(Dispatchers.IO) { callImage(key, bytes, mimeType, question) } }

    private fun callImage(key: String, bytes: ByteArray, mimeType: String, question: String): String {
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val body = JSONObject().put("contents", JSONArray().put(
            JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("text", question.ifBlank { "Décris cette image en détail, en français." }))
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mimeType).put("data", b64))))
        )).put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 2048))
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/${model()}:generateContent")
            .header("x-goog-api-key", key).post(body.toString().toRequestBody(Http.JSON)).build()
        val (code, text) = Http.fast.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        if (code !in 200..299) throw ApiException(code, "Gemini $code : ${text.take(300)}")
        val cands = JSONObject(text).optJSONArray("candidates")
        val parts = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
        return sb.toString().trim().ifBlank { throw IllegalStateException("réponse vide") }
    }

    suspend fun ping(key: String) = withContext(Dispatchers.IO) { call(key, "", "ping", 8) }

    /** Modèles « generateContent » disponibles pour cette clé. */
    suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
            .header("x-goog-api-key", key).build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, "Gemini ${resp.code} : ${text.take(200)}")
            val arr = JSONObject(text).getJSONArray("models")
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { m -> m.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true }
                .map { it.getString("name").removePrefix("models/") }
                .filter { id -> listOf("image", "tts", "live", "audio", "embed", "robotics", "omni", "veo", "imagen").none { id.contains(it, true) } }
                .sorted()
        }
    }

    private fun call(key: String, system: String, user: String, maxTokens: Int, thinkingOff: Boolean = true): String {
        val gen = JSONObject().put("temperature", 0.3).put("maxOutputTokens", if (maxTokens > 100) 8192 else maxTokens)
        if (thinkingOff) {
            val m = model()
            when {
                m.contains("2.5") -> gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                m.contains("gemini-3") -> gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            }
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", user)))
            ))
            .put("generationConfig", gen)
        if (system.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${model()}:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        val (code, text) = Http.fast.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        // Certains modèles refusent le réglage de réflexion : on réessaie sans.
        if (code == 400 && thinkingOff && text.contains("think", ignoreCase = true)) return call(key, system, user, maxTokens, false)
        if (code !in 200..299) throw ApiException(code, "Gemini $code : ${text.take(300)}")
        val cands = JSONObject(text).optJSONArray("candidates")
        val parts = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
        }
        return sb.toString().trim()
    }
}
