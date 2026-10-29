package fr.dd.jarvis.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Groq (API compatible OpenAI) : le « cerveau » principal, avec appel d'outils. */
class GroqClient(private val ring: KeyRing, private val model: () -> String) {

    suspend fun chat(messages: JSONArray, tools: JSONArray?): JSONObject =
        ring.run { key -> withContext(Dispatchers.IO) { call(key, messages, tools, 1024) } }

    suspend fun plain(system: String, user: String): String {
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return chat(msgs, null).optString("content", "").trim()
    }

    /** Test d'une clé précise (bouton « Tester »). */
    suspend fun ping(key: String) = withContext(Dispatchers.IO) {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
        call(key, msgs, null, 1)
    }

    private fun call(key: String, messages: JSONArray, tools: JSONArray?, maxTokens: Int): JSONObject {
        val body = JSONObject()
            .put("model", model())
            .put("messages", messages)
            .put("temperature", 0.4)
            .put("max_tokens", if (maxTokens == 1) 1 else 2048)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        return Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, "Groq ${resp.code} : ${text.take(300)}")
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        }
    }
}

/** Gemini (REST) : les agents spécialisés (planning, mails, contacts). */
class GeminiClient(private val ring: KeyRing, private val model: () -> String) {

    suspend fun generate(system: String, user: String): String =
        ring.run { key -> withContext(Dispatchers.IO) { call(key, system, user, 4096) } }

    suspend fun ping(key: String) = withContext(Dispatchers.IO) { call(key, "", "ping", 8) }

    private fun call(key: String, system: String, user: String, maxTokens: Int): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", user)))
            ))
            .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", maxTokens))
        if (system.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${model()}:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        return Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, "Gemini ${resp.code} : ${text.take(300)}")
            val cands = JSONObject(text).optJSONArray("candidates")
            val parts = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    sb.append(parts.getJSONObject(i).optString("text", ""))
                }
            }
            sb.toString().trim()
        }
    }
}
