package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

/**
 * Contrôleur Wi-Fi. Depuis Android 10, Google interdit aux applications
 * d'activer/désactiver le Wi-Fi directement en code (restriction de sécurité,
 * WifiManager.setWifiEnabled() ne fait plus rien pour les apps ciblant l'API 29+).
 * On ouvre donc le panneau rapide système : un seul tap suffit côté utilisateur.
 */
object WifiController {

    fun isWifiEnabled(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wm?.isWifiEnabled ?: false
    }

    fun enableWifi(context: Context): String {
        if (isWifiEnabled(context)) return "📶 Le Wi-Fi est déjà activé."
        return try {
            openWifiPanel(context)
            "📶 Panneau Wi-Fi ouvert — active-le d'un tap."
        } catch (e: Exception) {
            "❌ Échec de l'ouverture du panneau Wi-Fi : ${e.message}"
        }
    }

    fun disableWifi(context: Context): String {
        if (!isWifiEnabled(context)) return "📶 Le Wi-Fi est déjà désactivé."
        return try {
            openWifiPanel(context)
            "📶 Panneau Wi-Fi ouvert — désactive-le d'un tap."
        } catch (e: Exception) {
            "❌ Échec de l'ouverture du panneau Wi-Fi : ${e.message}"
        }
    }

    fun getWifiInfo(context: Context): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return "❌ Wi-Fi non disponible sur cet appareil."

        if (!wm.isWifiEnabled) return "📶 Le Wi-Fi est actuellement désactivé."

        return try {
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            @Suppress("DEPRECATION")
            val ssid = info?.ssid?.trim('"')
            if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") {
                "📶 Wi-Fi activé, mais non connecté à un réseau (ou nom masqué par le système)."
            } else {
                "📶 Wi-Fi activé, connecté à **$ssid**."
            }
        } catch (e: Exception) {
            "📶 Le Wi-Fi est activé (détails indisponibles : ${e.message})."
        }
    }

    private fun openWifiPanel(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}
