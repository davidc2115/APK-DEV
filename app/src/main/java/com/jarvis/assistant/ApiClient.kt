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

object ApiClient {

    private const val SYSTEM_PROMPT =
        "Tu es JARVIS, un assistant IA vocal et domotique/mobile inspiré d'Iron Man. " +
            "Tu es concis, précis, réactif, légèrement formel et efficace. Réponds en français.\n\n" +
            "TU AS LE CONTRÔLE COMPLET DU SMARTPHONE DE L'UTILISATEUR. Quand l'utilisateur te demande d'effectuer une action système sur son téléphone, tu peux inclure un bloc de commande sous la forme exacte suivante dans ta réponse :\n" +
            "[JARVIS_CMD:{\"action\":\"NOM_ACTION\", ...params}]\n\n" +
            "Actions système disponibles :\n" +
            "• Call : {\"action\":\"call\", \"target\":\"Maman ou 0612345678\"}\n" +
            "• SMS : {\"action\":\"send_sms\", \"to\":\"Pierre\", \"message\":\"Coucou\"}\n" +
            "• Lire SMS : {\"action\":\"read_sms\", \"count\":5}\n" +
            "• Contacts : {\"action\":\"search_contact\", \"name\":\"Jean\"}\n" +
            "• Musique : {\"action\":\"play_music\", \"query\":\"Jazz\"}, {\"action\":\"pause_music\"}, {\"action\":\"stop_music\"}, {\"action\":\"set_volume\", \"level\":8}\n" +
            "• Agenda : {\"action\":\"today_events\"}, {\"action\":\"create_event\", \"title\":\"Rendez-vous doctor\", \"startTime\":1700000000000}\n" +
            "• Emails : {\"action\":\"read_emails\"}, {\"action\":\"send_email\", \"to\":\"contact@mail.com\", \"subject\":\"Projet\", \"body\":\"Bonjour\"}\n" +
            "• Fichiers : {\"action\":\"list_files\", \"path\":\"/sdcard/Downloads\"}, {\"action\":\"storage_info\"}\n" +
            "• GPS / Maps : {\"action\":\"get_location\"}, {\"action\":\"open_maps\", \"query\":\"Tour Eiffel\"}\n" +
            "• Notifications : {\"action\":\"get_notifications\"}\n" +
            "• Bluetooth : {\"action\":\"bluetooth_info\"}, {\"action\":\"enable_bluetooth\"}, {\"action\":\"disable_bluetooth\"}\n" +
            "• Wi-Fi : {\"action\":\"wifi_info\"}, {\"action\":\"enable_wifi\"}, {\"action\":\"disable_wifi\"}\n\n" +
            "Exemple de réponse : \"Très bien Monsieur, j'appelle Maman tout de suite. [JARVIS_CMD:{\"action\":\"call\",\"target\":\"Maman\"}]\""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun sendChat(context: Context, history: List<HistoryEntry>): String =
        withContext(Dispatchers.IO) {
            val provider = Prefs.getProvider(context)

            val rawResponse = try {
                when {
                    provider.isAuto -> sendAuto(context, history)
                    provider.isLocal -> sendLocal(context, history)
                    provider == Provider.CLAUDE ->
                        sendClaudeWithRotation(context, history)
                    provider == Provider.GEMINI ->
                        sendGeminiWithRotation(context, history)
                    provider == Provider.SERPAPI ->
                        sendSerpApiWithRotation(context, history)
                    else ->
                        sendOpenAiWithRotation(context, history, provider)
                }
            } catch (e: Exception) {
                "Connexion impossible. Vérifiez les paramètres dans ⚙. Détail : ${e.message}"
            }

            // Exécution automatique des commandes système si présentes dans la réponse
            val commandResult = JarvisCommandParser.parseAndExecute(context, rawResponse)
            val cleanText = JarvisCommandParser.cleanResponse(rawResponse)

            if (commandResult is JarvisCommandParser.CommandResult.Executed) {
                if (cleanText.isBlank()) commandResult.outputMessage
                else "$cleanText\n\n${commandResult.outputMessage}"
            } else {
                rawResponse
            }
        }

    // ─── Mode Automatique avec multi-clés ──────────────────────────────────────

