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
        return ring.run { key -> withContext(Dispatchers.IO) { call(key, msgs, null, 1024, Http.fast) } }.optString("content", "").trim()
    }

    /** Test d'une clé précise (bouton « Tester »). */
    suspend fun ping(key: String) = withContext(Dispatchers.IO) {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
        call(key, msgs, null, 1)
    }

    /** Modèles de chat disponibles pour cette clé. */
    suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.groq.com/openai/v1/models").header("Authorization", "Bearer $key").build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, "Groq ${resp.code} : ${text.take(200)}")
            val arr = JSONObject(text).getJSONArray("data")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
                .filter { id -> listOf("whisper", "orpheus", "guard", "safeguard", "tts", "embed").none { id.contains(it, true) } }
                .sorted()
        }
    }

    private fun call(key: String, messages: JSONArray, tools: JSONArray?, maxTokens: Int, client: okhttp3.OkHttpClient = Http.client): JSONObject {
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
        return client.newCall(req).execute().use { resp ->
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

    /** Modèles « generateContent » disponibles pour cette clé. */
    suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
            .header("x-goog-api-key", key).build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, "Gemini ${resp.code} : ${text.take(200)}")
            val arr = JSONObject(text).getJSONArray("models")
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { m -> m.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true }
                .map { it.getString("name").removePrefix("models/") }
                .filter { id -> listOf("image", "tts", "live", "audio", "embed", "robotics", "omni", "veo", "imagen").none { id.contains(it, true) } }
                .sorted()
        }
    }

    private fun call(key: String, system: String, user: String, maxTokens: Int, thinkingOff: Boolean = true): String {
        val gen = JSONObject().put("temperature", 0.3).put("maxOutputTokens", if (maxTokens > 100) 8192 else maxTokens)
        if (thinkingOff) {
            val m = model()
            when {
                m.contains("2.5") -> gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                m.contains("gemini-3") -> gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            }
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", user)))
            ))
            .put("generationConfig", gen)
        if (system.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${model()}:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        val (code, text) = Http.fast.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        // Certains modèles refusent le réglage de réflexion : on réessaie sans.
        if (code == 400 && thinkingOff && text.contains("think", ignoreCase = true)) return call(key, system, user, maxTokens, false)
        if (code !in 200..299) throw ApiException(code, "Gemini $code : ${text.take(300)}")
        val cands = JSONObject(text).optJSONArray("candidates")
        val parts = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
        }
        return sb.toString().trim()
    }
}
