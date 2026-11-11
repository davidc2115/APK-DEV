package fr.dd.jarvis.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** API officielle OpenAI (format Chat Completions), avec rotation et temporisation des clés. */
class OpenAiClient(private val ring: KeyRing, private val model: () -> String) {
    private val base = "https://api.openai.com/v1"

    fun disponible() = ring.count() > 0

    suspend fun chat(messages: JSONArray, tools: JSONArray?, out: Int = 1800, forceText: Boolean = false): JSONObject =
        ring.run { key -> withContext(Dispatchers.IO) { call(key, messages, tools, out, forceText) } }

    suspend fun plain(system: String, user: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return ring.run { key -> withContext(Dispatchers.IO) { call(key, messages, null, 1024, false) } }
            .optString("content").trim()
    }

    suspend fun ping(key: String) = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
        call(key, messages, null, 1, false)
    }

    suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$base/models")
            .header("Authorization", "Bearer $key").get().build()
        Http.fast.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, "OpenAI ${response.code} : ${text.take(240)}")
            val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
                .filter { id -> id.isNotBlank() && (id.startsWith("gpt-") || id.startsWith("o")) }
                .sorted()
        }
    }

    private fun call(key: String, messages: JSONArray, tools: JSONArray?, out: Int, forceText: Boolean): JSONObject {
        val mdl = model().ifBlank { "gpt-4.1-mini" }
        val body = JSONObject()
            .put("model", mdl)
            .put("messages", sansChampsGemini(messages))
            .put("max_completion_tokens", out.coerceAtLeast(1))
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            body.put("tool_choice", if (forceText) "none" else "auto")
        }
        val request = Request.Builder().url("$base/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(Http.JSON)).build()
        return Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, "OpenAI ${response.code} : ${text.take(300)}")
            val root = runCatching { JSONObject(text) }.getOrElse { throw ApiException(502, "OpenAI : réponse illisible") }
            val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw ApiException(502, "OpenAI : réponse vide")
            if (message.has("content") && !message.isNull("content")) {
                message.put("content", nettoyerRaisonnement(message.optString("content")))
            }
            message
        }
    }
}
