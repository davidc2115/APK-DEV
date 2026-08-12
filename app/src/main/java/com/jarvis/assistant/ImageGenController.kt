package com.jarvis.assistant

import android.content.Context
import android.os.Environment
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Génération d'images IA — essaie plusieurs fournisseurs en cascade pour
 * maximiser la fiabilité (si l'un échoue, essaie automatiquement le suivant) :
 *
 * 1. OpenAI DALL-E 3 — si une clé OpenAI est configurée (meilleure qualité, la plus fiable).
 * 2. Stable Diffusion (via Hugging Face Inference API) — si un jeton
 *    Hugging Face est configuré (celui déjà utilisé pour les modèles locaux).
 * 3. Pollinations AI — GRATUIT, AUCUNE CLÉ REQUISE, en dernier recours.
 *    ⚠️ Pollinations traverse actuellement une période de qualité dégradée
 *    (flou, basse résolution) — problème confirmé côté Pollinations eux-mêmes
 *    (issue GitHub #5372, pas un bug de notre intégration). D'où la priorité
 *    donnée aux fournisseurs payants dès qu'une clé est disponible.
 *
 * L'image est sauvegardée dans Pictures/JARVIS-Generated et affichée
 * directement dans le chat.
 *
 * Vidéo et musique : toujours PAS implémenté — aucune API publique simple et
 * largement accessible pour ça actuellement.
 */
object ImageGenController {

    data class Result(val message: String, val base64: String?, val mime: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // la génération d'image peut prendre du temps
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())

    suspend fun generateImage(context: Context, prompt: String): Result {
        if (prompt.isBlank()) {
            return Result("❌ Aucune description d'image fournie.", null, null)
        }

        // 1. OpenAI DALL-E 3, si une clé est configurée — qualité la plus fiable.
        tryOpenAI(context, prompt)?.let { return it }

        // 2. Stable Diffusion via Hugging Face, si un jeton est configuré.
        tryHuggingFace(context, prompt)?.let { return it }

        // 3. Pollinations AI — gratuit, sans clé, dernier recours.
        tryPollinations(context, prompt)?.let { return it }

        return Result(
            "❌ Échec de la génération d'image sur tous les moteurs disponibles " +
                "(OpenAI, Hugging Face, Pollinations). Vérifie ta connexion internet.",
            null, null
        )
    }

    // ─── 1. Pollinations AI (gratuit, sans clé) ────────────────────────────────

    private fun tryPollinations(context: Context, prompt: String): Result? {
        return try {
            val encodedPrompt = java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
            val url = "https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&nologo=true&model=flux&enhance=true"
            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null

                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val savedPath = saveToGallery(context, bytes, prompt)
                Result(
                    "🎨 Image générée pour « $prompt » (Pollinations AI).\n📁 Enregistrée dans : $savedPath",
                    base64,
                    "image/jpeg"
                )
            }
        } catch (e: Exception) {
            null // on passe au fournisseur suivant
        }
    }

    // ─── 2. OpenAI DALL-E 3 ─────────────────────────────────────────────────────

    private fun tryOpenAI(context: Context, prompt: String): Result? {
        val keys = Prefs.getApiKeysFor(context, Provider.OPENAI)
        if (keys.isEmpty()) return null

        for (apiKey in keys) {
            try {
                val body = JSONObject()
                    .put("model", "dall-e-3")
                    .put("prompt", prompt)
                    .put("n", 1)
                    .put("size", "1024x1024")
                    .put("response_format", "b64_json")
                    .toString()
                    .toRequestBody(JSON)

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/images/generations")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        if (response.code == 429 || response.code == 401) {
                            Prefs.markKeyFailed(context, Provider.OPENAI, apiKey)
                        }
                        return@use // essaie la clé OpenAI suivante s'il y en a une
                    }

                    val json = JSONObject(bodyStr)
                    val dataArr = json.optJSONArray("data")
                    val b64 = dataArr?.optJSONObject(0)?.optString("b64_json")
                    if (b64.isNullOrBlank()) return@use

                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val savedPath = saveToGallery(context, bytes, prompt)
                    return Result(
                        "🎨 Image générée pour « $prompt » (OpenAI DALL-E 3).\n📁 Enregistrée dans : $savedPath",
                        b64,
                        "image/png"
                    )
                }
            } catch (e: Exception) {
                // essaie la clé suivante
            }
        }
        return null
    }

    // ─── 3. Stable Diffusion via Hugging Face Inference API ───────────────────

    private fun tryHuggingFace(context: Context, prompt: String): Result? {
        val token = Prefs.getHfToken(context)
        if (token.isBlank()) return null

        return try {
            val body = JSONObject().put("inputs", prompt).toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("https://api-inference.huggingface.co/models/stabilityai/stable-diffusion-xl-base-1.0")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type") ?: ""
                if (!response.isSuccessful || !contentType.startsWith("image/")) return null

                val bytes = response.body?.bytes() ?: return null
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val savedPath = saveToGallery(context, bytes, prompt)
                Result(
                    "🎨 Image générée pour « $prompt » (Stable Diffusion XL).\n📁 Enregistrée dans : $savedPath",
                    base64,
                    "image/jpeg"
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun saveToGallery(context: Context, bytes: ByteArray, prompt: String): String {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "JARVIS-Generated"
            ).also { it.mkdirs() }

            val safePrompt = prompt.take(40).replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()
            val fileName = "${fileDateFormat.format(Date())}_$safePrompt.jpg"
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            "(échec de la sauvegarde locale : ${e.message})"
        }
    }
}
