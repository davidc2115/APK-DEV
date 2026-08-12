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
 * Génération d'images IA via l'API OpenAI (DALL-E 3), en réutilisant la clé
 * OpenAI déjà configurée dans ⚙ Paramètres → Clés API. L'image générée est
 * sauvegardée dans un dossier dédié (Pictures/JARVIS-Generated, visible dans
 * la galerie du téléphone) et renvoyée en base64 pour un affichage direct
 * dans le chat.
 *
 * Vidéo et musique : PAS implémenté. Il n'existe pas d'API publique simple
 * et largement accessible pour ça actuellement.
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

        val keys = Prefs.getApiKeysFor(context, Provider.OPENAI)
        if (keys.isEmpty()) {
            return Result(
                "❌ Aucune clé OpenAI configurée. Ajoute-en une dans ⚙ Paramètres → Clés API " +
                    "pour pouvoir générer des images (utilise DALL-E 3).",
                null, null
            )
        }

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
                        return@use // essaie la clé suivante s'il y en a une
                    }

                    val json = JSONObject(bodyStr)
                    val dataArr = json.optJSONArray("data")
                    val b64 = dataArr?.optJSONObject(0)?.optString("b64_json")
                    if (b64.isNullOrBlank()) {
                        return Result("❌ Réponse inattendue du générateur d'images.", null, null)
                    }

                    val savedPath = saveToGallery(context, b64, prompt)
                    return Result(
                        "🎨 Image générée pour « $prompt ».\n📁 Enregistrée dans : $savedPath",
                        b64,
                        "image/png"
                    )
                }
            } catch (e: Exception) {
                // essaie la clé suivante s'il y en a une
            }
        }

        return Result("❌ Échec de la génération d'image (toutes les clés OpenAI ont échoué).", null, null)
    }

    private fun saveToGallery(context: Context, base64: String, prompt: String): String {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "JARVIS-Generated"
            ).also { it.mkdirs() }

            val safePrompt = prompt.take(40).replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()
            val fileName = "${fileDateFormat.format(Date())}_$safePrompt.png"
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            "(échec de la sauvegarde locale : ${e.message})"
        }
    }
}
