package fr.dd.jarvis.work

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Brain
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.ai.ToolBox

/**
 * Tâche d'arrière-plan : exécute une demande de l'utilisateur (tri de nombreux mails, renommer/modifier des contacts…)
 * avec tous les outils de JARVIS, même application fermée, puis notifie le résultat.
 */
class TacheWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        @Volatile var enCours = false
        fun lancer(ctx: Context, demande: String) {
            WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<TacheWorker>().setInputData(workDataOf("demande" to demande)).build())
        }
    }

    override suspend fun doWork(): Result {
        val app = ctx.applicationContext as JarvisApp
        val demande = inputData.getString("demande").orEmpty()
        if (demande.isBlank()) return Result.success()
        enCours = true
        val rep = try {
            // Confirmations « normales » acceptées (la demande vient de l'utilisateur) ; les confirmations forcées (SMS, appels, suppressions) sont refusées.
            Brain(app.llm, ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }
                .reply(listOf(ChatMsg(true, demande))) { }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { "❌ ${e.message ?: e.javaClass.simpleName}" }
        finally { enCours = false }
        if (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val pi = PendingIntent.getActivity(ctx, 31, Intent(ctx, fr.dd.jarvis.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val plain = rep.replace(Regex("[#*_`>|]+"), "").trim()
            val n = NotificationCompat.Builder(ctx, JarvisApp.CHANNEL_AGENTS).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("✅ Tâche terminée : " + demande.take(50)).setContentText(plain.lineSequence().firstOrNull().orEmpty().take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(plain.take(3500))).setContentIntent(pi).setAutoCancel(true).build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(4400 + (System.currentTimeMillis() % 100).toInt(), n)
        }
        runCatching { app.obsidian.writeNote("JARVIS/Journal/${java.time.LocalDate.now()}.md", "## Tâche arrière-plan\n- Demande : $demande\n- Résultat : ${rep.take(600)}\n", true) }
        return Result.success()
    }
}
