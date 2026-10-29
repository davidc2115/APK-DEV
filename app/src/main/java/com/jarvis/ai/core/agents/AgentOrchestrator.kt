package com.jarvis.ai.core.agents

import com.jarvis.ai.core.ai.AIMessage
import com.jarvis.ai.core.ai.AIResponse
import com.jarvis.ai.core.ai.AIRouter
import com.jarvis.ai.core.ai.TaskKind
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cerveau principal d'Élite Cortot.
 *
 * 1. Réponse à une confirmation en attente (« oui » / « non »).
 * 2. Raccourcis locaux (« Lumos », « Nox »...) : exécutés sans IA.
 * 3. Sinon : boucle IA ↔ sous-agents. Le modèle demande un outil avec un bloc
 *    <tool_call>{"agent":"...","action":"...","args":{...}}</tool_call> (format compatible
 *    Hermes), reçoit <tool_response>...</tool_response>, et répond quand il a tout ce qu'il faut.
 *    Les actions « sensibles » (modifier un agenda, créer un raccourci...) demandent d'abord
 *    une confirmation explicite de l'utilisateur.
 *
 * Le modèle utilisé est choisi par AIRouter (réglages) : Gemini/Groq (offres gratuites),
 * Ollama/Hermes en local, ou Claude avec une clé API.
 */
