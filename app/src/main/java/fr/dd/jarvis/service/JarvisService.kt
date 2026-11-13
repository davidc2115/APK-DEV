package fr.dd.jarvis.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Brain
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.ai.ToolBox
import fr.dd.jarvis.voice.VoiceManager
import kotlinx.coroutines.*
import java.text.Normalizer

/**
 * Écoute en arrière-plan, comme « Ok Google » : dis « Jarvis » → petit bip → ta demande (ex. « lance un itinéraire vers
 * chez Dupont »). La réponse est dite à voix haute ; tu peux la couper en reprenant la parole. La conversation reste
 * ouverte tant que tu enchaînes, et se referme d'elle-même après quelques secondes de silence (réglage « silenceSec »).
 *
 * Arrière-plan : service au premier plan (type micro) + verrou de veille. Android interdit de rallumer le micro tout seul
 * au démarrage du téléphone ou après un arrêt forcé : une notification « Relancer l'écoute » le fait d'un geste.
 */
class JarvisService : Service() {

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "fr.dd.jarvis.WAKE_STOP"
        const val ACTION_RELANCER = "fr.dd.jarvis.WAKE_RELANCER"
        /** Android Auto (appli média) : « Parler à JARVIS » → écoute immédiate, comme si on avait dit « Jarvis ». */
        const val ACTION_ECOUTER = "fr.dd.jarvis.WAKE_ECOUTER"
        @Volatile private var actuel: JarvisService? = null

        /** « Jarvis, déverrouille » reçu ailleurs que par l'écoute (écran de présence, chat…) : vérification de la voix ici. */
        fun deverrouiller(): Boolean = actuel?.let { s -> android.os.Handler(android.os.Looper.getMainLooper()).post { s.deverrouillerALaVoix() }; true } ?: false
        private const val NOTIF = 4001
        private const val NOTIF_REPRISE = 4003

