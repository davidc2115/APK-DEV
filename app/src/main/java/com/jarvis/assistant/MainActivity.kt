package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var messageInput: EditText
    private lateinit var statusText: TextView

    private val messages = mutableListOf<Message>()
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val recognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                messageInput.setText(spokenText)
                sendMessage(spokenText)
            }
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchVoiceRecognition()
        } else {
            Toast.makeText(this, "Permission micro refusée", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        messageInput = findViewById(R.id.messageInput)
        statusText = findViewById(R.id.statusText)
        val micButton = findViewById<TextView>(R.id.micButton)
        val sendButton = findViewById<TextView>(R.id.sendButton)
        val settingsButton = findViewById<TextView>(R.id.settingsButton)

        adapter = ChatAdapter(messages)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        tts = TextToSpeech(this, this)

        addMessage("Bonjour. Je suis JARVIS, prêt à vous assister. Configurez d'abord votre API dans les paramètres (⚙) si ce n'est pas déjà fait.", isUser = false, speak = false)

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                messageInput.text.clear()
            }
        }

        micButton.setOnClickListener {
            checkPermissionAndListen()
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun checkPermissionAndListen() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            launchVoiceRecognition()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Je vous écoute…")
        }
        try {
            recognizerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Reconnaissance vocale indisponible sur cet appareil", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendMessage(text: String) {
        addMessage(text, isUser = true, speak = false)
        conversationHistory.add("user" to text)
        statusText.text = "● JARVIS réfléchit…"

        CoroutineScope(Dispatchers.Main).launch {
            val reply = ApiClient.sendChat(this@MainActivity, conversationHistory)
            conversationHistory.add("assistant" to reply)
            addMessage(reply, isUser = false, speak = true)
            statusText.text = "● en veille"
        }
    }

    private fun addMessage(text: String, isUser: Boolean, speak: Boolean) {
        adapter.addMessage(Message(text, isUser))
        recyclerView.scrollToPosition(messages.size - 1)
        if (speak && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.FRENCH)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
