package fr.dd.jarvis.ai

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Jours et dates dits en français dans une demande : « lundi », « lundi prochain », « mardi 24 », « le 3 décembre »,
 * « 24/11 », « 2026-11-24 », « après-demain », « dans 3 jours », « dans une semaine »… et le moment de la journée
 * (« lundi matin », « cet après-midi »). Utilisé par les réflexes (navigation vers un rendez-vous, planning) pour ne
 * plus ramener toute demande datée à « aujourd'hui ».
 */
object DatesFr {
    val JOURS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val MOIS = listOf("janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre")
    private val MOIS_COURTS = mapOf("janv" to 1, "fev" to 2, "fevr" to 2, "avr" to 4, "juil" to 7, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12)
    private val NOMBRES = mapOf("un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8,
        "neuf" to 9, "dix" to 10, "quinze" to 14)

    private const val J = "lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche"
    private val M = (MOIS + MOIS_COURTS.keys).sortedByDescending { it.length }.joinToString("|")

    data class Trouve(val date: LocalDate, val texte: String)

    /** Minuscules sans accents ; « 24/11 », « 24-11-2026 », « 24.11 » gardés en « 24/11… » ; « 14:30 » → « 14h30 ». */
    fun normaliser(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace(Regex("(\\d{1,2}):(\\d{2})"), "$1h$2")
        .replace(Regex("(\\d)\\s*[/.\\-]\\s*(?=\\d)"), "$1/")
        .replace(Regex("[^a-z0-9/ ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun mois(m: String): Int? = MOIS.indexOf(m).takeIf { it >= 0 }?.plus(1) ?: MOIS_COURTS[m]

    private fun date(a: Int, m: Int, j: Int): LocalDate? = runCatching { LocalDate.of(a, m, j) }.getOrNull()

    /** Date sans année : cette année, ou l'an prochain si elle est déjà passée ([futur]). */
    private fun sansAnnee(m: Int, j: Int, aujourdhui: LocalDate, futur: Boolean): LocalDate? {
        val d = date(aujourdhui.year, m, j) ?: return null
        return if (futur && d.isBefore(aujourdhui)) date(aujourdhui.year + 1, m, j) else d
    }

    private fun jourSemaine(nom: String) = DayOfWeek.of(JOURS.indexOf(nom) + 1)

    /**
     * Jour désigné dans [n] (texte de [normaliser]) et le passage qui le dit, ou null. [futur] : une date sans année
     * déjà passée désigne l'an prochain, un jour de la semaine le prochain (aujourd'hui compris).
     */
    fun jour(n: String, aujourdhui: LocalDate, futur: Boolean = true): Trouve? {
        // 2026-11-24 / 2026/11/24
        Regex("\\b(\\d{4})/(\\d{1,2})/(\\d{1,2})\\b").find(n)?.let { m ->
            date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return Trouve(it, m.value) }
        }
        // (lundi) (le) 24/11(/2026)
        Regex("\\b(?:(?:$J) )?(?:le |du |au |pour le )?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b").find(n)?.let { m ->
            val j = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            val a = m.groupValues[3].takeIf { it.isNotBlank() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            (if (a != null) date(a, mo, j) else sansAnnee(mo, j, aujourdhui, futur))?.let { return Trouve(it, m.value) }
        }
        // (lundi) (le) 24 novembre (2026), 1er décembre
        Regex("\\b(?:(?:$J) )?(?:le |du |au |pour le |ce )?(1er|premier|\\d{1,2}) ($M)\\b(?: (\\d{4}))?").find(n)?.let { m ->
            val j = if (m.groupValues[1] in setOf("1er", "premier")) 1 else m.groupValues[1].toInt()
            val mo = mois(m.groupValues[2]) ?: return@let
            val a = m.groupValues[3].takeIf { it.isNotBlank() }?.toInt()
            (if (a != null) date(a, mo, j) else sansAnnee(mo, j, aujourdhui, futur))?.let { return Trouve(it, m.value) }
        }
        // après-demain / avant-hier / demain / hier / aujourd'hui
        Regex("\\bapres demain\\b").find(n)?.let { return Trouve(aujourdhui.plusDays(2), it.value) }
        Regex("\\bavant hier\\b").find(n)?.let { return Trouve(aujourdhui.minusDays(2), it.value) }
        Regex("\\b(?:de |pour )?demain\\b").find(n)?.let { return Trouve(aujourdhui.plusDays(1), it.value) }
        Regex("\\b(?:d )?hier\\b").find(n)?.let { return Trouve(aujourdhui.minusDays(1), it.value) }
        // dans 3 jours / dans une semaine / dans quinze jours
        Regex("\\bdans (\\d{1,3}|${NOMBRES.keys.joinToString("|")}) (jours?|semaines?)\\b").find(n)?.let { m ->
            val k = m.groupValues[1].toIntOrNull() ?: NOMBRES.getValue(m.groupValues[1])
            return Trouve(aujourdhui.plusDays(if (m.groupValues[2].startsWith("semaine")) 7L * k else k.toLong()), m.value)
        }
        // lundi de la semaine prochaine / lundi en huit / lundi prochain / lundi dernier / (ce) lundi (24)
        Regex("\\b(?:ce |de |du |pour |le )?($J)(?: (\\d{1,2})(?! ?h\\b| ?heures?\\b))?( de la semaine prochaine| en huit| prochain| d apres| qui vient| dernier)?\\b").find(n)?.let { m ->
            val js = jourSemaine(m.groupValues[1])
            m.groupValues[2].toIntOrNull()?.let { num ->
                // « mardi 24 » : le 24 (ce mois-ci, ou le suivant s'il est passé).
                val ce = date(aujourdhui.year, aujourdhui.monthValue, num)
                val d = if (ce != null && !ce.isBefore(aujourdhui)) ce else aujourdhui.plusMonths(1).let { s -> date(s.year, s.monthValue, num) }
                if (d != null) return Trouve(d, m.value)
            }
            val d = when (m.groupValues[3].trim()) {
                "de la semaine prochaine" -> aujourdhui.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).with(TemporalAdjusters.nextOrSame(js))
                "en huit" -> aujourdhui.with(TemporalAdjusters.next(js)).plusWeeks(1)
                "prochain", "d apres", "qui vient" -> aujourdhui.with(TemporalAdjusters.next(js))
                "dernier" -> aujourdhui.with(TemporalAdjusters.previous(js))
                else -> if (futur) aujourdhui.with(TemporalAdjusters.nextOrSame(js)) else aujourdhui.with(TemporalAdjusters.previousOrSame(js))
            }
            return Trouve(d, m.value)
        }
        // le 24 (sans mois) : ce mois-ci, ou le mois suivant s'il est passé — jamais « le 2e », « de 14 h ».
        Regex("\\b(?:le|du|au|pour le) (1er|premier|\\d{1,2})\\b(?! ?h\\b| ?heures?\\b| ?e\\b| ?eme\\b| ?(?:rendez|rdv|min))").find(n)?.let { m ->
            val num = if (m.groupValues[1] in setOf("1er", "premier")) 1 else m.groupValues[1].toInt()
            if (num !in 1..31) return@let
            val ce = date(aujourdhui.year, aujourdhui.monthValue, num)
            val d = if (ce != null && (!futur || !ce.isBefore(aujourdhui))) ce else aujourdhui.plusMonths(1).let { s -> date(s.year, s.monthValue, num) }
            if (d != null) return Trouve(d, m.value)
        }
        Regex("\\b(?:d )?aujourd hui\\b|\\bce matin\\b|\\bce soir\\b|\\bcet apres midi\\b|\\bce midi\\b|\\bcette nuit\\b").find(n)?.let { return Trouve(aujourdhui, it.value) }
        return null
    }

    /**
     * Rappel pour l'IA du jour EXACT que désigne la demande (« lundi » = lundi 23 novembre 2026, 2026-11-23) : les
     * modèles se trompent souvent en calculant un jour de la semaine, ou retombent sur aujourd'hui. Vide sans date.
     */
    fun indice(texte: String, aujourdhui: LocalDate = LocalDate.now()): String {
        val n = normaliser(texte)
        val t = jour(n, aujourdhui) ?: return ""
        if (t.date == aujourdhui && !Regex("\\d").containsMatchIn(t.texte)) return ""
        val noms = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
        val complet = "${JOURS[t.date.dayOfWeek.value - 1]} ${if (t.date.dayOfMonth == 1) "1er" else t.date.dayOfMonth} ${noms[t.date.monthValue - 1]} ${t.date.year}"
        val moment = partie(n)?.first?.let { " ($it)" }.orEmpty()
        return "\n\nJOUR DEMANDÉ : « ${t.texte} » = $complet$moment, soit ${t.date}. Utilise EXACTEMENT cette date dans les outils (agenda, planning, " +
            "itinéraire, navigation, rappels, événements…) — ne la ramène jamais à aujourd'hui."
    }

    /** Moment de la journée demandé (heures de début acceptées), ou null : matin, midi, après-midi, soir. */
    fun partie(n: String): Pair<String, IntRange>? = when {
        Regex("\\bapres midi\\b|\\baprem\\b").containsMatchIn(n) -> "après-midi" to 12..17
        Regex("\\bmatin(ee)?\\b").containsMatchIn(n) -> "matin" to 0..11
        Regex("\\bsoir(ee)?\\b").containsMatchIn(n) -> "soir" to 17..23
        Regex("\\b(ce |a |le )?midi\\b").containsMatchIn(n) -> "midi" to 11..13
        else -> null
    }

    /** « aujourd'hui », « demain », « après-demain », sinon « lundi 23 novembre » (avec l'année si ce n'est pas celle-ci). */
    fun libelle(d: LocalDate, aujourdhui: LocalDate): String = when (d) {
        aujourdhui -> "aujourd'hui"
        aujourdhui.plusDays(1) -> "demain"
        aujourdhui.plusDays(2) -> "après-demain"
        aujourdhui.minusDays(1) -> "hier"
        else -> {
            val noms = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
            "${JOURS[d.dayOfWeek.value - 1]} ${if (d.dayOfMonth == 1) "1er" else d.dayOfMonth} ${noms[d.monthValue - 1]}" + if (d.year != aujourdhui.year) " ${d.year}" else ""
        }
    }
}
