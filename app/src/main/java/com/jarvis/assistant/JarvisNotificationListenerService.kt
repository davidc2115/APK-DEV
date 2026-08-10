package com.jarvis.assistant

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

class JarvisNotificationListenerService : NotificationListenerService() {

    companion object {
        val latestNotifications = mutableListOf<String>()
        var isConnected = false

        fun getRecent(count: Int = 5): String {
            if (!isConnected) {
                return "⚠️ Le service de lecture des notifications n'est pas actif. Activez JARVIS dans Paramètres Android → Accès aux notifications."
            }
            if (latestNotifications.isEmpty()) {
                return "🔔 Aucune notification récente enregistrée."
            }

            val sb = StringBuilder("🔔 **Dernières notifications reçues** :\n\n")
            val items = latestNotifications.takeLast(count).reversed()
            items.forEachIndexed { i, notif ->
                sb.append("${i + 1}. $notif\n\n")
            }
            return sb.toString().trimEnd()
        }

        fun checkAndRequestAccess(context: Context): String {
            val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
            return if (enabledPackages.contains(context.packageName)) {
                "✅ Service de lecture des notifications activé."
            } else {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "⚙️ Veuillez activer l'accès aux notifications pour JARVIS dans l'écran qui vient de s'ouvrir."
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val pkg = sbn.packageName ?: "Inconnu"
        if (pkg == packageName) return // Ignorer les notifications de JARVIS lui-même

        val extras = sbn.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }

        val entry = "📱 **$appName** : $title ${if (text.isNotEmpty()) "— $text" else ""}"

        synchronized(latestNotifications) {
            latestNotifications.add(entry)
            if (latestNotifications.size > 50) {
                latestNotifications.removeAt(0)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
