package fr.dd.jarvis.device

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.provider.AlarmClock
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
    const val CH_ALARM = "horloge_alarme_v2"
    const val CH_RUN = "horloge_en_cours_v2"
    const val ACT_FIRE = "fr.dd.jarvis.HORLOGE_FIRE"
    const val ACT_STOP = "fr.dd.jarvis.HORLOGE_STOP"
    private const val PREFS = "horloge"
    private const val CHRONO_ID = 4300

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Android 12+ exige un accès spécial pour programmer une alarme exacte fiable en veille. */
    private fun exactAutorise(c: Context): Boolean = android.os.Build.VERSION.SDK_INT < 31 || am(c).canScheduleExactAlarms()

    private fun messageAccesExact(): String = "❌ Action non programmée : Android n'autorise pas encore les alarmes exactes pour JARVIS. Active une seule fois « Alarmes et rappels » dans Réglages Android > Applications > Accès spécial des applications > Alarmes et rappels > JARVIS. Ensuite, les alarmes et minuteurs seront lancés directement en arrière-plan, sans confirmation à chaque demande."

    /** Ne jamais annoncer un minuteur/chronomètre actif si Android bloque ses notifications. */
    private fun notificationsAutorisees(c: Context): Boolean {
        channels(c)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        if (!androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled()) return false
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            nm(c).getNotificationChannel(CH_RUN)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return false
        return true
    }

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

    private fun accorderAccesExactParShizuku(c: Context): Boolean {
        if (exactAutorise(c)) return true
        if (!ShizukuShell.ready()) return false
        ShizukuShell.exec("cmd appops set " + c.packageName + " SCHEDULE_EXACT_ALARM allow")
        return exactAutorise(c)
    }

    private fun planifier(c: Context, o: JSONObject) {
        val id = o.getInt("id")
        val at = o.getLong("at")
        val operation = pi(c, id, ACT_FIRE)
        val manager = am(c)
        check(exactAutorise(c)) { "Accès Alarmes et rappels non accordé." }
        val show = PendingIntent.getActivity(c, 7, Intent(c, fr.dd.jarvis.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
    }
    private fun prochaine(h: Int, m: Int, days: List<Int>): Long {
        val now = LocalDateTime.now()
        var d = now.toLocalDate().atTime(h, m)
        fun ok(x: LocalDateTime): Boolean = days.isEmpty() || (x.dayOfWeek.let { if (it == DayOfWeek.SUNDAY) 1 else it.value + 1 } in days)
        var n = 0
        while ((!d.isAfter(now) || !ok(d)) && n < 9) { d = d.plusDays(1); n++ }
        return d.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun nouvelId(c: Context): Int {
        val id = prefs(c).getInt("next_id", 10000)
        prefs(c).edit().putInt("next_id", if (id >= 2_000_000_000) 10000 else id + 1).commit()
        return id
    }

    /** Transmet la création d'une alarme à l'application Horloge native Android/Xiaomi. */
    fun creerAlarme(c: Context, heure: String, libelle: String, jours: String): String {
        val match = Regex("(\\d{1,2})\\s*(?:[:hH]\\s*(\\d{1,2})?)?").find(heure.trim())
            ?: return "Heure d'alarme non comprise : « $heure »."
        val h = match.groupValues[1].toIntOrNull() ?: return "Heure invalide : $heure."
        val mn = match.groupValues[2].toIntOrNull() ?: 0
        if (h !in 0..23 || mn !in 0..59) return "Heure invalide : $heure."
        val low = jours.lowercase()
        val dayMap = listOf("dim" to 1, "lun" to 2, "mar" to 3, "mer" to 4, "jeu" to 5, "ven" to 6, "sam" to 7)
        val days = when {
            low.contains("tous") || low.contains("quotidien") -> (1..7).toList()
            low.contains("semaine") || low.contains("ouvr") -> (2..6).toList()
            else -> dayMap.filter { low.contains(it.first) }.map { it.second }
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, mn)
            putExtra(AlarmClock.EXTRA_MESSAGE, libelle.ifBlank { "Alarme" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            if (intent.resolveActivity(c.packageManager) == null) return "❌ Aucune application Horloge compatible n'a été trouvée pour créer cette alarme."
            c.startActivity(intent)
            "⏰ Demande envoyée à l'application Horloge native : %02d:%02d%s. Vérifie sa présence dans ses alarmes.".format(
                h, mn, if (libelle.isNotBlank()) " — $libelle" else ""
            )
        } catch (e: Exception) {
            "❌ Impossible de transmettre l'alarme à l'Horloge native : ${e.message ?: e.javaClass.simpleName}."
        }
    }

    /** Crée et démarre un compte à rebours dans l'application Horloge native. */
    fun creerMinuteur(c: Context, secondes: Int, libelle: String): String {
        if (secondes <= 0) return "Durée du minuteur invalide."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, secondes)
            putExtra(AlarmManager.EXTRA_MESSAGE, libelle.ifBlank { "Minuteur" })
            putExtra(AlarmManager.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            if (intent.resolveActivity(c.packageManager) == null) return "❌ Aucune application Horloge compatible n'a été trouvée pour créer ce minuteur."
            c.startActivity(intent)
            val h = secondes / 3600
            val mi = secondes % 3600 / 60
            val se = secondes % 60
            val txt = listOfNotNull(if (h > 0) "$h h" else null, if (mi > 0) "$mi min" else null, if (se > 0) "$se s" else null).joinToString(" ")
            "⏳ Minuteur de $txt transmis à l'application Horloge native. Vérifie qu'il apparaît et démarre dans ses minuteurs."
        } catch (e: Exception) {
            "❌ Impossible de transmettre le minuteur à l'Horloge native : ${e.message ?: e.javaClass.simpleName}."
        }
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

    fun diagnostic(c: Context): String {
        channels(c)
        val exact = exactAutorise(c)
        val notifications = notificationsAutorisees(c)
        val next = am(c).nextAlarmClock?.triggerTime?.let {
            java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm:ss"))
        } ?: "aucune alarme système"
        val chrono = prefs(c).getLong("chrono", 0L)
        val saved = load(c)
        return buildString {
            appendLine("Diagnostic Horloge JARVIS")
            appendLine("Android API : ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Alarmes exactes : ${if (exact) "AUTORISÉES" else "REFUSÉES"}")
            appendLine("Shizuku : ${if (!ShizukuShell.installed(c)) "non installé" else if (!ShizukuShell.running()) "installé, non démarré" else if (!ShizukuShell.granted()) "démarré, accès JARVIS refusé" else "prêt"}")
            appendLine("Notifications : ${if (notifications) "autorisées" else "bloquées"}")
            appendLine("Prochaine alarme système : $next")
            appendLine("Alarmes/minuteurs enregistrés : ${saved.size}")
            append("Chronomètre : ${if (chrono == 0L) "arrêté" else "actif depuis " + formater(System.currentTimeMillis() - chrono)}")
        }
    }

    fun liste(c: Context): String {
        val f = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.FRENCH)
        val l = load(c).sortedBy { it.optLong("at") }
        if (l.isEmpty()) return "Aucune alarme ni minuteur en cours."
        return l.joinToString("\n") { (if (it.optString("kind") == "alarm") "⏰ " else "⏳ ") + java.time.Instant.ofEpochMilli(it.optLong("at")).atZone(ZoneId.systemDefault()).format(f) + (it.optString("label").takeIf { s -> s.isNotBlank() }?.let { s -> " — $s" } ?: "") }
    }

    // ---- Chronomètre natif : Android ne définit pas d'action publique standard pour démarrer le chrono.
    // On ouvre l'application Horloge sur son écran de chronomètres ; aucun faux démarrage interne n'est annoncé.
    fun chronometre(c: Context, action: String): String {
        val a = action.trim().lowercase()
        val intent = Intent(AlarmClock.ACTION_SHOW_TIMERS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            if (intent.resolveActivity(c.packageManager) == null) {
                val launch = c.packageManager.getLaunchIntentForPackage("com.android.deskclock")
                    ?: c.packageManager.getLaunchIntentForPackage("com.google.android.deskclock")
                    ?: return "❌ Impossible de trouver l'application Horloge native."
                c.startActivity(launch)
            } else {
                c.startActivity(intent)
            }
            if (a.startsWith("arr") || a.startsWith("stop") || a.startsWith("fin")) {
                "Horloge native ouverte. Android ne fournit pas d'action publique universelle pour arrêter le chronomètre : termine-le dans l'application."
            } else if (a.startsWith("etat") || a.startsWith("état") || a.startsWith("temps") || a.startsWith("lap") || a.startsWith("tour")) {
                "Horloge native ouverte. Consulte le temps directement dans l'onglet Chronomètre."
            } else {
                "⏱️ Horloge native ouverte. Android ne fournit pas d'API publique universelle pour appuyer automatiquement sur Démarrer dans l'onglet Chronomètre ; appuie sur Démarrer dans l'application."
            }
        } catch (e: Exception) {
            "❌ Impossible d'ouvrir le chronomètre natif : ${e.message ?: e.javaClass.simpleName}."
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
            .setOngoing(true).setAutoCancel(false)
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
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> Horloge.restaurer(context)
        }
    }
}
