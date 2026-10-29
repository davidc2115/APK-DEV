package com.jarvis.ai.core.agents

import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Gère la liste des raccourcis. L'exécution des raccourcis est faite par l'orchestrateur. */
@Singleton
class ShortcutAgent @Inject constructor(private val store: ShortcutStore) : Agent {
    override val name = "shortcuts"
    override val description =
        "Raccourcis de commande : un mot ou une phrase déclenche des actions sans passer par l'IA (ex. « lumos » allume la lampe)."
    override val actions = listOf(
        AgentAction("list", "{}"),
        AgentAction(
            "add",
            "{\"phrase\":\"lumos\",\"steps\":[{\"agent\":\"phone\",\"action\":\"flashlight_on\",\"args\":{}}]}",
            sensitive = true
        ),
        AgentAction("remove", "{\"phrase\":\"lumos\"}", sensitive = true)
    )

    override suspend fun execute(action: String, args: JSONObject): String = when (action) {
        "list" -> {
            val arr = JSONArray()
            store.all().forEach { sc ->
                val steps = JSONArray()
                sc.steps.forEach { st ->
                    steps.put(JSONObject().put("agent", st.agent).put("action", st.action).put("args", st.args))
                }
                arr.put(JSONObject().put("phrase", sc.phrase).put("steps", steps))
            }
            arr.toString()
        }
        "add" -> {
            val phrase = args.getString("phrase")
            val stepsJson = args.getJSONArray("steps")
            val steps = mutableListOf<ShortcutStep>()
            for (i in 0 until stepsJson.length()) {
                val s = stepsJson.getJSONObject(i)
                steps += ShortcutStep(s.getString("agent"), s.getString("action"), s.optJSONObject("args") ?: JSONObject())
            }
            require(steps.isNotEmpty()) { "Un raccourci doit contenir au moins une étape." }
            store.add(phrase, steps)
            "⚡ Raccourci « $phrase » enregistré (${steps.size} action(s))."
        }
        "remove" -> {
            val phrase = args.getString("phrase")
            if (store.remove(phrase)) "🗑️ Raccourci « $phrase » supprimé." else "Aucun raccourci « $phrase »."
        }
        else -> "Action inconnue : $action"
    }
}
