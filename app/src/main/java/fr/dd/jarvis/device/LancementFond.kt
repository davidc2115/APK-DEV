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

/**
 * Ouverture d'une appli quand JARVIS est en arrière-plan (Android Auto, écran éteint, autre appli devant).
 * Android bloque ces lancements EN SILENCE (aucune erreur) : JARVIS disait « navigation lancée » alors que Waze
 * ne s'ouvrait pas. Depuis Android 15, le droit « superposition » ne suffit plus : il faut une fenêtre VISIBLE.
 * JARVIS affiche donc un point de 2 pixels quasi transparent le temps du lancement (puis le retire), et passe par son
 * service d'accessibilité s'il est actif.
 */
object LancementFond {
    private val main = Handler(Looper.getMainLooper())

    fun demarrer(ctx: Context, i: Intent) {
        val app = ctx.applicationContext
        val point = if (Build.VERSION.SDK_INT >= 26 && runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)) afficherPoint(app) else null
        fr.dd.jarvis.voice.JournalVoix.noter("lancement", "${i.`package` ?: i.data?.scheme ?: "appli"} : superposition ${if (point != null) "affichée" else "non autorisée"}, " +
            "accessibilité ${if (fr.dd.jarvis.service.JarvisAccessibilityService.instance != null) "active" else "inactive"}")
        try {
            val acc = fr.dd.jarvis.service.JarvisAccessibilityService.instance
            if (acc != null) runCatching { acc.startActivity(i) }.getOrElse { app.startActivity(i) } else app.startActivity(i)
        } finally {
            point?.let { v -> main.postDelayed({ retirer(app, v) }, 2500) }
        }
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
