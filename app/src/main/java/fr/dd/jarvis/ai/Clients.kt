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
                    val e = ApiException(r.code, "Groq ${r.code} : ${t.take(200)}${if (r.code == 429) infosLimites(r) else ""}")
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
        call(key, msgs, null, 1, Http.fast)
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

    private fun infosLimites(resp: okhttp3.Response): String {
        val noms = listOf("retry-after", "x-ratelimit-remaining-requests", "x-ratelimit-reset-requests", "x-ratelimit-remaining-tokens", "x-ratelimit-reset-tokens")
        val valeurs = noms.mapNotNull { nom -> resp.header(nom)?.let { "$nom=$it" } }
        return if (valeurs.isEmpty()) "" else " [${valeurs.joinToString("; ")}]"
    }

    private fun call(key: String, messages: JSONArray, tools: JSONArray?, maxTokens: Int, client: okhttp3.OkHttpClient = Http.client, out: Int = 2048, forceText: Boolean = false): JSONObject {
        val mdl = model()
        val body = JSONObject()
            .put("model", mdl)
            .put("messages", sansChampsGemini(messages))
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
            if (!resp.isSuccessful) throw ApiException(resp.code, "Groq ${resp.code} : ${text.take(300)}${if (resp.code == 429) infosLimites(resp) else ""}")
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
        if (list.isEmpty()) throw IllegalStateException("Aucun compte Cloudflare configuré (Réglages › IA › Images & vidéo).")
        val now = System.currentTimeMillis()
        val order = list.indices.map { (next + it) % list.size }
        val (ready, cooling) = order.partition { (cooldown[list[it]] ?: 0L) <= now }
        var last: ApiException? = null
        for (i in ready + cooling) {
            val (id, token) = list[i].split(":", limit = 2)
            try {
                val body = JSONObject().put("model", model().ifBlank { "@cf/meta/llama-3.3-70b-instruct-fp8-fast" })
                    .put("messages", sansChampsGemini(messages)).put("temperature", 0.4)
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
                cooldown[list[i]] = System.currentTimeMillis() + (KeyRing.pause(e.code, e.message.orEmpty()) ?: 15_000L)
            }
        }
        throw last ?: IllegalStateException("Cloudflare : toutes les requêtes ont échoué.")
    }

    fun disponible() = accounts().any { it.contains(':') }

    /** (comptes, en pause) — tableau de santé. */
    fun etat(): Pair<Int, Int> {
        val l = accounts().filter { it.contains(':') }
        val now = System.currentTimeMillis()
        return l.size to l.count { (cooldown[it] ?: 0L) > now }
    }
}

/** Copie des messages sans les champs que seul Gemini comprend (signature de réflexion des appels d'outils) :
 *  Groq et Cloudflare refusent les propriétés inconnues. */
internal fun sansChampsGemini(messages: JSONArray): JSONArray {
    if (!messages.toString().contains("extra_content")) return messages
    val out = JSONArray()
    for (i in 0 until messages.length()) {
        val m = messages.optJSONObject(i) ?: continue
        val appels = m.optJSONArray("tool_calls")
        if (appels == null) { out.put(m); continue }
        val c = JSONObject(m.toString())
        val a = c.getJSONArray("tool_calls")
        for (j in 0 until a.length()) a.optJSONObject(j)?.remove("extra_content")
        out.put(c)
    }
    return out
}

/**
 * Gemini en mode « compatible OpenAI » (appel d'outils compris) : relais du cerveau quand toutes les clés Groq sont
 * épuisées, avant Cloudflare. Mêmes clés Gemini que les agents, en rotation (anneau partagé : une clé épuisée ici l'est
 * aussi pour les agents, et inversement).
 */
class GeminiChatClient(private val ring: KeyRing, private val model: () -> String) {
    private val url = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"

    fun disponible() = ring.count() > 0

    suspend fun chat(messages: JSONArray, tools: JSONArray?, out: Int = 2048, forceText: Boolean = false): JSONObject =
        ring.run { key -> withContext(Dispatchers.IO) { call(key, messages, tools, out, forceText, true) } }

