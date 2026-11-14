package fr.dd.jarvis.ai

import android.util.Base64
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Génération vidéo de secours : Gemini Omni (si quota disponible), puis Dreamina/BytePlus. */
object VideoProviderGen {
    private val http = OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(55, TimeUnit.SECONDS).build()
    private val geminiHttp = OkHttpClient.Builder().dns(Http.dnsGoogle).connectionPool(Http.poolGoogle).addInterceptor(Http.relocalisation)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.MINUTES)
        .callTimeout(8, TimeUnit.MINUTES)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun dreaminaDisponible(app: JarvisApp) = app.store.dreaminaApiKey.value.isNotBlank()
    fun geminiDisponible(app: JarvisApp) = app.store.geminiKeys.any { !KeyRing.estCleOpenAi(it) }
    fun disponible(app: JarvisApp) = geminiDisponible(app) || dreaminaDisponible(app)

    /** Gemini Omni 1.2 est essayé en premier avec les clés Google déjà enregistrées dans Réglages. */
    suspend fun genererClipSecours(app: JarvisApp, prompt: String, duration: Int, vertical: Boolean): ByteArray? {
        val erreurs = mutableListOf<String>()
        if (geminiDisponible(app)) {
            try { return genererGemini(app, prompt, duration, vertical) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { erreurs += "Gemini Omni : " + (e.message ?: e.javaClass.simpleName) }
        }
        if (dreaminaDisponible(app)) {
            try { return genererDreamina(app, prompt, duration, vertical) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { erreurs += "Dreamina : " + (e.message ?: e.javaClass.simpleName) }
        }
        if (erreurs.isNotEmpty()) throw IllegalStateException(erreurs.joinToString(". "))
        return null
    }

    /**
     * Gemini Omni 1.2 Flash via l'API Interactions (sortie vidéo base64, jusqu'à 15 s).
     * Chaque clé Gemini est essayée au plus une fois ; les clés OpenAI sont explicitement exclues.
     */
    private suspend fun genererGemini(app: JarvisApp, prompt: String, duration: Int, vertical: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val keys = app.store.geminiKeys.toList().filterNot(KeyRing::estCleOpenAi)
        if (keys.isEmpty()) throw IllegalStateException("Aucune clé API Gemini disponible.")
        val input = prompt.trim() + "\nGenerate one finished video clip, approximately ${duration.coerceIn(3, 10)} seconds long."
        val payload = JSONObject()
            .put("model", "gemini-omni-1.2-flash")
            .put("input", input)
            .put("response_format", JSONObject()
                .put("type", "video")
                .put("aspect_ratio", if (vertical) "9:16" else "16:9")
                .put("resolution", "720p"))
        val failures = mutableListOf<String>()
        for ((index, key) in keys.withIndex()) {
            try {
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/interactions")
                    .header("x-goog-api-key", key)
                    .header("Accept", "application/json")
                    .post(payload.toString().toRequestBody(jsonType))
                    .build()
                val result = geminiHttp.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val detail = runCatching {
                            val j = JSONObject(raw)
                            j.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: raw
                        }.getOrDefault(raw)
                        throw IllegalStateException("HTTP ${response.code} : ${detail.take(240)}")
                    }
                    JSONObject(raw)
                }
                val status = result.optString("status").lowercase()
                if (status.isNotBlank() && status !in setOf("completed", "succeeded", "success")) {
                    throw IllegalStateException(result.optString("error").ifBlank { "statut API : $status" })
                }
                val steps = result.optJSONArray("steps") ?: JSONArray()
                for (i in steps.length() - 1 downTo 0) {
                    val contents = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
                    for (j in 0 until contents.length()) {
                        val part = contents.optJSONObject(j) ?: continue
                        if (part.optString("type") != "video") continue
                        val data = part.optString("data")
                        if (data.isNotBlank()) {
                            val bytes = Base64.decode(data, Base64.DEFAULT)
                            if (bytes.isNotEmpty()) return@withContext bytes
                        }
                        val uri = part.optString("uri")
                        if (uri.startsWith("https://")) return@withContext download(uri)
                    }
                }
                throw IllegalStateException("Gemini a répondu sans contenu vidéo exploitable.")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                failures += "clé ${index + 1}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Toutes les clés Gemini ont échoué : " + failures.joinToString(" ; "))
    }

    private suspend fun genererDreamina(app: JarvisApp, prompt: String, duration: Int, vertical: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val key = app.store.dreaminaApiKey.value.trim()
        val model = app.store.dreaminaModel.value.trim().ifBlank { "seedance-1-5-pro-251215" }
        val payload = JSONObject()
            .put("model", model)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", prompt)))
            .put("ratio", if (vertical) "9:16" else "16:9")
            .put("resolution", "720p")
            .put("duration", duration.coerceIn(4, 12))
            .put("watermark", false)
        val base = "https://ark.ap-southeast.bytepluses.com/api/v3/contents/generations/tasks"
        val submitted = requestJson("POST", base, key, payload)
        val id = submitted.optString("id")
        if (id.isBlank()) throw IllegalStateException("ModelArk n'a pas renvoyé d'identifiant de tâche.")
        repeat(90) {
            delay(4000)
            val status = requestJson("GET", base + "/" + id, key)
            when (status.optString("status").lowercase()) {
                "succeeded", "success", "completed" -> {
                    val url = status.optJSONObject("content")?.optString("video_url").orEmpty()
                    if (url.isBlank()) throw IllegalStateException("Dreamina a terminé sans fournir d'URL vidéo.")
                    return@withContext download(url)
                }
                "failed", "cancelled", "expired" -> throw IllegalStateException(status.optString("error").ifBlank { "La génération Dreamina a échoué." })
            }
        }
        throw IllegalStateException("Délai dépassé pendant la génération Dreamina.")
    }

    private fun requestJson(method: String, url: String, token: String, payload: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(url).header("Authorization", "Bearer " + token).header("Accept", "application/json")
        if (method == "POST") builder.post((payload ?: JSONObject()).toString().toRequestBody(jsonType)) else builder.get()
        http.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code + " : " + raw.take(350))
            return JSONObject(raw)
        }
    }

    private fun download(url: String): ByteArray {
        require(url.startsWith("https://")) { "URL vidéo non sécurisée refusée." }
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Téléchargement vidéo impossible (HTTP " + response.code + ").")
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) throw IllegalStateException("Le MP4 téléchargé est vide.")
            return bytes
        }
    }
}
