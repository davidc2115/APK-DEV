package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client IA générique : route la conversation vers le bon fournisseur
 * (Claude, Gemini, OpenAI-compatible pour ChatGPT/Mistral/Groq/Ollama/DeepSeek/
 * Perplexity/Together/OpenRouter, SerpAPI pour la recherche web,
 * ou modèle local embarqué TASK/GGUF/ONNX).
 *
 * En mode Automatique, essaie chaque fournisseur configuré (avec une clé
 * enregistrée) jusqu'à obtenir une réponse valide.
 */
object ApiClient {

    private const val SYSTEM_PROMPT =
        "Tu es JARVIS, un assistant IA vocal inspiré d'Iron Man : concis, précis, " +
            "légèrement formel et efficace. Réponds toujours en français sauf si on te parle dans une autre langue."

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun sendChat(context: Context, history: List<HistoryEntry>): String =
        withContext(Dispatchers.IO) {
            val provider = Prefs.getProvider(context)

            try {
                when {
                    provider.isAuto -> sendAuto(context, history)
                    provider.isLocal -> sendLocal(context, history)
                    provider == Provider.CLAUDE ->
                        sendClaude(
                            Prefs.getBaseUrl(context),
                            Prefs.getModel(context),
                            Prefs.getApiKeyFor(context, Provider.CLAUDE).ifBlank { Prefs.getApiKey(context) },
                            history
                        )
                    provider == Provider.GEMINI ->
                        sendGemini(
                            Prefs.getBaseUrl(context),
                            Prefs.getApiKeyFor(context, Provider.GEMINI).ifBlank { Prefs.getApiKey(context) },
                            history
                        )
                    provider == Provider.SERPAPI ->
                        sendSerpApi(
                            Prefs.getApiKeyFor(context, Provider.SERPAPI),
                            history
                        )
                    else ->
                        sendOpenAiCompatible(
                            Prefs.getBaseUrl(context),
                            Prefs.getModel(context),
                            Prefs.getApiKeyFor(context, provider).ifBlank { Prefs.getApiKey(context) },
                            history,
                            provider
                        )
                }
            } catch (e: Exception) {
                "Connexion impossible. Vérifiez les paramètres dans ⚙. Détail : ${e.message}"
            }
        }

    // ─── Mode Automatique : essaie chaque fournisseur configuré ───────────────

    private fun sendAuto(context: Context, history: List<HistoryEntry>): String {
        val candidates = Provider.AUTO_FALLBACK_ORDER.filter {
            Prefs.getApiKeyFor(context, it).isNotBlank()
        }

        if (candidates.isEmpty()) {
            return "Aucune IA configurée pour le mode Automatique. " +
                "Ouvre ⚙ Paramètres → onglet « Clés API » et ajoute au moins une clé " +
                "(Groq, Claude, ChatGPT, Gemini, Mistral, DeepSeek, Perplexity, Together ou OpenRouter)."
        }

        var lastError = ""
        for (provider in candidates) {
            val key = Prefs.getApiKeyFor(context, provider)
            val result = try {
                when (provider) {
                    Provider.CLAUDE -> sendClaude(
                        provider.defaultBaseUrl, provider.defaultModel, key, history
                    )
                    Provider.GEMINI -> sendGemini(
                        provider.defaultBaseUrl, key, history
                    )
                    else -> sendOpenAiCompatible(
                        provider.defaultBaseUrl, provider.defaultModel, key, history, provider
                    )
                }
            } catch (e: Exception) {
                "Erreur : ${e.message}"
            }

            if (!result.startsWith("Erreur") &&
                !result.startsWith("Connexion impossible") &&
                !result.startsWith("Format de réponse inattendu") &&
                !result.startsWith("Clé API")
            ) {
                return result
            }
            lastError = "[${provider.displayName}] $result"
        }

        return "Toutes les IA configurées ont échoué. Dernière erreur : $lastError"
    }

    // ─── Modèle local sur l'appareil (TASK / GGUF / ONNX) ────────────────────

    private suspend fun sendLocal(context: Context, history: List<HistoryEntry>): String {
        val modelPath = Prefs.getLocalModelPath(context)
        if (modelPath.isBlank()) {
            return "Aucun modèle local configuré. Ouvre ⚙ Paramètres → onglet « Local » " +
                "et choisis ou télécharge un modèle."
        }
        val prompt = buildPromptFromHistory(history)
        return LocalLlmManager.generate(context, modelPath, prompt)
    }

    private fun buildPromptFromHistory(history: List<HistoryEntry>): String {
        val recent = history.takeLast(8)
        val sb = StringBuilder(SYSTEM_PROMPT).append("\n\n")
        for (entry in recent) {
            val label = if (entry.role == "user") "Utilisateur" else "JARVIS"
            val suffix = if (entry.imageBase64 != null) " [photo jointe non prise en charge en local]" else ""
            sb.append(label).append(": ").append(entry.text).append(suffix).append("\n")
        }
        sb.append("JARVIS: ")
        return sb.toString()
    }

    // ─── OpenAI-compatible : ChatGPT, Groq, Mistral, Ollama, DeepSeek, etc. ──

