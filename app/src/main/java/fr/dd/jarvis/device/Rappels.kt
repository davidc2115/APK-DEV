package fr.dd.jarvis.device

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Un rappel : un message qui arrive en notification à l'heure dite (une fois ou en répétition). */
data class Rappel(
    val id: Int,
    val texte: String,
    val at: Long,
    val repetition: String = "",
    val fait: Boolean = false,
    val cree: Long = System.currentTimeMillis(),
    val dernier: Long = 0L,
)

/**
 * Rappels de JARVIS (« rappelle-moi d'appeler Martin demain à 9 h ») : notification à l'heure exacte avec « ✅ Fait »
 * et « ⏰ Plus tard », répétition possible (jour, semaine, lundi → vendredi, mois). Gardés 7 jours après leur passage
 * pour la page « Rappels et automatisations ». Replanifiés après un redémarrage ou une mise à jour.
 */
object Rappels {
    const val CANAL = "rappels_jarvis"
    const val ACT_SONNER = "fr.dd.jarvis.RAPPEL_SONNER"
    const val ACT_FAIT = "fr.dd.jarvis.RAPPEL_FAIT"
    const val ACT_PLUS_TARD = "fr.dd.jarvis.RAPPEL_PLUS_TARD"
    private const val BASE_NOTIF = 7800

    /** Répétitions proposées en un geste sur la page (code → libellé). Toute autre répétition s'écrit en français. */
    val REPETITIONS = linkedMapOf("" to "une fois", "1:jour" to "tous les jours", "2:jour" to "un jour sur deux", "jours:1,2,3,4,5" to "du lundi au vendredi",
        "1:semaine" to "chaque semaine", "2:semaine" to "une semaine sur deux", "1:mois" to "chaque mois", "2:mois" to "tous les deux mois",
        "3:mois" to "tous les trois mois", "1:an" to "chaque année")

    private fun fichier(c: Context) = File(c.filesDir, "rappels.json")

    @Synchronized fun liste(c: Context): List<Rappel> = runCatching {
        val a = JSONArray(fichier(c).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Rappel(it.optInt("id"), it.optString("texte"), it.optLong("at"), it.optString("repetition"), it.optBoolean("fait"), it.optLong("cree"), it.optLong("dernier"))
        }
    }.getOrDefault(emptyList())

    @Synchronized private fun enregistrer(c: Context, l: List<Rappel>) {
        // Les rappels passés (non répétés) sont gardés 7 jours pour l'historique de la page.
        val limite = System.currentTimeMillis() - 7 * 86_400_000L
        val a = JSONArray()
        l.filter { it.repetition.isNotBlank() || maxOf(it.at, it.dernier) > limite }.forEach {
            a.put(JSONObject().put("id", it.id).put("texte", it.texte).put("at", it.at).put("repetition", it.repetition)
                .put("fait", it.fait).put("cree", it.cree).put("dernier", it.dernier))
        }
        fichier(c).writeText(a.toString())
    }

    private fun mettreAJour(c: Context, r: Rappel) = enregistrer(c, liste(c).map { if (it.id == r.id) r else it })

    // ------------------------------------------------------------------ création

