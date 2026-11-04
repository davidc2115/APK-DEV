package fr.dd.jarvis.device

import android.Manifest
import android.app.AlarmManager
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import fr.dd.jarvis.JarvisApp
import java.util.Locale

/** Rapport local, en lecture seule, des ressources et accès Android réellement observables. */
object PhoneHealth {
    fun report(app: JarvisApp): String {
        val ctx = app.applicationContext
        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val battery = runCatching {
            (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        }.getOrNull()
        val charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in setOf(
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL
        )
        val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }?.div(10.0)
        val batteryHealth = when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "bonne"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "surchauffe"
            BatteryManager.BATTERY_HEALTH_DEAD -> "défectueuse"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "surtension"
            BatteryManager.BATTERY_HEALTH_COLD -> "trop froide"
            else -> "non communiquée"
        }

        val storage = runCatching { StatFs(Environment.getDataDirectory().absolutePath) }.getOrNull()
        val freeGb = storage?.availableBytes?.div(1024.0 * 1024.0 * 1024.0)
        val totalGb = storage?.totalBytes?.div(1024.0 * 1024.0 * 1024.0)
        val usedPct = if (storage != null && storage.totalBytes > 0L)
            ((storage.totalBytes - storage.availableBytes) * 100 / storage.totalBytes).toInt() else null

        val memory = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val ramTotal = memory.totalMem / (1024.0 * 1024.0 * 1024.0)
        val ramFree = memory.availMem / (1024.0 * 1024.0 * 1024.0)

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val network = when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "hors ligne"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "données mobiles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "connectée (transport inconnu)"
        }
        val metered = cm.isActiveNetworkMetered
        val listener = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            .contains(ctx.packageName)
        val notifications = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val exactAlarm = Build.VERSION.SDK_INT < 31 ||
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        val unrestrictedBattery = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
        val crash = java.io.File(ctx.filesDir, JarvisApp.CRASH_FILE).exists()
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching {
            when ((ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "léger"
                PowerManager.THERMAL_STATUS_MODERATE -> "modéré"
                PowerManager.THERMAL_STATUS_SEVERE -> "élevé"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critique"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "urgence"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "arrêt imminent"
                else -> "inconnu"
            }
        }.getOrDefault("indisponible") else "non exposé par Android"
        val uptimeMin = android.os.SystemClock.elapsedRealtime() / 60_000L

        return buildString {
            append("# Diagnostic local du téléphone\n\n")
            append("- Appareil : ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("- Batterie : ${battery?.let { "$it %" } ?: "indisponible"}${if (charging) " · en charge" else ""} · santé : $batteryHealth")
            if (temperature != null) append(" · température batterie : ${String.format(Locale.FRANCE, "%.1f", temperature)} °C")
            append("\n- État thermique Android : $thermal\n")
            append("- Stockage interne : ${freeGb?.let { String.format(Locale.FRANCE, "%.2f", it) } ?: "?"} Go libres / ${totalGb?.let { String.format(Locale.FRANCE, "%.1f", it) } ?: "?"} Go")
            if (usedPct != null) append(" · $usedPct % utilisés")
            append("\n- RAM : ${String.format(Locale.FRANCE, "%.2f", ramFree)} Go libres / ${String.format(Locale.FRANCE, "%.2f", ramTotal)} Go · ${if (memory.lowMemory) "pression mémoire signalée" else "pas de pression mémoire signalée"}\n")
            append("- Réseau : $network · ${if (metered) "facturé/limité selon forfait" else "non mesuré comme réseau mobile"}\n")
            append("- Shizuku : ${if (ShizukuShell.ready()) "prêt et autorisé" else if (ShizukuShell.running()) "démarré, autorisation JARVIS manquante" else "arrêté"}\n")
            append("- Accessibilité : ${if (app.phone.accessibilityEnabled()) "connectée" else if (app.phone.accessibilityInSettings()) "activée dans Android mais non connectée" else "désactivée"}\n")
            append("- Accès aux notifications : ${if (listener) "autorisé" else "non autorisé"} · notifications Android : ${if (notifications) "autorisées" else "bloquées"}\n")
            append("- Alarmes exactes : ${if (exactAlarm) "autorisées" else "non autorisées"} · batterie sans restriction : ${if (unrestrictedBattery) "oui" else "non"}\n")
            append("- Service vocal JARVIS : ${if (fr.dd.jarvis.service.JarvisService.running) "actif" else "inactif"} · durée depuis démarrage : ${uptimeMin / 60} h ${uptimeMin % 60} min\n")
            append("- Rapport de plantage local : ${if (crash) "présent, à consulter" else "aucun fichier détecté"}\n\n")
            val alerts = mutableListOf<String>()
            if (battery != null && battery <= 20 && !charging) alerts += "Batterie faible : branche le téléphone si nécessaire."
            if (freeGb != null && freeGb < 1.5) alerts += "Stockage presque saturé : moins de 1,5 Go libres."
            if (memory.lowMemory) alerts += "Android signale une pression sur la mémoire vive."
            if (thermal in setOf("élevé", "critique", "urgence", "arrêt imminent")) alerts += "Température système élevée : laisse refroidir le téléphone et évite les tâches lourdes."
            if (!unrestrictedBattery) alerts += "L'optimisation batterie peut interrompre les tâches en arrière-plan."
            if (crash) alerts += "Un rapport de plantage existe ; il faut le consulter avant de conclure."
            if (alerts.isEmpty()) append("Aucune alerte locale détectée avec les informations accessibles.")
            else append("## Points à surveiller\n" + alerts.joinToString("\n") { "- $it" })
            append("\n\nRapport en lecture seule : Android ne permet pas à JARVIS de mesurer toutes les applications, ni de garantir qu'un processus tiers restera actif.")
        }
    }
}
