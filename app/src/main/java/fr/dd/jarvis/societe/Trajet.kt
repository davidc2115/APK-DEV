package fr.dd.jarvis.societe

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Locator
import fr.dd.jarvis.societe.core.nombre
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Itinéraire et temps de route enchaînant les rendez-vous de l'agenda. */
object Trajet {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dur(min: Double): String { val m = Math.round(min).toInt(); return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min" }

    private suspend fun origine(app: JarvisApp, depuis: String): Triple<String, Geo.Point, String>? {
        val d = depuis.trim().lowercase()
        if (d.isBlank() || d == "position" || d == "ma position") {
            Locator.point(app)?.let { return Triple("ma position", it, "${it.lat},${it.lon}") }
        }
        val adr = if (d.isBlank() || d == "position" || d == "ma position" || d == "siege" || d == "siège") app.societe.data.societe.adresse else depuis
        if (adr.isBlank()) return null
        val hq = app.societe.data.societe
        val p = if (adr == hq.adresse && hq.lat != null && hq.lon != null) Geo.Point(hq.lat, hq.lon) else Geo.geocode(adr)
        return p?.let { Triple(if (adr == hq.adresse) "le siège" else adr, it, adr) }
    }

    /** Trajet simple origine → destination. */
    suspend fun simple(app: JarvisApp, destination: String, depuis: String): String {
        val o = origine(app, depuis) ?: return "Point de départ inconnu : autorise la localisation (Réglages › Contrôle du téléphone) ou renseigne l'adresse du siège."
        val dest = Geo.geocode(destination) ?: return "Adresse introuvable : $destination"
        val r = Geo.route(o.second, dest) ?: return "Itinéraire indisponible."
        return "# 🧭 Trajet\n\n**${o.first}** → **$destination**\n- 📏 ${nombre(Math.round(r.km * 10) / 10.0)} km\n- ⏱️ ${dur(r.minutes)}${if (r.estime) " (estimation à vol d'oiseau)" else ""}\n" +
            "- 🕗 Départ maintenant → arrivée ${java.time.LocalTime.now().plusMinutes(Math.round(r.minutes)).format(hm)}\n" +
            "\n[🗺️ Ouvrir dans Google Maps](https://www.google.com/maps/dir/?api=1&origin=${enc(o.third)}&destination=${enc(destination)}&travelmode=driving)"
    }

    /** Tournée du jour : rendez-vous avec lieu, restants seulement si [restants], en partant de la position ou du siège. */
    suspend fun tournee(app: JarvisApp, date: LocalDate, restants: Boolean, depuis: String, calendrier: String): String {
        if (!app.calendar.hasPermission()) return "Permission agenda manquante."
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val filtre = calendrier.takeIf { it.isNotBlank() }?.let { app.calendar.findCalendar(it, false)?.id }
        val evs = app.calendar.events(date, 1)
            .filter { filtre == null || it.calId == filtre }
            .filter { !it.allDay && it.location.isNotBlank() }
            .filter { !restants || date != LocalDate.now() || it.end > now }
            .sortedBy { it.begin }
        if (evs.isEmpty()) return "Aucun rendez-vous avec un lieu${if (restants && date == LocalDate.now()) " restant aujourd'hui" else ""} dans l'agenda."

        var cur = origine(app, depuis) ?: return "Point de départ inconnu : autorise la localisation (Réglages › Contrôle du téléphone) ou renseigne l'adresse du siège."
        val depart = cur
        val etapes = mutableListOf<String>()
        val sb = StringBuilder("# 🧭 Tournée du ${date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.FRENCH))}\n\nDépart : **${cur.first}**\n")
        var totKm = 0.0
        var totMin = 0.0
        var dispo = if (date == LocalDate.now()) now else 0L // heure à laquelle on est libre
        var first = true
        for (e in evs) {
            val lieu = e.location.lines().first().trim()
            val titre = e.title.ifBlank { "Rendez-vous" }
            val p = Geo.geocode(lieu)
            if (!first) delay(1100)
            first = false
            val debut = Instant.ofEpochMilli(e.begin).atZone(zone).format(hm)
            val fin = Instant.ofEpochMilli(e.end).atZone(zone).format(hm)
            sb.append("\n---\n\n## 🕗 $debut – $fin · $titre\n📍 [$lieu](https://www.google.com/maps/search/?api=1&query=${enc(lieu)})\n")
            if (p == null) { sb.append("⚠️ Adresse introuvable : trajet non calculé.\n"); continue }
            val r = Geo.route(cur.second, p)
            if (r == null) { sb.append("⚠️ Itinéraire indisponible.\n"); continue }
            totKm += r.km; totMin += r.minutes
            val aller = Math.round(r.minutes)
            sb.append("🚗 ${nombre(Math.round(r.km * 10) / 10.0)} km · ${dur(r.minutes)}${if (r.estime) " (estimé)" else ""} depuis ${cur.first}\n")
            val partirAvant = e.begin - (aller + 5) * 60_000L
            sb.append("🕗 Partir à **${Instant.ofEpochMilli(maxOf(partirAvant, dispo)).atZone(zone).format(hm)}** (marge 5 min) pour arriver à $debut\n")
            if (dispo > 0 && dispo + aller * 60_000L > e.begin)
                sb.append("⚠️ Retard probable : arrivée vers ${Instant.ofEpochMilli(dispo + aller * 60_000L).atZone(zone).format(hm)} pour un rendez-vous à $debut.\n")
            etapes += lieu
            cur = Triple(lieu, p, lieu)
            dispo = e.end
        }
        sb.append("\n---\n\n**Total route : ${nombre(Math.round(totKm * 10) / 10.0)} km · ${dur(totMin)}** pour ${evs.size} rendez-vous.\n")
        if (etapes.isNotEmpty()) {
            sb.append("\n[🗺️ Tournée complète dans Google Maps](https://www.google.com/maps/dir/?api=1&origin=${enc(depart.third)}&destination=${enc(etapes.last())}" +
                (if (etapes.size > 1) "&waypoints=" + etapes.dropLast(1).joinToString("%7C") { enc(it) } else "") + "&travelmode=driving)")
        }
        return sb.toString()
    }
}
