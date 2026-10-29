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
        groq.chat(messages, tools)
    } catch (e: ApiException) {
        if (e.code == 404 && autoFixGroq()) groq.chat(messages, tools) else throw e
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
            groq.ping(k); "✅ OK"
        } catch (e: Exception) {
            "❌ " + (e.message ?: "erreur").take(90)
        }
    }

    suspend fun testGeminiKeys(): List<String> = store.geminiKeys.toList().map { k ->
        mask(k) + " : " + try {
            gemini.ping(k); "✅ OK"
        } catch (e: Exception) {
            "❌ " + (e.message ?: "erreur").take(90)
        }
    }

    companion object {
        fun mask(k: String) = if (k.length > 10) k.take(4) + "…" + k.takeLast(4) else "••••"
    }
}
