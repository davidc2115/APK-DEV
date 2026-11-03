package fr.dd.jarvis.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * Reconnaissance vocale (micro → texte) et synthèse vocale (texte → voix), en français, façon assistant :
 *  - [ecouterConversation] : écoute qui se termine d'elle-même ([onSilence]) si l'utilisateur ne parle pas dans le délai ;
 *  - [carillon] : petit son « je t'écoute » / « fin de conversation » ;
 *  - [speak] : réponse parlée que l'on peut couper en reprenant la parole (comme avec Gemini). Sur le haut-parleur du
 *    téléphone, la voix passe en mode « appel » le temps de la phrase pour que l'annulation d'écho du téléphone retire
 *    la voix de JARVIS du micro : la coupure se déclenche sur VOTRE voix, pas sur la sienne.
 */
class VoiceManager(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onFail: (String) -> Unit = {}
    var onListening: (Boolean) -> Unit = {}
    /** Écoute en conversation terminée : personne n'a commencé à parler dans le délai. */
    var onSilence: () -> Unit = {}
    /** Coupe les bips de la reconnaissance (écoute en arrière-plan). */
    var muteBeeps = false
    /** Couper la parole à JARVIS en reprenant la parole. */
    var bargeIn = true
    /** Sur le haut-parleur du téléphone : voix en mode « appel » pendant la réponse (annulation d'écho → coupure fiable). */
    var duplex = true

    private var muted = false
    private val beepStreams = intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)

    private fun setMuted(on: Boolean) {
        if (!muteBeeps || muted == on) return
        for (st in beepStreams) {
            try {
                if (st == AudioManager.STREAM_MUSIC && am.isMusicActive && on) continue // ne pas couper une musique en cours
                am.adjustStreamVolume(st, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Throwable) {}
        }
        muted = on
    }
    private val unmuteLater = Runnable { setMuted(false) }

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                ttsReady = true
            }
        }
    }

    // ============================================================================================ écoute

    /** Fin de la fenêtre de conversation (0 = écoute simple, sans délai propre). */
    private var fenetreFin = 0L
    /** L'utilisateur a commencé à parler pendant l'écoute en cours. */
    private var parole = false
    /** Numéro de l'écoute en cours : les rappels d'une écoute annulée sont ignorés. */
    private var jeton = 0

    private val finFenetre = Runnable {
        if (fenetreFin > 0 && !parole) {
            jeton++
            runCatching { recognizer?.cancel() }
            finirSilence()
        }
    }

    private fun finirSilence() {
        fenetreFin = 0
        main.removeCallbacks(finFenetre)
        main.postDelayed(unmuteLater, 900)
        onListening(false)
        onSilence()
    }

    /** Écoute simple : une phrase, puis [onFinal] ou [onFail]. */
    fun startListening() { fenetreFin = 0; demarrer(0) }

    /**
     * Écoute « en conversation » : si l'utilisateur ne commence pas à parler dans [silenceMs], l'écoute s'arrête et
     * [onSilence] est appelé. Si le reconnaisseur d'Android abandonne plus tôt, il est relancé sans bruit jusqu'au délai.
     */
    fun ecouterConversation(silenceMs: Long) {
        fenetreFin = System.currentTimeMillis() + silenceMs.coerceAtLeast(2000)
        demarrer(0)
    }

    /** [essai] : relances automatiques sur les erreurs transitoires (service vocal déconnecté…), puis repli sur la reconnaissance locale. */
    private fun demarrer(essai: Int) {
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                fenetreFin = 0
                onFail("Reconnaissance vocale indisponible sur cet appareil (installer / activer Google).")
                return@post
            }
            stopSpeaking()
            main.removeCallbacks(unmuteLater)
            main.removeCallbacks(finFenetre)
            setMuted(true)
            recognizer?.destroy()
            parole = false
            val moi = ++jeton
            val local = essai >= 2 && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
            val r = if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                private fun actif() = moi == jeton
                private fun aParle() { if (!parole) { parole = true; main.removeCallbacks(finFenetre) } }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() { if (actif()) aParle() }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    if (!actif()) return
                    main.removeCallbacks(finFenetre)
                    // 11 = service de reconnaissance déconnecté, 5 = erreur client, 10 = trop de requêtes, 8 = occupé : on recrée et on relance
                    if (error in setOf(11, 5, 10, 8, SpeechRecognizer.ERROR_SERVER) && essai < 3) {
                        main.postDelayed({ if (moi == jeton) demarrer(essai + 1) }, 500L * (essai + 1))
                        return
                    }
                    val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (fenetreFin > 0 && silence && !parole) {
                        // Le reconnaisseur abandonne avant la fin du délai de conversation : on le relance sans bruit.
                        if (System.currentTimeMillis() < fenetreFin - 400) { demarrer(0); return }
                        finirSilence(); return
                    }
                    fenetreFin = 0
                    main.postDelayed(unmuteLater, 900)
                    onListening(false)
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onFail("")
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onFail("Permission micro refusée.")
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> onFail("Reconnaissance vocale : réseau indisponible.")
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> onFail("Reconnaissance vocale occupée, réessaie.")
                        11 -> onFail("Le service de reconnaissance vocale Google est indisponible : vérifie le réseau, ou Réglages Android › Applis › Google › « Reconnaissance vocale » (activer / mettre à jour).")
                        else -> onFail("Erreur de reconnaissance vocale ($error).")
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (!actif()) return
                    fenetreFin = 0
                    main.removeCallbacks(finFenetre)
                    main.postDelayed(unmuteLater, 900)
                    onListening(false)
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (t.isNotBlank()) onFinal(t) else onFail("")
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!actif()) return
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (t.isNotBlank()) { aParle(); onPartial(t) }
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Laisse le temps de réfléchir / faire des pauses avant de considérer la phrase terminée
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            }
            r.startListening(i)
            onListening(true)
            val reste = fenetreFin - System.currentTimeMillis()
            if (fenetreFin > 0) main.postDelayed(finFenetre, reste.coerceAtLeast(500))
        }
    }

    fun stopListening() {
        main.post {
            fenetreFin = 0
            main.removeCallbacks(finFenetre)
            recognizer?.stopListening()
            onListening(false)
        }
    }

    // ============================================================================================ sons

    /** Petit carillon : deux notes montantes (« je t'écoute ») ou descendantes (« fin de la conversation »), puis [puis]. */
    fun carillon(montant: Boolean = true, puis: () -> Unit = {}) {
        Thread {
            var piste: AudioTrack? = null
            try {
                val taux = 22050
                val notes = if (montant) doubleArrayOf(784.0, 1175.0) else doubleArrayOf(988.0, 659.0)
                val n = (taux * 0.08).toInt()
                val pcm = ShortArray(n * notes.size)
                notes.forEachIndexed { k, f ->
                    for (i in 0 until n) {
                        val enveloppe = sin(PI * i / n)
                        pcm[k * n + i] = (sin(2 * PI * f * i / taux) * enveloppe * 0.2 * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                piste = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(taux).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                piste.write(pcm, 0, pcm.size)
                piste.play()
                Thread.sleep(notes.size * 80L + 50)
            } catch (_: Throwable) {
            } finally {
                runCatching { piste?.release() }
            }
            main.post(puis)
        }.start()
    }

    // ============================================================================================ voix de JARVIS

    private var barge: BargeIn? = null
    private var enCours: AtomicBoolean? = null
    private var appelActif = false
    private var focus: AudioFocusRequest? = null

    private fun micOk() = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Vrai si la voix sort du haut-parleur du téléphone (pas de casque, d'écouteurs Bluetooth ni de voiture). */
    private fun hautParleurSeul(): Boolean {
        if ((ctx.applicationContext as? fr.dd.jarvis.JarvisApp)?.enVoiture == true) return false
        val externes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL
        )
        return runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type in externes } }.getOrDefault(false)
    }

    private val attrAppel = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val attrAssistant = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun entrerModeAppel(): Boolean {
        if (appelActif) return true
        return try {
            if (am.mode != AudioManager.MODE_NORMAL) return false          // vrai appel en cours : on n'y touche pas
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrAppel).build()
                .also { am.requestAudioFocus(it) }
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
            appelActif = true
            true
        } catch (_: Throwable) {
            sortirModeAppel(force = true); false
        }
    }

    private fun sortirModeAppel(force: Boolean = false) {
        if (!appelActif && !force) return
        appelActif = false
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        }
        runCatching { if (am.mode == AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_NORMAL }
        runCatching { focus?.let { am.abandonAudioFocusRequest(it) } }
        focus = null
    }

    /**
     * Dit [text] puis appelle [onDone]. Si l'utilisateur reprend la parole pendant ce temps, JARVIS se tait aussitôt et
     * [onDone] est appelé (l'appelant relance alors l'écoute : la demande est captée).
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        main.removeCallbacks(unmuteLater); setMuted(false)
        enCours?.set(true); barge?.stop(); barge = null
        val clean = cleanForSpeech(text)
        if (!ttsReady || clean.isBlank()) {
            main.post(onDone)
            return
        }
        val coupe = bargeIn && micOk()
        val appel = coupe && duplex && hautParleurSeul() && entrerModeAppel()
        runCatching { tts?.setAudioAttributes(if (appel) attrAppel else attrAssistant) }
        val fini = AtomicBoolean(false)
        enCours = fini
        fun terminer() {
            if (fini.compareAndSet(false, true)) {
                barge?.stop(); barge = null
                main.post { sortirModeAppel(); onDone() }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // L'écoute de coupure démarre quand la voix sort vraiment (ses 400 premières ms servent à mesurer l'écho).
            override fun onStart(utteranceId: String?) {
                if (coupe) main.post {
                    if (!fini.get() && barge == null) barge = BargeIn { main.post { if (!fini.get()) { tts?.stop(); terminer() } } }.also { it.start() }
                }
            }
            override fun onDone(utteranceId: String?) { terminer() }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { terminer() }
            override fun onError(utteranceId: String?, errorCode: Int) { terminer() }
        })
        tts?.speak(clean.take(3500), TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    /** Fait taire JARVIS sans rappeler la suite (nouvelle demande, arrêt…). */
    fun stopSpeaking() {
        enCours?.set(true)
        barge?.stop(); barge = null
        tts?.stop()
        sortirModeAppel()
    }

    fun release() {
        main.post { main.removeCallbacks(unmuteLater); main.removeCallbacks(finFenetre); setMuted(false) }
        main.post {
            jeton++
            recognizer?.destroy()
            recognizer = null
        }
        enCours?.set(true); barge?.stop(); barge = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        main.post { sortirModeAppel() }
    }

    private fun cleanForSpeech(t: String): String = t
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[[^\\]]*]\\(jarvis-file:[^)]*\\)"), "fichier créé")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]"), "")
        .replace(Regex("[#*`_>|]"), "")
        .replace(Regex("(?m)^\\s*-{3,}\\s*$"), ". ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
