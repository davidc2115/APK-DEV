package com.jarvis.assistant

/**
 * Liste des fournisseurs IA disponibles.
 * isLocal = true signifie : aucun réseau, modèle exécuté directement sur le téléphone.
 */
enum class Provider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val isLocal: Boolean = false
) {
    CLAUDE(
        "Claude (Anthropic)",
        "https://api.anthropic.com/v1/messages",
        "claude-3-5-sonnet-20241022"
    ),
    OPENAI(
        "ChatGPT (OpenAI)",
        "https://api.openai.com/v1/chat/completions",
        "gpt-4o-mini"
    ),
    GEMINI(
        "Google Gemini",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent",
        "gemini-1.5-flash"
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

    companion object {
        fun fromName(name: String): Provider =
            entries.find { it.name == name } ?: GROQ
    }
}
