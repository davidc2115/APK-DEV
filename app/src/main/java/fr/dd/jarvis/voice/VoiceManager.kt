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

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                ttsReady = true
            }
        }
    }

    fun startListening() {
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                onFail("Reconnaissance vocale indisponible sur cet appareil (installer / activer Google).")
                return@post
            }
            stopSpeaking()
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    onListening(false)
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onFail("")
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onFail("Permission micro refusée.")
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> onFail("Reconnaissance vocale : réseau indisponible.")
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> onFail("Reconnaissance vocale occupée, réessaie.")
                        else -> onFail("Erreur de reconnaissance vocale ($error).")
                    }
                }

                override fun onResults(results: Bundle?) {
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

    fun speak(text: String, onDone: () -> Unit = {}) {
        val clean = cleanForSpeech(text)
        if (!ttsReady || clean.isBlank()) {
            main.post(onDone)
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post(onDone)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post(onDone)
            }
        })
        tts?.speak(clean.take(3500), TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun release() {
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
