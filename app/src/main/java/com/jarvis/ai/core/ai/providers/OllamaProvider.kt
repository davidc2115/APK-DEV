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

/**
 * Fournisseur "serveur local" : un PC/NAS du foyer fait tourner Ollama, le téléphone
 * s'y connecte en Wi-Fi local (ex: http://192.168.1.50:11434) ou via VPN (Tailscale/WireGuard)
 * pour un accès distant. Adresse configurable dans les réglages, pas de clé requise.
 */
class OllamaProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsDataStore
) : AIProvider {
    override val name = "ollama"
    override val supportsOffline = true // "offline" vis-à-vis du cloud, dépend du réseau local

    override suspend fun isAvailable(): Boolean = settings.getOllamaBaseUrl().isNotBlank()

    override suspend fun chat(history: List<AIMessage>): AIResponse {
        val baseUrl = settings.getOllamaBaseUrl().trimEnd('/')
        val model = settings.getModelOverride("ollama").ifBlank { "llama3.2" }
        val messages = JSONArray().apply {
            history.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) error("Ollama server error ${resp.code}")
            val text = json.optJSONObject("message")?.optString("content").orEmpty()
            return AIResponse(text = text, providerName = name, raw = json)
        }
    }
}
