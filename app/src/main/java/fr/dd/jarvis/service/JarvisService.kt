package fr.dd.jarvis.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * Écoute en arrière-plan : dis « Jarvis, … » (ex. « Jarvis, lance un itinéraire vers chez Dupont »).
 * Reconnaissance vocale Android en boucle ; la réponse est dite à voix haute et affichée en notification.
 */
class JarvisService : Service() {

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "fr.dd.jarvis.WAKE_STOP"
        private const val NOTIF = 4001

        fun start(ctx: Context) {
            val i = Intent(ctx, JarvisService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, JarvisService::class.java)) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var voice: VoiceManager? = null
    private var wake: PowerManager.WakeLock? = null
    private var busy = false
    private var followUp = false
    private var confirm: CompletableDeferred<Boolean>? = null
    private var failures = 0
    private val history = mutableListOf<ChatMsg>()
    private lateinit var app: JarvisApp
    private lateinit var brain: Brain

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private val wakeRegex = Regex("\\b(jarvis|jarvice|jarviss|djarvis|jarvi|jarves|charvis)\\b[ ,.:!?-]*")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app = application as JarvisApp
            app.store.wakeOn.set(false)
            stopSelf(); return START_NOT_STICKY
        }
        if (running) return START_STICKY
        app = application as JarvisApp
        val pi = PendingIntent.getService(this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, fr.dd.jarvis.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, JarvisApp.CHANNEL_WAKE).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS à l'écoute").setContentText("Dis « Jarvis, … » pour une demande").setOngoing(true)
            .setContentIntent(open).addAction(0, "Arrêter", pi).build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIF, n)
        } catch (e: Throwable) { app.store.wakeOn.set(false); stopSelf(); return START_NOT_STICKY }
        running = true
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:wake").apply { setReferenceCounted(false); acquire() }
        val tools = ToolBox(app) { q, _ -> voiceConfirm(q) }
        brain = Brain(app.llm, tools, app) { app.store.userName.value }
        voice = VoiceManager(this).also { v ->
            v.onFinal = { t -> failures = 0; handle(t) }
            v.onFail = { _ ->
                failures++
                if (!busy || confirm != null) main.postDelayed({ listen() }, if (failures > 4) 5000L else 1200L)
            }
        }
        main.postDelayed({ listen() }, 1500)
        return START_STICKY
    }

    private fun listen() { if (running) voice?.startListening() }

    private fun handle(text: String) {
        confirm?.let { d ->
            val t = norm(text)
            d.complete(Regex("\\b(oui|ouais|ok|okay|confirme|vas-y|d'accord|yes|allez)\\b").containsMatchIn(t)); return
        }
        if (busy) return
        val t = norm(text)
        val cmd: String
        if (followUp) { cmd = text.trim(); followUp = false }
        else {
            val m = wakeRegex.find(t)
            if (m == null) { listen(); return }
            cmd = t.substring(m.range.last + 1).trim().ifBlank { "" }
            if (cmd.isBlank()) { followUp = true; voice?.speak("Oui ?") { listen() }; return }
        }
        process(cmd)
    }

    private fun process(cmd: String) {
        busy = true
        scope.launch {
            history += ChatMsg(true, cmd)
            val reply = try { brain.reply(history.takeLast(10)) { } } catch (e: CancellationException) { throw e } catch (e: Throwable) { "Erreur : ${e.message ?: e.javaClass.simpleName}" }
            history += ChatMsg(false, reply)
            notifier(cmd, reply)
            withContext(Dispatchers.Main) {
                voice?.speak(reply.take(700)) { busy = false; listen() } ?: run { busy = false }
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
        withContext(Dispatchers.Main) { voice?.speak(question.take(300) + " Dis oui ou non.") { listen() } }
        val r = withTimeoutOrNull(25_000) { d.await() } ?: false
        confirm = null
        return r
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        voice?.release(); voice = null
        runCatching { wake?.release() }
        super.onDestroy()
    }
}
