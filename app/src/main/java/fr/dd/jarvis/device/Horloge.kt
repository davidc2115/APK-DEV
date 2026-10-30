package fr.dd.jarvis.device

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import fr.dd.jarvis.JarvisApp
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Alarmes, minuteurs et chronomètre gérés par JARVIS lui-même : aucune application ne s'ouvre.
 * Alarme/minuteur : AlarmManager.setAlarmClock (icône d'alarme dans la barre d'état, fiable en veille) + notification sonore.
 * Chronomètre : notification avec compteur. Les alarmes sont conservées et replanifiées au redémarrage du téléphone.
 */
object Horloge {
    const val CH_ALARM = "horloge_alarme"
    const val CH_RUN = "horloge_en_cours"
    const val ACT_FIRE = "fr.dd.jarvis.HORLOGE_FIRE"
    const val ACT_STOP = "fr.dd.jarvis.HORLOGE_STOP"
    private const val PREFS = "horloge"
    private const val CHRONO_ID = 4300

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun channels(c: Context) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        nm(c).createNotificationChannel(NotificationChannel(CH_ALARM, "Alarmes et minuteurs JARVIS", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(snd, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            setBypassDnd(true)
        })
        nm(c).createNotificationChannel(NotificationChannel(CH_RUN, "Minuteur / chronomètre en cours", NotificationManager.IMPORTANCE_LOW))
    }

