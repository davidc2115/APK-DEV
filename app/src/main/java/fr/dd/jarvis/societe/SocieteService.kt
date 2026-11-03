package fr.dd.jarvis.societe

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import fr.dd.jarvis.device.ObsidianWriter
import fr.dd.jarvis.societe.core.*
import java.io.File
import java.time.LocalDate
import java.util.UUID

class SocieteService(
    private val ctx: Context,
    val repo: SocieteRepo,
    private val obsidian: ObsidianWriter
) {
    val data get() = repo.data

    fun newId() = UUID.randomUUID().toString().take(8)

    // ------------------------------------------------------------------ distance / déplacement

    private fun cle(hq: String, dest: String) = normaliser(hq) + "|" + normaliser(dest)

    /** Distance siège → adresse. Utilise le cache du site si l'adresse du siège et du site n'ont pas changé. */
    suspend fun distance(client: Client, site: Site?): Geo.Route? {
        val hq = data.societe
        val dest = site?.adresse?.ifBlank { null } ?: client.adresse
        if (hq.adresse.isBlank() || dest.isBlank()) return null
        val key = cle(hq.adresse, dest)
        if (site != null && site.distanceKm != null && site.distanceCle == key)
            return Geo.Route(site.distanceKm, site.dureeMin ?: 0.0, false)

        val origin = if (hq.lat != null && hq.lon != null) Geo.Point(hq.lat, hq.lon)
        else Geo.geocode(hq.adresse)?.also { p -> repo.update { it.copy(societe = it.societe.copy(lat = p.lat, lon = p.lon)) } }
            ?: return null
        val to = (if (site?.lat != null && site.lon != null && site.distanceCle.substringAfter('|') == normaliser(dest)) Geo.Point(site.lat, site.lon) else Geo.geocode(dest))
            ?: return null
        val route = Geo.route(origin, to) ?: return null
        if (site != null && !route.estime) {
            repo.update { d ->
                d.copy(clients = d.clients.map { c ->
                    if (c.id != client.id) c else c.copy(sites = c.sites.map { s ->
                        if (s.id == site.id) s.copy(lat = to.lat, lon = to.lon, distanceKm = route.km, dureeMin = route.minutes, distanceCle = key) else s
                    })
                })
            }
        }
        return route
    }

    // ------------------------------------------------------------------ documents

    data class Creation(val doc: Doc, val avertissements: List<String>, val emplacement: String)

    suspend fun creerDocument(
        type: String, client: Client, site: Site?,
        prestations: List<Triple<Tarif, Double, Double>>, libres: List<Ligne>,
        deplacement: Boolean?, remiseManuelle: Double?, notes: String, date: LocalDate = LocalDate.now()
    ): Creation {
        val d = data
        val warn = mutableListOf<String>()
        val remise = remiseManuelle?.let { Remise(it, "remise exceptionnelle") } ?: d.remiseClient(client, date)

        var km: Double? = null
        if (deplacement != false && Pricing.deplacementConfigure(d.regles)) {
            val r = distance(client, site)
            if (r == null) warn += "Distance introuvable (adresse du siège ou du client manquante/inconnue) : frais de déplacement non ajoutés."
            else {
                km = r.km
                if (r.estime) warn += "Distance estimée à vol d'oiseau (service d'itinéraire indisponible)."
            }
        }
        val (lignes, w) = Pricing.construireLignes(prestations, remise.pct, km, d.regles, d.societe.tvaDefaut, d.societe.franchiseTva)
        warn += w
        val toutes = lignes + libres.map {
            it.copy(remisePct = remise.pct, tva = if (d.societe.franchiseTva) 0.0 else it.tva)
        }
        require(toutes.isNotEmpty()) { "Aucune ligne à facturer : " + warn.joinToString(" ") }

        val jours = if (type == "devis") d.societe.validiteDevisJours else d.societe.delaiPaiementJours
        val doc = Doc(
            numero = d.numeroSuivant(type, date.year), type = type, clientId = client.id, siteId = site?.id ?: "",
            date = date.toString(), echeance = date.plusDays(jours.toLong()).toString(), lignes = toutes, notes = notes,
            statut = if (type == "devis") "brouillon" else "a_payer",
            remiseInfo = if (remise.pct > 0) "${nombre(remise.pct)} % — ${remise.detail}" else "", distanceKm = km
        )
        repo.update { it.copy(docs = it.docs + doc) }
        return Creation(doc, warn, exporter(doc))
    }

    fun convertirEnFacture(devis: Doc): Result<Creation> {
        val d = data
        if (devis.type != "devis") return Result.failure(IllegalArgumentException("${devis.numero} n'est pas un devis."))
        if (devis.statut == "facture") return Result.failure(IllegalArgumentException("Le devis ${devis.numero} a déjà été facturé."))
        if (devis.statut == "refuse") return Result.failure(IllegalArgumentException("Le devis ${devis.numero} est refusé."))
        val today = LocalDate.now()
        val f = devis.copy(
            numero = d.numeroSuivant("facture", today.year), type = "facture", date = today.toString(),
            echeance = today.plusDays(d.societe.delaiPaiementJours.toLong()).toString(),
            statut = "a_payer", devisRef = devis.numero, payeLe = "", modePaiement = ""
        )
        repo.update { it.copy(docs = it.docs.map { x -> if (x.numero == devis.numero) x.copy(statut = "facture") else x } + f) }
        return Result.success(Creation(f, emptyList(), exporter(f)))
    }

    fun changerStatut(numero: String, statut: String): Doc? {
        var out: Doc? = null
        repo.update { d -> d.copy(docs = d.docs.map { x -> if (x.numero == numero) x.copy(statut = statut).also { out = it } else x }) }
        out?.let { exporter(it) }
        return out
    }

    /** Facture encaissée : statut « payée » + mise de côté en réserve (catégorie dédiée). */
    fun marquerPayee(numero: String, mode: String, date: LocalDate = LocalDate.now()): Pair<Doc, Provision?>? {
        val f = data.doc(numero)?.takeIf { it.type == "facture" } ?: return null
        val payee = f.copy(statut = "payee", payeLe = date.toString(), modePaiement = mode)
        val prov = if (data.provisions.any { it.factureNumero == f.numero }) null
        else Reserve.provision(payee, data.totaux(payee), data.societe.reservePct, date.toString(), newId())
        repo.update { d ->
            d.copy(docs = d.docs.map { if (it.numero == numero) payee else it }, provisions = d.provisions + listOfNotNull(prov))
        }
        exporter(payee)
        return payee to prov
    }

    /** Une facture n'est jamais supprimée : elle est annulée (et sa mise en réserve retirée). */
    fun annuler(numero: String): Doc? {
        val d0 = data.doc(numero) ?: return null
        val nouveau = if (d0.type == "facture") "annulee" else "refuse"
        var out: Doc? = null
        repo.update { d ->
            d.copy(
                docs = d.docs.map { if (it.numero == numero) it.copy(statut = nouveau).also { x -> out = x } else it },
                provisions = d.provisions.filter { it.factureNumero != numero }
            )
        }
        out?.let { exporter(it) }
        return out
    }

    /**
     * Supprime définitivement un devis ou une facture (et les fichiers associés). Irréversible.
     * Le second élément dit si le fichier Obsidian a réellement disparu (sinon il faut le signaler :
     * avant, l'échec de suppression du fichier était avalé en silence et on répondait « supprimé » à tort).
     */
    fun supprimerDocument(numero: String): Pair<Doc, Boolean>? {
        val doc = data.doc(numero) ?: return null
        val dossier = "Société/" + if (doc.type == "devis") "Devis" else "Factures"
        val client = data.client(doc.clientId)?.nom.orEmpty()
        val base = "${doc.numero} ${client}".trim()
        repo.update { d ->
            d.copy(
                docs = d.docs.filterNot { it.numero == numero },
                provisions = d.provisions.filter { it.factureNumero != numero }
            )
        }
        val fichierSupprime = runCatching { obsidian.supprimer(dossier, base) }.getOrDefault(false)
        return doc to fichierSupprime
    }

    /**
     * Retire du JSON (index local) les devis/factures dont le fichier Obsidian n'existe plus — cas d'une
     * suppression faite directement dans Obsidian (ou sur le téléphone), en dehors de JARVIS : rien ne
     * prévenait alors JARVIS, et la liste Devis/Factures restait périmée (« le coffre sur l'application
     * ne se remet pas à jour »). Le JSON n'est qu'un index pour aller vite ; le coffre reste la vraie
     * source de vérité, donc on l'aligne dessus à chaque appel. Sans effet si rien n'a changé.
     */
    fun synchroniserAvecCoffre() {
        if (data.docs.isEmpty()) return
        val manquants = data.docs.filter { doc ->
            val dossier = "Société/" + if (doc.type == "devis") "Devis" else "Factures"
            val client = data.client(doc.clientId)?.nom.orEmpty()
            val base = "${doc.numero} ${client}".trim()
            obsidian.ficheExistante(dossier, base) == null
        }
        if (manquants.isEmpty()) return
        val numeros = manquants.map { it.numero }.toSet()
        repo.update { d ->
            d.copy(
                docs = d.docs.filterNot { it.numero in numeros },
                provisions = d.provisions.filter { it.factureNumero !in numeros }
            )
        }
    }

    // ------------------------------------------------------------------ export (Obsidian + PDF)

    fun exporter(doc: Doc): String {
        val md = Rendu.rendreDoc(data, doc)
        val dossier = "Société/" + if (doc.type == "devis") "Devis" else "Factures"
        val client = data.client(doc.clientId)?.nom.orEmpty()
        val base = "${doc.numero} ${client}".trim()
        val loc = obsidian.saveIn(dossier, base, md)
        runCatching { obsidian.saveBytes(dossier, "$base.pdf", PdfExporter.toPdf(md, Logo.bitmap(ctx)), "application/pdf") }
        return loc
    }

    /** PDF du document (devis/facture), avec le logo s'il y en a un — utilisé pour l'envoyer en pièce jointe par email. */
    fun pdfBytes(doc: Doc): ByteArray = PdfExporter.toPdf(Rendu.rendreDoc(data, doc), Logo.bitmap(ctx))

    fun exporterClient(c: Client): Pair<String, String> {
        val md = Rendu.rendreClient(data, c)
        return md to obsidian.saveIn("Société/Clients", c.nom, md)
    }

    fun bulletin(e: Employe, mois: String, primes: Double, heuresEffectuees: Double? = null, netVerse: Double? = null): Pair<String, String> {
        val b = Paie.bulletin(e, data.paie, mois, primes, heuresEffectuees, netVerse)
        val md = Rendu.rendreBulletin(data, b)
        repo.update { d ->
            d.copy(bulletins = d.bulletins.filterNot { it.employeId == e.id && it.mois == mois } +
                BulletinRef(newId(), e.id, mois, b.brut, b.netAPayer, b.coutEmployeur))
        }
        val loc = obsidian.saveIn("Société/Paie", "$mois ${e.nom}", md)
        runCatching { obsidian.saveBytes("Société/Paie", "$mois ${e.nom}.pdf", PdfExporter.toPdf(md), "application/pdf") }
        return md to loc
    }

    fun bilan(annee: Int) = Reserve.bilan(annee, data.provisions, data.employes, data.bulletins, data.societe.partDirigeantPct)

    fun bilanTexte(annee: Int): String {
        val b = bilan(annee)
        val s = data.societe
        if (s.reservePct <= 0.0) return "⚠️ Aucun pourcentage de réserve n'est défini (societe_info_set reserve_pct)."
        return buildString {
            append("# 💰 Réserve bénéfices $annee\n\n")
            append("Mise de côté : **${nombre(s.reservePct)} %** du HT de chaque facture encaissée.\n\n")
            append("- Factures encaissées concernées : ${b.nbFactures}\n")
            append("- **Total réservé : ${euro(b.total)}**\n")
            append("- Part dirigeant (${nombre(s.partDirigeantPct)} %) : ${euro(b.dirigeant)}\n")
            if (b.salaries.isNotEmpty()) {
                append("- Part salariés (${nombre(100 - s.partDirigeantPct)} %, au prorata du brut) :\n")
                b.salaries.forEach { (e, m) -> append("  - ${e.nom} : ${euro(m)}\n") }
            } else if (b.nonAffecte > 0) append("- Part non affectée (aucun salarié) : ${euro(b.nonAffecte)}\n")
            append("\n_Provision comptable interne. Un dividende doit être voté en assemblée et dépend du bénéfice distribuable ; une participation salariés suit des règles légales. À valider avec votre expert-comptable._")
        }
    }

    // ------------------------------------------------------------------ partage PDF

    fun partagerPdf(nom: String, markdown: String, avecLogo: Boolean = false) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, nom.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".pdf")
        f.writeBytes(PdfExporter.toPdf(markdown, if (avecLogo) Logo.bitmap(ctx) else null))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------------ factures impayées

    fun impayees(): String {
        val d = data
        val today = LocalDate.now()
        val l = d.docs.filter { it.type == "facture" && it.statut == "a_payer" }.sortedBy { it.echeance }
        if (l.isEmpty()) return ""
        val total = l.sumOf { d.totaux(it).ttc }
        return l.joinToString("\n") { f ->
            val retard = runCatching { LocalDate.parse(f.echeance).isBefore(today) }.getOrDefault(false)
            "- ${f.numero} · ${d.client(f.clientId)?.nom.orEmpty()} · ${euro(d.totaux(f).ttc)} TTC · échéance ${dateFr(f.echeance)}" +
                if (retard) " ⚠️ EN RETARD" else ""
        } + "\n**Total à encaisser : ${euro(total)} TTC**"
    }

    // ------------------------------------------------------------------ export KML (Google Earth / My Maps)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Convertit #RRGGBB en couleur KML aabbggrr. */
    private fun kmlColor(hex: String): String {
        val h = hex.removePrefix("#")
        if (!h.matches(Regex("[0-9A-Fa-f]{6}"))) return "ff20a450"
        return "ff" + h.substring(4, 6) + h.substring(2, 4) + h.substring(0, 2)
    }

    /** Exporte les clients en KML : fiches HTML illustrées, styles par catégorie et données indexées. */
    suspend fun exporterKml(recherche: String = ""): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val manquants = mutableListOf<String>()
        data class KmlPoint(val nom: String, val client: String, val type: String, val adresse: String, val description: String, val point: Geo.Point)
        val pts = mutableListOf<KmlPoint>()
        val filtre = normaliser(recherche.trim())
        for (c in data.clients) {
            val cibles = if (c.sites.isEmpty()) listOf(null) else c.sites
            for (site in cibles) {
                val adr = site?.adresse?.ifBlank { null } ?: c.adresse
                val nomSite = site?.nom?.ifBlank { null } ?: "Adresse client"
                val searchable = normaliser(listOf(c.nom, c.contact, c.type, c.tel, c.adresse, c.email, site?.nom.orEmpty(), adr, site?.onduleur.orEmpty(), site?.notes.orEmpty()).joinToString(" "))
                if (filtre.isNotBlank() && !searchable.contains(filtre)) continue
                val particulier = c.type.contains("part", ignoreCase = true)
                val emoji = if (particulier) "🏠" else "🏢"
                val label = "$emoji ${c.nom} — $nomSite"
                var point = if (site?.lat != null && site.lon != null) Geo.Point(site.lat, site.lon) else null
                if (point == null && adr.isNotBlank()) {
                    point = Geo.geocode(adr)
                    kotlinx.coroutines.delay(1100)
                    if (point != null && site != null) {
                        val geocodePoint = point
                        repo.update { current -> current.copy(clients = current.clients.map { x ->
                            if (x.id != c.id) x else x.copy(sites = x.sites.map { y ->
                                if (y.id == site.id) y.copy(lat = geocodePoint.lat, lon = geocodePoint.lon) else y
                            })
                        }) }
                    }
                }
                if (point == null) { manquants += label; continue }
                val couleur = if (particulier) data.societe.kmlCouleurParticulier else data.societe.kmlCouleurProfessionnel
                val description = buildString {
                    append("<div style='font-family:Arial,sans-serif;color:#202124'>")
                    append("<div style='padding:12px 14px;background:$couleur;color:white;border-radius:8px'>")
                    append("<h2 style='margin:0'>$emoji ${esc(c.nom)}</h2>")
                    append("<div style='font-size:13px;margin-top:4px'>${if (particulier) "Client particulier" else "Client professionnel"}</div></div>")
                    if (c.contact.isNotBlank()) append("<p>👤 <b>Contact :</b> ${esc(c.contact)}</p>")
                    if (c.tel.isNotBlank()) append("<p>📞 <b>Téléphone :</b> ${esc(c.tel)}</p>")
                    if (c.email.isNotBlank()) append("<p>✉️ <b>E-mail :</b> ${esc(c.email)}</p>")
                    if (c.adresse.isNotBlank()) append("<p>📍 <b>Adresse client :</b> ${esc(c.adresse)}</p>")
                    if (c.depuis.isNotBlank()) append("<p>🗓️ <b>Client depuis :</b> ${esc(c.depuis)}</p>")
                    if (c.notes.isNotBlank()) append("<p>📝 <b>Notes client :</b><br>${esc(c.notes).replace("\n", "<br>")}</p>")
                    append("<hr><h3>☀️ Installation : ${esc(nomSite)}</h3>")
                    if (adr.isNotBlank()) append("<p>📍 <b>Adresse :</b> ${esc(adr)}</p>")
                    if (site != null && site.kwc > 0) append("<p>⚡ <b>Puissance :</b> ${nombre(site.kwc)} kWc</p>")
                    if (site != null && site.miseEnService.isNotBlank()) append("<p>📅 <b>Mise en service :</b> ${esc(site.miseEnService)}</p>")
                    if (site != null && site.onduleur.isNotBlank()) append("<p>🔌 <b>Onduleur :</b> ${esc(site.onduleur)}</p>")
                    if (site != null && site.nbPanneaux > 0) append("<p>🔋 <b>Nombre de panneaux :</b> ${site.nbPanneaux}</p>")
                    if (site != null && site.notes.isNotBlank()) append("<p>📝 <b>Notes installation :</b><br>${esc(site.notes).replace("\n", "<br>")}</p>")
                    append("</div>")
                }.replace("]]>", "]]]]><![CDATA[>")
                pts += KmlPoint(label, c.nom, if (particulier) "particulier" else "professionnel", adr, description, point)
            }
        }
        // Google Earth superpose les repères ayant exactement les mêmes coordonnées.
        // On conserve les coordonnées GPS réelles dans les fiches ; seul l'affichage KML est décalé.
        val groupesMemeAdresse = pts.indices.groupBy { i ->
            val p = pts[i].point
            String.format(java.util.Locale.US, "%.6f|%.6f", p.lat, p.lon)
        }
        val pointsAffichage = pts.mapIndexed { index, item ->
            val groupe = groupesMemeAdresse.values.firstOrNull { index in it }.orEmpty()
            if (groupe.size <= 1) item else {
                val rang = groupe.indexOf(index)
                val angle = 2.0 * Math.PI * rang / groupe.size - Math.PI / 2.0
                val rayonMetres = 9.0 + groupe.size.coerceAtMost(8) * 1.2
                val latOffset = rayonMetres * kotlin.math.cos(angle) / 111320.0
                val lonScale = kotlin.math.cos(Math.toRadians(item.point.lat)).coerceAtLeast(0.15)
                val lonOffset = rayonMetres * kotlin.math.sin(angle) / (111320.0 * lonScale)
                item.copy(point = Geo.Point(item.point.lat + latOffset, item.point.lon + lonOffset))
            }
        }
        val hq = data.societe
        val kml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
            append("<name>${esc(hq.nom.ifBlank { "Clients" })} — clients</name>\n")
            append("<Style id=\"particulier\"><IconStyle><color>${kmlColor(hq.kmlCouleurParticulier)}</color><scale>1.15</scale><Icon><href>https://maps.google.com/mapfiles/kml/pushpin/wht-pushpin.png</href></Icon></IconStyle></Style>\n")
            append("<Style id=\"professionnel\"><IconStyle><color>${kmlColor(hq.kmlCouleurProfessionnel)}</color><scale>1.15</scale><Icon><href>https://maps.google.com/mapfiles/kml/pushpin/wht-pushpin.png</href></Icon></IconStyle></Style>\n")
            // Punaise BLANCHE teintée, comme pour les clients : Google Earth MULTIPLIE la couleur par celle de l'icône,
            // une punaise bleue teintée en orange donnait un repère olive foncé au lieu de la couleur choisie.
            append("<Style id=\"siege\"><IconStyle><color>${kmlColor(hq.kmlCouleurSiege)}</color><scale>1.25</scale><Icon><href>https://maps.google.com/mapfiles/kml/pushpin/wht-pushpin.png</href></Icon></IconStyle></Style>\n")
            if (hq.lat != null && hq.lon != null) {
                append("<Folder><name>Siège</name><Placemark><name>🏭 Siège ${esc(hq.nom)}</name><styleUrl>#siege</styleUrl><description>${esc(hq.adresse)}</description><Point><coordinates>${hq.lon},${hq.lat},0</coordinates></Point></Placemark></Folder>\n")
            }
            listOf("professionnel", "particulier").forEach { categorie ->
                val categoriePoints = pointsAffichage.filter { it.type == categorie }
                if (categoriePoints.isNotEmpty()) {
                    val titre = if (categorie == "professionnel") "Clients professionnels" else "Clients particuliers"
                    append("<Folder><name>$titre (${categoriePoints.size})</name>\n")
                    categoriePoints.forEach { item ->
                        append("<Placemark><name>${esc(item.nom)}</name><Snippet maxLines=\"2\">${esc(item.client)} — ${esc(item.adresse)}</Snippet><styleUrl>#${item.type}</styleUrl><description><![CDATA[${item.description}]]></description>")
                        append("<ExtendedData><Data name=\"client\"><value>${esc(item.client)}</value></Data><Data name=\"type_client\"><value>${item.type}</value></Data><Data name=\"adresse\"><value>${esc(item.adresse)}</value></Data></ExtendedData>")
                        append("<Point><coordinates>${item.point.lon},${item.point.lat},0</coordinates></Point></Placemark>\n")
                    }
                    append("</Folder>\n")
                }
            }
            append("</Document></kml>\n")
        }
        val loc = obsidian.saveBytes("Société/Cartes", "Clients.kml", kml.toByteArray(), "application/vnd.google-earth.kml+xml")
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        File(dir, "Clients.kml").writeBytes(kml.toByteArray())
        "# 🗺️ Export KML\n\n${pts.size} point(s) GPS enregistrés : $loc\n" +
            (if (filtre.isNotBlank()) "🔎 Filtre appliqué : **${esc(recherche)}**\n" else "") +
            (if (manquants.isNotEmpty()) "\n⚠️ Coordonnées GPS non trouvées (adresse absente ou non reconnue par les services cartographiques) : ${manquants.joinToString(", ")}\n" else "") +
            "\nLes noms des repères contiennent le client et l'installation. Les données étendues « client », « type_client » et « adresse » accompagnent chaque repère pour faciliter la recherche dans les lieux importés."
    }

    fun partagerKml() {
        val f = File(File(ctx.cacheDir, "share"), "Clients.kml")
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.google-earth.kml+xml"
            putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Ouvrir avec Google Earth…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
