package fr.dd.jarvis.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Vidéo IA avec fal.ai / Wan 2.2 5B (API officielle en file d'attente) :
 *  - [texteVersVideo] : une description → un clip ;
 *  - [generate] : anime une image (image → vidéo).
 * Suivi : soumission sur queue.fal.run, interrogation de status_url jusqu'à COMPLETED, puis lecture du résultat sur
 * response_url (qui porte aussi l'erreur détaillée si la génération a échoué). La clé est conservée dans le stockage
 * chiffré Android, jamais dans le dépôt.
 */
object ImageToVideoGen {
    private const val MODELE_IMAGE = "fal-ai/wan/v2.2-5b/image-to-video"
    private const val MODELE_TEXTE = "fal-ai/wan/v2.2-5b/text-to-video"
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
    private const val FPS = 24
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    fun dossier(app: JarvisApp) = File(app.filesDir, "videos_ia").apply { mkdirs() }

    /** Une clé fal.ai est configurée (et le stockage chiffré qui la garde est disponible). */
    fun disponible(app: JarvisApp) = app.store.falApiKey.available && app.store.falApiKey.value.isNotBlank()

    private fun cle(app: JarvisApp): String {
        val key = app.store.falApiKey.value.trim()
        if (!app.store.falApiKey.available) throw IllegalStateException("Le stockage chiffré Android est indisponible : la clé fal.ai ne peut pas être enregistrée de façon sûre.")
        if (key.isBlank()) throw IllegalStateException("Ajoute ta clé fal.ai dans Réglages › Vidéo IA.")
        return key
    }

    /** Nombre d'images pour [secondes] à 24 i/s, dans les limites du modèle (17 à 121 → jusqu'à 5 s). */
    private fun images(secondes: Double) = (secondes.coerceIn(1.0, 5.0) * FPS).toInt().coerceIn(17, 121)

    /** Format d'image demandé → rapport accepté par Wan 2.2 (« 9:16 », « 16:9 », « 1:1 »). */
    fun rapport(format: String): String = when (format.trim().lowercase()) {
        "horizontal", "paysage", "16:9", "landscape" -> "16:9"
        "carre", "carré", "1:1", "square" -> "1:1"
        else -> "9:16"
    }

    /** Clip à partir d'une description (texte → vidéo). [prompt] de préférence en anglais. */
    suspend fun texteVersVideo(app: JarvisApp, prompt: String, secondes: Double = 5.0, format: String = "vertical", nom: String = "video"): Pair<File, String> =
        withContext(Dispatchers.IO) {
            val bytes = clipTexte(app, prompt, secondes, rapport(format))
            enregistrer(app, bytes, "${nom}_${System.currentTimeMillis()}.mp4")
        }

    /** Octets MP4 d'un clip texte → vidéo (utilisé aussi pour les plans des vidéos TikTok). */
    fun clipTexte(app: JarvisApp, prompt: String, secondes: Double, rapport: String): ByteArray {
        if (prompt.isBlank()) throw IllegalStateException("Décris la vidéo à générer.")
        val input = JSONObject()
            .put("prompt", prompt)
            .put("num_frames", images(secondes))
            .put("frames_per_second", FPS)
            .put("resolution", "720p")
            .put("aspect_ratio", rapport)
            .put("enable_prompt_expansion", false)
            .put("enable_safety_checker", true)
        return executer(MODELE_TEXTE, input, cle(app))
    }

    /** Anime une image (image → vidéo). */
    suspend fun generate(app: JarvisApp, image: File, prompt: String, duration: Double = 3.5): Pair<File, String> =
        withContext(Dispatchers.IO) {
            val key = cle(app)
            if (!image.exists() || !image.isFile) throw IllegalStateException("Image source introuvable.")
            if (image.length() > MAX_IMAGE_BYTES) throw IllegalStateException("L'image dépasse 8 Mo. Réduis sa taille puis réessaie.")
            // fal accepte les Data URI base64 comme image_url : pas d'URL publique ni d'hébergement intermédiaire.
            val input = JSONObject()
                .put("image_url", dataUri(image))
                .put("prompt", prompt.ifBlank { "A natural, realistic motion. Preserve the original subject, identity, composition and visual style. Smooth cinematic camera movement." })
                .put("num_frames", images(duration))
                .put("frames_per_second", FPS)
                .put("resolution", "720p")
                .put("aspect_ratio", "auto")
                .put("enable_safety_checker", true)
            enregistrer(app, executer(MODELE_IMAGE, input, key), "anime_${System.currentTimeMillis()}.mp4")
        }

    /** Data URI de l'image ; les grosses images sont recompressées en JPEG pour garder une requête légère. */
    private fun dataUri(image: File): String {
        val ext = image.extension.lowercase()
        if (image.length() <= 2_500_000) {
            val mime = when (ext) { "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "image/png" }
            return "data:$mime;base64," + Base64.encodeToString(image.readBytes(), Base64.NO_WRAP)
        }
        val bmp = BitmapFactory.decodeFile(image.absolutePath) ?: throw IllegalStateException("Image illisible.")
        val echelle = minOf(1.0, 1600.0 / maxOf(bmp.width, bmp.height))
        val petite = if (echelle < 1.0) Bitmap.createScaledBitmap(bmp, (bmp.width * echelle).toInt(), (bmp.height * echelle).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        petite.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun enregistrer(app: JarvisApp, videoBytes: ByteArray, name: String): Pair<File, String> {
        if (videoBytes.isEmpty()) throw ApiException(0, "Le MP4 généré est vide.")
        val file = File(dossier(app), name).apply { writeBytes(videoBytes) }
        val location = app.obsidian.saveBytes("JARVIS/Vidéos IA", name, videoBytes, "video/mp4")
        return file to location
    }

    /**
     * Musique générée par fal.ai : instrumentale (Stable Audio 3, jusqu'à 380 s) ou chanson avec PAROLES chantées
     * (MiniMax Music 3) quand [paroles] est fourni. Renvoie (octets, extension).
     */
    fun musique(app: JarvisApp, description: String, secondes: Double, paroles: String = ""): Pair<ByteArray, String> {
        val key = cle(app)
        return if (paroles.isNotBlank()) {
            val input = JSONObject().put("prompt", description.ifBlank { "catchy modern pop song" })
                .put("lyrics", paroles).put("duration", secondes.coerceIn(10.0, 300.0))
            executer("minimax/music-3", input, key, ::urlAudio) to "wav"
        } else {
            val input = JSONObject().put("prompt", description.ifBlank { "upbeat modern background music, no vocals" })
                .put("duration", secondes.coerceIn(5.0, 380.0)).put("output_format", "mp3")
            executer("fal-ai/stable-audio-3/medium/text-to-audio", input, key, ::urlAudio) to "mp3"
        }
    }

    private fun urlVideo(result: JSONObject) = result.optJSONObject("video")?.optString("url").orEmpty()
        .ifBlank { result.optJSONArray("videos")?.optJSONObject(0)?.optString("url").orEmpty() }

    private fun urlAudio(result: JSONObject) = result.optJSONObject("audio")?.optString("url").orEmpty()
        .ifBlank { result.optJSONObject("audio_file")?.optString("url").orEmpty() }
        .ifBlank { result.optJSONArray("audios")?.optJSONObject(0)?.optString("url").orEmpty() }

    /** Soumet [input] à la file fal.ai de [modele], attend la fin, renvoie les octets du fichier produit (vidéo par défaut). */
    private fun executer(modele: String, input: JSONObject, key: String, extraire: (JSONObject) -> String = ::urlVideo): ByteArray {
        val accepted = executeJson(
            Request.Builder().url("https://queue.fal.run/$modele")
                .header("Authorization", "Key $key")
                .post(input.toString().toRequestBody("application/json".toMediaType()))
                .build()
        )
        val requestId = accepted.optString("request_id")
        // Les URL de suivi renvoyées par fal sont la référence (elles omettent le sous-chemin du modèle) ; repli calculé sinon.
        val appli = modele.split('/').take(2).joinToString("/")
        val statusUrl = accepted.optString("status_url").ifBlank { if (requestId.isBlank()) "" else "https://queue.fal.run/$appli/requests/$requestId/status" }
        val responseUrl = accepted.optString("response_url").ifBlank { if (requestId.isBlank()) "" else "https://queue.fal.run/$appli/requests/$requestId" }
        if (statusUrl.isBlank() || responseUrl.isBlank()) throw ApiException(0, "Réponse fal.ai incomplète : URL de suivi absente.")

        var lastStatus = "IN_QUEUE"
        val debut = System.currentTimeMillis()
        while (true) {
            Thread.sleep(if (System.currentTimeMillis() - debut < 60_000) 3000 else 6000)
            val status = runCatching {
                executeJson(Request.Builder().url(statusUrl).header("Authorization", "Key $key").get().build())
            }.getOrElse { e ->
                // Une erreur réseau passagère pendant le suivi ne doit pas abandonner une génération déjà payée.
                if (e is ApiException && e.code in setOf(401, 402, 403, 404)) throw e
                null
            }
            if (status != null) {
                lastStatus = status.optString("status", lastStatus)
                when (lastStatus) {
                    "COMPLETED" -> break
                    "FAILED", "CANCELLED", "ERROR" -> throw ApiException(500, "fal.ai a échoué : ${status.optString("error", "génération refusée")}")
                }
            }
            if (System.currentTimeMillis() - debut > 12 * 60_000) throw ApiException(408, "La génération prend trop de temps (dernier état : $lastStatus). Réessaie plus tard.")
        }

        // Le résultat (ou l'erreur détaillée si la génération a échoué) se lit sur response_url.
        val result = executeJson(Request.Builder().url(responseUrl).header("Authorization", "Key $key").get().build())
        val videoUrl = extraire(result)
        if (!videoUrl.startsWith("https://") && !videoUrl.startsWith("data:")) {
            val detail = result.optString("detail").ifBlank { result.toString().take(200) }
            throw ApiException(0, "fal.ai n'a pas renvoyé de fichier : $detail")
        }
        if (videoUrl.startsWith("data:")) return Base64.decode(videoUrl.substringAfter(','), Base64.DEFAULT)
        return http.newCall(Request.Builder().url(videoUrl).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "Téléchargement du fichier fal.ai impossible (${response.code}).")
            response.body?.bytes() ?: throw ApiException(0, "Le fichier reçu est vide.")
        }
    }

    private fun executeJson(request: Request): JSONObject =
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val j = JSONObject(raw)
                    j.optJSONArray("detail")?.let { arr -> (0 until arr.length()).joinToString(" ; ") { arr.optJSONObject(it)?.optString("msg").orEmpty() } }
                        ?.ifBlank { null } ?: j.optString("detail").ifBlank { j.optString("error") }
                }.getOrDefault(raw).ifBlank { raw }
                val message = when (response.code) {
                    401, 403 -> "Clé fal.ai refusée : vérifie-la dans Réglages › Vidéo IA."
                    402 -> "Crédit fal.ai insuffisant. Consulte ton solde avant de relancer."
                    422 -> "Paramètres, texte ou image refusés par fal.ai : ${detail.take(250)}"
                    429 -> "Limite de requêtes fal.ai atteinte. Attends un peu avant de réessayer."
                    else -> "fal.ai (${response.code}) : ${detail.take(300)}"
                }
                throw ApiException(response.code, message)
            }
            runCatching { JSONObject(raw) }.getOrElse { throw ApiException(0, "Réponse JSON fal.ai invalide.") }
        }
}
