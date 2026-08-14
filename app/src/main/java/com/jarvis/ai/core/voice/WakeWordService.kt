package com.jarvis.ai.core.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.ai.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Service premier plan qui écoute en continu le micro pour détecter le mot-clé "Jarvis",
 * via WakeWordDetectionManager (openWakeWord en principal, Vosk en secours). Aucune clé,
 * aucun compte : tout le traitement est local à l'appareil.
 *
 * Notification persistante requise par Android 13+ pour un service micro en premier plan.
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject lateinit var detectionManager: WakeWordDetectionManager

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification())
        detectionManager.start(onWakeWordDetected = ::onWakeWordDetected)
    }

    override fun onDestroy() {
        detectionManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onWakeWordDetected() {
        // TODO: notifier ChatViewModel / OrbState -> passer en LISTENING et lancer WhisperTranscriber.
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis écoute")
            .setContentText("Dites « Jarvis » pour démarrer une conversation")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Écoute Jarvis", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_wake_word"
        private const val NOTIFICATION_ID = 42
    }
}
