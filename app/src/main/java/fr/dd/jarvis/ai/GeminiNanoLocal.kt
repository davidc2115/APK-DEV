package fr.dd.jarvis.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.withTimeout

/**
 * Petit moteur local facultatif. Il ne remplace jamais Hermès ni le routage existant :
 * il ne sert qu'aux phrases de conversation détectées par Brain.conversationRapide().
 *
 * Pas de téléchargement déclenché en douce : si AICore n'a pas déjà préparé Nano,
 * on repasse immédiatement sur le fournisseur cloud déjà utilisé par Jarvis.
 */
internal object GeminiNanoLocal {
    private const val TIMEOUT_MS = 1_600L
    private const val STATUS_CACHE_MS = 60_000L

    @Volatile private var cachedStatus: Int? = null
    @Volatile private var checkedAt: Long = 0L
    private val model by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Generation.getClient() }

    suspend fun repondre(prompt: String): String? {
        if (prompt.isBlank() || prompt.length > 7_000) return null
        return try {
            withTimeout(TIMEOUT_MS) {
                val now = System.currentTimeMillis()
                var status = cachedStatus
                if (status == null || now - checkedAt > STATUS_CACHE_MS) {
                    status = model.checkStatus()
                    cachedStatus = status
                    checkedAt = now
                }
                if (status != FeatureStatus.AVAILABLE) return@withTimeout null
                model.generateContent(prompt).text
                    .trim()
                    .takeIf { it.isNotBlank() && it.length <= 2_000 }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // AICore absent, occupé, quota atteint ou usage en arrière-plan : fallback cloud.
            cachedStatus = null
            null
        }
    }
}
