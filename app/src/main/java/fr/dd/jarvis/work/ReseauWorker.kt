package fr.dd.jarvis.work

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Registre
import fr.dd.jarvis.ai.Reseau
import fr.dd.jarvis.ai.Serveur
import java.util.concurrent.TimeUnit

object ReseauScheduler {
    private var dernier = 0L

    fun apply(ctx: Context, store: fr.dd.jarvis.data.Store) {
        val wm = WorkManager.getInstance(ctx)
        if (!store.scanAuto.value) { wm.cancelUniqueWork("reseau_auto"); return }
        wm.enqueueUniquePeriodicWork("reseau_auto", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReseauWorker>(60, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())
    }

    /** À chaque connexion à un Wi-Fi : analyse (au plus une fois toutes les 20 minutes). */
    @Synchronized fun declencher(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - dernier < 20 * 60_000L) return
        dernier = now
        WorkManager.getInstance(ctx).enqueueUniqueWork("reseau_connexion", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReseauWorker>().setInitialDelay(25, TimeUnit.SECONDS).build())
    }
}

/** Analyse automatique du réseau : enregistre tous les appareils et l'IP publique (utiles pour la connexion à distance) ; prévient d'un nouvel appareil. */
class ReseauWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = ctx.applicationContext as JarvisApp
        if (!app.store.scanAuto.value) return Result.success()
        val avant = Registre.nombreAppareils(app)
        var neufs = 0
        val i = Reseau.infos(app)
        if (i != null && i.wifi) {
            runCatching { neufs += Registre.appareils(app, Reseau.scanner(app), "Wi-Fi ${i.ip.substringBeforeLast('.')}.x") }
            runCatching { Reseau.ippPublique().takeIf { it.isNotBlank() }?.let { Registre.ipPublique(app, it, "Wi-Fi ${i.ip.substringBeforeLast('.')}.x") } }
        }
        if (Serveur.sshConfigure(app)) runCatching { neufs += Registre.appareils(app, Serveur.scanReseau(app), "Réseau du serveur") }
        if (neufs > 0 && avant > 0 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || (neufs > 0 && avant > 0 && android.os.Build.VERSION.SDK_INT < 33)) {
            val n = NotificationCompat.Builder(ctx, JarvisApp.CHANNEL_AGENTS).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("📡 $neufs nouvel appareil sur ton réseau").setContentText("Enregistré dans la liste des appareils (demande-moi « liste des appareils »).").setAutoCancel(true).build()
            runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(5001, n) }
        }
        return Result.success()
    }
}
