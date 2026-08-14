package com.jarvis.ai.core.websearch

import com.jarvis.ai.data.settings.SettingsDataStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

data class WebSearchResult(val title: String, val snippet: String, val url: String)

/**
 * Recherche web via SerpAPI : résultats bruts (titres/snippets/liens), résumés ensuite en
 * texte directement dans le chat par l'AIRouter (pas d'ouverture de navigateur). Perplexity
 * (core/ai/providers/PerplexityProvider) est l'alternative "résumé déjà rédigé" quand un
 * résumé narratif est préférable à une liste de résultats.
 */
class WebSearchProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsDataStore
) {
    suspend fun search(query: String): List<WebSearchResult> {
        val apiKey = settings.getApiKey("serpapi")
        val url = "https://serpapi.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("hl", "fr")
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) error("SerpAPI error ${resp.code}")
            val organic = json.optJSONArray("organic_results") ?: return emptyList()
            return (0 until organic.length()).map { i ->
                val obj = organic.getJSONObject(i)
                WebSearchResult(
                    title = obj.optString("title"),
                    snippet = obj.optString("snippet"),
                    url = obj.optString("link")
                )
            }
        }
    }
}
