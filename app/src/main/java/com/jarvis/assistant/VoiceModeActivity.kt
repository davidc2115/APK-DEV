package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceModeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var orbView: OrbView
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isBusy = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else {
            statusText.text = "Permission micro requise pour le mode vocal"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_mode)

        orbView = findViewById(R.id.orbView)
        statusText = findViewById(R.id.voiceStatusText)
        transcriptText = findViewById(R.id.voiceTranscriptText)
        val closeButton = findViewById<TextView>(R.id.closeVoiceButton)
        val micToggle = findViewById<TextView>(R.id.micToggleButton)

        orbView.accentColor = Prefs.getAccentColor(this)
        orbView.visualStyle = if (Prefs.getOrbStyle(this) == "NETWORK_SPHERE") {
            OrbView.VisualStyle.NETWORK_SPHERE
        } else {
            OrbView.VisualStyle.PULSE
        }
        tts = TextToSpeech(this, this)

        closeButton.setOnClickListener { finish() }
        micToggle.setOnClickListener {
            if (!isBusy) checkPermissionAndListen()
        }

        checkPermissionAndListen()
    }

    private fun checkPermissionAndListen() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "Reconnaissance vocale indisponible sur cet appareil"
            return
        }
        isBusy = true
        orbView.state = OrbView.OrbState.LISTENING
        statusText.text = "Je vous écoute…"
        transcriptText.text = ""

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    orbView.state = OrbView.OrbState.THINKING
                    statusText.text = "JARVIS réfléchit…"
                }

                override fun onError(error: Int) {
                    isBusy = false
                    orbView.state = OrbView.OrbState.IDLE
                    statusText.text = "Touchez le micro pour réessayer"
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        transcriptText.text = spoken
                        handleUserSpeech(spoken)
                    } else {
                        isBusy = false
                        orbView.state = OrbView.OrbState.IDLE
                        statusText.text = "Je n'ai rien entendu. Touchez le micro."
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            startListening(intent)
        }
    }

    private fun handleUserSpeech(text: String) {
        ConversationStore.addUser(text)
        CoroutineScope(Dispatchers.Main).launch {
            val reply = ApiClient.sendChat(this@VoiceModeActivity, ConversationStore.history)
            ConversationStore.addAssistant(reply)
            transcriptText.text = reply
            speak(MarkdownUtils.stripForSpeech(reply))
        }
    }

    private fun speak(text: String) {
        orbView.state = OrbView.OrbState.SPEAKING
        statusText.text = "JARVIS répond…"

        if (!ttsReady) {
            isBusy = false
            orbView.state = OrbView.OrbState.IDLE
            statusText.text = "Touchez le micro pour parler"
            return
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    isBusy = false
                    orbView.state = OrbView.OrbState.IDLE
                    statusText.text = "Touchez le micro pour parler"
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    isBusy = false
                    orbView.state = OrbView.OrbState.IDLE
                }
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.FRENCH)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
