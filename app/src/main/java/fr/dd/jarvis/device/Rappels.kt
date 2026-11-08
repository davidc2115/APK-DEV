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

    val REPETITIONS = linkedMapOf("" to "une fois", "jour" to "tous les jours", "ouvres" to "du lundi au vendredi", "semaine" to "chaque semaine",
        "2semaines" to "une semaine sur deux", "mois" to "chaque mois")

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
        val moment = interpreter(quand, LocalDateTime.now()) ?: return "❌ Rappel : je n'ai pas compris quand (« $quand »). Exemples : « demain 9h », « dans 20 min », « 2026-11-09 18:30 »."
        val rep = repetitionDe(repetition)
        val at = moment.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at <= System.currentTimeMillis() + 5_000 && rep.isBlank()) return "❌ Rappel : ce moment (${format(at)}) est déjà passé."
        val l = liste(c)
        val r = Rappel((l.maxOfOrNull { it.id } ?: 0) + 1, t, at, rep)
        enregistrer(c, l + r)
        val exact = planifier(c, r)
        return "⏰ Rappel programmé ${format(at)}${if (rep.isNotBlank()) " (${REPETITIONS[rep]})" else ""} : « $t »." +
            (if (!exact) "\n⚠️ Android n'autorise pas encore les alarmes exactes pour JARVIS : le rappel peut arriver avec quelques minutes de retard (Réglages Android › Applications › Accès spécial › Alarmes et rappels › JARVIS)." else "") +
            "\n[📋 Voir tous mes rappels et automatisations](jarvis://ecran/rappels)"
    }

    internal fun repetitionDe(s: String): String {
        val n = normaliser(s)
        return when {
            n.isBlank() || n.contains("une fois") || n == "non" || n == "aucune" -> ""
            // « tous les 15 jours », « une semaine sur deux », « un dimanche sur deux », « bimensuel » (au sens courant)
            n == "2semaines" || Regex("\\b(15|quinze|14|quatorze) jours\\b|\\b(2|deux) semaines\\b|sur deux|quinzaine|bi.?mensuel|biweekly|every 2 weeks").containsMatchIn(n) -> "2semaines"
            n.contains("ouvr") || n.contains("lundi au vendredi") || n.contains("semaine de travail") || n == "semaine ouvree" -> "ouvres"
            n.contains("jour") || n.contains("quotidien") || n.contains("chaque matin") || n.contains("chaque soir") -> "jour"
            n.contains("mois") || n.contains("mensuel") -> "mois"
            n.contains("semaine") || n.contains("hebdo") -> "semaine"
            n in REPETITIONS -> n
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
        val z = ZoneId.systemDefault()
        var d = java.time.Instant.ofEpochMilli(at).atZone(z).toLocalDateTime()
        val limite = java.time.Instant.ofEpochMilli(apres).atZone(z).toLocalDateTime()
        var n = 0
        while (!d.isAfter(limite) && n < 4000) {
            d = when (repetition) {
                "semaine" -> d.plusWeeks(1)
                "2semaines" -> d.plusWeeks(2)
                "mois" -> d.plusMonths(1)
                "ouvres" -> { var x = d.plusDays(1); while (x.dayOfWeek == DayOfWeek.SATURDAY || x.dayOfWeek == DayOfWeek.SUNDAY) x = x.plusDays(1); x }
                else -> d.plusDays(1)
            }
            n++
        }
        return d.atZone(z).toInstant().toEpochMilli()
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
