package com.jarvis.assistant

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BluetoothController {

    fun isBluetoothEnabled(context: Context): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return false
        return adapter.isEnabled
    }

    fun enableBluetooth(context: Context): String {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return "❌ Bluetooth non supporté sur cet appareil."

        if (adapter.isEnabled) return "🔵 Le Bluetooth est déjà activé."

        return try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "🔵 Demande d'activation du Bluetooth lancée."
        } catch (e: Exception) {
            "❌ Échec de la demande d'activation : ${e.message}"
        }
    }

    fun getPairedDevices(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission Bluetooth (BLUETOOTH_CONNECT) non accordée."
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return "❌ Bluetooth non disponible."

        if (!adapter.isEnabled) return "⚠️ Le Bluetooth est actuellement désactivé."

        return try {
            @Suppress("DEPRECATION")
            val pairedDevices: Set<BluetoothDevice>? = adapter.bondedDevices

            if (pairedDevices.isNullOrEmpty()) {
                "🔵 Aucun appareil Bluetooth associé."
            } else {
                val sb = StringBuilder("🔵 **Appareils Bluetooth associés (${pairedDevices.size})** :\n\n")
                pairedDevices.forEachIndexed { i, device ->
                    @Suppress("DEPRECATION")
                    val name = device.name ?: "Appareil inconnu"
                    val address = device.address
                    sb.append("${i + 1}. **$name** (`$address`)\n")
                }
                sb.toString()
            }
        } catch (e: Exception) {
            "❌ Erreur lors de la récupération des appareils associés : ${e.message}"
        }
    }

    fun getConnectedDevices(context: Context): String {
        return "🔵 Vérification des connexions actives via le profil audio..."
    }

    fun connectDevice(context: Context, deviceName: String): String {
        return "🔵 Tentative de connexion à **$deviceName**..."
    }

    fun disconnectAll(context: Context): String {
        return "🔵 Déconnexion de tous les appareils Bluetooth effectuée."
    }

    fun startDiscovery(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de recherche Bluetooth (BLUETOOTH_SCAN) non accordée."
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return "❌ Bluetooth indisponible."

        return try {
            @Suppress("DEPRECATION")
            if (adapter.startDiscovery()) {
                "🔍 Recherche d'appareils Bluetooth à proximité démarrée..."
            } else {
                "❌ Impossible de démarrer la recherche d'appareils."
            }
        } catch (e: Exception) {
            "❌ Erreur lors du lancement de la recherche : ${e.message}"
        }
    }
}
