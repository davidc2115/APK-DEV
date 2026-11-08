package fr.dd.jarvis.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Contexte
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Veille toutes les 15 min : alertes de santé avant la panne, arrivée chez un client (mode chantier) — sa fiche,
 * sa dernière intervention et un raccourci vers le compte-rendu —, heure de départ et météo des rendez-vous,
 * routines (lieu, heure), journal de bord du soir et conversation JARVIS sur la montre.
 */
class VeilleWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        fun planifier(ctx: Context) = runCatching {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("veille_jarvis", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<VeilleWorker>(15, TimeUnit.MINUTES).build())
        }

        private const val CANAL = "chantier_jarvis"
    }

    override suspend fun doWork(): Result {
        val app = ctx.applicationContext as JarvisApp
        runCatching { fr.dd.jarvis.device.Sante.verifier(app) }
        runCatching { arrivee(app) }
        runCatching { Depart.verifier(app) }
        runCatching { Depart.meteo(app) }
        runCatching { fr.dd.jarvis.device.Routines.surveillerLieu(app) }
        runCatching { fr.dd.jarvis.device.Routines.horaires(app) }
        runCatching { Journal.siLHeure(app) }
        runCatching { fr.dd.jarvis.service.Poignet.assurer(app) }
        runCatching { fr.dd.jarvis.widget.DragonWidget.majTous(app) }
        return Result.success()
    }

    /** Première détection du mode chantier sur une installation aujourd'hui : notification avec la fiche utile. */
    private fun arrivee(app: JarvisApp) {
        val e = Contexte.actuel(app)
        if (e.mode != Contexte.Mode.CHANTIER || e.clientId == null || e.siteId == null || e.force) return
        val cle = "${e.clientId}|${e.siteId}|${LocalDate.now()}"
        if (app.store.dernierChantier.value == cle) return
        app.store.dernierChantier.set(cle)
        val c = app.societe.data.client(e.clientId) ?: return
        val s = c.sites.firstOrNull { it.id == e.siteId } ?: return
        val derniere = s.interventions.maxByOrNull { it.date }
        val texte = listOfNotNull(
            listOfNotNull(s.kwc.takeIf { it > 0 }?.let { "$it kWc" }, s.onduleur.takeIf { it.isNotBlank() }?.let { "onduleur $it" }).joinToString(" · ").ifBlank { null },
            derniere?.let { "Dernière intervention : ${it.date} (${it.categorie}) — ${it.description.take(90)}" } ?: "Première intervention sur cette installation.",
            s.interventions.mapNotNull { it.prochaineMaintenance.ifBlank { null } }.maxOrNull()?.let { "Prochaine maintenance prévue : $it" },
            c.tel.takeIf { it.isNotBlank() }?.let { "📞 $it" },
        ).joinToString("\n")
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, "Arrivée chez un client", NotificationManager.IMPORTANCE_DEFAULT))
        fun ouvrir(code: Int, terrain: String) = PendingIntent.getActivity(ctx, code, Intent(ctx, fr.dd.jarvis.MainActivity::class.java).putExtra("terrain", terrain)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            NotificationManagerCompat.from(ctx).notify(7710, NotificationCompat.Builder(ctx, CANAL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("👷 Chez ${e.detail}").setContentText(texte.lineSequence().firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(texte)).setContentIntent(ouvrir(7711, ""))
                .addAction(0, "📝 Compte-rendu", ouvrir(7712, "compte_rendu")).addAction(0, "📷 Regarder", ouvrir(7713, "regarder"))
                .setAutoCancel(true).build())
        }
    }
}
