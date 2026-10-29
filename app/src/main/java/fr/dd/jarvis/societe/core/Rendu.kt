package fr.dd.jarvis.societe.core

import java.time.LocalDate

object Rendu {
    const val AVERTISSEMENT_PAIE =
        "Bulletin simplifié à titre d'estimation (taux globaux). À faire valider par votre expert-comptable ou un logiciel de paie avant tout usage officiel."

    fun statutLabel(type: String, s: String) = when (s) {
        "brouillon" -> "Brouillon"; "envoye" -> "Envoyé"; "accepte" -> "Accepté"; "refuse" -> "Refusé"
        "facture" -> "Facturé"; "a_payer" -> "À payer"; "payee" -> "Payée"; "annulee" -> "Annulée"
        else -> s
    }

    fun num(x: Double) = String.format(java.util.Locale.US, "%.2f", x)

    fun societeVars(d: SocieteData): Map<String, String> = with(d.societe) {
        mapOf(
            "societe_nom" to nom, "societe_forme" to forme, "societe_adresse" to adresse, "societe_tel" to tel,
            "societe_email" to email, "societe_siret" to siret, "societe_tva" to tvaNum, "societe_iban" to iban,
            "societe_bic" to bic, "societe_mentions" to mentions
        )
    }

    fun conditions(d: SocieteData, doc: Doc): String =
        if (doc.type == "devis") "Devis valable ${d.societe.validiteDevisJours} jours. Règlement à ${d.societe.delaiPaiementJours} jours à réception de facture."
        else "Paiement à ${d.societe.delaiPaiementJours} jours. En cas de retard : pénalités au taux de trois fois le taux d'intérêt légal et indemnité forfaitaire de recouvrement de 40 €. Pas d'escompte pour paiement anticipé."

    fun docCtx(d: SocieteData, doc: Doc): Ctx {
        val c = d.client(doc.clientId)
        val site = c?.sites?.firstOrNull { it.id == doc.siteId }
        val t = d.totaux(doc)
        val franchise = d.societe.franchiseTva
        val vars = societeVars(d) + mapOf(
            "type_doc" to doc.type,
            "type_label" to (if (doc.type == "devis") "Devis" else "Facture"),
            "numero" to doc.numero,
            "client_nom" to (c?.nom ?: ""), "client_adresse" to (c?.adresse ?: ""),
            "site_nom" to (site?.nom ?: ""), "site_adresse" to (site?.adresse ?: ""),
            "site_kwc" to (site?.let { nombre(it.kwc) } ?: ""),
            "date" to dateFr(doc.date), "echeance" to dateFr(doc.echeance),
            "echeance_label" to (if (doc.type == "devis") "Valable jusqu'au" else "Échéance de paiement"),
            "devis_ref" to doc.devisRef,
            "statut_label" to statutLabel(doc.type, doc.statut),
            "total_ht" to euro(t.ht), "total_ht_num" to num(t.ht),
            "total_ttc" to euro(t.ttc), "total_ttc_num" to num(t.ttc),
            "remise_info" to doc.remiseInfo,
            "tva_mention" to (if (franchise) "TVA non applicable, art. 293 B du CGI." else ""),
            "notes" to doc.notes, "conditions" to conditions(d, doc),
            "signature" to (if (doc.type == "devis") "1" else "")
        )
        val lignes = doc.lignes.map {
            mapOf(
                "designation" to it.designation,
                "qte" to nombre(it.qte), "unite" to it.unite,
                "pu_ht" to euro(it.puHt),
                "remise" to (if (it.remisePct > 0) "${nombre(it.remisePct)} %" else "–"),
                "total_ht" to euro(Pricing.totalLigne(it))
            )
        }
        val tva = if (franchise) emptyList() else t.tvaParTaux.map { (taux, base, m) ->
            mapOf("taux" to nombre(taux), "base" to euro(base), "montant" to euro(m))
        }
        return Ctx(vars, mapOf("lignes" to lignes, "tva_lignes" to tva))
    }

    fun clientCtx(d: SocieteData, c: Client, aujourdhui: LocalDate = LocalDate.now()): Ctx {
        val r = d.remiseClient(c, aujourdhui)
        val vars = societeVars(d) + mapOf(
            "client_nom" to c.nom, "type" to c.type, "contact" to c.contact, "tel" to c.tel, "email" to c.email,
            "adresse" to c.adresse, "depuis" to (if (c.depuis.isBlank()) "—" else dateFr(c.depuis)),
            "anciennete" to Pricing.anciennete(c.depuis, aujourdhui).toString(),
            "nb_sites" to c.sites.size.toString(), "notes" to c.notes,
            "ca_total" to euro(d.caClient(c)),
            "remise_applicable" to (if (r.pct > 0) "${nombre(r.pct)} % (${r.detail})" else "aucune")
        )
        val sites = c.sites.map {
            mapOf(
                "nom" to it.nom.ifBlank { "Installation" }, "adresse" to it.adresse, "kwc" to nombre(it.kwc),
                "mise_en_service" to it.miseEnService.ifBlank { "—" }, "onduleur" to it.onduleur.ifBlank { "—" },
                "nb_panneaux" to (if (it.nbPanneaux > 0) it.nbPanneaux.toString() else "—"),
                "distance_km" to (it.distanceKm?.let { k -> nombre(round2(k)) } ?: "—"), "notes" to it.notes
            )
        }
        val docs = d.docs.filter { it.clientId == c.id }.sortedByDescending { it.date }.map {
            mapOf(
                "numero" to it.numero, "type" to (if (it.type == "devis") "Devis" else "Facture"),
                "date" to dateFr(it.date), "total_ttc" to euro(d.totaux(it).ttc), "statut" to statutLabel(it.type, it.statut)
            )
        }
        return Ctx(vars, mapOf("sites" to sites, "documents" to docs))
    }

