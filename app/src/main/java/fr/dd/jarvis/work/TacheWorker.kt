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
        val id = 4400 + (System.currentTimeMillis() % 50).toInt() * 2
        val titre = "⏳ Tâche en cours : " + demande.take(60)
        SuiviTaches.enCours(ctx, id, titre, "Démarrage…")
        var derniere = 0L
        val rep = try {
            // Confirmations « normales » acceptées (la demande vient de l'utilisateur) ; les confirmations forcées (SMS, appels, suppressions) sont refusées.
            Brain(app.llm, ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }
                .reply(listOf(ChatMsg(true, demande))) { etape ->
                    // Progression visible dans la notification (au plus une mise à jour par seconde).
                    val t = System.currentTimeMillis()
                    if (t - derniere > 1000) { derniere = t; SuiviTaches.enCours(ctx, id, titre, etape.take(200)) }
                }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { "❌ ${e.message ?: e.javaClass.simpleName}" }
        finally { enCours = false }
        SuiviTaches.termine(ctx, id, (if (rep.startsWith("❌")) "⚠️ Tâche échouée : " else "✅ Tâche terminée : ") + demande.take(50), rep)
        // Le résultat arrive aussi dans la conversation.
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { app.chatAjouter(ChatMsg(false, "✅ **Tâche terminée** : ${demande.take(80)}\n\n$rep")) } }
        runCatching { app.obsidian.writeNote("JARVIS/Journal/${java.time.LocalDate.now()}.md", "## Tâche arrière-plan\n- Demande : $demande\n- Résultat : ${rep.take(600)}\n", true) }
        return Result.success()
    }
}
