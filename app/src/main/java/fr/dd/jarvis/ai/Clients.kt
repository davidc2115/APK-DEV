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
        val m = client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, "Groq ${resp.code} : ${text.take(300)}")
            val choix = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            val msg = choix.getJSONObject("message")
            if (msg.has("content") && !msg.isNull("content")) msg.put("content", nettoyerRaisonnement(msg.optString("content")))
            if (choix.optString("finish_reason") == "length") msg.put("_coupe", true)
            msg
        }
        // Réponse coupée par la limite de sortie au milieu d'un appel d'outil (commande, fichier) : jamais exécutée telle
        // quelle — on redemande avec plus de place, puis on abandonne ce fournisseur si ça ne suffit pas.
        if (m.optBoolean("_coupe") && (m.optJSONArray("tool_calls")?.length() ?: 0) > 0) {
            if (maxTokens != 1 && out < 8192) return call(key, messages, tools, maxTokens, client, minOf(out * 2, 8192), forceText)
            throw ApiException(502, "Groq : réponse coupée au milieu d'un appel d'outil (limite de sortie atteinte)")
        }
        m.remove("_coupe")
        return m
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
/**
 * Modèle Gemini utilisable PAR CLÉ : certaines clés (comptes / projets différents) n'ont pas accès au modèle choisi
 * dans les Réglages et Google répond 403 / 404 alors que la clé est valide. On retient alors, pour cette clé seulement,
 * le meilleur modèle qu'elle peut utiliser (6 h), au lieu de la déclarer « refusée ».
 */
object GeminiModeles {
    private val parCle = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val PREFERES = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-flash-latest", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash")

    /** Modèle à utiliser pour [key] : celui des Réglages, sauf s'il a été refusé à CETTE clé (le remplaçant est retenu
     *  pour ce modèle-là seulement : choisir un autre modèle dans les Réglages s'applique tout de suite). */
    fun pour(key: String, defaut: String): String {
        val d = defaut.trim().removePrefix("models/")
        return parCle["$key|$d"]?.takeIf { it.second > System.currentTimeMillis() }?.first ?: d
    }

    /** Erreur due au modèle (pas à la clé). */
    fun modeleInaccessible(code: Int, message: String): Boolean = when (code) {
        404 -> true
        403 -> !KeyRing.cleRefusee(code, message)
        400 -> Regex("model.*(not found|not supported|unavailable|does not exist)|is not found for API version", RegexOption.IGNORE_CASE).containsMatchIn(message)
        else -> false
    }

    /** Meilleur autre modèle accessible à cette clé (liste demandée à Google), retenu 6 h. */
    fun secours(key: String, refuse: String): String? = runCatching {
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").header("x-goog-api-key", key).build()
        val modeles = Http.fast.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@runCatching null
            val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("models") ?: return@runCatching null
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true }
                .map { it.optString("name").removePrefix("models/") }
                .filter { id -> id != refuse && listOf("image", "tts", "live", "audio", "embed", "robotics", "veo", "imagen").none { id.contains(it, true) } }
        }
        (PREFERES.firstOrNull { it in modeles } ?: modeles.filter { it.contains("flash") && !it.contains("exp") }.maxOrNull())
            ?.also { parCle["$key|${refuse.trim().removePrefix("models/")}"] = it to System.currentTimeMillis() + 6 * 3_600_000L }
    }.getOrNull()
}

class GeminiChatClient(private val ring: KeyRing, private val model: () -> String) {
    private val url = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"

    /** Façon d'appeler Gemini : API native (generateContent), ou point d'accès compatible OpenAI (Bearer / en-tête Google). */
    enum class Voie(val libelle: String) { NATIF("API native"), OPENAI_BEARER("compatible OpenAI (Bearer)"), OPENAI_CLE("compatible OpenAI (en-tête)") }

    /** Voie qui a marché en dernier pour chaque clé. */
    private val voieOk = java.util.concurrent.ConcurrentHashMap<String, Voie>()

