package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhoneController {

    fun makeCall(context: Context, contactNameOrNumber: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission d'appel non accordée. Veuillez accorder la permission dans les paramètres."
        }

        var number = contactNameOrNumber.replace(" ", "").replace("-", "")
        if (!number.all { it.isDigit() || it == '+' }) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return "❌ Permission d'accès aux contacts (READ_CONTACTS) non accordée. Elle est nécessaire pour trouver le numéro de « $contactNameOrNumber »."
            }
            val resolved = ContactsController.findPhoneNumber(context, contactNameOrNumber)
            if (resolved != null) {
                number = resolved
            } else {
                return "❌ Impossible de trouver le numéro de téléphone pour « $contactNameOrNumber » dans vos contacts."
            }
        }

        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "📞 Appel en cours vers $contactNameOrNumber ($number)..."
        } catch (e: Exception) {
            "❌ Échec du lancement de l'appel : ${e.message}"
        }
    }

    fun endCall(context: Context): String {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null && ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                telecomManager.endCall()
                "📞 Appel terminé."
            } else {
                "❌ Permission ou service de téléphonie non disponible pour couper l'appel."
            }
        } catch (e: Exception) {
            "❌ Échec de la fin d'appel : ${e.message}"
        }
    }

    fun getRecentCalls(context: Context, count: Int = 10): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission d'accès au journal d'appels non accordée."
        }

        val projection = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        return try {
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "📞 Aucun appel récent trouvé."

                val sb = StringBuilder("📞 **Journal des ${minOf(count, c.count)} derniers appels** :\n\n")
                val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH)
                var index = 0

                while (c.moveToNext() && index < count) {
                    val name = c.getString(0) ?: "Inconnu"
                    val number = c.getString(1) ?: ""
                    val type = c.getInt(2)
                    val date = c.getLong(3)
                    val duration = c.getLong(4)

                    val typeStr = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "📥 Entrant"
                        CallLog.Calls.OUTGOING_TYPE -> "📤 Sortant"
                        CallLog.Calls.MISSED_TYPE -> "🔴 Manqué"
                        else -> "📞 Autre"
                    }

                    val displayName = if (name != "Inconnu") "$name ($number)" else number
                    val dateStr = sdf.format(Date(date))
                    val durationStr = if (duration > 0) "${duration}s" else ""

                    sb.append("${index + 1}. **$displayName** — $typeStr\n")
                    sb.append("   Date: $dateStr ${if (durationStr.isNotEmpty()) "| Durée: $durationStr" else ""}\n\n")
                    index++
                }
                sb.toString().trimEnd()
            } ?: "❌ Impossible d'accéder au journal d'appels."
        } catch (e: Exception) {
            "❌ Erreur lecture journal d'appels : ${e.message}"
        }
    }

    fun getCallStats(context: Context): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission d'accès au journal d'appels non accordée."
        }

        return try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.TYPE),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                null
            )
            val missedCount = cursor?.use { it.count } ?: 0
            "🔴 Vous avez **$missedCount appel(s) manqué(s)** au total."
        } catch (e: Exception) {
            "❌ Erreur de calcul des statistiques : ${e.message}"
        }
    }
}
