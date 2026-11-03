package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Gestion Lovelace en mode stockage via l'API WebSocket officielle de Home Assistant. */
object HomeAssistantDashboard {
    private val ids = AtomicInteger(1)
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build()

    private class Session(private val app: JarvisApp) : WebSocketListener(), AutoCloseable {
        private val incoming = ArrayBlockingQueue<String>(32)
        private var ws: WebSocket? = null
        override fun onMessage(webSocket: WebSocket, text: String) { incoming.offer(text) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { incoming.offer(JSONObject().put("_failure", t.message ?: "WebSocket indisponible").toString()) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.offer(JSONObject().put("_failure", "Connexion fermée : $reason").toString()) }
        private fun next(): JSONObject {
            val o = JSONObject(incoming.poll(35, TimeUnit.SECONDS) ?: throw IllegalStateException("Home Assistant ne répond pas à l'API WebSocket."))
            if (o.has("_failure")) throw IllegalStateException(o.optString("_failure"))
            return o
        }
        fun connect() {
            if (!Serveur.haConfigure(app)) throw IllegalStateException("Home Assistant non configuré : adresse et jeton requis dans Réglages › Serveur.")
            val base = app.store.haUrl.value.trim().trimEnd('/')
            val url = (if (base.startsWith("https://")) "wss://" + base.removePrefix("https://") else "ws://" + base.removePrefix("http://")) + "/api/websocket"
            ws = client.newWebSocket(Request.Builder().url(url).build(), this)
            if (next().optString("type") != "auth_required") throw IllegalStateException("Réponse WebSocket Home Assistant inattendue.")
            ws!!.send(JSONObject().put("type", "auth").put("access_token", app.store.haToken.value.trim()).toString())
            val auth = next()
            if (auth.optString("type") != "auth_ok") throw IllegalStateException("Authentification WebSocket refusée : ${auth.optString("message", auth.optString("type"))}")
        }
        fun command(type: String, args: JSONObject = JSONObject()): JSONObject {
            val id = ids.getAndIncrement()
            if (ws?.send(JSONObject(args.toString()).put("id", id).put("type", type).toString()) != true) throw IllegalStateException("Envoi impossible : $type.")
            repeat(16) {
                val r = next()
                if (r.optInt("id", -1) == id) {
                    if (!r.optBoolean("success", false)) throw IllegalStateException("Home Assistant refuse $type : ${r.optJSONObject("error")?.optString("message") ?: r}")
                    val result = r.opt("result")\n                    return if (result is JSONObject) result else JSONObject().put("_value", result)
                }
            }
            throw IllegalStateException("Réponse manquante pour $type.")
        }
        override fun close() { ws?.close(1000, "Jarvis terminé") }
    }

    private fun backup(app: JarvisApp, path: String, config: JSONObject) {
        val dir = File(app.filesDir, "ha-dashboard-backups").apply { mkdirs() }
        val safe = path.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        File(dir, "$safe-${System.currentTimeMillis()}.json").writeText(config.toString(2))
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
    }
    private fun config(s: Session, path: String) = s.command("lovelace/config", if (path.isBlank()) JSONObject() else JSONObject().put("url_path", path))
    private fun save(s: Session, path: String, config: JSONObject) {
        val a = JSONObject().put("config", config)
        if (path.isNotBlank()) a.put("url_path", path)
        s.command("lovelace/config/save", a)
    }

