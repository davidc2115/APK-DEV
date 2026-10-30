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
    private val brain = Brain(app.llm, tools, app) { store.userName.value }

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
            voiceSession = false   // silence ou erreur : la conversation vocale s'arrête
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

    /** Micro touché : passe automatiquement en conversation (réponse parlée puis réécoute) jusqu'à ce qu'on le retouche ou qu'on dise « merci / c'est tout ». */
    private var voiceSession = false
    private val stopWords = Regex("^(merci|c'est tout|c est tout|stop|arrete|arrête|termine|terminé|fin|a plus tard|à plus tard|au revoir|laisse tomber|ça suffit|ca suffit)\\b.*", RegexOption.IGNORE_CASE)

    fun toggleListening() {
        if (listening) { voiceSession = false; voice.stopListening() } else {
            status = ""
            voiceSession = true
            voice.startListening()
        }
    }

    fun send(text: String, fromVoice: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        voice.stopSpeaking()
        if (fromVoice && stopWords.matches(t)) {
            voiceSession = false
            messages.add(ChatMsg(true, t)); messages.add(ChatMsg(false, "D'accord."))
            persist()
            if (store.speakReplies.value) voice.speak("D'accord.") {}
            return
        }
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
            val conv = store.conversationMode.value || (fromVoice && voiceSession)
            if ((store.speakReplies.value || (fromVoice && voiceSession)) && (fromVoice || conv)) {
                voice.speak(reply) { if (conv) voice.startListening() }
            } else if (conv && fromVoice) {
                voice.startListening()
            }
        }
    }

    private var convId = ""

    data class ConvInfo(val id: String, val title: String, val count: Int, val ts: Long)

    private val convDir get() = File(getApplication<Application>().filesDir, "convs").apply { mkdirs() }

    private fun serialize(list: List<ChatMsg>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.fromUser).put("t", it.text).put("ts", it.ts)) }
        return arr.toString()
    }

    /** Range la conversation en cours dans l'historique (si elle contient un message de l'utilisateur). */
    private fun archiveCurrent() {
        val list = messages.toList()
        if (list.none { it.fromUser }) return
        if (convId.isBlank()) convId = (list.first().ts.takeIf { it > 0 } ?: System.currentTimeMillis()).toString()
        runCatching { File(convDir, "$convId.json").writeText(serialize(list)) }
    }

    /** Nouvelle conversation : l'actuelle est conservée dans l'historique. */
    fun nouvelle() {
        if (busy) return
        voice.stopSpeaking()
        archiveCurrent(); messages.clear(); persist()
        convId = ""
    }

    fun historique(): List<ConvInfo> = (convDir.listFiles() ?: emptyArray()).mapNotNull { f ->
        runCatching {
            val arr = JSONArray(f.readText())
            var titre = "Conversation"
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { if (it.getBoolean("u")) { titre = it.getString("t").take(60); return@let } }
            val firstUser = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.getBoolean("u") }?.getString("t")?.take(60) ?: titre
            ConvInfo(f.nameWithoutExtension, firstUser, arr.length(), f.nameWithoutExtension.toLongOrNull() ?: f.lastModified())
        }.getOrNull()
    }.sortedByDescending { it.ts }

    fun ouvrir(id: String) {
        if (busy) return
        val f = File(convDir, "$id.json")
        if (!f.exists()) return
        val text = f.readText()
        archiveCurrent()
        messages.clear()
        runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { messages.add(ChatMsg(it.getBoolean("u"), it.getString("t"), it.optLong("ts"))) }
        }
        convId = id
        persist()
    }

    fun supprimer(id: String) { File(convDir, "$id.json").delete(); if (id == convId) { messages.clear(); convId = ""; persist() } }

    /** Efface la conversation en cours et tout l'historique. */
    fun toutEffacer() {
        messages.clear(); convId = ""; persist()
        convDir.listFiles()?.forEach { it.delete() }
    }

    /** Remise à zéro complète : conversations, mémoire des consignes, images générées, point du matin en attente. */
    fun remiseAZero() {
        toutEffacer()
        runCatching { app.memo.clear() }
        File(getApplication<Application>().filesDir, "images").listFiles()?.forEach { it.delete() }
        store.lastBriefing.set("")
    }

    fun clear() = nouvelle()

    private fun persist() {
        try {
            val arr = JSONArray()
            messages.takeLast(200).forEach {
                arr.put(JSONObject().put("u", it.fromUser).put("t", it.text).put("ts", it.ts))
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
        archiveCurrent()
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