        fun start(ctx: Context) {
            val i = Intent(ctx, JarvisService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, JarvisService::class.java)) }

        /**
         * Ouvre le micro de JARVIS (bip puis écoute de la demande) depuis la voiture. Démarre l'écoute si elle était
         * coupée (elle s'arrête alors en quittant la voiture). Faux si Android refuse.
         */
        fun ecouterDepuisVoiture(ctx: Context): Boolean {
            val s = actuel
            if (running && s != null) { s.main.post { s.demandeExterne() }; return true }
            if (!micAccorde(ctx)) return false
            return try {
                val i = Intent(ctx, JarvisService::class.java).setAction(ACTION_ECOUTER)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                true
            } catch (_: Throwable) { false }
        }

        /**
         * L'appli JARVIS (micro du chat, conversation à l'écran) prend le micro : le service suspend l'écoute du mot
         * « Jarvis » et ignore tout réveil jusqu'à la fin — sinon les deux entendaient la même phrase (double détection,
         * bips en double, deux réponses).
         */
        @Volatile var suspenduParAppli = false
            private set

        fun suspendrePourAppli(oui: Boolean) {
            if (suspenduParAppli == oui) return
            suspenduParAppli = oui
            val s = actuel ?: return
            s.main.removeCallbacks(s.finSuspension)
            if (oui) s.main.postDelayed(s.finSuspension, 5 * 60_000L)   // filet : jamais sourd plus de 5 min
            s.main.post {
                if (oui) {
                    s.main.removeCallbacks(s.relance)
                    if (s.awake && !s.busy) { s.voice?.stopSpeaking(); s.voice?.stopListening(); s.awake = false; s.voice?.sessionFin() }
                    s.vosk?.stop()
                } else if (!s.awake && !s.busy) s.goIdle()
            }
        }

        /** Bouton pause de la voiture : JARVIS se tait et la conversation se referme. */
        fun interrompreDepuisVoiture() {
            val s = actuel ?: return
            s.main.post { s.interrompre() }
        }

        private fun micAccorde(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Notification « Relancer l'écoute » : un toucher redonne à Android le droit d'utiliser le micro en arrière-plan. */
        fun notifierReprise(ctx: Context, texte: String) {
            val pi = PendingIntent.getForegroundService(ctx, 5, Intent(ctx, JarvisService::class.java).setAction(ACTION_RELANCER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // Canal discret (aucun son) et une seule alerte : plus de bips à répétition quand Android bloque le micro.
            val n = NotificationCompat.Builder(ctx, JarvisApp.CHANNEL_WAKE).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Écoute « Jarvis » en pause").setContentText(texte)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texte)).setOnlyAlertOnce(true).setSilent(true)
                .setContentIntent(pi).setAutoCancel(true)
                .addAction(android.R.drawable.ic_btn_speak_now, "Relancer l'écoute", pi).build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_REPRISE, n) }
        }

        /** Au démarrage du téléphone / après une mise à jour : relance l'écoute si elle était active. */
        fun restaurer(ctx: Context) {
            val app = ctx.applicationContext as? JarvisApp ?: return
            if (!app.store.wakeOn.value || running) return
            if (!micAccorde(ctx)) return
            try { start(ctx) } catch (_: Throwable) {
                notifierReprise(ctx, "Android ne laisse pas JARVIS rallumer le micro seul après un redémarrage. Touche ici pour relancer l'écoute « Jarvis ».")
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var voice: VoiceManager? = null
    private var wake: PowerManager.WakeLock? = null
    private var busy = false
    private var awake = false              // conversation en cours : plus besoin de dire « Jarvis »
    private var vosk: fr.dd.jarvis.voice.WakeDetector? = null
    private var confirm: CompletableDeferred<Boolean>? = null
    private var failures = 0
    private var incompris = 0              // phrases non comprises d'affilée pendant une conversation
    private var echangeReel = false        // vrai dès qu'une vraie demande a été traitée pendant cette conversation
    private lateinit var app: JarvisApp
    private lateinit var tools: ToolBox

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private val wakeRegex = Regex("\\b(jarvis|jarvice|jarviss|djarvis|jarvi|jarves|charvis)\\b[ ,.:!?-]*")

    override fun onBind(intent: Intent?): IBinder? = null

    private var texteEcoute = "Dis « Jarvis », attends le bip, puis ta demande"

    /** Met à jour le texte de la notification permanente (état du détecteur), sans son. */
    private fun majNotification(texte: String) {
        if (texte == texteEcoute) return
        texteEcoute = texte
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF, notificationEcoute()) }
    }

    private fun notificationEcoute(): Notification {
        val pi = PendingIntent.getService(this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, fr.dd.jarvis.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, JarvisApp.CHANNEL_WAKE).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS à l'écoute").setContentText(texteEcoute).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open).addAction(0, "Arrêter", pi).build()
    }

    /** Passe (ou repasse) au premier plan avec le micro ; faux si Android le refuse (lancement depuis l'arrière-plan). */
    private fun premierPlan(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, notificationEcoute(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF, notificationEcoute())
        true
    } catch (_: Throwable) { false }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        app = application as JarvisApp
        if (intent?.action == ACTION_STOP) {
            app.store.wakeOn.set(false)
            stopSelf(); return START_NOT_STICKY
        }
        if (running) {
            if (intent?.action == ACTION_RELANCER) {
                // Toucher la notification redonne le droit au micro : on repasse au premier plan et on réarme le détecteur.
                getSystemService(NotificationManager::class.java).cancel(NOTIF_REPRISE)
                premierPlan()
                if (!awake && !busy) { vosk?.stop(); goIdle() }
            }
            return START_STICKY
        }
        if (!premierPlan()) {
            // Lancement refusé (démarrage du téléphone, arrière-plan…) : on NE désactive PAS l'écoute, on propose de la relancer.
            if (intent?.action == ACTION_ECOUTER) fr.dd.jarvis.car.ActiviteVoiture.signaler(fr.dd.jarvis.car.ActiviteVoiture.Etape.ECHEC,
                texte = "Android bloque le micro en arrière-plan : active l'écoute « Jarvis » dans JARVIS avant de prendre la route.")
            notifierReprise(this, "Android a bloqué l'accès au micro en arrière-plan. Touche ici pour relancer l'écoute « Jarvis ».")
            stopSelf(); return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIF_REPRISE)
        running = true
        actuel = this
        if (intent?.action == ACTION_ECOUTER) main.postDelayed({ demandeExterne() }, 1800)
        // Verrou de veille seulement PENDANT une conversation (voir garderEveille) : en attente du mot « Jarvis », le
        // micro actif suffit à Android ; le verrou permanent empêchait le téléphone de dormir même micro coupé.
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:wake").apply { setReferenceCounted(false) }
        tools = ToolBox(app) { q, _ ->
            // Action sensible à la voix, téléphone verrouillé : seulement si c'est TA voix (empreinte vocale),
            // sinon il faut déverrouiller le téléphone (personne d'autre ne peut la déclencher).
            // Désactiver un système de sécurité téléphone verrouillé : empreinte vocale exigée même si l'option est coupée.
            if ((app.store.biometrie.value || q.startsWith(fr.dd.jarvis.securite.Systemes.QUESTION)) &&
                fr.dd.jarvis.device.Securite.sensible(q) && fr.dd.jarvis.device.Securite.verrouille(this)) {
                if (fr.dd.jarvis.voice.EmpreinteVocale.prete(app)) voiceConfirm(q) && verifierMaVoix()
                else {
                    withContext(Dispatchers.Main) { voice?.speak("C'est une action sensible : déverrouille d'abord le téléphone, puis redemande-la.") {} }
                    false
                }
            } else voiceConfirm(q)
        }
        // Hermès installé : on s'assure que sa passerelle tourne dans Termux (après un redémarrage du téléphone par ex.).
        if (app.store.hermesMode.value != "off") scope.launch {
            runCatching {
                fr.dd.jarvis.hermes.PontMcp.demarrer(app)
                val hook = fr.dd.jarvis.hermes.HermesTermux.VERSION_HOOK_MEMOIRE.toString()
                // Mémoire partagée : installée une fois (puis à chaque nouvelle version du hook), passerelle relancée.
                if (app.store.hermesInstalle.value && app.store.hookMemoireVersion.value != hook) {
                    if (fr.dd.jarvis.hermes.HermesTermux.installerMemoirePartagee(app) == null) app.store.hookMemoireVersion.set(hook)
                } else if (!fr.dd.jarvis.hermes.Hermes.enLigne(force = true)) fr.dd.jarvis.hermes.Hermes.relancerSiBesoin(app)
            }
        }
        voice = VoiceManager(this).also { v ->
            v.muteBeeps = app.store.muteBeeps.value
            v.bargeIn = app.store.coupeParole.value
            v.duplex = app.store.duplex.value
            v.onFinal = { t -> failures = 0; incompris = 0; handle(t) }
            v.onPartial = { t -> fr.dd.jarvis.car.ActiviteVoiture.signaler(fr.dd.jarvis.car.ActiviteVoiture.Etape.PARTIEL, texte = t) }
            v.onSilence = {
                val c = confirm
                when {
                    c != null -> c.complete(false)                 // pas de réponse à « oui ou non » : on n'exécute pas
                    busy -> {}
                    awake -> finConversation()
                    vosk == null -> main.postDelayed({ listen() }, 1200)
                }
            }
            v.onFail = { msg ->
                failures++
                when {
                    confirm != null -> main.postDelayed({ ecouter(8000) }, 400)
                    busy -> {}
                    // Phrase entendue mais pas comprise : on réécoute (dans la limite de 3 fois), sinon on referme.
                    awake -> if (msg.isBlank() && ++incompris < 3) ecouter() else finConversation()
                    vosk == null -> main.postDelayed({ listen() }, if (failures > 4) 5000L else 1200L) // repli sans détecteur : écoute en boucle
                }
            }
        }
        vosk = when {
            app.store.picoKey.value.isNotBlank() -> fr.dd.jarvis.voice.PorcupineWake(this, app.store.picoKey.value) { main.post { onWakeWord() } }
            fr.dd.jarvis.voice.VoskModel.ready(this) -> fr.dd.jarvis.voice.VoskWake(this, app.store.ecouteEconome.value) { main.post { onWakeWord() } }
            else -> null
        }?.also { brancher(it) }
        main.postDelayed(surveillance, 30_000)
        if (vosk == null) {
            // Ni clé Picovoice ni modèle Vosk : on télécharge le détecteur hors-ligne (≈ 40 Mo, une seule fois), toujours SANS écoute en boucle.
            val nm = getSystemService(NotificationManager::class.java)
            val open = PendingIntent.getActivity(this, 2, Intent(this, fr.dd.jarvis.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(NOTIF + 1, NotificationCompat.Builder(this, JarvisApp.CHANNEL_WAKE).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("JARVIS prépare l'écoute « Jarvis »").setContentText("Téléchargement du détecteur hors-ligne (≈ 40 Mo)…").setOngoing(true).build())
            scope.launch(Dispatchers.IO) {
                val msg = fr.dd.jarvis.voice.VoskModel.download(this@JarvisService) { }
                main.post {
                    if (running && fr.dd.jarvis.voice.VoskModel.ready(this@JarvisService)) {
                        nm.cancel(NOTIF + 1)
                        vosk = fr.dd.jarvis.voice.VoskWake(this@JarvisService, app.store.ecouteEconome.value) { main.post { onWakeWord() } }.also { brancher(it) }
                        goIdle()
                    } else if (running) {
                        app.store.wakeOn.set(false)
                        nm.notify(NOTIF + 1, NotificationCompat.Builder(this@JarvisService, JarvisApp.CHANNEL_WAKE).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                            .setContentTitle("Écoute « Jarvis » non démarrée").setContentText(msg.take(120)).setAutoCancel(true).setContentIntent(open).build())
                        stopSelf()
                    }
                }
            }
            return START_STICKY
        }
        // Le canal de notification JARVIS n'est utile que pendant une projection Android Auto.
        if (app.enVoiture) runCatching { CarBridge.post(this, "Je suis prêt. Réponds pour me parler.") }
        main.postDelayed({ goIdle() }, 1200)
        return START_STICKY
    }

    private fun listen() { if (running) voice?.startListening() }

    private fun silenceMs() = app.store.silenceSec.value.coerceIn(3, 30) * 1000L

    /** Écoute dans la conversation : se referme seule si personne ne parle dans le délai. */
    private fun ecouter(delai: Long = silenceMs()) {
        if (!running) return
        voice?.ecouterConversation(delai)
        fr.dd.jarvis.car.ActiviteVoiture.signaler(fr.dd.jarvis.car.ActiviteVoiture.Etape.ECOUTE)
    }

    /**
     * Demande venue de la voiture (bouton micro de l'appli média) : écoute tout de suite. Si JARVIS parle, il se tait
     * d'abord ; s'il est encore en train de traiter une demande, on le signale au lieu d'attendre indéfiniment.
     */
    private fun demandeExterne(essai: Int = 0) {
        if (!running) return
        // La voiture envoie parfois deux fois le même appui (▶ puis « jouer depuis la recherche ») : un seul bip.
        if (essai == 0) {
            val t = System.currentTimeMillis()
            if (t - derniereDemandeExterne < 1500) return
            derniereDemandeExterne = t
        }
        if (busy) {
            voice?.stopSpeaking()
            if (essai < 6) main.postDelayed({ demandeExterne(essai + 1) }, 500)
            else fr.dd.jarvis.car.ActiviteVoiture.signaler(fr.dd.jarvis.car.ActiviteVoiture.Etape.ECHEC,
                texte = "Je termine ta demande précédente, réessaie dans un instant.")
            return
        }
        if (awake) {
            voice?.stopSpeaking()
            voice?.carillon(montant = true) { if (awake && !busy) ecouter(maxOf(silenceMs(), 6000L)) }
            return
        }
        dernierReveil = 0L
        onWakeWord()
    }

    /** Pause demandée par la voiture : on coupe la voix et on referme la conversation (sans toucher à une demande en cours). */
    private fun interrompre() {
        voice?.stopSpeaking()
        if (awake && !busy) { voice?.stopListening(); finConversation() }
    }

    /** Conversation en cours (écoute, réflexion, réponse) : le téléphone ne doit pas s'endormir au milieu (10 min au plus). */
    private fun garderEveille(oui: Boolean) {
        try {
            if (oui) wake?.acquire(10 * 60_000L)
            else if (wake?.isHeld == true) wake?.release()
        } catch (_: Throwable) {}
    }

    /** Retour à l'attente du mot « Jarvis » (détecteur hors-ligne s'il est installé, sinon écoute en boucle). */
    private fun goIdle() {
        if (!busy) garderEveille(false)
        fr.dd.jarvis.car.ActiviteVoiture.signaler(fr.dd.jarvis.car.ActiviteVoiture.Etape.FIN)
        awake = false
        incompris = 0
        finLe = System.currentTimeMillis()
        voice?.sessionFin()
        voice?.stopListening()
        if (suspenduParAppli) return
        if (vosk != null) {
            // Laisse retomber l'écho de la voix de JARVIS (ou du bip) avant de réarmer l'écoute du mot « Jarvis »,
            // sinon le micro peut se réentendre lui-même et redéclencher aussitôt. Un seul réarmement en attente à la
            // fois (plusieurs fins de conversation rapprochées relançaient plusieurs détecteurs).
            main.removeCallbacks(relance)
            main.postDelayed(relance, 900)
        } else listen()
    }

    /** Fin de la dernière conversation : un « Jarvis » entendu juste après est l'écho de la voix ou du bip. */
    private var finLe = 0L
    private var derniereDemandeExterne = 0L

    /** Fin de conversation (silence) : petit carillon descendant s'il y a eu un vrai échange, puis attente de « Jarvis ». */
    private fun finConversation() {
        if (!awake) return
        awake = false
        if (!echangeReel && incompris == 0) noterFauxReveil() else vosk?.exigence = exigenceCourante()
        if (echangeReel) voice?.carillon(montant = false) { goIdle() } ?: goIdle() else goIdle()
    }

    /** Le détecteur prévient s'il s'arrête tout seul (micro perdu, erreur) : on le relance au lieu de rester sourd. */
    private fun brancher(v: fr.dd.jarvis.voice.WakeDetector) {
        v.onPanne = { main.post { if (running && !awake && !busy && vosk === v) { v.stop(); main.postDelayed({ rearmer(v) }, 1500) } } }
        v.exigence = exigenceCourante()
    }

    private var echecsReveil = 0

    /**
     * (Re)démarre l'écoute du mot « Jarvis ». Un échec (micro occupé par une autre appli, appel…) ne coupe plus le
     * service : on réessaie avec un délai croissant ; une clé Picovoice refusée bascule sur le détecteur hors-ligne.
     */
    private fun rearmer(v: fr.dd.jarvis.voice.WakeDetector) {
        main.removeCallbacks(relance)
        if (!running || awake || busy || vosk !== v || suspenduParAppli) return
        if (v.start()) {
            echecsReveil = 0
            majNotification("Dis « Jarvis », attends le bip, puis ta demande")
            return
        }
        echecsReveil++
        val err = (v as? fr.dd.jarvis.voice.PorcupineWake)?.error.orEmpty()
        if (v is fr.dd.jarvis.voice.PorcupineWake && echecsReveil >= 2 && fr.dd.jarvis.voice.VoskModel.ready(this)) {
            v.release()
            vosk = fr.dd.jarvis.voice.VoskWake(this, app.store.ecouteEconome.value) { main.post { onWakeWord() } }.also { brancher(it) }
            majNotification("Clé Picovoice refusée (${err.take(60)}) : détecteur hors-ligne utilisé")
            main.postDelayed({ vosk?.let { rearmer(it) } }, 500)
            return
        }
        val delai = when (echecsReveil) { 1 -> 1500L; 2 -> 4000L; 3 -> 10_000L; else -> 30_000L }
        majNotification("Micro occupé : nouvelle tentative dans ${delai / 1000} s" + (if (err.isNotBlank()) " (${err.take(60)})" else ""))
        main.postDelayed(relance, delai)
    }

    private val relance = Runnable { vosk?.let { rearmer(it) } }
    private val finSuspension = Runnable { suspendrePourAppli(false) }

    // ---- faux réveils : si « Jarvis » se déclenche tout seul (télé, conversation), le détecteur devient plus strict.
    private val fauxReveils = ArrayDeque<Long>()

    private fun exigenceCourante(): Int {
        val maintenant = System.currentTimeMillis()
        while (fauxReveils.isNotEmpty() && maintenant - fauxReveils.first() > 15 * 60_000) fauxReveils.removeFirst()
        return when { fauxReveils.size >= 4 -> 3; fauxReveils.size >= 2 -> 2; else -> 1 }
    }

    private fun noterFauxReveil() {
        fauxReveils.addLast(System.currentTimeMillis())
        vosk?.exigence = exigenceCourante()
    }

    private var dernierReveil = 0L

    /** « Jarvis » entendu : bip montant puis écoute immédiate de la demande (plus de « Oui ? » parlé qui fait attendre). */
    private fun onWakeWord() {
        if (!running || awake || busy || suspenduParAppli) return
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierReveil < 2500) return   // écho du bip / de la voix de JARVIS pris pour « Jarvis »
        if (dernierReveil != 0L && maintenant - finLe < 1500) return   // écho de la fin de réponse / du bip de fin
        dernierReveil = maintenant
        garderEveille(true)
        // Connexion à l'IA préparée pendant que tu parles (évite la poignée de main TLS au moment de la demande).
        fr.dd.jarvis.ai.Http.prechauffer()
        vosk?.stop()
        // En voiture (JARVIS ouvert dans Android Auto) : micro de la VOITURE et haut-parleurs de la voiture, GPS toujours affiché.
        if (fr.dd.jarvis.car.ConversationAuto.possible(app)) {
            awake = true; busy = true; echangeReel = true
            voice?.stopSpeaking()
            voice?.carillon(montant = true) {
                fr.dd.jarvis.car.ConversationAuto.demarrer(app) { busy = false; awake = false; goIdle() }
            }
            return
        }
        awake = true; echangeReel = false; incompris = 0
        voice?.sessionDebut()
        voice?.stopSpeaking()
        voice?.carillon(montant = true) { if (awake && !busy) ecouter(maxOf(silenceMs(), 6000L)) }
    }

    private val stopRegex = Regex("^(merci|c'est tout|c est tout|stop|arrete|arrête|termine|terminé|fin|a plus tard|à plus tard|au revoir|laisse tomber|ca suffit|ça suffit)\\b.*")

    private fun handle(text: String) {
        confirm?.let { d ->
            val t = norm(text)
            d.complete(Regex("\\b(oui|ouais|ok|okay|confirme|vas-y|d'accord|yes|allez)\\b").containsMatchIn(t)); return
        }
        if (busy) return
        val t = norm(text)
        val m = wakeRegex.find(t)
        if (!awake && m == null) { listen(); return }   // repli sans détecteur : on ignore tout ce qui ne commence pas par Jarvis
        val cmd = (if (m != null && m.range.first <= 2) t.substring(m.range.last + 1) else text).trim()
        awake = true
        voice?.sessionDebut()
        if (cmd.isBlank()) { voice?.carillon(montant = true) { ecouter(maxOf(silenceMs(), 6000L)) }; return }
        if (stopRegex.matches(norm(cmd))) { awake = false; voice?.speak("D'accord.") { goIdle() }; return }
        process(cmd)
    }

    private fun process(cmd: String) {
        if (fr.dd.jarvis.securite.TelephoneSecurise.estDemande(cmd)) { deverrouillerALaVoix(); return }
        fr.dd.jarvis.device.CommandeMedia.intention(cmd) { fr.dd.jarvis.device.CommandeMedia.musiqueOuverte(this) }?.let { musique(cmd, it); return }
        busy = true; echangeReel = true
        garderEveille(true)
        scope.launch {
            val app = application as JarvisApp
            app.chatCharger()
            val userMessage = ChatMsg(true, cmd)
            fr.dd.jarvis.car.ActiviteVoiture.signaler(fr.dd.jarvis.car.ActiviteVoiture.Etape.REFLEXION, cmd)
            withContext(Dispatchers.Main) {
                app.chatAjouter(userMessage)
                if (app.enVoiture) CarBridge.ajouter(userMessage)
            }
            // Les demandes vocales suivent la même règle que le chat : Jarvis d'abord (réflexes instantanés, commandes du
            // téléphone), et Hermès automatiquement quand il le faut (automatismes, serveur, échec de Jarvis…).
            // Voix progressive : Jarvis commence à parler dès la première phrase écrite par Hermès.
            var relancer = true
            val flux = withContext(Dispatchers.Main) { voice?.parole(700) { busy = false; if (relancer) ecouter() else goIdle() } }
            val reply = try {
                fr.dd.jarvis.ai.Brain(app.llm, tools, app) { app.store.userName.value }
                    .reply(app.chat.toList().takeLast(16), onTexte = { d -> flux?.ajouter(d) }) { etat -> android.os.Handler(android.os.Looper.getMainLooper()).post { majNotification(etat) } }
            } catch (e: CancellationException) { flux?.arreter(sansSuite = true); throw e }
              catch (e: Throwable) { "⚠️ ${e.message ?: e.javaClass.simpleName}" }
            val assistantMessage = ChatMsg(false, reply)
            fr.dd.jarvis.car.ActiviteVoiture.signaler(fr.dd.jarvis.car.ActiviteVoiture.Etape.REPONSE, cmd, reply)
            withContext(Dispatchers.Main) {
                app.chatAjouter(assistantMessage)
                if (app.enVoiture) {
                    CarBridge.ajouter(assistantMessage)
                    // Rend aussi la réponse disponible dans le canal de messagerie Android Auto — SANS alerte : JARVIS vient de
                    // la dire ; une alerte faisait annoncer / relire le message par Android Auto (double voix, musique coupée).
                    CarBridge.post(this@JarvisService, reply, silencieux = true)
                }
            }
            notifier(cmd, reply)
            withContext(Dispatchers.Main) {
                // La conversation continue : on réécoute après la réponse (ou dès que l'utilisateur la coupe), puis on
                // referme après quelques secondes de silence.
                // Autoradio / Bluetooth : on ne rouvre le micro que si Jarvis pose une question — sinon il entendrait la
                // radio ou la suite de sa propre réponse ; il suffit de redire « Jarvis » pour une nouvelle demande.
                relancer = awake && (voice?.sortieExterne() != true || reply.trimEnd().endsWith("?"))
                flux?.finir(reply) ?: run { busy = false }
            }
        }
    }

    /**
     * Commande de musique à la voix : exécutée tout de suite, sans IA, sans Hermès ni agent, et sans phrase parlée (un bip
     * suffit) — la musique ne se fait plus couper par la réponse de JARVIS. « Pause » : la musique mise en pause pour
     * l'écoute ne repart pas à la fin. Lecture / suivant / précédent : envoyés une fois le micro et la voix libérés.
     */
    private fun musique(cmd: String, a: fr.dd.jarvis.device.CommandeMedia.Action) {
        val app = application as JarvisApp
        val info = a == fr.dd.jarvis.device.CommandeMedia.Action.INFO
        fun noter(rep: String) {
            app.chatAjouter(ChatMsg(true, cmd)); app.chatAjouter(ChatMsg(false, rep))
            if (app.enVoiture) { CarBridge.ajouter(ChatMsg(true, cmd)); CarBridge.ajouter(ChatMsg(false, rep)) }
            fr.dd.jarvis.hermes.Routeur.derniere = fr.dd.jarvis.hermes.Routeur.Cible.JARVIS
        }
        when {
            info -> {
                busy = true
                val rep = fr.dd.jarvis.device.CommandeMedia.executer(this, a, app.enVoiture)
                noter(rep)
                voice?.speak(fr.dd.jarvis.voice.TexteParle.preparer(rep)) { busy = false; goIdle() } ?: run { busy = false; goIdle() }
            }
            a == fr.dd.jarvis.device.CommandeMedia.Action.PAUSE -> {
                JarvisNotificationListener.oublierPauseVoix()
                val rep = fr.dd.jarvis.device.CommandeMedia.executer(this, a, app.enVoiture)
                noter(rep); voice?.carillon(montant = false); goIdle()
            }
            else -> {
                voice?.carillon(montant = false); goIdle()
                // Après la fin de session (focus audio rendu, musique mise en pause pour l'écoute relancée) : la commande.
                main.postDelayed({ noter(fr.dd.jarvis.device.CommandeMedia.executer(this, a, app.enVoiture)) }, 850)
            }
        }
    }

    private fun notifier(cmd: String, reply: String) {
        val plain = reply.replace(Regex("[#*`]"), "").replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        val n = NotificationCompat.Builder(this, JarvisApp.CHANNEL_AGENTS).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("« $cmd »").setContentText(plain.lineSequence().firstOrNull { it.isNotBlank() } ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(plain.take(3500))).setAutoCancel(true).build()
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(4002, n) }
    }

    /** Déverrouillage à la voix : seulement si l'empreinte vocale reconnaît le propriétaire. */
    private fun deverrouillerALaVoix() {
        busy = true
        garderEveille(true)
        scope.launch {
            val app = application as JarvisApp
            val r = try {
                when {
                    !fr.dd.jarvis.securite.TelephoneSecurise.verrouille(app) -> {
                        fr.dd.jarvis.securite.Systemes.liste(app).filter { it.actif && it.modele == "telephone_securise" }
                            .forEach { fr.dd.jarvis.securite.Systemes.sauver(app, it.copy(actif = false)) }
                        "Le téléphone est déjà déverrouillé."
                    }
                    !fr.dd.jarvis.voice.EmpreinteVocale.prete(app) -> {
                        fr.dd.jarvis.securite.TelephoneSecurise.afficherDeverrouillage(app)
                        "Je n'ai pas encore ton empreinte vocale : j'affiche le déverrouillage. Enregistre ta voix dans Réglages, Sécurité, Empreinte vocale."
                    }
                    verifierMaVoix() -> fr.dd.jarvis.securite.TelephoneSecurise.deverrouiller(app).also {
                        if (!fr.dd.jarvis.securite.TelephoneSecurise.verrouille(app)) fr.dd.jarvis.securite.Systemes.liste(app)
                            .filter { it.actif && it.modele == "telephone_securise" }.forEach { fr.dd.jarvis.securite.Systemes.sauver(app, it.copy(actif = false)) }
                    }
                    else -> ""   // voix non reconnue : verifierMaVoix l'a déjà dit
                }
            } catch (e: Throwable) { "Déverrouillage impossible : ${e.message ?: e.javaClass.simpleName}" }
            if (r.isNotBlank()) dire(r)
            withContext(Dispatchers.Main) { busy = false; garderEveille(false); goIdle() }
        }
    }

    /** Dit [texte] et attend la fin de la phrase. */
    private suspend fun dire(texte: String) {
        val fin = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { voice?.speak(texte) { fin.complete(Unit) } ?: fin.complete(Unit) }
        withTimeoutOrNull(20_000) { fin.await() }
    }

    /** Empreinte vocale : fait dire une phrase et vérifie que c'est bien la voix du propriétaire. */
    private suspend fun verifierMaVoix(): Boolean {
        dire("Pour confirmer, dis : ${fr.dd.jarvis.voice.EmpreinteVocale.PHRASE_VERIF}.")
        val r = fr.dd.jarvis.voice.EmpreinteVocale.verifier(app)
        dire(if (r.ok) "Voix reconnue." else "Je ne reconnais pas ta voix (${r.message}) : action annulée. Déverrouille le téléphone pour la faire.")
        return r.ok
    }

    private suspend fun voiceConfirm(question: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        confirm = d
        withContext(Dispatchers.Main) { voice?.speak(question.take(300) + " Dis oui ou non.") { ecouter(8000) } }
        val r = withTimeoutOrNull(25_000) { d.await() } ?: false
        confirm = null
        return r
    }

    // ------------------------------------------------------------------ surveillance du micro

    private var micCoupe = 0

    /**
     * Android peut « faire taire » le micro d'une appli en arrière-plan (le service tourne mais n'entend plus rien).
     * Si cela dure, on prévient avec une notification qui relance l'écoute d'un toucher.
     */
    private val surveillance = object : Runnable {
        override fun run() {
            if (!running) return
            if (Build.VERSION.SDK_INT >= 29 && !awake && !busy && vosk != null) {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val silencieux = runCatching { am.activeRecordingConfigurations.any { it.isClientSilenced } }.getOrDefault(false)
                val appel = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
                micCoupe = if (silencieux && !appel) micCoupe + 1 else 0
                if (micCoupe == 4) notifierReprise(this@JarvisService, "Android a coupé le micro de JARVIS en arrière-plan : le mot « Jarvis » n'est plus entendu. Touche ici pour relancer l'écoute.")
            }
            main.postDelayed(this, 30_000)
        }
    }

    override fun onDestroy() {
        running = false
        if (actuel === this) actuel = null
        main.removeCallbacks(surveillance)
        main.removeCallbacks(relance)
        scope.cancel()
        vosk?.release(); vosk = null
        voice?.release(); voice = null
        runCatching { wake?.release() }
        super.onDestroy()
    }
}
