package com.jarvis.ai.core.voice

/**
 * Moteur de secours basé sur Vosk (STT offline léger). Utilisé tant que le modèle
 * openWakeWord "Jarvis" n'est pas entraîné/déployé, ou en environnement très bruyant
 * où un vrai modèle de reconnaissance de parole est plus robuste qu'un simple wake word.
 *
 * TODO: intégrer la lib Vosk-Android (org.vosk:vosk-android), charger un modèle FR compact,
 * repérer l'occurrence du mot "jarvis" dans les hypothèses de transcription partielle.
 */
class VoskWakeWordEngine {
    fun processAudioFrame(frame: ShortArray): Boolean {
        // TODO: alimenter le reconnaisseur Vosk, retourner true si "jarvis" détecté.
        return false
    }
}
