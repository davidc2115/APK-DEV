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
        private const val NOTIF = 4001
        private const val NOTIF_REPRISE = 4003

        fun start(ctx: Context) {
            val i = Intent(ctx, JarvisService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, JarvisService::class.java)) }

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
            notifierReprise(this, "Android a bloqué l'accès au micro en arrière-plan. Touche ici pour relancer l'écoute « Jarvis ».")
            stopSelf(); return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIF_REPRISE)
        running = true
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:wake").apply { setReferenceCounted(false); acquire() }
        tools = ToolBox(app) { q, _ -> voiceConfirm(q) }
        // Hermès installé : on s'assure que sa passerelle tourne dans Termux (après un redémarrage du téléphone par ex.).
        if (app.store.hermesInstalle.value && app.store.hermesMode.value != "off") scope.launch {
            runCatching { fr.dd.jarvis.hermes.PontMcp.demarrer(app); if (!fr.dd.jarvis.hermes.Hermes.enLigne(force = true)) fr.dd.jarvis.hermes.Hermes.relancerSiBesoin(app) }
        }
        voice = VoiceManager(this).also { v ->
            v.muteBeeps = app.store.muteBeeps.value
            v.bargeIn = app.store.coupeParole.value
            v.duplex = app.store.duplex.value
            v.onFinal = { t -> failures = 0; incompris = 0; handle(t) }
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
            fr.dd.jarvis.voice.VoskModel.ready(this) -> fr.dd.jarvis.voice.VoskWake(this) { main.post { onWakeWord() } }
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
                        vosk = fr.dd.jarvis.voice.VoskWake(this@JarvisService) { main.post { onWakeWord() } }.also { brancher(it) }
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
    private fun ecouter(delai: Long = silenceMs()) { if (running) voice?.ecouterConversation(delai) }

    /** Retour à l'attente du mot « Jarvis » (détecteur hors-ligne s'il est installé, sinon écoute en boucle). */
    private fun goIdle() {
        awake = false
        incompris = 0
        voice?.stopListening()
        val v = vosk
        if (v != null) {
            // Laisse retomber l'écho de la voix de JARVIS (ou du bip) avant de réarmer l'écoute du mot « Jarvis »,
            // sinon le micro peut se réentendre lui-même et redéclencher aussitôt.
            main.postDelayed({ rearmer(v) }, 900)
        } else listen()
    }

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
        if (!running || awake || busy || vosk !== v) return
        if (v.start()) {
            echecsReveil = 0
            majNotification("Dis « Jarvis », attends le bip, puis ta demande")
            return
        }
        echecsReveil++
        val err = (v as? fr.dd.jarvis.voice.PorcupineWake)?.error.orEmpty()
        if (v is fr.dd.jarvis.voice.PorcupineWake && echecsReveil >= 2 && fr.dd.jarvis.voice.VoskModel.ready(this)) {
            v.release()
            vosk = fr.dd.jarvis.voice.VoskWake(this) { main.post { onWakeWord() } }.also { brancher(it) }
            majNotification("Clé Picovoice refusée (${err.take(60)}) : détecteur hors-ligne utilisé")
            main.postDelayed({ vosk?.let { rearmer(it) } }, 500)
            return
        }
        val delai = when (echecsReveil) { 1 -> 1500L; 2 -> 4000L; 3 -> 10_000L; else -> 30_000L }
        majNotification("Micro occupé : nouvelle tentative dans ${delai / 1000} s" + (if (err.isNotBlank()) " (${err.take(60)})" else ""))
        main.postDelayed(relance, delai)
    }

    private val relance = Runnable { vosk?.let { rearmer(it) } }

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
        if (!running || awake || busy) return
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierReveil < 2500) return   // écho du bip / de la voix de JARVIS pris pour « Jarvis »
        dernierReveil = maintenant
        vosk?.stop()
        awake = true; echangeReel = false; incompris = 0
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
        if (cmd.isBlank()) { voice?.carillon(montant = true) { ecouter(maxOf(silenceMs(), 6000L)) }; return }
        if (stopRegex.matches(norm(cmd))) { awake = false; voice?.speak("D'accord.") { goIdle() }; return }
        process(cmd)
    }

    private fun process(cmd: String) {
        busy = true; echangeReel = true
        scope.launch {
            val app = application as JarvisApp
            app.chatCharger()
            val userMessage = ChatMsg(true, cmd)
            withContext(Dispatchers.Main) {
                app.chatAjouter(userMessage)
                if (app.enVoiture) CarBridge.ajouter(userMessage)
            }
            // Les demandes vocales suivent la même règle que le chat : Hermès est le cerveau exclusif.
            // Aucun basculement silencieux vers Brain/Groq n'est autorisé dans cette voie vocale.
            val reply = try {
                if (!app.store.hermesInstalle.value || app.store.hermesMode.value == "off") {
                    "Hermès est désactivé ou non installé. Active Hermès dans les réglages pour utiliser les commandes vocales."
                } else {
                    fr.dd.jarvis.hermes.Hermes.repondre(
                        app,
                        app.chat.toList().takeLast(12),
                        tools,
                        app.store.userName.value
                    ) { etat -> android.os.Handler(android.os.Looper.getMainLooper()).post { majNotification(etat) } }
                        ?: "Je n'arrive pas à joindre Hermès pour le moment. Je n'ai pas exécuté la demande ; réessaie dans un instant."
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Throwable) { "Erreur Hermès : ${e.message ?: e.javaClass.simpleName}" }
            val assistantMessage = ChatMsg(false, reply)
            withContext(Dispatchers.Main) {
                app.chatAjouter(assistantMessage)
                if (app.enVoiture) {
                    CarBridge.ajouter(assistantMessage)
                    // Rend aussi la réponse disponible dans le canal de messagerie Android Auto.
                    CarBridge.post(this@JarvisService, reply)
                }
            }
            notifier(cmd, reply)
            withContext(Dispatchers.Main) {
                // La conversation continue : on réécoute après la réponse (ou dès que l'utilisateur la coupe), puis on
                // referme après quelques secondes de silence.
                voice?.speak(reply.take(700)) { busy = false; if (awake) ecouter() else goIdle() } ?: run { busy = false }
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
        main.removeCallbacks(surveillance)
        main.removeCallbacks(relance)
        scope.cancel()
        vosk?.release(); vosk = null
        voice?.release(); voice = null
        runCatching { wake?.release() }
        super.onDestroy()
    }
}
