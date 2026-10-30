package fr.dd.jarvis

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import fr.dd.jarvis.ai.Llm
import fr.dd.jarvis.data.Store
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
    lateinit var phone: PhoneControl
        private set
    lateinit var memo: fr.dd.jarvis.device.Memo
        private set
    lateinit var societe: SocieteService
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
        store = Store(this)
        llm = Llm(store)
        calendar = CalendarRepo(this, store)
        mail = MailRepo(this, store)
        contacts = ContactsRepo(this)
        obsidian = ObsidianWriter(this, store)
        phone = PhoneControl(this)
        memo = fr.dd.jarvis.device.Memo(this, obsidian)
        Thread { runCatching { memo.load() } }.start()
        societe = SocieteService(this, SocieteRepo(this), obsidian)
        societe.data.modeles["planning"]?.let { fr.dd.jarvis.ai.Prompts.planningFormat = it }
        createChannel()
        if (store.briefingOn.value) Scheduler.schedule(this, store)
        fr.dd.jarvis.work.AgentsScheduler.apply(this, store)
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
            nm.createNotificationChannel(NotificationChannel(CHANNEL_WAKE, "Écoute « Jarvis »", NotificationManager.IMPORTANCE_LOW))
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
        const val CHANNEL_CAR = "car"
        const val CRASH_FILE = "last_crash.txt"
    }
}
