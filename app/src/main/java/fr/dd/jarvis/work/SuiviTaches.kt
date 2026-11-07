package fr.dd.jarvis.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Notifications des travaux en arrière-plan (montages vidéo, tâches d'arrière-plan, automatismes) :
 *  - une notification « en cours » silencieuse et persistante, mise à jour à chaque étape ;
 *  - une notification de résultat à la fin (le détail arrive aussi dans la conversation).
 * Catégories dédiées, distinctes des anciennes « alertes » : elles sont actives par défaut même si une ancienne
 * catégorie de Jarvis a été coupée dans les réglages Android.
 */
object SuiviTaches {
    const val CANAL_EN_COURS = "taches_en_cours"
    const val CANAL_RESULTATS = "taches_resultats"

    fun canaux(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL_EN_COURS, "Tâches et vidéos en cours", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progression des montages vidéo et des tâches que Jarvis exécute en arrière-plan."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_RESULTATS, "Tâches et vidéos terminées", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Résultat d'un montage vidéo, d'une tâche d'arrière-plan ou d'un automatisme."
        })
    }

    /** Null si les notifications peuvent s'afficher ; sinon l'explication (pour le dire dans la conversation). */
    fun bloquees(ctx: Context): String? {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return "⚠️ Jarvis n'a pas l'autorisation d'afficher des notifications : autorise-les (Réglages Android › Applications › JARVIS › Notifications). Le résultat arrivera quand même ici."
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled())
            return "⚠️ Les notifications de Jarvis sont désactivées (Réglages Android › Applications › JARVIS › Notifications) : le suivi n'apparaîtra pas, le résultat arrivera quand même ici."
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val coupees = listOf(CANAL_EN_COURS, CANAL_RESULTATS).mapNotNull { nm.getNotificationChannel(it) }.filter { it.importance == NotificationManager.IMPORTANCE_NONE }
            if (coupees.isNotEmpty()) return "⚠️ La catégorie de notifications « ${coupees.joinToString(" » et « ") { it.name }} » de Jarvis est coupée dans les réglages Android : réactive-la pour suivre la progression."
        }
        return null
    }

    private fun ouvrir(ctx: Context, code: Int) = PendingIntent.getActivity(ctx, code,
        Intent(ctx, fr.dd.jarvis.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun peut(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Progression d'un travail (silencieuse, persistante tant qu'il tourne). */
    fun enCours(ctx: Context, id: Int, titre: String, texte: String, avance: Pair<Int, Int>? = null) {
        if (!peut(ctx)) return
        canaux(ctx)
        runCatching {
            NotificationManagerCompat.from(ctx).notify(id, NotificationCompat.Builder(ctx, CANAL_EN_COURS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(titre).setContentText(texte).setStyle(NotificationCompat.BigTextStyle().bigText(texte))
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS).setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(avance?.second ?: 0, avance?.first ?: 0, avance == null)
                .setContentIntent(ouvrir(ctx, id)).build())
        }
    }

    /** Fin d'un travail : retire la progression et affiche le résultat. */
    fun termine(ctx: Context, id: Int, titre: String, texte: String) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(id) }
        if (!peut(ctx)) return
        canaux(ctx)
        val plain = texte.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "").replace(Regex("[#*_`>|]+"), "").trim()
        runCatching {
            NotificationManagerCompat.from(ctx).notify(id + 1, NotificationCompat.Builder(ctx, CANAL_RESULTATS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(titre).setContentText(plain.lineSequence().firstOrNull().orEmpty().take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(plain.take(3500)))
                .setCategory(NotificationCompat.CATEGORY_STATUS).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true).setContentIntent(ouvrir(ctx, id + 1)).build())
        }
    }
}
