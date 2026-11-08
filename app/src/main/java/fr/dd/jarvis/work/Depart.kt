package fr.dd.jarvis.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Locator
import fr.dd.jarvis.societe.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Alerte de départ : pour le prochain rendez-vous avec une adresse, JARVIS calcule l'heure à laquelle partir depuis
 * ta position (trajet + marge de 10 min) et te prévient ~20 min avant. Trafic en temps réel avec une clé TomTom
 * (gratuite, facultative) ; sinon estimation selon les heures de pointe.
 * Météo des interventions : la veille au soir et le matin, alerte si orage, fortes rafales ou forte pluie sont prévus
 * à l'heure d'un rendez-vous, avec proposition de trouver un autre créneau.
 */
object Depart {
    private const val CANAL = "depart_jarvis"
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    data class Duree(val minutes: Int, val retardTrafic: Int, val tempsReel: Boolean, val km: Double)

    /** Coefficient de trafic estimé (sans service de trafic) : heures de pointe en semaine. */
    internal fun coefficientTrafic(d: LocalDateTime): Double {
        if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) return 1.05
        return when (d.hour * 60 + d.minute) {
            in 435..570 -> 1.35      // 7 h 15 – 9 h 30
            in 990..1140 -> 1.3      // 16 h 30 – 19 h
            in 720..810 -> 1.12      // midi
            else -> 1.05
        }
    }

    /** Heure de départ : début du rendez-vous moins le trajet et une marge. */
    internal fun heureDepart(debut: Long, trajetMin: Int, margeMin: Int = 10): Long = debut - (trajetMin + margeMin) * 60_000L

    private fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)

    private fun f6(v: Double) = String.format(java.util.Locale.US, "%.6f", v)

    /** Durée du trajet : TomTom (trafic réel) si une clé est configurée, sinon OSRM × heures de pointe. */
    suspend fun duree(app: JarvisApp, a: Geo.Point, b: Geo.Point, arrivee: Long): Duree? = withContext(Dispatchers.IO) {
        val cle = app.store.tomtomKey.value.trim()
        if (cle.isNotBlank()) runCatching {
            val url = "https://api.tomtom.com/routing/1/calculateRoute/${f6(a.lat)},${f6(a.lon)}:${f6(b.lat)},${f6(b.lon)}/json?key=$cle&traffic=true&travelMode=car&departAt=now"
            fr.dd.jarvis.ai.Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null else {
                    val s = JSONObject(r.body?.string().orEmpty()).getJSONArray("routes").getJSONObject(0).getJSONObject("summary")
                    Duree(ceil(s.getInt("travelTimeInSeconds") / 60.0).toInt(), (s.optInt("trafficDelayInSeconds") / 60.0).roundToInt(), true, s.optInt("lengthInMeters") / 1000.0)
                }
            }
        }.getOrNull()?.let { return@withContext it }
        val r = Geo.route(a, b) ?: return@withContext null
        val depart = Instant.ofEpochMilli(arrivee - (r.minutes * 60_000).toLong()).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val total = ceil(r.minutes * coefficientTrafic(depart)).toInt()
        Duree(total, total - r.minutes.roundToInt(), false, r.km)
    }

    /** Appelée par la veille (toutes les 15 min) : prévient quand il est bientôt l'heure de partir. */
    suspend fun verifier(app: JarvisApp) {
        if (!app.store.departAlertes.value || !app.calendar.hasPermission()) return
        val maintenant = System.currentTimeMillis()
        val e = runCatching { app.calendar.events(LocalDate.now(), 2) }.getOrDefault(emptyList())
            .filter { !it.allDay && it.location.isNotBlank() && it.begin > maintenant + 5 * 60_000 && it.begin < maintenant + 4 * 3_600_000 }
            .minByOrNull { it.begin } ?: return
        val cle = "${e.id}|${e.begin}"
        if (app.store.departNotifie.value == cle) return
        val hq = app.societe.data.societe
        val ici = runCatching { Locator.point(app) }.getOrNull() ?: if (hq.lat != null && hq.lon != null) Geo.Point(hq.lat, hq.lon) else return
        val dest = runCatching { Geo.geocode(e.location) }.getOrNull() ?: return
        if (Geo.haversine(ici, dest) < 0.3) return
        val d = duree(app, ici, dest, e.begin) ?: return
        val depart = heureDepart(e.begin, d.minutes)
        if (maintenant < depart - 20 * 60_000) return
        app.store.departNotifie.set(cle)
        val retard = ((maintenant - depart) / 60_000).toInt()
        val titre = if (retard > 0) "⏰ Tu devrais déjà être parti pour « ${e.title.take(40)} »" else "🚗 Pars à ${hhmm(depart)} pour « ${e.title.take(40)} »"
        val trafic = when {
            d.tempsReel && d.retardTrafic >= 3 -> " dont ${d.retardTrafic} min de bouchons"
            d.tempsReel -> ", trafic fluide"
            d.retardTrafic >= 3 -> " (heure de pointe : +${d.retardTrafic} min estimées)"
            else -> ""
        }
        val texte = "Rendez-vous à ${hhmm(e.begin)} · ${e.location.take(80)}\nTrajet : ${d.minutes} min (${"%.0f".format(d.km)} km)$trafic, marge de 10 min comprise." +
            (if (retard > 0) "\nTu risques ${retard} min de retard : veux-tu prévenir ?" else "")
        notifier(app, 7730, titre, texte, e.location,
            if (retard > 0) "Envoie un message pour prévenir que j'aurai environ ${retard + 5} minutes de retard au rendez-vous « ${e.title} » de ${hhmm(e.begin)}." else null)
        if (app.enVoiture) runCatching { fr.dd.jarvis.service.CarBridge.post(app, "$titre. ${texte.replace('\n', ' ')}") }
    }

    /** Risque météo pour une intervention (testable) : null si rien à signaler. */
    internal fun risqueMeteo(code: Int, rafalesKmh: Double, pluieMm: Double, probaPluie: Int): String? = when {
        code in 95..99 -> "⛈️ orage" + (if (code >= 96) " avec grêle" else "")
        rafalesKmh >= 60 -> "💨 rafales à ${rafalesKmh.roundToInt()} km/h"
        pluieMm >= 2.0 && probaPluie >= 60 -> "🌧️ forte pluie (${"%.1f".format(pluieMm)} mm/h, $probaPluie %)"
        code in setOf(71, 73, 75, 77, 85, 86) -> "🌨️ neige"
        code in setOf(56, 57, 66, 67) -> "🧊 pluie verglaçante"
        else -> null
    }

    /** Veille au soir (dès 18 h) pour demain, et le matin (dès 6 h) pour aujourd'hui : une alerte au plus par passage. */
    suspend fun meteo(app: JarvisApp) {
        if (!app.store.meteoAlertes.value || !app.calendar.hasPermission()) return
        val h = java.time.LocalTime.now().hour
        val (jour, passage) = when {
            h >= 18 -> LocalDate.now().plusDays(1) to "soir"
            h in 6..11 -> LocalDate.now() to "matin"
            else -> return
        }
        val cle = "$jour|$passage"
        if (app.store.meteoNotifie.value == cle) return
        app.store.meteoNotifie.set(cle)
        val evts = runCatching { app.calendar.events(jour, 1) }.getOrDefault(emptyList())
            .filter { !it.allDay && it.location.isNotBlank() && it.begin > System.currentTimeMillis() }
        val alertes = mutableListOf<Pair<String, String>>()
        for (e in evts.take(8)) withContext(Dispatchers.IO) {
            val p = runCatching { Geo.geocode(e.location) }.getOrNull() ?: return@withContext
            val heure = Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()).hour
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${f6(p.lat)}&longitude=${f6(p.lon)}&timezone=auto&start_date=$jour&end_date=$jour" +
                "&hourly=precipitation,precipitation_probability,wind_gusts_10m,weather_code"
            val hh = runCatching {
                fr.dd.jarvis.ai.Http.client.newCall(Request.Builder().url(url).build()).execute().use { JSONObject(it.body?.string().orEmpty()).getJSONObject("hourly") }
            }.getOrNull() ?: return@withContext
            val i = heure.coerceIn(0, 23)
            risqueMeteo(hh.getJSONArray("weather_code").optInt(i), hh.getJSONArray("wind_gusts_10m").optDouble(i, 0.0),
                hh.getJSONArray("precipitation").optDouble(i, 0.0), hh.getJSONArray("precipitation_probability").optInt(i))
                ?.let { alertes += "${hhmm(e.begin)} ${e.title.take(40)}" to it }
        }
        if (alertes.isEmpty()) return
        val quand = if (passage == "soir") "demain" else "aujourd'hui"
        notifier(app, 7731, "⚠️ Météo défavorable $quand pour ${alertes.size} rendez-vous",
            alertes.joinToString("\n") { "• ${it.first} : ${it.second}" } + "\nVeux-tu décaler ?", null,
            "Propose-moi d'autres créneaux, sans mauvais temps, pour ces rendez-vous de $quand : " + alertes.joinToString(" ; ") { "${it.first} (${it.second})" } + ". Vérifie la météo des jours suivants et mon agenda.")
    }

    private fun notifier(app: JarvisApp, id: Int, titre: String, texte: String, lieu: String?, demande: String?) {
        val ctx: Context = app
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, "Départs et météo des rendez-vous", NotificationManager.IMPORTANCE_HIGH))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val b = NotificationCompat.Builder(ctx, CANAL).setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(titre).setContentText(texte.lineSequence().first()).setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(PendingIntent.getActivity(ctx, id, Intent(ctx, fr.dd.jarvis.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
        if (lieu != null) b.addAction(0, "🧭 Y aller", PendingIntent.getActivity(ctx, id + 100,
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(lieu))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
        if (demande != null) b.addAction(0, if (lieu != null) "📨 Prévenir" else "📅 Autre créneau", PendingIntent.getActivity(ctx, id + 200,
            Intent(ctx, fr.dd.jarvis.MainActivity::class.java).putExtra("demande", demande).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags))
        runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }
}
