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
 * (Claude, Gemini, OpenAI-compatible pour ChatGPT/Mistral/Groq/Ollama,
 * ou modèle local embarqué sur l'appareil).
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

    /**
     * history : liste de paires (role, contenu) où role = "user" ou "assistant"
     */
    suspend fun sendChat(context: Context, history: List<Pair<String, String>>): String =
        withContext(Dispatchers.IO) {
            val provider = Prefs.getProvider(context)

            try {
                when {
                    provider.isLocal -> sendLocal(context, history)
                    provider == Provider.CLAUDE -> sendClaude(context, history)
                    provider == Provider.GEMINI -> sendGemini(context, history)
                    else -> sendOpenAiCompatible(context, history)
                }
            } catch (e: Exception) {
                "Connexion impossible. Vérifiez les paramètres dans ⚙. Détail : ${e.message}"
            }
        }

    // ---------- Modèle local sur l'appareil ----------

    private suspend fun sendLocal(context: Context, history: List<Pair<String, String>>): String {
        val modelPath = Prefs.getLocalModelPath(context)
        if (modelPath.isBlank()) {
            return "Aucun modèle local configuré. Ouvre ⚙ Paramètres et choisis un fichier .task."
        }
        val prompt = buildPromptFromHistory(history)
        return LocalLlmManager.generate(context, modelPath, prompt)
    }

    private fun buildPromptFromHistory(history: List<Pair<String, String>>): String {
        val recent = history.takeLast(8)
        val sb = StringBuilder(SYSTEM_PROMPT).append("\n\n")
        for ((role, content) in recent) {
            sb.append(if (role == "user") "Utilisateur: " else "JARVIS: ").append(content).append("\n")
        }
        sb.append("JARVIS: ")
        return sb.toString()
    }

    // ---------- OpenAI-compatible : ChatGPT, Mistral, Groq, Ollama, Custom ----------

    private fun sendOpenAiCompatible(context: Context, history: List<Pair<String, String>>): String {
        val baseUrl = Prefs.getBaseUrl(context)
        val model = Prefs.getModel(context)
        val apiKey = Prefs.getApiKey(context)

        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for ((role, content) in history) {
            messagesArray.put(JSONObject().put("role", role).put("content", content))
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("temperature", 0.7)
            .toString()
            .toRequestBody(JSON)

        val requestBuilder = Request.Builder()
            .url(baseUrl)
            .post(body)
            .addHeader("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
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

    // ---------- Claude (Anthropic) ----------

    private fun sendClaude(context: Context, history: List<Pair<String, String>>): String {
        val baseUrl = Prefs.getBaseUrl(context)
        val model = Prefs.getModel(context)
        val apiKey = Prefs.getApiKey(context)

        if (apiKey.isBlank()) return "Clé API Claude manquante. Ajoute-la dans ⚙ Paramètres."

        val messagesArray = JSONArray()
        for ((role, content) in history) {
            messagesArray.put(JSONObject().put("role", role).put("content", content))
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

    // ---------- Google Gemini ----------

    private fun sendGemini(context: Context, history: List<Pair<String, String>>): String {
        val baseUrl = Prefs.getBaseUrl(context)
        val apiKey = Prefs.getApiKey(context)

        if (apiKey.isBlank()) return "Clé API Gemini manquante. Ajoute-la dans ⚙ Paramètres."

        val separator = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${separator}key=$apiKey"

        val contentsArray = JSONArray()
        for ((role, content) in history) {
            val geminiRole = if (role == "assistant") "model" else "user"
            val partsArray = JSONArray().put(JSONObject().put("text", content))
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
}
