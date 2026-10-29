package fr.dd.jarvis.work

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.ToolBox
import fr.dd.jarvis.data.Store
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val NAME = "briefing"

    fun schedule(ctx: Context, store: Store) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(store.briefingHour.value, store.briefingMinute.value)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMinutes().coerceAtLeast(1)
        val req = OneTimeWorkRequestBuilder<BriefingWorker>()
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
    }
}

/** Prépare le point du matin (agenda + mails), l'enregistre pour le chat et envoie une notification. */
class BriefingWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = ctx.applicationContext as JarvisApp
        try {
            // Aucune confirmation possible en arrière-plan : le briefing n'utilise que des outils en lecture.
            val text = ToolBox(app) { _, _ -> false }.briefing()
            app.store.lastBriefing.set(text)
            notify(text)
        } catch (e: Exception) {
            notify("Impossible de préparer le point du matin : ${e.message}")
        } finally {
            if (app.store.briefingOn.value) Scheduler.schedule(ctx, app.store)
        }
        return Result.success()
    }

    private fun notify(text: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val plain = text.replace(Regex("[#*`]"), "").replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        val n = NotificationCompat.Builder(ctx, JarvisApp.CHANNEL_BRIEFING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Point du matin")
            .setContentText(plain.lineSequence().firstOrNull { it.isNotBlank() } ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(plain.take(3500)))
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1001, n)
    }
}
