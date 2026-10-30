package fr.dd.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** Reconnaissance vocale (micro → texte) et synthèse vocale (texte → voix), en français. */
class VoiceManager(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onFail: (String) -> Unit = {}
    var onListening: (Boolean) -> Unit = {}
    /** Coupe les bips de la reconnaissance (écoute en arrière-plan). */
    var muteBeeps = false
    private var muted = false
    private val beepStreams = intArrayOf(android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.STREAM_SYSTEM, android.media.AudioManager.STREAM_MUSIC)

    private fun setMuted(on: Boolean) {
        if (!muteBeeps || muted == on) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        for (st in beepStreams) {
            try {
                if (st == android.media.AudioManager.STREAM_MUSIC && am.isMusicActive && on) continue // ne pas couper une musique en cours
                am.adjustStreamVolume(st, if (on) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Throwable) {}
        }
        muted = on
    }
    private val unmuteLater = Runnable { setMuted(false) }

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                ttsReady = true
            }
        }
    }

    fun startListening() = startListening(0)

    /** [essai] : relances automatiques sur les erreurs transitoires (service vocal déconnecté…), puis repli sur la reconnaissance locale. */
    private fun startListening(essai: Int) {
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                onFail("Reconnaissance vocale indisponible sur cet appareil (installer / activer Google).")
                return@post
            }
            stopSpeaking()
            main.removeCallbacks(unmuteLater)
            setMuted(true)
            recognizer?.destroy()
            val local = essai >= 2 && android.os.Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
            val r = if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    main.postDelayed(unmuteLater, 900)
                    // 11 = service de reconnaissance déconnecté, 5 = erreur client, 10 = trop de requêtes, 8 = occupé : on recrée et on relance
                    if (error in setOf(11, 5, 10, 8, SpeechRecognizer.ERROR_SERVER) && essai < 3) {
                        main.postDelayed({ startListening(essai + 1) }, 500L * (essai + 1))
                        return
                    }
                    onListening(false)
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onFail("")
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onFail("Permission micro refusée.")
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> onFail("Reconnaissance vocale : réseau indisponible.")
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> onFail("Reconnaissance vocale occupée, réessaie.")
                        11 -> onFail("Le service de reconnaissance vocale Google est indisponible : vérifie le réseau, ou Réglages Android › Applis › Google › « Reconnaissance vocale » (activer / mettre à jour).")
                        else -> onFail("Erreur de reconnaissance vocale ($error).")
                    }
                }

                override fun onResults(results: Bundle?) {
                    main.postDelayed(unmuteLater, 900)
                    onListening(false)
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (t.isNotBlank()) onFinal(t) else onFail("")
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (t.isNotBlank()) onPartial(t)
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Laisse le temps de réfléchir / faire des pauses avant de considérer la phrase terminée
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            }
            r.startListening(i)
            onListening(true)
        }
    }

    fun stopListening() {
        main.post {
            recognizer?.stopListening()
            onListening(false)
        }
    }

    /** Couper la parole : si l'utilisateur parle pendant que JARVIS parle, JARVIS se tait (et la suite — réécoute — démarre). */
    var bargeIn = true
    private var barge: BargeIn? = null

    fun speak(text: String, onDone: () -> Unit = {}) {
        main.removeCallbacks(unmuteLater); setMuted(false)
        barge?.stop(); barge = null
        val clean = cleanForSpeech(text)
        if (!ttsReady || clean.isBlank()) {
            main.post(onDone)
            return
        }
        val fini = java.util.concurrent.atomic.AtomicBoolean(false)
        fun terminer() { if (fini.compareAndSet(false, true)) { barge?.stop(); barge = null; main.post(onDone) } }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { terminer() }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { terminer() }
        })
        tts?.speak(clean.take(3500), TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        if (bargeIn && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            barge = BargeIn { main.post { if (!fini.get()) { tts?.stop(); terminer() } } }.also { it.start() }
        }
    }

    fun stopSpeaking() {
        barge?.stop(); barge = null
        tts?.stop()
    }

    fun release() {
        main.post { main.removeCallbacks(unmuteLater); setMuted(false) }
        main.post {
            recognizer?.destroy()
            recognizer = null
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun cleanForSpeech(t: String): String = t
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]"), "")
        .replace(Regex("[#*`_>|]"), "")
        .replace(Regex("(?m)^\\s*-{3,}\\s*$"), ". ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
