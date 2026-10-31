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
    private val app: fr.dd.jarvis.JarvisApp,
    private val userName: () -> String
) {
    suspend fun reply(history: List<ChatMsg>, onStatus: (String) -> Unit): String {
        val recent = history.takeLast(4).filter { it.fromUser }.joinToString(" ") { it.text }
        val societeOn = fr.dd.jarvis.societe.SocieteTools.concerned(recent)
        // Recherche d'imprimante / scan du réseau : toujours par l'outil, jamais par le modèle (qui inventait des appareils).
        val dernier = history.lastOrNull { it.fromUser }?.text.orEmpty().lowercase()
        val outilDirect = when {
            Regex("imprimante").containsMatchIn(dernier) && Regex("cherch|trouv|d[ée]tect|recherch|list|scan|quelle|o[uù] est|y a.t.il").containsMatchIn(dernier)
                && !Regex("imprime[rz]? (ce|le|la|mon|ma|un|une|cette)|lance l.impression").containsMatchIn(dernier) -> "imprimantes_trouver"
            Regex("(scan|analys|d[ée]tect|list|cherch|affich).{0,25}(r[ée]seau|wi-?fi|appareils)|appareils connect").containsMatchIn(dernier) -> "reseau_scanner"
            else -> null
        }
        if (outilDirect != null) {
            onStatus("⚙️ $outilDirect…")
            val args = JSONObject(); if (dernier.contains("serveur")) args.put("via", "serveur")
            return try { tools.execute(outilDirect, args).text } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "❌ ${e.message}" }
        }
        onStatus("🧠 Analyse de la demande…")
        val all = tools.schemaAll()
        val plan = try { Analyseur.analyser(app, history, all) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        val schema: JSONArray
        var societeNeeded = societeOn
        if (plan != null && plan.outils.isNotEmpty()) {
            val keep = (plan.outils + listOf("memoire_ajouter")).toSet()
            schema = JSONArray()
            for (i in 0 until all.length()) if (all.getJSONObject(i).getJSONObject("function").getString("name") in keep) schema.put(all.get(i))
            if (plan.outils.any { it in fr.dd.jarvis.societe.SocieteTools.NAMES }) societeNeeded = true
        } else schema = tools.schemaFor(recent)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content",
            Prompts.brain(userName()) + app.memo.toutes() + (if (societeNeeded) "\n\n" + fr.dd.jarvis.societe.SocieteTools.PROMPT else "") +
                (if (plan != null && plan.note.isNotBlank()) "\n\nAnalyse préalable de la demande (Gemini) — suis ce plan, avec les outils fournis : ${plan.note}" else "")))
        history.takeLast(16).forEach { m ->
            messages.put(JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", m.text.take(3000)))
        }

        repeat(6) {
            val msg = try {
                llm.groqChat(messages, schema)
            } catch (e: ApiException) {
                // Llama génère parfois un appel d'outil mal formé : on réessaie sans outils plutôt que d'échouer.
                if (e.code == 400 && (e.message ?: "").contains("tool_use_failed")) {
                    try { llm.groqChat(messages, schema) } catch (e2: ApiException) {
                        if (e2.code != 400) throw e2
                        val m2 = JSONArray(); for (k in 0 until messages.length()) m2.put(messages.get(k))
                        m2.put(JSONObject().put("role", "system").put("content", "L'appel d'outil a échoué pour ce tour. N'invente AUCUNE donnée (appareils, imprimantes, mails, événements, fichiers) : dis simplement que l'action n'a pas pu être lancée et demande de réessayer."))
                        llm.groqChat(m2, null)
                    }
                } else throw e
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
