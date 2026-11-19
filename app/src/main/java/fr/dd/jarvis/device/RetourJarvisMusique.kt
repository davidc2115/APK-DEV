package fr.dd.jarvis.device

import android.content.Context
import android.content.Intent
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.MainActivity

/**
 * Après le démarrage confirmé d'une musique, ramène l'interface JARVIS au premier plan.
 * L'application musicale reste en arrière-plan avec sa session active.
 * Si le mode Présence était actif, on le réaffiche explicitement (notamment pendant Android Auto).
 */
object RetourJarvisMusique {
    fun afficher(ctx: Context): Boolean {
        val app = ctx.applicationContext as? JarvisApp ?: return false
        val presence = app.store.presenceModeActif.value
        val intent = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (presence) putExtra("terrain", "presence")
        }
        return runCatching {
            app.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
