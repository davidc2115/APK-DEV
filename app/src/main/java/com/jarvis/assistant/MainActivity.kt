package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var welcomeShown = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openVoiceMode() else {
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

        adapter = ChatAdapter(ConversationStore.messages)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        tts = TextToSpeech(this, this)

        if (ConversationStore.messages.isEmpty()) {
            welcomeShown = true
            addMessage(
                "Bonjour. Je suis JARVIS, prêt à vous assister. Configurez votre IA dans les paramètres (⚙) si ce n'est pas déjà fait.",
                isUser = false,
                speak = false
            )
        }

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                messageInput.text.clear()
            }
        }

        micButton.setOnClickListener { checkPermissionAndOpenVoiceMode() }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
        if (ConversationStore.messages.isNotEmpty()) {
            recyclerView.scrollToPosition(ConversationStore.messages.size - 1)
        }
    }

    private fun checkPermissionAndOpenVoiceMode() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) openVoiceMode() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun openVoiceMode() {
        startActivity(Intent(this, VoiceModeActivity::class.java))
    }

    private fun sendMessage(text: String) {
        addMessage(text, isUser = true, speak = false)
        statusText.text = "● JARVIS réfléchit…"

        CoroutineScope(Dispatchers.Main).launch {
            val reply = ApiClient.sendChat(this@MainActivity, ConversationStore.history)
            addMessage(reply, isUser = false, speak = true)
            statusText.text = "● en veille"
        }
    }

    private fun addMessage(text: String, isUser: Boolean, speak: Boolean) {
        if (isUser) {
            ConversationStore.addUser(text)
        } else {
            ConversationStore.addAssistant(text)
        }
        adapter.notifyItemInserted(ConversationStore.messages.size - 1)
        recyclerView.scrollToPosition(ConversationStore.messages.size - 1)
        if (speak && ttsReady) {
            tts?.speak(MarkdownUtils.stripForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, null)
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
