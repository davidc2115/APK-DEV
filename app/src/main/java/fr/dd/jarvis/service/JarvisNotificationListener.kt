package fr.dd.jarvis.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Capture les notifications du téléphone (en mémoire) pour que JARVIS puisse les lire, les résumer et alerter. */
class JarvisNotificationListener : NotificationListenerService() {

    data class Item(val pkg: String, val app: String, val titre: String, val texte: String, val time: Long)

    companion object {
        private val items = ArrayDeque<Item>()
        @Volatile var active = false

        @Synchronized fun snapshot(): List<Item> = items.toList()
        @Synchronized private fun add(i: Item) { items.addFirst(i); while (items.size > 300) items.removeLast() }
    }

    override fun onListenerConnected() { active = true }
    override fun onListenerDisconnected() { active = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName) return
            val n = sbn.notification ?: return
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
            val e = n.extras
            val titre = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val texte = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (titre.isBlank() && texte.isBlank()) return
            val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
            add(Item(sbn.packageName, label, titre, texte, sbn.postTime))
            // Annonce vocale (SMS, mails, autres notifications choisies) avec sonnerie réglable
            runCatching {
                fr.dd.jarvis.device.Annonces.traiter(applicationContext, applicationContext as fr.dd.jarvis.JarvisApp, sbn.key + sbn.postTime, sbn.packageName, label, n.category, titre, texte)
            }
        } catch (_: Throwable) {
        }
    }
}
