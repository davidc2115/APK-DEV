package fr.dd.jarvis.ai

import fr.dd.jarvis.data.Store

/**
 * Façade des deux fournisseurs :
 *  - Groq = cerveau principal (conversation + choix des outils), clés en rotation ;
 *  - Gemini = agents (analyse du planning, tri/résumé des mails, fiches contacts), clés en rotation.
 * Si aucune clé Gemini n'est configurée, les agents se rabattent sur Groq.
 */
class Llm(private val store: Store) {
    private val groqRing = KeyRing { store.groqKeys.toList() }
    private val geminiRing = KeyRing { store.geminiKeys.toList() }
    val groq = GroqClient(groqRing) { store.groqModel.value }
    val gemini = GeminiClient(geminiRing) { store.geminiModel.value }

    fun keyStatus(): String {
        val g = groqRing.count()
        val m = geminiRing.count()
        return "Groq $g clé(s)" + (if (groqRing.pausedCount() > 0) " (${groqRing.pausedCount()} en pause)" else "") +
            " · Gemini $m clé(s)" + (if (geminiRing.pausedCount() > 0) " (${geminiRing.pausedCount()} en pause)" else "")
    }

    /** Dernière erreur des agents (affichée quand un repli sans IA est utilisé). */
    @Volatile
    var lastError: String = ""

