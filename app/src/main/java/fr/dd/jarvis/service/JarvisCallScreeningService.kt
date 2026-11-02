package fr.dd.jarvis.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Telephony

/** Identification des appels entrants à partir des fiches Jarvis, des clients et du coffre Obsidian. */
class JarvisCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        // Répondre immédiatement : Android impose un délai court au service de filtrage.
        runCatching {
            respondToCall(callDetails, CallResponse.Builder()
                .setDisallowCall(false).setRejectCall(false)
                .setSkipCallLog(false).setSkipNotification(false).build())
        }
        if (number.isBlank()) return
        Thread {
            val app = applicationContext as? JarvisApp ?: return@Thread
            val name = runCatching { Telephony.identifierAppel(app, number) }.getOrNull()
            if (!name.isNullOrBlank()) Handler(Looper.getMainLooper()).post { afficherIdentite(number, name) }
        }.start()
    }

    private fun afficherIdentite(number: String, name: String) {
        if (Build.VERSION.SDK_INT >= 23 && android.provider.Settings.canDrawOverlays(this)) {
            if (runCatching { afficherOverlay(name, number) }.isSuccess) return
        }
        afficherNotification(name, number)
    }

    private fun afficherOverlay(name: String, number: String) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "JARVIS · APPEL IDENTIFIÉ\n$name\n$number"
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(42, 28, 42, 28)
            setBackgroundColor(android.graphics.Color.rgb(35, 15, 58))
            elevation = 18f
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 90 }
        wm.addView(view, params)
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { wm.removeView(view) } }, 9000)
    }

    private fun afficherNotification(name: String, number: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = "jarvis_caller_id"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channelId, "Identification des appels", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Nom des appelants trouvés dans les fiches JARVIS et Obsidian"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(fr.dd.jarvis.R.drawable.ic_launcher)
            .setContentTitle("Appel entrant identifié · $name")
            .setContentText(number).setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true).build()
        manager.notify(number.filter(Char::isDigit).takeLast(8).hashCode(), notification)
    }
}
