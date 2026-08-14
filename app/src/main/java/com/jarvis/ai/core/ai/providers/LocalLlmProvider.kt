package com.jarvis.ai.core.ai.providers

import com.jarvis.ai.core.ai.AIMessage
import com.jarvis.ai.core.ai.AIProvider
import com.jarvis.ai.core.ai.AIResponse
import javax.inject.Inject

/**
 * IA embarquée 100% locale sur le téléphone (llama.cpp ou MLC-LLM via JNI), pour un
 * modèle quantisé léger (Phi-3-mini, Gemma-2B, Qwen2.5-1.5B...). Sert de dernier recours
 * hors-ligne quand ni le cloud ni le serveur Ollama local ne sont joignables.
 *
 * TODO Phase 7 : intégrer le binding natif (.so) + charger les poids du modèle depuis
 * le stockage de l'app (poids non inclus dans ce scaffold — plusieurs centaines de Mo à Go).
 * Ce fichier définit déjà le contrat attendu par AIRouter pour que l'intégration soit un
 * simple remplacement du corps de chat().
 */
class LocalLlmProvider @Inject constructor() : AIProvider {
    override val name = "local_llm"
    override val supportsOffline = true

    private var modelLoaded = false // deviendra vrai une fois le binding natif branché

    override suspend fun isAvailable(): Boolean = modelLoaded

    override suspend fun chat(history: List<AIMessage>): AIResponse {
        if (!modelLoaded) {
            error("Modèle local non chargé. Voir docs/ROADMAP.md Phase 7 (llama.cpp/MLC).")
        }
        // TODO: appel JNI vers le runtime d'inférence.
        return AIResponse(text = "[réponse du LLM local]", providerName = name)
    }
}
