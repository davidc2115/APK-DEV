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

    suspend fun agent(system: String, user: String): String =
        if (geminiRing.count() > 0) gemini.generate(system, user) else groq.plain(system, user)

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