    /**
     * Ordre des voies : celle qui a déjà marché, sinon l'API native d'abord pour les nouvelles clés « AQ. » (clés
     * d'authentification : le point d'accès compatible OpenAI les refuse ou les traite mal), le compatible OpenAI sinon.
     */
    internal fun voies(key: String): List<Voie> {
        val base = if (key.startsWith("AQ.")) listOf(Voie.NATIF, Voie.OPENAI_BEARER, Voie.OPENAI_CLE) else listOf(Voie.OPENAI_BEARER, Voie.OPENAI_CLE, Voie.NATIF)
        val ordre = voieOk[key]?.let { v -> listOf(v) + (base - v) } ?: base
        // Voies que Google refuse pour CETTE clé alors qu'une autre marche (ex. clé « AQ. » + en-tête sur le point compatible
        // OpenAI) : inutile de les retenter — et surtout leur refus ne doit pas faire passer la clé pour invalide.
        val maintenant = System.currentTimeMillis()
        val ko = voiesKo[key].orEmpty().filterValues { it > maintenant }.keys
        return ordre.filter { it !in ko }.ifEmpty { ordre }
    }

    /** Voies refusées pour une clé (voie → jusqu'à quand), alors qu'une autre voie de la même clé fonctionne. */
    private val voiesKo = java.util.concurrent.ConcurrentHashMap<String, MutableMap<Voie, Long>>()
    private fun voieKo(key: String, v: Voie) { voiesKo.getOrPut(key) { java.util.concurrent.ConcurrentHashMap() }[v] = System.currentTimeMillis() + 12 * 3_600_000L }

    fun disponible() = ring.count() > 0

    suspend fun chat(messages: JSONArray, tools: JSONArray?, out: Int = 2048, forceText: Boolean = false): JSONObject =
        ring.run { key -> withContext(Dispatchers.IO) { appel(key, messages, tools, out, forceText) } }

    /** Test réel d'une clé sur ce point d'accès (celui qu'utilisent Jarvis et Hermès), en une réponse très courte. */
    suspend fun ping(key: String): String = withContext(Dispatchers.IO) {
        val m = appel(key, JSONArray().put(JSONObject().put("role", "user").put("content", "Réponds juste : ok")), null, 256, false)
        if (m.optString("content").isBlank() && m.optJSONArray("tool_calls") == null) throw ApiException(502, "Gemini : réponse vide")
        GeminiModeles.pour(key, model().ifBlank { "gemini-2.5-flash" }.removePrefix("models/"))
    }

    /** Voie retenue pour cette clé (après un appel réussi). */
    fun voie(key: String): Voie? = voieOk[key]

    /** Test de CHAQUE voie pour une clé : (voie, null si OK sinon l'erreur). */
    suspend fun diagnostic(key: String): List<Pair<Voie, ApiException?>> = withContext(Dispatchers.IO) {
        val m = GeminiModeles.pour(key, model().ifBlank { "gemini-2.5-flash" }.removePrefix("models/"))
        val msg = JSONArray().put(JSONObject().put("role", "user").put("content", "Réponds juste : ok"))
        val r = Voie.values().map { v -> v to try { essai(v, key, m, msg, null, 256, false); null } catch (e: ApiException) { e } }
        val bonnes = r.filter { it.second == null }.map { it.first }
        if (bonnes.isNotEmpty()) {
            voieOk[key] = voies(key).firstOrNull { it in bonnes } ?: bonnes.first()
            r.filter { it.second != null && voieRefusee(it.second!!) }.forEach { voieKo(key, it.first) }
            r.filter { it.second == null }.forEach { voiesKo[key]?.remove(it.first) }
        }
        r
    }

    /** L'erreur vient de la FAÇON d'appeler (authentification, adresse) et pas du contenu de la requête. */
    private fun voieRefusee(e: ApiException): Boolean = e.code in setOf(401, 403) || (e.code == 400 && (Http.refusLocalisation(e.message.orEmpty()) ||
        Regex("credential|authenticat|api key|API_KEY|ACCESS_TOKEN", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())))

    /** Requête refusée à cause des schémas d'outils (imbrication trop profonde, champ inconnu, valeur invalide). */
    private fun schemaRefuse(e: ApiException): Boolean = e.code == 400 &&
        Regex("nesting depth|FunctionDeclaration|function_declarations|functionDeclarations|parameters|schema|Invalid JSON payload|Unknown name|Invalid value|tools\\[", RegexOption.IGNORE_CASE)
            .containsMatchIn(e.message.orEmpty())

