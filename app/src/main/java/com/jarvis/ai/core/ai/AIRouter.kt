package com.jarvis.ai.core.ai

import com.jarvis.ai.data.settings.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Choisit le fournisseur IA actif selon : la tâche demandée, les préférences utilisateur
 * (réglages) et la disponibilité réseau. Dégradation : cloud préféré -> serveur local (Ollama)
 * -> LLM embarqué si hors-ligne ou aucune clé cloud configurée.
 */
@Singleton
class AIRouter @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards AIProvider>,
    private val settings: SettingsDataStore
) {
    suspend fun route(taskKind: TaskKind, history: List<AIMessage>): AIResponse {
        val preferred = settings.preferredProviderFor(taskKind)
        val ordered = buildList {
            providers.find { it.name == preferred }?.let { add(it) }
            addAll(providers.filter { it.name != preferred })
        }

        for (provider in ordered) {
            if (provider.isAvailable()) {
                return provider.chat(history)
            }
        }
        error("Aucun fournisseur IA disponible (vérifier réglages et connexion réseau).")
    }
}
