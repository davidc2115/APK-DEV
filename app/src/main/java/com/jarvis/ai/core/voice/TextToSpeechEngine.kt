package com.jarvis.ai.core.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synthèse vocale : moteur Android natif par défaut (gratuit, offline, sans clé).
 * Bascule optionnelle vers une voix cloud (ElevenLabs/Azure) si une clé est configurée,
 * pour une voix plus proche de l'esthétique "Jarvis".
 */
@Singleton
class TextToSpeechEngine @Inject constructor(
    @ApplicationContext context: Context
) {
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
            }
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    fun shutdown() {
        tts?.shutdown()
    }
}
