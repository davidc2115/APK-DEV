package com.jarvis.assistant

/**
 * Liste des fournisseurs IA disponibles.
 * isLocal = true signifie : aucun réseau, modèle exécuté directement sur le téléphone.
 * isAuto = true signifie : essaie plusieurs fournisseurs configurés jusqu'à ce que l'un réponde.
 * needsApiKey = false signifie : pas de clé API requise (Ollama local, Custom sans auth…).
 */
enum class Provider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val isLocal: Boolean = false,
    val isAuto: Boolean = false,
    val needsApiKey: Boolean = true
) {
    AUTO_BEST(
        "🤖 Automatique (essaie tes IA configurées)",
        "",
        "",
        isAuto = true,
        needsApiKey = false
    ),

    // ── Fournisseurs Cloud ────────────────────────────────────────────────────
    GROQ(
        "Groq (gratuit, très rapide)",
        "https://api.groq.com/openai/v1/chat/completions",
        "llama-3.3-70b-versatile"
    ),
    OPENAI(
        "ChatGPT (OpenAI)",
        "https://api.openai.com/v1/chat/completions",
        "gpt-4o-mini"
    ),
    CLAUDE(
        "Claude (Anthropic)",
        "https://api.anthropic.com/v1/messages",
        "claude-sonnet-4-5"
    ),
    GEMINI(
        "Google Gemini",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent",
        "gemini-2.0-flash-lite"
    ),
    MISTRAL(
        "Mistral AI",
        "https://api.mistral.ai/v1/chat/completions",
        "mistral-large-latest"
    ),
    DEEPSEEK(
        "DeepSeek",
        "https://api.deepseek.com/v1/chat/completions",
        "deepseek-chat"
    ),
    PERPLEXITY(
        "Perplexity AI",
        "https://api.perplexity.ai/chat/completions",
        "sonar"
    ),
    TOGETHER(
        "Together AI",
        "https://api.together.xyz/v1/chat/completions",
        "mistralai/Mixtral-8x7B-Instruct-v0.1"
    ),
    OPENROUTER(
        "OpenRouter (multi-modèles)",
        "https://openrouter.ai/api/v1/chat/completions",
        "openai/gpt-4o-mini"
    ),
    SERPAPI(
        "SerpAPI (Recherche Web)",
        "https://serpapi.com/search",
        "",
        needsApiKey = true
    ),

    // ── IA sur réseau local (PC) ──────────────────────────────────────────────
    OLLAMA(
        "IA locale sur PC (Ollama)",
        "http://192.168.1.50:11434/v1/chat/completions",
        "llama3.1",
        needsApiKey = false
    ),
    CUSTOM(
        "Autre / URL personnalisée",
        "",
        "",
        needsApiKey = false
    ),

    // ── Modèles embarqués sur le téléphone (hors-ligne) ───────────────────────
    ON_DEVICE(
        "Modèle sur téléphone (.task MediaPipe)",
        "",
        "",
        isLocal = true,
        needsApiKey = false
    ),
    LOCAL_GGUF(
        "Modèle GGUF sur téléphone (llama.cpp)",
        "",
        "",
        isLocal = true,
        needsApiKey = false
    ),
    LOCAL_ONNX(
        "Modèle ONNX sur téléphone",
        "",
        "",
        isLocal = true,
        needsApiKey = false
    );

    /** Fournisseurs cloud éligibles au mode Automatique, par ordre de préférence. */
    companion object {
        val AUTO_FALLBACK_ORDER = listOf(
            GROQ, CLAUDE, OPENAI, GEMINI, MISTRAL, DEEPSEEK, PERPLEXITY, TOGETHER, OPENROUTER
        )

        /** Tous les providers cloud qui acceptent une clé API individuelle. */
        val CLOUD_KEY_PROVIDERS = listOf(
            GROQ, OPENAI, CLAUDE, GEMINI, MISTRAL,
            DEEPSEEK, PERPLEXITY, TOGETHER, OPENROUTER, SERPAPI
        )

        fun fromName(name: String): Provider =
            entries.find { it.name == name } ?: GROQ
    }
}
