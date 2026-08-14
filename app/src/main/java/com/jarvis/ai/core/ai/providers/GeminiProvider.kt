package com.jarvis.ai.core.ai.providers

import com.jarvis.ai.core.ai.AIMessage
import com.jarvis.ai.core.ai.AIProvider
import com.jarvis.ai.core.ai.AIResponse
import com.jarvis.ai.data.settings.SettingsDataStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/** Fournisseur Google Gemini. */
class GeminiProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsDataStore
) : AIProvider {
    override val name = "gemini"
    override val supportsOffline = false
    override suspend fun isAvailable(): Boolean = settings.getApiKey("gemini").isNotBlank()

    override suspend fun chat(history: List<AIMessage>): AIResponse {
        val apiKey = settings.getApiKey("gemini")
        val model = settings.getModelOverride("gemini").ifBlank { "gemini-2.0-flash" }
        val contents = JSONArray().apply {
            history.forEach {
                put(JSONObject().put("role", if (it.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.content))))
            }
        }
        val body = JSONObject().put("contents", contents)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) error("Gemini API error ${resp.code}")
            val text = json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optString("text").orEmpty()
            return AIResponse(text = text, providerName = name, raw = json)
        }
    }
}
