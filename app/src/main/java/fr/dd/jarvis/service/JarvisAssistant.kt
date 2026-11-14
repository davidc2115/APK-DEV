package fr.dd.jarvis.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/**
 * JARVIS comme assistant numérique d'Android (Réglages › Téléphone › Applis par défaut › Assistant) : l'appui long sur le bouton
 * d'alimentation / d'accueil, ou le geste d'angle, ouvre directement JARVIS à l'écoute. C'est le seul déclencheur « natif » ouvert
 * aux applications tierces : le mot d'éveil « Ok Google » lui-même est réservé au système (DSP du téléphone).
 */
class JarvisAssistantService : VoiceInteractionService()

class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisSession(this)
}

class JarvisSession(ctx: Context) : VoiceInteractionSession(ctx) {
    /**
     * Appui long sur le bouton d'alimentation / l'accueil (JARVIS assistant numérique par défaut) : comme « Jarvis » dit à
     * voix haute — bip puis écoute de la demande, MÊME écran verrouillé et sans ouvrir l'appli. Repli : l'appli s'ouvre
     * micro ouvert (écoute « Jarvis » coupée).
     */
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        runCatching { fr.dd.jarvis.voice.JournalVoix.noter("assistant", "appel par le geste de l'assistant") }
        val parLeService = runCatching { fr.dd.jarvis.service.JarvisService.ecouterDepuisVoiture(context) }.getOrDefault(false)
        if (!parLeService) runCatching {
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
