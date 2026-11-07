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
                // Un seul écouteur : chaque phrase dite par la voix Android débloque la parole qui l'attend.
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { utteranceId?.let { debutsAndroid[it]?.invoke() } }
                    override fun onDone(utteranceId: String?) { utteranceId?.let { finsAndroid.remove(it)?.countDown() } }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { finsAndroid.remove(it)?.countDown() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { utteranceId?.let { finsAndroid.remove(it)?.countDown() } }
                    override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { finsAndroid.remove(it)?.countDown() } }
                })
            }
        }
    }
    private val finsAndroid = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()
    private val debutsAndroid = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

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
                    val entendu = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (entendu.isNotBlank() && estEcho(entendu)) {
                        // Jarvis s'est réentendu (voix renvoyée par l'autoradio) : on ignore et on continue d'écouter.
                        main.removeCallbacks(finFenetre)
                        if (fenetreFin > 0 && System.currentTimeMillis() < fenetreFin - 400) { onPartial(""); demarrer(0); return }
                        fenetreFin = 0
                        main.postDelayed(unmuteLater, 900)
                        onListening(false)
                        onFail("")
                        return
                    }
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
                    // Un résultat partiel peut être une hallucination sur un bruit ou l'écho du TTS.
                    // Seul onBeginningOfSpeech (détection vocale du moteur) démarre la fenêtre de parole ;
                    // le texte partiel reste affiché sans empêcher la fermeture sur silence.
                    if (t.isNotBlank() && !estEcho(t)) onPartial(t)
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
    @Volatile private var paroleEnCours: Parole? = null
    private var appelActif = false
    private var focus: AudioFocusRequest? = null
    private var focusLegacy = false
    private var mediaPauseeParJarvis = false

    // ------------------------------------------------------------------ session de conversation

    /** Conversation vocale en cours : la musique reste en pause d'un bout à l'autre (plus de coupure/reprise à chaque phrase). */
    @Volatile var sessionActive = false
        private set
    private var focusSession: AudioFocusRequest? = null
    private var mediaPauseeSession = false
    private val focusSessionListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Quelqu'un reprend la main durablement (appel, lecture relancée à la main) : on ne relancera rien nous-mêmes.
        if (change == AudioManager.AUDIOFOCUS_LOSS) mediaPauseeSession = false
    }

    /** Début de conversation (mot « Jarvis », micro touché) : met la musique en pause une seule fois pour toute la session. */
    fun sessionDebut() = main.post {
        if (sessionActive) return@post
        sessionActive = true
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            val demande = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(if (sortieBluetooth()) attrBluetooth else attrAssistant)
                .setOnAudioFocusChangeListener(focusSessionListener, main)
                .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true).build()
            focusSession = demande
            am.requestAudioFocus(demande)
        }
        // Certains lecteurs (et autoradios) ignorent le focus : vraie commande PAUSE, reprise ciblée en fin de session.
        mediaPauseeSession = fr.dd.jarvis.service.JarvisNotificationListener.pauseMediaPourVoix(ctx)
    }

    /** Fin de conversation : rend le focus et relance la musique que Jarvis avait mise en pause (après un court délai). */
    fun sessionFin() = main.post {
        if (!sessionActive) return@post
        sessionActive = false
        runCatching { focusSession?.let { am.abandonAudioFocusRequest(it) } }
        focusSession = null
        if (mediaPauseeSession) {
            mediaPauseeSession = false
            // Laisse le micro et la voix se libérer, sinon la musique repart puis se recoupe.
            main.postDelayed({ if (!sessionActive) fr.dd.jarvis.service.JarvisNotificationListener.reprendreMediaApresVoix() }, 700)
        }
    }

    /** Vrai si le son sort vers un appareil externe sans annulation d'écho fiable (Bluetooth, autoradio, Android Auto). */
    fun sortieExterne(): Boolean = sortieBluetooth()

    // ------------------------------------------------------------------ anti-écho (voix de Jarvis réentendue)

    @Volatile private var derniereVoix = ""
    @Volatile private var finVoix = 0L

    private fun mots(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.length >= 2 }

    /** Le texte reconnu reprend la réponse que Jarvis vient de dire (écho de l'autoradio / du haut-parleur). */
    internal fun estEcho(entendu: String): Boolean {
        if (derniereVoix.isBlank() || System.currentTimeMillis() - finVoix > 20_000) return false
        return estEchoDe(entendu, derniereVoix)
    }

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
    // Une voix envoyée vers A2DP doit utiliser le même usage média que les lecteurs Bluetooth :
    // USAGE_ASSISTANT peut être routé différemment par certaines politiques audio constructeur.
    private val attrBluetooth = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Le listener est volontairement présent : Android peut notifier la perte/reprise du focus
        // et certains constructeurs n'accordent pas correctement une demande sans listener.
        if (change == AudioManager.AUDIOFOCUS_LOSS) main.post { paroleEnCours?.arreter(sansSuite = false) }
    }
    private fun sortieBluetooth(): Boolean {
        val types = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER
        )
        return (ctx.applicationContext as? fr.dd.jarvis.JarvisApp)?.enVoiture == true ||
            runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types } }.getOrDefault(false)
    }

    /** Focus exclusif temporaire : demande aux lecteurs actifs de suspendre leur lecture pendant le TTS. */
    private fun demanderFocusReponse(attrs: AudioAttributes = attrAssistant) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            focus?.let { am.abandonAudioFocusRequest(it) }
            if (focusLegacy) {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
                focusLegacy = false
            }
            val demande = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, main)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .build()
            focus = demande
            val resultat = am.requestAudioFocus(demande)
            if (resultat != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                // Repli legacy : utile sur certaines ROM Android modifiées qui refusent la requête moderne.
                @Suppress("DEPRECATION")
                focusLegacy = am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }
    }

    private fun entrerModeAppel(): Boolean {
        if (appelActif) return true
        return try {
            if (am.mode != AudioManager.MODE_NORMAL) return false          // vrai appel en cours : on n'y touche pas
            demanderFocusReponse(attrAppel)
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
        val etaitEnModeAppel = appelActif
        appelActif = false
        if (etaitEnModeAppel || force) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                }
            }
            runCatching { if (am.mode == AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_NORMAL }
        }
        // Le focus est aussi utilisé en Bluetooth/A2DP sans passer en mode appel.
        runCatching { focus?.let { am.abandonAudioFocusRequest(it) } }
        if (focusLegacy) runCatching {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
        focusLegacy = false
        focus = null
    }

    /**
     * Dit [text] puis appelle [onDone]. Si l'utilisateur reprend la parole pendant ce temps, JARVIS se tait aussitôt et
     * [onDone] est appelé (l'appelant relance alors l'écoute : la demande est captée).
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        parole(onDone = onDone).apply { ajouter(text); finir() }
    }

    /**
     * Réponse parlée PROGRESSIVE : le texte est ajouté au fil de l'eau ([Parole.ajouter], par exemple pendant qu'Hermès
     * écrit) et Jarvis commence à parler dès la première phrase complète ; [Parole.finir] donne le texte final.
     * Voix naturelle (Gemini) si disponible — la phrase suivante est préparée pendant que la précédente est dite —,
     * sinon voix Android, phrase par phrase.
     */
    fun parole(limite: Int = 3500, onDone: () -> Unit = {}): Parole {
        main.removeCallbacks(unmuteLater); setMuted(false)
        paroleEnCours?.arreter(sansSuite = true)
        return Parole(limite, onDone).also { paroleEnCours = it; it.demarrer() }
    }

    inner class Parole internal constructor(private val limite: Int, private val onDone: () -> Unit) {
        private val fini = AtomicBoolean(false)
        private val file = java.util.concurrent.LinkedBlockingDeque<String>()
        private val tampon = StringBuilder()
        private val recu = StringBuilder()
        @Volatile private var ferme = false
        @Volatile private var unMorceau = false
        @Volatile private var occupe = false
        @Volatile private var prepare = false
        @Volatile private var total = 0
        private var coupe = false
        private var externe = false
        private var attrsVoix: AudioAttributes = attrAssistant
        @Volatile private var piste: AudioTrack? = null
        private var formatPiste = 0 to 0
        private val dits = StringBuilder()

        /** Ajoute du texte (morceau de réponse en cours d'écriture). */
        fun ajouter(texte: String) {
            if (fini.get() || ferme || texte.isEmpty()) return
            synchronized(tampon) { recu.append(texte); tampon.append(texte); extraire(attendreBloc = occupe, toutDire = false) }
        }

        /**
         * Fin du texte. [texteFinal] = réponse complète (si elle diffère de ce qui a été reçu : repli, message
         * d'arrière-plan… on dit ce qui manque, ou la réponse entière si rien n'a été reçu).
         */
        fun finir(texteFinal: String? = null) {
            if (fini.get()) return
            synchronized(tampon) {
                if (texteFinal != null) {
                    val r = norm(recu.toString()); val f = norm(texteFinal)
                    when {
                        r.isBlank() -> tampon.append(texteFinal)
                        r.contains(f) -> {}                                             // déjà tout reçu (et dit)
                        f.startsWith(r) -> tampon.append(texteFinal.trimStart().removePrefix("🪽").trimStart().drop(recu.toString().trimStart().removePrefix("🪽").trimStart().length))
                        else -> tampon.append("\n").append(texteFinal)
                    }
                }
                extraire(attendreBloc = false, toutDire = true)
                ferme = true
            }
        }

        private fun norm(t: String) = t.trim().removePrefix("🪽").trim()

        /** Découpe le texte reçu en morceaux à dire (première phrase seule, puis blocs). Appelé sous verrou [tampon]. */
        private fun extraire(attendreBloc: Boolean, toutDire: Boolean) {
            val (morceaux, reste) = VoixNaturelle.decouper(tampon.toString(), unMorceau, toutDire, attendreBloc)
            tampon.setLength(0); tampon.append(reste)
            for (m in morceaux) {
                val c = cleanForSpeech(m)
                if (c.isBlank() || total > limite) continue
                total += c.length; unMorceau = true
                file.offer(c)
            }
        }

        internal fun demarrer() { Thread({ boucle() }, "jarvis-voix").start() }

        /** Arrête la voix ; [sansSuite] = sans appeler la suite (nouvelle demande, arrêt demandé). */
        fun arreter(sansSuite: Boolean) {
            if (!fini.compareAndSet(false, true)) return
            barge?.stop(); barge = null
            runCatching { piste?.pause(); piste?.flush() }
            runCatching { tts?.stop() }
            if (sansSuite) {
                main.post {
                    sortirModeAppel()
                    if (mediaPauseeParJarvis) fr.dd.jarvis.service.JarvisNotificationListener.reprendreMediaApresVoix()
                    mediaPauseeParJarvis = false
                }
            } else terminerApres()
        }

        /** Préparation audio au premier morceau (focus, mode appel pour l'annulation d'écho, pause de la musique). */
        private fun preparer() {
            if (prepare) return
            prepare = true
            // Sur l'autoradio / un casque Bluetooth, le micro entend la voix de Jarvis sans annulation d'écho : la « coupure
            // en reprenant la parole » se déclencherait sur sa propre voix. Elle reste active sur le téléphone seul.
            externe = sortieBluetooth()
            coupe = bargeIn && micOk() && !externe
            val appel = coupe && duplex && hautParleurSeul() && entrerModeAppel()
            // En Bluetooth/A2DP et Android Auto, l'usage MEDIA garantit le routage vers l'autoradio.
            attrsVoix = when { appel -> attrAppel; externe -> attrBluetooth; else -> attrAssistant }
            if (!appel && am.mode == AudioManager.MODE_NORMAL && !sessionActive) demanderFocusReponse(attrsVoix)
            // Hors session : focus + vraie commande PAUSE pour les lecteurs qui ignorent le focus, reprise après la réponse.
            mediaPauseeParJarvis = !sessionActive && externe && fr.dd.jarvis.service.JarvisNotificationListener.pauseMediaPourVoix(ctx)
            runCatching { tts?.setAudioAttributes(attrsVoix) }
        }

        /** La voix sort vraiment : l'écoute de coupure démarre (ses 400 premières ms servent à mesurer l'écho). */
        private fun voixLancee() {
            if (coupe) main.post {
                if (!fini.get() && barge == null) barge = BargeIn { main.post { if (!fini.get()) arreter(sansSuite = false) } }.also { it.start() }
            }
        }

        private fun boucle() {
            val app = ctx.applicationContext as? fr.dd.jarvis.JarvisApp
            val prepa = java.util.concurrent.Executors.newSingleThreadExecutor()
            var suivant: Pair<String, java.util.concurrent.Future<VoixNaturelle.Audio?>>? = null
            try {
                while (!fini.get()) {
                    // Voix libre : les phrases complètes déjà reçues partent sans attendre un bloc plus long.
                    if (file.isEmpty()) synchronized(tampon) { extraire(attendreBloc = false, toutDire = false) }
                    val morceau = file.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (morceau == null) {
                        if (ferme && file.isEmpty()) break
                        continue
                    }
                    occupe = true
                    var audio: VoixNaturelle.Audio? = null
                    if (app != null && VoixNaturelle.active(app)) {
                        audio = suivant?.takeIf { it.first == morceau }?.let { runCatching { it.second.get(25, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() }
                            ?: VoixNaturelle.synthese(app, morceau)
                        suivant = null
                        // Le morceau suivant se prépare pendant que celui-ci est dit.
                        file.peekFirst()?.let { n -> if (VoixNaturelle.active(app)) suivant = n to prepa.submit<VoixNaturelle.Audio?> { VoixNaturelle.synthese(app, n) } }
                    }
                    if (fini.get()) break
                    preparer()
                    dits.append(morceau).append(' ')
                    derniereVoix = dits.toString(); finVoix = System.currentTimeMillis() + 60_000
                    if (audio != null) jouer(audio) else direAndroid(morceau)
                    occupe = false
                }
                if (!fini.get()) vider()
            } catch (_: Throwable) {
            } finally {
                prepa.shutdownNow()
                runCatching { piste?.release() }; piste = null
                if (fini.compareAndSet(false, true)) terminerApres()
            }
        }

        private fun jouer(a: VoixNaturelle.Audio) {
            val canal = if (a.canaux == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            if (piste == null || formatPiste != a.frequence to a.canaux) {
                vider()
                val min = AudioTrack.getMinBufferSize(a.frequence, canal, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
                piste = AudioTrack.Builder().setAudioAttributes(attrsVoix)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(a.frequence).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(canal).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(min * 4).build()
                formatPiste = a.frequence to a.canaux
                ecrits = 0L
                piste?.play()
                voixLancee()
            }
            val t = piste ?: return
            var off = 0
            while (off < a.pcm.size && !fini.get()) {
                val n = t.write(a.pcm, off, minOf(8192, a.pcm.size - off))
                if (n <= 0) break
                off += n
            }
            ecrits += a.pcm.size / (2L * a.canaux)
        }
        private var ecrits = 0L

        /**
         * Attend que tout ce qui a été écrit soit réellement joué, puis libère la piste. Un peu de silence est ajouté :
         * une piste en flux ne démarre qu'une fois son tampon rempli (une phrase très courte resterait muette).
         */
        private fun vider() {
            val t = piste ?: return
            val freq = formatPiste.first.coerceAtLeast(8000)
            runCatching { t.write(ByteArray((t.bufferSizeInFrames * 2 * formatPiste.second).coerceIn(2048, 192_000)), 0, (t.bufferSizeInFrames * 2 * formatPiste.second).coerceIn(2048, 192_000)) }
            val limite = System.currentTimeMillis() + (ecrits * 1000 / freq) + 2500
            while (!fini.get() && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < ecrits && System.currentTimeMillis() < limite) Thread.sleep(25)
            runCatching { t.pause(); t.flush(); t.release() }
            piste = null; ecrits = 0L; formatPiste = 0 to 0
        }

        private fun direAndroid(morceau: String) {
            vider()
            val t = tts ?: return
            if (!ttsReady) return
            val id = UUID.randomUUID().toString()
            val latch = java.util.concurrent.CountDownLatch(1)
            finsAndroid[id] = latch
            var premier = true
            debutsAndroid[id] = { if (premier) { premier = false; voixLancee() } }
            val ok = runCatching { t.speak(morceau, TextToSpeech.QUEUE_ADD, null, id) }.getOrDefault(TextToSpeech.ERROR)
            if (ok == TextToSpeech.ERROR) { finsAndroid.remove(id); debutsAndroid.remove(id); return }
            latch.await(15_000L + morceau.length * 120L, java.util.concurrent.TimeUnit.MILLISECONDS)
            debutsAndroid.remove(id)
        }

        private fun terminerApres() {
            barge?.stop(); barge = null
            finVoix = System.currentTimeMillis()
            main.post {
                if (paroleEnCours === this) paroleEnCours = null
                sortirModeAppel()
                if (mediaPauseeParJarvis) fr.dd.jarvis.service.JarvisNotificationListener.reprendreMediaApresVoix()
                mediaPauseeParJarvis = false
                // Bluetooth / autoradio : le son sort avec du retard (tampon A2DP + traitement de l'autoradio) ; on
                // attend que la fin de la phrase soit vraiment jouée avant de rouvrir le micro.
                if (externe) main.postDelayed({ finVoix = System.currentTimeMillis(); onDone() }, 1000) else onDone()
            }
        }
    }

    /** Fait taire JARVIS sans rappeler la suite (nouvelle demande, arrêt…). */
    fun stopSpeaking() {
        paroleEnCours?.arreter(sansSuite = true)
        paroleEnCours = null
        barge?.stop(); barge = null
        tts?.stop()
    }

    fun release() {
        sessionFin()
        main.post { main.removeCallbacks(unmuteLater); main.removeCallbacks(finFenetre); setMuted(false) }
        main.post {
            jeton++
            recognizer?.destroy()
            recognizer = null
        }
        paroleEnCours?.arreter(sansSuite = true); paroleEnCours = null
        barge?.stop(); barge = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        main.post {
            sortirModeAppel()
            if (mediaPauseeParJarvis) fr.dd.jarvis.service.JarvisNotificationListener.reprendreMediaApresVoix()
            mediaPauseeParJarvis = false
        }
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

/**
 * Vrai si [entendu] n'est qu'un morceau de [voix] (ce que Jarvis vient de dire) : au moins 3 mots dont 60 % figurent
 * dans la réponse, ou un fragment (≥ 6 lettres) contenu tel quel dans la réponse.
 */
internal fun estEchoDe(entendu: String, voix: String): Boolean {
    fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    val e = norm(entendu); val v = norm(voix)
    if (e.isBlank() || v.isBlank()) return false
    val motsE = e.split(' ').filter { it.length >= 2 }
    val motsV = v.split(' ').filter { it.length >= 2 }.toSet()
    if (motsE.size >= 3) return motsE.count { it in motsV } >= kotlin.math.ceil(motsE.size * 0.6)
    return e.length >= 6 && v.contains(e)
}
