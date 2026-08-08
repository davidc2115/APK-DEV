package com.jarvis.assistant

import android.content.Context

object Prefs {
    private const val PREFS_NAME = "jarvis_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY = "api_key"

    // Valeurs par défaut : compatibles avec une API locale de type Ollama
    // exposant un endpoint OpenAI-compatible (/v1/chat/completions)
    private const val DEFAULT_BASE_URL = "http://10.0.2.2:11434/v1/chat/completions"
    private const val DEFAULT_MODEL = "llama3.1"

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun save(context: Context, baseUrl: String, model: String, apiKey: String) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, baseUrl.ifBlank { DEFAULT_BASE_URL })
            .putString(KEY_MODEL, model.ifBlank { DEFAULT_MODEL })
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
