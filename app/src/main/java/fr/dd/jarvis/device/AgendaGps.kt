package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.Geo
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Remplace le lieu (adresse) de rendez-vous du planning par leur point GPS « latitude, longitude » : format reconnu par
 * Waze (synchronisation de l'agenda et recherche) comme par Google Maps / Google Agenda. L'adresse d'origine est
 * conservée dans la description, avec un lien Waze direct.
 */
object AgendaGps {
    private fun n(s: String) = fr.dd.jarvis.societe.core.normaliser(s)
    private val LIGNES_GEREES = Regex("^\\s*(adresse|waze|point gps non trouv)", RegexOption.IGNORE_CASE)

    /** Point GPS au format Waze / Google Maps, tel qu'il est écrit dans le champ Lieu. */
    fun lieu(p: Geo.Point) = Geo.texteGps(p)

    /** Description : anciennes lignes « Adresse / Waze » remplacées par les nouvelles, le reste conservé. */
    fun description(ancienne: String, adresse: String, p: Geo.Point?): String =
        (ancienne.lines().filterNot { LIGNES_GEREES.containsMatchIn(it) } +
            (if (adresse.isNotBlank()) "Adresse : $adresse" else "") +
            (if (p != null) "Waze : ${Geo.lienWaze(p)}" else ""))
            .filter { it.isNotBlank() }.joinToString("\n")

    private fun adresseDescription(desc: String) = desc.lines().firstOrNull { it.trim().startsWith("Adresse :", true) }
        ?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }

    private fun dejaGps(lieu: String): Boolean = Geo.lireCoordonnees(lieu)?.let { lieu.trim() == Geo.texteGps(it) } == true

    /** Adresse à convertir : le champ Lieu s'il n'est pas déjà un point GPS, sinon la ligne « Adresse : » de la description. */
    private fun adresseDe(e: EventInfo): String = e.location.trim().takeIf { it.isNotBlank() && Geo.lireCoordonnees(it) == null }
        ?: adresseDescription(e.description).orEmpty()

    private fun aConvertir(e: EventInfo) = !dejaGps(e.location) && (e.location.isNotBlank() || adresseDescription(e.description) != null)

    /** Position d'une installation client si le rendez-vous la désigne sans ambiguïté (souvent plus précise que l'adresse). */
    private suspend fun positionClient(app: JarvisApp, e: EventInfo, adresse: String): Geo.Localisation? {
        val texte = n(e.title + " " + e.location)
        val client = app.societe.data.clients.filter { n(it.nom).length >= 4 && Regex("\\b" + Regex.escape(n(it.nom)) + "\\b").containsMatchIn(texte) }
            .maxByOrNull { it.nom.length } ?: return null
        val parNom = client.sites.firstOrNull { n(it.nom).length >= 3 && texte.contains(n(it.nom)) }
        val site = parNom ?: client.sites.firstOrNull { it.adresse.isNotBlank() && adresse.isNotBlank() && (n(adresse).contains(n(it.adresse)) || n(it.adresse).contains(n(adresse))) }
            ?: client.sites.singleOrNull()
        val adresseFiche = site?.adresse?.ifBlank { null } ?: client.adresse
        val memeLieu = parNom != null || adresse.isBlank() ||
            (adresseFiche.isNotBlank() && (n(adresse).contains(n(adresseFiche)) || n(adresseFiche).contains(n(adresse))))
        if (!memeLieu) return null
        return runCatching { app.societe.localiser(client, site) }.getOrNull()
    }

    private val heure = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.FRENCH)
    private fun quand(e: EventInfo) = Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()).format(heure)

    /**
     * [eventId] : un rendez-vous précis ; sinon [recherche] (mots du titre, du lieu ou de la description) sur [jours]
     * jours à partir de [debut]. [tous] = tous les rendez-vous trouvés ; [forcer] = accepte un point imprécis (centre de commune).
     */
    suspend fun convertir(
        app: JarvisApp, eventId: Long?, recherche: String, debut: LocalDate?, jours: Int?, tous: Boolean, forcer: Boolean,
        confirmer: suspend (String) -> Boolean
    ): String {
        if (!app.calendar.hasPermission()) return "Permission agenda manquante (Réglages › Téléphone › Autorisations)."
        val depart = debut ?: LocalDate.now()
        val duree = (jours ?: if (debut != null || recherche.isBlank()) 1 else 30).coerceIn(1, 60)
        val mots = n(recherche).split(' ').filter { it.length >= 3 && it !in setOf("rendez", "vous", "rdv", "les", "des", "mon", "chez", "planning", "agenda") }
        val evenements = if (eventId != null) listOfNotNull(app.calendar.event(eventId))
            else app.calendar.events(depart, duree, onlyVisible = true)
                .filter { e -> mots.isEmpty() || n(e.title + " " + e.location + " " + e.description).let { t -> mots.all { t.contains(it) } } }
                .distinctBy { it.id }
        if (evenements.isEmpty()) return if (eventId != null) "Rendez-vous #$eventId introuvable."
            else "Aucun rendez-vous trouvé" + (if (recherche.isNotBlank()) " pour « $recherche »" else "") + " entre le ${depart.format(DateTimeFormatter.ofPattern("d/MM"))} et le ${depart.plusDays(duree - 1L).format(DateTimeFormatter.ofPattern("d/MM"))}."
        val cibles = evenements.filter { aConvertir(it) }
        if (cibles.isEmpty()) return evenements.take(5).joinToString("\n", "Rien à convertir :\n") { e ->
            "• ${quand(e)} — ${e.title} : " + if (dejaGps(e.location)) "lieu déjà au format GPS (${e.location.trim()})" else "aucune adresse dans le lieu ni la description"
        }
        if (eventId == null && !tous && cibles.size > 1) return cibles.take(10).joinToString("\n",
            "Plusieurs rendez-vous ont une adresse. Lequel convertir en point GPS (ou dis « tous ») ?\n") { e -> "• #${e.id} ${quand(e)} — ${e.title} : ${adresseDe(e)}" }
        if (cibles.size > 1 && !confirmer(cibles.joinToString("\n", "Remplacer le lieu de ces ${cibles.size} rendez-vous par leur point GPS (adresse conservée dans la description) ?\n\n") { "• ${quand(it)} — ${it.title}" }))
            return "Annulé : aucun rendez-vous modifié."
        val agendas = app.calendar.calendars().associateBy { it.id }
        val lignes = mutableListOf<String>()
        for ((i, e) in cibles.withIndex()) {
            val titre = "${quand(e)} — ${e.title}"
            if (agendas[e.calId]?.writable == false) { lignes += "⛔ $titre : agenda « ${agendas[e.calId]?.let { app.calendar.displayName(it) }} » en lecture seule."; continue }
            val adresse = adresseDe(e)
            val dejaPoint = Geo.lireCoordonnees(e.location)
            val loc: Geo.Localisation? = if (dejaPoint != null) Geo.Localisation(dejaPoint, Geo.Precision.MANUELLE, "point déjà présent")
            else {
                if (i > 0) delay(1100)   // services de géocodage publics : une requête par seconde
                val client = positionClient(app, e, adresse)
                val geo = if (client != null && client.precision.exacte) null else runCatching { Geo.localiser(adresse) }.getOrNull()
                listOfNotNull(client, geo).maxByOrNull { it.precision.rang }
            }
            if (loc == null) { lignes += "❌ $titre : adresse introuvable (« $adresse »), lieu inchangé."; continue }
            if (loc.precision.rang < Geo.Precision.LIEU_DIT.rang && !forcer) {
                lignes += "⚠️ $titre : seul le ${loc.precision.libelle} a été trouvé pour « $adresse » ; lieu inchangé (dis « même approximatif » pour l'appliquer quand même)."
                continue
            }
            val ok = app.calendar.update(e.id, null, null, null, lieu(loc.point), description(e.description, adresse, loc.point))
            lignes += if (ok) "✅ $titre : lieu → ${lieu(loc.point)} (${loc.precision.libelle}${if (loc.source.isNotBlank()) ", " + loc.source else ""})"
                else "❌ $titre : modification refusée par l'agenda."
        }
        return lignes.joinToString("\n") + "\nLe champ Lieu contient maintenant « latitude, longitude » : Waze et Google Maps y vont directement ; l'adresse d'origine et un lien Waze sont dans la description."
    }
}
