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

/** Fournisseur Claude (Anthropic) — recommandé pour chat général et codage. */
class ClaudeProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsDataStore
) : AIProvider {
    override val name = "claude"
    override val supportsOffline = false

    override suspend fun isAvailable(): Boolean = settings.getApiKey("claude").isNotBlank()

    override suspend fun chat(history: List<AIMessage>): AIResponse {
        val apiKey = settings.getApiKey("claude")
        val model = settings.getModelOverride("claude").ifBlank { "claude-sonnet-5" }

        val messages = JSONArray().apply {
            history.filter { it.role != "system" }.forEach {
                put(JSONObject().put("role", it.role).put("content", it.content))
            }
        }
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1024)
            put("messages", messages)
            history.firstOrNull { it.role == "system" }?.let { put("system", it.content) }
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) error("Claude API error ${resp.code}: ${json.optString("error")}")
            val text = json.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()
            return AIResponse(text = text, providerName = name, raw = json)
        }
    }
}
