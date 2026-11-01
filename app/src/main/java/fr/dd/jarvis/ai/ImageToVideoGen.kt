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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Animation d'une image avec fal.ai / Wan 2.2 (API officielle en file d'attente).
 * La clé est conservée dans le stockage chiffré Android, jamais dans le dépôt.
 */
object ImageToVideoGen {
    private const val MODEL = "fal-ai/wan/v2.2-5b/image-to-video"
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun dossier(app: JarvisApp) = File(app.filesDir, "videos_ia").apply { mkdirs() }

    suspend fun generate(app: JarvisApp, image: File, prompt: String, duration: Double = 3.5): Pair<File, String> =
        withContext(Dispatchers.IO) {
            val key = app.store.falApiKey.value.trim()
            if (key.isBlank()) throw IllegalStateException("Ajoute ta clé fal.ai dans Réglages › Vidéo IA.")
            if (!app.store.falApiKey.available) throw IllegalStateException("Le stockage chiffré Android est indisponible : la clé fal.ai ne peut pas être enregistrée de façon sûre.")
            if (!image.exists() || !image.isFile) throw IllegalStateException("Image source introuvable.")
            if (image.length() > MAX_IMAGE_BYTES) throw IllegalStateException("L'image dépasse 8 Mo. Réduis sa taille puis réessaie.")

            val mime = when (image.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                else -> "image/png"
            }
            // fal accepte les Data URI base64 comme image_url : pas d'URL publique ni d'hébergement intermédiaire.
            val dataUri = "data:$mime;base64," + Base64.encodeToString(image.readBytes(), Base64.NO_WRAP)
            val seconds = duration.coerceIn(2.0, 5.0)
            val frames = (seconds * 16).toInt().coerceIn(17, 81)
            val input = JSONObject()
                .put("image_url", dataUri)
                .put("prompt", prompt.ifBlank { "A natural, realistic motion. Preserve the original subject, identity, composition and visual style. Smooth cinematic camera movement." })
                .put("num_frames", frames)
                .put("frames_per_second", 16)
                .put("resolution", "720p")
                .put("aspect_ratio", "auto")
                .put("enable_safety_checker", true)

            val accepted = executeJson(
                Request.Builder().url("https://queue.fal.run/$MODEL")
                    .header("Authorization", "Key $key")
                    .header("Content-Type", "application/json")
                    .post(input.toString().toRequestBody("application/json".toMediaType()))
                    .build(), key
            )
            val statusUrl = accepted.optString("status_url")
            val responseUrl = accepted.optString("response_url")
            if (statusUrl.isBlank() || responseUrl.isBlank()) throw ApiException(0, "Réponse fal.ai incomplète : URL de suivi absente.")

            var completed = false
            var lastStatus = "IN_QUEUE"
            repeat(200) {
                delay(3000)
                val status = executeJson(
                    Request.Builder().url(statusUrl).header("Authorization", "Key $key").get().build(), key
                )
                lastStatus = status.optString("status", "UNKNOWN")
                when (lastStatus) {
                    "COMPLETED" -> { completed = true; return@repeat }
                    "FAILED", "CANCELLED" -> throw ApiException(500, "fal.ai a échoué : ${status.optString("error", "génération refusée")}")
                }
                if (completed) return@repeat
            }
            if (!completed) throw ApiException(408, "La génération prend trop de temps (dernier état : $lastStatus). Réessaie plus tard.")

            val result = executeJson(
                Request.Builder().url(responseUrl).header("Authorization", "Key $key").get().build(), key
            )
            val videoUrl = result.optJSONObject("video")?.optString("url").orEmpty()
            if (!videoUrl.startsWith("https://")) throw ApiException(0, "fal.ai n'a pas renvoyé d'URL MP4 valide.")

            val videoBytes = http.newCall(Request.Builder().url(videoUrl).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, "Téléchargement du MP4 impossible (${response.code}).")
                response.body?.bytes() ?: throw ApiException(0, "Le MP4 reçu est vide.")
            }
            if (videoBytes.isEmpty()) throw ApiException(0, "Le MP4 généré est vide.")
            val name = "anime_${System.currentTimeMillis()}.mp4"
            val file = File(dossier(app), name).apply { writeBytes(videoBytes) }
            val location = runCatching { app.obsidian.saveBytes("JARVIS/Vidéos IA", name, videoBytes, "video/mp4") }.getOrDefault("")
            file to location
        }

    private fun executeJson(request: Request, key: String): JSONObject =
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optString("detail").ifBlank { JSONObject(raw).optString("error") } }.getOrDefault(raw)
                val message = when (response.code) {
                    401, 403 -> "Clé fal.ai refusée : vérifie-la dans Réglages › Vidéo IA."
                    402 -> "Crédit fal.ai insuffisant. Consulte ton solde avant de relancer."
                    422 -> "Paramètres ou image refusés par fal.ai : ${detail.take(250)}"
                    429 -> "Limite de requêtes fal.ai atteinte. Attends un peu avant de réessayer."
                    else -> "fal.ai (${response.code}) : ${detail.take(300)}"
                }
                throw ApiException(response.code, message)
            }
            runCatching { JSONObject(raw) }.getOrElse { throw ApiException(0, "Réponse JSON fal.ai invalide.") }
        }
}