    fun execute(app: JarvisApp, action: String, a: JSONObject): String {
        Session(app).use { s ->
            s.connect()
            val path = a.optString("url_path").trim()
            when (action.lowercase()) {
                "lister" -> {
                    val listed = s.command("lovelace/dashboards/list")\n                    val arr = listed.optJSONArray("_value") ?: listed.optJSONArray("dashboards") ?: JSONArray()
                    return if (arr.length() == 0) "Aucun tableau de bord personnalisé." else (0 until arr.length()).joinToString("\n") { i ->
                        val d = arr.getJSONObject(i)
                        "- ${d.optString("title")} · /${d.optString("url_path")} · ${d.optString("icon")} · ${d.optString("mode")}"
                    }
                }
                "creer" -> {
                    if (!path.matches(Regex("[a-z0-9_-]{2,60}"))) return "url_path requis : 2 à 60 caractères minuscules, chiffres, tirets ou underscores."
                    val title = a.optString("titre").trim().ifBlank { path.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() } }
                    s.command("lovelace/dashboards/create", JSONObject().put("url_path", path).put("title", title)
                        .put("icon", a.optString("icone").ifBlank { "mdi:view-dashboard" }).put("show_in_sidebar", true).put("require_admin", false))
                    val initial = a.optString("configuration").takeIf { it.isNotBlank() }?.let { JSONObject(it) }
                        ?: JSONObject().put("title", title).put("views", JSONArray().put(JSONObject().put("title", "Accueil").put("path", "accueil").put("icon", "mdi:home").put("cards", JSONArray())))
                    save(s, path, initial)
                    return "Tableau de bord « $title » créé (/$path) avec une vue Accueil."
                }
                "supprimer" -> {
                    if (path.isBlank()) return "url_path obligatoire."
                    s.command("lovelace/dashboards/delete", JSONObject().put("url_path", path))
                    return "Tableau de bord /$path supprimé."
                }
                "lire" -> return config(s, path).toString(2).take(18000)
                "modifier", "refaire" -> {
                    if (path.isBlank()) return "url_path obligatoire."
                    val raw = a.optString("configuration").trim()
                    if (raw.isBlank()) return "Fournis le JSON Lovelace complet dans configuration. Lis d'abord le tableau avec action=lire."
                    val old = config(s, path)
                    val replacement = JSONObject(raw)
                    if ((replacement.optJSONArray("views")?.length() ?: 0) < 1) return "Configuration refusée : au moins une vue est requise."
                    backup(app, path, old); save(s, path, replacement)
                    return "Présentation de /$path remplacée. Une sauvegarde locale de l'ancienne version a été créée."
                }
                "carte_ajouter" -> {
                    if (path.isBlank()) return "url_path obligatoire."
                    val raw = a.optString("carte").trim()
                    if (raw.isBlank()) return "JSON de la carte requis."
                    val c = config(s, path); val views = c.optJSONArray("views") ?: return "Aucune vue dans ce tableau."
                    val vi = a.optInt("vue", 0).coerceIn(0, views.length() - 1); val view = views.getJSONObject(vi)
                    backup(app, path, c)
                    val sections = view.optJSONArray("sections")
                    if (sections != null && sections.length() > 0) {
                        val si = a.optInt("section", 0).coerceIn(0, sections.length() - 1)
                        val section = sections.getJSONObject(si)
                        (section.optJSONArray("cards") ?: JSONArray().also { section.put("cards", it) }).put(JSONObject(raw))
                    } else (view.optJSONArray("cards") ?: JSONArray().also { view.put("cards", it) }).put(JSONObject(raw))
                    save(s, path, c)
                    return "Carte ajoutée à la vue ${view.optString("title", vi.toString())} de /$path. Sauvegarde créée."
                }
                "vue_ajouter" -> {
                    if (path.isBlank()) return "url_path obligatoire."
                    val raw = a.optString("vue_json").trim()
                    if (raw.isBlank()) return "JSON de la nouvelle vue requis."
                    val c = config(s, path); backup(app, path, c)
                    (c.optJSONArray("views") ?: JSONArray().also { c.put("views", it) }).put(JSONObject(raw))
                    save(s, path, c)
                    return "Nouvelle vue ajoutée à /$path. Sauvegarde créée."
                }
                else -> return "Actions : lister, lire, creer, modifier, refaire, carte_ajouter, vue_ajouter, supprimer."
            }
        }
    }
}