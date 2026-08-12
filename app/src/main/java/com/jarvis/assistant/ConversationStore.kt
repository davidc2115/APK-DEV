package com.jarvis.assistant

import android.content.Context

/**
 * Une entrée de l'historique envoyé à l'IA. imageBase64 n'est présent que sur
 * les messages utilisateur qui ont joint une photo.
 */
data class HistoryEntry(
    val role: String,
    val text: String,
    val imageBase64: String? = null,
    val imageMime: String? = null
)

/**
 * Conversation partagée entre MainActivity (chat texte) et VoiceModeActivity
 * (mode vocal), pour que les deux restent synchronisés. Persistée en base
 * (voir ConversationDatabase) pour permettre de reprendre une ancienne
 * conversation depuis la barre latérale.
 */
object ConversationStore {
    val messages = mutableListOf<Message>()
    val history = mutableListOf<HistoryEntry>()

    /** ID de la conversation courante en base, ou null si pas encore sauvegardée. */
    var currentConversationId: Long? = null
        private set

    fun addUser(text: String, imageBase64: String? = null, imageMime: String? = null) {
        messages.add(Message(text, true, imageBase64, imageMime))
        history.add(HistoryEntry("user", text, imageBase64, imageMime))
    }

    fun addAssistant(text: String, imageBase64: String? = null, imageMime: String? = null) {
        messages.add(Message(text, false, imageBase64, imageMime))
        history.add(HistoryEntry("assistant", text))
    }

    /** Sauvegarde la conversation courante en base (création ou mise à jour). */
    fun persist(context: Context) {
        currentConversationId = ConversationHistoryManager.save(context, currentConversationId, messages)
    }

    /** Vide la conversation en cours (après l'avoir sauvegardée) pour en démarrer une nouvelle. */
    fun startNew(context: Context) {
        persist(context)
        messages.clear()
        history.clear()
        currentConversationId = null
    }

    /** Charge une conversation existante depuis la base et la rend active. */
    fun loadConversation(context: Context, conversationId: Long) {
        persist(context) // sauvegarde la précédente avant de basculer
        val loaded = ConversationHistoryManager.load(context, conversationId)
        messages.clear()
        messages.addAll(loaded)
        history.clear()
        for (m in loaded) {
            history.add(HistoryEntry(if (m.isUser) "user" else "assistant", m.text, m.imageBase64, m.imageMimeType))
        }
        currentConversationId = conversationId
    }
}
