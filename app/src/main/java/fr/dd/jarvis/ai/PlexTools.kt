package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Accès Plex Media Server via le tunnel SSH du serveur Debian. */
object Plex {
    private const val PORT = 32400
    private const val CLIENT_ID = "fr.dd.jarvis.plex"
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private fun token(app: JarvisApp): String = app.store.plexToken.value.trim().also {
        if (it.isBlank()) throw IllegalStateException("Jeton Plex manquant : Réglages › Maison › Serveur › Plex.")
    }

    private fun url(app: JarvisApp, path: String, query: Map<String, String> = emptyMap()): okhttp3.HttpUrl {
        val port = fr.dd.jarvis.ai.Serveur.tunnel(app, PORT)
        val b = "http://127.0.0.1:$port$path".toHttpUrl().newBuilder()
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun request(app: JarvisApp, path: String, query: Map<String, String> = emptyMap(),
                        targetClient: String? = null): String {
        val b = Request.Builder().url(url(app, path, query))
            .header("Accept", "application/json")
            .header("X-Plex-Token", token(app))
            .header("X-Plex-Client-Identifier", CLIENT_ID)
            .header("X-Plex-Product", "JARVIS")
            .header("X-Plex-Version", "1.0")
        targetClient?.let { b.header("X-Plex-Target-Client-Identifier", it) }
        http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401) throw IllegalStateException("Plex refuse le jeton (401). Vérifie le jeton dans les réglages Plex de JARVIS.")
            if (r.code == 403) throw IllegalStateException("Plex refuse l'accès (403). Vérifie les droits du compte et du lecteur.")
            if (!r.isSuccessful) throw IllegalStateException("Plex HTTP ${r.code} : ${body.take(250)}")
            return body
        }
    }

    fun serveur(app: JarvisApp): JSONObject = JSONObject(request(app, "/"))
    fun appareils(app: JarvisApp): JSONArray {
        val root = JSONObject(request(app, "/clients"))
        val container = root.optJSONObject("MediaContainer") ?: root
        return container.optJSONArray("Device") ?: JSONArray()
    }

    fun rechercher(app: JarvisApp, texte: String, limite: Int = 15): List<JSONObject> {
        require(texte.isNotBlank()) { "Précise le titre à rechercher dans Plex." }
        val root = JSONObject(request(app, "/hubs/search/", mapOf("query" to texte.trim(), "limit" to limite.coerceIn(1, 30).toString())))
        val hubs = root.optJSONObject("MediaContainer")?.optJSONArray("Hub") ?: JSONArray()
        val resultats = linkedMapOf<String, JSONObject>()
        for (i in 0 until hubs.length()) {
            val items = hubs.optJSONObject(i)?.optJSONArray("Metadata") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val key = item.optString("ratingKey").ifBlank { item.optString("key") }
                if (key.isNotBlank()) resultats.putIfAbsent(key, item)
            }
        }
        return resultats.values.toList()
    }

    fun lancer(app: JarvisApp, media: JSONObject, appareil: JSONObject): String {
        val ratingKey = media.optString("ratingKey").ifBlank { throw IllegalStateException("Plex n'a pas fourni l'identifiant de ce média.") }
        val clientId = appareil.optString("machineIdentifier").ifBlank { throw IllegalStateException("Ce lecteur Plex ne fournit pas d'identifiant de contrôle.") }
        val server = serveur(app)
        val machineId = server.optString("machineIdentifier").ifBlank { throw IllegalStateException("Identifiant du serveur Plex introuvable.") }
        val adresse = Serveur.ipLocaleServeur(app) ?: throw IllegalStateException("Impossible de déterminer l'adresse locale du serveur Debian pour transmettre le flux au lecteur.")
        request(app, "/player/playback/playMedia", mapOf(
            "key" to "/library/metadata/$ratingKey",
            "machineIdentifier" to machineId,
            "address" to adresse,
            "port" to PORT.toString(),
            "protocol" to "http",
            "offset" to "0",
            "commandID" to "1",
            "type" to "video"
        ), clientId)
        return "Commande de lecture transmise à ${appareil.optString("name").ifBlank { appareil.optString("product").ifBlank { "lecteur Plex" } }}."
    }
}

