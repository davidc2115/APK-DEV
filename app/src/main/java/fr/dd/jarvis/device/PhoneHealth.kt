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
    data class Dashboard(
        val battery: Int?,
        val charging: Boolean,
        val batteryHealth: String,
        val temperatureC: Double?,
        val storageFreeGb: Double?,
        val storageTotalGb: Double?,
        val storageUsedPct: Int?,
        val ramFreeGb: Double,
        val ramTotalGb: Double,
        val network: String,
        val thermal: String,
        val shizukuReady: Boolean,
        val accessibility: Boolean,
        val notificationListener: Boolean,
        val notificationsAllowed: Boolean,
        val exactAlarmsAllowed: Boolean,
        val unrestrictedBattery: Boolean,
        val uptimeMinutes: Long
    )

    /** Mesures instantanées destinées au tableau de bord visuel, sans contenu personnel. */
    fun dashboard(app: JarvisApp): Dashboard {
        val ctx = app.applicationContext
        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val battery = runCatching {
            (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        }.getOrNull()
        val charging = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1) in setOf(
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL
        )
        val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it >= 0 }?.div(10.0)
        val health = when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Bonne"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Surchauffe"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Défectueuse"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Surtension"
            BatteryManager.BATTERY_HEALTH_COLD -> "Trop froide"
            else -> "Non communiquée"
        }
        val storage = runCatching { StatFs(Environment.getDataDirectory().absolutePath) }.getOrNull()
        val free = storage?.availableBytes?.div(1024.0 * 1024.0 * 1024.0)
        val total = storage?.totalBytes?.div(1024.0 * 1024.0 * 1024.0)
        val used = if (storage != null && storage.totalBytes > 0L)
            ((storage.totalBytes - storage.availableBytes) * 100 / storage.totalBytes).toInt() else null
        val memory = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val network = when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "Hors ligne"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Données mobiles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connectée"
        }
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching {
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Léger"
                PowerManager.THERMAL_STATUS_MODERATE -> "Modéré"
                PowerManager.THERMAL_STATUS_SEVERE -> "Élevé"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critique"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Urgence"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Arrêt imminent"
                else -> "Inconnu"
            }
        }.getOrDefault("Indisponible") else "Non exposé"
        val listener = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            .split(':').any { it.substringBefore('/').equals(ctx.packageName, true) }
        val notifications = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val exact = Build.VERSION.SDK_INT < 31 ||
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        return Dashboard(
            battery, charging, health, temperature, free, total, used,
            memory.availMem / (1024.0 * 1024.0 * 1024.0),
            memory.totalMem / (1024.0 * 1024.0 * 1024.0),
            network, thermal, ShizukuShell.ready(), app.phone.accessibilityEnabled(),
            listener, notifications, exact, power.isIgnoringBatteryOptimizations(ctx.packageName),
            android.os.SystemClock.elapsedRealtime() / 60_000L
        )
    }

    /** Enregistre un point technique toutes les 30 minutes, indépendamment de l'agent spécialisé. */
    fun recordSample(app: JarvisApp) {
        runCatching {
            val ctx = app.applicationContext
            val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val battery = runCatching {
                (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
            }.getOrNull()
            val free = StatFs(Environment.getDataDirectory().absolutePath).availableBytes
            val prefs = ctx.getSharedPreferences("phone_health_history", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val previousAt = prefs.getLong("sample_at", 0L)
            if (previousAt == 0L || now - previousAt >= 30L * 60L * 1000L) {
                val history = prefs.getString("samples", "").orEmpty().split(';')
                    .filter { it.count { ch -> ch == ',' } == 2 }.toMutableList()
                history += "${now},${battery ?: -1},${free}"
                prefs.edit().putLong("sample_at", now).putInt("battery", battery ?: -1)
                    .putLong("free_bytes", free).putString("samples", history.takeLast(48).joinToString(";")).apply()
            }
        }
    }
    fun report(app: JarvisApp): String {
        val ctx = app.applicationContext
        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val battery = runCatching {
            (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        }.getOrNull()
        val charging = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1) in setOf(
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

    /** Historique local des mesures réellement relevées (jusqu'à 48 points). */
    fun history(app: JarvisApp): List<Sample> {
        val raw = app.applicationContext.getSharedPreferences("phone_health_history", Context.MODE_PRIVATE)
            .getString("samples", "").orEmpty()
        return raw.split(';').mapNotNull { row ->
            val p = row.split(',')
            if (p.size != 3) null else runCatching {
                Sample(p[0].toLong(), p[1].toInt().takeIf { it in 0..100 }, p[2].toLong())
            }.getOrNull()
        }
    }

    /** Synthèse de tendance basée uniquement sur les échantillons réellement collectés localement. */
    fun historyReport(app: JarvisApp): String {
        val samples = history(app)
        if (samples.isEmpty()) return "## Historique du téléphone\nAucune mesure historique disponible pour le moment. Les relevés sont collectés automatiquement au maximum toutes les 30 minutes."
        val first = samples.first()
        val last = samples.last()
        val batteries = samples.mapNotNull { it.battery }
        val batteryRange = if (batteries.isEmpty()) "non disponible" else "${batteries.minOrNull()}–${batteries.maxOrNull()} %"
        val batteryChange = if (first.battery != null && last.battery != null) {
            val delta = last.battery - first.battery
            (if (delta > 0) "+" else "") + "$delta points"
        } else "non calculable"
        val storageDelta = (last.freeBytes - first.freeBytes) / (1024.0 * 1024.0 * 1024.0)
        val storageChange = String.format(Locale.FRANCE, "%+.2f Go", storageDelta)
        val date = java.text.SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
        return buildString {
            append("## Historique du téléphone\n")
            append("- Mesures conservées : ${samples.size}/48 (maximum 24 h environ)\n")
            append("- Période observée : ${date.format(java.util.Date(first.at))} → ${date.format(java.util.Date(last.at))}\n")
            append("- Batterie : ${last.battery?.let { "$it %" } ?: "non relevée"} ; plage observée $batteryRange ; évolution $batteryChange\n")
            append("- Stockage libre : ${String.format(Locale.FRANCE, "%.2f Go", last.freeBytes / (1024.0 * 1024.0 * 1024.0))} ; évolution $storageChange\n")
            append("\nDerniers relevés :\n")
            samples.takeLast(8).asReversed().forEach { sample ->
                append("- ${date.format(java.util.Date(sample.at))} · batterie ${sample.battery?.let { "$it %" } ?: "n/c"} · libre ${String.format(Locale.FRANCE, "%.2f Go", sample.freeBytes / (1024.0 * 1024.0 * 1024.0))}\n")
            }
            append("\nCes valeurs sont des instantanés espacés d'au moins 30 minutes, pas une surveillance continue.")
        }
    }

    data class Sample(val at: Long, val battery: Int?, val freeBytes: Long)

}
