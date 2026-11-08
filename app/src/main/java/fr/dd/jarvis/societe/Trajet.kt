package fr.dd.jarvis.societe

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Meteo
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
        val o = origine(app, depuis) ?: return "Point de départ inconnu : autorise la localisation (Réglages › Téléphone › Contrôle) ou renseigne l'adresse du siège."
        val dest = Geo.geocode(destination) ?: return "Adresse introuvable : $destination"
        val r = Geo.route(o.second, dest) ?: return "Itinéraire indisponible."
        return "# 🧭 Trajet\n\n**${o.first}** → **$destination**\n- 📏 ${nombre(Math.round(r.km * 10) / 10.0)} km\n- ⏱️ ${dur(r.minutes)}${if (r.estime) " (estimation à vol d'oiseau)" else ""}\n" +
            "- 🕗 Départ maintenant → arrivée ${java.time.LocalTime.now().plusMinutes(Math.round(r.minutes)).format(hm)}\n" +
            "\n[🗺️ Ouvrir dans Google Maps](https://www.google.com/maps/dir/?api=1&origin=${enc(o.third)}&destination=${enc(destination)}&travelmode=driving)"
    }

    /**
     * Ordre de visite le plus court : plus proche voisin depuis le départ, puis améliorations « 2-opt » (inversion de
     * segments) tant qu'elles raccourcissent le trajet ; retour au départ compté si [retour].
     */
    internal fun ordre(depart: Geo.Point, points: List<Geo.Point>, retour: Boolean): List<Int> {
        val n = points.size
        if (n <= 1) return (0 until n).toList()
        fun d(a: Geo.Point, b: Geo.Point) = Geo.haversine(a, b)
        val restants = (0 until n).toMutableList()
        val o = mutableListOf<Int>()
        var cur = depart
        while (restants.isNotEmpty()) { val k = restants.minByOrNull { d(cur, points[it]) }!!; o += k; restants.remove(k); cur = points[k] }
        fun cout(t: List<Int>): Double {
            var c = d(depart, points[t[0]])
            for (i in 1 until t.size) c += d(points[t[i - 1]], points[t[i]])
            if (retour) c += d(points[t.last()], depart)
            return c
        }
        var meilleur = o.toList()
        var ameliore = true
        var tours = 0
        while (ameliore && tours++ < 50) {
            ameliore = false
            for (i in 0 until n - 1) for (j in i + 1 until n) {
                val t = meilleur.toMutableList(); t.subList(i, j + 1).reverse()
                if (cout(t) + 1e-9 < cout(meilleur)) { meilleur = t; ameliore = true }
            }
        }
        return meilleur
    }

    private fun heureDe(texte: String): java.time.LocalTime? {
        val m = Regex("(\\d{1,2})\\s*[h:]\\s*(\\d{2})?").find(texte.trim().lowercase()) ?: return null
        return runCatching { java.time.LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].ifBlank { "0" }.toInt()) }.getOrNull()
    }

    /**
     * Tournée optimisée d'une liste de clients (sans horaires imposés) : ordre le plus court, trajets réels, heures
     * d'arrivée (durée sur place [dureeMin]), météo et ensoleillement sur chaque site, lien Google Maps de la tournée.
     */
    suspend fun optimisee(app: JarvisApp, clients: List<String>, date: LocalDate, depuis: String, heure: String, dureeMin: Int, retour: Boolean): String {
        data class Arret(val nom: String, val adresse: String, val point: Geo.Point, val tel: String)
        val arrets = mutableListOf<Arret>(); val introuvables = mutableListOf<String>()
        fun n(s: String) = fr.dd.jarvis.societe.core.normaliser(s)
        for (demande in clients.map { it.trim() }.filter { it.isNotBlank() }.distinct()) {
            val c = app.societe.data.clients.firstOrNull { n(it.nom) == n(demande) }
                ?: app.societe.data.clients.firstOrNull { n(it.nom).contains(n(demande)) || n(demande).contains(n(it.nom)) }
            val site = c?.sites?.firstOrNull { n(demande).contains(n(it.nom)) && it.nom.isNotBlank() } ?: c?.sites?.firstOrNull()
            val adresse = site?.adresse?.ifBlank { null } ?: c?.adresse?.ifBlank { null } ?: if (c == null) demande else null
            val p = if (site?.lat != null && site.lon != null) Geo.Point(site.lat, site.lon) else adresse?.let { delay(1100); Geo.geocode(it) }
            if (p == null || adresse == null) introuvables += demande
            else arrets += Arret(c?.let { it.nom + if (it.sites.size > 1 && site != null) " · ${site.nom}" else "" } ?: demande, adresse, p, c?.tel.orEmpty())
        }
        if (arrets.isEmpty()) return "Aucun client localisable dans « ${clients.joinToString(", ")} » (adresse ou position GPS manquante)."
        val dep = origine(app, depuis) ?: return "Point de départ inconnu : autorise la localisation (Réglages › Téléphone › Contrôle) ou renseigne l'adresse du siège."
        val ordre = ordre(dep.second, arrets.map { it.point }, retour)
        val zone = ZoneId.systemDefault()
        var t = java.time.LocalDateTime.of(date, heureDe(heure) ?: if (date == LocalDate.now()) java.time.LocalTime.now().plusMinutes(5).withSecond(0).withNano(0) else java.time.LocalTime.of(8, 0))
        val jour = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.FRENCH))
        val sb = StringBuilder("# 📅 Tournée optimisée — $jour\n\nDépart : **${dep.first}** à **${t.format(hm)}** · ${arrets.size} client(s) · $dureeMin min sur place\n")
        var cur = dep.second; var curNom = dep.first
        var totKm = 0.0; var totMin = 0.0
        for ((rang, k) in ordre.withIndex()) {
            val a = arrets[k]
            val r = Geo.route(cur, a.point)
            val min = r?.minutes ?: 0.0
            totKm += r?.km ?: 0.0; totMin += min
            t = t.plusMinutes(Math.round(min))
            val arrivee = t
            t = t.plusMinutes(dureeMin.toLong())
            sb.append("\n---\n\n## 🕗 ${arrivee.format(hm)} – ${t.format(hm)} · ${rang + 1}. ${a.nom}\n")
            sb.append("📍 [${a.adresse}](https://www.google.com/maps/search/?api=1&query=${enc(a.adresse)})\n")
            if (a.tel.isNotBlank()) sb.append("📞 [${a.tel}](tel:${a.tel.replace(" ", "")})\n")
            if (r != null) sb.append("🚗 ${nombre(Math.round(r.km * 10) / 10.0)} km · ${dur(r.minutes)}${if (r.estime) " (estimé)" else ""} depuis $curNom\n")
            Meteo.horaire(a.point.lat, a.point.lon, date)?.let { h -> Meteo.ligneHeure(h, arrivee.hour)?.let { sb.append(it).append("\n") } }
            cur = a.point; curNom = a.nom
        }
        if (retour) {
            Geo.route(cur, dep.second)?.let { r -> totKm += r.km; totMin += r.minutes; t = t.plusMinutes(Math.round(r.minutes))
                sb.append("\n---\n\n## 🏁 Retour vers ${dep.first} · arrivée vers ${t.format(hm)}\n🚗 ${nombre(Math.round(r.km * 10) / 10.0)} km · ${dur(r.minutes)}\n") }
        }
        sb.append("\n---\n\n**Total route : ${nombre(Math.round(totKm * 10) / 10.0)} km · ${dur(totMin)}** · fin vers **${t.format(hm)}**\n")
        if (introuvables.isNotEmpty()) sb.append("\n⚠ Non placés (adresse inconnue) : ${introuvables.joinToString(", ")}\n")
        val etapes = ordre.map { arrets[it] }
        val destination = if (retour) dep.third else etapes.last().adresse
        val points = if (retour) etapes else etapes.dropLast(1)
        sb.append("\n[🗺️ Toute la tournée dans Google Maps](https://www.google.com/maps/dir/?api=1&origin=${enc(dep.third)}&destination=${enc(destination)}" +
            (if (points.isNotEmpty()) "&waypoints=" + points.joinToString("%7C") { enc(it.adresse) } else "") + "&travelmode=driving)")
        return sb.toString()
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

        var cur = origine(app, depuis) ?: return "Point de départ inconnu : autorise la localisation (Réglages › Téléphone › Contrôle) ou renseigne l'adresse du siège."
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
