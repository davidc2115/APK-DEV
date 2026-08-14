package com.jarvis.ai.core.ai

/** Nature de la tâche demandée : sert au AIRouter à choisir le meilleur fournisseur. */
enum class TaskKind {
    CHAT, CODE, WEB_SEARCH, IMAGE
}

data class AIMessage(val role: String, val content: String) // role: "user" | "assistant" | "system"

data class AIResponse(
    val text: String,
    val providerName: String,
    val raw: Any? = null
)

/** Contrat commun à tous les fournisseurs IA (cloud, serveur local, embarqué). */
interface AIProvider {
    val name: String
    val supportsOffline: Boolean
    suspend fun isAvailable(): Boolean
    suspend fun chat(history: List<AIMessage>): AIResponse
}
