package fr.dd.jarvis.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect

/**
 * Moteur local Gemini Nano pour les échanges naturels.
 * Les réponses sont diffusées au fil de leur génération afin que la voix puisse commencer
 * dès la première phrase, sans attendre la fin complète de l'inférence.
 */
internal object GeminiNanoLocal {
    private const val STATUS_TIMEOUT_MS = 2_200L
    private const val GENERATION_TIMEOUT_MS = 7_000L
    private const val STATUS_CACHE_MS = 60_000L

    @Volatile private var cachedStatus: Int? = null
    @Volatile private var checkedAt: Long = 0L
    private val model by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Generation.getClient() }

    suspend fun repondre(prompt: String, onTexte: ((String) -> Unit)? = null): String? {
        if (prompt.isBlank() || prompt.length > 7_000) return null
        val resultat = StringBuilder()
        return try {
            val now = System.currentTimeMillis()
            var status = cachedStatus
            if (status == null || now - checkedAt > STATUS_CACHE_MS) {
                status = withTimeout(STATUS_TIMEOUT_MS) { model.checkStatus() }
                cachedStatus = status
                checkedAt = now
            }
            if (status != FeatureStatus.AVAILABLE) return null

            withTimeout(GENERATION_TIMEOUT_MS) {
                model.generateContentStream(prompt).collect { morceau ->
                    val delta = morceau.candidates.firstOrNull()?.text.orEmpty()
                    if (delta.isNotEmpty() && resultat.length < 2_000) {
                        val reste = delta.take(2_000 - resultat.length)
                        resultat.append(reste)
                        onTexte?.invoke(reste)
                    }
                }
            }
            resultat.toString().trim().takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Si Nano a déjà commencé à répondre, on conserve ce texte plutôt que de lancer
            // une seconde réponse cloud qui ferait doublon dans la conversation.
            resultat.toString().trim().takeIf { it.isNotBlank() } ?: run {
                cachedStatus = null
                null
            }
        }
    }
}
