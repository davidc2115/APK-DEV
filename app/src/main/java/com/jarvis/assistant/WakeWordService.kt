package com.jarvis.assistant

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Service qui écoute en permanence en arrière-plan et déclenche le mode
 * vocal dès que le mot-clé configuré est prononcé.
 *
 * Deux moteurs possibles, choisis automatiquement :
 *
 * 1. Porcupine (ai.picovoice) — moteur DÉDIÉ à la détection de mot-clé,
 *    utilisé si une clé d'accès Picovoice est configurée ET que le mot-clé
 *    choisi fait partie des mots-clés intégrés (dont "Jarvis", par défaut).
 *    Fonctionne 100% hors-ligne, consommation très faible — c'est le moteur
 *    normalement utilisé.
 *
 * 2. Reconnaissance vocale standard Android en boucle — repli automatique
 *    si aucune clé Picovoice n'est configurée, ou si le mot-clé choisi n'est
 *    pas un mot-clé intégré Porcupine. Fonctionne avec n'importe quel mot,
 *    mais consomme plus de batterie et nécessite internet.
 *
 * ⚠️ Sur certains téléphones (Xiaomi/MIUI, Huawei, Oppo...), Android tue
 * agressivement les services en arrière-plan par défaut. Il faut autoriser
 * manuellement le "démarrage automatique" pour JARVIS dans les paramètres
 * système, sinon l'écoute s'arrêtera après quelques minutes ou au redémarrage.
 */
class WakeWordService : Service(), RecognitionListener {

    // ── Moteur 1 : Porcupine (dédié, basse consommation) ──────────────────────
    private var porcupineManager: PorcupineManager? = null

    // ── Moteur 2 : repli SpeechRecognizer standard ─────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var restartAttempts = 0

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    companion object {
        const val CHANNEL_ID = "jarvis_wakeword_channel"
        const val NOTIFICATION_ID = 4242
        const val ACTION_STOP = "com.jarvis.assistant.STOP_WAKEWORD"

        /** Associe les mots-clés intégrés Porcupine à leur nom en français courant. */
        private val BUILT_IN_KEYWORDS = mapOf(
            "jarvis" to Porcupine.BuiltInKeyword.JARVIS,
            "alexa" to Porcupine.BuiltInKeyword.ALEXA,
            "computer" to Porcupine.BuiltInKeyword.COMPUTER,
            "ordinateur" to Porcupine.BuiltInKeyword.COMPUTER,
            "picovoice" to Porcupine.BuiltInKeyword.PICOVOICE,
            "porcupine" to Porcupine.BuiltInKeyword.PORCUPINE,
            "terminator" to Porcupine.BuiltInKeyword.TERMINATOR,
            "blueberry" to Porcupine.BuiltInKeyword.BLUEBERRY,
            "bumblebee" to Porcupine.BuiltInKeyword.BUMBLEBEE,
            "grapefruit" to Porcupine.BuiltInKeyword.GRAPEFRUIT,
            "grasshopper" to Porcupine.BuiltInKeyword.GRASSHOPPER
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListeningLoop()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(""))
        if (!isRunning) {
            isRunning = true
            startBestAvailableEngine()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    // ─────────────────────────────────────────────────────────────────────────

    private fun startBestAvailableEngine() {
        val accessKey = Prefs.getPicovoiceKey(this)
        val keyword = Prefs.getWakeWord(this).lowercase().trim().ifBlank { "jarvis" }
        val builtIn = BUILT_IN_KEYWORDS[keyword]

        if (accessKey.isNotBlank() && builtIn != null) {
            if (startPorcupine(accessKey, builtIn, keyword)) {
                updateNotification("moteur dédié Porcupine — faible consommation")
                return
            }
        }

        // Repli : reconnaissance vocale standard en boucle
        updateNotification("moteur de repli — plus gourmand en batterie")
        startListeningLoop()
    }

    private fun startPorcupine(accessKey: String, keyword: Porcupine.BuiltInKeyword, label: String): Boolean {
        return try {
            val callback = PorcupineManagerCallback {
                triggerVoiceMode()
            }
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(keyword)
                .build(applicationContext, callback)
            porcupineManager?.start()
            true
        } catch (e: Exception) {
            // Clé invalide, quota dépassé, appareil non supporté... on bascule sur le repli.
            porcupineManager = null
            false
        }
    }

    private fun stopPorcupine() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (_: Exception) { }
        porcupineManager = null
    }

    // ── Moteur de repli : SpeechRecognizer standard en boucle ──────────────────

    private fun startListeningLoop() {
        handler.post {
            if (!isRunning) return@post

            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                updateNotification("reconnaissance vocale indisponible sur cet appareil")
                return@post
            }

            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(this@WakeWordService)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }

            try {
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                scheduleRestart()
            }
        }
    }

    private fun scheduleRestart() {
        if (!isRunning) return
        restartAttempts++
        val delay = if (restartAttempts > 5) 3000L else 400L
        handler.postDelayed({ startListeningLoop() }, delay)
    }

    private fun checkForWakeWord(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val keyword = Prefs.getWakeWord(this).lowercase().trim().ifBlank { "jarvis" }
        return text.lowercase().contains(keyword)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun stopListeningLoop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        stopPorcupine()
    }

    private fun triggerVoiceMode() {
        restartAttempts = 0
        val intent = Intent(this, VoiceModeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(VoiceModeActivity.EXTRA_TRIGGERED_BY_WAKEWORD, true)
        }
        startActivity(intent)

        // Le moteur de repli doit être remis en écoute après un délai (pour ne
        // pas se ré-entendre parler) ; Porcupine, lui, continue de tourner en
        // continu tout seul et n'a pas besoin d'être relancé.
        if (porcupineManager == null) {
            handler.postDelayed({ startListeningLoop() }, 4000L)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecognitionListener (moteur de repli uniquement)
    // ─────────────────────────────────────────────────────────────────────────

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}

    override fun onError(error: Int) {
        scheduleRestart()
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches?.any { checkForWakeWord(it) } == true) {
            triggerVoiceMode()
        } else {
            scheduleRestart()
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches?.any { checkForWakeWord(it) } == true) {
            speechRecognizer?.stopListening()
            triggerVoiceMode()
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Notification (obligatoire pour un service au premier plan)
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "JARVIS — Écoute permanente", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indique que JARVIS écoute en arrière-plan pour le mot-clé d'activation."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): android.app.Notification {
        val keyword = Prefs.getWakeWord(this).ifBlank { "Jarvis" }
        val stopIntent = Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS écoute « $keyword »")
            .setContentText(status.ifBlank { "en veille" })
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Arrêter l'écoute", stopPendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        stopListeningLoop()
        super.onDestroy()
    }
}
