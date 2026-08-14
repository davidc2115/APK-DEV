package com.jarvis.ai.ui.orb

/** États visuels de l'orb, pilotés par le cœur conversationnel et le wake word. */
enum class OrbState {
    IDLE,       // veille, légère pulsation
    LISTENING,  // écoute active (après wake word ou appui)
    THINKING,   // requête IA en cours
    SPEAKING,   // réponse vocale en cours de lecture (TTS)
    ERROR       // erreur réseau/IA à signaler visuellement
}