    private fun essai(v: Voie, key: String, mdl: String, messages: JSONArray, tools: JSONArray?, out: Int, forceText: Boolean, profondeur: Int = 0): JSONObject {
        // Profondeur limitée : les paramètres trop imbriqués deviennent un texte JSON, reconverti dans la réponse.
        val aplatis = HashMap<String, MutableSet<String>>()
        val outils = if (profondeur > 0 && tools != null) aplatir(tools, profondeur, aplatis) else tools
        val m = when (v) {
            Voie.NATIF -> callNatif(key, mdl, messages, outils, out, forceText, true)
            Voie.OPENAI_BEARER -> call(key, mdl, messages, outils, out, forceText, true, true)
            Voie.OPENAI_CLE -> call(key, mdl, messages, outils, out, forceText, true, false)
        }
        if (aplatis.isNotEmpty()) restaurer(m, aplatis)
        return m
    }

    /** Essai d'une voie ; si Google refuse les schémas d'outils, nouvel essai avec des schémas de moins en moins imbriqués. */
    private fun essaiSouple(v: Voie, key: String, mdl: String, messages: JSONArray, tools: JSONArray?, out: Int, forceText: Boolean): JSONObject {
        try { return essai(v, key, mdl, messages, tools, out, forceText) }
        catch (e: ApiException) {
            if (tools == null || tools.length() == 0 || !schemaRefuse(e)) throw e
            var derniere = e
            for (p in listOf(4, 3, 2)) {
                try { return essai(v, key, mdl, messages, tools, out, forceText, p) }
                catch (e2: ApiException) { derniere = e2; if (!schemaRefuse(e2)) throw e2 }
            }
            throw derniere
        }
    }

    /**
     * Appel avec ses replis : les autres voies (API native ↔ compatible OpenAI, Bearer ↔ en-tête Google) si Google refuse
     * l'authentification ou l'adresse, puis un autre modèle si celui des Réglages n'est pas ouvert à cette clé.
     */
    private fun appel(key: String, messages: JSONArray, tools: JSONArray?, out: Int, forceText: Boolean): JSONObject {
        val principal = GeminiModeles.pour(key, model().ifBlank { "gemini-2.5-flash" }.removePrefix("models/"))
        val erreurs = mutableListOf<Pair<Voie, ApiException>>()
        for (v in voies(key)) {
            try { return essaiSouple(v, key, principal, messages, tools, out, forceText).also { voieOk[key] = v; voiesKo[key]?.remove(v) } }
            catch (e: ApiException) {
                erreurs += v to e
                val m = e.message.orEmpty()
                if (GeminiModeles.modeleInaccessible(e.code, m)) {
                    val autre = GeminiModeles.secours(key, principal) ?: throw e
                    return essaiSouple(v, key, autre, messages, tools, out, forceText).also { voieOk[key] = v }
                }
                // Autre voie seulement si c'est la façon d'appeler (authentification, adresse) ou le format qui est refusé.
                if (!voieRefusee(e) && !schemaRefuse(e)) throw e
            }
        }
        // Aucune voie n'a marché. Ne JAMAIS faire passer la clé pour refusée à cause d'une voie secondaire : si la clé a déjà
        // marché par une voie (test ou appel précédent), les refus d'authentification des autres voies sont des refus de voie.
        val connueBonne = voieOk[key] != null
        if (connueBonne) erreurs.filter { voieRefusee(it.second) && it.first != voieOk[key] }.forEach { voieKo(key, it.first) }
        val utile = erreurs.firstOrNull { !voieRefusee(it.second) }?.second
            ?: erreurs.firstOrNull { it.first == voieOk[key] }?.second
        if (utile != null) throw ApiException(if (KeyRing.cleRefusee(utile.code, utile.message.orEmpty()) && connueBonne) 503 else utile.code, utile.message.orEmpty())
        val derniere = erreurs.lastOrNull()?.second ?: throw ApiException(502, "Gemini : aucune voie")
        if (connueBonne) throw ApiException(503, "Gemini : appel refusé sur toutes les voies alors que la clé fonctionnait (${KeyRing.motif(derniere.code, derniere.message.orEmpty())})")
        throw derniere
    }

