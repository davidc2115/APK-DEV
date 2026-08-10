package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gestion des préférences JARVIS v3.
 *
 * Nouveautés v3 :
 *  - Multi-clés par provider (JSON array) avec rotation round-robin
 *  - Blacklist temporaire des clés en erreur (429/401)
 *  - Stratégie de rotation configurable par provider
 *  - Comptes email IMAP/SMTP multiples
 *  - Paramètres de contrôle téléphone (permissions accordées)
 */
object Prefs {
    private const val PREFS_NAME = "jarvis_prefs"

    // ─── Clés de stockage ─────────────────────────────────────────────────────
    private const val KEY_PROVIDER          = "provider"
    private const val KEY_BASE_URL          = "base_url"
    private const val KEY_MODEL             = "model"
    private const val KEY_API_KEY           = "api_key"            // rétrocompat
    private const val KEY_LOCAL_MODEL_PATH  = "local_model_path"
    private const val KEY_LOCAL_MODEL_FORMAT= "local_model_format"
    private const val KEY_ACCENT_COLOR      = "accent_color"
    private const val KEY_HF_TOKEN          = "hf_token"
    private const val KEY_ORB_STYLE         = "orb_style"
    private const val KEY_EMAIL_ACCOUNTS    = "email_accounts"     // JSON array
    private const val KEY_ROTATION_STRATEGY = "rotation_strategy"  // "ROUNDROBIN"|"FALLBACK"|"RANDOM"

    const val DEFAULT_ACCENT_COLOR = -16724737 // #FF00E5FF (cyan)

