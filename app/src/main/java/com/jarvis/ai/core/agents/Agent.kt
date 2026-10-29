package com.jarvis.ai.core.agents

import org.json.JSONObject

/** Une action exposée par un agent. `sensitive` = l'utilisateur doit confirmer avant exécution. */
data class AgentAction(
    val name: String,
    val usage: String,
    val sensitive: Boolean = false
)

/**
 * Contrat d'un sous-agent d'Élite Cortot. Le cerveau principal (AgentOrchestrator) l'appelle
 * via des blocs <tool_call> (format compatible Hermes/Nous Research) et lui renvoie le résultat.
 */
interface Agent {
    val name: String
    val description: String
    val actions: List<AgentAction>
    suspend fun execute(action: String, args: JSONObject): String
}
