package fr.dd.jarvis.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.MainActivity
import fr.dd.jarvis.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Widget d'écran d'accueil : la tête du dragon, le prochain rendez-vous et un bouton « Parler » qui ouvre JARVIS
 * directement à l'écoute. Mis à jour toutes les 30 min et à chaque ouverture de l'appli.
 */
class DragonWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = maj(context, manager, ids)

    companion object {
        /** Intention qui ouvre JARVIS et lance l'écoute. */
        fun ecouter(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 9100,
            Intent(ctx, MainActivity::class.java).putExtra("listen", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun ouvrir(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 9101, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Met à jour tous les widgets posés (appelé aussi à l'ouverture de l'appli). */
        fun majTous(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = runCatching { m.getAppWidgetIds(ComponentName(ctx, DragonWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) maj(ctx, m, ids)
        }

        private fun maj(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val info = runCatching { prochainRdv(ctx) }.getOrNull() ?: "Touche le dragon pour me parler"
            for (id in ids) {
                val v = RemoteViews(ctx.packageName, R.layout.widget_dragon)
                v.setTextViewText(R.id.widget_info, info)
                v.setOnClickPendingIntent(R.id.widget_dragon, ecouter(ctx))
                v.setOnClickPendingIntent(R.id.widget_parler, ecouter(ctx))
                v.setOnClickPendingIntent(R.id.widget_racine, ouvrir(ctx))
                runCatching { manager.updateAppWidget(id, v) }
            }
        }

        private fun prochainRdv(ctx: Context): String? {
            val app = ctx.applicationContext as? JarvisApp ?: return null
            val now = System.currentTimeMillis()
            val e = app.calendar.events(LocalDate.now(), 2).filter { it.end > now }.minByOrNull { it.begin } ?: return "Aucun rendez-vous aujourd'hui ni demain"
            val d = java.time.Instant.ofEpochMilli(e.begin).atZone(java.time.ZoneId.systemDefault())
            val jour = if (d.toLocalDate() == LocalDate.now()) "" else "Demain "
            return "📅 $jour${if (e.allDay) "" else d.format(DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)) + " · "}${e.title}"
        }
    }
}
