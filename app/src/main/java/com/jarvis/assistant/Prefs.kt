package com.jarvis.assistant

import android.content.Context

/**
 * Gestion des préférences persistantes de l'application JARVIS.
 * Chaque fournisseur IA dispose désormais de sa propre clé API stockée
 * sous la clé "api_key_<NOM_PROVIDER>", ce qui permet au mode Automatique
 * de sélectionner indépendamment la clé de chaque fournisseur configuré.
 */
object Prefs {
    private const val PREFS_NAME = "jarvis_prefs"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    // Clé "globale" conservée pour rétrocompatibilité (lecture seule depuis v2)
    private const val KEY_API_KEY = "api_key"
    private const val KEY_LOCAL_MODEL_PATH = "local_model_path"
    private const val KEY_LOCAL_MODEL_FORMAT = "local_model_format"
    private const val KEY_ACCENT_COLOR = "accent_color"
    private const val KEY_HF_TOKEN = "hf_token"
    private const val KEY_ORB_STYLE = "orb_style"

    const val DEFAULT_ACCENT_COLOR = -16724737 // #FF00E5FF (cyan)

    // ─── Provider actif ────────────────────────────────────────────────────────

    fun getProvider(context: Context): Provider =
        Provider.fromName(
            prefs(context).getString(KEY_PROVIDER, Provider.GROQ.name) ?: Provider.GROQ.name
        )

    // ─── URL de base & modèle (pour le provider sélectionné) ──────────────────

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, Provider.GROQ.defaultBaseUrl)
            ?: Provider.GROQ.defaultBaseUrl

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, Provider.GROQ.defaultModel)
            ?: Provider.GROQ.defaultModel

    // ─── Clé API globale (rétrocompatibilité) ─────────────────────────────────

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    // ─── Clé API individuelle par fournisseur (mode Multi-clés & Automatique) ─

    /**
     * Retourne la clé API mémorisée pour ce fournisseur précis.
     * Si aucune clé individuelle n'existe, tente de lire l'ancienne clé globale
     * uniquement pour le provider actuellement sélectionné (migration douce).
     */
    fun getApiKeyFor(context: Context, provider: Provider): String {
        val specific = prefs(context).getString("api_key_${provider.name}", "") ?: ""
        if (specific.isNotBlank()) return specific
        // Migration: si c'est le provider actif, on regarde l'ancienne clé globale
        if (provider == getProvider(context)) {
            return prefs(context).getString(KEY_API_KEY, "") ?: ""
        }
        return ""
    }

    fun saveApiKeyFor(context: Context, provider: Provider, key: String) {
        prefs(context).edit().putString("api_key_${provider.name}", key).apply()
    }

    /** Sauvegarde les clés pour plusieurs providers d'un coup (écran multi-clés). */
    fun saveApiKeys(context: Context, keys: Map<Provider, String>) {
        val editor = prefs(context).edit()
        for ((provider, key) in keys) {
            editor.putString("api_key_${provider.name}", key)
        }
        editor.apply()
    }

    // ─── Modèle local ─────────────────────────────────────────────────────────

    fun getLocalModelPath(context: Context): String =
        prefs(context).getString(KEY_LOCAL_MODEL_PATH, "") ?: ""

    fun saveLocalModelPath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_PATH, path).apply()
    }

    /** Format du modèle local : "TASK" | "GGUF" | "ONNX" */
    fun getLocalModelFormat(context: Context): String =
        prefs(context).getString(KEY_LOCAL_MODEL_FORMAT, "TASK") ?: "TASK"

    fun saveLocalModelFormat(context: Context, format: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_FORMAT, format).apply()
    }

    // ─── UI / Personnalisation ─────────────────────────────────────────────────

    fun getAccentColor(context: Context): Int =
        prefs(context).getInt(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR)

    fun saveAccentColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_ACCENT_COLOR, color).apply()
    }

    fun getHfToken(context: Context): String =
        prefs(context).getString(KEY_HF_TOKEN, "") ?: ""

    fun saveHfToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_HF_TOKEN, token).apply()
    }

    fun getOrbStyle(context: Context): String =
        prefs(context).getString(KEY_ORB_STYLE, "PULSE") ?: "PULSE"

    fun saveOrbStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_ORB_STYLE, style).apply()
    }

    // ─── Sauvegarde groupée (provider actif + config) ─────────────────────────

    fun save(
        context: Context,
        provider: Provider,
        baseUrl: String,
        model: String,
        apiKey: String
    ) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_BASE_URL, baseUrl.ifBlank { provider.defaultBaseUrl })
            .putString(KEY_MODEL, model.ifBlank { provider.defaultModel })
            .putString(KEY_API_KEY, apiKey) // conservé pour rétrocompatibilité
            .apply()
        // La clé est aussi mémorisée par fournisseur (mode Automatique & multi-clés).
        if (!provider.isLocal && !provider.isAuto && apiKey.isNotBlank()) {
            saveApiKeyFor(context, provider, apiKey)
        }
    }

    // ─── Interne ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
