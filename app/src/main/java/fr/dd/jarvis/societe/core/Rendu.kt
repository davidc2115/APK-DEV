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
            "societe_bic" to bic, "societe_mentions" to mentions,
            "societe_rcs" to rcs, "societe_ape" to codeApe, "societe_convention" to conventionCollective,
            "societe_capital" to (if (capitalSocial > 0) euro(capitalSocial) else ""),
            "societe_forme_capital" to formeCapital(d.societe), "societe_identite" to identiteLegale(d)
        )
    }

    /** « SAS au capital de 1 000 € » (ou la forme seule). */
    fun formeCapital(s: Societe): String = listOf(s.forme.trim(), if (s.capitalSocial > 0) "au capital de ${euro(s.capitalSocial)}" else "")
        .filter { it.isNotBlank() }.joinToString(" ")

    /** Mentions légales de la société en une ligne (pied de page de tous ses documents). */
    fun identiteLegale(d: SocieteData): String = with(d.societe) {
        listOf(
            listOf(nom.trim(), formeCapital(this)).filter { it.isNotBlank() }.joinToString(", "),
            adresse.trim(),
            if (siret.isNotBlank()) "SIRET $siret" else "",
            rcs.trim(),
            if (codeApe.isNotBlank()) "APE $codeApe" else "",
            if (tvaNum.isNotBlank()) "TVA $tvaNum" else ""
        ).filter { it.isNotBlank() }.joinToString(" · ").takeIf { nom.isNotBlank() } ?: ""
    }

    fun conditions(d: SocieteData, doc: Doc): String {
        val s = d.societe
        if (doc.type == "devis") return "Devis gratuit, valable ${s.validiteDevisJours} jours à compter de sa date d'émission. " +
            "Règlement à ${s.delaiPaiementJours} jours à réception de la facture."
        val pro = d.client(doc.clientId)?.type == "professionnel"
        val echeance = if (doc.echeance.isNotBlank()) " (au plus tard le ${dateFr(doc.echeance)})" else ""
        return "Paiement à ${s.delaiPaiementJours} jours$echeance. " +
            (if (pro) "En cas de retard de paiement : pénalités au taux de trois fois le taux d'intérêt légal, exigibles dès le lendemain de l'échéance, " +
                "et indemnité forfaitaire pour frais de recouvrement de 40 € (articles L441-10 et D441-5 du Code de commerce)."
            else "En cas de retard de paiement : pénalités au taux d'intérêt légal en vigueur.") +
            " Pas d'escompte pour paiement anticipé."
    }

    /** Bloc « émetteur | client » ligne par ligne (les lignes vides des deux côtés sont omises). */
    private fun entete(d: SocieteData, c: Client?): List<Map<String, String>> {
        val s = d.societe
        val g = listOf("**${s.nom}**", formeCapital(s), s.adresse, if (s.siret.isNotBlank()) "SIRET : ${s.siret}" else "", s.rcs,
            if (s.codeApe.isNotBlank()) "Code APE : ${s.codeApe}" else "", if (s.tvaNum.isNotBlank()) "TVA intracommunautaire : ${s.tvaNum}" else "",
            listOf(if (s.tel.isNotBlank()) "Tél. ${s.tel}" else "", s.email).filter { it.isNotBlank() }.joinToString(" · ")).filter { it.isNotBlank() && it != "****" }
        val dr = if (c == null) emptyList() else listOf("**${c.nom}**", if (c.contact.isNotBlank()) "À l'attention de ${c.contact}" else "", c.adresse,
            if (c.siren.isNotBlank()) "SIREN : ${c.siren}" else "", if (c.tvaNum.isNotBlank()) "TVA intracommunautaire : ${c.tvaNum}" else "",
            listOf(if (c.tel.isNotBlank()) "Tél. ${c.tel}" else "", c.email).filter { it.isNotBlank() }.joinToString(" · ")).filter { it.isNotBlank() }
        val n = maxOf(g.size, dr.size)
        return (0 until n).map { i -> mapOf("gauche" to g.getOrElse(i) { "" }, "droite" to dr.getOrElse(i) { "" }) }
    }

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
            "signature" to (if (doc.type == "devis") "1" else ""),
            "type_label_maj" to (if (doc.type == "devis") "DEVIS" else "FACTURE"),
            "date_prestation" to dateFr(doc.datePrestation.ifBlank { doc.date }),
            "nature" to "Prestation de services (maintenance et entretien d'installations photovoltaïques)",
            "client_siren" to (c?.siren ?: ""), "client_tva" to (c?.tvaNum ?: ""), "client_type" to (c?.type ?: ""),
            "client_tel" to (c?.tel ?: ""), "client_email" to (c?.email ?: ""),
            "facture" to (if (doc.type == "facture") "1" else ""), "devis" to (if (doc.type == "devis") "1" else ""),
            "net_a_payer" to euro(t.ttc),
            "mode_paiement" to doc.modePaiement, "paye_le" to (if (doc.payeLe.isBlank()) "" else dateFr(doc.payeLe))
        )
        val lignes = doc.lignes.map {
            mapOf(
                "designation" to it.designation,
                "qte" to nombre(it.qte), "unite" to it.unite,
                "pu_ht" to euro(it.puHt),
                "remise" to (if (it.remisePct > 0) "${nombre(it.remisePct)} %" else "–"),
                "tva" to (if (franchise) "–" else "${nombre(it.tva)} %"),
                "total_ht" to euro(Pricing.totalLigne(it))
            )
        }
        val tva = if (franchise) emptyList() else t.tvaParTaux.map { (taux, base, m) ->
            mapOf("taux" to nombre(taux), "base" to euro(base), "montant" to euro(m))
        }
        return Ctx(vars, mapOf("lignes" to lignes, "tva_lignes" to tva, "entete" to entete(d, c)))
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
        ) + bulletinPro(d, b)
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
        return Ctx(vars, mapOf("cotisations" to lignes, "rubriques" to rubriques(b), "entete" to entetePaie(d, b)))
    }

    /** Rubriques du bulletin clarifié (arrêté du 25 février 2016), dans l'ordre officiel. */
    private val RUBRIQUES = listOf(
        "SANTÉ", "ACCIDENTS DU TRAVAIL - MALADIES PROFESSIONNELLES", "RETRAITE", "FAMILLE", "ASSURANCE CHÔMAGE",
        "AUTRES CONTRIBUTIONS DUES PAR L'EMPLOYEUR", "CSG / CRDS", "EXONÉRATIONS, ÉCRÊTEMENTS ET ALLÈGEMENTS DE COTISATIONS"
    )

    internal fun rubrique(libelle: String): Int {
        val n = normaliser(libelle)
        return when {
            Regex("reduction|exoneration|allegement|ecretement").containsMatchIn(n) -> 7
            n.startsWith("csg") -> 6
            Regex("accident").containsMatchIn(n) -> 1
            Regex("chomage|\\bags\\b|apec").containsMatchIn(n) -> 4
            Regex("retraite|vieillesse|agirc|\\bceg\\b|\\bcet\\b").containsMatchIn(n) -> 2
            Regex("famil").containsMatchIn(n) -> 3
            Regex("sante|maladie|prevoyance|mutuelle|deces").containsMatchIn(n) -> 0
            else -> 5
        }
    }

    private fun montant(x: Double) = when { x > 0 -> euro(x); x < 0 -> "- " + euro(-x); else -> "" }

    fun rubriques(b: Bulletin): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        b.lignes.filter { it.montantSalarial != 0.0 || it.montantPatronal != 0.0 }.groupBy { rubrique(it.libelle) }.toSortedMap().forEach { (k, ls) ->
            out += mapOf("libelle" to "**${RUBRIQUES[k]}**", "base" to "", "taux_sal" to "", "montant_sal" to "", "taux_pat" to "", "montant_pat" to "")
            ls.forEach {
                out += mapOf(
                    "libelle" to it.libelle, "base" to (if (it.base > 0) euro(it.base) else ""),
                    "taux_sal" to (if (it.tauxSalarial > 0) nombre(it.tauxSalarial) + " %" else ""),
                    "montant_sal" to montant(it.montantSalarial),
                    "taux_pat" to (if (it.tauxPatronal > 0) nombre(it.tauxPatronal) + " %" else ""),
                    "montant_pat" to montant(it.montantPatronal)
                )
            }
        }
        return out
    }

    /** Bloc « employeur | salarié » du bulletin. */
    private fun entetePaie(d: SocieteData, b: Bulletin): List<Map<String, String>> {
        val s = d.societe; val e = b.employe
        val g = listOf("**${s.nom}**", formeCapital(s), s.adresse, if (s.siret.isNotBlank()) "SIRET : ${s.siret}" else "",
            if (s.codeApe.isNotBlank()) "Code APE : ${s.codeApe}" else "", "Cotisations versées à : ${s.urssaf.ifBlank { "URSSAF" }}",
            "Convention collective : ${s.conventionCollective.ifBlank { "aucune" }}").filter { it.isNotBlank() && it != "****" }
        val statut = (if (e.statut == "dirigeant") "Dirigeant assimilé salarié" else "Salarié") + (if (d.paie.cadre) " · cadre" else " · non cadre")
        val dr = listOf("**${e.nom}**", "Emploi : ${e.poste.ifBlank { "—" }}", "Statut : $statut",
            if (e.dateEntree.isNotBlank()) "Entrée le : ${dateFr(e.dateEntree)}" else "",
            "Durée du travail : ${nombre(e.heuresHebdo)} h/semaine (${nombre(b.heuresContrat)} h/mois)",
            "Taux horaire : ${euro(b.tauxHoraire)}").filter { it.isNotBlank() }
        val n = maxOf(g.size, dr.size)
        return (0 until n).map { i -> mapOf("gauche" to g.getOrElse(i) { "" }, "droite" to dr.getOrElse(i) { "" }) }
    }

    /** Variables du bulletin « comme un cabinet comptable » : période, paiement, net imposable / social, cumuls de l'année. */
    private fun bulletinPro(d: SocieteData, b: Bulletin): Map<String, String> {
        val ym = runCatching { java.time.YearMonth.parse(b.mois) }.getOrNull()
        val debut = ym?.atDay(1)?.toString() ?: ""; val fin = ym?.atEndOfMonth()?.toString() ?: ""
        val imposable = b.netImposable.takeIf { it > 0 } ?: b.netAvantImpot
        val annee = b.mois.take(4)
        val precedents = d.bulletins.filter { it.employeId == b.employe.id && it.mois.startsWith(annee) && it.mois < b.mois }
        val h25 = round2(b.tauxHoraire * 1.25); val h50 = round2(b.tauxHoraire * 1.5)
        return mapOf(
            "periode_debut" to dateFr(debut), "periode_fin" to dateFr(fin), "date_paiement" to dateFr(fin), "annee" to annee,
            "net_imposable" to euro(imposable), "net_social" to euro(b.netSocial.takeIf { it > 0 } ?: b.netAvantImpot),
            "taux_sup25" to euro(h25), "montant_sup25" to (if (b.heuresSup25 > 0) euro(round2(h25 * b.heuresSup25)) else ""),
            "taux_sup50" to euro(h50), "montant_sup50" to (if (b.heuresSup50 > 0) euro(round2(h50 * b.heuresSup50)) else ""),
            "allegements" to (if (b.allegements > 0) euro(b.allegements) else ""),
            "cumul_brut" to euro(round2(precedents.sumOf { it.brut } + b.brut)),
            "cumul_imposable" to euro(round2(precedents.sumOf { it.netImposable.takeIf { x -> x > 0 } ?: it.netAPayer } + imposable)),
            "cumul_heures" to nombre(round2(precedents.sumOf { it.heures } + b.heuresEffectuees)),
            "cumul_cout" to euro(round2(precedents.sumOf { it.coutEmployeur } + b.coutEmployeur)),
            "statut_cadre" to (if (d.paie.cadre) "cadre" else "non cadre")
        )
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
