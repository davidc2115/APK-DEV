package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Ollama du serveur de la maison : IA locale de SECOURS (quand Groq, Gemini et Cloudflare échouent, ou sans internet)
 * et sous-agent d'Hermès (route « jarvis-ollama » via le relais du pont).
 *
 * Adresse : celle des réglages, sinon le serveur SSH sur le port 11434. Ollama n'écoute souvent que sur 127.0.0.1 du
 * serveur : si l'accès direct échoue, Jarvis passe par un tunnel SSH (aucune modification du serveur nécessaire).
 * API native /api/chat (pour fixer la taille du contexte), convertie vers/depuis le format OpenAI utilisé partout ailleurs.
 */
object Ollama {
    const val PORT = 11434
    /** Contexte demandé à Ollama : assez pour un prompt compacté + outils, raisonnable pour un petit serveur. */
    private const val CONTEXTE = 8192

    @Volatile internal var app: JarvisApp? = null

    private val sonde = OkHttpClient.Builder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(4, TimeUnit.SECONDS).build()
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(240, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS).build()

    @Volatile private var base: String? = null
    @Volatile private var baseA = 0L
    @Volatile private var echecA = 0L
    @Volatile private var modeleAuto: String? = null
    @Volatile var etat = "non vérifié"
        private set

    /** URL de base d'Ollama à partir de la saisie (vide = serveur SSH), avec http:// et le port 11434 par défaut. */
    internal fun urlDe(saisie: String, hoteSsh: String): String? {
        var u = saisie.trim().trimEnd('/').ifBlank { hoteSsh.trim() }
        if (u.isBlank()) return null
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
        u = u.removeSuffix("/v1").removeSuffix("/api").trimEnd('/')
        val schema = u.substringBefore("://")
        val reste = u.substringAfter("://")
        val hote = reste.substringBefore('/')
        val chemin = reste.removePrefix(hote)
        return "$schema://" + (if (hote.contains(':')) hote else "$hote:$PORT") + chemin
    }

    fun configure(app: JarvisApp) = app.store.ollamaActif.value && urlDe(app.store.ollamaUrl.value, app.store.sshHost.value) != null