    private fun call(key: String, messages: JSONArray, tools: JSONArray?, out: Int, forceText: Boolean, reflexionReduite: Boolean): JSONObject {
        val mdl = model().ifBlank { "gemini-2.5-flash" }.removePrefix("models/")
        val body = JSONObject().put("model", mdl).put("messages", messagesPourGemini(messages)).put("temperature", 0.4)
            // La réflexion de Gemini compte dans les jetons de sortie : marge pour que la réponse ne soit pas coupée.
            .put("max_tokens", (out + 2048).coerceAtMost(8192))
        val outils = outilsPourGemini(tools)
        if (outils != null && outils.length() > 0) { body.put("tools", outils); body.put("tool_choice", if (forceText) "none" else "auto") }
        if (reflexionReduite) when {
            mdl.contains("2.5") && !mdl.contains("pro") -> body.put("reasoning_effort", "none")
            mdl.contains("gemini-3") -> body.put("reasoning_effort", "low")
        }
        val req = Request.Builder().url(url).header("Authorization", "Bearer $key").post(body.toString().toRequestBody(Http.JSON)).build()
        val (code, text) = Http.client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        if (code == 400 && reflexionReduite && Regex("reasoning|think", RegexOption.IGNORE_CASE).containsMatchIn(text)) return call(key, messages, tools, out, forceText, false)
        if (code !in 200..299) throw ApiException(code, "Gemini $code : ${text.take(300)}")
        val racine = runCatching { JSONObject(if (text.trimStart().startsWith("[")) JSONArray(text).getJSONObject(0).toString() else text) }
            .getOrElse { throw ApiException(502, "Gemini : réponse illisible") }
        val msg = racine.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw ApiException(502, "Gemini : réponse vide")
        val appels = msg.optJSONArray("tool_calls")
        if (appels != null) for (i in 0 until appels.length()) appels.optJSONObject(i)?.let { a ->
            if (a.optString("id").isBlank()) a.put("id", "call_" + System.nanoTime().toString(36) + i)
            if (!a.has("type")) a.put("type", "function")
        }
        if (msg.has("content") && !msg.isNull("content")) msg.put("content", nettoyerRaisonnement(msg.optString("content")))
        if ((appels == null || appels.length() == 0) && msg.optString("content").isBlank()) throw ApiException(502, "Gemini : réponse vide")
        return msg
    }

