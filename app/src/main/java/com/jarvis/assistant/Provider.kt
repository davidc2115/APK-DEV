package com.jarvis.assistant

/**
 * Liste des fournisseurs IA disponibles.
 * isLocal = true signifie : aucun réseau, modèle exécuté directement sur le téléphone.
 * isAuto = true signifie : essaie plusieurs fournisseurs configurés jusqu'à ce que l'un réponde.
 */
enum class Provider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val isLocal: Boolean = false,
    val isAuto: Boolean = false
) {
    AUTO_BEST(
        "🤖 Automatique (essaie tes IA configurées)",
        "",
        "",
        isAuto = true
    ),
    CLAUDE(
        "Claude (Anthropic)",
        "https://api.anthropic.com/v1/messages",
        "claude-sonnet-5"
    ),
    OPENAI(
        "ChatGPT (OpenAI)",
        "https://api.openai.com/v1/chat/completions",
        "gpt-4o-mini"
    ),
    GEMINI(
        "Google Gemini",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
        "gemini-3.5-flash-lite"
    ),
    MISTRAL(
        "Mistral AI",
        "https://api.mistral.ai/v1/chat/completions",
        "mistral-large-latest"
    ),
    GROQ(
        "Groq (gratuit, très rapide)",
        "https://api.groq.com/openai/v1/chat/completions",
        "llama-3.3-70b-versatile"
    ),
    OLLAMA(
        "IA locale sur PC (Ollama)",
        "http://192.168.1.50:11434/v1/chat/completions",
        "llama3.1"
    ),
    ON_DEVICE(
        "Modèle local sur ce téléphone (hors-ligne)",
        "",
        "",
        isLocal = true
    ),
    CUSTOM(
        "Autre / URL personnalisée",
        "",
        ""
    );

    /** Fournisseurs cloud éligibles au mode Automatique, par ordre de préférence. */
    companion object {
        val AUTO_FALLBACK_ORDER = listOf(CLAUDE, OPENAI, GEMINI, GROQ, MISTRAL)

        fun fromName(name: String): Provider =
            entries.find { it.name == name } ?: GROQ
    }
}
