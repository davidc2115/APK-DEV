package fr.dd.jarvis.ai

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

/** Génération texte-vers-vidéo : Google Flow via UseAPI, puis Dreamina via BytePlus ModelArk. */
object VideoProviderGen {
    private val http = OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(55, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun flowDisponible(app: JarvisApp) = app.store.googleFlowApiToken.value.isNotBlank()
    fun dreaminaDisponible(app: JarvisApp) = app.store.dreaminaApiKey.value.isNotBlank()
    fun disponible(app: JarvisApp) = flowDisponible(app) || dreaminaDisponible(app)

    suspend fun genererClipSecours(app: JarvisApp, prompt: String, duration: Int, vertical: Boolean): ByteArray? {
        var flowError: Throwable? = null
        if (flowDisponible(app)) {
            try { return genererFlow(app, prompt, duration, vertical) }
            catch (e: Exception) { flowError = e }
        }
        if (dreaminaDisponible(app)) {
            try { return genererDreamina(app, prompt, duration, vertical) }
            catch (e: Exception) {
                val previous = flowError?.message?.let { "Flow : " + it + ". " }.orEmpty()
                throw IllegalStateException(previous + "Dreamina : " + e.message, e)
            }
        }
        if (flowError != null) throw IllegalStateException("Google Flow : " + flowError.message, flowError)
        return null
    }

    private suspend fun genererFlow(app: JarvisApp, prompt: String, duration: Int, vertical: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val token = app.store.googleFlowApiToken.value.trim()
        val payload = JSONObject()
            .put("prompt", prompt)
            .put("model", app.store.googleFlowModel.value.trim().ifBlank { "veo-3.1-fast" })
            .put("aspectRatio", if (vertical) "portrait" else "landscape")
            .put("duration", duration.coerceIn(4, 8))
            .put("async", true)
        app.store.googleFlowAccountEmail.value.trim().takeIf { it.isNotBlank() }?.let { payload.put("email", it) }
        val submitted = requestJson("POST", "https://api.useapi.net/v1/google-flow/videos", token, payload)
        val jobId = submitted.optString("jobid").ifBlank { submitted.optString("jobId") }
        if (jobId.isBlank()) {
            val directUrl = submitted.optJSONArray("media")?.optJSONObject(0)?.optString("videoUrl").orEmpty()
            if (directUrl.isNotBlank()) return@withContext download(directUrl)
            throw IllegalStateException("UseAPI n'a pas renvoyé d'identifiant de tâche.")
        }
        repeat(90) {
            delay(4000)
            val status = requestJson("GET", "https://api.useapi.net/v1/google-flow/jobs/" + jobId, token)
            when (status.optString("status").lowercase()) {
                "completed", "success", "succeeded" -> {
                    val response = status.optJSONObject("response") ?: status
                    val url = response.optJSONArray("media")?.optJSONObject(0)?.optString("videoUrl").orEmpty()
                    if (url.isBlank()) throw IllegalStateException("Flow a terminé sans fournir d'URL vidéo.")
                    return@withContext download(url)
                }
                "failed", "error", "cancelled" -> throw IllegalStateException(status.optString("error").ifBlank { "La génération Flow a échoué." })
            }
        }
        throw IllegalStateException("Délai dépassé pendant la génération Google Flow.")
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
