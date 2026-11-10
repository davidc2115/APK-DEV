package fr.dd.jarvis.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.dd.jarvis.MainActivity

/** Confirmation visible au-dessus des autres applications si l'utilisateur a autorisé la superposition,
 *  avec notification prioritaire en complément / solution de repli.
 */
object ConfirmationNotification {
    const val ACTION = "fr.dd.jarvis.CONFIRMATION"
    const val EXTRA_OK = "jarvis_confirmation_ok"
    private const val CHANNEL = "jarvis_confirmations_v1"
    private const val ID = 48120
    @Volatile private var overlay: View? = null
    @Volatile private var overlayManager: WindowManager? = null

    private fun intent(context: Context, accepte: Boolean): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_OK, accepte)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, if (accepte) 48121 else 48122, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun afficher(context: Context, question: String) {
        // Un seul canal visible à la fois : la fenêtre flottante est prioritaire.
        // La notification ne sert que de repli si la superposition n'est pas autorisée ou impossible.
        if (afficherOverlay(context.applicationContext, question)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Confirmations JARVIS", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Demandes d'acceptation et de confirmation des actions de JARVIS"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }
        val ouvrir = PendingIntent.getActivity(context, 48123,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("JARVIS — confirmation requise")
            .setContentText(question.take(160))
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(ouvrir)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Refuser", intent(context, false))
            .addAction(android.R.drawable.ic_menu_send, "Accepter", intent(context, true))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }

    /** Fenêtre de confirmation tactile au-dessus de toute application (accord SYSTEM_ALERT_WINDOW requis). */
    private fun afficherOverlay(context: Context, question: String): Boolean {
        if (Build.VERSION.SDK_INT < 23 || !Settings.canDrawOverlays(context)) return false
        val programme = java.util.concurrent.CountDownLatch(1)
        val affiche = java.util.concurrent.atomic.AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post {
            try {
                retirerOverlay()
                val dm = context.resources.displayMetrics
                val marge = (16 * dm.density).toInt()
                val racine = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(marge, marge, marge, marge)
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(15, 18, 30))
                        cornerRadius = 22 * dm.density
                        setStroke((1.5f * dm.density).toInt(), Color.rgb(145, 105, 220))
                    }
                    elevation = 18 * dm.density
                }
                racine.addView(TextView(context).apply {
                    text = "JARVIS · CONFIRMATION"
                    setTextColor(Color.rgb(190, 155, 255))
                    textSize = 12f
                    letterSpacing = 0.08f
                })
                racine.addView(TextView(context).apply {
                    text = question
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding(0, (10 * dm.density).toInt(), 0, (14 * dm.density).toInt())
                })
                val actions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                fun action(label: String, accepte: Boolean) = Button(context).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener {
                        retirerOverlay()
                        runCatching { intent(context, accepte).send() }
                    }
                }
                actions.addView(action("Refuser", false))
                actions.addView(action("Accepter", true))
                racine.addView(actions)
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else WindowManager.LayoutParams.TYPE_PHONE
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = 0
                    y = (24 * dm.density).toInt()
                }
                wm.addView(racine, params)
                overlay = racine
                overlayManager = wm
                affiche.set(true)
            } catch (_: Exception) {
                affiche.set(false)
            } finally {
                programme.countDown()
            }
        }
        return try {
            programme.await(700, java.util.concurrent.TimeUnit.MILLISECONDS) && affiche.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun retirerOverlay() {
        val v = overlay ?: return
        val wm = overlayManager
        overlay = null
        overlayManager = null
        runCatching { wm?.removeView(v) }
    }

    fun annuler(context: Context) {
        Handler(Looper.getMainLooper()).post { retirerOverlay() }
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }
}
