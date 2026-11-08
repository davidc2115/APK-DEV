package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Musique
import org.json.JSONArray
import org.json.JSONObject

/** Outils de recherche et de pilotage de YT Music Morphe via les sessions Android. */
class MusicTools(private val app: JarvisApp) {
    companion object {
        val KEYWORDS = Regex("musique|music|morceau|chanson|titre|artiste|album|playlist|biblioth[eè]que|j.aime|al[ée]atoire|file d.attente|yt ?music|morphe|spotify|lecture musicale|\u00e9couter|mets le \\d", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = KEYWORDS.containsMatchIn(t)
        val NAMES = setOf("musique_etat", "musique_commander", "musique_rechercher", "musique_resultats", "musique_choisir", "musique_bibliotheque", "musique_action", "musique_file")
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
            .put("description", "Recherche puis tente de lancer réellement le titre ou l'artiste dans l'application musicale demandée ou configurée (Morphe, Spotify, YouTube Music, Deezer, Amazon Music, VLC…). Vérifie le titre et l'état de lecture. Paramètre application facultatif ; s'il est vide, utilise la préférence musicale mémorisée dans Jarvis, sinon l'application musicale installée disponible.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("requete", prop("Titre, artiste ou morceau recherché.")).put("application", prop("Application musicale à utiliser (Morphe, Spotify, YouTube Music, Deezer…). Facultatif."))).put("required", JSONArray().put("requete")))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_resultats")
            .put("description", "YT Music Morphe : CHERCHE dans le catalogue sans rien lancer et renvoie la liste numérotée des résultats (titres, albums, artistes, playlists, vidéos) pour que l'utilisateur choisisse. Ensuite musique_choisir(numero).")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("requete", prop("Ce qu'il faut chercher."))
                .put("type", prop("tout | titres | albums | artistes | playlists | videos (défaut tout)."))).put("required", JSONArray().put("requete")))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_choisir")
            .put("description", "Lance le résultat n° numero de la DERNIÈRE liste de musique montrée (« mets le 2 », « plutôt le troisième »). radio=oui : lance la radio de ce titre (titres similaires à la suite).")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("numero", prop("Numéro dans la liste (1, 2, 3…)."))
                .put("radio", prop("oui pour la radio du titre."))).put("required", JSONArray().put("numero")))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_bibliotheque")
            .put("description", "Bibliothèque PERSONNELLE de YT Music Morphe : section = jaime (titres J'aime), playlists, titres, albums, artistes, telechargements. Sans nom : liste ce qu'elle contient. Avec nom : lance cet élément (aleatoire=oui pour la lecture aléatoire). Ex. « mets ma playlist Sport », « lance mes titres j'aime en aléatoire ».")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("section", prop("jaime | playlists | titres | albums | artistes | telechargements"))
                .put("nom", prop("Élément à lancer (facultatif)."))
                .put("aleatoire", prop("oui pour la lecture aléatoire."))).put("required", JSONArray().put("section")))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_action")
            .put("description", "Action sur le titre en cours : jaime (ajouter aux J'aime), jaimepas, aleatoire, repeter, radio.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("action", prop("jaime | jaimepas | aleatoire | repeter | radio"))).put("required", JSONArray().put("action")))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_file")
            .put("description", "File d'attente du lecteur : sans numéro, liste les prochains titres ; avec numero, saute à ce titre.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("numero", prop("Numéro du titre dans la file (facultatif)."))).put("required", JSONArray()))),
    )

    private fun paquet(): String? = fr.dd.jarvis.device.YtMusique.paquet(app, app.store.appPrefs["musique"])
    @Suppress("UNUSED_PARAMETER") private fun nomAppli(p: String) = fr.dd.jarvis.device.YtMusique.NOM
    suspend fun execute(name: String, a: JSONObject): ToolResult = try {
        when (name) {
            "musique_etat" -> ToolResult(Musique.etat(app), direct = true)
            "musique_commander" -> ToolResult(Musique.commander(app, a.optString("action")), direct = true)
            "musique_rechercher" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.phone.perform("music", a.optString("requete") + "|" + a.optString("application").ifBlank { app.store.appPrefs["musique"].orEmpty() })
            }, direct = true)
            "musique_resultats" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val filtre = a.optString("type").trim().lowercase().let { t -> if (t in fr.dd.jarvis.device.YtMusique.FILTRES) t else "tout" }
                val l = fr.dd.jarvis.device.YtMusique.rechercher(a.optString("requete"), filtre).filter { it.type != fr.dd.jarvis.device.YtMusique.Type.AUTRE }.take(8)
                fr.dd.jarvis.device.YtMusique.retenir(l)
                if (l.isEmpty()) "Aucun résultat pour « ${a.optString("requete")} »." else "🔎 Résultats pour « ${a.optString("requete")} » :\n" + fr.dd.jarvis.device.YtMusique.liste(l, 8) +
                    "\n\nDis le numéro à lancer (ex. « mets le 2 »)."
            }, direct = true)
            "musique_choisir" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val n = a.optString("numero").filter { it.isDigit() }.toIntOrNull() ?: 0
                val r = fr.dd.jarvis.device.YtMusique.derniers.getOrNull(n - 1)
                val p = paquet()
                when {
                    r == null -> "Il n'y a pas de résultat n° $n dans la dernière liste : redemande une recherche."
                    p == null -> "YT Music Morphe n'est pas installée."
                    else -> app.phone.jouerYtm(p, r.titre, nomAppli(p), r, radio = a.optString("radio").lowercase() in setOf("oui", "true", "1"))
                        ?: "❌ Impossible d'ouvrir ce résultat dans ${nomAppli(p)}."
                }
            }, direct = true)
            "musique_bibliotheque" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val p = paquet() ?: return@withContext "YT Music Morphe n'est pas installée."
                val section = fr.dd.jarvis.societe.core.normaliser(a.optString("section")).replace(" ", "").let { s ->
                    when { s.contains("aime") || s.contains("like") -> "jaime"; s.startsWith("playlist") -> "playlists"; s.startsWith("titre") || s.startsWith("chanson") -> "titres"
                        s.startsWith("album") -> "albums"; s.startsWith("artiste") -> "artistes"; s.startsWith("telecharg") -> "telechargements"; else -> "playlists" } }
                val nom = a.optString("nom").trim()
                val aleatoire = a.optString("aleatoire").lowercase() in setOf("oui", "true", "1")
                if (section == "jaime") {
                    if (!fr.dd.jarvis.device.YtMusique.ouvrirLien(app, p, "https://music.youtube.com/watch?list=LM")) "❌ Impossible d'ouvrir tes titres J'aime."
                    else {
                        Thread.sleep(3500)
                        if (aleatoire) fr.dd.jarvis.device.MusiqueBibliotheque.action(app, "aleatoire", p)
                        "❤️ Lecture de tes titres « J'aime » dans ${nomAppli(p)}" + (if (aleatoire) " (aléatoire demandé)." else ".")
                    }
                } else if (nom.isBlank()) {
                    val (l, err) = fr.dd.jarvis.device.MusiqueBibliotheque.lister(app, p, section)
                    err?.let { "❌ $it" } ?: ("📚 Ta bibliothèque — $section :\n" + l.take(40).mapIndexed { i, e -> "${i + 1}. ${e.titre}" + (if (e.sousTitre.isNotBlank()) " — ${e.sousTitre}" else "") }.joinToString("\n") +
                        "\n\nDis lequel lancer (ex. « mets la playlist ${l.firstOrNull()?.titre ?: "…"} »).")
                } else fr.dd.jarvis.device.MusiqueBibliotheque.jouer(app, p, section, nom, aleatoire)
            }, direct = true)
            "musique_action" -> ToolResult(if (fr.dd.jarvis.societe.core.normaliser(a.optString("action")).contains("radio")) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val e = fr.dd.jarvis.device.MusiqueBibliotheque.enCours(app)
                val p = paquet()
                if (e == null || p == null) "Aucun titre en cours." else
                    fr.dd.jarvis.device.YtMusique.rechercher("${e.titre} ${e.artiste}", "titres").firstOrNull()?.let { r -> app.phone.jouerYtm(p, r.titre, nomAppli(p), r, radio = true) } ?: "Radio introuvable pour ce titre."
            } else fr.dd.jarvis.device.MusiqueBibliotheque.action(app, a.optString("action")), direct = true)
            "musique_file" -> ToolResult(run {
                val e = fr.dd.jarvis.device.MusiqueBibliotheque.enCours(app) ?: return@run "Aucune musique en cours."
                val n = a.optString("numero").filter { it.isDigit() }.toIntOrNull()
                if (e.file.isEmpty()) "Le lecteur (${e.app}) ne donne pas sa file d'attente."
                else if (n != null) e.file.getOrNull(n - 1)?.let { (id, t) -> if (fr.dd.jarvis.device.MusiqueBibliotheque.allerA(app, id)) "⏭️ $t" else "❌ Saut impossible." } ?: "Pas de n° $n dans la file."
                else "📜 File d'attente (${e.app}, ${e.file.size} titres) :\n" + e.file.mapIndexed { i, (id, t) -> "${i + 1}. " + (if (id == e.actif) "▶️ " else "") + t }.joinToString("\n")
            }, direct = true)
            else -> ToolResult("Outil musical inconnu.")
        }
    } catch (e: SecurityException) {
        ToolResult("Accès aux sessions multimédias refusé. Active l'accès aux notifications de JARVIS dans les réglages Android.")
    } catch (e: Exception) {
        ToolResult("Erreur musicale : ${e.message ?: e.javaClass.simpleName}")
    }
}
