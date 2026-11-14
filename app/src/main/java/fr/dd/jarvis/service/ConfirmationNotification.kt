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
    const val EXTRA_TOUJOURS = "jarvis_confirmation_toujours"
    const val EXTRA_COMMANDE = "jarvis_confirmation_commande"
    /** Question affichée (pour « Toujours autoriser » depuis la notification ou la fenêtre flottante). */
    @Volatile var questionCourante: String = ""
    private const val CHANNEL = "jarvis_confirmations_v1"
    private const val ID = 48120
    @Volatile private var overlay: View? = null
    @Volatile private var overlayManager: WindowManager? = null
    /** JARVIS est affiché à l'écran (MainActivity démarrée) : sa propre fenêtre de confirmation suffit. */
    @Volatile var appVisible = false
    /** Réponse directe à la demande en cours (fournie par l'écran de conversation) : « Accepter » / « Refuser »
     *  depuis la fenêtre flottante ou la notification répondent sans ramener JARVIS au premier plan. */
    @Volatile var reponse: ((Boolean) -> Unit)? = null

    /** Applique la réponse sur place ; faux si personne n'attend (on ouvre alors JARVIS en secours). */
    fun repondre(context: Context, accepte: Boolean): Boolean {
        val r = reponse ?: return false
        annuler(context.applicationContext)
        Handler(Looper.getMainLooper()).post { r(accepte) }
        return true
    }

    /** Bouton de notification : diffusion interne traitée par [ConfirmationReceiver], sans ouvrir l'application. */
    private fun intentDirect(context: Context, accepte: Boolean, toujours: Boolean = false, commande: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(context, if (commande) 48127 else if (toujours) 48126 else if (accepte) 48124 else 48125,
            Intent(context, ConfirmationReceiver::class.java).setAction(ACTION).putExtra(EXTRA_OK, accepte).putExtra(EXTRA_TOUJOURS, toujours).putExtra(EXTRA_COMMANDE, commande),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun intent(context: Context, accepte: Boolean): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_OK, accepte)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, if (accepte) 48121 else 48122, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun afficher(context: Context, question: String) {
        // Nettoie une éventuelle confirmation précédente (notification persistante comprise) avant d'en afficher une.
        // Une seule demande utilisateur peut être visible à la fois, même si plusieurs outils travaillent en parallèle.
        annuler(context.applicationContext)
        questionCourante = question
        // Dans l'application, la fenêtre de confirmation de JARVIS est déjà affichée : pas de doublon par-dessus.
        if (appVisible) return
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
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Refuser", intentDirect(context, false))
            .addAction(android.R.drawable.ic_menu_send, "Accepter", intentDirect(context, true))
            // Trois boutons au plus dans une notification : « Toujours » vise cette commande précise quand c'est possible.
            .apply {
                if (fr.dd.jarvis.device.Autorisations.commandePossible(question)) addAction(android.R.drawable.ic_menu_send, "Toujours (cette commande)", intentDirect(context, true, commande = true))
                else if (fr.dd.jarvis.device.Autorisations.clePour(question) != null) addAction(android.R.drawable.ic_menu_send, "Toujours", intentDirect(context, true, toujours = true))
            }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }

    /** Fenêtre de confirmation tactile au-dessus de toute application (accord SYSTEM_ALERT_WINDOW requis). */
    private fun afficherOverlay(context: Context, question: String): Boolean {
        if (Build.VERSION.SDK_INT < 23 || !Settings.canDrawOverlays(context)) return false
        Handler(Looper.getMainLooper()).post {
            runCatching {
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
                // Texte qui défile dans une hauteur bornée (55 % de l'écran) : les boutons restent toujours visibles.
                val defilement = object : android.widget.ScrollView(context) {
                    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec((dm.heightPixels * 0.55).toInt(), android.view.View.MeasureSpec.AT_MOST))
                }.apply { isVerticalScrollBarEnabled = true; isFillViewport = false }
                defilement.addView(TextView(context).apply {
                    text = question
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding(0, (10 * dm.density).toInt(), 0, (14 * dm.density).toInt())
                })
                racine.addView(defilement)
                fun action(label: String, accepte: Boolean, toujours: Boolean = false, commande: Boolean = false) = Button(context).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener {
                        retirerOverlay()
                        (context.applicationContext as? fr.dd.jarvis.JarvisApp)?.let {
                            if (toujours) fr.dd.jarvis.device.Autorisations.toujours(it, question)
                            if (commande) fr.dd.jarvis.device.Autorisations.toujoursCommande(it, question)
                        }
                        // On reste dans l'application en cours : JARVIS n'est rouvert que si plus rien n'attend la réponse.
                        if (!repondre(context, accepte)) runCatching { intent(context, accepte).send() }
                    }
                }
                val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
                actions.addView(action("Refuser", false))
                actions.addView(action("Accepter", true))
                racine.addView(actions)
                // Options « toujours » sur leur propre ligne (jamais poussées hors de l'écran).
                val toujours = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
                if (fr.dd.jarvis.device.Autorisations.commandePossible(question)) toujours.addView(action("Toujours pour cette commande", true, commande = true))
                fr.dd.jarvis.device.Autorisations.clePour(question)?.let { toujours.addView(action("Toujours : " + fr.dd.jarvis.device.Autorisations.libelle(it), true, toujours = true)) }
                if (toujours.childCount > 0) racine.addView(toujours)
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
            }
        }
        // L'autorisation est active : on réserve la confirmation à la fenêtre flottante.
        // Si le système refuse exceptionnellement addView, on évite tout de même le doublon de notification.
        return true
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

/** « Accepter » / « Refuser » d'une notification de confirmation : réponse appliquée sans ouvrir JARVIS. */
class ConfirmationReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = intent.getBooleanExtra(ConfirmationNotification.EXTRA_OK, false)
        if (ok && intent.getBooleanExtra(ConfirmationNotification.EXTRA_TOUJOURS, false))
            (context.applicationContext as? fr.dd.jarvis.JarvisApp)?.let { fr.dd.jarvis.device.Autorisations.toujours(it, ConfirmationNotification.questionCourante) }
        if (ok && intent.getBooleanExtra(ConfirmationNotification.EXTRA_COMMANDE, false))
            (context.applicationContext as? fr.dd.jarvis.JarvisApp)?.let { fr.dd.jarvis.device.Autorisations.toujoursCommande(it, ConfirmationNotification.questionCourante) }
        if (!ConfirmationNotification.repondre(context, ok)) runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).setAction(ConfirmationNotification.ACTION)
                .putExtra(ConfirmationNotification.EXTRA_OK, ok).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}