    private fun sendOpenAiCompatible(
        baseUrl: String,
        model: String,
        apiKey: String,
        history: List<HistoryEntry>,
        provider: Provider = Provider.CUSTOM
    ): String {
        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (entry in history) {
            if (entry.imageBase64 != null) {
                val contentArray = JSONArray()
                contentArray.put(JSONObject().put("type", "text").put("text", entry.text))
                contentArray.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put(
                            "url",
                            "data:${entry.imageMime ?: "image/jpeg"};base64,${entry.imageBase64}"
                        )
                    )
                )
                messagesArray.put(JSONObject().put("role", entry.role).put("content", contentArray))
            } else {
                messagesArray.put(JSONObject().put("role", entry.role).put("content", entry.text))
            }
        }

        val bodyObj = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("temperature", 0.7)

        val requestBuilder = Request.Builder()
            .url(baseUrl)
            .post(bodyObj.toString().toRequestBody(JSON))
            .addHeader("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        // OpenRouter requiert des headers supplémentaires
        if (provider == Provider.OPENROUTER) {
            requestBuilder
                .addHeader("HTTP-Referer", "https://github.com/davidc2115/APK-DEV")
                .addHeader("X-Title", "JARVIS Android")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return "Erreur API (${response.code}) : $bodyStr"
            val json = JSONObject(bodyStr)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                return message?.optString("content") ?: "Réponse vide reçue du serveur."
            }
            return "Format de réponse inattendu : $bodyStr"
        }
    }

    // ─── Claude (Anthropic) ───────────────────────────────────────────────────

    private fun sendClaude(
        baseUrl: String,
        model: String,
        apiKey: String,
        history: List<HistoryEntry>
    ): String {
        if (apiKey.isBlank()) return "Clé API Claude manquante. Ajoute-la dans ⚙ Paramètres → Clés API."

        val messagesArray = JSONArray()
        for (entry in history) {
            if (entry.imageBase64 != null) {
                val contentArray = JSONArray()
                contentArray.put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", entry.imageMime ?: "image/jpeg")
                            .put("data", entry.imageBase64)
                    )
                )
                contentArray.put(JSONObject().put("type", "text").put("text", entry.text))
                messagesArray.put(JSONObject().put("role", entry.role).put("content", contentArray))
            } else {
                messagesArray.put(JSONObject().put("role", entry.role).put("content", entry.text))
            }
        }

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", SYSTEM_PROMPT)
            .put("messages", messagesArray)
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url(baseUrl)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return "Erreur API Claude (${response.code}) : $bodyStr"
            val json = JSONObject(bodyStr)
            val content = json.optJSONArray("content")
            if (content != null && content.length() > 0) {
                return content.getJSONObject(0).optString("text", "Réponse vide.")
            }
            return "Format de réponse inattendu : $bodyStr"
        }
    }

    // ─── Google Gemini ────────────────────────────────────────────────────────

    private fun sendGemini(
        baseUrl: String,
        apiKey: String,
        history: List<HistoryEntry>
    ): String {
        if (apiKey.isBlank()) return "Clé API Gemini manquante. Ajoute-la dans ⚙ Paramètres → Clés API."

        val separator = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${separator}key=$apiKey"

        val contentsArray = JSONArray()
        for (entry in history) {
            val geminiRole = if (entry.role == "assistant") "model" else "user"
            val partsArray = JSONArray()
            partsArray.put(JSONObject().put("text", entry.text))
            if (entry.imageBase64 != null) {
                partsArray.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", entry.imageMime ?: "image/jpeg")
                            .put("data", entry.imageBase64)
                    )
                )
            }
            contentsArray.put(JSONObject().put("role", geminiRole).put("parts", partsArray))
        }

        val body = JSONObject()
            .put("contents", contentsArray)
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            )
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return "Erreur API Gemini (${response.code}) : $bodyStr"
            val json = JSONObject(bodyStr)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    return parts.getJSONObject(0).optString("text", "Réponse vide.")
                }
            }
            return "Format de réponse inattendu : $bodyStr"
        }
    }

    // ─── SerpAPI (Recherche Web) ──────────────────────────────────────────────

    private fun sendSerpApi(apiKey: String, history: List<HistoryEntry>): String {
        if (apiKey.isBlank()) return "Clé API SerpAPI manquante. Ajoute-la dans ⚙ Paramètres → Clés API."

        // On extrait la dernière question de l'utilisateur comme query de recherche
        val query = history.lastOrNull { it.role == "user" }?.text
            ?: return "Aucune question à rechercher."

        val url = "https://serpapi.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&api_key=$apiKey&engine=google&hl=fr&gl=fr&num=5"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return "Erreur SerpAPI (${response.code}) : $bodyStr"

            val json = JSONObject(bodyStr)

            // Réponse directe (answer box)
            val answerBox = json.optJSONObject("answer_box")
            if (answerBox != null) {
                val answer = answerBox.optString("answer", "")
                    .ifBlank { answerBox.optString("snippet", "") }
                if (answer.isNotBlank()) return "🔍 Réponse directe : $answer"
            }

            // Top résultats organiques
            val organic = json.optJSONArray("organic_results")
            if (organic != null && organic.length() > 0) {
                val sb = StringBuilder("🔍 Résultats web pour « $query » :\n\n")
                val count = minOf(3, organic.length())
                for (i in 0 until count) {
                    val item = organic.getJSONObject(i)
                    val title = item.optString("title", "Sans titre")
                    val snippet = item.optString("snippet", "")
                    val link = item.optString("link", "")
                    sb.append("${i + 1}. **$title**\n$snippet\n🔗 $link\n\n")
                }
                return sb.toString().trimEnd()
            }

            return "Aucun résultat trouvé pour « $query »."
        }
    }
}
