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

        private fun fichier(ctx: android.content.Context) = java.io.File(ctx.filesDir, "notifs.json")

        /** Sauvegarde les dernières notifications pour qu'elles survivent à un redémarrage du processus. */
        @Synchronized fun sauver(ctx: android.content.Context) {
            runCatching {
                val arr = org.json.JSONArray()
                items.take(120).forEach { arr.put(org.json.JSONObject().put("p", it.pkg).put("a", it.app).put("t", it.titre).put("x", it.texte).put("d", it.time)) }
                fichier(ctx).writeText(arr.toString())
            }
        }

        @Synchronized fun recharger(ctx: android.content.Context) {
            if (items.isNotEmpty()) return
            runCatching {
                val f = fichier(ctx); if (!f.exists()) return
                val arr = org.json.JSONArray(f.readText())
                for (i in 0 until arr.length()) arr.getJSONObject(i).let { o -> items.addLast(Item(o.getString("p"), o.getString("a"), o.getString("t"), o.getString("x"), o.getLong("d"))) }
            }
        }

        val MESSAGERIES = listOf("messaging", "mms", "whatsapp", "telegram", "signal", "sms", "messenger", "orca", "textra")
        val MAILS = listOf("com.google.android.gm", "mail", "outlook", "proton", "yahoo")
        fun estMessage(pkg: String) = MESSAGERIES.any { pkg.contains(it, true) }
        fun estMail(pkg: String) = MAILS.any { pkg.contains(it, true) }
    }

    override fun onListenerConnected() { active = true; recharger(applicationContext) }
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
            sauver(applicationContext)
            // Agents « en direct » : un nouveau SMS / message / mail déclenche tout de suite le passage de l'agent concerné.
            runCatching {
                val st = (applicationContext as fr.dd.jarvis.JarvisApp).store
                if (st.agentsOn.value) when {
                    estMail(sbn.packageName) && st.agentMails.value -> fr.dd.jarvis.work.AgentsScheduler.declencher(applicationContext, "mails")
                    (estMessage(sbn.packageName) || n.category == Notification.CATEGORY_MESSAGE) && st.agentNotifs.value -> fr.dd.jarvis.work.AgentsScheduler.declencher(applicationContext, "notifs")
                }
            }
            // Annonce vocale (SMS, mails, autres notifications choisies) avec sonnerie réglable
            runCatching {
                fr.dd.jarvis.device.Annonces.traiter(applicationContext, applicationContext as fr.dd.jarvis.JarvisApp, sbn.key + sbn.postTime, sbn.packageName, label, n.category, titre, texte)
            }
        } catch (_: Throwable) {
        }
    }
}
