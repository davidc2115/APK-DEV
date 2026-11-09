package fr.dd.jarvis.voice

import java.time.LocalDate

/**
 * Texte → phrase à DIRE. Les moteurs de synthèse épellent les mots en MAJUSCULES (« CLAIROTTE » → « C L A I R O T T E »)
 * et prononcent certains signes (« tiret », « point », « barre oblique »…). On transforme donc, avant de parler :
 * noms en majuscules → « Clairotte », puces et séparateurs (· — / |) → simples pauses, heures (07:00 → 7 h),
 * dates (2026-11-09 → 9 novembre 2026), symboles (€, %, °C, ≈, →) en mots, liens / Markdown / emojis retirés.
 */
object TexteParle {
    /** Sigles de 4 lettres ou plus qui s'épellent vraiment (les autres mots en capitales sont lus comme des mots). */
    private val SIGLES = setOf("SNCF", "RATP", "HDMI", "SMTP", "IMAP", "HTTP", "HTTPS", "VPN", "USB", "RGPD", "CNIL", "SIREN", "BTP", "PDF", "HTML", "JSON", "CPAM", "DGFIP")
    private val MOIS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

    private fun dateParlee(a: Int, m: Int, j: Int): String? =
        runCatching { LocalDate.of(a, m, j); "${if (j == 1) "1er" else j.toString()} ${MOIS[m - 1]} $a" }.getOrNull()

    /** « 07:00 » → « 7 h », « 14:30 » → « 14 h 30 ». */
    private fun heure(h: String, m: String): String { val hh = h.toInt(); return if (m == "00") "$hh h" else "$hh h $m" }

    /** Mot en capitales (« CLAIROTTE », « GRANCEY-LE-CHÂTEAU ») → « Clairotte », « Grancey-Le-Château ». */
    private fun casse(mot: String): String =
        mot.split('-').joinToString("-") { p -> p.lowercase().replaceFirstChar { it.titlecase() } }

    fun preparer(t: String): String {
        var s = t
            // Symboles qui seraient retirés plus bas avec les emojis : d'abord en mots.
            .replace(Regex("(?i)\\bn°\\s*"), "numéro ").replace(Regex("(\\d)\\s*°\\s*C\\b"), "$1 degrés").replace(Regex("(\\d)\\s*°"), "$1 degrés")
            .replace(Regex("(\\.{2,}|…)\\s*$"), ".")
            // Liens, fichiers, URL, Markdown.
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[[^\\]]*]\\(jarvis-file:[^)]*\\)"), "fichier créé")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]"), "")
            .replace(Regex("(?m)^\\s*-{3,}\\s*$"), ". ")
            .replace(Regex("(?m)^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$"), "")   // séparateurs de tableau
            .replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
            // Puces et numéros de liste en début de ligne : la ligne devient une phrase.
            .replace(Regex("(?m)^\\s*(?:[-•*·▪►✓✔]+|\\d{1,2}[.)])\\s+"), "")
            .replace(Regex("[*`_>#]"), "")
        // Fin de ligne sans ponctuation → courte pause (sinon les lignes s'enchaînent ou une puce est lue).
        s = s.lines().map { it.trim() }.filter { it.isNotBlank() }
            .joinToString(" ") { l -> if (l.last() in ".!?:;,…") l else "$l." }
        // Dates.
        s = s.replace(Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")) { m -> dateParlee(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) ?: m.value }
        s = s.replace(Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b")) { m -> dateParlee(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) ?: m.value }
        // Heures.
        s = s.replace(Regex("\\b([01]?\\d|2[0-3])[:h]([0-5]\\d)\\b")) { m -> heure(m.groupValues[1], m.groupValues[2]) }
        // Séparateurs lus à voix haute → pauses.
        s = s.replace(Regex("\\s*[|·•]\\s*"), ", ")
            .replace(Regex("\\s+[—–-]\\s+"), ", ")
            .replace(Regex("(?<=\\d)\\s*/\\s*(?=\\d)"), ", ")
            .replace(Regex("(?i)\\bkm/h\\b"), "kilomètres heure")
            .replace(Regex("(?<=\\p{L})/(?=\\p{L})"), " ")
            .replace(Regex("\\s+/\\s+"), ", ")
            .replace("→", ", ").replace("⇒", ", ").replace("←", ", ")
            .replace(Regex("[≈~]\\s*"), "environ ")
            .replace(Regex("\\s*&\\s*"), " et ")
            .replace(Regex("\\s*%"), " pour cent")
            .replace(Regex("(\\d)\\s*€"), "$1 euros").replace("€", " euros")
            .replace(Regex("\\.{2,}|…"), ", ")
            .replace(";", ",")
            .replace(Regex("[()\\[\\]{}\"«»]"), " ")
        // Mots en MAJUSCULES : lus comme des mots, pas épelés (sigles courts et sigles connus conservés).
        s = s.replace(Regex("\\b\\p{Lu}[\\p{Lu}'’]*(?:-\\p{Lu}[\\p{Lu}'’]*)*\\b")) { m ->
            val mot = m.value
            val lettres = mot.count { it.isLetter() }
            if (lettres >= 4 && mot.replace("-", "") !in SIGLES) casse(mot) else mot
        }
        return s.replace(Regex("\\s+([,.!?])"), "$1")
            .replace(Regex("([,.])(?:\\s*[,.])+"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim().trimStart(',', '.', ' ').replace(Regex(",$"), ".")
    }
}
