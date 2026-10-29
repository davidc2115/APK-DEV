package fr.dd.jarvis.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    val JSON = "application/json; charset=utf-8".toMediaType()
}

/**
 * Anneau de clés API avec rotation automatique :
 *  - chaque requête utilise la clé suivante (répartition de la charge sur les quotas gratuits) ;
 *  - une clé qui renvoie 429 / 401 / 403 / 5xx est mise en pause puis on passe à la suivante.
 */
class KeyRing(private val keys: () -> List<String>) {
    private var next = 0
    private val cooldown = mutableMapOf<String, Long>()

    fun count(): Int = keys().count { it.isNotBlank() }

    fun pausedCount(): Int {
        val now = System.currentTimeMillis()
        return keys().count { (cooldown[it] ?: 0L) > now }
    }

    suspend fun <T> run(block: suspend (String) -> T): T {
        val list = keys().filter { it.isNotBlank() }
        if (list.isEmpty()) throw IllegalStateException("Aucune clé configurée (Réglages)")
        val now = System.currentTimeMillis()
        val order = list.indices.map { (next + it) % list.size }
        val (ready, cooling) = order.partition { (cooldown[list[it]] ?: 0L) <= now }
        var last: ApiException? = null
        for (i in ready + cooling) {
            val key = list[i]
            try {
                val result = block(key)
                next = (i + 1) % list.size
                return result
            } catch (e: ApiException) {
                last = e
                val pause = when {
                    e.code == 429 -> 60_000L
                    e.code >= 500 -> 15_000L
                    e.code == 401 || e.code == 403 -> 10 * 60_000L
                    else -> throw e
                }
                cooldown[key] = System.currentTimeMillis() + pause
            }
        }
        throw last ?: IllegalStateException("Toutes les clés ont échoué")
    }
}
