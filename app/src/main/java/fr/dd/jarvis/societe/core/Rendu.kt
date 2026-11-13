package fr.dd.jarvis.societe.core

import fr.dd.jarvis.societe.Geo

import java.time.LocalDate

object Rendu {
    const val AVERTISSEMENT_PAIE = Paie.AVERTISSEMENT

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

    /** Coordonnées GPS d'une installation au format Google Maps (vides si inconnues). */
    private fun gpsVars(s: Site): Map<String, String> {
        if (s.lat == null || s.lon == null) return mapOf("gps" to "", "gps_dms" to "", "precision_gps" to "", "lien_maps" to "")
        val p = Geo.Point(s.lat, s.lon)
        return mapOf(
            "gps" to Geo.texteGps(p), "gps_dms" to Geo.texteDms(p),
            "precision_gps" to (Geo.precisionDe(s.gpsPrecision) ?: Geo.Precision.INCONNUE).libelle,
            "lien_maps" to Geo.lienGoogleMaps(p)
        )
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
            ) + gpsVars(it)
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
        val heuresSup = b.heuresSup25 + b.heuresSup50
        val heuresManquantes = (b.heuresContrat - b.heuresEffectuees).coerceAtLeast(0.0)
        val heuresNormales = minOf(b.heuresEffectuees, b.heuresContrat)
        val vars = societeVars(d) + mapOf(
            "employe_nom" to b.employe.nom, "poste" to b.employe.poste.ifBlank { "—" },
            "statut_label" to (if (b.employe.statut == "dirigeant") "dirigeant" else "salarié"),
            "mois" to b.mois, "mois_label" to moisLabel(b.mois),
            "heures_hebdo" to nombre(b.employe.heuresHebdo), "heures_mensuelles" to nombre(b.heuresContrat),
            "heures_contrat" to nombre(b.heuresContrat), "heures_effectuees" to nombre(b.heuresEffectuees),
            "heures_normales" to nombre(heuresNormales),
            "heures_sup" to (if (heuresSup > 0) nombre(heuresSup) else ""),
            "heures_sup25" to (if (b.heuresSup25 > 0) nombre(b.heuresSup25) else ""),
            "heures_sup50" to (if (b.heuresSup50 > 0) nombre(b.heuresSup50) else ""),
            "heures_manquantes" to (if (heuresManquantes > 0) nombre(heuresManquantes) else ""),
            "taux_horaire" to euro(b.tauxHoraire),
            "brut_heures_normales" to euro(b.brutHeuresNormales), "brut_heures_sup" to (if (b.brutHeuresSup > 0) euro(b.brutHeuresSup) else ""),
            "reduction_heures_sup" to (if (b.reductionHeuresSup > 0) euro(b.reductionHeuresSup) else ""),
            "net_vise" to (if (b.employe.netMensuelVise > 0) euro(b.employe.netMensuelVise) else ""),
            "brut_base" to euro(b.brutBase), "primes" to (if (b.primes > 0) euro(b.primes) else ""),
            "brut" to euro(b.brut),
            "cotisations_salariales" to euro(b.totalCotisationsSalariales), "net_avant_impot" to euro(b.netAvantImpot),
            "taux_pas" to nombre(b.tauxPas), "pas" to euro(b.pas), "net_a_payer" to euro(b.netAPayer),
            "net_a_payer_num" to num(b.netAPayer),
            "charges_patronales" to euro(b.totalChargesPatronales), "cout_employeur" to euro(b.coutEmployeur),
            "avertissement" to b.source.ifBlank { AVERTISSEMENT_PAIE }
        )
        val lignes = b.lignes.map {
            mapOf(
                "libelle" to it.libelle, "base" to euro(it.base),
                "taux_sal" to (if (it.tauxSalarial > 0) nombre(it.tauxSalarial) + " %" else "—"),
                "montant_sal" to when {
                    it.montantSalarial > 0 -> euro(it.montantSalarial)
                    it.montantSalarial < 0 -> "- " + euro(-it.montantSalarial) + " (réduction)"
                    else -> "—"
                },
                "taux_pat" to (if (it.tauxPatronal > 0) nombre(it.tauxPatronal) + " %" else "—"),
                "montant_pat" to (if (it.montantPatronal > 0) euro(it.montantPatronal) else "—")
            )
        }
        return Ctx(vars, mapOf("cotisations" to lignes))
    }

    fun rendreDoc(d: SocieteData, doc: Doc) = Templates.render(d.modele(doc.type), docCtx(d, doc))
    fun rendreClient(d: SocieteData, c: Client) = Templates.render(d.modele("client"), clientCtx(d, c))
    fun rendreBulletin(d: SocieteData, b: Bulletin) = Templates.render(d.modele("paie"), bulletinCtx(d, b))

    fun statutIntervention(s: String) = when (s) {
        "terminee" -> "✅ Terminée"; "a_suivre" -> "🔁 À suivre"; "en_attente" -> "⏳ En attente"; else -> s
    }

    /** Données d'un rapport d'intervention (modèle « rapport »). */
    fun interventionCtx(d: SocieteData, c: Client, s: Site, i: Intervention): Ctx {
        val mes = MesuresSeries.analyser(i.mesures)
        val actions = ListesRapport.elements(i.actions).map { mapOf("action" to it) }
        val pieces = ListesRapport.elements(i.pieces).map { mapOf("piece" to it) }
        val series = mes.series.map { mapOf("serie" to "Série ${it.numero}", "valeur" to it.valeur) }
        val paires = mes.series.chunked(2).map { p ->
            mapOf("serie_a" to "Série ${p[0].numero}", "valeur_a" to p[0].valeur,
                "serie_b" to (p.getOrNull(1)?.let { "Série ${it.numero}" } ?: ""), "valeur_b" to (p.getOrNull(1)?.valeur ?: ""))
        }
        val autres = mes.autres.map { mapOf("mesure" to it) }
        fun image(chemin: String) = "![[${i.id}-${chemin.substringAfterLast('/')}]]"
        val avant = i.photosAvant.map { mapOf("photo" to image(it)) }
        val apres = i.photosApres.map { mapOf("photo" to image(it)) }
        fun drapeau(l: List<*>) = if (l.isEmpty()) "" else "oui"
        val vars = societeVars(d) + mapOf(
            "titre" to "Rapport d'intervention", "reference" to i.id,
            "client" to c.nom, "installation" to s.nom.ifBlank { "Installation" },
            "date" to dateFr(i.date), "categorie" to i.categorie.replaceFirstChar { it.uppercase() },
            "statut" to statutIntervention(i.statut),
            "adresse" to s.adresse, "onduleur" to s.onduleur, "kwc" to (if (s.kwc > 0) nombre(s.kwc) else ""),
            "description" to Fidelite.sansMeta(i.description), "diagnostic" to Fidelite.sansMeta(i.diagnostic),
            "a_actions" to drapeau(actions), "a_pieces" to drapeau(pieces), "a_series" to drapeau(series),
            "a_mesures" to drapeau(autres), "a_photos_avant" to drapeau(avant), "a_photos_apres" to drapeau(apres),
            "nb_series" to (if (series.isEmpty()) "" else series.size.toString()),
            "prochaine_maintenance" to (if (i.prochaineMaintenance.isBlank()) "" else dateFr(i.prochaineMaintenance)),
            "notes" to Fidelite.sansMeta(i.notes),
            "signature" to (if (i.signature.isBlank()) "" else image(i.signature))
        )
        return Ctx(vars, mapOf("actions" to actions, "pieces" to pieces, "series" to series, "series_paires" to paires,
            "mesures" to autres, "photos_avant" to avant, "photos_apres" to apres))
    }

    fun rendreIntervention(d: SocieteData, c: Client, s: Site, i: Intervention): String =
        Templates.render(d.modele("rapport"), interventionCtx(d, c, s, i)).replace(Regex("\n{3,}"), "\n\n").trim() + "\n"

    /** Données fictives pour valider un modèle réécrit (balises autorisées + rendu sans erreur). */
    fun exemple(type: String): Ctx {
        val site = Site("s1", "Toiture Sud", "12 rue des Lilas 31000 Toulouse", 9.0, "2021-05-01", "Fronius", 20, "RAS", lat = 43.604652, lon = 1.444209, distanceKm = 25.0, gpsPrecision = "NUMERO")
        val cl = Client("c1", "Client Exemple", adresse = "12 rue des Lilas 31000 Toulouse", sites = listOf(site), depuis = "2015-01-01")
        val d = SocieteData(
            societe = Societe(nom = "Ma Société", adresse = "1 rue du Siège", tel = "0600000000", iban = "FR76", bic = "BIC", mentions = "Mentions"),
            clients = listOf(cl)
        )
        val doc = Doc("DEV-2026-0001", if (type == "facture") "facture" else "devis", "c1", "s1", "2026-01-01", "2026-01-31",
            listOf(Ligne("Maintenance", 1.0, "forfait", 100.0, 5.0, 20.0)), notes = "n", remiseInfo = "fidélité", devisRef = "DEV-1")
        return when (type) {
            "rapport" -> interventionCtx(d, cl, site, Intervention("i1", "2026-01-01", "maintenance", "Description", "Diagnostic",
                "Action 1\nAction 2", "Fusible 10 A", "3 séries à environ 500 V\nIsolement : 20 MΩ", "terminee", "2027-01-01", "Notes",
                listOf("interventions/i1/avant-01.jpg"), listOf("interventions/i1/apres-01.jpg"), "interventions/i1/signature.png"))
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
