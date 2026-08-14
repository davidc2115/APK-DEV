package com.jarvis.ai.core.phonecontrol

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lecture des notifications système (nécessite l'autorisation "Accès aux notifications",
 * accordée manuellement par l'utilisateur dans les réglages Android — pas une permission
 * runtime classique). Permet à Jarvis de résumer/lire les notifications sur demande.
 */
class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // TODO: transmettre au ChatViewModel / historique (filtré par app autorisée par l'utilisateur).
    }
}
