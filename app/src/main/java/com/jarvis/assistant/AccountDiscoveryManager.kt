package com.jarvis.assistant

import android.accounts.AccountManager
import android.content.Context
import android.provider.CalendarContract
import android.util.Log

/**
 * Détecte automatiquement les comptes configurés sur le téléphone Android
 * (Comptes Google, Outlook, Yahoo, Exchange, etc.) pour les emails, le calendrier et les contacts.
 */
object AccountDiscoveryManager {

    data class DiscoveredAccount(
        val email: String,
        val type: String,
        val providerPreset: String
    )

    /**
     * Lit les comptes système enregistrés sur le smartphone (requiert la permission GET_ACCOUNTS).
     */
    fun getDeviceAccounts(context: Context): List<DiscoveredAccount> {
        val discovered = mutableListOf<DiscoveredAccount>()

        // 1. Détection via AccountManager Android
        try {
            val am = AccountManager.get(context)
            val accounts = am.accounts
            for (acc in accounts) {
                val name = acc.name
                if (name.contains("@")) {
                    val preset = when {
                        acc.type.contains("google", ignoreCase = true) || name.endsWith("@gmail.com", ignoreCase = true) -> "Gmail"
                        acc.type.contains("microsoft", ignoreCase = true) || acc.type.contains("exchange", ignoreCase = true) || name.endsWith("@outlook.com", ignoreCase = true) || name.endsWith("@hotmail.com", ignoreCase = true) -> "Outlook"
                        acc.type.contains("yahoo", ignoreCase = true) || name.endsWith("@yahoo.com", ignoreCase = true) -> "Yahoo"
                        acc.type.contains("apple", ignoreCase = true) || name.endsWith("@icloud.com", ignoreCase = true) -> "iCloud"
                        else -> "Personnalisé"
                    }
                    if (discovered.none { it.email.equals(name, ignoreCase = true) }) {
                        discovered.add(DiscoveredAccount(name, acc.type, preset))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AccountDiscovery", "Erreur lecture AccountManager", e)
        }

        // 2. Détection via CalendarContract (recherche des comptes synchronisant l'agenda)
        try {
            val projection = arrayOf(
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE
            )
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val accName = cursor.getString(0) ?: ""
                    val accType = cursor.getString(1) ?: ""
                    if (accName.contains("@") && discovered.none { it.email.equals(accName, ignoreCase = true) }) {
                        val preset = if (accName.endsWith("@gmail.com", ignoreCase = true)) "Gmail" else "Outlook"
                        discovered.add(DiscoveredAccount(accName, accType, preset))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AccountDiscovery", "Erreur lecture Calendars", e)
        }

        return discovered
    }

    /**
     * Rapport textuel synthétique pour l'utilisateur des comptes détectés sur le téléphone.
     */
    fun getSummaryReport(context: Context): String {
        val accounts = getDeviceAccounts(context)
        if (accounts.isEmpty()) {
            return "📱 Aucun compte email/synchro détecté automatiquement. Vérifiez les permissions de l'application."
        }
        return buildString {
            append("📱 **${accounts.size} compte(s) détecté(s) sur cet appareil** :\n\n")
            accounts.forEachIndexed { i, acc ->
                append("${i + 1}. **${acc.email}** (${acc.providerPreset})\n")
            }
        }
    }
}
