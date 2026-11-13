package fr.dd.jarvis.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Chaque semaine (dès que le téléphone a Internet) : relève les barèmes officiels de l'URSSAF — plafond de la Sécurité
 * sociale, SMIC, impôt sur les sociétés, prélèvement forfaitaire sur les dividendes, coût de chaque salarié — et les
 * applique au calcul du résultat, du dividende et des fiches de paie. Une valeur qui change est signalée (notification
 * + conversation Jarvis). Les changements de loi tombent surtout au 1er janvier : une semaine de délai au plus.
 */
class BaremesWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        fun planifier(ctx: Context) = runCatching {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("baremes_urssaf", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BaremesWorker>(7, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext as JarvisApp
        val texte = runCatching { app.societe.actualiserBaremes() }.getOrElse { return@withContext Result.retry() }
        if (texte.startsWith("❌")) return@withContext Result.retry()
        if (texte.startsWith("📈")) {
            withContext(Dispatchers.Main) { app.chatAjouter(fr.dd.jarvis.ai.ChatMsg(false, "🔔 **Barèmes officiels mis à jour**\n\n" + texte.substringBefore("\n\n# "))) }
            runCatching { SuiviTaches.termine(app, 7801, "Barèmes officiels mis à jour", texte.substringBefore("\n\n# ").replace("**", "")) }
        }
        Result.success()
    }
}