    /** Appel par l'API native (generateContent) : messages et outils OpenAI convertis, réponse reconvertie au format OpenAI. */
    private fun callNatif(key: String, mdl: String, messages: JSONArray, tools: JSONArray?, out: Int, forceText: Boolean, reflexionReduite: Boolean): JSONObject {
        val (systeme, contenus) = versNatif(messagesPourGemini(messages))
        val gen = JSONObject().put("temperature", 0.4).put("maxOutputTokens", out.coerceIn(256, 16384))
        if (reflexionReduite) when {
            mdl.contains("2.5") && !mdl.contains("pro") -> gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            mdl.contains("gemini-3") -> gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }
        val body = JSONObject().put("contents", contenus).put("generationConfig", gen)
        if (systeme.isNotBlank()) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systeme))))
        val decl = declarations(outilsPourGemini(tools))
        if (decl.length() > 0) {
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decl)))
            body.put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", if (forceText) "NONE" else "AUTO")))
        }
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$mdl:generateContent")
            .header("x-goog-api-key", key).post(body.toString().toRequestBody(Http.JSON)).build()
        val (code, text) = GeminiPasserelle.executer(Http.client, req)
        if (code == 400 && reflexionReduite && Regex("think", RegexOption.IGNORE_CASE).containsMatchIn(text)) return callNatif(key, mdl, messages, tools, out, forceText, false)
        if (code !in 200..299) throw ApiException(code, "Gemini $code : ${text.take(300)}")
        val m = depuisNatif(text)
        // Coupé par la limite de sortie pendant un appel d'outil : redemandé avec plus de place, jamais exécuté tronqué.
        val coupe = runCatching { JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason") == "MAX_TOKENS" }.getOrDefault(false)
        if (coupe && (m.optJSONArray("tool_calls")?.length() ?: 0) > 0) {
            if (out < 16384) return callNatif(key, mdl, messages, tools, minOf(out * 2, 16384), forceText, reflexionReduite)
            throw ApiException(502, "Gemini : réponse coupée au milieu d'un appel d'outil (limite de sortie atteinte)")
        }
        return m
    }

    private fun call(key: String, mdl: String, messages: JSONArray, tools: JSONArray?, out: Int, forceText: Boolean, reflexionReduite: Boolean, bearer: Boolean): JSONObject {
        val body = JSONObject().put("model", mdl).put("messages", messagesPourGemini(messages)).put("temperature", 0.4)
            // La réflexion de Gemini compte dans les jetons de sortie : marge pour que la réponse ne soit pas coupée.
            // Jusqu'à 8192 : un appel d'outil qui écrit un fichier complet porte tout le fichier dans la sortie.
            .put("max_tokens", out.coerceIn(256, 16384))
        val outils = outilsPourGemini(tools)
        if (outils != null && outils.length() > 0) { body.put("tools", outils); body.put("tool_choice", if (forceText) "none" else "auto") }
        if (reflexionReduite) when {
            mdl.contains("2.5") && !mdl.contains("pro") -> body.put("reasoning_effort", "none")
            mdl.contains("gemini-3") -> body.put("reasoning_effort", "low")
        }
        val req = Request.Builder().url(url).apply { if (bearer) header("Authorization", "Bearer $key") else header("x-goog-api-key", key) }
            .post(body.toString().toRequestBody(Http.JSON)).build()
        val (code, text) = GeminiPasserelle.executer(Http.client, req)
        if (code == 400 && reflexionReduite && Regex("reasoning|think", RegexOption.IGNORE_CASE).containsMatchIn(text)) return call(key, mdl, messages, tools, out, forceText, false, bearer)
        if (code !in 200..299) throw ApiException(code, "Gemini $code : ${text.take(300)}")
        val racine = runCatching { JSONObject(if (text.trimStart().startsWith("[")) JSONArray(text).getJSONObject(0).toString() else text) }
            .getOrElse { throw ApiException(502, "Gemini : réponse illisible") }
        val msg = racine.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw ApiException(502, "Gemini : réponse vide")
        val appels = msg.optJSONArray("tool_calls")
        if (racine.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason") == "length" && (appels?.length() ?: 0) > 0) {
            if (out < 16384) return call(key, mdl, messages, tools, minOf(out * 2, 16384), forceText, reflexionReduite, bearer)
            throw ApiException(502, "Gemini : réponse coupée au milieu d'un appel d'outil (limite de sortie atteinte)")
        }
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

        /** Messages OpenAI (déjà nettoyés) → (consigne système, contents de l'API native). */
        internal fun versNatif(messages: JSONArray): Pair<String, JSONArray> {
            val systeme = StringBuilder()
            val contenus = mutableListOf<Pair<String, JSONArray>>()
            fun ajouter(role: String, part: JSONObject) {
                val dernier = contenus.lastOrNull()
                if (dernier != null && dernier.first == role) dernier.second.put(part) else contenus += role to JSONArray().put(part)
            }
            for (i in 0 until messages.length()) {
                val m = messages.optJSONObject(i) ?: continue
                val texte = m.optString("content")
                when (m.optString("role")) {
                    "system" -> if (texte.isNotBlank()) systeme.append(if (systeme.isEmpty()) "" else "\n\n").append(texte)
                    "assistant" -> {
                        if (texte.isNotBlank()) ajouter("model", JSONObject().put("text", texte))
                        m.optJSONArray("tool_calls")?.let { a -> for (j in 0 until a.length()) {
                            val t = a.optJSONObject(j) ?: continue
                            val f = t.optJSONObject("function") ?: continue
                            val args = runCatching { JSONObject(f.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                            val part = JSONObject().put("functionCall", JSONObject().put("name", f.optString("name")).put("args", args))
                            t.optJSONObject("extra_content")?.optJSONObject("google")?.optString("thought_signature")?.takeIf { it.isNotBlank() }?.let { part.put("thoughtSignature", it) }
                            ajouter("model", part)
                        } }
                    }
                    "tool" -> ajouter("user", JSONObject().put("functionResponse", JSONObject().put("name", m.optString("name").ifBlank { "outil" })
                        .put("response", JSONObject().put("content", texte.ifBlank { "(vide)" }))))
                    else -> ajouter("user", JSONObject().put("text", texte.ifBlank { "…" }))
                }
            }
            // La conversation doit commencer par l'utilisateur.
            if (contenus.firstOrNull()?.first == "model") contenus.add(0, "user" to JSONArray().put(JSONObject().put("text", "(suite de la conversation)")))
            if (contenus.isEmpty()) contenus += "user" to JSONArray().put(JSONObject().put("text", "…"))
            return systeme.toString() to JSONArray().also { out -> contenus.forEach { (r, p) -> out.put(JSONObject().put("role", r).put("parts", p)) } }
        }

        /** Outils OpenAI (nettoyés par [outilsPourGemini]) → functionDeclarations de l'API native (types en MAJUSCULES). */
        internal fun declarations(outils: JSONArray?): JSONArray {
            val out = JSONArray()
            if (outils == null) return out
            for (i in 0 until outils.length()) {
                val f = outils.optJSONObject(i)?.optJSONObject("function") ?: continue
                val d = JSONObject().put("name", f.optString("name")).put("description", f.optString("description"))
                f.optJSONObject("parameters")?.let { d.put("parameters", schemaNatif(it)) }
                out.put(d)
            }
            return out
        }

        private val CLES_NATIVES = setOf("type", "format", "description", "nullable", "enum", "items", "properties", "required", "anyOf", "minimum", "maximum", "minItems", "maxItems", "title")
        /** Formats acceptés par l'API native (les autres — uri, email, date… — font refuser toute la requête). */
        private val FORMATS_NATIFS = setOf("enum", "date-time", "int32", "int64", "float", "double")

        /**
         * Outils dont les paramètres plus imbriqués que [max] niveaux (objet = 1 niveau, tableau = 1 niveau) sont décrits
         * comme un texte JSON ; [aplatis] reçoit, par outil, les chemins concernés (« lignes[] », « options.filtre »).
         */
        internal fun aplatir(tools: JSONArray, max: Int, aplatis: MutableMap<String, MutableSet<String>>): JSONArray {
            val out = JSONArray()
            for (i in 0 until tools.length()) {
                val t = JSONObject(tools.optJSONObject(i)?.toString() ?: continue)
                val f = t.optJSONObject("function")
                val p = f?.optJSONObject("parameters")
                if (f != null && p != null) {
                    val chemins = mutableSetOf<String>()
                    f.put("parameters", aplatirSchema(p, 1, max, "", chemins))
                    if (chemins.isNotEmpty()) aplatis[f.optString("name")] = chemins
                }
                out.put(t)
            }
            return out
        }

        private fun aplatirSchema(o: JSONObject, niveau: Int, max: Int, chemin: String, chemins: MutableSet<String>): JSONObject {
            val type = o.optString("type").lowercase()
            val compose = type == "object" || type == "array" || o.has("properties") || o.has("items")
            if (compose && niveau > max && chemin.isNotEmpty()) {
                chemins += chemin
                return JSONObject().put("type", "string").put("description", (o.optString("description") + " (texte JSON : " +
                    (if (type == "array" || o.has("items")) "tableau" else "objet") + ")").trim())
            }
            val c = JSONObject(o.toString())
            o.optJSONObject("properties")?.let { pr ->
                val np = JSONObject()
                pr.keys().forEach { n -> pr.optJSONObject(n)?.let { np.put(n, aplatirSchema(it, niveau + 1, max, if (chemin.isEmpty()) n else "$chemin.$n", chemins)) } }
                c.put("properties", np)
            }
            o.optJSONObject("items")?.let { c.put("items", aplatirSchema(it, niveau + 1, max, "$chemin[]", chemins)) }
            return c
        }

        /** Remet en JSON les arguments que le modèle a donnés sous forme de texte pour les paramètres aplatis. */
        internal fun restaurer(message: JSONObject, aplatis: Map<String, Set<String>>) {
            val appels = message.optJSONArray("tool_calls") ?: return
            for (i in 0 until appels.length()) {
                val f = appels.optJSONObject(i)?.optJSONObject("function") ?: continue
                val chemins = aplatis[f.optString("name")] ?: continue
                val args = runCatching { JSONObject(f.optString("arguments").ifBlank { "{}" }) }.getOrNull() ?: continue
                fun visiter(v: Any?, chemin: String): Any? = when {
                    chemin in chemins && v is String -> runCatching { val t = v.trim(); if (t.startsWith("[")) JSONArray(t) else JSONObject(t) }.getOrDefault(v)
                    v is JSONObject -> { v.keys().asSequence().toList().forEach { k -> v.put(k, visiter(v.opt(k), if (chemin.isEmpty()) k else "$chemin.$k")) }; v }
                    v is JSONArray -> { for (j in 0 until v.length()) v.put(j, visiter(v.opt(j), "$chemin[]")); v }
                    else -> v
                }
                visiter(args, "")
                f.put("arguments", args.toString())
            }
        }

        private fun schemaNatif(o: JSONObject): JSONObject {
            val c = JSONObject()
            for (k in o.keys()) {
                if (k !in CLES_NATIVES) continue
                when (val v = o.get(k)) {
                    is JSONObject -> c.put(k, if (k == "properties") JSONObject().also { pr -> v.keys().forEach { n -> v.optJSONObject(n)?.let { pr.put(n, schemaNatif(it)) } } } else schemaNatif(v))
                    is JSONArray -> c.put(k, JSONArray().also { a -> for (j in 0 until v.length()) a.put((v.opt(j) as? JSONObject)?.let { schemaNatif(it) } ?: v.opt(j).toString()) })
                    is String -> c.put(k, if (k == "type") v.uppercase() else v)
                    else -> c.put(k, v)
                }
            }
            if (c.has("enum") && c.optString("type", "STRING") != "STRING") c.remove("enum")
            if (!c.has("type") && !c.has("anyOf")) c.put("type", if (c.has("properties")) "OBJECT" else "STRING")
            if (c.has("format") && c.optString("format") !in FORMATS_NATIFS) c.remove("format")
            // « required » ne doit citer que des propriétés décrites, sinon Google refuse toute la requête.
            c.optJSONArray("required")?.let { r ->
                val props = c.optJSONObject("properties")
                val gardes = (0 until r.length()).map { r.optString(it) }.filter { props?.has(it) == true }
                if (gardes.isEmpty()) c.remove("required") else c.put("required", JSONArray(gardes))
            }
            if (c.optString("type") == "ARRAY" && !c.has("items")) c.put("items", JSONObject().put("type", "STRING"))
            return c
        }

        /** Réponse de l'API native → message au format OpenAI (texte, tool_calls avec leur signature de réflexion). */
        internal fun depuisNatif(texte: String): JSONObject {
            val racine = runCatching { JSONObject(texte) }.getOrElse { throw ApiException(502, "Gemini : réponse illisible") }
            val cand = racine.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw ApiException(502, "Gemini : réponse vide" + (racine.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let { " (bloquée : $it)" } ?: ""))
            val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val sb = StringBuilder()
            val appels = JSONArray()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                p.optJSONObject("functionCall")?.let { f ->
                    val appel = JSONObject().put("id", f.optString("id").ifBlank { "call_" + System.nanoTime().toString(36) + i }).put("type", "function")
                        .put("function", JSONObject().put("name", f.optString("name")).put("arguments", (f.optJSONObject("args") ?: JSONObject()).toString()))
                    p.optString("thoughtSignature").takeIf { it.isNotBlank() }?.let { appel.put("extra_content", JSONObject().put("google", JSONObject().put("thought_signature", it))) }
                    appels.put(appel)
                } ?: run { if (!p.optBoolean("thought")) sb.append(p.optString("text")) }
            }
            val msg = JSONObject().put("role", "assistant").put("content", if (sb.isBlank()) JSONObject.NULL else nettoyerRaisonnement(sb.toString()))
            if (appels.length() > 0) msg.put("tool_calls", appels)
            if (appels.length() == 0 && sb.isBlank()) throw ApiException(502, "Gemini : réponse vide (${cand.optString("finishReason")})")
            return msg
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

    /** Client pour les longues rédactions (site web complet…) : jusqu'à 4 min. */
    private val long = Http.client.newBuilder().callTimeout(240, java.util.concurrent.TimeUnit.SECONDS).readTimeout(240, java.util.concurrent.TimeUnit.SECONDS).build()

    /** Longue réponse (jusqu'à [maxTokens] jetons, plafond 60 000) : sites web, documents complets. */
    suspend fun generateLong(system: String, user: String, maxTokens: Int = 48_000): String =
        ring.run { key -> withContext(Dispatchers.IO) {
            val principal = GeminiModeles.pour(key, model())
            try { call(key, system, user, maxTokens, m = principal, plafond = 60_000, client = long) } catch (e: ApiException) {
                if (!GeminiModeles.modeleInaccessible(e.code, e.message.orEmpty())) throw e
                call(key, system, user, maxTokens, m = GeminiModeles.secours(key, principal) ?: throw e, plafond = 60_000, client = long)
            }
        } }

    suspend fun generate(system: String, user: String): String =
        ring.run { key -> withContext(Dispatchers.IO) {
            val principal = GeminiModeles.pour(key, model())
            try { call(key, system, user, 2048, m = principal) } catch (e: ApiException) {
                if (!GeminiModeles.modeleInaccessible(e.code, e.message.orEmpty())) throw e
                call(key, system, user, 2048, m = GeminiModeles.secours(key, principal) ?: throw e)
            }
        } }

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
        val (code, text) = GeminiPasserelle.executer(Http.fast, req)
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
        val (code, text) = GeminiPasserelle.executer(Http.client, req)
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

    private fun call(key: String, system: String, user: String, maxTokens: Int, thinkingOff: Boolean = true, m: String = model(),
                     plafond: Int = 2048, client: okhttp3.OkHttpClient = Http.fast): String {
        val gen = JSONObject().put("temperature", 0.3).put("maxOutputTokens", maxTokens.coerceIn(8, plafond))
        if (thinkingOff) {
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
            .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        val (code, text) = GeminiPasserelle.executer(client, req)
        // Certains modèles refusent le réglage de réflexion : on réessaie sans.
        if (code == 400 && thinkingOff && text.contains("think", ignoreCase = true)) return call(key, system, user, maxTokens, false, m, plafond, client)
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
