package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Musique
import org.json.JSONArray
import org.json.JSONObject

/** Outils de recherche et de pilotage de YT Music Morphe via les sessions Android. */
class MusicTools(private val app: JarvisApp) {
    companion object {
        val KEYWORDS = Regex("musique|music|morceau|chanson|titre|artiste|yt ?music|morphe|spotify|lecture musicale|\u00e9couter", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = KEYWORDS.containsMatchIn(t)
        val NAMES = setOf("musique_etat", "musique_commander", "musique_rechercher")
    }
    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)
    val schema: List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_etat")
            .put("description", "Détecte les sessions musicales Android actives (Morphe, YouTube Music, Spotify, Deezer, VLC et autres lecteurs compatibles) et affiche l'application, le titre, l'artiste et l'état réel. Si aucune session n'est visible, indique précisément que l'accès aux notifications de JARVIS doit être activé.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_commander")
            .put("description", "Envoie une commande à la session musicale active du téléphone, quelle que soit l'application compatible : lecture, pause, suivant ou precedent. Ne prétends pas que le morceau a démarré avant de relire son état.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("action", prop("lecture | pause | suivant | precedent"))).put("required", JSONArray().put("action")))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_rechercher")
            .put("description", "Recherche puis tente de lancer réellement le titre ou l'artiste dans l'application musicale demandée ou configurée (Morphe, Spotify, YouTube Music, Deezer, Amazon Music, VLC…). Vérifie le titre et l'état de lecture. Paramètre application facultatif ; s'il est vide, utilise la préférence musicale Jarvis si elle existe, sinon une application musicale installée.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("requete", prop("Titre, artiste ou morceau recherché.")).put("application", prop("Application musicale à utiliser (Morphe, Spotify, YouTube Music, Deezer…). Facultatif."))).put("required", JSONArray().put("requete"))))
    )
    suspend fun execute(name: String, a: JSONObject): ToolResult = try {
        when (name) {
            "musique_etat" -> ToolResult(Musique.etat(app), direct = true)
            "musique_commander" -> ToolResult(Musique.commander(app, a.optString("action")), direct = true)
            "musique_rechercher" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.phone.perform("music", a.optString("requete") + "|" + a.optString("application"))
            }, direct = true)
            else -> ToolResult("Outil musical inconnu.")
        }
    } catch (e: SecurityException) {
        ToolResult("Accès aux sessions multimédias refusé. Active l'accès aux notifications de JARVIS dans les réglages Android.")
    } catch (e: Exception) {
        ToolResult("Erreur musicale : ${e.message ?: e.javaClass.simpleName}")
    }
}
