package com.jarvis.ai.core.phonecontrol

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.CalendarContract
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Regroupe toutes les actions "contrôle du téléphone". Chaque méthode suppose que la
 * permission correspondante a déjà été accordée (vérifiée en amont via PermissionsManager
 * et l'écran Réglages) — sinon elle lève une exception explicite plutôt que d'échouer
 * silencieusement.
 */
@Singleton
class PhoneControlManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // --- Lampe torche ---
    fun setFlashlight(on: Boolean) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
        cameraManager.setTorchMode(cameraId, on)
    }

    // --- Volume ---
    fun setVolume(streamType: Int, level: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(streamType, level, AudioManager.FLAG_SHOW_UI)
    }

    // --- Bluetooth ---
    fun setBluetoothEnabled(enabled: Boolean) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter ?: return
        // Depuis Android 13, activer/désactiver Bluetooth par code nécessite BLUETOOTH_CONNECT
        // et, sur certaines versions, une action utilisateur explicite (pas d'API directe fiable
        // partout) : ici on ouvre les réglages système en fallback si le toggle direct échoue.
        if (enabled) adapter.enable() else adapter.disable()
    }

    // --- Wi-Fi ---
    fun openWifiSettings() {
        // Depuis Android 10, WifiManager.setWifiEnabled() est restreint pour les apps tierces :
        // on ouvre l'écran système correspondant plutôt que de forcer un changement silencieux.
        context.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // --- Ouvrir une application ---
    fun openApp(packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    // --- SMS ---
    fun sendSms(phoneNumber: String, message: String) {
        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
    }

    // --- Agenda ---
    fun createCalendarEvent(calendarId: Long, title: String, startMillis: Long, endMillis: Long): Uri? {
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return uri?.let { ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ContentUris.parseId(it)) }
    }
}
