package com.jarvis.assistant

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmsController {

    fun sendSms(context: Context, contactNameOrNumber: String, body: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission d'envoi de SMS non accordée. Utilisez le bouton '💬 Demander SMS' ou '⚙️ AUTORISATIONS MANUELLES'."
        }

        var number = contactNameOrNumber.replace(" ", "").replace("-", "")
        if (!number.all { it.isDigit() || it == '+' }) {
            val resolved = ContactsController.findPhoneNumber(context, contactNameOrNumber)
            if (resolved != null) {
                number = resolved
            } else {
                return "❌ Numéro introuvable pour le contact « $contactNameOrNumber »."
            }
        }

        return try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(number, null, body, null, null)
            }
            "💬 SMS envoyé avec succès à **$contactNameOrNumber** ($number) !"
        } catch (e: Exception) {
            "❌ Échec de l'envoi du SMS : ${e.message}"
        }
    }

    fun readInboxSms(context: Context, count: Int = 10): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture des SMS non accordée. Cliquez sur '💬 Demander SMS' ou '⚙️ AUTORISATIONS MANUELLES'."
        }

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ
        )

        // 1. Essai sur Telephony.Sms.Inbox
        try {
            val cursor: Cursor? = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            if (cursor != null && cursor.count > 0) {
                return formatSmsCursor(cursor, count)
            }
        } catch (_: Exception) {}

        // 2. Fallback universel sur Telephony.Sms.CONTENT_URI (Compatible MIUI, OneUI, ColorOS, EMUI)
        return try {
            val cursor: Cursor? = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.TYPE} = 1",
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "💬 Aucun SMS reçu dans le téléphone."
                formatSmsCursor(c, count)
            } ?: "❌ Impossible d'accéder aux SMS."
        } catch (e: Exception) {
            "❌ Erreur lors de la lecture des SMS : ${e.message}"
        }
    }

    fun readUnreadSms(context: Context): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture des SMS non accordée."
        }

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        return try {
            val cursor: Cursor? = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.TYPE} = 1",
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "✅ Aucun SMS non lu."

                val sb = StringBuilder("🔴 **Vous avez ${c.count} SMS non lu(s)** :\n\n")
                val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH)
                var idx = 0

                while (c.moveToNext() && idx < 10) {
                    val address = c.getString(0) ?: "Inconnu"
                    val body = c.getString(1) ?: ""
                    val date = c.getLong(2)
                    val dateStr = sdf.format(Date(date))

                    sb.append("${idx + 1}. **$address** — $dateStr\n")
                    sb.append("   « $body »\n\n")
                    idx++
                }
                sb.toString().trimEnd()
            } ?: "❌ Impossible d'accéder aux SMS."
        } catch (e: Exception) {
            "❌ Erreur lors de la lecture des SMS non lus : ${e.message}"
        }
    }

    private fun formatSmsCursor(cursor: Cursor, count: Int): String {
        val sb = StringBuilder("💬 **Boîte de réception SMS (${minOf(count, cursor.count)})** :\n\n")
        val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH)
        var idx = 0

        while (cursor.moveToNext() && idx < count) {
            val address = cursor.getString(0) ?: "Inconnu"
            val body = cursor.getString(1) ?: ""
            val date = cursor.getLong(2)
            val isRead = cursor.getInt(3) == 1

            val readStatus = if (isRead) "" else " 🔴 (Non lu)"
            val dateStr = sdf.format(Date(date))

            sb.append("${idx + 1}. **$address**$readStatus — $dateStr\n")
            sb.append("   « $body »\n\n")
            idx++
        }
        return sb.toString().trimEnd()
    }

    fun markAllRead(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
            }
            val updated = context.contentResolver.update(
                Telephony.Sms.Inbox.CONTENT_URI,
                values,
                "${Telephony.Sms.READ} = 0",
                null
            )
            updated > 0
        } catch (e: Exception) {
            false
        }
    }
}
