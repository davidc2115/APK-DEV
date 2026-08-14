package com.jarvis.ai.core.voice

import com.jarvis.ai.data.settings.SettingsDataStore
import javax.inject.Inject

/**
 * Transcription vocale -> texte. Deux modes, choisis dans les réglages :
 *  - Local (whisper.cpp compilé en .so via JNI) : 100% offline, aucun coût, latence dépendant
 *    du modèle choisi (tiny/base/small).
 *  - Cloud (API Whisper) : meilleure précision, nécessite une clé API.
 *
 * TODO Phase 1 : intégrer whisper.cpp (module NDK) pour le mode local ; le mode cloud
 * ci-dessous est déjà appelable dès qu'une clé est renseignée.
 */
class WhisperTranscriber @Inject constructor(
    private val settings: SettingsDataStore
) {
    suspend fun transcribe(audioFilePcm16: ByteArray): String {
        return if (settings.useLocalWhisper()) {
            transcribeLocal(audioFilePcm16)
        } else {
            transcribeCloud(audioFilePcm16)
        }
    }

    private fun transcribeLocal(audio: ByteArray): String {
        // TODO: appel JNI vers whisper.cpp
        return ""
    }

    private suspend fun transcribeCloud(audio: ByteArray): String {
        // TODO: appel multipart vers l'API Whisper (OpenAI) avec la clé settings.getApiKey("openai")
        return ""
    }
}
