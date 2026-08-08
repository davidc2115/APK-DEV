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
 * Client générique compatible avec l'API "chat completions" au format OpenAI.
 * Fonctionne aussi bien avec :
 *  - une IA locale (Ollama, LM Studio, text-generation-webui, etc.)
 *  - un service cloud compatible (OpenAI, OpenRouter, etc.)
 */
object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Envoie l'historique de conversation et retourne la réponse texte de l'IA.
     * history: liste de paires (role, contenu) où role = "user" ou "assistant"
     */
    suspend fun sendChat(context: Context, history: List<Pair<String, String>>): String =
        withContext(Dispatchers.IO) {
            val baseUrl = Prefs.getBaseUrl(context)
            val model = Prefs.getModel(context)
            val apiKey = Prefs.getApiKey(context)

            val messagesArray = JSONArray()
            messagesArray.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", "Tu es JARVIS, un assistant IA vocal inspiré d'Iron Man : concis, précis, légèrement formel et efficace. Réponds toujours en français sauf si on te parle dans une autre langue.")
            )
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

            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@withContext "Erreur API (${response.code}) : $bodyStr"
                    }
                    val json = JSONObject(bodyStr)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val message = choices.getJSONObject(0).optJSONObject("message")
                        return@withContext message?.optString("content")
                            ?: "Réponse vide reçue du serveur."
                    }
                    return@withContext "Format de réponse inattendu : $bodyStr"
                }
            } catch (e: Exception) {
                return@withContext "Connexion impossible à l'API (${baseUrl}). Vérifiez les paramètres et le réseau. Détail : ${e.message}"
            }
        }
}
