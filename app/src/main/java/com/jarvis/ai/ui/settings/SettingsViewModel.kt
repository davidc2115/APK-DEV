package com.jarvis.ai.ui.settings

import androidx.lifecycle.ViewModel
import com.jarvis.ai.data.settings.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Expose toutes les préférences paramétrables de l'app : clés IA (Claude/OpenAI/Gemini/Groq/
 * Perplexity/SerpAPI/GitHub), endpoints (Home Assistant, Freebox, Ollama), vault Obsidian.
 * C'est l'écran qui matérialise "tout est paramétrable" : aucune valeur n'est codée en dur.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsDataStore
) : ViewModel() {

    fun getApiKey(provider: String) = settings.getApiKey(provider)
    fun setApiKey(provider: String, value: String) = settings.setApiKey(provider, value)

    fun getHomeAssistantUrl() = settings.getHomeAssistantUrl()
    fun setHomeAssistantUrl(value: String) = settings.setHomeAssistantUrl(value)
    fun getHomeAssistantToken() = settings.getHomeAssistantToken()
    fun setHomeAssistantToken(value: String) = settings.setHomeAssistantToken(value)

    fun getFreeboxUrl() = settings.getFreeboxUrl()
    fun setFreeboxUrl(value: String) = settings.setFreeboxUrl(value)

    fun getOllamaBaseUrl() = settings.getOllamaBaseUrl()
    fun setOllamaBaseUrl(value: String) = settings.setOllamaBaseUrl(value)
}
