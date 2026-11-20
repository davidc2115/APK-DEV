package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Garde-fou commun aux fournisseurs IA : les appels d'outils et les résultats récents sont conservés,
 * mais l'historique ancien, les descriptions et les sorties volumineuses sont bornés avant envoi.
 */
internal object BudgetRequeteIA {
    const val MAX_OUTILS = 128

    fun messages(source: JSONArray, niveau: Int = 1): JSONArray {
        val strict = niveau >= 2
        val systemeMax = if (strict) 3_200 else 6_000
        val outilMax = if (strict) 550 else 1_100
        val utilisateurMax = if (strict) 1_800 else 3_200
        val assistantMax = if (strict) 450 else 900
        val budgetTotal = if (strict) 24_000 else 42_000
        val items = (0 until source.length()).mapNotNull { source.optJSONObject(it) }
        val dernierUser = items.indexOfLast { it.optString("role") == "user" }
        val anciens = items.withIndex()
            .filter { (i, m) -> i < dernierUser && m.optString("role") in setOf("user", "assistant") && !m.has("tool_calls") }
            .map { it.index }
        val aGarder = anciens.takeLast(if (strict) 3 else 6).toSet()
        val out = JSONArray()
        items.forEachIndexed { i, original ->
            val role = original.optString("role")
            if (i < dernierUser && role in setOf("user", "assistant") && !original.has("tool_calls") && i !in aGarder) return@forEachIndexed
            val copy = JSONObject(original.toString())
            if (copy.opt("content") is String) {
                val limite = when (role) {
                    "system" -> systemeMax
                    "tool" -> outilMax
                    "user" -> if (i == dernierUser) utilisateurMax else assistantMax
                    else -> assistantMax
                }
                val value = copy.optString("content")
                if (value.length > limite) copy.put("content", value.take(limite) + "…")
            }
            out.put(copy)
        }

        // Si une succession d'outils dépasse encore le budget, réduire les anciens résultats en premier.
        var total = taille(out)
        val dernierUserSortie = (0 until out.length()).lastOrNull { out.optJSONObject(it)?.optString("role") == "user" } ?: -1
        if (total > budgetTotal) {
            for (i in 0 until out.length()) {
                if (total <= budgetTotal) break
                val item = out.optJSONObject(i) ?: continue
                val role = item.optString("role")
                if (role == "system" || i == dernierUserSortie) continue
                val value = item.optString("content")
                if (value.isBlank()) continue
                val minimum = if (role == "tool") { if (strict) 80 else 120 } else { if (strict) 120 else 180 }
                val nouveau = value.take(minimum) + if (value.length > minimum) "…" else ""
                item.put("content", nouveau)
                total = taille(out)
            }
        }
        return out
    }

    fun outils(source: JSONArray?, niveau: Int = 1): JSONArray? {
        if (source == null) return null
        val strict = niveau >= 2
        val maximumDescription = if (strict) 90 else 180
        val maximumPropriete = if (strict) 45 else 90
        val taille = source.length()
        val indices = if (taille <= MAX_OUTILS) (0 until taille).toList()
            else (0 until 96).toList() + (taille - 32 until taille).toList()
        val out = JSONArray()
        indices.forEach { index ->
            val item = source.optJSONObject(index) ?: return@forEach
            val copy = JSONObject(item.toString())
            val fn = copy.optJSONObject("function") ?: return@forEach
            fn.put("description", fn.optString("description").take(maximumDescription))
            fn.optJSONObject("parameters")?.optJSONObject("properties")?.let { properties ->
                val keys = properties.keys()
                while (keys.hasNext()) {
                    val prop = properties.optJSONObject(keys.next()) ?: continue
                    if (prop.has("description")) prop.put("description", prop.optString("description").take(maximumPropriete))
                }
            }
            out.put(copy)
        }
        return out
    }

    private fun taille(messages: JSONArray): Int =
        (0 until messages.length()).sumOf { messages.optJSONObject(it)?.toString()?.length ?: 0 }
}
