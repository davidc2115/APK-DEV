package fr.dd.jarvis.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
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

    companion object {
        /** Toutes les instances vivantes (service, appli, voiture…) : une seule peut écouter à la fois. */
        private val instances = java.util.Collections.newSetFromMap(java.util.WeakHashMap<VoiceManager, Boolean>())

        /**
         * Moteur de reconnaissance à utiliser : jamais celui de JARVIS. Quand JARVIS est l'assistant par défaut, Android
         * désigne son moteur « factice » comme reconnaissance du système : chaque écoute échouait, était relancée
         * (bips, délais) puis basculait sur la reconnaissance locale. On vise directement celui de Google.
         */
        fun moteur(ctx: Context): android.content.ComponentName? = runCatching {
            val l = ctx.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
                .map { it.serviceInfo }.filter { it.packageName != ctx.packageName }
            val choisi = l.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
                ?: l.firstOrNull { it.packageName == "com.google.android.as" } ?: l.firstOrNull { it.packageName.startsWith("com.google") } ?: l.firstOrNull()
            choisi?.let { android.content.ComponentName(it.packageName, it.name) }
        }.getOrNull()

        /** Le moteur de Google a refusé l'écoute « longue » (par segments) : mode normal jusqu'au prochain démarrage. */
        @Volatile var segmentKo = false

        /** Liaison mains-libres ouverte vers un casque / une voiture Bluetooth (micro et son de la conversation) et son auteur. */
        @Volatile private var microBt: AudioDeviceInfo? = null
        @Volatile private var microBtPar: VoiceManager? = null

        /** Mots sur lesquels une phrase française ne se termine presque jamais : Google a coupé à une pause. */
        private val MOTS_SUITE = setOf(
            "de", "du", "des", "le", "la", "les", "l", "d", "et", "ou", "ni", "pour", "avec", "sur", "dans", "à", "au", "aux",
            "en", "que", "qu", "mais", "puis", "ce", "cette", "ces", "cet", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa",
            "ses", "notre", "votre", "nos", "vos", "comme", "par", "chez", "sans", "très", "entre", "vers", "contre", "je", "j",
            "tu", "il", "on", "ils", "me", "te", "se", "vais", "quel", "quelle", "quels", "quelles", "parce", "lorsque",
            "lorsqu", "puisque", "puisqu", "jusqu", "jusque", "afin",
        )

        /**
         * Phrase visiblement inachevée (« lance la création de », « envoie un message à », « allume la lumière et ») :
         * la suite est attendue au lieu d'envoyer un bout de phrase. Une inversion (« comment vas-tu », « fais-le ») est
         * une phrase complète.
         */
        internal fun suiteProbable(t: String): Boolean {
            val s = t.trim()
            if (s.isEmpty()) return false
            if (s.endsWith(",") || s.endsWith("…") || s.endsWith("...") || s.endsWith("'") || s.endsWith("’")) return true
            val dernier = s.split(Regex("\\s+")).last()
            if ('-' in dernier) return false
            return dernier.lowercase().trim { !it.isLetter() } in MOTS_SUITE
        }
    }

    /** Nom affiché dans le journal de l'écoute (service, appli, voiture…). */
    var nom = "voix"

    init { synchronized(instances) { instances.add(this) } }

    private val main = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onFail: (String) -> Unit = {}
    var onListening: (Boolean) -> Unit = {}
    /** Écoute commencée / finie : avertit l'appelant et l'avatar (Presence). */
    private fun signalerEcoute(actif: Boolean) { Presence.ecouteChange(actif); onListening(actif) }
    /** Écoute en conversation terminée : personne n'a commencé à parler dans le délai. */
    var onSilence: () -> Unit = {}
    /** Coupe les bips de la reconnaissance (écoute en arrière-plan). */
    var muteBeeps = false
    /** Couper la parole à JARVIS en reprenant la parole. */
    var bargeIn = true
    /** Sur le haut-parleur du téléphone : voix en mode « appel » pendant la réponse (annulation d'écho → coupure fiable). */
    var duplex = true
    /** Force le routage média vers la sortie voiture quand la voix est pilotée par l'interface Android Auto. */
    @Volatile var sortieVoitureForcee = false

    private var muted = false
    // Bips du reconnaisseur de Google : flux système / notification (et accessibilité sur certains téléphones).
    private val beepStreams = intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_ACCESSIBILITY, AudioManager.STREAM_MUSIC)

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
        // Moteur de Google (voix bien plus naturelles) plutôt que celui du constructeur (Xiaomi…), s'il est installé.
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                VoixAndroid.appliquer(ctx, tts)
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
        tts = if (VoixAndroid.googleInstalle(ctx)) TextToSpeech(ctx, listener, VoixAndroid.MOTEUR_GOOGLE) else TextToSpeech(ctx, listener)
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
    /** Relance programmée après une erreur passagère : annulée dès que l'écoute est refermée (plus de micro rouvert après la fin). */
    private var relanceEnAttente: Runnable? = null
    /** Reconnaisseur réutilisé d'une écoute à l'autre (le recréer à chaque fois faisait répondre « service déconnecté »,
     *  erreur 11, à la première ouverture : un micro ouvert pour rien, donc un bip de plus). */
    private var recoCle = ""
    private var recoCasse = false
    private var ouvertLe = 0L
    /** Dernier texte partiel entendu pendant l'écoute en cours (sert si Google répond « rien compris » après la phrase). */
    private var dernierPartiel = ""
    /** Dernier moment où le micro a capté une voix (niveau sonore ou texte partiel) : on ne referme jamais pendant que tu parles. */
    private var dernierSon = 0L
    private var sonsForts = 0
    /** Début de phrase gardé quand Google a coupé à une pause (« lance la création de… ») : la suite y est ajoutée. */
    private var debutPhrase = ""
    /** La fenêtre d'écoute peut être prolongée tant qu'une voix est captée, mais jamais au-delà (TV, radio…). */
    private var limiteFenetre = 0L
    /** Moment où tu as commencé à parler dans l'écoute en cours (0 = pas encore). */
    private var debutParoleLe = 0L

    /** Silence qui marque la fin d'une phrase (réglage « Pause en fin de phrase »). */
    private fun pauseFinMs(): Int = ((ctx.applicationContext as? fr.dd.jarvis.JarvisApp)?.store?.pauseFin?.value ?: 2000).coerceIn(800, 4000)
    /** Écoute longue choisie dans les réglages (expérimentale). */
    private fun ecouteLongueVoulue(): Boolean = (ctx.applicationContext as? fr.dd.jarvis.JarvisApp)?.store?.ecouteLongue?.value == true

    /** Livre un début de phrase gardé (la suite n'est pas venue) : vrai si quelque chose a été livré. */
    private fun livrerDebut(): Boolean {
        val p = debutPhrase.trim()
        if (p.isEmpty()) return false
        debutPhrase = ""
        JournalVoix.noter(nom, "pas de suite : « ${p.take(200)} » envoyé tel quel")
        fenetreFin = 0
        main.removeCallbacks(finFenetre)
        main.postDelayed(unmuteLater, 900)
        signalerEcoute(false)
        onFinal(p)
        return true
    }
    /** Moment où le dernier reconnaisseur a été détruit : en recréer un dans la foulée faisait répondre « erreur 11 ». */
    private var detruitLe = 0L

    /**
     * Libère le reconnaisseur DÈS que son écoute est finie (résultat ou erreur définitive), pendant que JARVIS répond :
     * le service vocal de Google a le temps de se détacher. Avant, il était détruit puis recréé dans la même fraction de
     * seconde à la réouverture du micro → « service déconnecté » (erreur 11) presque à chaque tour de conversation.
     */
    private fun liberer() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
        detruitLe = System.currentTimeMillis()
    }
    /** Le micro peut-il s'ouvrir ? (le service se tait pendant une conversation dans l'appli). */
    var autorise: () -> Boolean = { true }
    /** La coupure de parole (voix entendue pendant la réponse) est-elle permise en ce moment ? */
    var coupureAutorisee: () -> Boolean = { true }

    private fun annulerRelance() { relanceEnAttente?.let { main.removeCallbacks(it) }; relanceEnAttente = null }

    private val finFenetre: Runnable = object : Runnable {
        override fun run() {
            if (fenetreFin <= 0 || parole) return
            // Voix captée il y a moins de 1,5 s (Google n'a pas encore signalé le début de la parole) : on prolonge,
            // sans dépasser la limite (une télé allumée ne doit pas garder le micro ouvert indéfiniment).
            val maintenant = System.currentTimeMillis()
            if (maintenant - dernierSon < 1500 && maintenant < limiteFenetre) {
                JournalVoix.noter(nom, "voix captée : écoute prolongée")
                fenetreFin = maintenant + 2500
                main.postDelayed(this, 2500)
                return
            }
            jeton++
            ecoute = false
            liberer()
            if (livrerDebut()) return
            finirSilence()
        }
    }

    private fun finirSilence() {
        fenetreFin = 0
        main.removeCallbacks(finFenetre)
        main.postDelayed(unmuteLater, 900)
        signalerEcoute(false)
        onSilence()
    }

    /** Écoute simple : une phrase, puis [onFinal] ou [onFail]. */
    fun startListening() { fenetreFin = 0; limiteFenetre = 0; debutPhrase = ""; relanceAuto = false; demarrer(0) }
    /** Écoute rouverte automatiquement après une réponse (et non demandée par « Jarvis » ou le bouton micro). */
    private var relanceAuto = false

    /**
     * Écoute « en conversation » : si l'utilisateur ne commence pas à parler dans [silenceMs], l'écoute s'arrête et
     * [onSilence] est appelé. Si le reconnaisseur d'Android abandonne plus tôt, il est relancé sans bruit jusqu'au délai.
     */
    fun ecouterConversation(silenceMs: Long, relance: Boolean = false) {
        relanceAuto = relance
        fenetreFin = System.currentTimeMillis() + silenceMs.coerceAtLeast(2000)
        limiteFenetre = fenetreFin + 20_000
        debutPhrase = ""
        relancesMuettes = 0
        relancesApresParole = 0
        demarrer(0)
    }

    /**
     * Relances du reconnaisseur dans la même fenêtre d'écoute. Chaque relance redémarre le reconnaisseur de Google, qui
     * joue son propre bip (surtout en Bluetooth / Android Auto, où il n'est pas toujours coupé) : au-delà de 2, on
     * referme plutôt que d'enchaîner les bips comme si JARVIS se déclenchait plusieurs fois.
     */
    private var relancesMuettes = 0
    private var relancesApresParole = 0

    /** [essai] : relances automatiques sur les erreurs transitoires (service vocal déconnecté…), puis repli sur la reconnaissance locale. */
    private fun demarrer(essai: Int) {
        main.post {
            annulerRelance()
            // Reconnaisseur précédent encore là (écoute annulée) : libéré maintenant, et ouverture 450 ms plus tard.
            if (recognizer != null) liberer()
            // Écoute JARVIS : aucun reconnaisseur de Google à laisser partir, le micro s'ouvre tout de suite.
            if (ecouteJarvis()) { ouvrir(essai); return@post }
            val attente = 450L - (System.currentTimeMillis() - detruitLe)
            if (attente > 0) {
                val r = Runnable { relanceEnAttente = null; ouvrir(essai) }
                relanceEnAttente = r
                main.postDelayed(r, attente)
            } else ouvrir(essai)
        }
    }

    private fun ouvrir(essai: Int) {
        run {
            if (!autorise()) {
                JournalVoix.noter(nom, "micro non ouvert : l'appli JARVIS a le micro")
                fenetreFin = 0; ecoute = false
                signalerEcoute(false)
                return@run
            }
            if (!ecouteJarvis() && !SpeechRecognizer.isRecognitionAvailable(ctx)) {
                fenetreFin = 0
                onFail("Reconnaissance vocale indisponible sur cet appareil (installer / activer Google).")
                return@run
            }
            stopSpeaking()
            // Un seul micro pour tout JARVIS : une autre écoute en cours (appli / service) est refermée.
            synchronized(instances) { instances.filter { it !== this }.toList() }.forEach { it.ceder(nom) }
            main.removeCallbacks(unmuteLater)
            main.removeCallbacks(finFenetre)
            setMuted(true)
            parole = false
            val moi = ++jeton
            ecoute = true
            dernierPartiel = ""
            sonsForts = 0
            debutParoleLe = 0L
            microJ?.arreter(false); microJ = null
            if (ecouteJarvis() && ouvrirJarvis(moi)) return@run
            val local = essai >= 3 && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
            val composant = if (local) null else moteur(ctx)
            val cle = if (local) "local" else composant?.flattenToString() ?: "defaut"
            // Reconnaisseur NEUF à chaque écoute : réutilisé (annulé puis relancé), celui de Google (com.google.android.as)
            // renvoyait souvent « erreur 7 » sur la phrase suivante.
            val reutilise = false
            val r = if (reutilise) recognizer!!.also { runCatching { it.cancel() } } else {
                if (recognizer != null) liberer()
                when {
                    local -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                    composant != null -> SpeechRecognizer.createSpeechRecognizer(ctx, composant)
                    else -> SpeechRecognizer.createSpeechRecognizer(ctx)
                }.also { recoCle = cle; recoCasse = false }
            }
            val segmente = !local && !segmentKo && Build.VERSION.SDK_INT >= 33 && ecouteLongueVoulue()
            val pauseMs = pauseFinMs().toLong()
            JournalVoix.noter(nom, "micro ouvert (reconnaissance ${if (local) "locale" else composant?.packageName ?: "par défaut"}${if (essai > 0) ", essai ${essai + 1}" else ""}${if (segmente) ", écoute longue" else ""})")
            recognizer = r
            ouvertLe = System.currentTimeMillis()
            val ecouteur = object : RecognitionListener {
                /** Un reconnaisseur envoie parfois une 2e erreur après son résultat (ou après une 1re erreur) : ignorée. */
                private var termine = false
                /** Écoute longue : morceaux de phrase déjà reconnus (Google les rend à chaque pause). */
                private val segments = StringBuilder()
                private fun actif() = moi == jeton && !termine
                private fun aParle() {
                    if (debutParoleLe == 0L) debutParoleLe = System.currentTimeMillis()
                    if (!parole) {
                        parole = true
                        main.removeCallbacks(finFenetre)
                        // Écoute longue : une voix détectée mais jamais transcrite (bruit) ne garde pas le micro ouvert.
                        if (segmente) main.postDelayed(sansTexte, 6000)
                    }
                }
                /** Texte partiel utilisable (tu as parlé, ce n'est pas l'écho de JARVIS), sans les morceaux déjà rendus. */
                private fun partielSur() = if (parole && dernierPartiel.isNotBlank() && !estEcho(dernierPartiel)) sansMorceaux(dernierPartiel) else ""
                /** Certains moteurs rendent en partiel TOUTE la phrase (morceaux déjà reçus compris) : pas de doublon. */
                private fun sansMorceaux(p: String): String {
                    val seg = segments.toString().trim()
                    if (seg.isEmpty()) return p
                    fun n(x: String) = java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD)
                        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
                    val np = n(p); val ns = n(seg)
                    if (np == ns || ns.endsWith(np)) return ""
                    if (!np.startsWith("$ns ")) return p
                    return p.trim().split(Regex("\\s+")).drop(seg.split(Regex("\\s+")).size).joinToString(" ")
                }
                /** Tout ce qui a été dit : début gardé + morceaux reconnus + [suite]. */
                private fun texte(suite: String = "") =
                    listOf(debutPhrase, segments.toString(), suite).filter { it.isNotBlank() }.joinToString(" ").replace(Regex("\\s+"), " ").trim()

                /** Phrase terminée : le micro se referme et le texte part. */
                private fun livrer(t: String, note: String = "entendu") {
                    termine = true
                    ecoute = false
                    debutPhrase = ""
                    fenetreFin = 0
                    main.removeCallbacks(finFenetre)
                    annulerGardes()
                    main.post { if (recognizer === r) liberer() }
                    main.postDelayed(unmuteLater, 900)
                    signalerEcoute(false)
                    JournalVoix.noter(nom, "$note : « ${t.take(240)} »")
                    onFinal(t)
                }

                /** Écoute longue : plus rien depuis la dernière parole (Google n'a pas signalé la fin) → la phrase part. */
                private val finSegments = Runnable {
                    if (!actif()) return@Runnable
                    val t = texte(partielSur())
                    if (t.isNotBlank()) livrer(t, "fin de phrase")
                }
                private fun relancerFinSegments() {
                    if (!segmente || (segments.isEmpty() && dernierPartiel.isBlank())) return
                    main.removeCallbacks(finSegments)
                    main.postDelayed(finSegments, pauseMs + 1500)
                }
                /** Écoute longue : voix détectée mais aucun mot reconnu en 6 s → traité comme « rien compris ». */
                private val sansTexte = Runnable {
                    if (actif() && segments.isEmpty() && dernierPartiel.isBlank()) { runCatching { r.cancel() }; onError(SpeechRecognizer.ERROR_NO_MATCH) }
                }
                /** Écoute longue : filet si Google ne signale jamais la fin (60 s au plus d'un seul tenant). */
                private val garde = Runnable {
                    if (!actif()) return@Runnable
                    val t = texte(partielSur())
                    if (t.isNotBlank()) livrer(t, "écoute longue arrêtée (60 s)") else { runCatching { r.cancel() }; onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
                }
                /** Écoute longue sans délai de conversation : personne n'a parlé en 8 s → on referme. */
                private val sansParole = Runnable { if (actif() && !parole) { runCatching { r.cancel() }; onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) } }
                fun armerGarde() {
                    main.postDelayed(garde, 60_000)
                    if (fenetreFin <= 0) main.postDelayed(sansParole, 8000)
                }
                private fun annulerGardes() { main.removeCallbacks(finSegments); main.removeCallbacks(garde); main.removeCallbacks(sansParole); main.removeCallbacks(sansTexte) }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() { if (actif()) aParle() }
                override fun onRmsChanged(rmsdB: Float) {
                    if (!actif()) return
                    Presence.microDb(rmsdB)
                    // Voix soutenue (plusieurs mesures fortes d'affilée) : tu parles, même si Google ne l'a pas encore dit.
                    if (rmsdB > 6f) {
                        if (++sonsForts >= 3) { dernierSon = System.currentTimeMillis(); relancerFinSegments() }
                    } else sonsForts = 0
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    if (!actif()) return
                    termine = true
                    JournalVoix.noter(nom, "reconnaissance : erreur $error${if (parole) " (après parole)" else ""}")
                    ecoute = false
                    main.removeCallbacks(finFenetre)
                    annulerGardes()
                    // Écoute longue refusée d'emblée par ce moteur (erreur client / langue / support) : mode normal ensuite.
                    if (segmente && !parole && error in setOf(5, 12, 13, 14) && System.currentTimeMillis() - ouvertLe < 1500) {
                        segmentKo = true
                        JournalVoix.noter(nom, "écoute longue non prise en charge par ce moteur : écoute normale")
                    }
                    // 11 = service de reconnaissance déconnecté, 5 = erreur client, 10 = trop de requêtes, 8 = occupé : on recrée et on relance
                    // (2 essais au plus, espacés : un reconnaisseur recréé trop vite répond « occupé » et rebipe.)
                    if (error in setOf(11, 5, 10, 8, SpeechRecognizer.ERROR_SERVER)) recoCasse = true
                    // Reconnaisseur libéré tout de suite (le service se détache pendant l'attente avant la relance).
                    main.post { if (recognizer === r) liberer() }
                    if (error in setOf(11, 5, 10, 8, SpeechRecognizer.ERROR_SERVER) && essai < 4) {
                        // Coupure EN PLEIN MILIEU de ta phrase (service déconnecté…) : ce qui a déjà été dit est gardé et la
                        // suite y sera ajoutée — avant, la relance repartait de zéro et le début de la phrase était perdu.
                        val dit = texte(partielSur())
                        if (dit.isNotBlank()) {
                            debutPhrase = dit
                            fenetreFin = maxOf(fenetreFin, System.currentTimeMillis() + 4000)
                            limiteFenetre = maxOf(limiteFenetre, fenetreFin + 8000)
                            JournalVoix.noter(nom, "début de phrase gardé : « ${dit.take(120)} »")
                        }
                        // « Occupé » (8) : l'écoute précédente n'est pas encore lâchée → plus longue attente. 11 juste après
                        // l'ouverture : service pas encore prêt → nouvel essai après sa libération (pas de fin de conversation).
                        val delai = when (error) { 8 -> 900L * (essai + 1); 11 -> if (System.currentTimeMillis() - ouvertLe < 600) 500L else 700L * (essai + 1); else -> 800L * (essai + 1) }
                        val relance = Runnable { relanceEnAttente = null; if (moi == jeton) demarrer(essai + 1) }
                        relanceEnAttente = relance
                        main.postDelayed(relance, delai)
                        return
                    }
                    val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    // Écoute longue : tu as parlé mais Google n'a rendu AUCUN mot (ni partiel ni morceau) → ce moteur ne la
                    // gère pas (cas du Xiaomi). Elle est coupée pour de bon et l'écoute repart aussitôt en mode normal.
                    if (segmente && silence && parole && segments.isEmpty() && dernierPartiel.isBlank() && debutPhrase.isBlank()) {
                        segmentKo = true
                        (ctx.applicationContext as? fr.dd.jarvis.JarvisApp)?.store?.ecouteLongue?.set(false)
                        JournalVoix.noter(nom, "écoute longue : Google ne rend aucun texte sur ce téléphone → désactivée, écoute normale")
                        demarrer(essai)
                        return
                    }
                    // Quelque chose a déjà été dit (début gardé, morceaux, texte partiel) : rien ne se perd, la phrase part.
                    val dit = texte(partielSur())
                    if (dit.isNotBlank() && (silence || debutPhrase.isNotBlank() || segments.isNotBlank())) {
                        termine = false
                        livrer(dit, if (debutPhrase.isNotBlank() || segments.isNotBlank()) "entendu (phrase reconstituée)" else "entendu (texte partiel conservé)")
                        return
                    }
                    if (fenetreFin > 0 && silence) {
                        // Tu as parlé mais rien n'a été compris : une nouvelle écoute complète (8 s au moins), une fois.
                        if (parole && relancesApresParole++ < 2) {
                            fenetreFin = maxOf(fenetreFin, System.currentTimeMillis() + 8000)
                            demarrer(0); return
                        }
                        // Rien de compris (silence, bruit, écho de la voix de JARVIS) avant la fin du délai de conversation :
                        // une relance discrète s'il reste vraiment du temps, sinon la conversation se referme.
                        if (System.currentTimeMillis() < fenetreFin - 1500 && relancesMuettes++ < 2) { demarrer(0); return }
                        if (!parole) { finirSilence(); return }
                    }
                    fenetreFin = 0
                    main.postDelayed(unmuteLater, 900)
                    signalerEcoute(false)
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
                    val brut = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (brut.isNotBlank() && estEcho(brut) && debutPhrase.isBlank() && segments.isEmpty()) {
                        termine = true
                        ecoute = false
                        main.post { if (recognizer === r) liberer() }
                        JournalVoix.noter(nom, "écho de ma propre voix ignoré : « ${brut.take(120)} »")
                        // Jarvis s'est réentendu (voix renvoyée par l'autoradio) : on ignore et on continue d'écouter.
                        main.removeCallbacks(finFenetre)
                        if (fenetreFin > 0 && System.currentTimeMillis() < fenetreFin - 400 && relancesMuettes++ < 2) { onPartial(""); demarrer(0); return }
                        fenetreFin = 0
                        main.postDelayed(unmuteLater, 900)
                        signalerEcoute(false)
                        onFail("")
                        return
                    }
                    // Moteur qui rend la phrase entière à la fin en plus des morceaux : on ne garde que la phrase entière.
                    val t = if (segments.isNotEmpty() && sansMorceaux(brut) != brut) {
                        val reste = sansMorceaux(brut); texte(reste)
                    } else texte(brut)
                    // Google coupe souvent à une simple pause (« lance la création de… ») : si la phrase est visiblement
                    // inachevée, le micro reste ouvert ; la suite est ajoutée, sinon la phrase part telle quelle.
                    if (brut.isNotBlank() && debutPhrase.isBlank() && suiteProbable(t)) {
                        termine = true
                        ecoute = false
                        main.post { if (recognizer === r) liberer() }
                        debutPhrase = t
                        JournalVoix.noter(nom, "phrase inachevée « ${t.take(120)} » : j'écoute la suite")
                        main.removeCallbacks(finFenetre)
                        annulerGardes()
                        fenetreFin = System.currentTimeMillis() + maxOf(2500L, pauseMs + 1500)
                        limiteFenetre = fenetreFin + 8000
                        onPartial(t)
                        demarrer(0)
                        return
                    }
                    if (t.isNotBlank()) { livrer(t); return }
                    termine = true
                    ecoute = false
                    main.post { if (recognizer === r) liberer() }
                    debutPhrase = ""
                    fenetreFin = 0
                    main.removeCallbacks(finFenetre)
                    main.postDelayed(unmuteLater, 900)
                    signalerEcoute(false)
                    onFail("")
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!actif()) return
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    // Un partiel qui n'est pas l'écho de JARVIS = tu parles : la fenêtre de silence ne peut plus refermer
                    // le micro (avant, seul le signal « début de parole » de Google comptait, et il arrive parfois en retard).
                    if (t.trim().length >= 2 && !estEcho(t)) {
                        dernierPartiel = t; dernierSon = System.currentTimeMillis()
                        aParle()
                        main.removeCallbacks(sansTexte)
                        relancerFinSegments()
                        val vu = texte(sansMorceaux(t))
                        Presence.entendu = vu
                        onPartial(vu)
                    }
                }

                /** Écoute longue : un morceau de phrase reconnu à une pause ; le micro reste ouvert pour la suite. */
                override fun onSegmentResults(segmentResults: Bundle) {
                    if (!actif()) return
                    val t = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                    if (t.isBlank() || estEcho(t)) return
                    aParle()
                    main.removeCallbacks(sansTexte)
                    if (!segments.endsWith(t)) { if (segments.isNotEmpty()) segments.append(' '); segments.append(t) }
                    dernierPartiel = ""
                    dernierSon = System.currentTimeMillis()
                    relancerFinSegments()
                    onPartial(texte())
                }

                /** Écoute longue : Google a entendu le silence de fin de phrase. */
                override fun onEndOfSegmentedSession() {
                    if (!actif()) return
                    val t = texte(partielSur())
                    if (t.isNotBlank()) {
                        // Visiblement inachevée malgré la pause : on laisse une chance à la suite.
                        if (debutPhrase.isBlank() && suiteProbable(t)) {
                            termine = true; ecoute = false
                            main.post { if (recognizer === r) liberer() }
                            annulerGardes()
                            debutPhrase = t
                            JournalVoix.noter(nom, "phrase inachevée « ${t.take(120)} » : j'écoute la suite")
                            fenetreFin = System.currentTimeMillis() + maxOf(2500L, pauseMs + 1500)
                            limiteFenetre = fenetreFin + 8000
                            demarrer(0)
                            return
                        }
                        livrer(t)
                    } else onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                }
            }
            r.setRecognitionListener(ecouteur)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Laisse le temps de réfléchir / faire des pauses avant de considérer la phrase terminée
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs * 4 / 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                // Android 13+ : écoute « longue » — Google rend un morceau à chaque pause SANS refermer le micro, et ne
                // termine qu'après le silence de fin de phrase choisi dans les réglages. Fini les phrases coupées en deux.
                if (segmente) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
            }
            r.startListening(i)
            signalerEcoute(true)
            val reste = fenetreFin - System.currentTimeMillis()
            if (fenetreFin > 0) main.postDelayed(finFenetre, reste.coerceAtLeast(500))
            if (segmente) ecouteur.armerGarde()
        }
    }

    // ================================================================= écoute JARVIS (micro tenu par JARVIS + Whisper)

    /**
     * Vocabulaire de JARVIS donné à Whisper (« Jarvis » bien écrit, « monte le son »…). Des mots isolés, jamais des
     * ordres : sur du bruit, Whisper recopie parfois son indice — une liste de mots est alors repérée et écartée.
     */
    private val INDICE_WHISPER = "Jarvis, télé, chaîne, monte, volume, déverrouille, sécurisé, Home Assistant, Hermès, Freebox, salon."
    private val MOTS_INDICE = INDICE_WHISPER.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 3 }.toSet()

    private var microJ: MicroJarvis? = null
    /** Écoute JARVIS impossible un moment (micro refusé, erreur) : Google prend le relais jusqu'à cette heure. */
    private var jarvisKoJusqua = 0L

    private fun appli() = ctx.applicationContext as? fr.dd.jarvis.JarvisApp

    /** Écoute JARVIS choisie et possible : Whisper sur Groq, ou à défaut le modèle français hors-ligne. */
    private fun ecouteJarvis(): Boolean {
        val a = appli() ?: return false
        if (a.store.moteurEcoute.value != "jarvis") return false
        if (System.currentTimeMillis() < jarvisKoJusqua) return false
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return a.llm.groq.disponible() || VoskModel.francais(ctx)
    }

    private fun ouvrirJarvis(moi: Int): Boolean {
        val m = MicroJarvis(ctx)
        val maintenant = System.currentTimeMillis()
        val attente = if (fenetreFin > 0) (fenetreFin - maintenant).coerceAtLeast(1500) else 8000L
        val pause = pauseFinMs()
        // Voix de JARVIS à peine terminée : sa fin (retard du Bluetooth / de l'autoradio) n'est pas prise pour toi.
        val depuisVoix = maintenant - finVoix
        val ignorer = if (depuisVoix in 0..1500) ((if (sortieExterne()) 700L else 250L) - depuisVoix).coerceAtLeast(0) else 0L
        // Android Auto : micro de la VOITURE si JARVIS y est ouvert ; casque / voiture Bluetooth : leur micro.
        val voiture = if (appli()?.enVoiture == true) fr.dd.jarvis.car.NavigationVoiture.microVoiture() else null
        val bt = if (voiture == null) entreeBluetooth() else null
        val ok = m.demarrer(attente, pause, object : MicroJarvis.Rappels {
            override fun niveau(db: Float) { main.post { if (moi == jeton) Presence.microDb(db) } }
            override fun debutParole() { main.post { if (moi == jeton) { parole = true; if (debutParoleLe == 0L) debutParoleLe = System.currentTimeMillis() } } }
            override fun partiel(texte: String) {
                main.post { if (moi == jeton && !estEcho(texte)) { dernierPartiel = texte; Presence.entendu = texte; onPartial(listOf(debutPhrase, texte).filter { it.isNotBlank() }.joinToString(" ")) } }
            }
            override fun fin(f: MicroJarvis.Fin) { main.post { if (moi == jeton) finJarvis(moi, f) } }
        }, ignorer, entree = bt, voiture = voiture)
        if (!ok) {
            jarvisKoJusqua = maintenant + 60_000
            JournalVoix.noter(nom, "micro indisponible pour l'écoute JARVIS : reconnaissance Google pour l'instant")
            return false
        }
        microJ = m
        setMuted(false)
        val groq = appli()?.llm?.groq?.disponible() == true
        val quel = if (voiture != null) "micro de la voiture" else m.source
        JournalVoix.noter(nom, "micro ouvert ($quel, écoute JARVIS${if (groq) " + Whisper" else ", hors-ligne"}, fin après ${String.format(Locale.FRANCE, "%.1f", pause / 1000.0)} s de pause)")
        signalerEcoute(true)
        return true
    }

    private fun finJarvis(moi: Int, f: MicroJarvis.Fin) {
        microJ = null
        when (f) {
            // Refermée par fermer() / ceder() / bouton micro sans parole : déjà signalé.
            is MicroJarvis.Fin.Annulee -> { ecoute = false }
            is MicroJarvis.Fin.Silence -> {
                ecoute = false
                if (livrerDebut()) return
                JournalVoix.noter(nom, "personne n'a parlé")
                if (fenetreFin > 0) finirSilence() else { signalerEcoute(false); onFail("") }
            }
            is MicroJarvis.Fin.Erreur -> {
                ecoute = false
                JournalVoix.noter(nom, "écoute JARVIS : ${f.message} → reconnaissance Google pour 1 min")
                jarvisKoJusqua = System.currentTimeMillis() + 60_000
                demarrer(0)
            }
            is MicroJarvis.Fin.Phrase -> {
                ecoute = false
                signalerEcoute(false)
                JournalVoix.noter(nom, "phrase enregistrée (${String.format(Locale.FRANCE, "%.1f", f.dureeMs / 1000.0)} s)${if (f.texteLocal.isNotBlank()) " : « ${f.texteLocal.take(120)} »" else ""}")
                onPartial(listOf(debutPhrase, f.texteLocal).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "…" })
                transcrireJarvis(moi, f)
            }
        }
    }

    /** Whisper (Groq) transcrit la phrase ; sans réseau ni clé, le texte du modèle hors-ligne est utilisé. */
    private fun transcrireJarvis(moi: Int, f: MicroJarvis.Fin.Phrase) {
        val a = appli()
        // Réécoute automatique et AUCUN mot sûr pour le modèle hors-ligne : ce n'est pas toi près du téléphone mais la
        // télé ou des gens autour. Rien n'est envoyé à Whisper ; l'écoute continue ou se referme comme un silence.
        if (relanceAuto && f.voskActif && f.motsSurs == 0) {
            JournalVoix.noter(nom, "voix lointaine ignorée (télé, autres personnes)${if (f.texteLocal.isNotBlank()) " : « ${f.texteLocal.take(60)} »" else ""}")
            livrerJarvis("", "voix lointaine", true, fond = true)
            return
        }
        Thread({
            var texte = ""
            var source = "hors-ligne"
            var whisperOk = false
            // Moins de ¼ s de vraie voix (claquement, toux, bruit) : pas d'appel à Whisper, qui « entend » alors des
            // phrases toutes faites (« Merci. », sous-titres…) — dont « merci », qui fermerait la conversation.
            val motsLocaux = f.texteLocal.split(Regex("\\s+")).count { it.isNotBlank() }
            if (a != null && a.llm.groq.disponible() && (f.voixMs >= 250 || motsLocaux >= 2)) {
                val debut = System.currentTimeMillis()
                try {
                    val brut = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { a.llm.groq.transcrire(fr.dd.jarvis.car.Pcm.wav(f.pcm), prompt = INDICE_WHISPER) } }
                    texte = fr.dd.jarvis.car.Pcm.nettoyerTranscription(brut)
                    // Whisper a recopié son indice (bruit) : écarté.
                    val motsW = texte.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 3 }
                    if (motsW.size >= 3 && motsW.count { it in MOTS_INDICE } >= motsW.size * 0.7) {
                        JournalVoix.noter(nom, "texte inventé par Whisper ignoré : « ${texte.take(60)} »")
                        texte = ""
                    }
                    // Bruit bref que le modèle hors-ligne n'a pas compris du tout, mais que Whisper « traduit » en un mot
                    // (« Merci. », « Ah. ») : ignoré.
                    if (motsLocaux == 0 && VoskModel.francais(ctx) && f.voixMs < 600 && texte.split(' ').size <= 2) {
                        JournalVoix.noter(nom, "bruit bref ignoré (Whisper : « ${texte.take(40)} »)")
                        texte = ""
                    }
                    whisperOk = true
                    source = "Whisper ${System.currentTimeMillis() - debut} ms"
                } catch (e: Throwable) {
                    JournalVoix.noter(nom, "Whisper indisponible (${(e.message ?: e.javaClass.simpleName).take(90)}) : texte hors-ligne")
                }
            }
            if (!whisperOk) texte = if (f.voixMs >= 250 || motsLocaux >= 2) f.texteLocal else ""
            else if (f.voskActif) {
                // Recoupement avec le texte hors-ligne (qui n'entend bien que la voix proche du téléphone) : mauvaise
                // langue, voix d'une autre personne ou de la télé, phrases de la télé collées à ta demande.
                val (t, note) = accorder(texte, f.texteLocal)
                if (note != null) JournalVoix.noter(nom, "$note : « ${texte.take(90)} » → « ${t.take(90)} »")
                texte = t
                // Réécoute automatique : rien de compris par le modèle hors-ligne mais toute une phrase pour Whisper =
                // quelqu'un d'autre parlait (télé, personnes autour) ; pas toi qui t'adresses à JARVIS.
                val motsW = texte.split(Regex("\\s+")).count { it.length >= 2 }
                if (relanceAuto && motsLocaux <= 1 && motsW >= 4) {
                    JournalVoix.noter(nom, "voix lointaine ignorée (télé, autres personnes) : « ${texte.take(90)} »")
                    main.post { if (moi == jeton) livrerJarvis("", "voix lointaine", true, fond = true) }
                    return@Thread
                }
            }
            main.post { if (moi == jeton) livrerJarvis(texte, source, f.dureeMs < 4000) }
        }, "jarvis-whisper").apply { isDaemon = true; start() }
    }

    private fun livrerJarvis(brut: String, source: String, courte: Boolean, fond: Boolean = false) {
        val t = listOf(debutPhrase, brut).filter { it.isNotBlank() }.joinToString(" ").trim()
        debutPhrase = ""
        if (fond && t.isBlank()) {
            // Personne ne s'adressait à JARVIS : la fenêtre de conversation n'est PAS prolongée ; on écoute jusqu'à sa
            // fin, puis la conversation se referme d'elle-même (avant : relances sans fin avec la télé allumée).
            onPartial("")
            if (fenetreFin > System.currentTimeMillis() + 1500 && relancesMuettes++ < 3) demarrer(0) else finirSilence()
            return
        }
        if (t.isBlank()) {
            JournalVoix.noter(nom, "phrase non comprise ($source)")
            if (fenetreFin > 0 && relancesApresParole++ < 2) {
                fenetreFin = maxOf(fenetreFin, System.currentTimeMillis() + 8000)
                demarrer(0); return
            }
            fenetreFin = 0
            onFail("")
            return
        }
        // L'écoute JARVIS n'ouvre le micro qu'une fois la voix finie : un écho n'est possible que par le Bluetooth /
        // l'autoradio (retard), et c'est alors un court morceau au tout début — jamais une phrase de plusieurs secondes.
        if (courte && sortieExterne() && estEcho(t)) {
            JournalVoix.noter(nom, "écho de ma propre voix ignoré : « ${t.take(120)} »")
            if (fenetreFin > 0 && System.currentTimeMillis() < fenetreFin - 400 && relancesMuettes++ < 2) { onPartial(""); demarrer(0); return }
            fenetreFin = 0
            onFail("")
            return
        }
        fenetreFin = 0
        JournalVoix.noter(nom, "entendu ($source) : « ${t.take(240)} »")
        onFinal(t)
    }

    /** Écoute en cours de cette instance (micro du reconnaisseur ouvert). */
    @Volatile private var ecoute = false

    /** Une autre partie de JARVIS prend le micro : on referme notre écoute (comme un silence, sans relance). */
    private fun ceder(a: String) {
        main.post {
            if (relanceEnAttente != null) { annulerRelance(); jeton++ }
            if (!ecoute) return@post
            ecoute = false
            debutPhrase = ""
            JournalVoix.noter(nom, "écoute refermée : le micro passe à « $a »")
            jeton++
            microJ?.arreter(false); microJ = null
            fenetreFin = 0
            main.removeCallbacks(finFenetre)
            liberer()
            signalerEcoute(false)
            onSilence()
        }
    }

    /**
     * Ferme l'écoute pour de bon (retour à l'attente de « Jarvis ») : plus aucun rappel de cette écoute n'est traité et
     * aucune relance programmée ne rouvrira le micro — sinon il se rouvrait après le bip de fin, pendant que le
     * détecteur « Jarvis » était réarmé (micro « occupé », bips en plus).
     */
    fun fermer() {
        main.post {
            debutPhrase = ""
            annulerRelance()
            jeton++
            microJ?.arreter(false); microJ = null
            val etait = ecoute
            ecoute = false
            fenetreFin = 0
            main.removeCallbacks(finFenetre)
            liberer()
            if (etait) signalerEcoute(false)
            main.removeCallbacks(unmuteLater); main.postDelayed(unmuteLater, 900)
        }
    }

    /** Coupe la réponse parlée en cours en laissant l'appelant reprendre la main (sa suite est appelée). */
    fun couperParole() = main.post { paroleEnCours?.arreter(sansSuite = false) }

    fun stopListening() {
        main.post {
            annulerRelance()
            ecoute = false
            fenetreFin = 0
            main.removeCallbacks(finFenetre)
            recognizer?.stopListening()
            // Écoute JARVIS : ce qui a déjà été dit est gardé et transcrit.
            microJ?.arreter(true)
            signalerEcoute(false)
        }
    }

    // ============================================================================================ sons

    /** Bip sur le haut-parleur du téléphone (son système de l'assistant). */
    private val attrBip = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /**
     * Petit carillon : deux notes montantes (« je t'écoute ») ou descendantes (« fin de la conversation »), puis [puis].
     * Il sort là où sort la voix de JARVIS : haut-parleurs de la voiture (Android Auto), casque / enceinte Bluetooth, ou
     * liaison mains-libres du casque quand son micro est utilisé.
     */
    fun carillon(montant: Boolean = true, puis: () -> Unit = {}) {
        JournalVoix.noter(nom, "bip ${if (montant) "montant (je t'écoute)" else "descendant (fin)"}")
        main.post {
            Thread {
                var tonalite: ToneGenerator? = null
                var focusBip: AudioFocusRequest? = null
                try {
                    // Android Auto utilise son canal média : ne jamais tenter d'ouvrir le profil mains-libres SCO ici.
                    // Hors projection, on conserve le micro Bluetooth si une conversation l'a explicitement demandé.
                    val voiture = sortieVoitureForcee || appli()?.enVoiture == true
                    val sco = if (voiture) false else attendreMicroBluetooth()
                    val externe = sco || sortieBluetooth() || voiture
                    val attrs = when {
                        sco -> attrAppel
                        externe -> attrBluetooth
                        else -> attrBip
                    }
                    if (externe && !sessionActive && Build.VERSION.SDK_INT >= 26) {
                        focusBip = runCatching {
                            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                                .setAudioAttributes(attrs).setOnAudioFocusChangeListener({ }, main).build()
                                .also { am.requestAudioFocus(it) }
                        }.getOrNull()
                    }
                    // ToneGenerator est plus fiable que l'AudioTrack statique sur certaines ROM : il utilise
                    // directement le flux média du téléphone / de la projection et ne dépend pas d'un buffer PCM.
                    val stream = when { sco -> AudioManager.STREAM_VOICE_CALL; voiture || sortieBluetooth() -> AudioManager.STREAM_MUSIC; else -> AudioManager.STREAM_NOTIFICATION }
                    tonalite = ToneGenerator(stream, 85)
                    val premier = if (montant) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK
                    val second = if (montant) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP
                    if (!tonalite.startTone(premier, 130)) error("ToneGenerator n'a pas démarré le premier bip")
                    Thread.sleep(170)
                    if (!tonalite.startTone(second, 160)) error("ToneGenerator n'a pas démarré le second bip")
                    Thread.sleep(210)
                    JournalVoix.noter(nom, "bip diffusé sur ${if (voiture) "le flux média Android Auto" else if (sco) "la liaison mains-libres" else if (externe) "la sortie externe" else "le téléphone"}")
                } catch (e: Throwable) {
                    JournalVoix.noter(nom, "échec lecture bip : ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    runCatching { tonalite?.release() }
                    focusBip?.let { f -> runCatching { am.abandonAudioFocusRequest(f) } }
                }
                main.post(puis)
            }.start()
        }
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
        // Casque / voiture Bluetooth : leur micro (liaison mains-libres) pour toute la conversation.
        ouvrirMicroBluetooth()
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            val attrs = if (sortieBluetooth()) attrBluetooth else attrAssistant
            val demande = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusSessionListener, main)
                .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true).build()
            focusSession = demande
            val resultat = am.requestAudioFocus(demande)
            JournalVoix.noter(nom, "focus audio conversation : $resultat")
            if (resultat != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                // Certaines ROM / Android Auto refusent EXCLUSIVE mais accordent MAY_DUCK :
                // le bip et la voix doivent tout de même pouvoir sortir sur le canal média voiture.
                val secours = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusSessionListener, main)
                    .setAcceptsDelayedFocusGain(false).build()
                if (am.requestAudioFocus(secours) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusSession = secours
                JournalVoix.noter(nom, "focus audio de secours : ${if (focusSession === secours) "accordé" else "refusé"}")
            }
        }
        // Certains lecteurs (et autoradios) ignorent le focus : vraie commande PAUSE, reprise ciblée en fin de session.
        mediaPauseeSession = fr.dd.jarvis.service.JarvisNotificationListener.pauseMediaPourVoix(ctx)
    }

    /** Fin de conversation : rend le focus et relance la musique que Jarvis avait mise en pause (après un court délai). */
    fun sessionFin() = main.post {
        if (!sessionActive) return@post
        sessionActive = false
        fermerMicroBluetooth()
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

    // ------------------------------------------------------------------ micro Bluetooth (casque, voiture sans Android Auto)

    /** Appareil qui joue le son des médias en ce moment (casque, autoradio…), null si inconnu. */
    private fun sortieMedia(): AudioDeviceInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) am.getAudioDevicesForAttributes(attrBluetooth).firstOrNull()
        else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
    }.getOrNull()

    /**
     * Micro Bluetooth à utiliser via le profil mains-libres, uniquement hors Android Auto :
     *  - casque / voiture Bluetooth seule : micro du même appareil que la sortie média ;
     *  - Android Auto : pas de bascule HFP/SCO. Elle déclenche l'écran et le routage « appel » de
     *    certains autoradios ; le mode média garde la sortie de projection et utilise le micro non-SCO.
     * Le micro CarAudioRecord reste réservé au mode Car App lorsqu'il est disponible.
     */
    private fun appareilMicroBluetooth(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 31) return null
        val app = appli() ?: return null
        // Android Auto doit rester sur le canal média de projection. Ouvrir le profil HFP/SCO
        // pour récupérer le micro de la voiture bascule l'autoradio en mode appel et peut rerouter
        // la fin du TTS vers le téléphone. Le micro CarAudioRecord est utilisé par le mode Car App ;
        // dans le mode média Android Auto, on garde le micro non-SCO et la sortie média.
        if (app.enVoiture) {
            if (app.store.microVoitureBt.value) JournalVoix.noter(nom,
                "Android Auto : liaison micro HFP/SCO ignorée pour éviter le mode appel ; canal média conservé")
            return null
        }
        if (!app.store.microBluetooth.value) return null
        if (am.mode != AudioManager.MODE_NORMAL) return null                    // appel en cours : on n'y touche pas
        val comm = runCatching { am.availableCommunicationDevices }.getOrNull() ?: return null
        val sortie = sortieMedia() ?: return null
        fun meme(d: AudioDeviceInfo) = (sortie.address.isNotBlank() && d.address == sortie.address) ||
            (d.productName.toString().isNotBlank() && d.productName.toString() == sortie.productName.toString())
        return when (sortie.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> comm.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && meme(it) }
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> comm.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET && meme(it) }
            else -> null
        }
    }

    private val verifMicroBt = object : Runnable {
        override fun run() {
            if (microBtPar !== this@VoiceManager) return
            // Filet : conversation terminée sans que la liaison ait été rendue.
            if (!sessionActive) fermerMicroBluetooth() else main.postDelayed(this, 60_000)
        }
    }

    /** Début de conversation : ouvre la liaison mains-libres d'un casque / d'une voiture Bluetooth hors Android Auto. */
    private fun ouvrirMicroBluetooth() {
        if (Build.VERSION.SDK_INT < 31 || microBt != null) return
        val d = appareilMicroBluetooth() ?: return
        if (runCatching { am.setCommunicationDevice(d) }.getOrDefault(false)) {
            microBt = d; microBtPar = this
            JournalVoix.noter(nom, "micro Bluetooth demandé : ${d.productName}")
            main.removeCallbacks(verifMicroBt); main.postDelayed(verifMicroBt, 60_000)
        } else JournalVoix.noter(nom, "micro Bluetooth refusé par Android : micro du téléphone")
    }

    private fun fermerMicroBluetooth(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 31) return
        val d = microBt ?: return
        if (!force && microBtPar !== this) return
        microBt = null; microBtPar = null
        main.removeCallbacks(verifMicroBt)
        runCatching { am.clearCommunicationDevice() }
        JournalVoix.noter(nom, "micro Bluetooth rendu (${d.productName})")
    }

    /** Liaison mains-libres vraiment établie : Android ne l'annonce qu'une fois le son et le micro passés par l'appareil. */
    private fun microBluetoothPret(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val d = microBt ?: return false
        return runCatching { am.communicationDevice?.type == d.type }.getOrDefault(false)
    }

    /** Attend (hors fil principal) la liaison ; trop lente → abandon, retour au micro du téléphone et au son A2DP. */
    private fun attendreMicroBluetooth(): Boolean {
        if (microBt == null) return false
        // Une voiture met plus de temps qu'un casque à ouvrir sa liaison « appel » (et à basculer ses haut-parleurs).
        val maxMs = if (appli()?.enVoiture == true) 4500L else 2500L
        val limite = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < limite) {
            if (microBluetoothPret()) { Thread.sleep(200); return true }
            Thread.sleep(50)
        }
        JournalVoix.noter(nom, "liaison mains-libres Bluetooth trop lente : micro du téléphone")
        main.post { fermerMicroBluetooth(force = true) }
        return false
    }

    /** Entrée micro Bluetooth à préférer pour l'enregistrement (liaison établie), sinon null. */
    private fun entreeBluetooth(): AudioDeviceInfo? {
        if (!microBluetoothPret()) return null
        val d = microBt ?: return null
        val type = if (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) AudioDeviceInfo.TYPE_BLE_HEADSET else AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        return runCatching {
            val entrees = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type == type }
            entrees.firstOrNull { d.address.isNotBlank() && it.address == d.address } ?: entrees.firstOrNull()
        }.getOrNull()
    }

    // ------------------------------------------------------------------ anti-écho (voix de Jarvis réentendue)

    @Volatile private var derniereVoix = ""
    @Volatile private var finVoix = 0L

    private fun mots(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.length >= 2 }

    /** Le texte reconnu reprend la réponse que Jarvis vient de dire (écho de l'autoradio / du haut-parleur). */
    internal fun estEcho(entendu: String): Boolean {
        if (derniereVoix.isBlank()) return false
        // Un écho arrive dans les secondes qui suivent la voix de JARVIS (retard du Bluetooth / de l'autoradio compris).
        // Au-delà, c'est toi qui parles — même si tu reprends ses mots (« oui, crée la facture pour Dupont ») : avant,
        // ta réponse était jetée comme un écho pendant 20 s et JARVIS « n'entendait pas ».
        val debut = if (debutParoleLe > 0) debutParoleLe else System.currentTimeMillis()
        if (debut - finVoix > (if (sortieExterne()) 5000 else 2500)) return false
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
        return sortieVoitureForcee || (ctx.applicationContext as? fr.dd.jarvis.JarvisApp)?.enVoiture == true ||
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
        // Android Auto doit lire toute la réponse : la limite courte de la voix du téléphone ne s'applique pas en voiture.
        parole(limite = if (sortieVoitureForcee) Int.MAX_VALUE else 3500, onDone = onDone).apply { ajouter(text); finir() }
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
        private var volume = 1f
        @Volatile private var piste: AudioTrack? = null
        private var formatPiste = 0 to 0
        private val dits = StringBuilder()
        /** Enveloppe de l'audio écrit dans la piste (un niveau toutes les 20 ms) : la bouche du dragon suit la voix. */
        private val enveloppe = java.util.ArrayList<Float>()
        @Volatile private var fenetre = 480

        private fun niveauPiste(): Float {
            val t = piste ?: return 0f
            val pos = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
            val i = (pos / fenetre.coerceAtLeast(1)).toInt()
            return synchronized(enveloppe) { enveloppe.getOrNull(i) ?: 0f }
        }

        /** Ajoute du texte (morceau de réponse en cours d'écriture). */
        fun ajouter(texte0: String) {
            if (fini.get() || ferme || texte0.isEmpty() || finOral) return
            // Après la marque de fin de la partie dite, le texte n'est plus qu'affiché.
            val i = texte0.indexOf(TexteParle.FIN_ORAL)
            val texte = if (i >= 0) { finOral = true; texte0.substring(0, i) } else texte0
            if (texte.isEmpty()) return
            synchronized(tampon) { recu.append(texte); tampon.append(texte); extraire(attendreBloc = occupe, toutDire = false) }
        }

        @Volatile private var finOral = false

        /**
         * Fin du texte. [texteFinal] = réponse complète (si elle diffère de ce qui a été reçu : repli, message
         * d'arrière-plan… on dit ce qui manque, ou la réponse entière si rien n'a été reçu).
         */
        fun finir(texteFinal0: String? = null) {
            if (fini.get()) return
            val texteFinal = texteFinal0?.let { TexteParle.oral(it) }
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
            Presence.parole(false); Presence.sousTitre = ""
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
            // Micro du casque Bluetooth en service : la voix passe par la même liaison mains-libres (l'A2DP y est suspendu).
            attrsVoix = when { appel -> attrAppel; microBluetoothPret() -> attrAppel; externe -> attrBluetooth; else -> attrAssistant }
            if (!appel && am.mode == AudioManager.MODE_NORMAL && !sessionActive) demanderFocusReponse(attrsVoix)
            // Hors session : focus + vraie commande PAUSE pour les lecteurs qui ignorent le focus, reprise après la réponse.
            mediaPauseeParJarvis = !sessionActive && externe && fr.dd.jarvis.service.JarvisNotificationListener.pauseMediaPourVoix(ctx)
            runCatching { tts?.setAudioAttributes(attrsVoix) }
            volume = (ctx.applicationContext as? fr.dd.jarvis.JarvisApp)?.let { runCatching { fr.dd.jarvis.device.Contexte.volumeVoix(it) }.getOrDefault(1f) } ?: 1f
            // Voix Android changée dans les réglages : appliquée sans redémarrer.
            // (et personnage de voix : genre, hauteur et débit de la voix Android de repli)
            VoixAndroid.appliquerSiChange(ctx, tts)
        }

        /** La voix sort vraiment : l'écoute de coupure démarre (ses 400 premières ms servent à mesurer l'écho). */
        private fun voixLancee() {
            Presence.parole(true)
            if (coupe) main.post {
                if (!fini.get() && barge == null && coupureAutorisee()) barge = BargeIn { main.post { if (!fini.get() && coupureAutorisee()) { JournalVoix.noter(nom, "coupure de parole : voix entendue pendant la réponse"); arreter(sansSuite = false) } } }.also { it.start() }
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
                    Presence.sousTitre = morceau
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
            val voiture = sortieVoitureForcee || appli()?.enVoiture == true
            if (piste == null || formatPiste != a.frequence to a.canaux) {
                vider()
                val min = AudioTrack.getMinBufferSize(a.frequence, canal, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
                piste = AudioTrack.Builder().setAudioAttributes(attrsVoix)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(a.frequence).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(canal).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(min * 4).build()
                if (voiture && Build.VERSION.SDK_INT >= 33) {
                    val sortie = runCatching { am.getAudioDevicesForAttributes(attrsVoix).firstOrNull() }.getOrNull()
                    if (sortie != null) runCatching { piste?.preferredDevice = sortie }
                    JournalVoix.noter(nom, "sortie voix voiture : ${sortie?.productName ?: "routage média Android"}")
                }
                formatPiste = a.frequence to a.canaux
                ecrits = 0L
                fenetre = (a.frequence / 50).coerceAtLeast(1)
                synchronized(enveloppe) { enveloppe.clear() }
                Presence.brancher { niveauPiste() }
                runCatching { piste?.setVolume(volume) }
                piste?.play()
                // L'autoradio Android Auto met parfois plusieurs centaines de ms à réactiver ses amplis
                // après un silence. Injecter un pré-roll muet DANS la piste avant le premier phonème :
                // le délai est consommé par l'autoradio, pas par le texte prononcé.
                if (voiture) {
                    val silence = ByteArray((a.frequence * 2L * a.canaux * 900L / 1000L).toInt())
                    var offset = 0
                    while (offset < silence.size && !fini.get()) {
                        val nSilence = piste?.write(silence, offset, minOf(8192, silence.size - offset)) ?: 0
                        if (nSilence <= 0) break
                        offset += nSilence
                    }
                    ecrits += offset / (2L * a.canaux)
                    JournalVoix.noter(nom, "pré-ouverture sortie voiture : ${offset / (a.frequence * 2L * a.canaux / 1000L).coerceAtLeast(1)} ms")
                }
                voixLancee()
            }
            val t = piste ?: return
            // Fenêtres alignées sur la position de lecture (l'enveloppe continue celle des morceaux précédents).
            val env = Presence.enveloppe(a.pcm, a.canaux, fenetre)
            synchronized(enveloppe) {
                val attendu = (ecrits / fenetre).toInt()
                while (enveloppe.size < attendu) enveloppe.add(0f)
                while (enveloppe.size > attendu) enveloppe.removeAt(enveloppe.size - 1)
                env.forEach { enveloppe.add(it) }
            }
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
            // Voix Android tout juste créée (voiture, première phrase) : on lui laisse le temps de s'initialiser.
            val attente = System.currentTimeMillis() + 3000
            while (!ttsReady && !fini.get() && System.currentTimeMillis() < attente) Thread.sleep(50)
            if (!ttsReady) return
            val id = UUID.randomUUID().toString()
            val latch = java.util.concurrent.CountDownLatch(1)
            finsAndroid[id] = latch
            var premier = true
            debutsAndroid[id] = { if (premier) { premier = false; voixLancee() } }
            // Voix Android : l'audio n'est pas accessible, la bouche suit un rythme syllabique simulé.
            val debutSim = System.nanoTime()
            Presence.brancher { Presence.simule((System.nanoTime() - debutSim) / 1e9) }
            val params = if (volume < 1f) Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) } else null
            // Même avec la voix Android de secours, l'autoradio doit avoir le temps de réouvrir son canal
            // de projection avant le premier phonème (sinon les premières syllabes disparaissent).
            if (sortieVoitureForcee || appli()?.enVoiture == true) {
                runCatching { t.playSilentUtterance(900L, TextToSpeech.QUEUE_ADD, "jarvis-auto-warmup-$id") }
            }
            val ok = runCatching { t.speak(morceau, TextToSpeech.QUEUE_ADD, params, id) }.getOrDefault(TextToSpeech.ERROR)
            if (ok == TextToSpeech.ERROR) { finsAndroid.remove(id); debutsAndroid.remove(id); return }
            latch.await(15_000L + morceau.length * 120L, java.util.concurrent.TimeUnit.MILLISECONDS)
            debutsAndroid.remove(id)
        }

        private fun terminerApres() {
            Presence.parole(false); Presence.sousTitre = ""
            val b = barge; barge = null
            finVoix = System.currentTimeMillis()
            val suite = Runnable {
                main.post {
                    if (paroleEnCours === this) paroleEnCours = null
                    val etaitAppel = appelActif
                    sortirModeAppel()
                    if (mediaPauseeParJarvis) fr.dd.jarvis.service.JarvisNotificationListener.reprendreMediaApresVoix()
                    mediaPauseeParJarvis = false
                    // UN SEUL micro à la fois : l'écoute de la suite ne s'ouvre qu'une fois le micro de la « coupure de
                    // parole » rendu et le son revenu du mode appel (annulation d'écho). Avant, la reconnaissance de Google
                    // démarrait sur un micro encore pris ou en reconfiguration → « erreur 7 (après parole) » puis fin.
                    // Bluetooth / autoradio : le son sort avec du retard (tampon A2DP) : on attend la fin réelle de la phrase.
                    val delai = when { externe -> 1000L; etaitAppel -> 600L; b != null -> 300L; else -> 150L }
                    main.postDelayed({ finVoix = System.currentTimeMillis(); onDone() }, delai)
                }
            }
            if (b != null) Thread({ b.stopEtAttendre(700); suite.run() }, "jarvis-fin-voix").start() else suite.run()
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
        synchronized(instances) { instances.remove(this) }
        sessionFin()
        main.post { fermerMicroBluetooth() }
        main.post { main.removeCallbacks(unmuteLater); main.removeCallbacks(finFenetre); setMuted(false) }
        main.post {
            annulerRelance()
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

    /** Texte prêt à dire : noms en capitales lus comme des mots, ponctuation et symboles jamais prononcés (voir TexteParle). */
    private fun cleanForSpeech(t: String): String = TexteParle.preparer(t)
}

/**
 * Vrai si [entendu] n'est qu'un morceau de [voix] (ce que Jarvis vient de dire) : au moins 3 mots dont 60 % figurent
 * dans la réponse, ou un fragment (≥ 6 lettres) contenu tel quel dans la réponse.
 */
/** Texte écrit (en partie) dans un autre alphabet que le français : Whisper s'est trompé de langue. */
internal fun etranger(t: String): Boolean =
    t.count { it.isLetter() && !(it in 'a'..'z' || it in 'A'..'Z' || it in "àâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ") } >= 2

/**
 * Recoupe la transcription de Whisper avec celle du modèle hors-ligne, qui n'entend bien que la voix proche du
 * téléphone : (texte gardé, note pour le journal ou null).
 *  - mauvaise langue (« Erumina náðum tors ») → texte hors-ligne ;
 *  - presque rien en commun → Whisper a transcrit quelqu'un d'autre (télé, personnes autour) → texte hors-ligne ;
 *  - phrases sans aucun mot commun au début ou à la fin (« Éteins la lampe torche. Ah ouais, c'est vraiment bien »)
 *    → retirées.
 */
internal fun accorder(whisper: String, local: String): Pair<String, String?> {
    val w = whisper.trim(); val l = local.trim()
    fun mots(t: String) = fr.dd.jarvis.societe.core.normaliser(t).split(' ').filter { it.length >= 3 }.map { it.take(5) }
    val ml = mots(l).toSet()
    if (etranger(w)) return (if (ml.isNotEmpty()) l else "") to "Whisper s'est trompé de langue"
    if (ml.size < 2 || w.isBlank()) return w to null
    val enCommun = mots(w).count { it in ml }
    if (enCommun.toDouble() / ml.size < 0.34) return l to "Whisper a entendu une autre voix"
    val phrases = w.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
    if (phrases.size < 2) return w to null
    val communs = phrases.map { p -> mots(p).count { it in ml } }
    var a = 0; var b = phrases.size - 1
    while (a < b && communs[a] == 0 && mots(phrases[a]).size >= 2) a++
    while (b > a && communs[b] == 0 && mots(phrases[b]).size >= 2) b--
    if (a == 0 && b == phrases.size - 1) return w to null
    return phrases.subList(a, b + 1).joinToString(" ") to "voix autour retirée"
}

internal fun estEchoDe(entendu: String, voix: String): Boolean {
    fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    val e = norm(entendu); val v = norm(voix)
    if (e.isBlank() || v.isBlank()) return false
    // « Jarvis » (que tu répètes souvent) et les petits mots ne prouvent rien : seulement les mots distincts qui comptent.
    val vides = setOf("jarvis", "le", "la", "les", "de", "du", "des", "un", "une", "et", "ou", "a", "au", "en", "je", "tu", "il", "est", "ce", "ca", "oui", "non", "merci")
    val motsE = e.split(' ').filter { it.length >= 2 && it !in vides }.distinct()
    val motsV = v.split(' ').filter { it.length >= 2 }.toSet()
    if (motsE.size >= 3) return motsE.count { it in motsV } >= kotlin.math.ceil(motsE.size * 0.6)
    return motsE.isNotEmpty() && e.length >= 6 && v.contains(e)
}
