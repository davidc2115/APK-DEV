package com.jarvis.ai.core.phonecontrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralise l'état des permissions runtime pour chaque capacité de contrôle du téléphone.
 * Chaque capacité est listée séparément dans l'écran Réglages, avec un interrupteur visible :
 * rien n'est activé sans que l'utilisateur ait explicitement accordé la permission Android.
 */
@Singleton
class PermissionsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun status(): Map<String, Boolean> = mapOf(
        "Agenda" to isGranted(Manifest.permission.READ_CALENDAR),
        "SMS" to isGranted(Manifest.permission.SEND_SMS),
        "Bluetooth" to isGranted(Manifest.permission.BLUETOOTH_CONNECT),
        "Caméra (lampe torche)" to isGranted(Manifest.permission.CAMERA),
    )
}
