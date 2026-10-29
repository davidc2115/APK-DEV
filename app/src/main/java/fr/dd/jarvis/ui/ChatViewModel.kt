package fr.dd.jarvis.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Brain
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.ai.ToolBox
import fr.dd.jarvis.voice.VoiceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JarvisApp
    private val store = app.store
    private val file = File(application.filesDir, "chat.json")

    val messages = mutableStateListOf<ChatMsg>()
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var pendingConfirm by mutableStateOf<String?>(null)
        private set

    private var confirmDeferred: CompletableDeferred<Boolean>? = null
    val voice = VoiceManager(application)

    private val tools = ToolBox(app) { question, force ->
        if (!force && !store.confirmSensitive.value) true else askConfirm(question)
    }
    private val brain = Brain(app.llm, tools) { store.userName.value }

    init {
        load()
        // Point du matin préparé en arrière-plan : on l'affiche à l'ouverture.
        val b = store.lastBriefing.value
        if (b.isNotBlank()) {
            messages.add(ChatMsg(false, "# 🌅 Point du matin\n\n$b"))
            store.lastBriefing.set("")
            persist()
        }
        voice.onListening = { listening = it }
        voice.onPartial = { partial = it }
        voice.onFinal = {
            partial = ""
            send(it, fromVoice = true)
        }
        voice.onFail = { msg ->
            partial = ""
            if (msg.isNotBlank()) status = msg
        }
    }

    private suspend fun askConfirm(question: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        confirmDeferred = d
        pendingConfirm = question
        val r = d.await()
        pendingConfirm = null
        return r
    }

    fun answerConfirm(ok: Boolean) {
        confirmDeferred?.complete(ok)
    }

    fun toggleListening() {
        if (listening) voice.stopListening() else {
            status = ""
            voice.startListening()
        }
    }

    fun send(text: String, fromVoice: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        voice.stopSpeaking()
        messages.add(ChatMsg(true, t))
        busy = true
        status = "Réflexion…"
        viewModelScope.launch {
            val reply = try {
                brain.reply(messages.toList()) { s -> status = s }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                "⚠️ ${e.message ?: e.javaClass.simpleName}"
            }
            messages.add(ChatMsg(false, reply))
            busy = false
            status = ""
            persist()
            val conv = store.conversationMode.value
            if (store.speakReplies.value && (fromVoice || conv)) {
                voice.speak(reply) { if (conv) voice.startListening() }
            } else if (conv && fromVoice) {
                voice.startListening()
            }
        }
    }

    fun clear() {
        messages.clear()
        persist()
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            messages.takeLast(60).forEach {
                arr.put(JSONObject().put("u", it.fromUser).put("t", it.text).put("ts", it.ts))
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                messages.add(ChatMsg(o.getBoolean("u"), o.getString("t"), o.optLong("ts")))
            }
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        voice.release()
        super.onCleared()
    }
}
