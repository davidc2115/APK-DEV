package fr.dd.jarvis.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput as PlatformRemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Brain
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.ai.ToolBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android Auto : JARVIS apparaît comme une conversation de messagerie. Dans la voiture, Android Auto lit ses messages à voix haute
 * et propose « Répondre » : on parle, JARVIS exécute la demande sur le téléphone (appli, Waze, SMS, agenda…) et répond dans la conversation.
 */
object CarBridge {
    const val ACTION_REPLY = "fr.dd.jarvis.CAR_REPLY"
    const val ACTION_READ = "fr.dd.jarvis.CAR_READ"
    const val KEY_TEXT = "car_text"
    const val NOTIF_ID = 4100
    private val history = mutableListOf<ChatMsg>()

    private val jarvis get() = Person.Builder().setName("JARVIS").setKey("jarvis").setImportant(true).build()

    /** Publie (ou met à jour) la conversation JARVIS ; [texte] = dernier message de JARVIS. */
    fun post(ctx: Context, texte: String) {
        val me = Person.Builder().setName("Moi").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle("JARVIS")
        synchronized(history) { history.takeLast(8) }.forEach { m ->
            style.addMessage(m.text.take(500), m.ts, if (m.fromUser) me else jarvis)
        }
        style.addMessage(texte.take(600), System.currentTimeMillis(), jarvis)

        val flagsMut = PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val flagsImm = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val remote = RemoteInput.Builder(KEY_TEXT).setLabel("Parle à JARVIS").build()
        val replyPi = PendingIntent.getBroadcast(ctx, 11, Intent(ctx, CarReplyReceiver::class.java).setAction(ACTION_REPLY).setPackage(ctx.packageName), flagsMut)
        val reply = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Répondre", replyPi)
            .addRemoteInput(remote).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build()
        val readPi = PendingIntent.getBroadcast(ctx, 12, Intent(ctx, CarReplyReceiver::class.java).setAction(ACTION_READ).setPackage(ctx.packageName), flagsImm)
        val read = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_view, "Lu", readPi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()
        val open = PendingIntent.getActivity(ctx, 13, Intent(ctx, fr.dd.jarvis.MainActivity::class.java), flagsImm)

        val n = NotificationCompat.Builder(ctx, JarvisApp.CHANNEL_CAR)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setStyle(style).setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false).setAutoCancel(false).setContentIntent(open)
            .addAction(reply).addAction(read).build()
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n) }
    }

    fun ajouter(m: ChatMsg) { synchronized(history) { history.add(m); while (history.size > 20) history.removeAt(0) } }
    fun contexte(): List<ChatMsg> = synchronized(history) { history.toList() }
}

class CarReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CarBridge.ACTION_READ) return
        val texte = androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CarBridge.KEY_TEXT)?.toString()?.trim().orEmpty()
        if (texte.isBlank()) return
        val app = context.applicationContext as JarvisApp
        val pending = goAsync()
        CarBridge.ajouter(ChatMsg(true, texte))
        CoroutineScope(Dispatchers.IO).launch {
            val rep = try {
                // En voiture : les actions sensibles (envoi de SMS, appel…) qui demandent une confirmation forcée sont refusées.
                val brain = Brain(app.llm, ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }
                brain.reply(CarBridge.contexte()) { }
            } catch (e: Throwable) { "Désolé, je n'ai pas pu faire ça : ${e.message ?: e.javaClass.simpleName}" }
            val propre = rep.replace(Regex("[#*_`>|]+"), "").replace(Regex("\\s+"), " ").trim().ifBlank { "C'est fait." }
            CarBridge.ajouter(ChatMsg(false, propre))
            CarBridge.post(context, propre)
            pending.finish()
        }
    }
}
