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
            "societe_info_set", "societe_info_get", "tarif_set", "tarif_delete", "regles_set", "client_ecrire", "client_fiche", "client_supprimer",
            "document_creer", "document_action", "document_email", "societe_liste", "employe_ecrire", "paie", "reserve_bilan",
            "presentation_modifier", "distance_deplacement", "clients_exporter_kml", "kml_couleurs_set", "clients_gps"
        )
        private val KEYWORDS = Regex(
            "devis|factur|client|soci[ée]t[ée]|tarif|remise|d[ée]placement|paie|salaire|bulletin|r[ée]serve|dividende|b[ée]n[ée]fice|" +
                "installation|photovolta|kwc|fiche|pr[ée]sentation|mise en forme|mise en page|si[èe]ge|employ[ée]|prix|onduleur|maintenance|" +
                "entretien|planning|agenda|pdf|obsidian|distance|itin[ée]raire|km|fid[ée]lit[ée]|kml|google earth|carte des clients|" +
                "gps|coordonn[ée]es|g[ée]oloc|localis|latitude|longitude|google maps",
            RegexOption.IGNORE_CASE
        )
        fun concerned(text: String) = KEYWORDS.containsMatchIn(text)

        val PROMPT = """
Module SOCIÉTÉ (maintenance et entretien d'installations photovoltaïques). Tu disposes d'outils pour : enregistrer les informations de l'entreprise (siège, SIRET, IBAN, TVA…), les tarifs par puissance (kWc), les règles de remise (fidélité, nombre d'installations) et de déplacement, les clients et leurs installations, créer des devis et factures, convertir un devis en facture, marquer une facture payée (mise en réserve), produire des bulletins de paie, le bilan de la réserve, et modifier la présentation des documents.
- Quand l'utilisateur donne un tarif, une règle ou une information sur la société, enregistre-la immédiatement avec l'outil adapté : c'est ainsi que tu la gardes en mémoire.
- Ne calcule jamais un montant toi-même : les outils font tous les calculs (tarif par puissance, remises, déplacement, TVA).
- Quand l’utilisateur demande de supprimer une fiche client, utilise client_supprimer (jamais client_ecrire). Ne dis que la fiche est supprimée que si l’outil confirme sa disparition. Si des devis/factures y sont liés, explique que la suppression est bloquée pour préserver l’historique.
- Si l’utilisateur demande d’analyser son planning pour ajouter les rendez-vous aux fiches clients, utilise TOUJOURS analyser_planning_clients (et pas seulement get_planning). Cet outil traite chaque client séparément, même lorsqu’un rendez-vous en mentionne plusieurs, extrait les coordonnées et les caractéristiques photovoltaïques explicitement présentes, puis demande confirmation avant l’enregistrement. Ne crée pas de nouveau client à partir d’un nom incertain.
- Quand l’utilisateur demande une carte KML des clients ou des installations pour Google Earth, utilise clients_exporter_kml. Le fichier contient une fiche HTML illustrée (emojis), un repère coloré selon particulier/professionnel et des champs de recherche par client, type et adresse. Si un nom est donné, passe-le dans recherche pour filtrer la carte.\n- Si l’utilisateur demande de changer les couleurs des clients sur Google Earth, utilise kml_couleurs_set pour enregistrer les couleurs puis régénère le KML avec clients_exporter_kml.
- Si l’utilisateur donne des coordonnées GPS pour une installation (copiées de Google Maps « 47.541558, 4.459845 », en degrés-minutes-secondes 47°32'29.6"N 4°27'35.4"E, ou un lien Google Maps), passe-les TELLES QUELLES dans site_gps de client_ecrire (avec site_nom si le client a plusieurs installations) ; pour le siège, dans gps de societe_info_set. Ces coordonnées priment sur l’adresse et ne sont jamais recalculées.
- Si l’utilisateur est sur place et demande d’enregistrer « ma position », « ma localisation exacte », « ici »… sur une fiche client, appelle client_ecrire avec site_gps = « ma position » ET site_adresse = « ma position » (l’outil relève le GPS du téléphone et l’adresse précise correspondante) ; ne demande pas l’adresse. S’il parle de toutes ses installations (« ses deux installations »), mets site_nom = « toutes ».
- Pour voir ou améliorer la précision des positions GPS des clients, utilise clients_gps (relocaliser=true pour recalculer avec la Base Adresse Nationale). N’invente jamais de coordonnées.
- Pour un devis ou une facture, identifie le client et l'installation (client_ecrire si le client n'existe pas encore). Si une information indispensable manque (client, prestation), demande-la en une phrase.
- Le texte renvoyé par les outils société est déjà mis en forme et affiché tel quel : ne le répète pas.
- Les montants sont toujours en euros, la TVA est gérée par l'application.
- Pour envoyer un devis ou une facture par email (envoi initial ou relance), utilise TOUJOURS document_email : il joint le PDF du document au mail. N'utilise jamais mail_envoyer pour un devis/une facture, car il n'attache aucune pièce jointe.
- N'invente JAMAIS un pourcentage de remise (remise_pct) pour qu'un devis atteigne un montant total donné par l'utilisateur : laisse remise_pct vide et affiche le vrai total (tarif + déplacement), quitte à dire explicitement qu'il dépasse le montant évoqué. Seule une remise que l'utilisateur chiffre lui-même explicitement doit être passée dans remise_pct.
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
        tool("clients_exporter_kml", "Génère le fichier KML Google Earth avec fiches contact colorées, emojis, noms de clients et données de recherche. Peut filtrer les clients par nom, société, adresse ou installation.", mapOf("recherche" to prop("Texte facultatif pour exporter uniquement les clients correspondant à un nom, une société, une adresse ou une installation."))),
        tool("clients_gps", "Affiche les coordonnées GPS (format Google Maps « latitude, longitude ») et la précision de localisation (adresse exacte, rue, lieu-dit, commune, saisie) de chaque installation, positions approximatives en tête. relocaliser=true recalcule les positions avec la Base Adresse Nationale (IGN) puis OpenStreetMap, sans toucher aux coordonnées saisies à la main.", mapOf(
            "recherche" to prop("Nom de client, d'installation ou d'adresse pour filtrer (optionnel)."),
            "relocaliser" to prop("« true » pour recalculer les positions (hors coordonnées saisies).")
        )),
        tool("kml_couleurs_set", "Définit les couleurs des repères Google Earth : clients particuliers, clients professionnels et siège. Couleur au format #RRGGBB ou nom simple (vert, bleu, rouge, orange, jaune, violet, rose, turquoise, marron, noir, blanc, gris).", mapOf("particuliers" to prop("Couleur des clients particuliers, ex. #20A45A ou « vert »."), "professionnels" to prop("Couleur des clients professionnels, ex. #2878D0 ou « bleu »."), "siege" to prop("Couleur du repère du siège, ex. #FF9800 ou « orange »."))),
        tool("societe_info_set", "Enregistre les informations de la société (seuls les champs fournis sont modifiés).", mapOf(
            "nom" to prop("Nom de la société."), "forme" to prop("Forme juridique (EURL, SASU, micro-entreprise…)."),
            "siret" to prop("SIRET."), "tva_num" to prop("N° de TVA intracommunautaire."),
            "adresse" to prop("Adresse du siège (point de départ des déplacements)."),
            "gps" to prop("Coordonnées GPS exactes du siège, copiées de Google Maps (« 47.541558, 4.459845 »), en degrés-minutes-secondes ou lien Google Maps."),
            "tel" to prop("Téléphone."), "email" to prop("Email."), "iban" to prop("IBAN."), "bic" to prop("BIC."),
            "tva_defaut" to prop("Taux de TVA par défaut en % (ex. 20, 10, 5.5)."),
            "franchise_tva" to prop("« true » si TVA non applicable (micro-entreprise, art. 293 B)."),
            "tarifs_saisis_ttc" to prop("« true » si les prix donnés pour tarif_set sont TTC (convertis en HT automatiquement) ; « false » (défaut) s'ils sont déjà HT."),
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
            "site_nom" to prop("Nom de l'installation (ex. « Toiture garage »). « toutes » pour appliquer la mise à jour à toutes les installations du client."),
            "site_adresse" to prop("Adresse de l'installation. « ma position » = adresse précise (n°, rue, code postal, ville) de l'endroit où se trouve le téléphone."),
            "kwc" to prop("Puissance de l'installation en kWc."), "mise_en_service" to prop("Date de mise en service yyyy-MM-dd."),
            "onduleur" to prop("Modèle d'onduleur."), "nb_panneaux" to prop("Nombre de panneaux."), "site_notes" to prop("Notes sur l'installation."),
            "site_gps" to prop("Coordonnées GPS exactes de l'installation telles que copiées dans Google Maps (« 47.541558, 4.459845 »), en degrés-minutes-secondes (47°32'29.6\"N 4°27'35.4\"E) ou lien Google Maps. Prioritaires sur l'adresse. « ma position » = position GPS actuelle du téléphone (quand l'utilisateur est sur place).")
        ), listOf("nom")),
        tool("client_fiche", "Affiche la fiche client complète et l'enregistre dans le coffre Obsidian.", mapOf("nom" to prop("Nom du client.")), listOf("nom")),
        tool("client_supprimer", "Supprime définitivement une fiche client de la base Société après confirmation. Refuse la suppression si des devis ou factures sont encore liés afin de préserver l’historique comptable.", mapOf("nom" to prop("Nom exact du client à supprimer.")), listOf("nom")),
        tool("document_creer", "Crée un devis ou une facture (tarifs, remises et déplacement calculés automatiquement), l'enregistre en Markdown et PDF dans Obsidian.", mapOf(
            "type" to prop("devis | facture"), "client" to prop("Nom du client."), "installation" to prop("Nom ou adresse de l'installation (optionnel si une seule)."),
            "prestations" to prop("Prestations du catalogue de tarifs : « Maintenance annuelle; Contrôle onduleur:2 » (nom[:quantité], séparées par « ; »)."),
            "lignes_libres" to prop("Lignes hors catalogue : « Fusible 32A:2:35.50; Câble:1:120 » (désignation:quantité:prix HT unitaire)."),
            "kwc" to prop("Puissance en kWc si l'installation n'est pas enregistrée."),
            "deplacement" to prop("« false » pour ne pas facturer de déplacement (par défaut : calculé depuis le siège)."),
            "remise_pct" to prop("Remise exceptionnelle en %, UNIQUEMENT si l'utilisateur a explicitement demandé un pourcentage de remise précis. Ne JAMAIS calculer ou deviner ce pourcentage toi-même pour faire correspondre un montant total désiré (ex. « un devis de 400 € tout compris ») : laisse ce champ vide et affiche le vrai total calculé par l'outil, déplacement inclus, même s'il diffère du montant que l'utilisateur avait en tête — il peut ensuite ajuster manuellement si besoin. Vide = remise automatique (fidélité/volume) selon les règles enregistrées."),
            "notes" to prop("Notes à afficher sur le document.")
        ), listOf("type", "client", "prestations")),
        tool("document_action", "Agit sur un devis ou une facture existant.", mapOf(
            "numero" to prop("Numéro, ex. DEV-2026-0003 ou FAC-2026-0001."),
            "action" to prop("afficher | convertir (devis → facture) | envoye | accepte | refuse | payee | annuler | exporter | supprimer"),
            "mode_paiement" to prop("Pour « payee » : virement, chèque, CB, espèces…")
        ), listOf("numero", "action")),
        tool("document_email", "Envoie (ou renvoie) un devis ou une facture par email, avec le PDF du document en pièce jointe (compte Gmail connecté requis, Réglages › Mails). Demande toujours confirmation avant l'envoi.", mapOf(
            "numero" to prop("Numéro du devis/facture, ex. DEV-2026-0003."),
            "destinataire" to prop("Adresse email du destinataire (optionnel : par défaut l'email enregistré du client)."),
            "compte" to prop("Compte Gmail à utiliser pour l'envoi (optionnel ; vide = le seul compte connecté, ou demande si plusieurs)."),
            "sujet" to prop("Objet du mail (optionnel, un objet par défaut est généré)."),
            "message" to prop("Texte du mail (optionnel, un message par défaut est généré).")
        ), listOf("numero")),
        tool("societe_liste", "Liste les clients, devis, factures, employés ou tarifs.", mapOf(
            "quoi" to prop("clients | devis | factures | employes | tarifs"),
            "filtre" to prop("Nom de client, statut ou année (optionnel).")
        ), listOf("quoi")),
        tool("employe_ecrire", "Crée ou modifie le dirigeant ou un salarié (pour les bulletins de paie).", mapOf(
            "nom" to prop("Nom."), "poste" to prop("Poste."), "statut" to prop("dirigeant | salarie"),
            "brut_mensuel" to prop("Salaire brut mensuel en € (si le salaire est négocié en brut)."),
            "net_mensuel" to prop("Salaire net mensuel visé (avant impôt) en € (si le salaire est négocié en net) : le brut nécessaire est recalculé automatiquement à chaque bulletin selon les cotisations réelles. Fournir soit brut_mensuel, soit net_mensuel, pas les deux."),
            "heures_hebdo" to prop("Base horaire contractuelle hebdomadaire : 35 (défaut), 39, 40… — sert à calculer les heures mensuelles et le taux horaire affichés sur le bulletin."),
            "taux_pas" to prop("Taux de prélèvement à la source en %."),
            "date_entree" to prop("Date d'entrée yyyy-MM-dd."),
            "cadre" to prop("« true » si le salarié (ou le dirigeant) est cadre (ajoute APEC/CET et le régime AGIRC-ARRCO) ; s'applique à toute l'équipe."),
            "plafond_secu" to prop("Plafond mensuel de la Sécurité sociale (PMSS) en € — à vérifier/mettre à jour chaque année (s'applique à toute l'équipe)."),
            "taux_atmp" to prop("Taux accident du travail / maladies professionnelles en % (dépend de l'activité ; s'applique à toute l'équipe)."),
            "taux_formation" to prop("Taux de la contribution formation professionnelle en % (s'applique à toute l'équipe).")
        ), listOf("nom")),
        tool("paie", "Produit le bulletin de paie DÉTAILLÉ (cotisations ligne par ligne : sécurité sociale, retraite, chômage, CSG/CRDS…) d'un employé ou du dirigeant pour un mois. Par défaut utilise les heures contractuelles. Pour un mois avec des heures supplémentaires ou une absence, renseigner SOIT heures_effectuees (le nombre d'heures réellement travaillées : les heures sup au-delà de la base contractuelle sont calculées et majorées automatiquement), SOIT net_verse (le montant net réellement versé ce mois-ci : le nombre d'heures travaillées est alors déduit automatiquement par calcul inverse). Ne JAMAIS renseigner les deux à la fois.", mapOf(
            "employe" to prop("Nom de l'employé."), "mois" to prop("yyyy-MM (par défaut le mois en cours)."), "primes" to prop("Primes du mois en € (optionnel)."),
            "heures_effectuees" to prop("Nombre d'heures réellement travaillées ce mois-ci (optionnel). Les heures au-delà de la base contractuelle sont automatiquement comptées en heures supplémentaires majorées 25 %/50 %. Ne pas renseigner en même temps que net_verse."),
            "net_verse" to prop("Montant net réellement versé/transféré à l'employé ce mois-ci en € (optionnel). JARVIS calcule automatiquement le nombre d'heures (dont heures sup) qui correspond à ce net. Ne pas renseigner en même temps que heures_effectuees.")
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
        "client_supprimer" -> clientSupprimer(a)
        "document_creer" -> documentCreer(a)
        "document_action" -> documentAction(a)
        "document_email" -> documentEmail(a)
        "societe_liste" -> liste(a)
        "employe_ecrire" -> employeEcrire(a)
        "paie" -> paie(a)
        "reserve_bilan" -> ok(svc.bilanTexte(a.s("annee").toIntOrNull() ?: LocalDate.now().year))
        "presentation_modifier" -> presentation(a)
        "distance_deplacement" -> distanceTool(a)
        "clients_exporter_kml" -> ok(svc.exporterKml(a.s("recherche")))
        "kml_couleurs_set" -> kmlCouleursSet(a)
        "clients_gps" -> ok(svc.rapportGps(a.s("recherche"), a.bool("relocaliser") == true))
        else -> err("Outil inconnu : $name")
    }

    private fun kmlCouleursSet(a: JSONObject): ToolResult {
        val noms = mapOf(
            "vert" to "#20A45A", "bleu" to "#2878D0", "rouge" to "#E53935", "orange" to "#FF9800", "jaune" to "#FDD835",
            "violet" to "#8E24AA", "rose" to "#EC407A", "turquoise" to "#00ACC1", "cyan" to "#00ACC1", "marron" to "#795548",
            "noir" to "#212121", "blanc" to "#FFFFFF", "gris" to "#9E9E9E"
        )
        fun couleur(k: String): String? {
            val brut = a.s(k).trim()
            if (brut.isBlank()) return null
            val nom = java.text.Normalizer.normalize(brut.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            noms[nom]?.let { return it }
            val value = brut.uppercase().let { if (it.startsWith("#")) it else "#$it" }
            return value.takeIf { it.matches(Regex("#[0-9A-F]{6}")) }
        }
        val particulier = couleur("particuliers")
        val professionnel = couleur("professionnels")
        val siege = couleur("siege")
        if (particulier == null && professionnel == null && siege == null)
            return err("Couleur invalide. Utilise #RRGGBB (par exemple #20A45A) ou un nom simple : ${noms.keys.joinToString()}.")
        val before = d.societe
        val after = before.copy(
            kmlCouleurParticulier = particulier ?: before.kmlCouleurParticulier,
            kmlCouleurProfessionnel = professionnel ?: before.kmlCouleurProfessionnel,
            kmlCouleurSiege = siege ?: before.kmlCouleurSiege
        )
        svc.repo.update { it.copy(societe = after) }
        return ok("🎨 Couleurs Google Earth enregistrées.\n- 🏠 Particuliers : ${after.kmlCouleurParticulier}\n- 🏢 Professionnels : ${after.kmlCouleurProfessionnel}\n- 🏭 Siège : ${after.kmlCouleurSiege}\n\nDemande maintenant « exporte la carte KML » pour appliquer les couleurs aux repères.")
    }

    /** Coordonnées GPS saisies (texte Google Maps, degrés-minutes-secondes ou lien, y compris lien court). */
    private suspend fun lireGps(texte: String): Geo.Point? =
        Geo.lireCoordonnees(texte) ?: Geo.localiser(texte)?.takeIf { it.precision == Geo.Precision.MANUELLE }?.point

    private val aideGps = "Format attendu : « 47.541558, 4.459845 » (dans Google Maps : appui long sur le lieu, puis touche les coordonnées pour les copier), " +
        "47°32'29.6\"N 4°27'35.4\"E, ou un lien Google Maps."

    private suspend fun infoSet(a: JSONObject): ToolResult {
        val before = d.societe
        var s = before
        if (a.hv("nom")) s = s.copy(nom = a.s("nom"))
        if (a.hv("forme")) s = s.copy(forme = a.s("forme"))
        if (a.hv("siret")) s = s.copy(siret = a.s("siret"))
        if (a.hv("tva_num")) s = s.copy(tvaNum = a.s("tva_num"))
        if (a.hv("adresse")) s = s.copy(adresse = a.s("adresse"), lat = null, lon = null, gpsPrecision = "")
        if (a.hv("gps")) {
            val p = lireGps(a.s("gps")) ?: return err("Coordonnées GPS du siège illisibles : « ${a.s("gps")} ». $aideGps")
            s = s.copy(lat = p.lat, lon = p.lon, gpsPrecision = Geo.Precision.MANUELLE.name)
        }
        if (a.hv("tel")) s = s.copy(tel = a.s("tel"))
        if (a.hv("email")) s = s.copy(email = a.s("email"))
        if (a.hv("iban")) s = s.copy(iban = a.s("iban"))
        if (a.hv("bic")) s = s.copy(bic = a.s("bic"))
        a.dbl("tva_defaut")?.let { s = s.copy(tvaDefaut = it) }
        a.bool("franchise_tva")?.let { s = s.copy(franchiseTva = it) }
        a.bool("tarifs_saisis_ttc")?.let { s = s.copy(tarifsSaisisTtc = it) }
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
            append("- TVA : " + (if (s.franchiseTva) "non applicable (art. 293 B)" else "${nombre(s.tvaDefaut)} %") + " · paiement ${s.delaiPaiementJours} j · devis valable ${s.validiteDevisJours} j\n")
            append("- Tarifs saisis en : " + (if (s.tarifsSaisisTtc) "TTC (convertis en HT à l'enregistrement)" else "HT") + "\n\n")
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
        val tva = a.dbl("tva") ?: d.societe.tvaDefaut
        if (d.societe.tarifsSaisisTtc && !d.societe.franchiseTva) tr = tr.map { it.copy(prix = versHt(it.prix, tva, true)) }
        val existing = Pricing.trouverTarif(d.tarifs, nom)?.takeIf { normaliser(it.designation) == normaliser(nom) }
        val t = Tarif(existing?.designation ?: nom, mode, tr, a.dbl("tva"))
        svc.repo.update { it.copy(tarifs = it.tarifs.filterNot { x -> x.designation == t.designation } + t) }
        val note = if (d.societe.tarifsSaisisTtc && !d.societe.franchiseTva) " (saisi en TTC, converti en HT)" else ""
        return ok("✅ Tarif ${if (existing != null) "modifié" else "enregistré"} : **${t.designation}** (${mode.name.lowercase()}) — ${Pricing.libelleTranches(t)}$note")
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

    private suspend fun clientSupprimer(a: JSONObject): ToolResult {
        val nom = a.s("nom")
        if (nom.isBlank()) return err("Nom du client manquant.")
        val trouves = d.chercherClients(nom)
        val exact = trouves.filter { normaliser(it.nom) == normaliser(nom) }
        val cible = when {
            exact.size == 1 -> exact.first()
            exact.size > 1 -> return err("Plusieurs fiches portent ce nom. Précise un autre identifiant.")
            trouves.size == 1 -> trouves.first()
            trouves.size > 1 -> return err("Plusieurs clients correspondent : ${trouves.joinToString { it.nom }}. Précise le nom exact.")
            else -> return err("Aucune fiche client trouvée pour « $nom ».")
        }
        val documents = d.docs.filter { it.clientId == cible.id }
        if (documents.isNotEmpty()) {
            val liste = documents.take(8).joinToString(", ") { it.numero }
            return err("La fiche « ${cible.nom} » est liée à ${documents.size} devis/facture(s) ($liste${if (documents.size > 8) "…" else ""}). Je bloque la suppression pour ne pas casser l’historique comptable. Il faut d’abord transférer ou traiter ces documents.")
        }
        if (!confirm("Supprimer définitivement la fiche client « ${cible.nom} » et ses ${cible.sites.size} installation(s) de la base Société ? Cette action est irréversible.", true))
            return err("Suppression refusée. La fiche « ${cible.nom} » est conservée.")
        svc.repo.update { data -> data.copy(clients = data.clients.filterNot { it.id == cible.id }) }
        val removed = d.clients.none { it.id == cible.id }
        if (!removed) return err("❌ La fiche « ${cible.nom} » est toujours présente après la tentative de suppression. Aucune confirmation de réussite.")
        // La fiche Markdown exportée est une copie de présentation : la retirer aussi si elle existe.
        val markdownPath = "Société/Clients/${cible.nom}.md"
        val markdownExists = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            markdownPath in app.obsidian.listNotes("Société/Clients")
        }
        val markdownDeleted = if (markdownExists) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.obsidian.supprimerNote(markdownPath)
        } else true
        val markdownStillThere = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            markdownPath in app.obsidian.listNotes("Société/Clients")
        }
        return if (markdownDeleted && !markdownStillThere)
            ok("🗑️ Fiche client « ${cible.nom} », ses installations et sa fiche Markdown Obsidian supprimées.")
        else err("⚠️ La fiche client « ${cible.nom} » a bien été retirée de la base Société, mais sa copie Markdown Obsidian est encore présente ou inaccessible : ${markdownPath}. Vérifie les droits d'écriture du coffre.")
    }
    /** « ma position », « ma localisation exacte », « ici », « où je suis »… = position actuelle du téléphone. */
    private fun estMaPosition(t: String): Boolean {
        val n = normaliser(t).trim()
        return n == "ici" || Regex("\\b(ma|mon|notre)\\s+(position|localisation|emplacement|geolocalisation|point gps)|position (actuelle|du telephone|gps actuelle)|ou je suis|ou je me trouve|sur place").containsMatchIn(n)
    }
    private fun estToutes(t: String) =
        Regex("^(toutes?|tous|les deux|les \\d+|toutes? (les|ses) (installations?|sites?)|tous (les|ses) sites?|les deux installations?)$").matches(normaliser(t).trim())

    /**
     * client_ecrire : remplace « ma position » (site_gps / site_adresse) par la position FRAÎCHE du téléphone et l'adresse
     * précise correspondante, et applique la mise à jour à toutes les installations si site_nom = « toutes ».
     */
    private suspend fun clientEcrire(entree: JSONObject): ToolResult {
        val a = JSONObject(entree.toString())
        var infoPos = ""
        val gpsIci = a.hv("site_gps") && estMaPosition(a.s("site_gps"))
        val adresseIci = a.hv("site_adresse") && estMaPosition(a.s("site_adresse"))
        if (gpsIci || adresseIci) {
            val (p, precision) = fr.dd.jarvis.device.Locator.pointPrecis(app)
                ?: return err("Position du téléphone indisponible : active la localisation (et l'autorisation de localisation pour Jarvis), puis réessaie.")
            val adr = Geo.adresseDe(p)
            // Sur place : le point du téléphone vaut pour l'installation, même si seule l'adresse a été demandée.
            if (gpsIci || !a.hv("site_gps")) a.put("site_gps", Geo.texteGps(p))
            a.put("_gps_source", "position du téléphone sur place (± ${precision.toInt()} m)")
            if (adresseIci) { if (adr != null) a.put("site_adresse", adr.libelle) else a.remove("site_adresse") }
            infoPos = "\n📍 Position du téléphone : ${Geo.texteGps(p)} (± ${precision.toInt()} m)" +
                (adr?.let { "\n🏠 Adresse à cet endroit : ${it.libelle}" + (it.distance?.takeIf { d -> d > 15 }?.let { d -> " (point d'adresse le plus proche, ~$d m)" } ?: "") }
                    ?: "\n🏠 Aucune adresse postale trouvée à cet endroit : seules les coordonnées GPS sont enregistrées.")
        }
        if (a.hv("site_nom") && estToutes(a.s("site_nom"))) {
            val c = d.chercherClients(a.s("nom")).let { l -> l.firstOrNull { normaliser(it.nom) == normaliser(a.s("nom")) } ?: l.singleOrNull() }
            if (c != null && c.sites.isNotEmpty()) {
                val res = c.sites.map { site ->
                    val un = JSONObject(a.toString()).put("site_nom", site.nom)
                    clientEcrireUn(un)
                }
                res.firstOrNull { !it.direct }?.let { return it }
                return ok("✅ ${c.nom} : ${c.sites.size} installation(s) mise(s) à jour (${c.sites.joinToString { it.nom }}).$infoPos" +
                    res.joinToString("") { r -> r.text.lines().filter { it.startsWith("☀️") || it.startsWith("🛰️") }.joinToString("\n", prefix = "\n") })
            }
            a.remove("site_nom")
        }
        val r = clientEcrireUn(a)
        return if (infoPos.isBlank()) r else r.copy(text = r.text + infoPos)
    }

    private suspend fun clientEcrireUn(a: JSONObject): ToolResult {
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
        val gpsSaisi = if (a.hv("site_gps")) lireGps(a.s("site_gps")) ?: return err("Coordonnées GPS illisibles : « ${a.s("site_gps")} ». $aideGps") else null
        if (a.hv("site_nom") || a.hv("site_adresse") || a.hv("kwc") || a.hv("onduleur") || a.hv("nb_panneaux") || a.hv("mise_en_service") || a.hv("site_notes") || gpsSaisi != null) {
            val ref = a.s("site_nom").ifBlank { a.s("site_adresse") }
            if (gpsSaisi != null && ref.isBlank() && c.sites.size > 1)
                return err("${c.nom} a plusieurs installations (${c.sites.joinToString { it.nom }}). Pour laquelle sont ces coordonnées GPS ? (site_nom, ou « toutes » pour toutes ses installations)")
            val trouve = c.sites.firstOrNull { x -> ref.isNotBlank() && (normaliser(x.nom) == normaliser(ref) || normaliser(x.adresse) == normaliser(ref)) }
                ?: (if (ref.isBlank() && c.sites.size == 1) c.sites.first() else null)
            val isNew = trouve == null
            var site: Site = trouve ?: Site(svc.newId(), a.s("site_nom").ifBlank { "Installation ${c.sites.size + 1}" }, a.s("site_adresse").ifBlank { c.adresse })
            if (a.hv("site_nom")) site = site.copy(nom = a.s("site_nom"))
            // Nouvelle adresse : l'ancienne position GPS ne vaut plus (elle sera recalculée), sauf coordonnées fournies.
            if (a.hv("site_adresse") && normaliser(a.s("site_adresse")) != normaliser(site.adresse))
                site = site.copy(adresse = a.s("site_adresse"), distanceKm = null, distanceCle = "", lat = null, lon = null, gpsPrecision = "", gpsTrouve = "")
            if (gpsSaisi != null) site = site.copy(
                lat = gpsSaisi.lat, lon = gpsSaisi.lon, gpsPrecision = Geo.Precision.MANUELLE.name, gpsTrouve = a.s("_gps_source").ifBlank { "coordonnées GPS saisies" },
                distanceKm = null, dureeMin = null, distanceCle = ""
            )
            val kwc = a.dbl("kwc")
            if (kwc != null) site = site.copy(kwc = kwc)
            if (a.hv("mise_en_service")) site = site.copy(miseEnService = a.s("mise_en_service"))
            if (a.hv("onduleur")) site = site.copy(onduleur = a.s("onduleur"))
            val nbp = a.s("nb_panneaux").toIntOrNull()
            if (nbp != null) site = site.copy(nbPanneaux = nbp)
            if (a.hv("site_notes")) site = site.copy(notes = a.s("site_notes"))
            val fin: Site = site
            c = c.copy(sites = if (isNew) c.sites + fin else c.sites.map { if (it.id == fin.id) fin else it })
            siteTxt = "\n☀️ Installation : ${fin.nom} — ${nombre(fin.kwc)} kWc — ${fin.adresse}" +
                (if (gpsSaisi != null) "\n🛰️ GPS : ${Geo.texteGps(gpsSaisi)} (${a.s("_gps_source").ifBlank { "coordonnées saisies" }}, format Google Maps) — ${Geo.texteDms(gpsSaisi)}" else "")
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
        val remisePct = a.dbl("remise_pct")
        return try {
            val r = svc.creerDocument(type, clientEff, siteFinal, prestations, libres, a.bool("deplacement"), remisePct, a.s("notes"))
            val md = Rendu.rendreDoc(d, r.doc)
            ok("✅ ${if (type == "devis") "Devis" else "Facture"} **${r.doc.numero}** créé(e) — ${euro(d.totaux(r.doc).ttc)} TTC\n📁 ${r.emplacement}\n" +
                (if (remisePct != null) "⚠️ Remise manuelle appliquée : ${nombre(remisePct)} % (demandée dans l'appel de l'outil, pas calculée automatiquement)\n" else "") +
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
            "supprimer", "effacer" -> {
                val avertFacture = if (doc.type == "facture")
                    "\n⚠️ Une facture émise doit légalement garder une numérotation continue : la supprimer laisse un trou dans la séquence. Pour une facture déjà envoyée, « Annuler » (qui garde le numéro) est en principe la bonne solution — un avoir si elle a été encaissée. Supprimer n'est recommandé que pour une erreur jamais envoyée."
                else ""
                if (!confirm("Supprimer DÉFINITIVEMENT ${doc.numero} ? Action irréversible.$avertFacture", true)) return err("Suppression refusée par l'utilisateur.")
                val (_, fichierSupprime) = svc.supprimerDocument(numero) ?: return err("Introuvable.")
                if (fichierSupprime) ok("🗑️ ${doc.numero} supprimé définitivement (fiche effacée du coffre Obsidian).")
                else ok("🗑️ ${doc.numero} retiré de la liste, mais le fichier n'a pas pu être retrouvé dans le coffre Obsidian pour être effacé (déjà supprimé manuellement, ou dossier/nom différent) — à vérifier et supprimer à la main si besoin.")
            }
            else -> err("Action inconnue : ${a.s("action")}.")
        }
    }

    private suspend fun documentEmail(a: JSONObject): ToolResult {
        val numero = a.s("numero").uppercase()
        val doc = d.doc(numero) ?: return err("Document $numero introuvable.")
        if (app.store.gmailAccounts.isEmpty()) return err("Envoyer un mail demande un compte Google connecté (Réglages › Mails) — pas disponible en IMAP.")
        val client = d.client(doc.clientId)
        val dest = a.s("destinataire").ifBlank { client?.email.orEmpty() }
        if (dest.isBlank() || !dest.contains("@"))
            return err("Adresse email du destinataire manquante" + (if (client != null) " (le client « ${client.nom} » n'a pas d'email enregistré — précise un destinataire, ou enregistre-le avec client_ecrire)." else "."))
        val q = a.s("compte").lowercase()
        val candidats = if (q.isBlank()) app.store.gmailAccounts.toList() else app.store.gmailAccounts.filter { it.lowercase().contains(q) }
        if (candidats.isEmpty()) return err("Aucun compte Gmail ne correspond à « ${a.s("compte")} ». Comptes : ${app.store.gmailAccounts.joinToString(", ")}")
        if (candidats.size > 1) return err("Plusieurs comptes Gmail correspondent : ${candidats.joinToString(", ")}. Précise lequel utiliser.")
        val compte = candidats[0]
        val estDevis = doc.type == "devis"
        val typeLabel = if (estDevis) "devis" else "facture"
        val article = if (estDevis) "le" else "la"
        val accord = if (estDevis) "" else "e"
        val sujet = a.s("sujet").ifBlank { "Votre $typeLabel ${doc.numero}" + (if (d.societe.nom.isNotBlank()) " — ${d.societe.nom}" else "") }
        val corps = a.s("message").ifBlank {
            "Bonjour,\n\nVeuillez trouver ci-joint votre $typeLabel ${doc.numero} (${euro(d.totaux(doc).ttc)} TTC).\n\nCordialement,\n${d.societe.nom.ifBlank { compte }}"
        }
        val nomFichier = "${doc.numero}.pdf"
        val pdf = svc.pdfBytes(doc)
        // force=true : une vraie confirmation à chaque fois ; direct=true : le ✅/❌ réel s'affiche tel quel, jamais reformulé.
        if (!confirm("Envoyer $article $typeLabel ${doc.numero} par email depuis $compte ?\n\nÀ : $dest\nObjet : $sujet\nPièce jointe : $nomFichier\n\n${corps.take(400)}", true))
            return ToolResult("Envoi refusé par l'utilisateur.", direct = true)
        return try {
            val id = fr.dd.jarvis.device.GmailApi.envoyer(app, compte, dest, sujet, corps, listOf(fr.dd.jarvis.device.GmailApi.PieceJointe(nomFichier, "application/pdf", pdf)))
            val premierEnvoi = estDevis && doc.statut == "brouillon"
            if (premierEnvoi) svc.changerStatut(numero, "envoye")
            // Trace de l'envoi (initial ou relance) dans le coffre Obsidian, par client, pour garder l'historique des échanges.
            runCatching {
                val ligne = "- ${java.time.LocalDate.now()} — ${if (premierEnvoi) "Envoi" else "Relance"} $typeLabel **${doc.numero}** (${euro(d.totaux(doc).ttc)} TTC) à $dest"
                app.obsidian.writeNote("Société/Relances/${(client?.nom ?: dest).replace('/', '-')}.md", ligne, append = true)
            }
            ToolResult("✅ ${typeLabel.replaceFirstChar { it.uppercase() }} ${doc.numero} envoyé$accord par email à $dest depuis $compte, PDF « $nomFichier » en pièce jointe (id $id).", direct = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult("❌ Envoi impossible : ${e.message ?: e.javaClass.simpleName}", direct = true)
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
            "employes", "employe", "salaries" -> if (d.employes.isEmpty()) "Aucun employé enregistré." else "# 👷 Équipe\n\n" + d.employes.joinToString("\n") {
                "- **${it.nom}** (${it.statut}) — ${it.poste.ifBlank { "—" }} — " +
                    (if (it.netMensuelVise > 0) "net visé ${euro(it.netMensuelVise)}/mois (brut ${euro(it.brutMensuel)})" else "brut ${euro(it.brutMensuel)}/mois") +
                    " — ${nombre(it.heuresHebdo)} h/semaine"
            }
            else -> resume()
        })
    }

    private fun employeEcrire(a: JSONObject): ToolResult {
        var cfg = d.paie
        a.bool("cadre")?.let { cfg = cfg.copy(cadre = it) }
        a.dbl("plafond_secu")?.let { cfg = cfg.copy(plafondSecu = it) }
        a.dbl("taux_atmp")?.let { cfg = cfg.copy(tauxAtmp = it) }
        a.dbl("taux_formation")?.let { cfg = cfg.copy(tauxFormationPct = it) }
        val cfgFinal = cfg

        val nom = a.s("nom")
        val existant = d.employes.firstOrNull { normaliser(it.nom) == normaliser(nom) } ?: d.employes.firstOrNull { normaliser(it.nom).contains(normaliser(nom)) }
        val nouveau = existant == null
        var e: Employe = existant ?: Employe(svc.newId(), nom)
        if (a.hv("poste")) e = e.copy(poste = a.s("poste"))
        if (a.hv("statut")) e = e.copy(statut = if (normaliser(a.s("statut")).startsWith("dir") || normaliser(a.s("statut")) == "patron") "dirigeant" else "salarie")
        val net = a.dbl("net_mensuel")
        val brut = a.dbl("brut_mensuel")
        if (net != null) {
            val brutCalcule = Paie.brutDepuisNet(net, e.statut == "dirigeant", cfgFinal)
            e = e.copy(netMensuelVise = net, brutMensuel = brutCalcule)
        } else if (brut != null) {
            e = e.copy(brutMensuel = brut, netMensuelVise = 0.0)
        }
        a.dbl("heures_hebdo")?.let { e = e.copy(heuresHebdo = it) }
        val pas = a.dbl("taux_pas")
        if (pas != null) e = e.copy(tauxPas = pas)
        if (a.hv("date_entree")) e = e.copy(dateEntree = a.s("date_entree"))
        val fin: Employe = e
        svc.repo.update {
            it.copy(
                paie = cfgFinal,
                employes = if (nouveau) it.employes + fin else it.employes.map { x -> if (x.id == fin.id) fin else x }
            )
        }
        val salaireTxt = if (fin.netMensuelVise > 0) "net visé ${euro(fin.netMensuelVise)}/mois (brut ${euro(fin.brutMensuel)})" else "brut ${euro(fin.brutMensuel)}/mois"
        return ok("✅ ${fin.nom} ${if (nouveau) "ajouté(e)" else "mis(e) à jour"} (${fin.statut}, $salaireTxt, ${nombre(fin.heuresHebdo)} h/semaine, PAS ${nombre(fin.tauxPas)} %).")
    }

    private fun paie(a: JSONObject): ToolResult {
        val n = normaliser(a.s("employe"))
        val e = d.employes.firstOrNull { normaliser(it.nom) == n } ?: d.employes.firstOrNull { normaliser(it.nom).contains(n) }
            ?: return err("Employé introuvable. Le créer avec employe_ecrire. Équipe : ${d.employes.joinToString { it.nom }.ifBlank { "vide" }}")
        val mois = a.s("mois").takeIf { Regex("\\d{4}-\\d{2}").matches(it) } ?: LocalDate.now().toString().take(7)
        val heuresEff = a.dbl("heures_effectuees")
        val netVerse = a.dbl("net_verse")
        if (heuresEff != null && netVerse != null) return err("Renseigne soit heures_effectuees, soit net_verse, pas les deux à la fois.")
        val (md, loc) = svc.bulletin(e, mois, a.dbl("primes") ?: 0.0, heuresEff, netVerse)
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
