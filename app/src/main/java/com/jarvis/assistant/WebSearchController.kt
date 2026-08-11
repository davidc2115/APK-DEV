package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Recherche web générique — utilisé pour les horaires, avis, infos pratiques,
 * ou toute question factuelle sur un lieu/sujet. À NE PAS confondre avec
 * LocationController.openMaps() qui sert uniquement à obtenir un itinéraire.
 *
 * Si une clé SerpAPI est configurée (⚙ Paramètres → Clés API → SerpAPI),
 * les résultats réels sont récupérés et renvoyés en texte, pour que l'IA
 * puisse répondre directement avec l'info demandée (ex: horaires, adresse).
 * Sinon, on ouvre simplement le navigateur sur la recherche.
 */
object WebSearchController {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun search(context: Context, query: String): String {
        val apiResult = tryFetchResults(context, query)
        if (apiResult != null) return apiResult
        return openInBrowser(context, query)
    }

    /** Retourne les extraits de résultats (texte brut à reformuler par l'IA), ou null si indisponible. */
    private fun tryFetchResults(context: Context, query: String): String? {
        val keys = Prefs.getApiKeysFor(context, Provider.SERPAPI)
        if (keys.isEmpty()) return null

        for (apiKey in keys) {
            try {
                val url = "https://serpapi.com/search?q=" +
                    java.net.URLEncoder.encode(query, "UTF-8") +
                    "&api_key=$apiKey&engine=google&hl=fr&gl=fr&num=5"
                val request = Request.Builder().url(url).get().build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 429 || response.code == 401) {
                            Prefs.markKeyFailed(context, Provider.SERPAPI, apiKey)
                        }
                        return@use
                    }
                    val bodyStr = response.body?.string() ?: return@use
                    val json = JSONObject(bodyStr)

                    // Réponse directe si Google la fournit (horaires, météo, définition...)
                    val answerBox = json.optJSONObject("answer_box")
                    if (answerBox != null) {
                        val direct = answerBox.optString("answer", "").ifBlank {
                            answerBox.optString("snippet", "")
                        }
                        if (direct.isNotBlank()) return direct
                    }

                    val localResults = json.optJSONArray("local_results")
                    if (localResults != null && localResults.length() > 0) {
                        val place = localResults.getJSONObject(0)
                        val sb = StringBuilder()
                        sb.append(place.optString("title", query)).append(" — ")
                        place.optJSONObject("hours")?.let { sb.append("horaires : ${it}. ") }
                        place.optString("address", "").let { if (it.isNotBlank()) sb.append("Adresse : $it. ") }
                        place.optString("type", "").let { if (it.isNotBlank()) sb.append("($it) ") }
                        return sb.toString()
                    }

                    val organic = json.optJSONArray("organic_results")
                    if (organic != null && organic.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until minOf(3, organic.length())) {
                            val item = organic.getJSONObject(i)
                            sb.append(item.optString("title")).append(" : ")
                                .append(item.optString("snippet")).append("\n")
                        }
                        return sb.toString().trim()
                    }
                }
            } catch (e: Exception) {
                // essaie la clé suivante s'il y en a une
            }
        }
        return null
    }

    private fun openInBrowser(context: Context, query: String): String {
        return try {
            val searchUri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
            val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "🔍 Recherche lancée pour « $query » (aucune clé SerpAPI configurée pour une réponse directe — " +
                "ajoute-en une dans ⚙ Paramètres → Clés API pour que je puisse te répondre directement la prochaine fois)."
        } catch (e: Exception) {
            "❌ Échec de la recherche : ${e.message}"
        }
    }
}
