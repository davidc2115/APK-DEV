package com.jarvis.assistant

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
 * (mode vocal), pour que les deux restent synchronisés.
 */
object ConversationStore {
    val messages = mutableListOf<Message>()
    val history = mutableListOf<HistoryEntry>()

    fun addUser(text: String, imageBase64: String? = null, imageMime: String? = null) {
        messages.add(Message(text, true, imageBase64, imageMime))
        history.add(HistoryEntry("user", text, imageBase64, imageMime))
    }

    fun addAssistant(text: String, imageBase64: String? = null, imageMime: String? = null) {
        messages.add(Message(text, false, imageBase64, imageMime))
        history.add(HistoryEntry("assistant", text))
    }
}
