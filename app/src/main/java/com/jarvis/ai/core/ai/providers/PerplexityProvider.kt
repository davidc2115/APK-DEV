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
 * Fournisseur Perplexity : utilisé par l'AIRouter pour TaskKind.WEB_SEARCH.
 * Renvoie une réponse déjà résumée en texte (pas besoin d'ouvrir une page web).
 * SerpAPI (core/websearch/WebSearchProvider) est utilisé en complément pour des
 * résultats bruts (liens, snippets) quand un résumé structuré est nécessaire.
 */
class PerplexityProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsDataStore
) : AIProvider {
    override val name = "perplexity"
    override val supportsOffline = false
    override suspend fun isAvailable(): Boolean = settings.getApiKey("perplexity").isNotBlank()

    override suspend fun chat(history: List<AIMessage>): AIResponse {
        val apiKey = settings.getApiKey("perplexity")
        val model = settings.getModelOverride("perplexity").ifBlank { "sonar" }
        val messages = JSONArray().apply {
            history.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }
        val body = JSONObject().put("model", model).put("messages", messages)
        val request = Request.Builder()
            .url("https://api.perplexity.ai/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) error("Perplexity API error ${resp.code}")
            val text = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            return AIResponse(text = text, providerName = name, raw = json)
        }
    }
}
