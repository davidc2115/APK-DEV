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
            android.os.Handler(android.os.Looper.getMainLooper()).let { h -> if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) chat.addAll(l) else h.post { chat.addAll(l) } }
        }
    }

    /** Ajoute un message (thread principal) et sauvegarde. */
    fun chatAjouter(m: fr.dd.jarvis.ai.ChatMsg) {
        chat.add(m); chatSauver()
    }

    fun chatSauver() {
        runCatching {
            val arr = org.json.JSONArray()
            chat.toList().takeLast(200).forEach { arr.put(org.json.JSONObject().put("u", it.fromUser).put("t", it.text).put("ts", it.ts)) }
            java.io.File(filesDir, "chat.json").writeText(arr.toString())
        }
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
        store = Store(this)
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
        llm = Llm(store)
        calendar = CalendarRepo(this, store)
        mail = MailRepo(this, store)
        contacts = ContactsRepo(this)
        obsidian = ObsidianWriter(this, store)
        val graphDatabase = GraphDatabase.getInstance(this)
        val graphIndexer = GraphIndexer(graphDatabase, obsidian)
        knowledgeRepository = KnowledgeRepository(graphDatabase, graphIndexer)
        // Index du graphe Obsidian (lecture de 400 notes) : au plus toutes les 6 h, et pas pendant les 30 premières
        // secondes (le processus démarre aussi pour les tâches de fond : inutile de relire tout le coffre à chaque fois).
        if (System.currentTimeMillis() - (store.grapheIndexeLe.value.toLongOrNull() ?: 0L) > 6 * 3_600_000L) graphScope.launch {
            kotlinx.coroutines.delay(30_000)
            graphIndexReport = runCatching { knowledgeRepository.rebuildMarkdownIndex() }.getOrNull()
            if (graphIndexReport != null) store.grapheIndexeLe.set(System.currentTimeMillis().toString())
        }
        fr.dd.jarvis.device.Energie.suivre(this)
        phone = PhoneControl(this)
        memo = fr.dd.jarvis.device.Memo(this, obsidian)
        Thread { runCatching { memo.load() } }.start()
        tasks = fr.dd.jarvis.data.TaskStore(this)
        societe = SocieteService(this, SocieteRepo(this), obsidian)
        fr.dd.jarvis.work.VeilleWorker.planifier(this)
        runCatching { fr.dd.jarvis.coffre.Coffre.planifier(this) }
        // Une fois : les rapports d'intervention existants sont régénérés avec leurs photos dans le PDF.
        if (!store.rapportsPhotosV2.value) Thread {
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
        // Pont MCP local (outils de Jarvis pour Hermès) : seulement si Hermès est utilisé.
        if (store.hermesMode.value != "off" && store.hermesInstalle.value) runCatching { fr.dd.jarvis.hermes.PontMcp.demarrer(this) }
        if (store.briefingOn.value) Scheduler.schedule(this, store)
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

    companion object {
        const val CHANNEL_BRIEFING = "briefing"
        const val CHANNEL_AGENTS = "agents"
        const val CHANNEL_WAKE = "wake"
        const val CHANNEL_MSG = "msg_live"
        const val CHANNEL_CAR = "car"
        const val CRASH_FILE = "last_crash.txt"
    }
}
