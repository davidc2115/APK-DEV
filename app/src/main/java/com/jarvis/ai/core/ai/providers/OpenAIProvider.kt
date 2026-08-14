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

/** Fournisseur OpenAI (ChatGPT). */
class OpenAIProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsDataStore
) : AIProvider {
    override val name = "openai"
    override val supportsOffline = false
    override suspend fun isAvailable(): Boolean = settings.getApiKey("openai").isNotBlank()

    override suspend fun chat(history: List<AIMessage>): AIResponse {
        val apiKey = settings.getApiKey("openai")
        val model = settings.getModelOverride("openai").ifBlank { "gpt-4o" }
        val messages = JSONArray().apply {
            history.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }
        val body = JSONObject().put("model", model).put("messages", messages)
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) error("OpenAI API error ${resp.code}")
            val text = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            return AIResponse(text = text, providerName = name, raw = json)
        }
    }
}
