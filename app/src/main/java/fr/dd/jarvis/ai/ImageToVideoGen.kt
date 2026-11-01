package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Anime une image existante via le Worker Cloudflare personnel et le Space Wan 2.2 public. */
object ImageToVideoGen {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(320, TimeUnit.SECONDS)
        .callTimeout(360, TimeUnit.SECONDS)
        .build()

    fun dossier(app: JarvisApp) = File(app.filesDir, "videos_ia").apply { mkdirs() }

    suspend fun generate(app: JarvisApp, image: File, prompt: String, duration: Double = 3.5): Pair<File, String> =
        withContext(Dispatchers.IO) {
            val endpoint = app.store.imageVideoWorkerUrl.value.trim().trimEnd('/')
            val token = app.store.imageVideoWorkerToken.value.trim()
            if (endpoint.isBlank()) throw IllegalStateException("Renseigne l'URL de ton Worker Cloudflare dans Réglages › Vidéo IA (animation d'image).")
            if (token.isBlank()) throw IllegalStateException("Renseigne le jeton privé du Worker dans Réglages › Vidéo IA (animation d'image).")
            if (!endpoint.startsWith("https://")) throw IllegalStateException("L'URL du Worker doit commencer par https://.")
            if (!image.exists() || !image.isFile) throw IllegalStateException("Image source introuvable.")
            if (image.length() > 8L * 1024 * 1024) throw IllegalStateException("L'image dépasse la limite de 8 Mo.")
            val params = JSONObject()
                .put("prompt", prompt.ifBlank { "Animate this image naturally, cinematic camera movement, smooth realistic motion." })
                .put("duration_seconds", duration.coerceIn(0.5, 4.5))
                .put("steps", 6)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("image", image.name, image.readBytes().toRequestBody("application/octet-stream".toMediaType()))
                .addFormDataPart("params", params.toString())
                .build()
            val request = Request.Builder()
                .url("$endpoint/generate")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            val videoUrl = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string().orEmpty().take(300)
                    throw ApiException(response.code, when (response.code) {
                        401, 403 -> "Accès au Worker refusé : vérifie le jeton configuré dans Cloudflare et dans Jarvis."
                        413 -> "Image trop volumineuse (maximum 8 Mo)."
                        429 -> "Le service vidéo gratuit est saturé ou en limite de requêtes."
                        else -> "Worker vidéo ${response.code} : ${detail.ifBlank { response.message }}"
                    })
                }
                val reader = response.body?.charStream()?.buffered() ?: throw ApiException(0, "Flux vidéo vide.")
                var event = "message"
                val data = StringBuilder()
                var result: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) {
                        if (event == "complete" && data.isNotEmpty()) {
                            result = extractVideoUrl(data.toString())
                            if (result != null) break
                        } else if (event == "error" && data.isNotEmpty()) {
                            throw ApiException(500, "Wan 2.2 : ${data.toString().take(300)}")
                        }
                        event = "message"
                        data.setLength(0)
                    } else if (line.startsWith("event:")) {
                        event = line.removePrefix("event:").trim()
                    } else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trim())
                    }
                }
                result ?: throw ApiException(0, "La génération s'est interrompue sans vidéo. Le Space gratuit est peut-être en veille, saturé ou a expiré.")
            }
            val videoBytes = http.newCall(Request.Builder().url(videoUrl).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, "Téléchargement du MP4 impossible (${response.code}).")
                response.body?.bytes() ?: throw ApiException(0, "Le fichier MP4 reçu est vide.")
            }
            if (videoBytes.isEmpty()) throw ApiException(0, "Le MP4 généré est vide.")
            val name = "anime_${System.currentTimeMillis()}.mp4"
            val file = File(dossier(app), name).apply { writeBytes(videoBytes) }
            val location = runCatching { app.obsidian.saveBytes("JARVIS/Vidéos IA", name, videoBytes, "video/mp4") }.getOrDefault("")
            file to location
        }

    private fun extractVideoUrl(raw: String): String? {
        val payload = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        val first = payload.opt(0)
        return when (first) {
            is JSONObject -> first.optString("url").ifBlank {
                first.optString("path").takeIf { it.isNotBlank() }?.let { "https://cbensimon-wan2-2-fp8da-aoti-preview2.hf.space/gradio_api/file=$it" }.orEmpty()
            }.ifBlank { null }
            is String -> if (first.startsWith("http")) first else "https://cbensimon-wan2-2-fp8da-aoti-preview2.hf.space/gradio_api/file=$first"
            else -> null
        }
    }
}
