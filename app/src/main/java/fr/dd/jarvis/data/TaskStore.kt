package fr.dd.jarvis.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class JarvisTask(
    val id: String,
    val title: String,
    val dueDate: String = "",
    val notes: String = "",
    val priority: String = "normale",
    val completed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/** Liste locale de tâches JARVIS, persistée sur l'appareil sans envoyer son contenu à un service externe. */
class TaskStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_personal_tasks", Context.MODE_PRIVATE)
    private val key = "tasks_v1"

    @Synchronized
    private fun read(): MutableList<JarvisTask> {
        val raw = prefs.getString(key, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").trim()
                if (title.isBlank()) return@mapNotNull null
                JarvisTask(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                    title = title,
                    dueDate = o.optString("dueDate"),
                    notes = o.optString("notes"),
                    priority = o.optString("priority", "normale"),
                    completed = o.optBoolean("completed"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    private fun write(tasks: List<JarvisTask>) {
        val arr = JSONArray()
        tasks.forEach { task ->
            arr.put(JSONObject()
                .put("id", task.id).put("title", task.title).put("dueDate", task.dueDate)
                .put("notes", task.notes).put("priority", task.priority)
                .put("completed", task.completed).put("createdAt", task.createdAt))
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    @Synchronized
    fun create(title: String, dueDate: String = "", notes: String = "", priority: String = "normale"): String {
        val name = title.trim().take(180)
        if (name.isBlank()) return "Tâche non créée : le titre est obligatoire."
        if (dueDate.isNotBlank() && runCatching { LocalDate.parse(dueDate) }.isFailure)
            return "Tâche non créée : date invalide, utilise yyyy-MM-dd."
        val p = priority.trim().lowercase().let { if (it in setOf("basse", "normale", "haute", "urgente")) it else "normale" }
        val task = JarvisTask(UUID.randomUUID().toString().take(8), name, dueDate, notes.trim().take(1000), p)
        write(read().apply { add(task) })
        return "Tâche créée (#" + task.id + ") : " + task.title +
            (if (dueDate.isNotBlank()) " — échéance " + dueDate else " — sans échéance") + " · priorité " + p
    }

    @Synchronized
    fun list(includeCompleted: Boolean = false): List<JarvisTask> =
        read().filter { includeCompleted || !it.completed }.sortedWith(compareBy<JarvisTask> { it.completed }.thenBy { it.dueDate.ifBlank { "9999-12-31" } }.thenByDescending { it.priority == "urgente" })

    @Synchronized
    fun complete(id: String): String {
        val tasks = read()
        val index = tasks.indexOfFirst { it.id.equals(id.trim().removePrefix("#"), true) }
        if (index < 0) return "Tâche introuvable : " + id
        if (tasks[index].completed) return "Cette tâche est déjà terminée : " + tasks[index].title
        tasks[index] = tasks[index].copy(completed = true)
        write(tasks)
        return "Tâche terminée : " + tasks[index].title
    }

    @Synchronized
    fun pendingDue(until: LocalDate): List<JarvisTask> = list().filter { task ->
        task.dueDate.isNotBlank() && runCatching { !LocalDate.parse(task.dueDate).isAfter(until) }.getOrDefault(false)
    }
}
