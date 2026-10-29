package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject

data class ChatMsg(val fromUser: Boolean, val text: String, val ts: Long = System.currentTimeMillis())

/**
 * Cerveau principal (Groq) : reçoit la conversation, choisit les outils, les exécute (les agents Gemini
 * travaillent à l'intérieur des outils) puis répond.
 */
class Brain(
    private val llm: Llm,
    private val tools: ToolBox,
    private val userName: () -> String
) {
    suspend fun reply(history: List<ChatMsg>, onStatus: (String) -> Unit): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", Prompts.brain(userName())))
        history.takeLast(16).forEach { m ->
            messages.put(JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", m.text.take(3000)))
        }

        repeat(6) {
            val msg = try {
                llm.groq.chat(messages, tools.schema)
            } catch (e: ApiException) {
                // Llama génère parfois un appel d'outil mal formé : on réessaie sans outils plutôt que d'échouer.
                if (e.code == 400 && (e.message ?: "").contains("tool_use_failed")) llm.groq.chat(messages, null) else throw e
            }
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return msg.optString("content", "").trim().ifBlank { "…" }
            }

            messages.put(
                JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls)
            )
            val direct = StringBuilder()
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val fn = c.getJSONObject("function")
                val name = fn.getString("name")
                val args = try {
                    JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" })
                } catch (e: Exception) {
                    JSONObject()
                }
                onStatus("⚙️ $name…")
                val res = try {
                    tools.execute(name, args)
                } catch (e: ApiException) {
                    ToolResult("Erreur du service IA : ${e.message}")
                } catch (e: Exception) {
                    ToolResult("Erreur : ${e.message}")
                }
                messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", res.text.take(6000))
                )
                if (res.direct) direct.append(res.text).append("\n\n")
            }
            if (direct.isNotEmpty()) return direct.toString().trim()
            onStatus("Réflexion…")
        }
        return "Je n'ai pas réussi à terminer cette opération."
    }
}
