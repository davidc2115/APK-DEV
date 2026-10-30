package fr.dd.jarvis.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/**
 * JARVIS comme assistant numérique d'Android (Réglages › Applications par défaut › Assistant) : l'appui long sur le bouton
 * d'alimentation / d'accueil, ou le geste d'angle, ouvre directement JARVIS à l'écoute. C'est le seul déclencheur « natif » ouvert
 * aux applications tierces : le mot d'éveil « Ok Google » lui-même est réservé au système (DSP du téléphone).
 */
class JarvisAssistantService : VoiceInteractionService()

class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisSession(this)
}

class JarvisSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        runCatching {
            context.startActivity(Intent(context, fr.dd.jarvis.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("listen", true))
        }
        hide()
    }
}

/** Requis par Android pour déclarer un service d'interaction vocale ; JARVIS n'est pas un moteur de dictée. */
class JarvisStubRecognizer : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { runCatching { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
