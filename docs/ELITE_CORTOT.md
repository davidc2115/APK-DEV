# Élite Cortot — phase 1

Évolution de JARVIS (même `applicationId` : la mise à jour s'installe par-dessus).

## Architecture
- `core/agents/AgentOrchestrator` : cerveau principal. Raccourcis locaux → sinon boucle IA ↔ agents.
- Protocole d'outils : `<tool_call>{"agent","action","args"}</tool_call>` (le format Hermes
  `{"name":"agent.action","arguments":{}}` est aussi accepté) → `<tool_response>…</tool_response>`.
- Agents : `phone` (torche, Wi-Fi, Bluetooth, minuteur, réveil, chrono), `calendar`
  (calendriers, surnoms, rendez-vous, couleur, création), `shortcuts` (Lumos / Nox...).
- Actions marquées `sensitive` : confirmation « oui / non » avant exécution.

## Ajouter un agent
Implémenter `Agent`, annoter `@Singleton class X @Inject constructor(...)`, puis l'ajouter à la
liste de l'orchestrateur.

## Cerveau
`AIRouter` choisit le fournisseur (réglages). Défaut chat : Gemini (offre gratuite).
Hermes : via Ollama (serveur local) ou modèle local GGUF une fois `LocalLlmProvider` branché.

## Phase 2 (à faire)
Gmail (résumés matin/soir, tri), fiches contacts enrichies (ContactsContract + Room),
WorkManager pour les résumés planifiés.