    companion object {
        /** Signature factice acceptée par Gemini 3 pour un appel d'outil qu'il n'a pas produit lui-même (historique Groq…). */
        private const val SIGNATURE_NEUTRE = "skip_thought_signature_validator"

        /** Messages au format accepté par Gemini : champs connus seulement, texte simple, nom de l'outil sur ses
         *  résultats, signature de réflexion sur chaque appel d'outil. */
        internal fun messagesPourGemini(messages: JSONArray): JSONArray {
            val noms = mutableMapOf<String, String>()
            val out = JSONArray()
            for (i in 0 until messages.length()) {
                val m = messages.optJSONObject(i) ?: continue
                val role = m.optString("role").let { if (it == "developer") "system" else it }
                val c = JSONObject().put("role", role)
                val texte = when (val x = m.opt("content")) {
                    is String -> x
                    is JSONArray -> (0 until x.length()).mapNotNull { x.optJSONObject(it) }.filter { it.optString("type") in setOf("text", "input_text") }.joinToString("\n") { it.optString("text") }
                    null, JSONObject.NULL -> ""
                    else -> x.toString()
                }
                c.put("content", texte)
                m.optJSONArray("tool_calls")?.takeIf { it.length() > 0 }?.let { a ->
                    val appels = JSONArray()
                    for (j in 0 until a.length()) {
                        val t = JSONObject(a.optJSONObject(j)?.toString() ?: continue)
                        val f = t.optJSONObject("function") ?: continue
                        if (t.optString("id").isNotBlank()) noms[t.optString("id")] = f.optString("name")
                        val g = t.optJSONObject("extra_content")?.optJSONObject("google")
                        if (g == null || g.optString("thought_signature").isBlank())
                            t.put("extra_content", JSONObject().put("google", JSONObject().put("thought_signature", SIGNATURE_NEUTRE)))
                        if (!t.has("type")) t.put("type", "function")
                        appels.put(t)
                    }
                    if (appels.length() > 0) c.put("tool_calls", appels)
                }
                if (role == "tool") {
                    val id = m.optString("tool_call_id")
                    c.put("tool_call_id", id)
                    (m.optString("name").ifBlank { noms[id].orEmpty() }).takeIf { it.isNotBlank() }?.let { c.put("name", it) }
                    if (texte.isBlank()) c.put("content", "(vide)")
                }
                out.put(c)
            }
            return out
        }

        private val CLES_REFUSEES = setOf("\$schema", "\$id", "additionalProperties", "strict", "examples", "default", "\$comment", "patternProperties", "unevaluatedProperties")

        /** Schémas d'outils ramenés au sous-ensemble accepté par Gemini (pas d'additionalProperties, types simples…). */
        internal fun outilsPourGemini(tools: JSONArray?): JSONArray? {
            if (tools == null) return null
            val out = JSONArray()
            for (i in 0 until tools.length()) {
                val t = JSONObject(tools.optJSONObject(i)?.toString() ?: continue)
                val f = t.optJSONObject("function") ?: continue
                f.remove("strict")
                f.optJSONObject("parameters")?.let { p ->
                    val propre = schema(p)
                    if (propre.optJSONObject("properties")?.length() ?: 0 == 0) f.remove("parameters") else f.put("parameters", propre)
                }
                out.put(t)
            }
            return out
        }

        private fun schema(o: JSONObject): JSONObject {
            val c = JSONObject()
            for (k in o.keys()) {
                if (k in CLES_REFUSEES) continue
                when (val v = o.get(k)) {
                    is JSONObject -> c.put(k, if (k == "properties") JSONObject().also { pr -> v.keys().forEach { n -> v.optJSONObject(n)?.let { pr.put(n, schema(it)) } } } else schema(v))
                    is JSONArray -> c.put(k, if (k == "type") v else JSONArray().also { a -> for (j in 0 until v.length()) a.put((v.opt(j) as? JSONObject)?.let { schema(it) } ?: v.opt(j)) })
                    else -> c.put(k, v)
                }
            }
            // « type »: ["string","null"] → type simple + nullable ; « const » → enum.
            (c.opt("type") as? JSONArray)?.let { a ->
                val types = (0 until a.length()).map { a.optString(it) }
                c.put("type", types.firstOrNull { it != "null" } ?: "string")
                if ("null" in types) c.put("nullable", true)
            }
            if (c.has("const")) { c.put("enum", JSONArray().put(c.get("const").toString())); c.remove("const") }
            // Gemini refuse un objet sans propriétés : on le décrit comme texte JSON.
            if (c.optString("type") == "object" && (c.optJSONObject("properties")?.length() ?: 0) == 0 && !c.has("anyOf")) {
                c.put("type", "string"); c.remove("properties"); c.remove("required")
                c.put("description", (c.optString("description") + " (objet JSON)").trim())
            }
            return c
        }
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

    /** Vision rapide (pilotage de l'écran) : consigne système + image, réflexion minimale, réponse JSON. */
    suspend fun visionJson(system: String, bytes: ByteArray, mimeType: String, question: String): String =
        ring.run { key -> withContext(Dispatchers.IO) { callVision(key, system, bytes, mimeType, question, true) } }

    private fun callVision(key: String, system: String, bytes: ByteArray, mimeType: String, question: String, thinkingOff: Boolean): String {
        val gen = JSONObject().put("temperature", 0.2).put("maxOutputTokens", 2048).put("responseMimeType", "application/json")
        if (thinkingOff) {
            val m = model()
            when {
                m.contains("2.5") -> gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                m.contains("gemini-3") -> gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            }
        }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val body = JSONObject().put("contents", JSONArray().put(
            JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mimeType).put("data", b64)))
                .put(JSONObject().put("text", question)))
        )).put("generationConfig", gen)
        if (system.isNotBlank()) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/${model()}:generateContent")
            .header("x-goog-api-key", key).post(body.toString().toRequestBody(Http.JSON)).build()
        val (code, text) = Http.client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        if (code == 400 && thinkingOff && text.contains("think", ignoreCase = true)) return callVision(key, system, bytes, mimeType, question, false)
        if (code !in 200..299) throw ApiException(code, "Gemini $code : ${text.take(300)}")
        val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
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