    /** Crée un rappel ; [quand] : « 2026-11-09 09:00 », « 18h30 », « dans 20 min », « demain 9h », « vendredi 17h »… */
    fun creer(c: Context, texte: String, quand: String, repetition: String = ""): String {
        val t = texte.trim().ifBlank { return "❌ Rappel : précise ce que je dois te rappeler." }
        // Répétition : le paramètre, sinon dite dans « quand » ou dans le texte (« un jour sur deux à 8h »).
        val rep = repetitionDe(repetition).ifBlank { repetitionDe(quand, strict = true) }.ifBlank { repetitionDe(t, strict = true) }
        val moment = interpreter(quand, LocalDateTime.now())?.let { premiere(it, rep) }
            ?: return "❌ Rappel : je n'ai pas compris quand (« $quand »). Exemples : « demain 9h », « dans 20 min », « 2026-11-09 18:30 »."
        val at = moment.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at <= System.currentTimeMillis() + 5_000 && rep.isBlank()) return "❌ Rappel : ce moment (${format(at)}) est déjà passé."
        val l = liste(c)
        val r = Rappel((l.maxOfOrNull { it.id } ?: 0) + 1, t, at, rep)
        enregistrer(c, l + r)
        val exact = planifier(c, r)
        return "⏰ Rappel programmé ${format(at)}${if (rep.isNotBlank()) " (${libelle(rep)})" else ""} : « $t »." +
            (if (!exact) "\n⚠️ Android n'autorise pas encore les alarmes exactes pour JARVIS : le rappel peut arriver avec quelques minutes de retard (Réglages Android › Applications › Accès spécial › Alarmes et rappels › JARVIS)." else "") +
            "\n[📋 Voir tous mes rappels et automatisations](jarvis://ecran/rappels)"
    }

    /** Règle de répétition : tous les [n] jours / semaines / mois / ans, ou certains jours de la semaine (1 = lundi … 7 = dimanche). */
    sealed class Regle {
        data class Pas(val n: Int, val unite: String) : Regle()
        data class Jours(val jours: Set<Int>) : Regle()
    }

    /** Code enregistré → règle (anciens codes « jour », « semaine », « 2semaines », « mois », « ouvres » compris). */
    internal fun regle(code: String): Regle? {
        val c = code.trim()
        return when {
            c.isBlank() -> null
            c == "jour" -> Regle.Pas(1, "jour")
            c == "semaine" -> Regle.Pas(1, "semaine")
            c == "2semaines" -> Regle.Pas(2, "semaine")
            c == "mois" -> Regle.Pas(1, "mois")
            c == "ouvres" -> Regle.Jours(setOf(1, 2, 3, 4, 5))
            c.startsWith("jours:") -> c.removePrefix("jours:").split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
                .takeIf { it.isNotEmpty() }?.let { Regle.Jours(it) }
            else -> Regex("^(\\d{1,3}):(jour|semaine|mois|an)$").matchEntire(c)?.let { m ->
                m.groupValues[1].toInt().takeIf { it > 0 }?.let { Regle.Pas(it, m.groupValues[2]) }
            }
        }
    }

    private val NOMS_JOURS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private fun et(l: List<String>) = if (l.size <= 1) l.joinToString() else l.dropLast(1).joinToString(", ") + " et " + l.last()

    /** Libellé lisible d'une répétition : « un jour sur deux », « tous les 3 mois », « chaque lundi et jeudi »… */
    fun libelle(code: String): String = when (val r = regle(code)) {
        null -> "une fois"
        is Regle.Jours -> when (r.jours) {
            setOf(1, 2, 3, 4, 5) -> "du lundi au vendredi"
            setOf(6, 7) -> "le week-end"
            (1..7).toSet() -> "tous les jours"
            else -> "chaque " + et(r.jours.sorted().map { NOMS_JOURS[it - 1] })
        }
        is Regle.Pas -> when (r.unite) {
            "jour" -> when (r.n) { 1 -> "tous les jours"; 2 -> "un jour sur deux"; else -> "tous les ${r.n} jours" }
            "semaine" -> when (r.n) { 1 -> "chaque semaine"; 2 -> "une semaine sur deux"; else -> "toutes les ${r.n} semaines" }
            "mois" -> when (r.n) { 1 -> "chaque mois"; 2 -> "tous les deux mois"; 3 -> "tous les trois mois"; 6 -> "tous les six mois"; else -> "tous les ${r.n} mois" }
            else -> if (r.n == 1) "chaque année" else "tous les ${r.n} ans"
        }
    }

    private val NOMBRES = mapOf("un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8,
        "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16, "vingt" to 20, "trente" to 30)
    private fun nombre(m: String): Int? = m.toIntOrNull() ?: NOMBRES[m]
    private fun unite(m: String): String? = when {
        m.startsWith("jour") -> "jour"
        m.startsWith("semaine") || m in NOMS_JOURS.map { it } || m.removeSuffix("s") in NOMS_JOURS || m.startsWith("week") -> "semaine"
        m.startsWith("mois") -> "mois"
        m.startsWith("an") -> "an"
        else -> null
    }
    private fun pas(n: Int, u: String): String = when {
        // Usage courant : « tous les 15 jours » = toutes les deux semaines, « tous les 8 jours » = chaque semaine.
        u == "jour" && n == 15 -> "2:semaine"
        u == "jour" && n == 8 -> "1:semaine"
        u == "jour" && n % 7 == 0 -> "${n / 7}:semaine"
        u == "mois" && n % 12 == 0 -> "${n / 12}:an"
        else -> "$n:$u"
    }

    /**
     * Répétition demandée, en code : « 2:jour » (un jour sur deux), « 3:semaine », « 2:mois », « 1:an », « jours:1,4 »
     * (lundi et jeudi)… Comprend le français courant : « tous les deux jours », « un jour sur deux », « tous les 15 jours »,
     * « un dimanche sur deux », « toutes les 3 semaines », « tous les deux mois », « chaque trimestre », « tous les ans »,
     * « tous les lundis et jeudis », « du lundi au vendredi », « le week-end ». [strict] : seulement si la phrase le dit
     * clairement (pour la chercher dans « quand » ou le texte du rappel).
     */
    internal fun repetitionDe(s: String, strict: Boolean = false): String {
        regle(s)?.let { return s.trim() }
        val n = normaliser(s).replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        if (n.isBlank() || Regex("^(une fois|non|aucune?|jamais|pas de repetition)$").matches(n)) return ""
        val mot = "(\\d{1,3}|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente)"
        val jourSem = NOMS_JOURS.joinToString("|")
        // « un jour sur deux », « une semaine sur trois », « un mois sur deux », « un dimanche sur deux », « un week-end sur deux »
        Regex("\\b(?:un|une) (jour|semaine|mois|an|annee|$jourSem|week end) sur $mot\\b").find(n)?.let { m ->
            val k = nombre(m.groupValues[2]) ?: return@let
            val u = unite(m.groupValues[1].replace("annee", "an")) ?: return@let
            return pas(k, u)
        }
        // « tous les deux jours », « toutes les 3 semaines », « tous les 2 mois », « tous les 5 ans »
        Regex("\\b(?:tous|toutes) les $mot (jours?|semaines?|mois|ans?|annees?)\\b").find(n)?.let { m ->
            val k = nombre(m.groupValues[1]) ?: return@let
            val u = unite(m.groupValues[2].replace("annee", "an")) ?: return@let
            return pas(k, u)
        }
        // Mots d'une fréquence
        when {
            Regex("quinzaine|bi ?mensuel|biweekly|every (2|two) weeks").containsMatchIn(n) -> return "2:semaine"
            Regex("bimestri").containsMatchIn(n) -> return "2:mois"
            Regex("trimestr").containsMatchIn(n) -> return "3:mois"
            Regex("semestr").containsMatchIn(n) -> return "6:mois"
            Regex("\\bannuel|chaque annee|tous les ans|une fois par an|every year|yearly").containsMatchIn(n) -> return "1:an"
        }
        // Jours de la semaine : « tous les lundis et jeudis », « chaque mardi », « du lundi au vendredi », « tous les week-ends »
        val repete = Regex("\\b(tous|toutes|chaque)\\b").containsMatchIn(n)
        if (Regex("du lundi au vendredi|jours? ouvr|en semaine|semaine de travail|weekdays?").containsMatchIn(n) && (repete || !strict || n.contains("lundi au vendredi"))) return "jours:1,2,3,4,5"
        if (repete) {
            if (Regex("\\bweek ?ends?\\b").containsMatchIn(n)) return "jours:6,7"
            val jours = NOMS_JOURS.mapIndexedNotNull { i, j -> if (Regex("\\b${j}s?\\b").containsMatchIn(n)) i + 1 else null }
            if (jours.isNotEmpty()) return if (jours.size == 7) "1:jour" else "jours:" + jours.joinToString(",")
        }
        when {
            Regex("\\bmensuel|chaque mois|tous les mois|une fois par mois|every month|monthly").containsMatchIn(n) -> return "1:mois"
            Regex("hebdo|chaque semaine|toutes les semaines|une fois par semaine|every week|weekly").containsMatchIn(n) -> return "1:semaine"
            Regex("quotidien|chaque jour|tous les jours|chaque (matin|soir|midi)|tous les (matins|soirs|midis)|every day|daily").containsMatchIn(n) -> return "1:jour"
        }
        if (strict) return ""
        // Mots seuls (paramètre « répétition » de l'outil) : « jour », « semaine », « mois », « an »…
        return when (n) {
            "jour", "jours", "journalier" -> "1:jour"
            "semaine", "semaines" -> "1:semaine"
            "mois" -> "1:mois"
            "an", "ans", "annee" -> "1:an"
            "week end" -> "jours:6,7"
            else -> ""
        }
    }

    // ------------------------------------------------------------------ interprétation de la date

    private val JOURS = mapOf("lundi" to DayOfWeek.MONDAY, "mardi" to DayOfWeek.TUESDAY, "mercredi" to DayOfWeek.WEDNESDAY, "jeudi" to DayOfWeek.THURSDAY,
        "vendredi" to DayOfWeek.FRIDAY, "samedi" to DayOfWeek.SATURDAY, "dimanche" to DayOfWeek.SUNDAY)

    /** Moment voulu (heure locale), ou null si incompréhensible. */
    internal fun interpreter(brut: String, maintenant: LocalDateTime): LocalDateTime? {
        val s = brut.trim().lowercase(Locale.FRENCH)
        if (s.isBlank()) return null
        // ISO ou presque : 2026-11-09T09:00, 2026-11-09 09:00(:00)(+01:00)
        Regex("(\\d{4})-(\\d{2})-(\\d{2})[t ](\\d{1,2})[:h](\\d{2})").find(s)?.let { m ->
            val (a, mo, j, h, mi) = m.destructured
            return runCatching { LocalDateTime.of(a.toInt(), mo.toInt(), j.toInt(), h.toInt(), mi.toInt()) }.getOrNull()
        }
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(s)?.let { m -> val (a, mo, j) = m.destructured; return runCatching { LocalDateTime.of(a.toInt(), mo.toInt(), j.toInt(), 9, 0) }.getOrNull() }
        val n = normaliser(s)
        // Relatif : « dans 20 min », « dans 1h30 », « dans 2 heures », « dans 3 jours »
        Regex("^dans (\\d+) ?(min|minutes?|mn|h|heures?|j|jours?|semaines?)$").find(n)?.let { m ->
            val v = m.groupValues[1].toLong()
            return when (m.groupValues[2].first()) { 'm' -> maintenant.plusMinutes(v); 'h' -> maintenant.plusHours(v); 'j' -> maintenant.plusDays(v); else -> maintenant.plusWeeks(v) }
        }
        Regex("^dans (\\d+) ?h(?:eures?)? ?(?:et )?(\\d{1,2})(?: ?min(?:utes?)?)?$").find(n)?.let { m ->
            return maintenant.plusHours(m.groupValues[1].toLong()).plusMinutes(m.groupValues[2].toLong())
        }
        if (Regex("^dans (une|un) (heure|minute|jour|semaine)$").matches(n)) return when {
            n.endsWith("heure") -> maintenant.plusHours(1); n.endsWith("minute") -> maintenant.plusMinutes(1)
            n.endsWith("jour") -> maintenant.plusDays(1); else -> maintenant.plusWeeks(1)
        }
        if (n.startsWith("dans une demi heure") || n == "dans 30 min") return maintenant.plusMinutes(30)
        // Heure dans la phrase : 9h, 9 h 30, 18:45, « midi », « minuit »
        val heure: LocalTime? = Regex("(\\d{1,2}) ?(?:h|:|heures?) ?(\\d{2})?").find(s)?.let { m ->
            runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0) }.getOrNull()
        } ?: when {
            Regex("\\bmidi\\b").containsMatchIn(n) -> LocalTime.NOON
            Regex("\\bminuit\\b").containsMatchIn(n) -> LocalTime.MIDNIGHT
            Regex("\\bmatin\\b").containsMatchIn(n) -> LocalTime.of(9, 0)
            Regex("\\bsoir\\b").containsMatchIn(n) -> LocalTime.of(19, 0)
            Regex("apres midi").containsMatchIn(n) -> LocalTime.of(14, 0)
            else -> null
        }
        // Jour : aujourd'hui, demain, après-demain, un jour de la semaine, une date « 12/11 » ou « 12 novembre »
        var jour: LocalDate? = when {
            n.contains("apres demain") -> maintenant.toLocalDate().plusDays(2)
            Regex("\\bdemain\\b").containsMatchIn(n) -> maintenant.toLocalDate().plusDays(1)
            n.contains("aujourd hui") || n.contains("ce soir") || n.contains("ce matin") || n.contains("cet apres midi") -> maintenant.toLocalDate()
            else -> null
        }
        if (jour == null) JOURS.entries.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(n) }?.let { (_, d) ->
            var x = maintenant.toLocalDate()
            do { x = x.plusDays(1) } while (x.dayOfWeek != d)
            if (maintenant.dayOfWeek == d && heure != null && heure.isAfter(maintenant.toLocalTime()) && !n.contains("prochain")) x = maintenant.toLocalDate()
            jour = x
        }
        if (jour == null) Regex("(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?").find(s)?.let { m ->
            val an = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            jour = runCatching { LocalDate.of(an ?: maintenant.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
                ?.let { if (an == null && it.isBefore(maintenant.toLocalDate())) it.plusYears(1) else it }
        }
        if (jour == null) Regex("\\ble (\\d{1,2}|1er) (?:de chaque mois|du mois|de tous les mois|chaque mois)").find(n)?.let { m ->
            val j = m.groupValues[1].removeSuffix("er").toInt().coerceIn(1, 31)
            var x = maintenant.toLocalDate().withDayOfMonth(1)
            repeat(14) {
                val c = x.withDayOfMonth(minOf(j, x.lengthOfMonth()))
                if (c.atTime(heure ?: LocalTime.of(9, 0)).isAfter(maintenant)) { jour = c; return@let }
                x = x.plusMonths(1)
            }
        }
        if (jour == null) {
            val mois = listOf("janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre")
            Regex("(\\d{1,2}|1er) (${mois.joinToString("|")})(?: (\\d{4}))?").find(n)?.let { m ->
                val j = m.groupValues[1].removeSuffix("er").toInt()
                val an = m.groupValues[3].toIntOrNull()
                jour = runCatching { LocalDate.of(an ?: maintenant.year, mois.indexOf(m.groupValues[2]) + 1, j) }.getOrNull()
                    ?.let { if (an == null && it.isBefore(maintenant.toLocalDate())) it.plusYears(1) else it }
            }
        }
        if (jour == null && heure == null) return null
        val h = heure ?: LocalTime.of(9, 0)
        val d = jour ?: maintenant.toLocalDate().let { if (h.isAfter(maintenant.toLocalTime())) it else it.plusDays(1) }
        return d.atTime(h)
    }

    /** Occurrence suivante d'un rappel répété (strictement après [apres]). */
    internal fun suivante(at: Long, repetition: String, apres: Long = System.currentTimeMillis()): Long {
        val r = regle(repetition) ?: return at
        val z = ZoneId.systemDefault()
        val debut = java.time.Instant.ofEpochMilli(at).atZone(z).toLocalDateTime()
        val limite = java.time.Instant.ofEpochMilli(apres).atZone(z).toLocalDateTime()
        var d = debut
        when (r) {
            // Calculé depuis la date de départ (k × pas) : un rappel du 31 reste au 31 (ou au dernier jour du mois).
            is Regle.Pas -> {
                var k = 1L
                do {
                    d = when (r.unite) {
                        "jour" -> debut.plusDays(r.n * k); "semaine" -> debut.plusWeeks(r.n * k)
                        "mois" -> debut.plusMonths(r.n * k); else -> debut.plusYears(r.n * k)
                    }
                    k++
                } while (!d.isAfter(limite) && k < 200_000)
            }
            is Regle.Jours -> {
                var n = 0
                do { d = d.plusDays(1); n++ } while ((d.dayOfWeek.value !in r.jours || !d.isAfter(limite)) && n < 200_000)
            }
        }
        return d.atZone(z).toInstant().toEpochMilli()
    }

    /** Première occurrence d'un rappel à jours précis : avance jusqu'au premier jour permis. */
    internal fun premiere(moment: LocalDateTime, code: String): LocalDateTime {
        val r = regle(code) as? Regle.Jours ?: return moment
        var d = moment
        var n = 0
        while (d.dayOfWeek.value !in r.jours && n < 8) { d = d.plusDays(1); n++ }
        return d
    }

    fun format(at: Long): String {
        val d = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val auj = LocalDate.now()
        val jour = when (d.toLocalDate()) {
            auj -> "aujourd'hui"; auj.plusDays(1) -> "demain"; auj.minusDays(1) -> "hier"
            else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH))
        }
        return "$jour à ${d.format(DateTimeFormatter.ofPattern("HH'h'mm", Locale.FRENCH))}"
    }

    // ------------------------------------------------------------------ planification

    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pi(c: Context, id: Int, action: String): PendingIntent = PendingIntent.getBroadcast(c, 880_000 + id * 3 + when (action) { ACT_FAIT -> 1; ACT_PLUS_TARD -> 2; else -> 0 },
        Intent(c, RappelReceiver::class.java).setAction(action).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Programme le rappel ; vrai si l'heure sera exacte. */
    private fun planifier(c: Context, r: Rappel): Boolean {
        val exact = Build.VERSION.SDK_INT < 31 || am(c).canScheduleExactAlarms()
        runCatching {
            if (exact) am(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi(c, r.id, ACT_SONNER))
            else am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi(c, r.id, ACT_SONNER))
        }
        return exact
    }

    private fun annuler(c: Context, id: Int) {
        runCatching { am(c).cancel(pi(c, id, ACT_SONNER)) }
        runCatching { NotificationManagerCompat.from(c).cancel(BASE_NOTIF + id) }
    }

    fun supprimer(c: Context, id: Int): Rappel? {
        val r = liste(c).firstOrNull { it.id == id } ?: return null
        annuler(c, id)
        enregistrer(c, liste(c).filter { it.id != id })
        return r
    }

    /** Supprime le(s) rappel(s) à venir qui contiennent [texte] ; renvoie leur nombre. */
    fun supprimerParTexte(c: Context, texte: String): List<Rappel> {
        val n = normaliser(texte)
        if (n.isBlank()) return emptyList()
        val cibles = liste(c).filter { normaliser(it.texte).contains(n) && (it.repetition.isNotBlank() || it.at > System.currentTimeMillis()) }
        cibles.forEach { supprimer(c, it.id) }
        return cibles
    }

    /** Après un redémarrage ou une mise à jour : replanifie les rappels à venir (et rattrape les répétés). */
    fun restaurer(c: Context) {
        val now = System.currentTimeMillis()
        liste(c).forEach { r ->
            when {
                r.at > now -> planifier(c, r)
                r.repetition.isNotBlank() -> { val x = r.copy(at = suivante(r.at, r.repetition, now)); mettreAJour(c, x); planifier(c, x) }
            }
        }
    }

    // ------------------------------------------------------------------ déclenchement

    fun sonner(c: Context, id: Int) {
        val r = liste(c).firstOrNull { it.id == id } ?: return
        if (Build.VERSION.SDK_INT >= 26) c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, "Rappels", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        val ouvrir = PendingIntent.getActivity(c, 880_000 - id, Intent(c, fr.dd.jarvis.MainActivity::class.java).putExtra("terrain", "rappels")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            NotificationManagerCompat.from(c).notify(BASE_NOTIF + id, NotificationCompat.Builder(c, CANAL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("⏰ Rappel").setContentText(r.texte)
                .setStyle(NotificationCompat.BigTextStyle().bigText(r.texte)).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER).setContentIntent(ouvrir).setAutoCancel(true)
                .addAction(0, "✅ Fait", pi(c, id, ACT_FAIT)).addAction(0, "⏰ Dans 10 min", pi(c, id, ACT_PLUS_TARD)).build())
        }
        // Dit à voix haute si JARVIS parle (voiture, maison) — sans bloquer si la voix n'est pas prête.
        runCatching { (c.applicationContext as? fr.dd.jarvis.JarvisApp)?.let { app -> if (app.enVoiture) fr.dd.jarvis.service.CarBridge.post(app, "⏰ Rappel : ${r.texte}") } }
        val now = System.currentTimeMillis()
        if (r.repetition.isNotBlank()) {
            val x = r.copy(at = suivante(r.at, r.repetition, now), dernier = now, fait = false)
            mettreAJour(c, x); planifier(c, x)
        } else mettreAJour(c, r.copy(dernier = now))
    }

    fun fait(c: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(c).cancel(BASE_NOTIF + id) }
        liste(c).firstOrNull { it.id == id }?.let { if (it.repetition.isBlank()) mettreAJour(c, it.copy(fait = true)) }
    }

    /** Décale un rappel (et toute sa série) de [jours] : ex. une semaine pour caler « une semaine sur deux » sur la bonne. */
    fun decaler(c: Context, id: Int, jours: Int): Rappel? {
        val r = liste(c).firstOrNull { it.id == id } ?: return null
        annuler(c, id)
        val x = r.copy(at = r.at + jours * 86_400_000L)
        mettreAJour(c, x); planifier(c, x)
        return x
    }

    /** Pour une répétition « une X sur N » : libellé du décalage d'une unité (« d'un jour », « d'une semaine »…), sinon null. */
    fun decalage(code: String): String? = (regle(code) as? Regle.Pas)?.takeIf { it.n > 1 }?.let {
        when (it.unite) { "jour" -> "d'un jour"; "semaine" -> "d'une semaine"; "mois" -> "d'un mois"; else -> "d'un an" }
    }

    /** Décale toute la série d'une unité (choisir le bon jour pour « un jour sur deux », la bonne semaine…). */
    fun decalerDUnPas(c: Context, id: Int): Rappel? {
        val r = liste(c).firstOrNull { it.id == id } ?: return null
        val p = regle(r.repetition) as? Regle.Pas ?: return null
        val z = ZoneId.systemDefault()
        val d = java.time.Instant.ofEpochMilli(r.at).atZone(z).toLocalDateTime()
        val x = when (p.unite) { "jour" -> d.plusDays(1); "semaine" -> d.plusWeeks(1); "mois" -> d.plusMonths(1); else -> d.plusYears(1) }
        annuler(c, id)
        val n = r.copy(at = x.atZone(z).toInstant().toEpochMilli())
        mettreAJour(c, n); planifier(c, n)
        return n
    }

    fun plusTard(c: Context, id: Int, minutes: Int = 10) {
        runCatching { NotificationManagerCompat.from(c).cancel(BASE_NOTIF + id) }
        val r = liste(c).firstOrNull { it.id == id } ?: return
        val at = System.currentTimeMillis() + minutes * 60_000L
        if (r.repetition.isBlank()) { val x = r.copy(at = at, fait = false); mettreAJour(c, x); planifier(c, x) }
        else { val l = liste(c); val x = Rappel((l.maxOfOrNull { it.id } ?: 0) + 1, r.texte, at); enregistrer(c, l + x); planifier(c, x) }
    }
}

class RappelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        when (intent.action) {
            Rappels.ACT_SONNER -> Rappels.sonner(context, id)
            Rappels.ACT_FAIT -> Rappels.fait(context, id)
            Rappels.ACT_PLUS_TARD -> Rappels.plusTard(context, id)
        }
    }
}
