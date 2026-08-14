package com.jarvis.ai.core.voice

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Combine deux moteurs gratuits et sans compte pour la détection du mot-clé "Jarvis" :
 *  - openWakeWord (modèle TFLite dédié, entraînable, très faible latence/consommation) — principal.
 *  - Vosk (reconnaissance vocale légère offline) — secours si le modèle openWakeWord custom
 *    n'est pas encore déployé (fonctionne en repérant le mot dans un flux STT continu, plus
 *    gourmand mais fiable dès le premier jour sans entraînement de modèle).
 *
 * TODO Phase 2 :
 *  1) Entraîner/exporter un modèle openWakeWord pour "Jarvis" (outil open source côté PC),
 *     déposer le .tflite dans app/src/main/assets/wakeword/jarvis.tflite.
 *  2) Brancher le runtime TFLite ici (openWakeWordEngine.detect(audioFrame)).
 *  3) Télécharger un petit modèle Vosk FR (vosk-model-small-fr) dans assets/ pour le fallback.
 */
@Singleton
class WakeWordDetectionManager @Inject constructor() {

    private var listening = false

    fun start(onWakeWordDetected: () -> Unit) {
        listening = true
        // TODO: ouvrir AudioRecord, streamer les frames vers le moteur actif
        // (openWakeWordEngine ou VoskWakeWordEngine selon settings.wakeWordEngine()),
        // appeler onWakeWordDetected() sur détection positive.
    }

    fun stop() {
        listening = false
        // TODO: libérer AudioRecord et le moteur actif.
    }

    fun isListening(): Boolean = listening
}