    private fun repond(u: String) = runCatching {
        sonde.newCall(Request.Builder().url("$u/api/tags").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** Adresse joignable : directe, sinon par tunnel SSH vers le serveur. Mise en cache (et l'échec aussi, 5 min). */
    @Synchronized
    fun base(app: JarvisApp, force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!force) {
            base?.let { if (now - baseA < 120_000) return it }
            if (now - echecA < 5 * 60_000) return null
        }
        val direct = urlDe(app.store.ollamaUrl.value, app.store.sshHost.value) ?: return null.also { etat = "non configuré" }
        if (repond(direct)) { base = direct; baseA = now; etat = "joignable ($direct)"; return direct }
        val hote = direct.substringAfter("://").substringBefore('/').substringBeforeLast(':')
        val memeServeur = app.store.ollamaUrl.value.isBlank() || hote == app.store.sshHost.value.trim() || hote == app.store.sshHostDistant.value.trim()
        if (memeServeur && Serveur.sshConfigure(app)) {
            val portDistant = direct.substringAfter("://").substringBefore('/').substringAfterLast(':').toIntOrNull() ?: PORT
            val local = runCatching { Serveur.tunnelOllama(app, portDistant) }.getOrNull()
            if (local != null) {
                val t = "http://127.0.0.1:$local"
                if (repond(t)) { base = t; baseA = now; etat = "joignable par tunnel SSH (Ollama n'écoute que sur le serveur lui-même)"; return t }
            }
        }
        base = null; echecA = now
        etat = "injoignable sur $direct" + if (Serveur.sshConfigure(app)) " (ni en direct ni par tunnel SSH : Ollama est-il démarré ?)" else ""
        return null
    }

    fun modeles(app: JarvisApp, b: String? = base(app)): List<String> {
        b ?: return emptyList()
        return runCatching {
            sonde.newCall(Request.Builder().url("$b/api/tags").build()).execute().use { r ->
                val a = JSONObject(r.body?.string().orEmpty()).optJSONArray("models") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            }
        }.getOrDefault(emptyList())
    }

    /** Modèle de conversation à utiliser : de préférence une famille qui sait appeler des outils. */
    internal fun choisir(noms: List<String>): String? {
        val utiles = noms.filter { n -> listOf("embed", "bge", "minilm", "rerank", "llava", "whisper", "moondream").none { n.lowercase().contains(it) } }
        val preferes = listOf("qwen3", "qwen2.5", "llama3.1", "llama3.2", "mistral-nemo", "mistral-small", "mistral", "llama3.3", "granite3", "command-r", "phi4", "gemma3", "llama3")
        return preferes.firstNotNullOfOrNull { p -> utiles.firstOrNull { it.lowercase().startsWith(p) } } ?: utiles.firstOrNull()
    }

    fun modele(app: JarvisApp, b: String): String? =
        app.store.ollamaModele.value.trim().ifBlank { null } ?: modeleAuto ?: choisir(modeles(app, b))?.also { modeleAuto = it }

    /** Messages OpenAI → format natif Ollama (arguments d'outils en objet, nom de l'outil sur les résultats). */
    internal fun versNatif(messages: JSONArray): JSONArray {
        val out = JSONArray()
        val noms = mutableMapOf<String, String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role").let { if (it == "developer") "system" else it }
            val c = JSONObject().put("role", role).put("content", texte(m.opt("content")))
            m.optJSONArray("tool_calls")?.let { a ->
                val tc = JSONArray()
                for (k in 0 until a.length()) {
                    val o = a.optJSONObject(k) ?: continue
                    val f = o.optJSONObject("function") ?: continue
                    noms[o.optString("id")] = f.optString("name")
                    val args = when (val x = f.opt("arguments")) {
                        is JSONObject -> x
                        is String -> runCatching { JSONObject(x.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                        else -> JSONObject()
                    }
                    tc.put(JSONObject().put("function", JSONObject().put("name", f.optString("name")).put("arguments", args)))
                }
                if (tc.length() > 0) c.put("tool_calls", tc)
            }
            if (role == "tool") noms[m.optString("tool_call_id")]?.let { c.put("tool_name", it) }
            out.put(c)
        }
        return out
    }

    /** Message natif Ollama → message OpenAI (tool_calls avec id et arguments en texte JSON). */
    internal fun versOpenAi(m: JSONObject): JSONObject {
        val msg = JSONObject().put("role", "assistant")
        val brut = m.optString("content")
        val contenu = if (brut.isBlank()) "" else nettoyerRaisonnement(brut)
        val appels = m.optJSONArray("tool_calls")
        if (appels != null && appels.length() > 0) {
            val tc = JSONArray()
            for (k in 0 until appels.length()) {
                val f = appels.optJSONObject(k)?.optJSONObject("function") ?: continue
                val args = when (val x = f.opt("arguments")) { is JSONObject -> x.toString(); null -> "{}"; else -> x.toString() }
                tc.put(JSONObject().put("id", "call_ollama_${System.nanoTime()}_$k").put("type", "function")
                    .put("function", JSONObject().put("name", f.optString("name")).put("arguments", args)))
            }
            msg.put("tool_calls", tc)
            msg.put("content", if (contenu.isBlank()) JSONObject.NULL else contenu)
        } else msg.put("content", contenu)
        return msg
    }

    private fun texte(c: Any?): String = when (c) {
        is String -> c
        is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }.filter { it.optString("type") in setOf("text", "input_text") }.joinToString("\n") { it.optString("text") }
        else -> ""
    }

    /** Tour de conversation (format OpenAI en entrée et en sortie). Bloquant : à appeler hors du fil principal. */
    fun chat(app: JarvisApp, messages: JSONArray, tools: JSONArray?, maxTokens: Int = 1200): JSONObject {
        var derniere: Exception? = null
        for (essai in 0..1) {
            val b = base(app, force = essai > 0) ?: throw IllegalStateException("Ollama $etat")
            val modele = modele(app, b) ?: throw IllegalStateException("Aucun modèle de conversation dans Ollama (sur le serveur : ollama pull qwen2.5:7b).")
            try {
                return appel(b, modele, messages, tools, maxTokens)
            } catch (e: java.io.IOException) {
                // Tunnel tombé ou serveur redémarré : on revérifie l'adresse une fois.
                derniere = e; base = null
            }
        }
        throw IllegalStateException("Ollama : ${derniere?.message ?: "injoignable"}")
    }

    fun generer(app: JarvisApp, system: String, user: String): String =
        chat(app, JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)), null, 2048)
            .optString("content").trim()

