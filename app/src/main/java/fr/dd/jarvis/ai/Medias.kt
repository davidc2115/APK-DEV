package fr.dd.jarvis.ai

/**
 * Médias produits par les outils (images générées, captures, graphiques, vidéos IA, fichiers créés) : références
 * « jarvis-img:NOM », « jarvis-video-ia:NOM », « jarvis-file:NOM ».
 *
 * Quand Hermès répond, il reformule le résultat des outils : il lui arrive de transformer « ![image](jarvis-img:…) » en
 * simple lien « images », ou d'oublier l'image. [completer] remet dans la réponse, sur une ligne à part, chaque média
 * produit pendant la demande qu'elle ne montre pas déjà — l'image s'affiche donc toujours dans la conversation.
 */
object Medias {
    /** Référence seule : type + nom de fichier. */
    private val REF = Regex("""(jarvis-img|jarvis-video-ia|jarvis-file):([^)\s\]"'>]+)""")
    /** Markdown complet autour d'une référence : ![légende](jarvis-img:…) ou [légende](jarvis-file:…). */
    private val MARKDOWN = Regex("""!?\[([^\]]*)]\(((?:jarvis-img|jarvis-video-ia|jarvis-file):[^)\s]+)\)""")

    data class Media(val ref: String, val markdown: String)

    /** Références présentes dans un texte (dans l'ordre, sans doublon). */
    fun references(texte: String): List<String> = REF.findAll(texte).map { "${it.groupValues[1]}:${it.groupValues[2].trimEnd('.', ',', ';', '!', '?')}" }.distinct().toList()

    /** Médias d'un résultat d'outil, avec leur Markdown d'origine (légende du fichier conservée). */
    fun extraire(texte: String): List<Media> {
        val md = MARKDOWN.findAll(texte).associate { it.groupValues[2] to it.value }
        return references(texte).map { ref -> Media(ref, md[ref] ?: ligne(ref)) }
    }

    /** Ligne Markdown affichable pour une référence. */
    fun ligne(ref: String): String = when {
        ref.startsWith("jarvis-img:") -> "![image]($ref)"
        ref.startsWith("jarvis-video-ia:") -> "[▶ Lire la vidéo]($ref)"
        else -> "[📎 ${ref.substringAfter(':').substringAfterLast('/')}]($ref)"
    }

    /** Ajoute à [reponse] les médias produits qu'elle ne montre pas déjà (chacun sur sa ligne). */
    fun completer(reponse: String, produits: List<Media>): String {
        if (produits.isEmpty()) return reponse
        val presents = references(reponse).toSet()
        val manquants = produits.distinctBy { it.ref }.filter { it.ref !in presents }
        if (manquants.isEmpty()) return reponse
        return reponse.trimEnd() + "\n\n" + manquants.joinToString("\n\n") { m ->
            // Une image se montre toujours en image (jamais en simple lien), quelle que soit la forme reçue.
            if (m.ref.startsWith("jarvis-img:") && !m.markdown.startsWith("!")) ligne(m.ref) else m.markdown
        }
    }

    /** Morceaux d'une ligne de conversation : texte et médias, dans l'ordre (pour l'affichage). */
    sealed class Morceau {
        data class Texte(val texte: String) : Morceau()
        data class Image(val nom: String) : Morceau()
        data class Video(val nom: String) : Morceau()
        data class Fichier(val libelle: String, val nom: String) : Morceau()
    }

    /** Image ou vidéo n'importe où dans la ligne (Markdown ou référence nue) ; null si la ligne n'en contient pas. */
    private val DANS_LIGNE = Regex("""!?\[([^\]]*)]\((jarvis-img|jarvis-video-ia|jarvis-file):([^)\s]+)\)|(?<![(\w:-])(jarvis-img|jarvis-video-ia):([^)\s\]"'>]+)""")

    fun decouper(ligne: String): List<Morceau>? {
        val trouves = DANS_LIGNE.findAll(ligne).toList()
        if (trouves.isEmpty()) return null
        val out = mutableListOf<Morceau>()
        var dernier = 0
        fun texte(t: String) {
            // Seulement du vrai texte (pas une puce ou une ponctuation restée seule autour de l'image).
            if (t.any { it.isLetterOrDigit() }) out += Morceau.Texte(t.trim())
        }
        for (m in trouves) {
            texte(ligne.substring(dernier, m.range.first))
            val type = m.groupValues[2].ifBlank { m.groupValues[4] }
            val nom = m.groupValues[3].ifBlank { m.groupValues[5] }.trimEnd('.', ',', ';', '!', '?')
            out += when (type) {
                "jarvis-img" -> Morceau.Image(nom)
                "jarvis-video-ia" -> Morceau.Video(nom)
                else -> Morceau.Fichier(m.groupValues[1].ifBlank { "📎 ${nom.substringAfterLast('/')}" }, nom)
            }
            dernier = m.range.last + 1
        }
        texte(ligne.substring(dernier))
        return out
    }
}
