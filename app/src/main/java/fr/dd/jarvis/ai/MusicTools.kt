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

        /** Normalise les formulations orales de la section « J'aime » de la bibliothèque. */
        /** Mode de lecture explicite ; le mode normal est le défaut, même si l'application gardait le shuffle actif. */
        internal fun modeLecture(mode: String, aleatoireHistorique: String = ""): String {
            val brut = mode.ifBlank { if (aleatoireHistorique.lowercase() in setOf("oui", "true", "1")) "aleatoire" else "normal" }
            val s = java.text.Normalizer.normalize(brut.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "").replace(' ', '_').replace('-', '_')
            return when {
                s.contains("aleatoire") || s.contains("shuffle") || s.contains("melange") || s.contains("hasard") -> "aleatoire"
                (s.contains("repeter") || s.contains("repeat") || s.contains("boucle")) &&
                    (s.contains("titre") || s.contains("morceau") || s.contains("chanson") || s.contains("song") || s.contains("une_seule") || s.contains("one") || s.contains("track")) -> "repeter_titre"
                (s.contains("repeter") || s.contains("repeat") || s.contains("boucle")) &&
                    (s.contains("playlist") || s.contains("liste") || s.contains("tout") || s.contains("all")) -> "repeter_playlist"
                s in setOf("repeter_titre", "repeat_one", "repeat_track", "une_chanson", "un_titre", "titre") -> "repeter_titre"
                s in setOf("repeter_playlist", "repeat_all", "repeat_playlist", "toute_la_playlist", "playlist", "tout") -> "repeter_playlist"
                else -> "normal"
            }
        }

        internal fun sectionBibliotheque(section: String): String {
            // Normalisation locale et prévisible : ne dépend pas du normaliseur métier (qui peut conserver
            // les préfixes possessifs ou traiter différemment les apostrophes et accents).
            val s = java.text.Normalizer.normalize(section.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace("’", "'")
                // NFD ne décompose pas les ligatures françaises œ/æ : les translittérer avant le filtre ASCII.
                .replace("œ", "oe").replace("æ", "ae")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
            val compact = s.replace(" ", "")
            return when {
                Regex("(j aime|aime|aimes|aimes|liked|likes|like|favori|favoris|favoris|coeur|coup de coeur)").containsMatchIn(s) ||
                    compact.contains("jaime") || compact.contains("favor") || compact.contains("coeur") -> "jaime"
                Regex("^(mes |ma |mon |les |le |la |tous les |toutes les )*playlists?$").containsMatchIn(s) ||
                    compact.endsWith("playlist") || compact.endsWith("playlists") -> "playlists"
                Regex("^(mes |ma |mon |les |le |la |tous les |toutes les )*(titres?|chansons?)$").containsMatchIn(s) ||
                    compact.endsWith("titre") || compact.endsWith("titres") || compact.endsWith("chanson") || compact.endsWith("chansons") -> "titres"
                compact.endsWith("album") || compact.endsWith("albums") -> "albums"
                compact.endsWith("artiste") || compact.endsWith("artistes") -> "artistes"
                compact.endsWith("telechargement") || compact.endsWith("telechargements") -> "telechargements"
                else -> "playlists"
            }
        }
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
            .put("description", "Lance le résultat n° numero de la DERNIÈRE liste de musique montrée (« mets le 2 », « plutôt le troisième »). mode=normal par défaut, aleatoire, repeter_titre ou repeter_playlist. radio=oui : lance la radio de ce titre (titres similaires à la suite).")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("numero", prop("Numéro dans la liste (1, 2, 3…)."))
                .put("radio", prop("oui pour la radio du titre.")).put("mode", prop("normal | aleatoire | repeter_titre | repeter_playlist (défaut normal)."))).put("required", JSONArray().put("numero")))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_bibliotheque")
            .put("description", "Bibliothèque PERSONNELLE de YT Music Morphe. À utiliser impérativement pour les demandes de lecture de bibliothèque, notamment « lis ma bibliothèque J'aime », « lance mes titres aimés », « mets ma playlist … », « lis mes albums ». section = jaime pour J'aime/titres aimés/favoris, sinon playlists, titres, albums, artistes ou telechargements. Sans nom : pour J'aime, lance toute la liste ; pour les autres sections, affiche les éléments. Avec nom : lance cet élément. mode=normal par défaut (ordre d'origine, shuffle désactivé), aleatoire, repeter_titre (boucle sur un morceau) ou repeter_playlist (boucle sur toute la liste). Ne réponds jamais que la lecture a démarré sans confirmation du lecteur.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("section", prop("jaime | playlists | titres | albums | artistes | telechargements"))
                .put("nom", prop("Élément à lancer (facultatif)."))
                .put("aleatoire", prop("Ancien paramètre compatible : oui pour la lecture aléatoire.")).put("mode", prop("normal | aleatoire | repeter_titre | repeter_playlist (défaut normal)."))).put("required", JSONArray().put("section")))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_action")
            .put("description", "Action sur le titre en cours : jaime, jaimepas, normal (désactive shuffle et répétition), aleatoire, repeter_titre, repeter_playlist, repeter (ancienne action), radio.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("action", prop("jaime | jaimepas | normal | aleatoire | repeter_titre | repeter_playlist | repeter | radio"))).put("required", JSONArray().put("action")))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "musique_file")
            .put("description", "File d'attente du lecteur : sans numéro, liste les prochains titres ; avec numero, saute à ce titre.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("numero", prop("Numéro du titre dans la file (facultatif)."))).put("required", JSONArray()))),
    )

    private fun paquet(): String? = fr.dd.jarvis.device.YtMusique.paquet(app, app.store.appPrefs["musique"])
    @Suppress("UNUSED_PARAMETER") private fun nomAppli(p: String) = fr.dd.jarvis.device.YtMusique.NOM
    suspend fun execute(name: String, a: JSONObject): ToolResult {
        val debut = System.currentTimeMillis()
        return try {
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
                    else -> app.phone.jouerYtm(p, r.titre, nomAppli(p), r,
                        radio = a.optString("radio").lowercase() in setOf("oui", "true", "1"),
                        mode = modeLecture(a.optString("mode")))
                        ?: "❌ Impossible d'ouvrir ce résultat dans ${nomAppli(p)}."
                }
            }, direct = true)
            "musique_bibliotheque" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val p = paquet() ?: return@withContext "YT Music Morphe n'est pas installée."
                val section = sectionBibliotheque(a.optString("section"))
                val nom = a.optString("nom").trim()
                val mode = modeLecture(a.optString("mode"), a.optString("aleatoire"))
                val aleatoire = mode == "aleatoire"
                val repetition = when (mode) { "repeter_titre" -> "titre"; "repeter_playlist" -> "playlist"; else -> "aucune" }
                if (section == "jaime") {
                    if (!fr.dd.jarvis.device.YtMusique.ouvrirLien(app, p, "https://music.youtube.com/watch?list=LM")) "❌ Impossible d'ouvrir tes titres J'aime."
                    else {
                        kotlinx.coroutines.delay(1400)
                        val demarree = fr.dd.jarvis.device.MusiqueBibliotheque.demarrerLecture(app, p, aleatoire, repetition)
                        fr.dd.jarvis.device.RetourJarvisMusique.afficher(app)
                        if (demarree) {
                            "❤️ Tes titres « J'aime » sont bien en lecture dans ${nomAppli(p)}" + when (mode) {
                                "aleatoire" -> " en aléatoire."
                                "repeter_titre" -> " avec répétition du titre."
                                "repeter_playlist" -> " avec répétition de toute la liste."
                                else -> " dans l'ordre normal."
                            }
                        } else {
                            "📂 Ta liste « J'aime » est ouverte dans ${nomAppli(p)}, mais le lecteur n'a pas confirmé le démarrage. Vérifie le bouton Lecture dans l'application."
                        }
                    }
                } else if (nom.isBlank()) {
                    val (l, err) = fr.dd.jarvis.device.MusiqueBibliotheque.lister(app, p, section)
                    err?.let { "❌ $it" } ?: ("📚 Ta bibliothèque — $section :\n" + l.take(40).mapIndexed { i, e -> "${i + 1}. ${e.titre}" + (if (e.sousTitre.isNotBlank()) " — ${e.sousTitre}" else "") }.joinToString("\n") +
                        "\n\nDis lequel lancer (ex. « mets la playlist ${l.firstOrNull()?.titre ?: "…"} »).")
                } else fr.dd.jarvis.device.MusiqueBibliotheque.jouer(app, p, section, nom, aleatoire, repetition).also {
                    fr.dd.jarvis.device.RetourJarvisMusique.afficher(app)
                }
            }, direct = true)
            "musique_action" -> ToolResult(if (fr.dd.jarvis.societe.core.normaliser(a.optString("action")).replace(' ', '_') in setOf("normal", "aleatoire", "repeter_titre", "repeter_playlist", "shuffle", "repeat_one", "repeat_all")) {
                fr.dd.jarvis.device.MusiqueBibliotheque.configurerMode(app, a.optString("action"))
            } else if (fr.dd.jarvis.societe.core.normaliser(a.optString("action")).contains("radio")) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Laisse l'annulation remonter jusqu'au service pour qu'il libère immédiatement la demande en cours.
            throw e
        } catch (e: SecurityException) {
            ToolResult("Accès aux sessions multimédias refusé. Active l'accès aux notifications de JARVIS dans les réglages Android.")
        } catch (e: Exception) {
            ToolResult("Erreur musicale : ${e.message ?: e.javaClass.simpleName}")
        } finally {
            fr.dd.jarvis.voice.JournalVoix.noter("musique", "$name en ${System.currentTimeMillis() - debut} ms")
        }
    }
}
