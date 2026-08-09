package com.jarvis.assistant

import android.content.Context

object Prefs {
    private const val PREFS_NAME = "jarvis_prefs"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_LOCAL_MODEL_PATH = "local_model_path"

    fun getProvider(context: Context): Provider =
        Provider.fromName(prefs(context).getString(KEY_PROVIDER, Provider.GROQ.name) ?: Provider.GROQ.name)

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, Provider.GROQ.defaultBaseUrl) ?: Provider.GROQ.defaultBaseUrl

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, Provider.GROQ.defaultModel) ?: Provider.GROQ.defaultModel

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun getLocalModelPath(context: Context): String =
        prefs(context).getString(KEY_LOCAL_MODEL_PATH, "") ?: ""

    fun save(context: Context, provider: Provider, baseUrl: String, model: String, apiKey: String) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_BASE_URL, baseUrl.ifBlank { provider.defaultBaseUrl })
            .putString(KEY_MODEL, model.ifBlank { provider.defaultModel })
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    fun saveLocalModelPath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_PATH, path).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