/** Outils conversationnels Plex ; aucune action de lecture n'est lancée sans confirmation. */
class PlexTools(private val app: JarvisApp, private val confirm: suspend (String, Boolean) -> Boolean) {
    companion object {
        val NAMES = setOf("plex_rechercher", "plex_appareils", "plex_lancer")
        val KEYWORDS = Regex("\\bplex\\b|biblioth[eè]que plex|lecteur plex", RegexOption.IGNORE_CASE)
        fun concerned(text: String) = KEYWORDS.containsMatchIn(text)
    }

    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun tool(name: String, description: String, props: Map<String, JSONObject>, required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                .put("required", JSONArray(required))))

    val schema = listOf(
        tool("plex_rechercher", "Recherche un film ou une série dans la bibliothèque Plex du serveur Debian (lecture seule).",
            mapOf("titre" to prop("Titre ou partie du titre à rechercher.")), listOf("titre")),
        tool("plex_appareils", "Liste les lecteurs Plex actuellement visibles par le serveur (TV, téléphone, boîtier…).",
            emptyMap()),
        tool("plex_lancer", "Lance un film ou une série Plex sur un lecteur Plex compatible actuellement visible. Demande confirmation avant de démarrer.",
            mapOf("titre" to prop("Titre exact ou suffisamment distinctif du film ou de la série."), "appareil" to prop("Nom du lecteur Plex cible, par exemple TV salon ou téléphone.")), listOf("titre", "appareil"))
    )

    suspend fun execute(name: String, a: JSONObject): ToolResult = withContextIo {
        try {
            when (name) {
                "plex_rechercher" -> {
                    val items = Plex.rechercher(app, a.optString("titre"))
                    if (items.isEmpty()) ToolResult("Aucun résultat Plex pour « ${a.optString("titre")} ».", direct = true)
                    else ToolResult("# Résultats Plex\n" + items.take(15).joinToString("\n") { item ->
                        "- **${item.optString("title")}** (${item.optString("year").ifBlank { "année inconnue" }}) · ${item.optString("type")} · ID ${item.optString("ratingKey")}" +
                            item.optString("summary").takeIf { it.isNotBlank() }?.let { "\n  ${it.take(180)}" }.orEmpty()
                    }, direct = true)
                }
                "plex_appareils" -> {
                    val devices = Plex.appareils(app)
                    if (devices.length() == 0) ToolResult("Aucun lecteur Plex n'est actuellement visible. Vérifie que l'application Plex est ouverte, connectée au même compte et que le contrôle à distance est autorisé.", direct = true)
                    else ToolResult("# Lecteurs Plex visibles\n" + (0 until devices.length()).joinToString("\n") { i ->
                        val d = devices.getJSONObject(i)
                        "- **${d.optString("name").ifBlank { d.optString("product") }}** · ${d.optString("product")} · ${d.optString("platform")} · ${d.optString("address")}"
                    }, direct = true)
                }
                "plex_lancer" -> {
                    val titre = a.optString("titre").trim()
                    val appareilNom = a.optString("appareil").trim()
                    val items = Plex.rechercher(app, titre)
                    val media = items.firstOrNull { it.optString("title").equals(titre, true) }
                        ?: items.singleOrNull()
                        ?: return@withContextIo ToolResult(if (items.isEmpty()) "Aucun média Plex trouvé pour « $titre »." else "Plusieurs médias correspondent à « $titre » : " + items.take(10).joinToString(", ") { it.optString("title") } + ". Précise le titre.")
                    val devices = Plex.appareils(app)
                    val device = (0 until devices.length()).map { devices.getJSONObject(it) }.firstOrNull {
                        it.optString("name").contains(appareilNom, true) || it.optString("product").contains(appareilNom, true)
                    } ?: return@withContextIo ToolResult("Lecteur Plex « $appareilNom » introuvable parmi les appareils actuellement visibles.")
                    val nom = media.optString("title")
                    if (!confirm("Lancer « $nom » sur « ${device.optString("name")} » via Plex ?", true))
                        return@withContextIo ToolResult("Lecture annulée.")
                    ToolResult(Plex.lancer(app, media, device), direct = true)
                }
                else -> ToolResult("Outil Plex inconnu.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ToolResult("❌ Plex : ${e.message ?: e.javaClass.simpleName}") }
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
}