    private fun sendAuto(context: Context, history: List<HistoryEntry>): String {
        val candidates = Provider.AUTO_FALLBACK_ORDER.filter {
            Prefs.getApiKeysFor(context, it).isNotEmpty()
        }

        if (candidates.isEmpty()) {
            return "Aucune IA configurée pour le mode Automatique. " +
                "Ouvre ⚙ Paramètres → onglet « Clés API » et ajoute au moins une clé."
        }

        var lastError = ""
        for (provider in candidates) {
            val result = try {
                when (provider) {
                    Provider.CLAUDE -> sendClaudeWithRotation(context, history)
                    Provider.GEMINI -> sendGeminiWithRotation(context, history)
                    else -> sendOpenAiWithRotation(context, history, provider)
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

    // ─── Modèle local sur l'appareil ──────────────────────────────────────────

    private suspend fun sendLocal(context: Context, history: List<HistoryEntry>): String {
        val modelPath = Prefs.getLocalModelPath(context)
        if (modelPath.isBlank()) {
            return "Aucun modèle local configuré. Ouvre ⚙ Paramètres → onglet « Local » et télécharge un modèle."
        }
        val prompt = buildPromptFromHistory(history)
        return LocalLlmManager.generate(context, modelPath, prompt)
    }

    private fun buildPromptFromHistory(history: List<HistoryEntry>): String {
        val recent = history.takeLast(8)
        val sb = StringBuilder(SYSTEM_PROMPT).append("\n\n")
        for (entry in recent) {
            val label = if (entry.role == "user") "Utilisateur" else "JARVIS"
            val suffix = if (entry.imageBase64 != null) " [photo jointe]" else ""
            sb.append(label).append(": ").append(entry.text).append(suffix).append("\n")
        }
        sb.append("JARVIS: ")
        return sb.toString()
    }

    // ─── OpenAI-compatible avec rotation de clés ──────────────────────────────

    private fun sendOpenAiWithRotation(
        context: Context,
        history: List<HistoryEntry>,
        provider: Provider
    ): String {
        val keys = Prefs.getApiKeysFor(context, provider)
        val baseUrl = if (!provider.isAuto && !provider.isLocal && provider != Provider.CUSTOM) provider.defaultBaseUrl else Prefs.getBaseUrl(context)
        val model = if (!provider.isAuto && !provider.isLocal && provider != Provider.CUSTOM) provider.defaultModel else Prefs.getModel(context)

        if (keys.isEmpty() && provider.needsApiKey) {
            return "Aucune clé API configurée pour ${provider.displayName}. Ajoute-en dans ⚙ Paramètres → Clés API."
        }

        val maxAttempts = maxOf(1, keys.size)
        var lastErr = ""

        for (attempt in 0 until maxAttempts) {
            val apiKey = if (keys.isNotEmpty()) Prefs.getNextApiKey(context, provider) else ""
            val result = sendOpenAiCompatible(baseUrl, model, apiKey, history, provider)

            if (!result.startsWith("Erreur API (429)") && !result.startsWith("Erreur API (401)")) {
                return result
            }

            if (apiKey.isNotBlank()) Prefs.markKeyFailed(context, provider, apiKey)
            lastErr = result
        }

        return lastErr
    }

    private fun sendOpenAiCompatible(
        baseUrl: String,
        model: String,
        apiKey: String,
        history: List<HistoryEntry>,
        provider: Provider
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
                        JSONObject().put("url", "data:${entry.imageMime ?: "image/jpeg"};base64,${entry.imageBase64}")
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

    // ─── Claude (Anthropic) avec rotation ──────────────────────────────────────

    private fun sendClaudeWithRotation(context: Context, history: List<HistoryEntry>): String {
        val keys = Prefs.getApiKeysFor(context, Provider.CLAUDE)
        if (keys.isEmpty()) return "Aucune clé API Claude configurée."

        for (apiKey in keys) {
            val res = sendClaude(Provider.CLAUDE.defaultBaseUrl, Provider.CLAUDE.defaultModel, apiKey, history)
            if (!res.startsWith("Erreur API Claude (429)") && !res.startsWith("Erreur API Claude (401)")) return res
            Prefs.markKeyFailed(context, Provider.CLAUDE, apiKey)
        }
        return "Toutes les clés API Claude ont échoué."
    }

    private fun sendClaude(baseUrl: String, model: String, apiKey: String, history: List<HistoryEntry>): String {
        val messagesArray = JSONArray()
        for (entry in history) {
            messagesArray.put(JSONObject().put("role", entry.role).put("content", entry.text))
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

    // ─── Google Gemini avec rotation ──────────────────────────────────────────

    private fun sendGeminiWithRotation(context: Context, history: List<HistoryEntry>): String {
        val keys = Prefs.getApiKeysFor(context, Provider.GEMINI)
        if (keys.isEmpty()) return "Aucune clé API Gemini configurée."

        for (apiKey in keys) {
            val res = sendGemini(Provider.GEMINI.defaultBaseUrl, apiKey, history)
            if (!res.startsWith("Erreur API Gemini (429)") && !res.startsWith("Erreur API Gemini (401)")) return res
            Prefs.markKeyFailed(context, Provider.GEMINI, apiKey)
        }
        return "Toutes les clés API Gemini ont échoué."
    }

    private fun sendGemini(baseUrl: String, apiKey: String, history: List<HistoryEntry>): String {
        val separator = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${separator}key=$apiKey"

        val contentsArray = JSONArray()
        for (entry in history) {
            val geminiRole = if (entry.role == "assistant") "model" else "user"
            val partsArray = JSONArray().put(JSONObject().put("text", entry.text))
            contentsArray.put(JSONObject().put("role", geminiRole).put("parts", partsArray))
        }

        val body = JSONObject()
            .put("contents", contentsArray)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder().url(url).post(body).addHeader("Content-Type", "application/json").build()

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

    // ─── SerpAPI avec rotation ────────────────────────────────────────────────

    private fun sendSerpApiWithRotation(context: Context, history: List<HistoryEntry>): String {
        val keys = Prefs.getApiKeysFor(context, Provider.SERPAPI)
        if (keys.isEmpty()) return "Aucune clé API SerpAPI configurée."

        val query = history.lastOrNull { it.role == "user" }?.text ?: return "Aucune question à rechercher."

        for (apiKey in keys) {
            val url = "https://serpapi.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&api_key=$apiKey&engine=google&hl=fr&gl=fr&num=5"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(bodyStr)
                    val organic = json.optJSONArray("organic_results")
                    if (organic != null && organic.length() > 0) {
                        val sb = StringBuilder("🔍 Résultats web pour « $query » :\n\n")
                        for (i in 0 until minOf(3, organic.length())) {
                            val item = organic.getJSONObject(i)
                            sb.append("${i + 1}. **${item.optString("title")}**\n${item.optString("snippet")}\n🔗 ${item.optString("link")}\n\n")
                        }
                        return sb.toString().trimEnd()
                    }
                } else if (response.code == 429 || response.code == 401) {
                    Prefs.markKeyFailed(context, Provider.SERPAPI, apiKey)
                }
            }
        }
        return "Toutes les clés SerpAPI ont échoué."
    }
}
