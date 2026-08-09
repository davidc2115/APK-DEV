package com.jarvis.assistant

/**
 * Historique de conversation partagé entre MainActivity (chat texte)
 * et VoiceModeActivity (mode vocal), pour que les deux restent synchronisés.
 */
object ConversationStore {
    val messages = mutableListOf<Message>()
    val history = mutableListOf<Pair<String, String>>()

    fun addUser(text: String) {
        messages.add(Message(text, true))
        history.add("user" to text)
    }

    fun addAssistant(text: String) {
        messages.add(Message(text, false))
        history.add("assistant" to text)
    }
}
