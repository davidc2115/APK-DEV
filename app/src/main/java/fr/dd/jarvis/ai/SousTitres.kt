package fr.dd.jarvis.ai

/**
 * Sous-titres façon TikTok : la voix off est dite phrase par phrase (durée de chaque phrase mesurée sur le fichier
 * audio), puis chaque phrase est découpée en courts morceaux de quelques mots affichés au rythme de la voix.
 */
object SousTitres {
    data class Repere(val debutMs: Long, val finMs: Long, val texte: String)

    /** Phrases de la narration ; une phrase trop longue est coupée aux virgules. */
    fun phrases(texte: String): List<String> = texte.replace(Regex("\\s+"), " ").trim()
        .split(Regex("(?<=[.!?…])\\s+")).flatMap { p ->
            if (p.length <= 110) listOf(p)
            else p.split(Regex("(?<=[,;:])\\s+")).fold(mutableListOf<String>()) { acc, morceau ->
                if (acc.isNotEmpty() && acc.last().length + morceau.length < 90) acc[acc.size - 1] = acc.last() + " " + morceau else acc += morceau
                acc
            }
        }.map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }

    /**
     * Repères affichés : chaque phrase [i] dure [dureesMs][i] ; elle est coupée en morceaux de [motsMax] mots au plus
     * (jamais un mot isolé à la fin), chacun affiché un temps proportionnel à sa longueur.
     */
    fun reperes(phrases: List<String>, dureesMs: List<Long>, motsMax: Int = 4): List<Repere> {
        val out = mutableListOf<Repere>()
        var debut = 0L
        for ((i, p) in phrases.withIndex()) {
            val duree = dureesMs.getOrNull(i) ?: break
            val mots = p.split(' ').filter { it.isNotBlank() }
            val morceaux = mots.chunked(motsMax).map { it.toMutableList() }.toMutableList()
            if (morceaux.size >= 2 && morceaux.last().size == 1) { morceaux[morceaux.size - 2].addAll(morceaux.last()); morceaux.removeAt(morceaux.size - 1) }
            val poids = morceaux.map { m -> m.joinToString(" ").length + 2 }
            val total = poids.sum().coerceAtLeast(1)
            var t = debut
            morceaux.forEachIndexed { k, m ->
                val fin = if (k == morceaux.size - 1) debut + duree else t + duree * poids[k] / total
                if (fin > t) out += Repere(t, fin, m.joinToString(" "))
                t = fin
            }
            debut += duree
        }
        return out
    }

    /** Repère affiché à l'instant [ms], ou null (entre deux phrases, après la voix). */
    fun a(reperes: List<Repere>, ms: Long): Repere? = reperes.firstOrNull { ms >= it.debutMs && ms < it.finMs }
}
