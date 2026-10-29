package com.jarvis.ai.core.agents

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import com.jarvis.ai.core.phonecontrol.PhoneControlManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Agent « téléphone » : lampe torche, Wi-Fi, Bluetooth, minuteur, réveil, chronomètre. */
@Singleton
class PhoneAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val phone: PhoneControlManager
) : Agent {
    override val name = "phone"
    override val description =
        "Contrôle du téléphone : lampe torche, Wi-Fi, Bluetooth, minuteur, réveil, chronomètre."
    override val actions = listOf(
        AgentAction("flashlight_on", "{}"),
        AgentAction("flashlight_off", "{}"),
        AgentAction("wifi", "{} (ouvre le panneau Wi-Fi : Android interdit l'activation directe aux apps)"),
        AgentAction("bluetooth", "{\"on\":true}"),
        AgentAction("timer", "{\"seconds\":300,\"label\":\"...\"}"),
        AgentAction("alarm", "{\"hour\":7,\"minute\":30,\"label\":\"...\"}"),
        AgentAction("show_alarms", "{}"),
        AgentAction("stopwatch_start", "{}"),
        AgentAction("stopwatch_stop", "{}")
    )

    private val prefs = context.getSharedPreferences("elite_phone", Context.MODE_PRIVATE)

    override suspend fun execute(action: String, args: JSONObject): String = when (action) {
        "flashlight_on" -> {
            phone.setFlashlight(true)
            "🔦 Lampe torche allumée"
        }
        "flashlight_off" -> {
            phone.setFlashlight(false)
            "🌑 Lampe torche éteinte"
        }
        "wifi" -> {
            if (Build.VERSION.SDK_INT >= 29) launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            else phone.openWifiSettings()
            "📶 Panneau Wi-Fi ouvert : touchez l'interrupteur pour l'activer ou le couper."
        }
        "bluetooth" -> {
            val on = args.optBoolean("on", true)
            if (Build.VERSION.SDK_INT >= 33) {
                // Depuis Android 13, enable()/disable() sont inopérants pour une app tierce.
                launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                "🔵 Réglages Bluetooth ouverts : ${if (on) "activez" else "désactivez"} l'interrupteur."
            } else {
                phone.setBluetoothEnabled(on)
                "🔵 Bluetooth ${if (on) "activé" else "désactivé"} (demande envoyée)."
            }
        }
        "timer" -> {
            val seconds = args.getInt("seconds").coerceIn(1, 86_400)
            val label = args.optString("label", "Minuteur")
            launch(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            })
            "⏱️ Minuteur lancé : ${formatDuration(seconds.toLong())}" + if (label.isNotBlank()) " ($label)" else ""
        }
        "alarm" -> {
            val hour = args.getInt("hour").coerceIn(0, 23)
            val minute = args.optInt("minute", 0).coerceIn(0, 59)
            val label = args.optString("label", "Réveil")
            launch(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            })
            "⏰ Réveil programmé à %02d:%02d".format(hour, minute)
        }
        "show_alarms" -> {
            launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            "⏰ Liste des réveils ouverte"
        }
        "stopwatch_start" -> {
            prefs.edit().putLong(KEY_STOPWATCH, System.currentTimeMillis()).apply()
            "⏱️ Chronomètre démarré"
        }
        "stopwatch_stop" -> {
            val start = prefs.getLong(KEY_STOPWATCH, 0L)
            if (start == 0L) {
                "Aucun chronomètre en cours."
            } else {
                prefs.edit().remove(KEY_STOPWATCH).apply()
                "⏱️ Chronomètre arrêté : ${formatDuration((System.currentTimeMillis() - start) / 1000)}"
            }
        }
        else -> "Action inconnue : $action"
    }

    private fun launch(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%dh%02dmin%02ds".format(h, m, s) else if (m > 0) "%dmin%02ds".format(m, s) else "${s}s"
    }

    private companion object {
        const val KEY_STOPWATCH = "stopwatch_start"
    }
}
