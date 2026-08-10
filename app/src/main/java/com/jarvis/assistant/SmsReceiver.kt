package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Récepteur Broadcast SMS pour déclarer JARVIS comme application recevant les SMS.
 * Nécessaire sur Android 10+ pour valider les permissions SMS auprès du système.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                for (msg in messages) {
                    val sender = msg.displayOriginatingAddress ?: "Inconnu"
                    val body = msg.displayMessageBody ?: msg.messageBody ?: ""
                    Log.d("SmsReceiver", "💬 Nouveau SMS de $sender : $body")
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Erreur réception SMS", e)
            }
        }
    }
}
