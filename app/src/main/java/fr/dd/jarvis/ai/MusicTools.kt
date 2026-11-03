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
            .put("description", "Détecte le morceau actuellement joué dans YT Music Morphe (titre, artiste, album et état lecture/pause) via la session multimédia Android. Si aucune session n'est visible, explique que l'accès aux notifications de JARVIS doit être activé.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_commander")
            .put("description", "Envoie une commande à la session active YT Music Morphe : lecture, pause, suivant ou precedent. Ne prétends pas que le morceau a démarré avant de relire son état.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("action", prop("lecture | pause | suivant | precedent"))).put("required", JSONArray().put("action")))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_rechercher")
            .put("description", "Ouvre une recherche de titre ou d'artiste directement dans YT Music Morphe, si l'application accepte le lien de recherche YouTube Music.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("requete", prop("Titre, artiste ou morceau recherché."))).put("required", JSONArray().put("requete"))))
    )
    fun execute(name: String, a: JSONObject): ToolResult = try {
        when (name) {
            "musique_etat" -> ToolResult(Musique.etat(app), direct = true)
            "musique_commander" -> ToolResult(Musique.commander(app, a.optString("action")), direct = true)
            "musique_rechercher" -> ToolResult(Musique.rechercher(app, a.optString("requete")), direct = true)
            else -> ToolResult("Outil musical inconnu.")
        }
    } catch (e: SecurityException) {
        ToolResult("Accès aux sessions multimédias refusé. Active l'accès aux notifications de JARVIS dans les réglages Android.")
    } catch (e: Exception) {
        ToolResult("Erreur musicale : ${e.message ?: e.javaClass.simpleName}")
    }
}