    fun moisLabel(mois: String): String {
        val noms = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
        val p = mois.split("-")
        val m = p.getOrNull(1)?.toIntOrNull() ?: return mois
        return "${noms.getOrElse(m - 1) { mois }} ${p[0]}"
    }

    fun bulletinCtx(d: SocieteData, b: Bulletin): Ctx {
        val vars = societeVars(d) + mapOf(
            "employe_nom" to b.employe.nom, "poste" to b.employe.poste.ifBlank { "—" },
            "statut_label" to (if (b.employe.statut == "dirigeant") "dirigeant" else "salarié"),
            "mois" to b.mois, "mois_label" to moisLabel(b.mois),
            "brut_base" to euro(b.brutBase), "primes" to (if (b.primes > 0) euro(b.primes) else ""),
            "brut" to euro(b.brut), "taux_salarial" to nombre(b.tauxSalarial),
            "cotisations_salariales" to euro(b.cotisationsSalariales), "net_avant_impot" to euro(b.netAvantImpot),
            "taux_pas" to nombre(b.tauxPas), "pas" to euro(b.pas), "net_a_payer" to euro(b.netAPayer),
            "net_a_payer_num" to num(b.netAPayer), "taux_patronal" to nombre(b.tauxPatronal),
            "charges_patronales" to euro(b.chargesPatronales), "cout_employeur" to euro(b.coutEmployeur),
            "avertissement" to AVERTISSEMENT_PAIE
        )
        return Ctx(vars)
    }

    fun rendreDoc(d: SocieteData, doc: Doc) = Templates.render(d.modele(doc.type), docCtx(d, doc))
    fun rendreClient(d: SocieteData, c: Client) = Templates.render(d.modele("client"), clientCtx(d, c))
    fun rendreBulletin(d: SocieteData, b: Bulletin) = Templates.render(d.modele("paie"), bulletinCtx(d, b))

    /** Données fictives pour valider un modèle réécrit (balises autorisées + rendu sans erreur). */
    fun exemple(type: String): Ctx {
        val site = Site("s1", "Toiture Sud", "12 rue des Lilas 31000 Toulouse", 9.0, "2021-05-01", "Fronius", 20, "RAS", distanceKm = 25.0)
        val cl = Client("c1", "Client Exemple", adresse = "12 rue des Lilas 31000 Toulouse", sites = listOf(site), depuis = "2015-01-01")
        val d = SocieteData(
            societe = Societe(nom = "Ma Société", adresse = "1 rue du Siège", tel = "0600000000", iban = "FR76", bic = "BIC", mentions = "Mentions"),
            clients = listOf(cl)
        )
        val doc = Doc("DEV-2026-0001", if (type == "facture") "facture" else "devis", "c1", "s1", "2026-01-01", "2026-01-31",
            listOf(Ligne("Maintenance", 1.0, "forfait", 100.0, 5.0, 20.0)), notes = "n", remiseInfo = "fidélité", devisRef = "DEV-1")
        return when (type) {
            "client" -> clientCtx(d.copy(docs = listOf(doc)), cl)
            "paie" -> bulletinCtx(d, Paie.bulletin(Employe("e", "Exemple", "Technicien", brutMensuel = 2000.0, tauxPas = 5.0), ConfigPaie(), "2026-01", 100.0))
            else -> docCtx(d, doc)
        }
    }

    /** Valide un modèle proposé. Retourne null si OK, sinon le motif du rejet. */
    fun valider(type: String, modele: String): String? {
        if (modele.isBlank()) return "modèle vide"
        if (type == "planning") return null
        if (!Templates.equilibre(modele)) return "sections {{#…}}…{{/…}} mal fermées"
        val ctx = exemple(type)
        val inc = Templates.inconnues(modele, ctx)
        if (inc.isNotEmpty()) return "balises inconnues : " + inc.joinToString { "{{$it}}" }
        return null
    }

    fun balisesDisponibles(type: String): String {
        val ctx = exemple(type)
        val listes = ctx.lists.entries.joinToString("\n") { (k, rows) -> "  liste {{#$k}}…{{/$k}} : " + (rows.firstOrNull()?.keys?.joinToString { "{{$it}}" } ?: "") }
        return "variables : " + ctx.vars.keys.joinToString { "{{$it}}" } + (if (listes.isNotBlank()) "\n$listes" else "")
    }
}
