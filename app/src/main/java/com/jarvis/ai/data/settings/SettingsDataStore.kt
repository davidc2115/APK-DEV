package com.jarvis.ai.data.settings

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.ai.core.ai.TaskKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tout ce qui rend l'app "entièrement paramétrable" passe par ici : clés API, endpoints
 * (Home Assistant, Freebox, Ollama), URI du vault Obsidian, préférences de fournisseur IA
 * par type de tâche. Stockage via EncryptedSharedPreferences (Jetpack Security / Tink) :
 * aucune clé en clair sur le disque.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "jarvis_secure_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // --- Clés API génériques (claude, openai, gemini, groq, perplexity, serpapi, github, freebox_app_token...) ---
    fun getApiKey(provider: String): String = prefs.getString("apikey_$provider", "").orEmpty()
    fun setApiKey(provider: String, value: String) = prefs.edit().putString("apikey_$provider", value).apply()

    fun getModelOverride(provider: String): String = prefs.getString("model_$provider", "").orEmpty()
    fun setModelOverride(provider: String, value: String) = prefs.edit().putString("model_$provider", value).apply()

    // --- Fournisseur préféré par type de tâche ---
    fun preferredProviderFor(task: TaskKind): String =
        prefs.getString("preferred_${task.name}", defaultProviderFor(task)).orEmpty()

    fun setPreferredProviderFor(task: TaskKind, providerName: String) =
        prefs.edit().putString("preferred_${task.name}", providerName).apply()

    private fun defaultProviderFor(task: TaskKind): String = when (task) {
        TaskKind.CHAT -> "claude"
        TaskKind.CODE -> "claude"
        TaskKind.WEB_SEARCH -> "perplexity"
        TaskKind.IMAGE -> "openai"
    }

    // --- Vault Obsidian ---
    fun getVaultUri(): Uri? = prefs.getString("obsidian_vault_uri", null)?.let(Uri::parse)
    fun setVaultUri(uri: Uri) = prefs.edit().putString("obsidian_vault_uri", uri.toString()).apply()

    // --- Home Assistant ---
    fun getHomeAssistantUrl(): String = prefs.getString("ha_url", "").orEmpty()
    fun setHomeAssistantUrl(url: String) = prefs.edit().putString("ha_url", url).apply()
    fun getHomeAssistantToken(): String = prefs.getString("ha_token", "").orEmpty()
    fun setHomeAssistantToken(token: String) = prefs.edit().putString("ha_token", token).apply()

    // --- Freebox ---
    fun getFreeboxUrl(): String = prefs.getString("freebox_url", "").orEmpty()
    fun setFreeboxUrl(url: String) = prefs.edit().putString("freebox_url", url).apply()
    fun setFreeboxTrackId(id: String) = prefs.edit().putString("freebox_track_id", id).apply()

    // --- Ollama (serveur local PC/NAS) ---
    fun getOllamaBaseUrl(): String = prefs.getString("ollama_base_url", "").orEmpty()
    fun setOllamaBaseUrl(url: String) = prefs.edit().putString("ollama_base_url", url).apply()

    // --- Voix ---
    fun useLocalWhisper(): Boolean = prefs.getBoolean("use_local_whisper", true)
    fun setUseLocalWhisper(value: Boolean) = prefs.edit().putBoolean("use_local_whisper", value).apply()

    fun wakeWordEngine(): String = prefs.getString("wake_word_engine", "openwakeword").orEmpty()
    fun setWakeWordEngine(engine: String) = prefs.edit().putString("wake_word_engine", engine).apply()
}