    /** Agent : Gemini d'abord (clés en rotation), puis Groq en secours. Échoue avec un message lisible. */
    suspend fun agent(system: String, user: String): String {
        val errors = mutableListOf<String>()
        if (geminiRing.count() > 0) {
            try {
                return try {
                    gemini.generate(system, user)
                } catch (e: ApiException) {
                    if (e.code == 404 && autoFixGemini()) gemini.generate(system, user) else throw e
                }.ifBlank { throw IllegalStateException("réponse vide") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "Gemini : " + (e.message ?: e.javaClass.simpleName).take(160)
            }
        }
        if (groqRing.count() > 0) {
            try {
                return plainGroq(system, user).ifBlank { throw IllegalStateException("réponse vide") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "Groq : " + (e.message ?: e.javaClass.simpleName).take(160)
            }
        }
        lastError = errors.joinToString(" | ").ifBlank { "aucune clé configurée" }
        throw IllegalStateException(lastError)
    }

    private suspend fun plainGroq(system: String, user: String): String = try {
        groq.plain(system, user)
    } catch (e: ApiException) {
        if (e.code == 404 && autoFixGroq()) groq.plain(system, user) else throw e
    }

    /** Appel du cerveau ; si le modèle n'existe plus (404), choisit automatiquement un modèle disponible. */
    suspend fun groqChat(messages: org.json.JSONArray, tools: org.json.JSONArray?): org.json.JSONObject = try {
        groq.chat(messages, tools, 1800)
    } catch (e: ApiException) {
        when {
            e.code == 404 && autoFixGroq() -> groq.chat(messages, tools, 1800)
            // 413 : requête trop grosse pour la limite de jetons/minute du modèle → on compacte (historique, résultats d'outils, schéma) et on réessaie.
            e.code == 413 || (e.code == 429 && (e.message ?: "").contains("tokens per minute", true) && (e.message ?: "").contains("Requested")) -> {
                try { groq.chat(compacter(messages, 1), compacterSchema(tools, 1), 1000) }
                catch (e2: ApiException) { if (e2.code == 413) groq.chat(compacter(messages, 2), compacterSchema(tools, 2), 700) else throw e2 }
            }
            else -> throw e
        }
    }

    private fun compacter(messages: org.json.JSONArray, niveau: Int): org.json.JSONArray {
        val sys = if (niveau == 1) 5000 else 2800
        val txt = if (niveau == 1) 700 else 350
        val outil = if (niveau == 1) 1800 else 800
        val src = (0 until messages.length()).map { messages.getJSONObject(it) }
        val dernierUser = src.indexOfLast { it.optString("role") == "user" }
        // Messages simples (sans appel d'outil) trop anciens : on ne garde que les plus récents.
        val anciens = src.withIndex().filter { (i, m) -> i < dernierUser && m.optString("role") in setOf("user", "assistant") && !m.has("tool_calls") }.map { it.index }
        val aGarder = anciens.takeLast(if (niveau == 1) 6 else 2).toSet()
        val out = org.json.JSONArray()
        src.forEachIndexed { i, m ->
            val role = m.optString("role")
            if (i < dernierUser && role in setOf("user", "assistant") && !m.has("tool_calls") && i !in aGarder) return@forEachIndexed
            val c = org.json.JSONObject(m.toString())
            val limite = when (role) { "system" -> sys; "tool" -> outil; else -> txt }
            if (c.opt("content") is String) c.put("content", c.getString("content").let { if (it.length > limite) it.take(limite) + "…" else it })
            out.put(c)
        }
        return out
    }

    private fun compacterSchema(tools: org.json.JSONArray?, niveau: Int): org.json.JSONArray? {
        if (tools == null) return null
        val out = org.json.JSONArray()
        for (i in 0 until tools.length()) {
            val t = org.json.JSONObject(tools.getJSONObject(i).toString())
            val f = t.getJSONObject("function")
            f.put("description", f.optString("description").take(if (niveau == 1) 160 else 90))
            f.optJSONObject("parameters")?.optJSONObject("properties")?.let { p ->
                p.keys().forEach { k -> p.optJSONObject(k)?.let { o -> if (niveau == 2) o.remove("description") else o.put("description", o.optString("description").take(70)) } }
            }
            out.put(t)
        }
        return out
    }

    suspend fun listGroqModels(): List<String> = firstKey(store.groqKeys.toList()) { groq.listModels(it) }
    suspend fun listGeminiModels(): List<String> = firstKey(store.geminiKeys.toList()) { gemini.listModels(it) }

    private suspend fun <T> firstKey(keys: List<String>, f: suspend (String) -> T): T {
        var last: Exception = IllegalStateException("Aucune clé configurée")
        for (k in keys) try { return f(k) } catch (e: Exception) { last = e }
        throw last
    }

    fun bestGroq(models: List<String>): String? {
        val pref = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.1-8b-instant")
        return pref.firstOrNull { it in models } ?: models.firstOrNull { it.startsWith("llama") && !it.contains("guard") }
    }

    fun bestGemini(models: List<String>): String? {
        val pref = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-flash-latest", "gemini-3.5-flash-lite", "gemini-2.5-flash")
        pref.firstOrNull { it in models }?.let { return it }
        return models.filter { it.contains("flash") && !it.contains("preview") && !it.contains("exp") }.maxOrNull()
    }

    suspend fun autoFixGroq(): Boolean = try {
        val m = bestGroq(listGroqModels())
        if (m != null && m != store.groqModel.value) { store.groqModel.set(m); true } else false
    } catch (e: Exception) { false }

    suspend fun autoFixGemini(): Boolean = try {
        val m = bestGemini(listGeminiModels())
        if (m != null && m != store.geminiModel.value) { store.geminiModel.set(m); true } else false
    } catch (e: Exception) { false }

    suspend fun testGroqKeys(): List<String> = store.groqKeys.toList().map { k ->
        mask(k) + " : " + try {
            val t0 = System.currentTimeMillis(); groq.ping(k); "✅ OK (${System.currentTimeMillis() - t0} ms)"
        } catch (e: Exception) {
            "❌ " + (e.message ?: "erreur").take(90)
        }
    }

    suspend fun testGeminiKeys(): List<String> = store.geminiKeys.toList().map { k ->
        mask(k) + " : " + try {
            val t0 = System.currentTimeMillis(); gemini.ping(k); "✅ OK (${System.currentTimeMillis() - t0} ms)"
        } catch (e: Exception) {
            "❌ " + (e.message ?: "erreur").take(90)
        }
    }

    companion object {
        fun mask(k: String) = if (k.length > 10) k.take(4) + "…" + k.takeLast(4) else "••••"
    }
}
