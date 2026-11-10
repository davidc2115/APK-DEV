package fr.dd.jarvis.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.dd.jarvis.MainActivity

/** Confirmation système : bannière prioritaire avec actions, utilisable même si une autre application est au premier plan. */
object ConfirmationNotification {
    const val ACTION = "fr.dd.jarvis.CONFIRMATION"
    const val EXTRA_OK = "jarvis_confirmation_ok"
    private const val CHANNEL = "jarvis_confirmations_v1"
    private const val ID = 48120

    private fun intent(context: Context, accepte: Boolean): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_OK, accepte)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, if (accepte) 48121 else 48122, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun afficher(context: Context, question: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Confirmations JARVIS", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Demandes d'acceptation et de confirmation des actions de JARVIS"
                enableVibration(true)
            })
        }
        val ouvrir = PendingIntent.getActivity(context, 48123,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("JARVIS — confirmation requise")
            .setContentText(question.take(160))
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(ouvrir)
            .addAction(0, "Refuser", intent(context, false))
            .addAction(0, "Accepter", intent(context, true))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }

    fun annuler(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }
}
