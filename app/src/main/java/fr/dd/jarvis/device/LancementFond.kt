package fr.dd.jarvis.device

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Ouverture d'une appli quand JARVIS est en arrière-plan (Android Auto, écran éteint, autre appli devant).
 * Android bloque ces lancements EN SILENCE (aucune erreur) : JARVIS disait « navigation lancée » alors que Waze
 * ne s'ouvrait pas. Depuis Android 15, le droit « superposition » ne suffit plus : il faut une fenêtre VISIBLE.
 * JARVIS affiche donc un point de 2 pixels quasi transparent le temps du lancement (puis le retire), et passe par son
 * service d'accessibilité s'il est actif.
 */
object LancementFond {
    private val main = Handler(Looper.getMainLooper())

    /**
     * Lance réellement l’activité sur le thread UI. Les outils JARVIS sont souvent exécutés sur Dispatchers.IO :
     * appeler startActivity depuis ce thread de travail rendait certains lancements GPS silencieux ou aléatoires.
     * Le résultat indique si Android a accepté startActivity, pas si le guidage a commencé.
     */
    fun demarrer(ctx: Context, i: Intent): Boolean {
        val app = ctx.applicationContext
        val intent = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val point = if (Build.VERSION.SDK_INT >= 26 && runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)) afficherPoint(app) else null
        fr.dd.jarvis.voice.JournalVoix.noter("lancement", "${intent.`package` ?: intent.data?.scheme ?: "appli"} : superposition ${if (point != null) "affichée" else "non autorisée"}, " +
            "accessibilité ${if (fr.dd.jarvis.service.JarvisAccessibilityService.instance != null) "active" else "inactive"}")
        val erreur = AtomicReference<Throwable?>(null)
        val fini = CountDownLatch(1)
        val lancer = Runnable {
            try {
                // Un seul chemin explicite et observable ; ne pas compter un appel d’accessibilité silencieux comme un succès.
                app.startActivity(intent)
            } catch (t: Throwable) {
                erreur.set(t)
            } finally {
                fini.countDown()
            }
        }
        val execute = if (Looper.myLooper() == Looper.getMainLooper()) {
            lancer.run(); true
        } else {
            main.post(lancer) && fini.await(3, TimeUnit.SECONDS)
        }
        point?.let { v -> main.postDelayed({ retirer(app, v) }, 2500) }
        val ok = execute && erreur.get() == null
        fr.dd.jarvis.voice.JournalVoix.noter("lancement", "${intent.`package` ?: intent.data?.scheme ?: "appli"} : startActivity ${if (ok) "accepté" else "échoué"}${erreur.get()?.let { " (${it.javaClass.simpleName}: ${it.message})" }.orEmpty()}")
        return ok
    }

    private fun afficherPoint(ctx: Context): View? {
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return null
        var vue: View? = null
        val fini = CountDownLatch(1)
        val faire = Runnable {
            runCatching {
                val v = View(ctx).apply { setBackgroundColor(0x01000000) }
                val lp = WindowManager.LayoutParams(2, 2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
                wm.addView(v, lp)
                vue = v
            }
            fini.countDown()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) faire.run()
        else {
            main.post(faire)
            fini.await(800, TimeUnit.MILLISECONDS)
            // Le temps que la fenêtre soit réellement affichée (une image) avant le lancement.
            if (vue != null) Thread.sleep(150)
        }
        return vue
    }

    private fun retirer(ctx: Context, v: View) {
        runCatching { ctx.getSystemService(WindowManager::class.java)?.removeView(v) }
    }
}
