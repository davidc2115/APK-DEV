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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JarvisApp
    private val store = app.store
    private val file = File(application.filesDir, "chat.json")

    val messages: androidx.compose.runtime.snapshots.SnapshotStateList<ChatMsg> get() = app.chat
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
    /** Jarvis est en train de parler (animation de la tête du dragon). */
    var parle by mutableStateOf(false)
    /** Réponse en cours d'écriture (flux d'Hermès), affichée et dite au fil de l'eau. */
    var enDirect by mutableStateOf("")
        private set
    var pendingConfirm by mutableStateOf<String?>(null)
        private set

    private var confirmDeferred: CompletableDeferred<Boolean>? = null
    // Les appels d'outils peuvent être parallèles ; une seule confirmation utilisateur doit être affichée à la fois.
    private val confirmationMutex = Mutex()
    val voice = VoiceManager(application)

    private val tools = ToolBox(app) { question, force ->
        if (!force && !store.confirmSensitive.value) true
        else askConfirm(question) && (!store.biometrie.value || !fr.dd.jarvis.device.Securite.sensible(question) ||
            fr.dd.jarvis.device.Securite.authentifier(app, "Confirmer l'action", question))
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
        voice.nom = "appli"
        voice.muteBeeps = store.muteBeeps.value
        voice.onListening = { listening = it }
        voice.onPartial = { partial = it }
        voice.onFinal = {
            partial = ""
            send(it, fromVoice = true)
        }
        voice.onFail = { msg ->
            partial = ""
            // Phrase entendue mais pas comprise : on réécoute (3 fois au plus) ; vraie erreur : on arrête et on l'affiche.
            if (msg.isBlank() && voiceSession && ++incompris < 3) {
                ecouter()
            } else {
                voiceSession = false
                if (msg.isNotBlank()) status = msg
            }
        }
        // Personne n'a reparlé dans le délai : la conversation se referme d'elle-même (comme Gemini).
        voice.onSilence = { partial = ""; voiceSession = false }
    }

    private suspend fun askConfirm(question: String): Boolean = confirmationMutex.withLock {
        val d = CompletableDeferred<Boolean>()
        confirmDeferred = d
        pendingConfirm = question
        fr.dd.jarvis.service.ConfirmationNotification.reponse = { ok -> d.complete(ok) }
        fr.dd.jarvis.service.ConfirmationNotification.afficher(app, question)
        try {
            d.await()
        } finally {
            pendingConfirm = null
            confirmDeferred = null
            fr.dd.jarvis.service.ConfirmationNotification.reponse = null
            fr.dd.jarvis.service.ConfirmationNotification.annuler(app)
        }
    }

    fun answerConfirm(ok: Boolean) {
        fr.dd.jarvis.service.ConfirmationNotification.annuler(app)
        confirmDeferred?.complete(ok)
    }

    /** Micro touché : passe automatiquement en conversation (réponse parlée puis réécoute) jusqu'à ce qu'on le retouche ou qu'on dise « merci / c'est tout ».
     *  Exposé en état Compose pour afficher l'orbe plein écran pendant toute la session (écoute, réflexion, réponse parlée). */
    private var voiceSessionEtat by mutableStateOf(false)
    var voiceSession: Boolean
        get() = voiceSessionEtat
        private set(v) {
            if (v == voiceSessionEtat) return
            voiceSessionEtat = v
            // Un seul micro à la fois : pendant la conversation à l'écran, le service ne guette plus « Jarvis »
            // (sinon les deux entendaient la même phrase : bips et réponses en double).
            fr.dd.jarvis.service.JarvisService.suspendrePourAppli(v)
            // Musique en pause pour toute la conversation, reprise à la fin (et plus à chaque phrase).
            if (v) voice.sessionDebut() else voice.sessionFin()
        }
    /** Phrases non comprises d'affilée pendant la conversation vocale. */
    private var incompris = 0
    private fun silenceMs() = store.silenceSec.value.coerceIn(3, 30) * 1000L

    /** Écoute dans la conversation : se referme seule après « silenceSec » secondes sans parole. */
    private fun ecouter(delai: Long = silenceMs()) {
        fr.dd.jarvis.ai.Http.prechauffer()
        voice.bargeIn = store.coupeParole.value
        voice.duplex = store.duplex.value
        voice.ecouterConversation(delai)
    }
    private val stopWords = Regex("^(merci|c'est tout|c est tout|stop|arrete|arrête|termine|terminé|fin|a plus tard|à plus tard|au revoir|laisse tomber|ça suffit|ca suffit)\\b.*", RegexOption.IGNORE_CASE)

    fun toggleListening() {
        if (listening) { voiceSession = false; voice.stopListening() } else {
            status = ""
            voiceSession = true
            incompris = 0
            voice.stopSpeaking()
            ecouter(maxOf(silenceMs(), 8000L))
        }
    }

    /** Saisie au clavier commencée : connexion à l'IA préparée pendant que l'utilisateur écrit. */
    fun saisieCommencee() = fr.dd.jarvis.ai.Http.prechauffer()

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
        enDirect = ""
        val conv = store.conversationMode.value || (fromVoice && voiceSession)
        val parler = (store.speakReplies.value || (fromVoice && voiceSession)) && (fromVoice || conv)
        voice.bargeIn = store.coupeParole.value
        voice.duplex = store.duplex.value
        // Réponse parlée (que l'on peut couper en parlant) qui démarre dès la première phrase écrite par Hermès, puis
        // réécoute ; fin automatique après quelques secondes de silence. Autoradio / Bluetooth : réécoute seulement si
        // Jarvis pose une question (sinon il entendrait la radio).
        var finale = ""
        val flux = if (!parler) null else voice.parole {
            parle = false
            val externe = voice.sortieExterne() && !finale.trimEnd().endsWith("?")
            if (conv && (voiceSession || store.conversationMode.value)) { if (externe) voiceSession = false else ecouter() }
        }
        val principal = android.os.Handler(android.os.Looper.getMainLooper())
        viewModelScope.launch {
            val reply = try {
                brain.reply(messages.toList(), onTexte = { d ->
                    flux?.ajouter(d)
                    principal.post { enDirect += d; if (flux != null) parle = true }
                }) { s -> status = s }
            } catch (e: kotlinx.coroutines.CancellationException) {
                flux?.arreter(sansSuite = true)
                throw e
            } catch (e: Throwable) {
                "⚠️ ${e.message ?: e.javaClass.simpleName}"
            }
            finale = reply
            enDirect = ""
            messages.add(ChatMsg(false, reply))
            busy = false
            status = ""
            persist()
            if (conv) incompris = 0
            if (flux != null) {
                parle = true
                flux.finir(reply)
            } else if (conv && fromVoice) {
                ecouter()
            }
        }
    }

    private var convId: String
        get() = app.convId
        set(v) { app.convId = v }

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

    private fun load() { app.chatCharger() }

    override fun onCleared() {
        fr.dd.jarvis.service.JarvisService.suspendrePourAppli(false)
        voice.release()
        super.onCleared()
    }
}
