package fr.dd.jarvis

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import fr.dd.jarvis.ai.Llm
import fr.dd.jarvis.data.Store
import fr.dd.jarvis.data.graph.GraphDatabase
import fr.dd.jarvis.data.graph.GraphIndexer
import fr.dd.jarvis.data.graph.GraphIndexReport
import fr.dd.jarvis.data.graph.KnowledgeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import fr.dd.jarvis.device.CalendarRepo
import fr.dd.jarvis.device.ContactsRepo
import fr.dd.jarvis.device.MailRepo
import fr.dd.jarvis.device.ObsidianWriter
import fr.dd.jarvis.device.PhoneControl
import fr.dd.jarvis.societe.SocieteRepo
import fr.dd.jarvis.societe.SocieteService
import fr.dd.jarvis.work.Scheduler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class JarvisApp : Application() {
    // ---- Conversation partagée entre l'écran Chat et le mode vocal « Jarvis » (service)
    val chat = androidx.compose.runtime.mutableStateListOf<fr.dd.jarvis.ai.ChatMsg>()
    @Volatile var chatLoaded = false
    @Volatile var convId = ""

    @Synchronized fun chatCharger() {
        if (chatLoaded) return
        chatLoaded = true
        runCatching {
            val f = java.io.File(filesDir, "chat.json")
            if (!f.exists()) return
            val arr = org.json.JSONArray(f.readText())
            val l = (0 until arr.length()).map { arr.getJSONObject(it).let { o -> fr.dd.jarvis.ai.ChatMsg(o.getBoolean("u"), o.getString("t"), o.optLong("ts")) } }
                .filter { it.fromUser || !fr.dd.jarvis.ai.estNul(it.text) }
            android.os.Handler(android.os.Looper.getMainLooper()).let { h -> if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) chat.addAll(l) else h.post { chat.addAll(l) } }
        }
    }

    /** Ajoute un message (thread principal) et sauvegarde. */
    fun chatAjouter(m: fr.dd.jarvis.ai.ChatMsg) {
        chat.add(m); chatSauver()
    }

    /** Sauvegarde de la conversation en cours, faite hors du fil de l'écran (voir [fr.dd.jarvis.data.Ecritures]). */
    fun chatSauver() {
        val derniers = runCatching { chat.toList().takeLast(200) }.getOrNull() ?: return
        fr.dd.jarvis.data.Ecritures.ecrire(java.io.File(filesDir, "chat.json")) { fr.dd.jarvis.ai.Conversations.serialiser(derniers) }
    }


    lateinit var store: Store
        private set
    lateinit var llm: Llm
        private set
    lateinit var calendar: CalendarRepo
        private set
    lateinit var mail: MailRepo
        private set
    lateinit var contacts: ContactsRepo
        private set
    lateinit var obsidian: ObsidianWriter
        private set
    lateinit var knowledgeRepository: KnowledgeRepository
        private set
    @Volatile var graphIndexReport: GraphIndexReport? = null
        private set
    private val graphScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var phone: PhoneControl
        private set
    lateinit var memo: fr.dd.jarvis.device.Memo
        private set
    lateinit var tasks: fr.dd.jarvis.data.TaskStore
        private set
    lateinit var societe: SocieteService
        private set
    /** Téléphone connecté à Android Auto (projection sur l'écran de la voiture). */
    @Volatile var enVoiture = false
        private set
    /** Heure (SystemClock.elapsedRealtime) du branchement à la voiture : Android Auto peut alors relancer seul la lecture. */
    @Volatile var voitureDepuis = 0L
        private set
    private val lanceLe = android.os.SystemClock.elapsedRealtime()

    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
        runCatching { noterArretsAnormaux() }
        store = Store(this)
        runCatching { fr.dd.jarvis.voice.JournalVoix.brancher(filesDir) }
        if (!store.hermesModeMigre.value) {
            if (store.hermesMode.value == "auto") store.hermesMode.set("tout")
            store.hermesModeMigre.set(true)
        }
        if (!store.hermesModeMigre3.value) {
            if (store.hermesMode.value in setOf("tout", "demande")) store.hermesMode.set("auto")
            store.hermesModeMigre.set(true); store.hermesModeMigre2.set(true); store.hermesModeMigre3.set(true)
        }
        if (!store.hermesModeMigre2.value) {
            if (store.hermesMode.value == "demande") store.hermesMode.set("tout")
            store.hermesModeMigre2.set(true)
        }
        if (!store.hermesModeMigre4.value) {
            if (store.hermesMode.value in setOf("auto", "demande")) store.hermesMode.set("tout")
            store.hermesModeMigre4.set(true)
        }
        fr.dd.jarvis.ai.Ollama.app = this
        // Noms des serveurs et salons Discord : « le serveur B&G » est reconnu comme Discord (pas le serveur Debian).
        runCatching { fr.dd.jarvis.ai.DiscordNoms.initialiser(this); fr.dd.jarvis.ai.DiscordNoms.rafraichirSiBesoin(this) }
        llm = Llm(store)
        // Pendant que tu parles ou écris : la connexion à Home Assistant est préparée avec celle de l'IA.
        fr.dd.jarvis.ai.Http.prechauffageAnnexe = { fr.dd.jarvis.ai.Serveur.prechaufferHa(this) }
        calendar = CalendarRepo(this, store)
        mail = MailRepo(this, store)
        contacts = ContactsRepo(this)
        obsidian = ObsidianWriter(this, store)
        val graphDatabase = GraphDatabase.getInstance(this)
        val graphIndexer = GraphIndexer(graphDatabase, obsidian)
        knowledgeRepository = KnowledgeRepository(graphDatabase, graphIndexer)
        // Index du graphe Obsidian (jusqu'à 3 000 notes, liens lus sur 1 200) : au plus toutes les 6 h, et pas pendant les 30 premières
        // secondes (le processus démarre aussi pour les tâches de fond : inutile de relire tout le coffre à chaque fois).
        if (System.currentTimeMillis() - (store.grapheIndexeLe.value.toLongOrNull() ?: 0L) > 6 * 3_600_000L) graphScope.launch {
            kotlinx.coroutines.delay(30_000)
            graphIndexReport = runCatching { knowledgeRepository.rebuildMarkdownIndex() }.getOrNull()
            if (graphIndexReport != null) store.grapheIndexeLe.set(System.currentTimeMillis().toString())
        }
        fr.dd.jarvis.device.Energie.suivre(this)
        fr.dd.jarvis.device.Energie.appliquerMode(this)
        phone = PhoneControl(this)
        memo = fr.dd.jarvis.device.Memo(this, obsidian)
        Thread { runCatching { memo.load() } }.start()
        tasks = fr.dd.jarvis.data.TaskStore(this)
        societe = SocieteService(this, SocieteRepo(this), obsidian)
        fr.dd.jarvis.work.VeilleWorker.planifier(this)
        fr.dd.jarvis.work.BaremesWorker.planifier(this)
        // Mémoire fiable : une fois, les notes déjà prises sont relues pour en tirer adresse, surnoms et faits.
        runCatching { fr.dd.jarvis.device.Faits.rattrapage(this) }
        // Tri des mails : seulement les LUS (les non lus restent dans la boîte de réception). Le grand ménage est retiré :
        // une étape encore programmée est annulée.
        store.mailTriStatut.set("lus")
        runCatching { androidx.work.WorkManager.getInstance(this).cancelUniqueWork("grand_menage_mails") }
        runCatching { fr.dd.jarvis.coffre.Coffre.planifier(this) }
        runCatching { fr.dd.jarvis.societe.MiroirFreebox.planifier(this) }
        runCatching { fr.dd.jarvis.coffre.Sauvegardes.planifier(this) }
        runCatching { fr.dd.jarvis.work.BilanSante.planifier(this) }
        // Une fois : les rapports d'intervention existants sont régénérés avec leurs photos dans le PDF.
        if (!store.rapportsV3.value) Thread {
            runCatching { societe.regenererRapports(jours = 3650) }
            store.rapportsV3.set(true); store.rapportsPhotosV2.set(true)
        }.start()
        else if (!store.rapportsPhotosV2.value) Thread {
            runCatching { societe.regenererRapportsAvecPhotos() }
            store.rapportsPhotosV2.set(true)
        }.start()
        societe.data.modeles["planning"]?.let { fr.dd.jarvis.ai.Prompts.planningFormat = it }
        // Contrôle des devis/factures présents dans Obsidian : une recherche par document, au plus toutes les 6 h.
        if (System.currentTimeMillis() - (store.docsVerifiesLe.value.toLongOrNull() ?: 0L) > 6 * 3_600_000L) Thread {
            runCatching { societe.synchroniserAvecCoffre(); store.docsVerifiesLe.set(System.currentTimeMillis().toString()) }
        }.start()
        createChannel()
        // Android Auto : JARVIS se présente comme appli média (écrans voiture désactivés hors Play Store), et l'ancienne
        // réinstallation en fond (qui tournait en boucle) est annulée.
        Thread {
            runCatching { fr.dd.jarvis.car.VisibiliteAuto.arreterAncienneReinstallation(this) }
            runCatching { fr.dd.jarvis.car.VisibiliteAuto.appliquerMode(this) }
        }.start()
        runCatching {
            val f = android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT)
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    Thread { runCatching {
                        fr.dd.jarvis.securite.Systemes.liste(this@JarvisApp).filter { it.actif && it.modele == "telephone_securise" }
                            .forEach { fr.dd.jarvis.securite.Systemes.sauver(this@JarvisApp, it.copy(actif = false)) }
                    } }.start()
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, android.content.Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        }
        // Surveillance de la maison par le téléphone : reprise après un redémarrage de JARVIS si un système est actif.
        Thread { runCatching { fr.dd.jarvis.securite.SurveillanceMaison.actualiser(this) } }.start()
        // Pont MCP local (outils de Jarvis pour Hermès) : seulement si Hermès est utilisé.
        if (store.hermesMode.value != "off" && store.hermesInstalle.value) runCatching { fr.dd.jarvis.hermes.PontMcp.demarrer(this) }
        // Nouvelle configuration Hermès (modèle principal = routeur Jarvis, outils Jarvis visibles directement) :
        // appliquée une fois automatiquement après la mise à jour, sans attendre un appui sur « Configurer ».
        if (store.hermesMode.value != "off" && store.hermesInstalle.value &&
            store.hermesConfigVersion.value < fr.dd.jarvis.hermes.HermesTermux.VERSION_CONFIG) graphScope.launch {
            kotlinx.coroutines.delay(20_000)
            val r = kotlinx.coroutines.withContext(Dispatchers.Main) { runCatching { fr.dd.jarvis.hermes.HermesTermux.synchroniserCles(this@JarvisApp) }.getOrDefault("") }
            if (r.startsWith("⏳")) store.hermesConfigVersion.set(fr.dd.jarvis.hermes.HermesTermux.VERSION_CONFIG)
        }
        if (store.briefingOn.value) Scheduler.schedule(this, store)
        if (store.briefingSoirOn.value) Scheduler.scheduleSoir(this, store)
        fr.dd.jarvis.work.AgentsScheduler.apply(this, store)
        fr.dd.jarvis.work.ReseauScheduler.apply(this, store)
        runCatching {
            androidx.car.app.connection.CarConnection(this).type.observeForever { t ->
                val avant = enVoiture
                enVoiture = t == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION
                // Seulement si JARVIS tournait déjà : un démarrage de JARVIS PAR un appui dans la voiture n'est pas une relance auto.
                val maintenant = android.os.SystemClock.elapsedRealtime()
                if (enVoiture && !avant && maintenant - lanceLe > 5_000) voitureDepuis = maintenant
                // Montée en voiture : accueil de JARVIS (prochain rendez-vous, météo) dans Android Auto.
                if (enVoiture && !avant) graphScope.launch(Dispatchers.IO) {
                    // Verdict d'Android Auto sur JARVIS (journal système), relevé peu après le branchement.
                    kotlinx.coroutines.delay(20_000); runCatching { fr.dd.jarvis.car.VisibiliteAuto.capturer(this@JarvisApp) }
                    kotlinx.coroutines.delay(60_000); runCatching { fr.dd.jarvis.car.VisibiliteAuto.capturer(this@JarvisApp) }
                }
                if (enVoiture && !avant) graphScope.launch {
                    runCatching { fr.dd.jarvis.car.AccueilVoiture.annoncer(this@JarvisApp) }
                    runCatching { fr.dd.jarvis.device.Routines.declencher(this@JarvisApp, "voiture") }
                }
                if (!enVoiture && avant) {
                    fr.dd.jarvis.car.VoixVoiture.liberer()
                    // Écoute « Jarvis » allumée seulement pour la voiture (bouton micro d'Android Auto) : on la coupe en sortant.
                    if (!store.wakeOn.value && fr.dd.jarvis.service.JarvisService.running) runCatching { fr.dd.jarvis.service.JarvisService.stop(this) }
                }
            }
        }
        // Connexion à un Wi-Fi : analyse automatique du réseau (appareils + IP publique).
        runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            cm.registerNetworkCallback(android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(),
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) { if (store.scanAuto.value) fr.dd.jarvis.work.ReseauScheduler.declencher(this@JarvisApp) }
                })
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_BRIEFING, "Point du matin", NotificationManager.IMPORTANCE_DEFAULT)
            )
            nm.createNotificationChannel(NotificationChannel(CHANNEL_AGENTS, "Agents JARVIS (alertes)", NotificationManager.IMPORTANCE_HIGH))
            runCatching { fr.dd.jarvis.device.Horloge.channels(this) }
            nm.createNotificationChannel(NotificationChannel(CHANNEL_CAR, "Android Auto (conversation JARVIS)", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_MSG, "SMS et mails (JARVIS en direct)", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_WAKE, "Écoute « Jarvis »", NotificationManager.IMPORTANCE_LOW))
            runCatching { fr.dd.jarvis.work.SuiviTaches.canaux(this) }
        }
    }

    /** Écrit les plantages dans un fichier, affiché au prochain lancement (pas besoin d'ADB). */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_FILE).writeText("Thread: ${t.name}\n\n$sw")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /**
     * Arrêts que le rapporteur ci-dessus ne voit pas : plantage NATIF (Vosk, llama.cpp…), appli figée (ANR), processus
     * tué par le système (mémoire, ressources). Android les garde en mémoire : relus au démarrage et ajoutés au rapport
     * de plantage affiché au lancement suivant (bouton « Copier »).
     */
    private fun noterArretsAnormaux() {
        if (Build.VERSION.SDK_INT < 30) return
        val am = getSystemService(android.app.ActivityManager::class.java) ?: return
        val prefs = getSharedPreferences("diagnostic_arrets", MODE_PRIVATE)
        val deja = prefs.getLong("dernier", 0L)
        // Première lecture (mise à jour) : les arrêts des dernières 48 h, ceux qui ont motivé ce relevé.
        val depuis = if (deja == 0L) System.currentTimeMillis() - 48 * 3_600_000L else deja
        val sorties = am.getHistoricalProcessExitReasons(packageName, 0, 16).filter { it.timestamp > depuis }
        if (sorties.isEmpty()) return
        prefs.edit().putLong("dernier", sorties.maxOf { it.timestamp }).apply()
        val graves = sorties.filter { it.reason in setOf(
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_ANR,
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, android.app.ApplicationExitInfo.REASON_CRASH) }
        if (graves.isEmpty()) return
        val heure = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.FRANCE)
        fun raison(r: Int) = when (r) {
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage natif"
            android.app.ApplicationExitInfo.REASON_ANR -> "appli figée (ANR)"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "tuée par le système (mémoire)"
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "tuée par le système (ressources)"
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "échec au démarrage"
            else -> "plantage Java"
        }
        val texte = buildString {
            append("Arrêts anormaux relevés par Android :\n")
            graves.sortedByDescending { it.timestamp }.forEach { e ->
                append("\n• ").append(heure.format(java.util.Date(e.timestamp))).append(" — ").append(raison(e.reason))
                append(" [").append(e.processName).append("]")
                e.description?.takeIf { it.isNotBlank() }?.let { append(" : ").append(it.take(300)) }
                append(" (mémoire ").append(e.pss / 1024).append(" Mo)\n")
                // ANR : début de la pile du fil principal, ce qui le bloquait.
                if (e.reason == android.app.ApplicationExitInfo.REASON_ANR) runCatching {
                    e.traceInputStream?.bufferedReader()?.use { r ->
                        val lignes = r.readLines()
                        val i = lignes.indexOfFirst { it.startsWith("\"main\"") }
                        if (i >= 0) lignes.drop(i).take(25).forEach { append("    ").append(it.trim()).append('\n') }
                    }
                }
            }
        }
        val f = File(filesDir, CRASH_FILE)
        // Relevé en tête (visible dans la fenêtre), puis la pile complète du dernier plantage Java s'il y en a une.
        f.writeText(if (f.exists()) texte + "\n\n" + f.readText().take(8000) else texte)
    }

    companion object {
        const val CHANNEL_BRIEFING = "briefing"
        const val CHANNEL_AGENTS = "agents"
        const val CHANNEL_WAKE = "wake"
        const val CHANNEL_MSG = "msg_live"
        const val CHANNEL_CAR = "car"
        const val CRASH_FILE = "last_crash.txt"
    }
}