    // ---- stockage : [{id, kind: alarm|timer, at, label, days[]}]
    private fun load(c: Context): MutableList<JSONObject> {
        val a = runCatching { JSONArray(prefs(c).getString("items", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
    }
    private fun save(c: Context, l: List<JSONObject>) { prefs(c).edit().putString("items", JSONArray(l).toString()).commit() }

    /** Demande l'affichage en pastille dans la barre d'état (Android 16 : notifications « en direct » avec compteur). Ignoré par les versions plus anciennes. */
    private fun enDirect(b: NotificationCompat.Builder, court: String = ""): NotificationCompat.Builder {
        b.addExtras(android.os.Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            if (court.isNotBlank()) putString("android.shortCriticalText", court)
        })
        return b
    }

    private fun pi(c: Context, id: Int, action: String, flags: Int = PendingIntent.FLAG_IMMUTABLE, extra: (Intent) -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(c, id, Intent(c, HorlogeReceiver::class.java).setAction(action).putExtra("id", id).also(extra), PendingIntent.FLAG_UPDATE_CURRENT or flags)

    private fun planifier(c: Context, o: JSONObject) {
        val id = o.getInt("id"); val at = o.getLong("at")
        val show = PendingIntent.getActivity(c, 7, Intent(c, fr.dd.jarvis.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(c, id, ACT_FIRE))
    }

    private fun prochaine(h: Int, m: Int, days: List<Int>): Long {
        val now = LocalDateTime.now()
        var d = now.toLocalDate().atTime(h, m)
        fun ok(x: LocalDateTime): Boolean = days.isEmpty() || (x.dayOfWeek.let { if (it == DayOfWeek.SUNDAY) 1 else it.value + 1 } in days)
        var n = 0
        while ((!d.isAfter(now) || !ok(d)) && n < 9) { d = d.plusDays(1); n++ }
        return d.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun creerAlarme(c: Context, heure: String, libelle: String, jours: String): String {
        val m = Regex("(\\d{1,2})\\s*(?:[:hH]\\s*(\\d{1,2})?)?").find(heure.trim()) ?: return "Heure d'alarme non comprise : « $heure »."
        val h = m.groupValues[1].toInt(); val mn = m.groupValues[2].toIntOrNull() ?: 0
        if (h !in 0..23 || mn !in 0..59) return "Heure invalide : $heure."
        val map = mapOf("dim" to 1, "lun" to 2, "mar" to 3, "mer" to 4, "jeu" to 5, "ven" to 6, "sam" to 7)
        val low = jours.lowercase()
        val days = mutableListOf<Int>()
        if (low.contains("tous") || low.contains("quotidien")) days.addAll(1..7)
        else if (low.contains("semaine") || low.contains("ouvr")) days.addAll(2..6)
        else map.forEach { (k, v) -> if (low.contains(k)) days.add(v) }
        channels(c)
        val id = (System.currentTimeMillis() % 1_000_000_000L).toInt()
        val at = prochaine(h, mn, days)
        val o = JSONObject().put("id", id).put("kind", "alarm").put("at", at).put("label", libelle).put("h", h).put("m", mn).put("days", JSONArray(days))
        val l = load(c); l.add(o); save(c, l); planifier(c, o)
        val quand = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMM 'à' HH:mm", java.util.Locale.FRENCH))
        return "⏰ Alarme réglée : $quand" + (if (days.isNotEmpty()) " (répétée : ${jours.trim()})" else "") + (if (libelle.isNotBlank()) " — $libelle" else "") + "."
    }

    fun creerMinuteur(c: Context, secondes: Int, libelle: String): String {
        if (secondes <= 0) return "Durée du minuteur invalide."
        channels(c)
        val id = (System.currentTimeMillis() % 1_000_000_000L).toInt()
        val at = System.currentTimeMillis() + secondes * 1000L
        val o = JSONObject().put("id", id).put("kind", "timer").put("at", at).put("label", libelle).put("days", JSONArray())
        val l = load(c); l.add(o); save(c, l); planifier(c, o)
        // Compte à rebours visible dans la barre de notifications.
        val n = enDirect(NotificationCompat.Builder(c, CH_RUN)).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setContentText("Temps restant")
            .setContentTitle("⏳ Minuteur" + (if (libelle.isNotBlank()) " — $libelle" else "")).setWhen(at).setUsesChronometer(true).setChronometerCountDown(true)
            .setOngoing(true).setOnlyAlertOnce(true).setTimeoutAfter(secondes * 1000L + 2000)
            .addAction(0, "Annuler", pi(c, id, ACT_STOP)).build()
        runCatching { nm(c).notify(id, n) }
        val h = secondes / 3600; val mi = secondes % 3600 / 60; val se = secondes % 60
        val txt = listOfNotNull(if (h > 0) "$h h" else null, if (mi > 0) "$mi min" else null, if (se > 0) "$se s" else null).joinToString(" ")
        return "⏳ Minuteur de $txt lancé" + (if (libelle.isNotBlank()) " ($libelle)" else "") + " — sans ouvrir aucune application."
    }

    fun supprimerAlarme(c: Context, heure: String, libelle: String): String {
        val l = load(c)
        val m = Regex("(\\d{1,2})\\s*[:hH]\\s*(\\d{0,2})").find(heure)
        val cibles = l.filter { it.optString("kind") == "alarm" && (
            (m != null && it.optInt("h") == m.groupValues[1].toInt() && it.optInt("m") == (m.groupValues[2].toIntOrNull() ?: 0)) ||
            (libelle.isNotBlank() && it.optString("label").contains(libelle, true)) || (m == null && libelle.isBlank())) }
        if (cibles.isEmpty()) return "Aucune alarme ne correspond."
        cibles.forEach { annuler(c, it.getInt("id")) }
        save(c, l.filter { it !in cibles })
        return "🗑️ ${cibles.size} alarme(s) supprimée(s)."
    }

    private fun annuler(c: Context, id: Int) { runCatching { am(c).cancel(pi(c, id, ACT_FIRE)) }; runCatching { nm(c).cancel(id) } }

    fun liste(c: Context): String {
        val f = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.FRENCH)
        val l = load(c).sortedBy { it.optLong("at") }
        if (l.isEmpty()) return "Aucune alarme ni minuteur en cours."
        return l.joinToString("\n") { (if (it.optString("kind") == "alarm") "⏰ " else "⏳ ") + java.time.Instant.ofEpochMilli(it.optLong("at")).atZone(ZoneId.systemDefault()).format(f) + (it.optString("label").takeIf { s -> s.isNotBlank() }?.let { s -> " — $s" } ?: "") }
    }

    // ---- chronomètre (notification avec compteur)
    fun chronometre(c: Context, action: String): String {
        channels(c)
        val a = action.trim().lowercase()
        val debut = prefs(c).getLong("chrono", 0L)
        return when {
            a.startsWith("arr") || a.startsWith("stop") || a.startsWith("fin") -> {
                if (debut == 0L) "Le chronomètre n'est pas lancé."
                else { prefs(c).edit().putLong("chrono", 0L).commit(); nm(c).cancel(CHRONO_ID); "⏱️ Chronomètre arrêté : " + formater(System.currentTimeMillis() - debut) + "." }
            }
            a.startsWith("etat") || a.startsWith("état") || a.startsWith("temps") || a.startsWith("lap") || a.startsWith("tour") ->
                if (debut == 0L) "Le chronomètre n'est pas lancé." else "⏱️ Temps écoulé : " + formater(System.currentTimeMillis() - debut) + "."
            else -> {
                if (debut != 0L) "⏱️ Chronomètre déjà lancé : " + formater(System.currentTimeMillis() - debut) + "."
                else {
                    val now = System.currentTimeMillis()
                    prefs(c).edit().putLong("chrono", now).commit()
                    val n = enDirect(NotificationCompat.Builder(c, CH_RUN)).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("⏱️ Chronomètre").setContentText("Temps écoulé").setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                        .setWhen(now).setUsesChronometer(true).setOngoing(true).setOnlyAlertOnce(true)
                        .addAction(0, "Arrêter", pi(c, CHRONO_ID, ACT_STOP)).build()
                    runCatching { nm(c).notify(CHRONO_ID, n) }
                    "⏱️ Chronomètre démarré (visible dans la barre de notifications), sans ouvrir aucune application."
                }
            }
        }
    }

    private fun formater(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    /** Déclenchement d'une alarme / d'un minuteur. */
    fun sonner(c: Context, id: Int) {
        channels(c)
        val l = load(c)
        val o = l.firstOrNull { it.optInt("id") == id } ?: return
        val timer = o.optString("kind") == "timer"
        val label = o.optString("label")
        val n = NotificationCompat.Builder(c, CH_ALARM).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (timer) "⏳ Minuteur terminé" else "⏰ Alarme").setContentText(label.ifBlank { if (timer) "Le temps est écoulé." else "C'est l'heure !" })
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true).setTimeoutAfter(90_000)
            .setContentIntent(PendingIntent.getActivity(c, 8, Intent(c, fr.dd.jarvis.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Arrêter", pi(c, id, ACT_STOP)).build()
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT   // le son se répète jusqu'à l'arrêt
        runCatching { nm(c).cancel(id) }
        runCatching { nm(c).notify(id + 1, n) }
        // Alarme répétée : on programme la prochaine occurrence ; sinon on l'oublie.
        val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
        if (!timer && days.isNotEmpty()) {
            o.put("at", prochaine(o.optInt("h"), o.optInt("m"), days)); save(c, l); planifier(c, o)
        } else save(c, l.filter { it !== o })
    }

    fun arreter(c: Context, id: Int) {
        runCatching { nm(c).cancel(id) }; runCatching { nm(c).cancel(id + 1) }
        if (id == CHRONO_ID) { prefs(c).edit().putLong("chrono", 0L).commit(); return }
        val l = load(c)
        val o = l.firstOrNull { it.optInt("id") == id }
        // « Annuler » un minuteur en cours : on le retire ; une alarme répétée qui sonne reste programmée.
        if (o != null && o.optString("kind") == "timer" && o.optLong("at") > System.currentTimeMillis()) { annuler(c, id); save(c, l.filter { it !== o }) }
    }

    /** Après redémarrage : replanifie tout. */
    fun restaurer(c: Context) {
        channels(c)
        val now = System.currentTimeMillis()
        val l = load(c).filter { it.optLong("at") > now || it.optJSONArray("days")?.length() ?: 0 > 0 }.toMutableList()
        l.forEach { o ->
            val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
            if (o.optLong("at") <= now && o.optString("kind") == "alarm") o.put("at", prochaine(o.optInt("h"), o.optInt("m"), days))
            planifier(c, o)
        }
        save(c, l)
    }
}

class HorlogeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        when (intent.action) {
            Horloge.ACT_FIRE -> Horloge.sonner(context, id)
            Horloge.ACT_STOP -> Horloge.arreter(context, id)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Horloge.restaurer(context)
        }
    }
}