    private fun appel(b: String, modele: String, messages: JSONArray, tools: JSONArray?, maxTokens: Int): JSONObject {
        fun corps(avecOutils: Boolean) = JSONObject().put("model", modele).put("messages", versNatif(messages)).put("stream", false)
            .put("keep_alive", "15m")
            .put("options", JSONObject().put("num_ctx", CONTEXTE).put("num_predict", maxTokens).put("temperature", 0.4))
            .also { if (avecOutils && tools != null && tools.length() > 0) it.put("tools", tools) }
        var (code, txt) = post(b, corps(true))
        // Modèle sans prise en charge des outils : on répond quand même, en texte.
        if (code == 400 && txt.contains("does not support tools", true)) post(b, corps(false)).let { code = it.first; txt = it.second }
        if (code !in 200..299) throw ApiException(code, "Ollama $code : ${txt.take(300)}")
        return versOpenAi(JSONObject(txt).optJSONObject("message") ?: JSONObject())
    }

    private fun post(b: String, corps: JSONObject): Pair<Int, String> =
        client.newCall(Request.Builder().url("$b/api/chat").post(corps.toString().toRequestBody(Http.JSON)).build()).execute()
            .use { it.code to it.body?.string().orEmpty() }

    /** Relais pour Hermès (http://127.0.0.1:<pont>/ollama/v1) : requête OpenAI compactée → Ollama → réponse OpenAI. */
    fun relais(app: JarvisApp, corps: JSONObject): Pair<Int, String> {
        if (!configure(app)) return 503 to fr.dd.jarvis.hermes.RelaisGroq.erreurJson("Ollama n'est pas configuré dans Jarvis.", "not_configured")
        val (msgs, outils) = fr.dd.jarvis.hermes.RelaisGroq.reduire(corps.optJSONArray("messages") ?: JSONArray(), corps.optJSONArray("tools"), 0)
        val sortie = corps.optInt("max_tokens", corps.optInt("max_completion_tokens", 1200)).coerceIn(256, 1500)
        return try {
            val m = chat(app, msgs, outils, sortie)
            200 to fr.dd.jarvis.hermes.RelaisGroq.completionDe(m, "ollama:" + (modeleAuto ?: app.store.ollamaModele.value.ifBlank { "auto" }), (msgs.toString().length + (outils?.toString()?.length ?: 0)) / 3)
        } catch (e: ApiException) {
            (if (e.code in 400..599) e.code else 502) to fr.dd.jarvis.hermes.RelaisGroq.erreurJson(e.message ?: "Ollama", "upstream_error")
        } catch (e: Exception) {
            502 to fr.dd.jarvis.hermes.RelaisGroq.erreurJson("Ollama : ${e.message ?: e.javaClass.simpleName}", "relay_error")
        }
    }

    fun modelesJson(app: JarvisApp): String = JSONObject().put("object", "list").put("data", JSONArray().also { a ->
        modeles(app).forEach { a.put(JSONObject().put("id", it).put("object", "model").put("owned_by", "ollama")) }
    }).toString()

    /** Test lisible pour les réglages et testerClesApi. */
    fun tester(app: JarvisApp): String {
        if (!app.store.ollamaActif.value) return "Ollama : désactivé."
        val b = base(app, force = true) ?: return "❌ Ollama : $etat."
        val noms = modeles(app, b)
        val m = modele(app, b) ?: return "⚠️ Ollama $etat, mais aucun modèle de conversation installé (sur le serveur : ollama pull qwen2.5:7b)."
        val debut = System.currentTimeMillis()
        val rep = runCatching { generer(app, "Réponds en un mot.", "Dis bonjour.") }
        val s = (System.currentTimeMillis() - debut) / 1000
        return rep.fold(
            { "✅ Ollama $etat · modèle $m (réponse en $s s)" + (if (noms.size > 1) " · installés : ${noms.joinToString(", ")}" else "") },
            { "⚠️ Ollama $etat · modèle $m, mais l'essai a échoué : ${it.message}" })
    }
}
