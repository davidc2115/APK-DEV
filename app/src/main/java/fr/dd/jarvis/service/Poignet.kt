package fr.dd.jarvis.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import fr.dd.jarvis.JarvisApp

/**
 * JARVIS au poignet : la conversation JARVIS (notification de messagerie) est relayée sur la montre connectée
 * (Wear OS, Galaxy Watch… et la plupart des montres qui affichent les notifications). « Répondre » → dicter à la montre
 * ou choisir une réponse toute faite (routines, prochain rendez-vous…) ; la réponse de JARVIS revient sur la montre.
 * La veille la republie sans bruit si elle a été balayée.
 */
object Poignet {
    private const val CANAL = "poignet_jarvis"

    fun canal(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, "JARVIS sur la montre", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Conversation avec JARVIS relayée sur la montre : réponds pour lui parler."
            })
        return CANAL
    }

    /** Réponses toutes faites proposées sur la montre (5 au plus). */
    fun choix(app: JarvisApp): List<String> =
        (fr.dd.jarvis.device.Routines.liste(app).filter { it.active && it.declencheur == "phrase" }.mapNotNull { it.phrases.firstOrNull()?.replaceFirstChar { c -> c.uppercase() } } +
            listOf("Mon prochain rendez-vous ?", "Résume mes mails non lus", "Quel temps fait-il ?", "Fais le point de ma journée"))
            .distinct().take(5)

    private fun presente(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == CarBridge.NOTIF_ID }
    }.getOrDefault(false)

    /** Activée : la conversation est présente (republiée sans bruit si besoin). */
    fun assurer(app: JarvisApp) {
        if (!app.store.poignet.value || app.enVoiture || presente(app)) return
        val dernier = CarBridge.contexte().lastOrNull { !it.fromUser }?.text
        CarBridge.post(app, dernier ?: "Je suis là. Réponds à ce message pour me parler.", silencieux = true)
    }

    fun activer(app: JarvisApp, oui: Boolean) {
        app.store.poignet.set(oui)
        if (oui) CarBridge.post(app, "Je suis sur ta montre. Réponds à ce message (à la voix ou avec une réponse proposée) pour me parler.")
        else if (!app.enVoiture) runCatching { app.getSystemService(NotificationManager::class.java).cancel(CarBridge.NOTIF_ID) }
    }
}
