package fr.dd.jarvis.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Capture les notifications du téléphone (en mémoire) pour que JARVIS puisse les lire, les résumer et alerter. */
class JarvisNotificationListener : NotificationListenerService() {

    data class Item(val pkg: String, val app: String, val titre: String, val texte: String, val time: Long)

    companion object {
        private val items = ArrayDeque<Item>()
        @Volatile var active = false
        // Sessions qui jouaient avant la réponse vocale et que Jarvis a explicitement mises en pause.
        private var sessionsPauseesPourVoix: List<MediaController> = emptyList()

        /**
         * Demande une vraie pause aux lecteurs actifs lorsque le focus audio seul est ignoré.
         * L'accès aux sessions est autorisé par le rôle Notification Listener déjà accordé à Jarvis.
         */
        @Synchronized fun pauseMediaPourVoix(ctx: Context): Boolean = runCatching {
            val manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val composant = ComponentName(ctx, JarvisNotificationListener::class.java)
            val enLecture = manager.getActiveSessions(composant).filter {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            sessionsPauseesPourVoix = enLecture
            enLecture.forEach { runCatching { it.transportControls.pause() } }
            enLecture.isNotEmpty()
        }.getOrDefault(false)

        /** Reprend uniquement les sessions que Jarvis avait mises en pause et qui sont toujours en pause. */
        @Synchronized fun reprendreMediaApresVoix() {
            val sessions = sessionsPauseesPourVoix
            sessionsPauseesPourVoix = emptyList()
            sessions.forEach { controller ->
                if (runCatching { controller.playbackState?.state == PlaybackState.STATE_PAUSED }.getOrDefault(false)) {
                    runCatching { controller.transportControls.play() }
                }
            }
        }

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
    override fun onListenerDisconnected() {
        active = false
        runCatching { requestRebind(android.content.ComponentName(this, JarvisNotificationListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName) return
            val n = sbn.notification ?: return
            val appel = n.category == Notification.CATEGORY_CALL
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 && !appel)) return
            val e = n.extras
            var titre = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val texte = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (titre.isBlank() && texte.isBlank()) return
            // Commande anti-vol (« JARVIS <code> … ») : jamais gardée, résumée ni lue à voix haute (elle contient le code).
            // Dernier message d'une conversation (style « messagerie ») : c'est lui qui vient d'arriver.
            val dernierMessage = runCatching {
                @Suppress("DEPRECATION") e.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull()?.let { (it as? android.os.Bundle)?.getCharSequence("text")?.toString() }
            }.getOrNull().orEmpty()
            val commande = listOf(texte, dernierMessage).firstOrNull { it.isNotBlank() && fr.dd.jarvis.securite.AntiVol.ressembleCommande(it) }
            // Diagnostic de l'anti-vol : un message qui commence par « JARVIS » a bien été vu dans une notification.
            if (listOf(texte, dernierMessage).any { it.trimStart().startsWith("jarvis", ignoreCase = true) } && (estMessage(sbn.packageName) || n.category == Notification.CATEGORY_MESSAGE))
                runCatching { (applicationContext as fr.dd.jarvis.JarvisApp).store.antivolDerniereNotif.set(System.currentTimeMillis().toString()) }
            if (commande != null) {
                // Secours de l'anti-vol : message en « Chat » (RCS) ou SMS non transmis par Android → lu ici.
                if (estMessage(sbn.packageName) || n.category == Notification.CATEGORY_MESSAGE) {
                    (applicationContext as? fr.dd.jarvis.JarvisApp)?.let { a -> runCatching { fr.dd.jarvis.securite.AntiVol.depuisNotification(a, titre, commande, n) } }
                    // La notification contient le code : retirée tout de suite (plus visible sur l'écran verrouillé).
                    runCatching { cancelNotification(sbn.key) }
                }
                return
            }
            val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
            if (appel) {
                val candidat = Regex("(?:\\+?\\d[\\d .()/-]{6,}\\d)").find("$titre $texte")?.value.orEmpty()
                val app = applicationContext as? fr.dd.jarvis.JarvisApp
                val identite = if (candidat.isNotBlank() && app != null) runCatching { fr.dd.jarvis.device.Telephony.identifierAppel(app, candidat) }.getOrNull() else null
                if (!identite.isNullOrBlank()) titre = "$identite · $titre".trim(' ', '·')
                else if (titre.isBlank()) titre = "Appel entrant"
            }
            val message = estMessage(sbn.packageName) || n.category == Notification.CATEGORY_MESSAGE
            // Seules les notifications d'appels manqués sont masquées. La notification d'appel entrant
            // reste visible pour ne jamais gêner l'écran permettant de répondre.
            val appelManque = n.category == Notification.CATEGORY_MISSED_CALL
            add(Item(sbn.packageName, label, titre, texte, sbn.postTime))
            sauver(applicationContext)
            val mail = estMail(sbn.packageName)
            // Jarvis ne republie plus de notification pour les SMS, mails et appels : celles d'Android suffisent.
            // Il les garde en mémoire pour les lire, les résumer, les annoncer à voix haute et alimenter ses agents.
            // Agents « en direct » : un nouveau SMS / message / mail déclenche tout de suite le passage de l'agent concerné.
            runCatching {
                val st = (applicationContext as fr.dd.jarvis.JarvisApp).store
                when {
                    mail && st.agentsOn.value && st.agentMails.value -> fr.dd.jarvis.work.AgentsScheduler.declencher(applicationContext, "mails")
                    message && st.agentsOn.value && st.agentNotifs.value -> fr.dd.jarvis.work.AgentsScheduler.declencher(applicationContext, "notifs")
                }
            }
            // Annonce vocale (SMS, mails, autres notifications choisies) avec sonnerie réglable
            runCatching {
                fr.dd.jarvis.device.Annonces.traiter(applicationContext, applicationContext as fr.dd.jarvis.JarvisApp, sbn.key + sbn.postTime, sbn.packageName, label, n.category, titre, texte)
            }
            // Les notifications d'Android (SMS, mails, appels, appels manqués) restent affichées : Jarvis les garde seulement
            // en mémoire pour les lire, les résumer et les annoncer — il n'en retire plus aucune (sauf commande anti-vol).
        } catch (_: Throwable) {
        }
    }
}
