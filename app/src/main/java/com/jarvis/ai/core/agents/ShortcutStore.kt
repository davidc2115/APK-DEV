package com.jarvis.ai.core.agents

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

data class ShortcutStep(val agent: String, val action: String, val args: JSONObject)
data class Shortcut(val phrase: String, val steps: List<ShortcutStep>)

/**
 * Raccourcis de commande : une phrase exacte (insensible à la casse, aux accents et à la
 * ponctuation) déclenche des actions locales, sans appel IA : instantané et hors-ligne.
 * Par défaut : « Lumos » allume la lampe torche, « Nox » l'éteint.
 */
@Singleton
class ShortcutStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("elite_shortcuts", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(KEY)) {
            add("lumos", listOf(ShortcutStep("phone", "flashlight_on", JSONObject())))
            add("nox", listOf(ShortcutStep("phone", "flashlight_off", JSONObject())))
        }
    }

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun find(text: String): Shortcut? {
        val wanted = normalize(text)
        if (wanted.isEmpty()) return null
        return all().firstOrNull { normalize(it.phrase) == wanted }
    }

    fun all(): List<Shortcut> {
        val root = JSONArray(prefs.getString(KEY, "[]"))
        val result = mutableListOf<Shortcut>()
        for (i in 0 until root.length()) {
            val o = root.getJSONObject(i)
            val stepsJson = o.getJSONArray("steps")
            val steps = mutableListOf<ShortcutStep>()
            for (j in 0 until stepsJson.length()) {
                val s = stepsJson.getJSONObject(j)
                steps += ShortcutStep(s.getString("agent"), s.getString("action"), s.optJSONObject("args") ?: JSONObject())
            }
            result += Shortcut(o.getString("phrase"), steps)
        }
        return result
    }

    fun add(phrase: String, steps: List<ShortcutStep>) {
        val key = normalize(phrase)
        save(all().filter { normalize(it.phrase) != key } + Shortcut(phrase.trim(), steps))
    }

    fun remove(phrase: String): Boolean {
        val key = normalize(phrase)
        val before = all()
        val after = before.filter { normalize(it.phrase) != key }
        if (after.size == before.size) return false
        save(after)
        return true
    }

    private fun save(list: List<Shortcut>) {
        val arr = JSONArray()
        list.forEach { sc ->
            val steps = JSONArray()
            sc.steps.forEach { st ->
                steps.put(JSONObject().put("agent", st.agent).put("action", st.action).put("args", st.args))
            }
            arr.put(JSONObject().put("phrase", sc.phrase).put("steps", steps))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "shortcuts"
    }
}