@Singleton
class AgentOrchestrator @Inject constructor(
    private val aiRouter: AIRouter,
    private val shortcuts: ShortcutStore,
    phoneAgent: PhoneAgent,
    calendarAgent: CalendarAgent,
    shortcutAgent: ShortcutAgent
) {
    private val agents: Map<String, Agent> =
        listOf<Agent>(phoneAgent, calendarAgent, shortcutAgent).associateBy { it.name }

    private data class ToolCall(val agent: String, val action: String, val args: JSONObject)

    @Volatile
    private var pending: ToolCall? = null

    suspend fun run(history: List<AIMessage>, userText: String): AIResponse {
        // 1. Confirmation d'une action sensible en attente
        pending?.let { call ->
            pending = null
            when (shortcuts.normalize(userText)) {
                "oui", "ok", "confirme", "confirmer", "yes", "go", "vas y", "d accord" ->
                    return AIResponse("✅ " + safeExecute(call), "agent")
                "non", "annule", "annuler", "stop", "no" ->
                    return AIResponse("❎ Action annulée.", "agent")
                else -> Unit
            }
        }

        // 2. Raccourcis locaux, sans IA
        shortcuts.find(userText)?.let { sc ->
            val lines = mutableListOf<String>()
            for (step in sc.steps) {
                lines += safeExecute(ToolCall(step.agent, step.action, step.args))
            }
            return AIResponse("⚡ " + lines.joinToString("\n"), "raccourci")
        }

        // 3. Cerveau principal + sous-agents
        val convo = mutableListOf(AIMessage("system", buildSystemPrompt()))
        convo += history.filter { it.role != "system" }.takeLast(MAX_HISTORY)

        var lastProvider = "agent"
        repeat(MAX_STEPS) {
            val response = aiRouter.route(TaskKind.CHAT, convo)
            lastProvider = response.providerName
            val calls = parseToolCalls(response.text)
            if (calls.isEmpty()) {
                return AIResponse(response.text.trim(), lastProvider)
            }

            convo += AIMessage("assistant", response.text)
            val results = StringBuilder()
            for (call in calls) {
                val action = agents[call.agent]?.actions?.firstOrNull { it.name == call.action }
                if (action == null) {
                    results.append("<tool_response>{\"error\":\"outil inconnu : ${call.agent}.${call.action}\"}</tool_response>\n")
                    continue
                }
                if (action.sensitive) {
                    pending = call
                    return AIResponse(
                        "⚠️ Confirmer cette action ?\n• ${call.agent}.${call.action} ${call.args}\n\nRépondez « oui » pour confirmer ou « non » pour annuler.",
                        "agent"
                    )
                }
                results.append("<tool_response>").append(safeExecute(call)).append("</tool_response>\n")
            }
            convo += AIMessage("user", results.toString())
        }
        return AIResponse("⚠️ Trop d'étapes sans réponse finale. Reformulez votre demande.", lastProvider)
    }

    private suspend fun safeExecute(call: ToolCall): String {
        val agent = agents[call.agent] ?: return "Agent inconnu : ${call.agent}"
        return try {
            agent.execute(call.action, call.args)
        } catch (e: SecurityException) {
            "❌ Permission refusée (${e.message ?: "inconnue"}). Accordez-la dans les réglages d'Android."
        } catch (e: Exception) {
            "❌ ${call.agent}.${call.action} a échoué : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private val toolRegex = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)

    /** Accepte {"agent","action","args"} et le format Hermes natif {"name":"agent.action","arguments":{...}}. */
    private fun parseToolCalls(text: String): List<ToolCall> =
        toolRegex.findAll(text).mapNotNull { m ->
            runCatching {
                val o = JSONObject(m.groupValues[1].trim())
                val args = o.optJSONObject("args") ?: o.optJSONObject("arguments") ?: JSONObject()
                if (o.has("agent")) {
                    ToolCall(o.getString("agent"), o.getString("action"), args)
                } else {
                    val name = o.getString("name")
                    val sep = name.indexOfFirst { it == '.' || it == '_' }
                    ToolCall(name.substring(0, sep), name.substring(sep + 1), args)
                }
            }.getOrNull()
        }.toList()

    private fun buildSystemPrompt(): String {
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy HH:mm (VV)", Locale.FRENCH))
        val tools = agents.values.joinToString("\n") { agent ->
            "• ${agent.name} : ${agent.description}\n" + agent.actions.joinToString("\n") {
                "    - ${it.name} ${it.usage}${if (it.sensitive) "  [confirmation requise]" else ""}"
            }
        }
        return """
Tu es Élite Cortot, l'assistant personnel de l'utilisateur sur son téléphone. Tu réponds en français, de façon claire et concise.
Date et heure actuelles : $now.

Tu disposes d'agents. Pour en utiliser un, réponds UNIQUEMENT avec un ou plusieurs blocs :
<tool_call>{"agent":"calendar","action":"list_events","args":{"date":"2026-01-31","calendar":"pro"}}</tool_call>
Le système te renvoie alors <tool_response>…</tool_response>. Quand tu as ce qu'il faut, réponds normalement, sans bloc tool_call.
N'invente jamais un rendez-vous, un calendrier ou un résultat : appelle l'outil. Pour une action marquée [confirmation requise], émets simplement le tool_call : l'application demandera la confirmation à l'utilisateur ; ne dis jamais que c'est déjà fait.
Si l'utilisateur désigne un calendrier par un nom (« mon planning pro »), utilise list_calendars pour trouver son id, et set_alias quand il demande de le nommer.

AGENTS DISPONIBLES
$tools

$PLANNING_STYLE
        """.trimIndent()
    }

    private companion object {
        const val MAX_STEPS = 5
        const val MAX_HISTORY = 20

        val PLANNING_STYLE = """
PRÉSENTATION DES PLANNINGS (toujours appliquée, sans que l'utilisateur ait à la redemander) :
- Titre : « # 📅 Jeudi 29 octobre » (jour et date).
- Un bloc par événement, séparés par une ligne « --- ».
- Titre de bloc : « ### » + emoji adapté + horaires + titre court, ex. « ### 🔧 7h00 – 9h00 · SAV Mairie de Fronville ».
- 📍 adresse cliquable : [Fronville, 52300](https://www.google.com/maps/search/?api=1&query=Fronville+52300+France). Si la description contient déjà un lien Google Maps, réutilise-le.
- 📞 numéros cliquables : [06 75 76 12 34](tel:0675761234). ⚠️ pour les consignes ou alertes.
- Si un événement couvre plusieurs sites, un sous-bloc par site (🏭 nom du site, ☀️ puissance, 🔌 équipements).
- N'affiche que ce qui figure dans l'agenda, rien d'inventé.
        """.trimIndent()
    }
}
