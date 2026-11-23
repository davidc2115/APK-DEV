package fr.dd.jarvis.ai

/**
 * Demande de musique qui vise la bibliothèque PERSONNELLE de YT Music Morphe (« mes titres J'aime », « ma playlist
 * sport », « mon album… ») et non le catalogue public. Avant, ces phrases partaient en recherche publique : « joue mes
 * titres J'aime » lançait une chanson intitulée « J'aime », « mets ma playlist sport » une playlist inconnue.
 *
 * Le texte reçu est déjà « à plat » (minuscules, sans accents, ponctuation → espaces), sans le verbe (« mets », « joue »…).
 */
object DemandeBibliotheque {
    data class Demande(val section: String, val nom: String = "", val mode: String = "normal")

    private val MODE_ALEATOIRE = Regex("\\b(?:en mode |en |au |de facon )?(?:aleatoire|hasard|melangee?s?|melange|shuffle)\\b")
    private val MODE_BOUCLE = Regex("\\b(?:en |a )?(?:boucle|repetition)\\b")
    private const val ARTICLES = "(?:(?:toute ma|toute la|tous mes|toutes mes|ma|mes|mon|la|les|le|l) )*"
    private const val NOMS = "(?:(?:bibliotheque|playlist|liste|titres?|musiques?|chansons?|sons?|morceaux?|playlist des titres|liste des titres) )?"
    private val JAIME = Regex("^$ARTICLES$NOMS(?:(?:de|des|du|en) )?" +
        "(?:j aime|que j aime|que j ai aime|que j adore|aimes?|aimees?|likes?|liked|liked music|favoris?|favorites?|preferes?|preferees?|" +
        "coups? de co?eur|coups? de cour)(?: (?:de|dans|sur) (?:youtube music|yt music|ytm|morphe))?$")
    private val TOUTE_MA_MUSIQUE = Regex("^(?:toute )?(?:ma|mes) (?:bibliotheque|musiques?|titres|chansons|sons|morceaux)$")
    private val PLAYLIST = Regex("^(?:ma|mes) (?:playlists?|listes? de lecture)(?: (?:qui s appelle|appelee|nommee|intitulee|de|du|des))?(?: (.+))?$")
    private val BIBLIO_NOM = Regex("^(?:ma|dans ma) bibliotheque (?:la playlist |playlist )?(.+)$")
    private val ALBUM = Regex("^(?:mon|mes) albums?(?: (?:de|du|des))?(?: (.+))?$")
    private val ARTISTE = Regex("^(?:mon|mes) artistes?(?: (?:prefere|favori))?(?: (.+))?$")
    private val TELECHARGES = Regex("^(?:mes|ma) (?:telechargements|musiques? telechargees?|titres telecharges|chansons telechargees|musique hors ligne)$")

    /** Mode de lecture dit dans la phrase, et la phrase sans lui. */
    internal fun mode(n: String): Pair<String, String> {
        val mode = when {
            MODE_ALEATOIRE.containsMatchIn(n) -> "aleatoire"
            MODE_BOUCLE.containsMatchIn(n) -> "repeter_playlist"
            else -> "normal"
        }
        val reste = n.replace(MODE_ALEATOIRE, " ").replace(MODE_BOUCLE, " ").replace(Regex("\\s+"), " ").trim()
        return mode to reste
    }

    /** Demande de bibliothèque, ou null (catalogue public). */
    fun analyser(n0: String): Demande? {
        val (mode, n) = mode(n0.trim().replace(Regex("\\s+"), " "))
        if (n.isBlank() || n == "j aime") return null           // « joue J'aime » : peut être un titre
        if (JAIME.matches(n) || TOUTE_MA_MUSIQUE.matches(n)) return Demande("jaime", mode = mode)
        PLAYLIST.matchEntire(n)?.let { m -> return Demande("playlists", m.groupValues[1].trim(), mode) }
        ALBUM.matchEntire(n)?.let { m -> return Demande("albums", m.groupValues[1].trim(), mode) }
        ARTISTE.matchEntire(n)?.let { m -> return Demande("artistes", m.groupValues[1].trim(), mode) }
        if (TELECHARGES.matches(n)) return Demande("telechargements", mode = mode)
        BIBLIO_NOM.matchEntire(n)?.let { m -> return Demande("playlists", m.groupValues[1].trim(), mode) }
        return null
    }
}