    // ═════════════════════════════════════════════════════════════════════════
    // MULTI-CLÉS API PAR PROVIDER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Retourne toutes les clés configurées pour ce provider.
     * Migration douce : si aucune liste n'existe, cherche l'ancienne clé unique.
     */
    fun getApiKeysFor(context: Context, provider: Provider): List<String> {
        val json = prefs(context).getString("api_keys_${provider.name}", null)
        if (json != null) {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
            } catch (_: Exception) { emptyList() }
        }
        // Migration : ancienne clé unique
        val legacy = prefs(context).getString("api_key_${provider.name}", "")
            ?: prefs(context).getString(KEY_API_KEY, "") ?: ""
        return if (legacy.isNotBlank()) listOf(legacy) else emptyList()
    }

    fun saveApiKeysFor(context: Context, provider: Provider, keys: List<String>) {
        val arr = JSONArray()
        keys.filter { it.isNotBlank() }.forEach { arr.put(it) }
        prefs(context).edit()
            .putString("api_keys_${provider.name}", arr.toString())
            .remove("api_key_${provider.name}") // nettoie l'ancien format
            .apply()
    }

    fun addApiKeyFor(context: Context, provider: Provider, key: String) {
        val current = getApiKeysFor(context, provider).toMutableList()
        if (key.isNotBlank() && !current.contains(key)) {
            current.add(key)
            saveApiKeysFor(context, provider, current)
        }
    }

    fun removeApiKeyFor(context: Context, provider: Provider, key: String) {
        val current = getApiKeysFor(context, provider).toMutableList()
        current.remove(key)
        saveApiKeysFor(context, provider, current)
        // Réinitialise l'index si nécessaire
        val idx = getKeyIndex(context, provider)
        if (idx >= current.size) saveKeyIndex(context, provider, 0)
    }

    /** Rétrocompat : retourne la 1ère clé configurée ou "". */
    fun getApiKeyFor(context: Context, provider: Provider): String =
        getApiKeysFor(context, provider).firstOrNull() ?: ""

    /** Sauvegarde un Map<Provider, String> en découpant les clés multiples par virgule ou retour à la ligne. */
    fun saveApiKeys(context: Context, keys: Map<Provider, String>) {
        for ((provider, keyStr) in keys) {
            val keysList = keyStr.split(",", "\n", ";").map { it.trim() }.filter { it.isNotBlank() }
            saveApiKeysFor(context, provider, keysList)
        }
    }

    /** Sauvegarde un Map<Provider, List<String>> en batch (écran Clés API). */
    fun saveAllApiKeys(context: Context, keysMap: Map<Provider, List<String>>) {
        val editor = prefs(context).edit()
        for ((provider, keys) in keysMap) {
            val arr = JSONArray()
            keys.filter { it.isNotBlank() }.forEach { arr.put(it) }
            editor.putString("api_keys_${provider.name}", arr.toString())
        }
        editor.apply()
    }

    // ─── Rotation round-robin ─────────────────────────────────────────────────

    /** Retourne la prochaine clé valide selon la stratégie configurée. */
    fun getNextApiKey(context: Context, provider: Provider): String {
        val keys = getApiKeysFor(context, provider)
        if (keys.isEmpty()) return ""
        val validKeys = keys.filter { !isKeyBlacklisted(context, provider, it) }
        if (validKeys.isEmpty()) {
            clearBlacklist(context, provider) // toutes blacklistées → reset
            return keys.first()
        }
        return when (getRotationStrategy(context)) {
            RotationStrategy.RANDOM -> validKeys.random()
            RotationStrategy.ROUNDROBIN -> {
                val idx = getKeyIndex(context, provider) % validKeys.size
                saveKeyIndex(context, provider, (idx + 1) % validKeys.size)
                validKeys[idx]
            }
            RotationStrategy.FALLBACK -> validKeys.first() // premier valide
        }
    }

    /** Signale une clé comme défaillante (blacklist temporaire 1h). */
    fun markKeyFailed(context: Context, provider: Provider, key: String) {
        val mapJson = prefs(context).getString("api_keys_failed_${provider.name}", "{}") ?: "{}"
        val map = try { JSONObject(mapJson) } catch (_: Exception) { JSONObject() }
        map.put(key, System.currentTimeMillis())
        prefs(context).edit().putString("api_keys_failed_${provider.name}", map.toString()).apply()
    }

    private fun isKeyBlacklisted(context: Context, provider: Provider, key: String): Boolean {
        val mapJson = prefs(context).getString("api_keys_failed_${provider.name}", "{}") ?: "{}"
        return try {
            val map = JSONObject(mapJson)
            if (!map.has(key)) return false
            val ts = map.getLong(key)
            System.currentTimeMillis() - ts < 60 * 60 * 1000L // 1 heure
        } catch (_: Exception) { false }
    }

    private fun clearBlacklist(context: Context, provider: Provider) {
        prefs(context).edit().remove("api_keys_failed_${provider.name}").apply()
    }

    private fun getKeyIndex(context: Context, provider: Provider): Int =
        prefs(context).getInt("api_key_idx_${provider.name}", 0)

    private fun saveKeyIndex(context: Context, provider: Provider, idx: Int) {
        prefs(context).edit().putInt("api_key_idx_${provider.name}", idx).apply()
    }

    // ─── Stratégie de rotation ────────────────────────────────────────────────

    enum class RotationStrategy { ROUNDROBIN, FALLBACK, RANDOM }

    fun getRotationStrategy(context: Context): RotationStrategy =
        try {
            RotationStrategy.valueOf(
                prefs(context).getString(KEY_ROTATION_STRATEGY, "ROUNDROBIN") ?: "ROUNDROBIN"
            )
        } catch (_: Exception) { RotationStrategy.ROUNDROBIN }

    fun saveRotationStrategy(context: Context, strategy: RotationStrategy) {
        prefs(context).edit().putString(KEY_ROTATION_STRATEGY, strategy.name).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // COMPTES EMAIL IMAP / SMTP
    // ═════════════════════════════════════════════════════════════════════════

    data class EmailAccount(
        val id: String = System.currentTimeMillis().toString(),
        val label: String = "",          // ex: "Gmail perso"
        val email: String = "",          // adresse email complète
        val password: String = "",       // mot de passe ou app-password
        // IMAP (lecture)
        val imapHost: String = "",
        val imapPort: Int = 993,
        val imapSsl: Boolean = true,
        // SMTP (envoi)
        val smtpHost: String = "",
        val smtpPort: Int = 587,
        val smtpSsl: Boolean = false,
        val smtpStartTls: Boolean = true,
        val isDefault: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("label", label); put("email", email)
            put("password", password)
            put("imapHost", imapHost); put("imapPort", imapPort); put("imapSsl", imapSsl)
            put("smtpHost", smtpHost); put("smtpPort", smtpPort); put("smtpSsl", smtpSsl)
            put("smtpStartTls", smtpStartTls); put("isDefault", isDefault)
        }

        companion object {
            fun fromJson(j: JSONObject) = EmailAccount(
                id          = j.optString("id", System.currentTimeMillis().toString()),
                label       = j.optString("label", ""),
                email       = j.optString("email", ""),
                password    = j.optString("password", ""),
                imapHost    = j.optString("imapHost", ""),
                imapPort    = j.optInt("imapPort", 993),
                imapSsl     = j.optBoolean("imapSsl", true),
                smtpHost    = j.optString("smtpHost", ""),
                smtpPort    = j.optInt("smtpPort", 587),
                smtpSsl     = j.optBoolean("smtpSsl", false),
                smtpStartTls= j.optBoolean("smtpStartTls", true),
                isDefault   = j.optBoolean("isDefault", false)
            )

            /** Configs pré-remplies pour les grands services. */
            fun preset(service: String, email: String, password: String): EmailAccount? = when (service.lowercase()) {
                "gmail" -> EmailAccount(
                    label = "Gmail", email = email, password = password,
                    imapHost = "imap.gmail.com", imapPort = 993, imapSsl = true,
                    smtpHost = "smtp.gmail.com", smtpPort = 587, smtpStartTls = true
                )
                "outlook", "hotmail", "live" -> EmailAccount(
                    label = "Outlook", email = email, password = password,
                    imapHost = "outlook.office365.com", imapPort = 993, imapSsl = true,
                    smtpHost = "smtp.office365.com", smtpPort = 587, smtpStartTls = true
                )
                "yahoo" -> EmailAccount(
                    label = "Yahoo", email = email, password = password,
                    imapHost = "imap.mail.yahoo.com", imapPort = 993, imapSsl = true,
                    smtpHost = "smtp.mail.yahoo.com", smtpPort = 587, smtpStartTls = true
                )
                "icloud" -> EmailAccount(
                    label = "iCloud", email = email, password = password,
                    imapHost = "imap.mail.me.com", imapPort = 993, imapSsl = true,
                    smtpHost = "smtp.mail.me.com", smtpPort = 587, smtpStartTls = true
                )
                else -> null
            }
        }
    }

    fun getEmailAccounts(context: Context): List<EmailAccount> {
        val json = prefs(context).getString(KEY_EMAIL_ACCOUNTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { EmailAccount.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun getDefaultEmailAccount(context: Context): EmailAccount? =
        getEmailAccounts(context).firstOrNull { it.isDefault }
            ?: getEmailAccounts(context).firstOrNull()

    fun saveEmailAccounts(context: Context, accounts: List<EmailAccount>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_EMAIL_ACCOUNTS, arr.toString()).apply()
    }

    fun addEmailAccount(context: Context, account: EmailAccount) {
        val list = getEmailAccounts(context).toMutableList()
        list.removeAll { it.id == account.id }
        list.add(account)
        saveEmailAccounts(context, list)
    }

    fun removeEmailAccount(context: Context, id: String) {
        val list = getEmailAccounts(context).filter { it.id != id }
        saveEmailAccounts(context, list)
    }

    fun setDefaultEmailAccount(context: Context, id: String) {
        val list = getEmailAccounts(context).map { it.copy(isDefault = it.id == id) }
        saveEmailAccounts(context, list)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PROVIDER ACTIF, URL, MODÈLE
    // ═════════════════════════════════════════════════════════════════════════

    fun getProvider(context: Context): Provider =
        Provider.fromName(
            prefs(context).getString(KEY_PROVIDER, Provider.GROQ.name) ?: Provider.GROQ.name
        )

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, Provider.GROQ.defaultBaseUrl)
            ?: Provider.GROQ.defaultBaseUrl

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, Provider.GROQ.defaultModel)
            ?: Provider.GROQ.defaultModel

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    // ─── Modèle local ─────────────────────────────────────────────────────────

    fun getLocalModelPath(context: Context): String =
        prefs(context).getString(KEY_LOCAL_MODEL_PATH, "") ?: ""

    fun saveLocalModelPath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_PATH, path).apply()
    }

    fun getLocalModelFormat(context: Context): String =
        prefs(context).getString(KEY_LOCAL_MODEL_FORMAT, "TASK") ?: "TASK"

    fun saveLocalModelFormat(context: Context, format: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_FORMAT, format).apply()
    }

    // ─── UI / Style ───────────────────────────────────────────────────────────

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

    // ─── Sauvegarde groupée ───────────────────────────────────────────────────

    fun save(context: Context, provider: Provider, baseUrl: String, model: String, apiKey: String) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_BASE_URL, baseUrl.ifBlank { provider.defaultBaseUrl })
            .putString(KEY_MODEL, model.ifBlank { provider.defaultModel })
            .putString(KEY_API_KEY, apiKey)
            .apply()
        if (!provider.isLocal && !provider.isAuto && apiKey.isNotBlank()) {
            // Ajoute à la liste multi-clés si pas déjà présente
            addApiKeyFor(context, provider, apiKey)
        }
    }

    // ─── Interne ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
