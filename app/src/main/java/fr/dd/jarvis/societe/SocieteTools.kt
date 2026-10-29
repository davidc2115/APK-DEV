package fr.dd.jarvis.societe

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Prompts
import fr.dd.jarvis.ai.ToolResult
import fr.dd.jarvis.societe.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Outils « Société » du cerveau : tarifs, règles, clients, devis/factures, paie, réserve, présentations. */
class SocieteTools(
    private val app: JarvisApp,
    private val confirm: suspend (String, Boolean) -> Boolean
) {
    private val svc get() = app.societe
    private val d get() = svc.data

    companion object {
        val NAMES = setOf(
            "societe_info_set", "societe_info_get", "tarif_set", "tarif_delete", "regles_set", "client_ecrire", "client_fiche",
            "document_creer", "document_action", "societe_liste", "employe_ecrire", "paie", "reserve_bilan",
            "presentation_modifier", "distance_deplacement"
        )
        private val KEYWORDS = Regex(
            "devis|factur|client|soci[ée]t[ée]|tarif|remise|d[ée]placement|paie|salaire|bulletin|r[ée]serve|dividende|b[ée]n[ée]fice|" +
                "installation|photovolta|kwc|fiche|pr[ée]sentation|mise en forme|mise en page|si[èe]ge|employ[ée]|prix|onduleur|maintenance|" +
                "entretien|planning|agenda|pdf|obsidian|distance|itin[ée]raire|km|fid[ée]lit[ée]",
            RegexOption.IGNORE_CASE
        )
        fun concerned(text: String) = KEYWORDS.containsMatchIn(text)

        val PROMPT = """
Module SOCIÉTÉ (maintenance et entretien d'installations photovoltaïques). Tu disposes d'outils pour : enregistrer les informations de l'entreprise (siège, SIRET, IBAN, TVA…), les tarifs par puissance (kWc), les règles de remise (fidélité, nombre d'installations) et de déplacement, les clients et leurs installations, créer des devis et factures, convertir un devis en facture, marquer une facture payée (mise en réserve), produire des bulletins de paie, le bilan de la réserve, et modifier la présentation des documents.
- Quand l'utilisateur donne un tarif, une règle ou une information sur la société, enregistre-la immédiatement avec l'outil adapté : c'est ainsi que tu la gardes en mémoire.
- Ne calcule jamais un montant toi-même : les outils font tous les calculs (tarif par puissance, remises, déplacement, TVA).
- Pour un devis ou une facture, identifie le client et l'installation (client_ecrire si le client n'existe pas encore). Si une information indispensable manque (client, prestation), demande-la en une phrase.
- Le texte renvoyé par les outils société est déjà mis en forme et affiché tel quel : ne le répète pas.
- Les montants sont toujours en euros, la TVA est gérée par l'application.
""".trimIndent()
    }

    // ------------------------------------------------------------------ schéma

    private fun prop(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun tool(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        val params = JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
        return JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", desc).put("parameters", params))
    }

    val schema: List<JSONObject> = listOf(
        tool("societe_info_set", "Enregistre les informations de la société (seuls les champs fournis sont modifiés).", mapOf(
            "nom" to prop("Nom de la société."), "forme" to prop("Forme juridique (EURL, SASU, micro-entreprise…)."),
            "siret" to prop("SIRET."), "tva_num" to prop("N° de TVA intracommunautaire."),
            "adresse" to prop("Adresse du siège (point de départ des déplacements)."),
            "tel" to prop("Téléphone."), "email" to prop("Email."), "iban" to prop("IBAN."), "bic" to prop("BIC."),
            "tva_defaut" to prop("Taux de TVA par défaut en % (ex. 20, 10, 5.5)."),
            "franchise_tva" to prop("« true » si TVA non applicable (micro-entreprise, art. 293 B)."),
            "delai_paiement_jours" to prop("Délai de paiement des factures en jours."),
            "validite_devis_jours" to prop("Durée de validité des devis en jours."),
            "mentions" to prop("Mentions légales complémentaires (assurance décennale, RCS…)."),
            "reserve_pct" to prop("Pourcentage du HT de chaque facture encaissée mis de côté en réserve bénéfices."),
            "part_dirigeant_pct" to prop("Part de la réserve attribuée au dirigeant en fin d'année en % (le reste aux salariés).")
        )),
        tool("societe_info_get", "Affiche les informations société, les tarifs, les règles de remise/déplacement et la réserve enregistrés."),
        tool("tarif_set", "Crée ou modifie un tarif. Mode : forfait (prix fixe selon la puissance de l'installation), par_kwc (prix par kWc), heure, unite.", mapOf(
            "designation" to prop("Nom de la prestation, ex. « Maintenance annuelle »."),
            "mode" to prop("forfait | par_kwc | heure | unite"),
            "tranches" to prop("Prix par tranche de puissance : « 0-3:150; 3-9:220; 9+:350 » (kWc:prix HT). Pour heure/unite : un seul prix « 65 »."),
            "tva" to prop("Taux de TVA propre à cette prestation en % (optionnel).")
        ), listOf("designation", "tranches")),
        tool("tarif_delete", "Supprime un tarif.", mapOf("designation" to prop("Nom du tarif.")), listOf("designation")),
        tool("regles_set", "Enregistre les règles de remise commerciale et de frais de déplacement (seuls les champs fournis sont modifiés).", mapOf(
            "remise_fidelite" to prop("Remise selon l'ancienneté du client : « 10:5; 5:3 » (années:%). Vide pour effacer."),
            "remise_volume" to prop("Remise selon le nombre d'installations : « 3:2; 5:4 » (nombre:%). Vide pour effacer."),
            "remise_cumul" to prop("« true » : fidélité et volume s'additionnent ; « false » : seule la plus forte s'applique."),
            "remise_max" to prop("Plafond de remise totale en % (0 = aucun)."),
            "remise_sur_deplacement" to prop("« true » pour appliquer la remise aussi aux frais de déplacement."),
            "km_prix" to prop("Prix du kilomètre en €."), "km_gratuits" to prop("Kilomètres offerts (sur le trajet aller)."),
            "deplacement_min" to prop("Forfait minimum de déplacement en €."),
            "aller_retour" to prop("« true » si le trajet aller-retour est facturé.")
        )),
        tool("client_ecrire", "Crée ou met à jour un client, et éventuellement une de ses installations.", mapOf(
            "nom" to prop("Nom du client."), "type" to prop("particulier | professionnel"), "contact" to prop("Personne à contacter."),
            "tel" to prop("Téléphone."), "email" to prop("Email."), "adresse" to prop("Adresse postale du client."),
            "depuis" to prop("Date de début de la relation yyyy-MM-dd (ou année) pour l'ancienneté/fidélité."),
            "notes" to prop("Notes."),
            "site_nom" to prop("Nom de l'installation (ex. « Toiture garage »)."), "site_adresse" to prop("Adresse de l'installation."),
            "kwc" to prop("Puissance de l'installation en kWc."), "mise_en_service" to prop("Date de mise en service yyyy-MM-dd."),
            "onduleur" to prop("Modèle d'onduleur."), "nb_panneaux" to prop("Nombre de panneaux."), "site_notes" to prop("Notes sur l'installation.")
        ), listOf("nom")),
        tool("client_fiche", "Affiche la fiche client complète et l'enregistre dans le coffre Obsidian.", mapOf("nom" to prop("Nom du client.")), listOf("nom")),
        tool("document_creer", "Crée un devis ou une facture (tarifs, remises et déplacement calculés automatiquement), l'enregistre en Markdown et PDF dans Obsidian.", mapOf(
            "type" to prop("devis | facture"), "client" to prop("Nom du client."), "installation" to prop("Nom ou adresse de l'installation (optionnel si une seule)."),
            "prestations" to prop("Prestations du catalogue de tarifs : « Maintenance annuelle; Contrôle onduleur:2 » (nom[:quantité], séparées par « ; »)."),
            "lignes_libres" to prop("Lignes hors catalogue : « Fusible 32A:2:35.50; Câble:1:120 » (désignation:quantité:prix HT unitaire)."),
            "kwc" to prop("Puissance en kWc si l'installation n'est pas enregistrée."),
            "deplacement" to prop("« false » pour ne pas facturer de déplacement (par défaut : calculé depuis le siège)."),
            "remise_pct" to prop("Remise exceptionnelle en % qui remplace la remise automatique (optionnel)."),
            "notes" to prop("Notes à afficher sur le document.")
        ), listOf("type", "client", "prestations")),
        tool("document_action", "Agit sur un devis ou une facture existant.", mapOf(
            "numero" to prop("Numéro, ex. DEV-2026-0003 ou FAC-2026-0001."),
            "action" to prop("afficher | convertir (devis → facture) | envoye | accepte | refuse | payee | annuler | exporter"),
            "mode_paiement" to prop("Pour « payee » : virement, chèque, CB, espèces…")
        ), listOf("numero", "action")),
        tool("societe_liste", "Liste les clients, devis, factures, employés ou tarifs.", mapOf(
            "quoi" to prop("clients | devis | factures | employes | tarifs"),
            "filtre" to prop("Nom de client, statut ou année (optionnel).")
        ), listOf("quoi")),
        tool("employe_ecrire", "Crée ou modifie le dirigeant ou un salarié (pour les bulletins de paie).", mapOf(
            "nom" to prop("Nom."), "poste" to prop("Poste."), "statut" to prop("dirigeant | salarie"),
            "brut_mensuel" to prop("Salaire brut mensuel en €."), "taux_pas" to prop("Taux de prélèvement à la source en %."),
            "date_entree" to prop("Date d'entrée yyyy-MM-dd."),
            "taux_salarial" to prop("Modifie le taux global de cotisations salariales en %."), "taux_patronal" to prop("Modifie le taux global de charges patronales en %.")
        ), listOf("nom")),
        tool("paie", "Produit le bulletin de paie (estimation simplifiée) d'un employé ou du dirigeant pour un mois.", mapOf(
            "employe" to prop("Nom de l'employé."), "mois" to prop("yyyy-MM (par défaut le mois en cours)."), "primes" to prop("Primes du mois en € (optionnel).")
        ), listOf("employe")),
        tool("reserve_bilan", "Bilan de la réserve bénéfices d'une année et répartition dirigeant / salariés.", mapOf("annee" to prop("Année (par défaut l'année en cours)."))),
        tool("presentation_modifier", "Modifie la présentation d'un modèle : fiche client, devis, facture, bulletin de paie ou planning. Peut aussi annuler la dernière modification ou revenir au modèle d'origine.", mapOf(
            "cible" to prop("client | devis | facture | paie | planning"),
            "action" to prop("modifier | annuler | reinitialiser | voir"),
            "instruction" to prop("Pour « modifier » : ce que l'utilisateur veut changer, avec ses propres mots.")
        ), listOf("cible", "action")),
        tool("distance_deplacement", "Calcule la distance et les frais de déplacement entre le siège et un client/une installation ou une adresse.", mapOf(
            "client" to prop("Nom du client (optionnel)."), "installation" to prop("Nom de l'installation (optionnel)."), "adresse" to prop("Adresse de destination (si pas de client).")
        ))
    )

    // ------------------------------------------------------------------ outils

    private fun JSONObject.s(k: String) = optString(k, "").trim()
    private fun JSONObject.hv(k: String) = s(k).isNotEmpty()
    private fun JSONObject.dbl(k: String): Double? = s(k).replace(',', '.').replace("%", "").replace("€", "").replace("kWc", "", true).trim().toDoubleOrNull()
    private fun JSONObject.bool(k: String): Boolean? = when (s(k).lowercase()) { "true", "oui", "vrai", "1" -> true; "false", "non", "faux", "0" -> false; else -> null }

    private fun ok(text: String) = ToolResult(text, direct = true)
    private fun err(text: String) = ToolResult(text)

    suspend fun execute(name: String, a: JSONObject): ToolResult = when (name) {
        "societe_info_set" -> infoSet(a)
        "societe_info_get" -> ok(resume())
        "tarif_set" -> tarifSet(a)
        "tarif_delete" -> tarifDelete(a)
        "regles_set" -> reglesSet(a)
        "client_ecrire" -> clientEcrire(a)
        "client_fiche" -> clientFiche(a)
        "document_creer" -> documentCreer(a)
        "document_action" -> documentAction(a)
        "societe_liste" -> liste(a)
        "employe_ecrire" -> employeEcrire(a)
        "paie" -> paie(a)
        "reserve_bilan" -> ok(svc.bilanTexte(a.s("annee").toIntOrNull() ?: LocalDate.now().year))
        "presentation_modifier" -> presentation(a)
        "distance_deplacement" -> distanceTool(a)
        else -> err("Outil inconnu : $name")
    }

    private fun infoSet(a: JSONObject): ToolResult {
        val before = d.societe
        var s = before
        if (a.hv("nom")) s = s.copy(nom = a.s("nom"))
        if (a.hv("forme")) s = s.copy(forme = a.s("forme"))
        if (a.hv("siret")) s = s.copy(siret = a.s("siret"))
        if (a.hv("tva_num")) s = s.copy(tvaNum = a.s("tva_num"))
        if (a.hv("adresse")) s = s.copy(adresse = a.s("adresse"), lat = null, lon = null)
        if (a.hv("tel")) s = s.copy(tel = a.s("tel"))
        if (a.hv("email")) s = s.copy(email = a.s("email"))
        if (a.hv("iban")) s = s.copy(iban = a.s("iban"))
        if (a.hv("bic")) s = s.copy(bic = a.s("bic"))
        a.dbl("tva_defaut")?.let { s = s.copy(tvaDefaut = it) }
        a.bool("franchise_tva")?.let { s = s.copy(franchiseTva = it) }
        a.s("delai_paiement_jours").toIntOrNull()?.let { s = s.copy(delaiPaiementJours = it) }
        a.s("validite_devis_jours").toIntOrNull()?.let { s = s.copy(validiteDevisJours = it) }
        if (a.hv("mentions")) s = s.copy(mentions = a.s("mentions"))
        a.dbl("reserve_pct")?.let { s = s.copy(reservePct = it.coerceIn(0.0, 100.0)) }
        a.dbl("part_dirigeant_pct")?.let { s = s.copy(partDirigeantPct = it.coerceIn(0.0, 100.0)) }
        if (s == before) return err("Aucune information reconnue à enregistrer.")
        svc.repo.update { it.copy(societe = s) }
        return ok("✅ Informations société enregistrées.\n\n" + resume())
    }

    private fun resume(): String {
        val s = d.societe
        val r = d.regles
        return buildString {
            append("# 🏢 ${s.nom.ifBlank { "Société (nom à renseigner)" }}\n\n")
            append("- Forme : ${s.forme.ifBlank { "—" }} · SIRET : ${s.siret.ifBlank { "—" }} · TVA n° : ${s.tvaNum.ifBlank { "—" }}\n")
            append("- 📍 Siège : ${s.adresse.ifBlank { "non renseigné" }}\n")
            append("- 📞 ${s.tel.ifBlank { "—" }} · ✉️ ${s.email.ifBlank { "—" }}\n")
            append("- IBAN : ${s.iban.ifBlank { "—" }}\n")
            append("- TVA : " + (if (s.franchiseTva) "non applicable (art. 293 B)" else "${nombre(s.tvaDefaut)} %") + " · paiement ${s.delaiPaiementJours} j · devis valable ${s.validiteDevisJours} j\n\n")
            append("## 💶 Tarifs\n")
            if (d.tarifs.isEmpty()) append("Aucun tarif enregistré.\n")
            d.tarifs.forEach { t -> append("- **${t.designation}** (${t.mode.name.lowercase()}) : ${Pricing.libelleTranches(t)}\n") }
            append("\n## 🎁 Remises\n")
            append("- Fidélité : " + (if (r.remiseFidelite.isEmpty()) "aucune" else r.remiseFidelite.joinToString(" ; ") { "≥ ${it.seuil} ans : ${nombre(it.pct)} %" }) + "\n")
            append("- Volume : " + (if (r.remiseVolume.isEmpty()) "aucune" else r.remiseVolume.joinToString(" ; ") { "≥ ${it.seuil} installations : ${nombre(it.pct)} %" }) + "\n")
            append("- " + (if (r.remiseCumul) "Cumulables" else "Non cumulables (la plus forte)") + (if (r.remiseMax > 0) " · plafond ${nombre(r.remiseMax)} %" else "") + "\n")
            append("\n## 🚗 Déplacement\n")
            append(if (Pricing.deplacementConfigure(r)) "- ${euro(r.kmPrix)}/km · ${nombre(r.kmGratuits)} km offerts · minimum ${euro(r.deplacementMin)} · " + (if (r.allerRetour) "aller-retour" else "aller simple") + "\n" else "- Non configuré.\n")
            append("\n## 💰 Réserve bénéfices\n")
            append(if (s.reservePct > 0) "- ${nombre(s.reservePct)} % du HT des factures encaissées · dirigeant ${nombre(s.partDirigeantPct)} % / salariés ${nombre(100 - s.partDirigeantPct)} %\n" else "- Non configurée.\n")
        }
    }

    private fun tarifSet(a: JSONObject): ToolResult {
        val nom = a.s("designation")
        val mode = when (normaliser(a.s("mode"))) {
            "par kwc", "parkwc", "kwc", "par_kwc" -> Mode.PAR_KWC
            "heure", "horaire" -> Mode.HEURE
            "unite", "unitaire" -> Mode.UNITE
            else -> Mode.FORFAIT
        }
        var tr = Pricing.parserTranches(a.s("tranches"))
        if (tr.isEmpty()) a.dbl("tranches")?.let { tr = listOf(Tranche(KWC_MAX, it)) }
        if (tr.isEmpty()) return err("Tranches illisibles. Format attendu : « 0-3:150; 3-9:220; 9+:350 ».")
        val existing = Pricing.trouverTarif(d.tarifs, nom)?.takeIf { normaliser(it.designation) == normaliser(nom) }
        val t = Tarif(existing?.designation ?: nom, mode, tr, a.dbl("tva"))
        svc.repo.update { it.copy(tarifs = it.tarifs.filterNot { x -> x.designation == t.designation } + t) }
        return ok("✅ Tarif ${if (existing != null) "modifié" else "enregistré"} : **${t.designation}** (${mode.name.lowercase()}) — ${Pricing.libelleTranches(t)}")
    }

    private fun tarifDelete(a: JSONObject): ToolResult {
        val t = Pricing.trouverTarif(d.tarifs, a.s("designation")) ?: return err("Tarif introuvable. Disponibles : ${d.tarifs.joinToString { it.designation }}")
        svc.repo.update { it.copy(tarifs = it.tarifs.filterNot { x -> x.designation == t.designation }) }
        return ok("🗑️ Tarif « ${t.designation} » supprimé.")
    }

    private fun reglesSet(a: JSONObject): ToolResult {
        var r = d.regles
        val before = r
        if (a.hv("remise_fidelite")) r = r.copy(remiseFidelite = Pricing.parserSeuils(a.s("remise_fidelite")))
        if (a.hv("remise_volume")) r = r.copy(remiseVolume = Pricing.parserSeuils(a.s("remise_volume")))
        a.bool("remise_cumul")?.let { r = r.copy(remiseCumul = it) }
        a.dbl("remise_max")?.let { r = r.copy(remiseMax = it) }
        a.bool("remise_sur_deplacement")?.let { r = r.copy(remiseSurDeplacement = it) }
        a.dbl("km_prix")?.let { r = r.copy(kmPrix = it) }
        a.dbl("km_gratuits")?.let { r = r.copy(kmGratuits = it) }
        a.dbl("deplacement_min")?.let { r = r.copy(deplacementMin = it) }
        a.bool("aller_retour")?.let { r = r.copy(allerRetour = it) }
        if (r == before) return err("Aucune règle reconnue à enregistrer.")
        svc.repo.update { it.copy(regles = r) }
        return ok("✅ Règles enregistrées.\n\n" + resume())
    }

    private fun parseDepuis(s: String): String = when {
        s.matches(Regex("\\d{4}")) -> "$s-01-01"
        s.isBlank() -> ""
        else -> try { LocalDate.parse(s.take(10)).toString() } catch (e: Exception) { "" }
    }

    private fun clientEcrire(a: JSONObject): ToolResult {
        val nom = a.s("nom")
        val trouves = d.chercherClients(nom)
        if (trouves.size > 1 && trouves.none { normaliser(it.nom) == normaliser(nom) })
            return err("Plusieurs clients correspondent : ${trouves.joinToString { it.nom }}. Préciser le nom.")
        val existant = trouves.firstOrNull { normaliser(it.nom) == normaliser(nom) } ?: trouves.firstOrNull()
        val nouveau = existant == null
        var c: Client = existant ?: Client(svc.newId(), nom, depuis = LocalDate.now().toString())
        if (a.hv("type")) c = c.copy(type = if (normaliser(a.s("type")).startsWith("pro")) "professionnel" else "particulier")
        if (a.hv("contact")) c = c.copy(contact = a.s("contact"))
        if (a.hv("tel")) c = c.copy(tel = a.s("tel"))
        if (a.hv("email")) c = c.copy(email = a.s("email"))
        if (a.hv("adresse")) c = c.copy(adresse = a.s("adresse"))
        if (a.hv("depuis")) {
            val dep = parseDepuis(a.s("depuis"))
            if (dep.isNotBlank()) c = c.copy(depuis = dep)
        }
        if (a.hv("notes")) c = c.copy(notes = a.s("notes"))
        var siteTxt = ""
        if (a.hv("site_nom") || a.hv("site_adresse") || a.hv("kwc")) {
            val ref = a.s("site_nom").ifBlank { a.s("site_adresse") }
            val trouve = c.sites.firstOrNull { x -> ref.isNotBlank() && (normaliser(x.nom) == normaliser(ref) || normaliser(x.adresse) == normaliser(ref)) }
                ?: (if (ref.isBlank() && c.sites.size == 1) c.sites.first() else null)
            val isNew = trouve == null
            var site: Site = trouve ?: Site(svc.newId(), a.s("site_nom").ifBlank { "Installation ${c.sites.size + 1}" }, a.s("site_adresse").ifBlank { c.adresse })
            if (a.hv("site_nom")) site = site.copy(nom = a.s("site_nom"))
            if (a.hv("site_adresse")) site = site.copy(adresse = a.s("site_adresse"), distanceKm = null, distanceCle = "")
            val kwc = a.dbl("kwc")
            if (kwc != null) site = site.copy(kwc = kwc)
            if (a.hv("mise_en_service")) site = site.copy(miseEnService = a.s("mise_en_service"))
            if (a.hv("onduleur")) site = site.copy(onduleur = a.s("onduleur"))
            val nbp = a.s("nb_panneaux").toIntOrNull()
            if (nbp != null) site = site.copy(nbPanneaux = nbp)
            if (a.hv("site_notes")) site = site.copy(notes = a.s("site_notes"))
            val fin: Site = site
            c = c.copy(sites = if (isNew) c.sites + fin else c.sites.map { if (it.id == fin.id) fin else it })
            siteTxt = "\n☀️ Installation : ${fin.nom} — ${nombre(fin.kwc)} kWc — ${fin.adresse}"
        }
        val final: Client = c
        svc.repo.update { it.copy(clients = if (nouveau) it.clients + final else it.clients.map { x -> if (x.id == final.id) final else x }) }
        val r = d.remiseClient(final)
        return ok("✅ Client « ${final.nom} » ${if (nouveau) "créé" else "mis à jour"}.$siteTxt\n🎁 Remise applicable aujourd'hui : " + (if (r.pct > 0) "${nombre(r.pct)} % (${r.detail})" else "aucune"))
    }

    private fun clientParNom(nom: String): Pair<Client?, String?> {
        val l = d.chercherClients(nom)
        return when {
            l.isEmpty() -> null to "Client « $nom » introuvable. Le créer avec client_ecrire."
            l.size > 1 && l.none { normaliser(it.nom) == normaliser(nom) } -> null to "Plusieurs clients correspondent : ${l.joinToString { it.nom }}. Préciser le nom."
            else -> (l.firstOrNull { normaliser(it.nom) == normaliser(nom) } ?: l.first()) to null
        }
    }

    private fun siteDe(c: Client, ref: String): Site? {
        if (ref.isNotBlank()) {
            val n = normaliser(ref)
            c.sites.firstOrNull { normaliser(it.nom) == n || normaliser(it.adresse) == n }?.let { return it }
            c.sites.firstOrNull { normaliser(it.nom).contains(n) || normaliser(it.adresse).contains(n) }?.let { return it }
        }
        return if (c.sites.size == 1) c.sites.first() else null
    }

    private suspend fun clientFiche(a: JSONObject): ToolResult {
        val (c, e) = clientParNom(a.s("nom"))
        if (c == null) return err(e!!)
        // met à jour les distances manquantes pour que la fiche soit complète
        c.sites.forEach { s -> if (s.distanceKm == null && d.societe.adresse.isNotBlank()) runCatching { svc.distance(c, s) } }
        val fresh = d.client(c.id) ?: c
        val (md, loc) = svc.exporterClient(fresh)
        return ok("✅ Fiche enregistrée : $loc\n\n---\n\n$md")
    }

    private suspend fun documentCreer(a: JSONObject): ToolResult {
        val type = if (a.s("type").lowercase().startsWith("f")) "facture" else "devis"
        val (client, e) = clientParNom(a.s("client"))
        if (client == null) return err(e!!)
        val refSite = a.s("installation")
        var site: Site? = siteDe(client, refSite)
        if (site == null && client.sites.size > 1) return err("Ce client a plusieurs installations (${client.sites.joinToString { it.nom }}). Laquelle ?")
        val kwcArg = a.dbl("kwc")
        val cur: Site? = site
        if (kwcArg != null && (cur == null || cur.kwc <= 0)) site = (cur ?: Site(svc.newId(), refSite.ifBlank { "Installation" }, client.adresse)).copy(kwc = kwcArg)
        val siteFinal: Site? = site
        val kwc = siteFinal?.kwc ?: 0.0

        val prestations = mutableListOf<Triple<Tarif, Double, Double>>()
        for (part in a.s("prestations").split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            val idx = part.lastIndexOf(':')
            val qte = if (idx > 0) part.substring(idx + 1).replace(',', '.').trim().toDoubleOrNull() else null
            val nom = if (qte != null) part.substring(0, idx) else part
            val t = Pricing.trouverTarif(d.tarifs, nom)
                ?: return err("Aucun tarif ne correspond à « ${nom.trim()} ». Tarifs enregistrés : " + (d.tarifs.joinToString { it.designation }.ifBlank { "aucun (utiliser tarif_set)" }))
            prestations += Triple(t, qte ?: 1.0, kwc)
        }
        val libres = mutableListOf<Ligne>()
        for (part in a.s("lignes_libres").split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            val bits = part.split(':').map { it.trim() }
            val pu = bits.getOrNull(2)?.replace(',', '.')?.toDoubleOrNull() ?: return err("Ligne libre illisible : « $part » (désignation:quantité:prix HT).")
            libres += Ligne(bits[0], bits[1].replace(',', '.').toDoubleOrNull() ?: 1.0, "", pu, tva = d.societe.tvaDefaut)
        }
        if (type == "facture" && !confirm("Créer la facture pour ${client.nom} ? (numéro définitif, non supprimable)", false)) return err("Création de la facture refusée par l'utilisateur.")

        val clientEff = if (siteFinal != null && client.sites.none { it.id == siteFinal.id }) client.copy(sites = client.sites + siteFinal).also { c2 ->
            svc.repo.update { it.copy(clients = it.clients.map { x -> if (x.id == c2.id) c2 else x }) }
        } else client
        return try {
            val r = svc.creerDocument(type, clientEff, siteFinal, prestations, libres, a.bool("deplacement"), a.dbl("remise_pct"), a.s("notes"))
            val md = Rendu.rendreDoc(d, r.doc)
            ok("✅ ${if (type == "devis") "Devis" else "Facture"} **${r.doc.numero}** créé(e) — ${euro(d.totaux(r.doc).ttc)} TTC\n📁 ${r.emplacement}\n" +
                (if (r.avertissements.isNotEmpty()) r.avertissements.joinToString("\n") { "⚠️ $it" } + "\n" else "") + "\n---\n\n$md")
        } catch (ex: IllegalArgumentException) {
            err(ex.message ?: "Création impossible.")
        }
    }

    private suspend fun documentAction(a: JSONObject): ToolResult {
        val numero = a.s("numero").uppercase()
        val doc = d.doc(numero) ?: return err("Document $numero introuvable.")
        return when (normaliser(a.s("action"))) {
            "afficher", "voir" -> ok(Rendu.rendreDoc(d, doc))
            "exporter" -> ok("📁 " + svc.exporter(doc))
            "convertir", "facturer" -> {
                if (!confirm("Transformer le devis ${doc.numero} en facture ? (numéro définitif)", false)) return err("Conversion refusée par l'utilisateur.")
                val r = svc.convertirEnFacture(doc)
                r.fold({ ok("✅ Facture **${it.doc.numero}** créée depuis ${doc.numero}\n📁 ${it.emplacement}\n\n---\n\n" + Rendu.rendreDoc(d, it.doc)) }, { err(it.message ?: "Conversion impossible.") })
            }
            "envoye", "envoyee" -> svc.changerStatut(numero, if (doc.type == "devis") "envoye" else "a_payer")?.let { ok("✅ ${doc.numero} marqué « envoyé ».") } ?: err("Introuvable.")
            "accepte", "accepted" -> if (doc.type != "devis") err("Seul un devis peut être accepté.") else { svc.changerStatut(numero, "accepte"); ok("✅ Devis ${doc.numero} accepté. Dis « convertis-le en facture » quand tu veux facturer.") }
            "refuse" -> if (doc.type != "devis") err("Seul un devis peut être refusé.") else { svc.changerStatut(numero, "refuse"); ok("Devis ${doc.numero} marqué refusé.") }
            "payee", "paye", "payer" -> {
                if (doc.type != "facture") return err("Seule une facture peut être marquée payée.")
                if (doc.statut == "payee") return err("${doc.numero} est déjà payée.")
                if (!confirm("Marquer la facture ${doc.numero} comme payée (${euro(d.totaux(doc).ttc)} TTC) ?", false)) return err("Refusé par l'utilisateur.")
                val (f, prov) = svc.marquerPayee(numero, a.s("mode_paiement")) ?: return err("Introuvable.")
                ok("✅ Facture ${f.numero} payée (${euro(d.totaux(f).ttc)} TTC)." +
                    (if (prov != null) "\n💰 Mis en réserve : **${euro(prov.montant)}** (${nombre(prov.pct)} % de ${euro(prov.baseHt)} HT)" else if (d.societe.reservePct <= 0) "\nℹ️ Aucune réserve configurée (reserve_pct)." else ""))
            }
            "annuler" -> {
                if (!confirm("Annuler ${doc.numero} ? Le numéro reste conservé (une facture n'est jamais supprimée).", false)) return err("Refusé par l'utilisateur.")
                svc.annuler(numero); ok("✅ ${doc.numero} annulé(e)." + (if (doc.type == "facture" && doc.statut == "payee") "\n⚠️ Si elle a été encaissée, un avoir est normalement nécessaire." else ""))
            }
            else -> err("Action inconnue : ${a.s("action")}.")
        }
    }

    private fun liste(a: JSONObject): ToolResult {
        val f = a.s("filtre")
        return ok(when (normaliser(a.s("quoi"))) {
            "clients", "client" -> {
                val l = d.clients.filter { f.isBlank() || normaliser(it.nom).contains(normaliser(f)) }.sortedBy { normaliser(it.nom) }
                if (l.isEmpty()) "Aucun client." else "# 👥 Clients (${l.size})\n\n" + l.joinToString("\n") { "- **${it.nom}** — ${it.sites.size} installation(s), ${nombre(it.sites.sumOf { s -> s.kwc })} kWc · CA ${euro(d.caClient(it))}" }
            }
            "devis", "factures", "facture" -> {
                val type = if (a.s("quoi").lowercase().startsWith("f")) "facture" else "devis"
                val l = d.docs.filter { it.type == type }.filter { x ->
                    f.isBlank() || x.date.startsWith(f) || x.statut == normaliser(f).replace(' ', '_') || (d.client(x.clientId)?.nom?.let { normaliser(it).contains(normaliser(f)) } == true)
                }.sortedByDescending { it.numero }
                if (l.isEmpty()) "Aucun document." else "# 🧾 ${if (type == "devis") "Devis" else "Factures"} (${l.size})\n\n| N° | Client | Date | TTC | Statut |\n|---|---|---|---:|---|\n" +
                    l.joinToString("\n") { "| ${it.numero} | ${d.client(it.clientId)?.nom.orEmpty()} | ${dateFr(it.date)} | ${euro(d.totaux(it).ttc)} | ${Rendu.statutLabel(it.type, it.statut)} |" }
            }
            "employes", "employe", "salaries" -> if (d.employes.isEmpty()) "Aucun employé enregistré." else "# 👷 Équipe\n\n" + d.employes.joinToString("\n") { "- **${it.nom}** (${it.statut}) — ${it.poste.ifBlank { "—" }} — brut ${euro(it.brutMensuel)}/mois" }
            else -> resume()
        })
    }

    private fun employeEcrire(a: JSONObject): ToolResult {
        val ts = a.dbl("taux_salarial")
        if (ts != null) svc.repo.update { it.copy(paie = it.paie.copy(tauxSalarial = ts, tauxSalarialDirigeant = ts)) }
        val tp = a.dbl("taux_patronal")
        if (tp != null) svc.repo.update { it.copy(paie = it.paie.copy(tauxPatronal = tp)) }
        val nom = a.s("nom")
        val existant = d.employes.firstOrNull { normaliser(it.nom) == normaliser(nom) } ?: d.employes.firstOrNull { normaliser(it.nom).contains(normaliser(nom)) }
        val nouveau = existant == null
        var e: Employe = existant ?: Employe(svc.newId(), nom)
        if (a.hv("poste")) e = e.copy(poste = a.s("poste"))
        if (a.hv("statut")) e = e.copy(statut = if (normaliser(a.s("statut")).startsWith("dir") || normaliser(a.s("statut")) == "patron") "dirigeant" else "salarie")
        val brut = a.dbl("brut_mensuel")
        if (brut != null) e = e.copy(brutMensuel = brut)
        val pas = a.dbl("taux_pas")
        if (pas != null) e = e.copy(tauxPas = pas)
        if (a.hv("date_entree")) e = e.copy(dateEntree = a.s("date_entree"))
        val fin: Employe = e
        svc.repo.update { it.copy(employes = if (nouveau) it.employes + fin else it.employes.map { x -> if (x.id == fin.id) fin else x }) }
        return ok("✅ ${fin.nom} ${if (nouveau) "ajouté(e)" else "mis(e) à jour"} (${fin.statut}, brut ${euro(fin.brutMensuel)}/mois, PAS ${nombre(fin.tauxPas)} %).")
    }

    private fun paie(a: JSONObject): ToolResult {
        val n = normaliser(a.s("employe"))
        val e = d.employes.firstOrNull { normaliser(it.nom) == n } ?: d.employes.firstOrNull { normaliser(it.nom).contains(n) }
            ?: return err("Employé introuvable. Le créer avec employe_ecrire. Équipe : ${d.employes.joinToString { it.nom }.ifBlank { "vide" }}")
        val mois = a.s("mois").takeIf { Regex("\\d{4}-\\d{2}").matches(it) } ?: LocalDate.now().toString().take(7)
        val (md, loc) = svc.bulletin(e, mois, a.dbl("primes") ?: 0.0)
        return ok("✅ Bulletin enregistré : $loc\n\n---\n\n$md")
    }

    private suspend fun distanceTool(a: JSONObject): ToolResult {
        if (d.societe.adresse.isBlank()) return err("L'adresse du siège n'est pas enregistrée (societe_info_set).")
        val r: Geo.Route?
        val cible: String
        if (a.hv("client")) {
            val (c, e) = clientParNom(a.s("client"))
            if (c == null) return err(e!!)
            val s = siteDe(c, a.s("installation"))
            cible = s?.adresse?.ifBlank { null } ?: c.adresse
            r = svc.distance(c, s)
        } else {
            cible = a.s("adresse")
            if (cible.isBlank()) return err("Client ou adresse manquant.")
            r = svc.distance(Client("tmp", "tmp", adresse = cible), null)
        }
        r ?: return err("Impossible de localiser les adresses (siège : ${d.societe.adresse} ; destination : $cible).")
        val frais = Pricing.deplacement(d.regles, r.km)
        return ok("🚗 **${d.societe.adresse}** → **$cible**\n- Distance : ${nombre(round2(r.km))} km" + (if (r.estime) " (estimation à vol d'oiseau)" else "") + "\n- Durée : ${r.minutes.toInt()} min\n- Frais de déplacement : " +
            (if (Pricing.deplacementConfigure(d.regles)) euro(frais) else "règles non configurées (regles_set)"))
    }

    private suspend fun presentation(a: JSONObject): ToolResult {
        val cible = normaliser(a.s("cible")).let { if (it == "factures") "facture" else if (it == "fiche client" || it == "clients") "client" else it }
        if (cible !in setOf("client", "devis", "facture", "paie", "planning")) return err("Cible inconnue : ${a.s("cible")} (client, devis, facture, paie, planning).")
        val courant = if (cible == "planning") Prompts.planningFormat else d.modele(cible)
        fun appliquer(nouveau: String?) {
            svc.repo.update { x ->
                val prec = x.modeles[cible] ?: (if (cible == "planning") Templates.PLANNING_FORMAT_DEFAUT else Templates.defaut(cible))
                x.copy(
                    modeles = if (nouveau == null) x.modeles - cible else x.modeles + (cible to nouveau),
                    modelesPrec = x.modelesPrec + (cible to prec)
                )
            }
            if (cible == "planning") Prompts.planningFormat = nouveau ?: Templates.PLANNING_FORMAT_DEFAUT
        }
        return when (normaliser(a.s("action"))) {
            "voir" -> ok("```\n$courant\n```")
            "reinitialiser", "reset" -> { appliquer(null); ok("✅ Présentation « $cible » remise au modèle d'origine (tu peux annuler).") }
            "annuler" -> {
                val prec = d.modelesPrec[cible] ?: return err("Rien à annuler pour « $cible ».")
                svc.repo.update { x -> x.copy(modeles = x.modeles + (cible to prec), modelesPrec = x.modelesPrec - cible) }
                if (cible == "planning") Prompts.planningFormat = prec
                ok("↩️ Dernière modification de « $cible » annulée.")
            }
            else -> {
                val instr = a.s("instruction")
                if (instr.isBlank()) return err("Précise ce que tu veux changer.")
                val systeme = if (cible == "planning")
                    "Tu réécris des CONSIGNES de mise en forme d'un planning (Markdown). Applique la demande de l'utilisateur en conservant les consignes qu'il ne remet pas en cause (liens Google Maps, liens tel:, etc.). Réponds uniquement avec les nouvelles consignes, sans bloc de code."
                else
                    "Tu modifies un modèle Markdown à balises {{nom}}, {{#liste}}…{{/liste}} (répétition) et {{#variable}}…{{/variable}} (affiché si non vide). " +
                        "Applique UNIQUEMENT la demande de l'utilisateur (mise en page, ordre, emojis, titres, colonnes). Tu ne peux utiliser QUE ces balises :\n${Rendu.balisesDisponibles(cible)}\n" +
                        "Conserve toutes les informations légales et les totaux. Chaque {{#x}} doit avoir son {{/x}}. Réponds uniquement avec le modèle complet, sans bloc de code ni commentaire."
                var essai = 0
                var motif: String? = null
                var proposition = ""
                while (essai < 2) {
                    val demande = "Demande : $instr\n\n" + (if (motif != null) "Ta proposition précédente a été rejetée ($motif). Corrige.\n\n" else "") + "Modèle actuel :\n$courant"
                    proposition = app.llm.agent(systeme, demande).trim().removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()
                    motif = Rendu.valider(cible, proposition)
                    if (motif == null) break
                    essai++
                }
                if (motif != null) return err("Je n'ai pas réussi à appliquer ce changement proprement ($motif). Rien n'a été modifié.")
                appliquer(proposition)
                val apercu = if (cible == "planning") "Les prochains plannings suivront cette présentation." else "Aperçu :\n\n---\n\n" + Templates.render(proposition, Rendu.exemple(cible))
                ok("✅ Présentation « $cible » modifiée (dis « annule » pour revenir en arrière).\n\n$apercu")
            }
        }
    }
}
