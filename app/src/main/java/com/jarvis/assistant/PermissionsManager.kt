package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Gestionnaire centralisé des permissions Android runtime pour JARVIS.
 *
 * Regroupe toutes les permissions en catégories logiques.
 * La demande se fait depuis MainActivity en une seule session au premier lancement.
 */
object PermissionsManager {

    // ─────────────────────────────────────────────────────────────────────────
    // Groupes de permissions par fonctionnalité
    // ─────────────────────────────────────────────────────────────────────────

    val PHONE_PERMISSIONS = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS
    )

    val SMS_PERMISSIONS = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS
    )

    val CONTACTS_PERMISSIONS = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.GET_ACCOUNTS
    )

    val CALENDAR_PERMISSIONS = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    val CAMERA_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA
    )

    val BLUETOOTH_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        @Suppress("DEPRECATION")
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    val MEDIA_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES
        )
    } else {
        @Suppress("DEPRECATION")
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val NOTIFICATION_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    /** Toutes les permissions demandées au premier lancement. */
    val ALL_RUNTIME_PERMISSIONS: Array<String> by lazy {
        (PHONE_PERMISSIONS + SMS_PERMISSIONS + CONTACTS_PERMISSIONS +
            CALENDAR_PERMISSIONS + LOCATION_PERMISSIONS + CAMERA_PERMISSIONS +
            BLUETOOTH_PERMISSIONS + MEDIA_PERMISSIONS + NOTIFICATION_PERMISSION)
            .distinct().toTypedArray()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vérification
    // ─────────────────────────────────────────────────────────────────────────

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean =
        permissions.all { hasPermission(context, it) }

    fun hasCameraPermission(context: Context) = hasPermission(context, Manifest.permission.CAMERA)
    fun hasCallPermission(context: Context)   = hasPermission(context, Manifest.permission.CALL_PHONE)
    fun hasSmsPermission(context: Context)    = hasPermission(context, Manifest.permission.READ_SMS) || hasPermission(context, Manifest.permission.SEND_SMS)
    fun hasContactsPermission(context: Context) = hasPermission(context, Manifest.permission.READ_CONTACTS)
    fun hasCalendarPermission(context: Context) = hasPermission(context, Manifest.permission.READ_CALENDAR)
    fun hasLocationPermission(context: Context) = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasStoragePermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        hasPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        @Suppress("DEPRECATION")
        hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun hasBluetoothPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        @Suppress("DEPRECATION")
        hasPermission(context, Manifest.permission.BLUETOOTH)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Demande de permission
    // ─────────────────────────────────────────────────────────────────────────

    /** Filtre les permissions non encore accordées et les demande. */
    fun requestMissingPermissions(activity: Activity, requestCode: Int) {
        val missing = ALL_RUNTIME_PERMISSIONS.filter { !hasPermission(activity, it) }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), requestCode)
        }
    }

    /** Demande un groupe spécifique de permissions. */
    fun requestPermissions(activity: Activity, permissions: Array<String>, requestCode: Int) {
        val missing = permissions.filter { !hasPermission(activity, it) }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), requestCode)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions spéciales (hors runtime standard)
    // ─────────────────────────────────────────────────────────────────────────

    /** Vérifie si l'app a accès à TOUS les fichiers (MANAGE_EXTERNAL_STORAGE — Android 11+). */
    fun hasManageStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else { true }

    /** Ouvre le paramètre pour accorder MANAGE_EXTERNAL_STORAGE. */
    fun requestManageStoragePermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }

    /** Vérifie si le service de notification est activé. */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(context.packageName)
    }

    /** Ouvre les paramètres d'accès aux notifications. */
    fun openNotificationListenerSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rapport de statut (pour l'UI)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retourne un rapport textuel du statut des permissions pour l'affichage dans l'app.
     */
    fun getPermissionsReport(context: Context): String = buildString {
        append("📋 **Statut des permissions JARVIS** :\n\n")

        fun status(granted: Boolean) = if (granted) "✅" else "❌"

        append("📞 Téléphone / Appels : ${status(hasCallPermission(context))}\n")
        append("💬 SMS : ${status(hasSmsPermission(context))}\n")
        append("👤 Contacts : ${status(hasContactsPermission(context))}\n")
        append("📅 Agenda : ${status(hasCalendarPermission(context))}\n")
        append("📍 Localisation : ${status(hasLocationPermission(context))}\n")
        append("📷 Caméra : ${status(hasCameraPermission(context))}\n")
        append("🎵 Médias : ${status(hasStoragePermission(context))}\n")
        append("🔵 Bluetooth : ${status(hasBluetoothPermission(context))}\n")
        append("🔔 Notifications : ${status(isNotificationListenerEnabled(context))} (service)\n")
        append("📁 Stockage total : ${status(hasManageStoragePermission())}\n")

        if (!isNotificationListenerEnabled(context)) {
            append("\n💡 **Si l'accès aux notifications est grisé (Android 13+)** :\n")
            append("1. Cliquez sur '⚙ INFOS APPLICATION' ci-dessous.\n")
            append("2. En haut à droite, touchez les 3 points (⋮).\n")
            append("3. Choisissez 'Autoriser les paramètres restreints'.\n")
            append("4. Revenez et activez 'Accès aux notifications'.\n\n")
        }
        if (!hasManageStoragePermission()) {
            append("⚠️ Pour l'accès complet aux fichiers, touchez '📁 ACCÈS COMPLET AU STOCKAGE'.\n\n")
        }

        append(AccountDiscoveryManager.getSummaryReport(context))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constantes request codes
    // ─────────────────────────────────────────────────────────────────────────

    const val REQUEST_ALL       = 100
    const val REQUEST_PHONE     = 101
    const val REQUEST_SMS       = 102
    const val REQUEST_CONTACTS  = 103
    const val REQUEST_CALENDAR  = 104
    const val REQUEST_LOCATION  = 105
    const val REQUEST_CAMERA    = 106
    const val REQUEST_BLUETOOTH = 107
    const val REQUEST_MEDIA     = 108
    const val REQUEST_NOTIF     = 109
}
