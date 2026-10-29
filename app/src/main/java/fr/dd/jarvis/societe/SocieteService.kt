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

        var frais = 0.0
        var km: Double? = null
        if (deplacement != false && Pricing.deplacementConfigure(d.regles)) {
            val r = distance(client, site)
            if (r == null) warn += "Distance introuvable (adresse du siège ou du client manquante/inconnue) : frais de déplacement non ajoutés."
            else {
                km = r.km
                frais = Pricing.deplacement(d.regles, r.km)
                if (r.estime) warn += "Distance estimée à vol d'oiseau (service d'itinéraire indisponible)."
            }
        }
        val (lignes, w) = Pricing.construireLignes(prestations, remise.pct, frais, km, d.regles, d.societe.tvaDefaut, d.societe.franchiseTva)
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

    // ------------------------------------------------------------------ export (Obsidian + PDF)

    fun exporter(doc: Doc): String {
        val md = Rendu.rendreDoc(data, doc)
        val dossier = "Société/" + if (doc.type == "devis") "Devis" else "Factures"
        val client = data.client(doc.clientId)?.nom.orEmpty()
        val base = "${doc.numero} ${client}".trim()
        val loc = obsidian.saveIn(dossier, base, md)
        runCatching { obsidian.saveBytes(dossier, "$base.pdf", PdfExporter.toPdf(md), "application/pdf") }
        return loc
    }

    fun exporterClient(c: Client): Pair<String, String> {
        val md = Rendu.rendreClient(data, c)
        return md to obsidian.saveIn("Société/Clients", c.nom, md)
    }

    fun bulletin(e: Employe, mois: String, primes: Double): Pair<String, String> {
        val b = Paie.bulletin(e, data.paie, mois, primes)
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

    fun partagerPdf(nom: String, markdown: String) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, nom.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".pdf")
        f.writeBytes(PdfExporter.toPdf(markdown))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
