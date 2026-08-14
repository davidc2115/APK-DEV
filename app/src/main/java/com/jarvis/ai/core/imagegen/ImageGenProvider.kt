package com.jarvis.ai.core.imagegen

import com.jarvis.ai.data.settings.SettingsDataStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject

/**
 * Génération d'image à la demande depuis le chat. Fournisseur configurable dans les
 * réglages (Stability AI, DALL·E/OpenAI, Gemini Images...). Retourne une URL ou des
 * données base64 selon le fournisseur, exportable ensuite vers le vault Obsidian ou
 * partagée en pièce jointe.
 */
class ImageGenProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsDataStore
) {
    suspend fun generate(prompt: String): ByteArray {
        val apiKey = settings.getApiKey("openai")
        val body = JSONObject().put("model", "gpt-image-1").put("prompt", prompt).put("size", "1024x1024")
        val request = Request.Builder()
            .url("https://api.openai.com/v1/images/generations")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) error("Image gen error ${resp.code}")
            val b64 = json.getJSONArray("data").getJSONObject(0).getString("b64_json")
            return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        }
    }
}
