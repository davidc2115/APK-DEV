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
            "document_creer", "document_action", "document_email", "societe_liste", "employe_ecrire", "paie", "reserve_bilan", "societe_resultat", "societe_baremes",
            "presentation_modifier", "distance_deplacement", "clients_exporter_kml", "kml_couleurs_set", "clients_gps", "intervention_enregistrer", "intervention_modifier", "intervention_supprimer", "interventions_liste", "interventions_synthese",
            "installation_supprimer"
        )
        private val KEYWORDS = Regex(
            "devis|factur|client|soci[ée]t[ée]|tarif|remise|d[ée]placement|paie|salaire|bulletin|r[ée]serve|dividende|b[ée]n[ée]fice|r[ée]sultat|tr[ée]sorerie|charges fixes|imp[ôo]t sur les soci[ée]t[ée]s|capital social|bar[èe]mes?|urssaf|cotisations|s[ée]curit[ée] sociale|smic|plafond|" +
                "installation|photovolta|kwc|fiche|pr[ée]sentation|mise en forme|mise en page|si[èe]ge|employ[ée]|prix|onduleur|maintenance|" +
                "entretien|intervention|historique photovolta[ïi]que|bilan maintenance|panne|diagnostic|pi[èe]ce remplac[ée]e|planning|agenda|pdf|obsidian|distance|itin[ée]raire|km|fid[ée]lit[ée]|kml|google earth|carte des clients|" +
                "gps|coordonn[ée]es|g[ée]oloc|localis|latitude|longitude|google maps",
            RegexOption.IGNORE_CASE
        )
        fun concerned(text: String) = KEYWORDS.containsMatchIn(text)

        val PROMPT = """
Module SOCIÉTÉ (maintenance et entretien d'installations photovoltaïques). Tu disposes d'outils pour : enregistrer les informations de l'entreprise (siège, SIRET, IBAN, TVA…), les tarifs par puissance (kWc), les règles de remise (fidélité, nombre d'installations) et de déplacement, les clients et leurs installations, créer des devis et factures, convertir un devis en facture, marquer une facture payée (mise en réserve), produire des bulletins de paie, le bilan de la réserve, et modifier la présentation des documents.
- CRÉATION / MODIFICATION D'UN CLIENT : utilise TOUJOURS l'outil natif client_ecrire en premier pour enregistrer le client dans la base Société de JARVIS. N'utilise JAMAIS une écriture Markdown, obsidian_fichiers, stockage_telephone ou un autre outil de fichiers pour simuler la création d'un client. client_ecrire enregistre la fiche dans Société → Clients et exporte ensuite sa copie Markdown dans « Société/Clients ». N'appelle client_fiche qu'après la confirmation de client_ecrire, uniquement pour afficher/régénérer la fiche. Si client_ecrire renvoie une erreur, arrête-toi, explique l'erreur et ne crée aucun fichier de remplacement. N'annonce jamais la création si l'outil ne l'a pas confirmée.
- Quand l'utilisateur donne un tarif, une règle ou une information sur la société, enregistre-la immédiatement avec l'outil adapté : c'est ainsi que tu la gardes en mémoire.
- Ne calcule jamais un montant toi-même : les outils font tous les calculs (tarif par puissance, remises, déplacement, TVA).
- Quand l’utilisateur demande de supprimer une fiche client, utilise client_supprimer (jamais client_ecrire). Ne dis que la fiche est supprimée que si l’outil confirme sa disparition. Si des devis/factures y sont liés, explique que la suppression est bloquée pour préserver l’historique.
- RÈGLE DE SÉCURITÉ : une demande de suppression d’intervention ne concerne jamais la fiche client. Utilise uniquement interventions_liste puis intervention_supprimer avec l’ID exact et confirmation explicite. N’appelle jamais client_supprimer et ne propose jamais la suppression du client comme solution de remplacement. Si l’outil d’intervention est indisponible, arrête-toi sans modifier le client ni son historique.
- Si l’utilisateur demande d’analyser son planning pour ajouter les rendez-vous aux fiches clients, utilise TOUJOURS analyser_planning_clients (et pas seulement get_planning). Cet outil traite chaque client séparément, même lorsqu’un rendez-vous en mentionne plusieurs, extrait les coordonnées et les caractéristiques photovoltaïques explicitement présentes, puis demande confirmation avant l’enregistrement. Ne crée pas de nouveau client à partir d’un nom incertain.
- Quand l’utilisateur demande de créer, mettre à jour ou régénérer la carte KML Google Earth, appelle clients_exporter_kml avec recherche VIDE par défaut : l'outil inclut toute la base Société persistée, les anciens ET nouveaux clients, et toutes les installations avec GPS exploitable. Ne passe JAMAIS la demande complète ni une longue phrase dans recherche. Renseigne recherche uniquement si l'utilisateur demande explicitement une carte limitée à un client ou une installation nommés. Si plusieurs clients sont cités, laisse recherche vide pour produire la carte complète. Vérifie le nombre de clients parcourus, de repères exportés et d'installations sans GPS dans le résultat. Le fichier contient une fiche HTML illustrée, des repères colorés par catégorie et des champs de recherche par client, type et adresse.\n- Si l’utilisateur demande de changer les couleurs des clients sur Google Earth, utilise kml_couleurs_set pour enregistrer les couleurs puis régénère le KML avec clients_exporter_kml.
- DEMANDE AVEC PLUSIEURS INSTALLATIONS / POINTS GPS : traite chaque installation comme une entrée distincte. Pour N coordonnées GPS distinctes, appelle client_ecrire N fois, une fois par installation, en réutilisant exactement le nom du client et en donnant un site_nom distinct et explicite à chaque appel. Ne fusionne jamais plusieurs coordonnées dans un seul champ site_gps et ne t’arrête pas au premier point. Attends la confirmation de chaque écriture avant de passer à la suivante. Après toutes les confirmations, appelle clients_exporter_kml avec recherche vide et vérifie le nombre de repères exportés.
- Si l’utilisateur donne des coordonnées GPS pour une installation (copiées de Google Maps « 47.541558, 4.459845 », en degrés-minutes-secondes 47°32'29.6"N 4°27'35.4"E, ou un lien Google Maps), passe-les TELLES QUELLES dans site_gps de client_ecrire (avec site_nom si le client a plusieurs installations) ; pour le siège, dans gps de societe_info_set. Ces coordonnées priment sur l’adresse et ne sont jamais recalculées.
- Si l’utilisateur est sur place et demande d’enregistrer « ma position », « ma localisation exacte », « ici »… sur une fiche client, appelle client_ecrire avec site_gps = « ma position » ET site_adresse = « ma position » (l’outil relève le GPS du téléphone et l’adresse précise correspondante) ; ne demande pas l’adresse. S’il parle de toutes ses installations (« ses deux installations »), mets site_nom = « toutes ».
- Pour voir ou améliorer la précision des positions GPS des clients, utilise clients_gps (relocaliser=true pour recalculer avec la Base Adresse Nationale). N’invente jamais de coordonnées.
- Pour chaque intervention photovoltaïque réellement réalisée ET lorsque l'utilisateur demande explicitement de l'enregistrer, utilise intervention_enregistrer. Ne l'utilise jamais pour répondre à une demande de suppression, correction ou consultation. N'invente jamais une intervention de test, un intitulé « test », ni une intervention de remplacement pour simuler une suppression.
- Pour supprimer une intervention : utilise impérativement l'outil MCP interventions_liste (nom exact, sans préfixe) pour lire les interventions depuis la base Société de Jarvis, et non Obsidian/Bibliothèque. Lis les ID, présente la date, la catégorie, le résumé et l'ID de la fiche ciblée, puis demande une confirmation explicite à l'utilisateur. Après son « oui » explicite, appelle intervention_supprimer avec client, installation, intervention_id exact et confirmation="oui". Si l'ID manque, si plusieurs fiches correspondent ou si l'utilisateur n'a pas confirmé, arrête-toi sans rien créer ni supprimer. N'appelle jamais intervention_enregistrer en secours d'une suppression.
- Les photos avant/après sont ajoutées depuis l'onglet Société > Interventions, avec les boutons « Ajouter avant » et « Ajouter après ». Après avoir enregistré l'intervention, indique à l'utilisateur où joindre les photos si elles sont disponibles ; ne prétends jamais qu'une photo a été enregistrée si l'interface ne l'a pas confirmé. Les images sont conservées dans le stockage privé et comme pièces jointes Obsidian.
- Pour consulter l'historique en vue d'une suppression, appelle interventions_liste sans installation : l'outil liste toutes les installations du client, chaque intervention et son ID exact. Présente ensuite les fiches avec leur installation et demande à l'utilisateur de choisir si sa demande est ambiguë. Pour enregistrer ou modifier une intervention, demande l'installation exacte si le client en possède plusieurs. N'utilise jamais une correspondance partielle ambiguë.
- Pour corriger ou compléter une intervention existante, utilise intervention_modifier avec le client, l’installation et l’identifiant retourné par interventions_liste. Les champs omis restent inchangés ; « effacer » vide explicitement un champ. La fiche Jarvis, la note Obsidian et le PDF sont actualisés ensemble. Ne crée pas une nouvelle intervention pour une simple correction.
- Pour supprimer une intervention en double ou erronée, consulte d’abord interventions_liste, identifie exactement la bonne fiche par son ID et utilise intervention_supprimer. L’outil demande une confirmation explicite avant suppression et retire la fiche de Jarvis, le rapport Obsidian/PDF et les photos associées si elles sont accessibles. Ne supprime jamais une intervention sur la seule base d’une date ou d’un intitulé ambigu.
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
        tool("clients_exporter_kml", "Génère ou met à jour la carte KML Google Earth depuis toute la base Société persistée : anciens et nouveaux clients et toutes leurs installations GPS. Laisse recherche vide pour la carte complète. Ne renseigne recherche QUE si l'utilisateur demande explicitement une carte filtrée sur un client ou une installation nommés ; ne transmets JAMAIS la consigne complète ni une longue phrase dans recherche.", mapOf("recherche" to prop("OPTIONNEL. Vide par défaut pour exporter TOUS les clients. Renseigner uniquement un nom précis de client/installation si l'utilisateur demande explicitement un filtre."))),
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
            "part_dirigeant_pct" to prop("Part de la réserve attribuée au dirigeant en fin d'année en % (le reste aux salariés)."),
            "charges_mensuelles" to prop("Charges fixes mensuelles hors salaires en € (loyer, assurance, comptable, véhicule…), pour le calcul du résultat."),
            "capital_social" to prop("Capital social en € (plafond de la réserve légale : 10 % du capital)."),
            "reserve_legale_cumulee" to prop("Réserve légale déjà constituée les années précédentes, en €."),
            "distribution_pct" to prop("Part du bénéfice distribuable versée en dividende en fin d'année, en % (le reste reste dans la société)."),
            "impot_societes" to prop("« true » si la société est à l'impôt sur les sociétés, « false » si elle est à l'impôt sur le revenu (déduit de la forme sinon)."),
            "is_taux_reduit" to prop("Taux réduit d'IS en % (15 par défaut)."), "is_plafond_reduit" to prop("Plafond de bénéfice du taux réduit en € (42500 par défaut)."),
            "is_taux_normal" to prop("Taux normal d'IS en % (25 par défaut)."), "pfu_pct" to prop("Prélèvement forfaitaire unique sur les dividendes en % (31,4 en 2026).")
        )),
        tool("societe_resultat", "Résultat de la société (recalculé automatiquement à chaque facture payée) : chiffre d'affaires encaissé HT, salaires versés (coût employeur, bulletins ou estimation), charges fixes, ce qu'il reste à la société avant impôt, impôt sur les sociétés, bénéfice net, réserve légale, dividende de fin d'année possible (brut et net après prélèvement forfaitaire), et projection au 31 décembre.",
            mapOf("annee" to prop("Année (défaut : l'année en cours)."))),
        tool("societe_baremes", "Barèmes officiels relevés automatiquement chaque semaine auprès de l'URSSAF (plafond de la Sécurité sociale, SMIC, taux et plafond de l'impôt sur les sociétés, prélèvement forfaitaire sur les dividendes, coût officiel de chaque salarié) et appliqués au calcul du résultat, du dividende et des fiches de paie. action=voir (défaut) | actualiser (vérifie tout de suite) | auto_oui / auto_non (application automatique).",
            mapOf("action" to prop("voir | actualiser | auto_oui | auto_non"))),
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
        tool("client_ecrire", "OUTIL OBLIGATOIRE pour toute création/modification de client : enregistre et vérifie la fiche dans la base interne Société → Clients, puis exporte sa copie Markdown dans Société/Clients. Ne jamais remplacer cet appel par une création de fichier Obsidian.", mapOf(
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
        tool("client_fiche", "Affiche et réexporte la fiche Markdown d'un client DÉJÀ présent dans la base interne Société. Ne crée jamais un client et ne remplace jamais client_ecrire.", mapOf("nom" to prop("Nom du client.")), listOf("nom")),
        tool("intervention_enregistrer", "Enregistre une intervention photovoltaïque réellement effectuée dans l'historique du site et dans Obsidian (note + rapport PDF). RÈGLE ABSOLUE : le rapport ne contient QUE ce que l'utilisateur a dit, avec ses mots (reformulés proprement au besoin). N'ajoute JAMAIS d'action, de mesure, de valeur (température, tension, courant…), de constat, d'état (« parfait état », « conforme »), de recommandation ni de date qu'il n'a pas donnés ; un champ non dicté reste vide. Pas de commentaire sur le rapport lui-même. Les éléments non dits sont retirés automatiquement et signalés.", mapOf(
            "client" to prop("Nom exact du client."), "installation" to prop("Nom exact de l'installation si plusieurs."),
            "date" to prop("Date de l'intervention au format yyyy-MM-dd (défaut : aujourd'hui)."),
            "categorie" to prop("maintenance | panne | diagnostic | nettoyage | remplacement | contrôle | autre."),
            "description" to prop("Résumé de ce que l'utilisateur a dit de l'intervention (une ou deux phrases, sans rien ajouter)."),
            "diagnostic" to prop("Constat donné par l'utilisateur, seulement s'il en a donné un ; sinon vide."),
            "actions" to prop("Actions dites par l'utilisateur, UNE PAR LIGNE (elles deviennent une liste ✅). Aucune action supposée."),
            "pieces" to prop("Pièces remplacées dites par l'utilisateur, une par ligne (références et quantités s'il les donne)."),
            "mesures" to prop("Mesures dites par l'utilisateur, recopiées telles quelles avec unités, une par ligne (ex. « 20 séries à environ 500 V », « série 7 : 320 V », « isolement 20 MΩ »). Les séries deviennent automatiquement un tableau Série 1…N. Aucune valeur supposée."),
            "statut" to prop("terminee | a_suivre | en_attente."),
            "prochaine_maintenance" to prop("Date yyyy-MM-dd UNIQUEMENT si l'utilisateur l'a demandée."), "notes" to prop("Remarques dites par l'utilisateur, sinon vide.")
        ), listOf("client", "description")),
        tool("intervention_supprimer", "Supprime une intervention précise de l'historique Jarvis et nettoie son rapport Obsidian/PDF et ses photos. À appeler uniquement après interventions_liste, avec l'ID exact et après un « oui » explicite de l'utilisateur. Ne crée jamais de nouvelle intervention.", mapOf(
            "client" to prop("Nom exact du client."),
            "installation" to prop("Nom exact de l'installation."),
            "intervention_id" to prop("Identifiant exact de la fiche à supprimer, affiché par interventions_liste."),
            "confirmation" to prop("Doit être exactement « oui » uniquement après confirmation explicite de l'utilisateur.")
        ), listOf("client", "installation", "intervention_id", "confirmation")),
        tool("interventions_liste", "Consulte l'historique des interventions photovoltaïques et renvoie l'identifiant exact de chaque fiche. Si installation est vide, liste toutes les installations du client avec leurs interventions.", mapOf(
            "client" to prop("Nom exact du client."), "installation" to prop("Nom de l'installation si le client en possède plusieurs.")
        ), listOf("client")),
        tool("intervention_modifier", "Modifie le CONTENU d'un rapport d'intervention existant (description, actions, mesures, diagnostic…) et régénère sa note Obsidian et son PDF sans supprimer les photos. Les champs omis restent inchangés ; « effacer » vide explicitement un champ ; pour retirer une ligne (ex. une mesure), renvoie le champ complet SANS cette ligne (jamais de remarque « retiré du rapport »). Pour la MISE EN PAGE des rapports (ordre, titres, emojis, tableaux), utilise presentation_modifier avec cible « rapport ». RÈGLE ABSOLUE : le rapport ne contient QUE ce que l'utilisateur a dit, avec ses mots (reformulés proprement au besoin). N'ajoute JAMAIS d'action, de mesure, de valeur (température, tension, courant…), de constat, d'état (« parfait état », « conforme »), de recommandation ni de date qu'il n'a pas donnés ; un champ non dicté reste vide. Pas de commentaire sur le rapport lui-même. Les éléments non dits sont retirés automatiquement et signalés.", mapOf(
            "client" to prop("Nom exact du client."),
            "installation" to prop("Nom exact de l'installation."),
            "intervention_id" to prop("Identifiant exact obtenu via interventions_liste."),
            "date" to prop("Nouvelle date yyyy-MM-dd (optionnel)."),
            "categorie" to prop("Nouvelle catégorie (optionnel)."),
            "description" to prop("Nouveau résumé factuel (optionnel)."),
            "diagnostic" to prop("Diagnostic constaté ou « effacer » (optionnel)."),
            "actions" to prop("Actions réalisées, une par ligne, ou « effacer » (optionnel)."),
            "pieces" to prop("Pièces remplacées ou « effacer » (optionnel)."),
            "mesures" to prop("Mesures relevées avec unités, une par ligne (« 20 séries à environ 500 V » → tableau des séries), ou « effacer » (optionnel)."),
            "statut" to prop("terminee | a_suivre | en_attente (optionnel)."),
            "prochaine_maintenance" to prop("Date yyyy-MM-dd ou « effacer » (optionnel)."),
            "notes" to prop("Notes ou « effacer » (optionnel).")
        ), listOf("client", "installation", "intervention_id")),
        tool("interventions_synthese", "Produit un bilan intelligent de l'historique photovoltaïque : volume, dernières interventions, catégories, actions en attente et prochaines maintenances. N'infère aucune panne ni mesure absente.", mapOf(
            "client" to prop("Nom du client exact ou partiel ; vide = tous les clients."),
            "installation" to prop("Nom exact ou partiel de l'installation (optionnel).")
        )),
        tool("installation_supprimer", "Supprime UNE installation (site photovoltaïque) d'un client, avec son historique d'interventions et leurs rapports, en CONSERVANT la fiche client et ses autres installations. C'est l'outil à utiliser pour « supprime l'installation X (du client Y) » — jamais client_supprimer. Confirmation demandée.", mapOf(
            "client" to prop("Nom du client."),
            "installation" to prop("Nom (ou adresse) de l'installation à supprimer.")
        ), listOf("client", "installation")),
        tool("client_supprimer", "Supprime définitivement TOUTE une fiche client (pour retirer une seule installation, utilise installation_supprimer) de la base Société après confirmation. Refuse la suppression si des devis ou factures sont encore liés afin de préserver l’historique comptable.", mapOf("nom" to prop("Nom exact du client à supprimer.")), listOf("nom")),
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
        tool("document_email", "Envoie (ou renvoie) un devis ou une facture par email, avec le PDF du document en pièce jointe (compte Gmail connecté requis, Réglages › Comptes › Mails). Demande toujours confirmation avant l'envoi.", mapOf(
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
        tool("presentation_modifier", "Modifie la présentation d'un modèle : fiche client, devis, facture, bulletin de paie, planning ou RAPPORT D'INTERVENTION (titres, ordre des sections, emojis, tableaux…). Pour « rapport », les rapports récents sont régénérés avec la nouvelle présentation. Peut aussi annuler la dernière modification ou revenir au modèle d'origine.", mapOf(
            "cible" to prop("client | devis | facture | paie | planning | rapport"),
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
        "intervention_enregistrer" -> interventionEnregistrer(a)
        "intervention_modifier" -> interventionModifier(a)
        "intervention_supprimer" -> interventionSupprimer(a)
        "interventions_liste" -> interventionsListe(a)
        "interventions_synthese" -> interventionsSynthese(a)
        "client_supprimer" -> clientSupprimer(a)
        "installation_supprimer" -> installationSupprimer(a)
        "document_creer" -> documentCreer(a)
        "document_action" -> documentAction(a)
        "document_email" -> documentEmail(a)
        "societe_liste" -> liste(a)
        "employe_ecrire" -> employeEcrire(a)
        "paie" -> paie(a)
        "reserve_bilan" -> ok(svc.bilanTexte(a.s("annee").toIntOrNull() ?: LocalDate.now().year))
        "societe_resultat" -> ok(svc.resultatTexte(a.s("annee").toIntOrNull() ?: LocalDate.now().year))
        "societe_baremes" -> when (a.s("action").lowercase()) {
            "actualiser", "mettre_a_jour", "maj" -> ok(svc.actualiserBaremes())
            "auto_oui", "auto_non" -> {
                val auto = a.s("action").lowercase() == "auto_oui"
                svc.repo.update { it.copy(societe = it.societe.copy(baremesAuto = auto)) }
                if (auto) ok(svc.actualiserBaremes()) else ok("✅ Application automatique des barèmes désactivée : les valeurs actuelles restent figées (la vérification hebdomadaire continue de les relever).")
            }
            else -> ok(svc.baremesTexte())
        }
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
        a.dbl("charges_mensuelles")?.let { s = s.copy(chargesMensuelles = it.coerceAtLeast(0.0)) }
        a.dbl("capital_social")?.let { s = s.copy(capitalSocial = it.coerceAtLeast(0.0)) }
        a.dbl("reserve_legale_cumulee")?.let { s = s.copy(reserveLegaleCumulee = it.coerceAtLeast(0.0)) }
        a.dbl("distribution_pct")?.let { s = s.copy(distributionPct = it.coerceIn(0.0, 100.0)) }
        a.bool("impot_societes")?.let { s = s.copy(soumisIs = it) }
        a.dbl("is_taux_reduit")?.let { s = s.copy(isTauxReduit = it.coerceIn(0.0, 100.0)) }
        a.dbl("is_plafond_reduit")?.let { s = s.copy(isPlafondReduit = it.coerceAtLeast(0.0)) }
        a.dbl("is_taux_normal")?.let { s = s.copy(isTauxNormal = it.coerceIn(0.0, 100.0)) }
        a.dbl("pfu_pct")?.let { s = s.copy(pfuPct = it.coerceIn(0.0, 100.0)) }
        if (s == before) return err("Aucune information reconnue à enregistrer.")
        val siegeDeplace = before.adresse != s.adresse || before.lat != s.lat || before.lon != s.lon
        svc.repo.update { current ->
            current.copy(
                societe = s,
                clients = if (!siegeDeplace) current.clients else current.clients.map { client ->
                    client.copy(
                        distanceKm = null, distanceDureeMin = null, distanceCle = "",
                        sites = client.sites.map { site -> site.copy(distanceKm = null, dureeMin = null, distanceCle = "") }
                    )
                }
            )
        }
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
            append("\n## 📊 Résultat et dividende (calcul automatique à chaque facture payée)\n")
            append("- Charges fixes : ${if (s.chargesMensuelles > 0) euro(s.chargesMensuelles) + " / mois" else "non renseignées"} · capital social : ${if (s.capitalSocial > 0) euro(s.capitalSocial) else "non renseigné"}\n")
            append("- ${if (fr.dd.jarvis.societe.core.Resultat.soumisIs(s)) "Impôt sur les sociétés : ${nombre(s.isTauxReduit)} % jusqu'à ${euro(s.isPlafondReduit)}, ${nombre(s.isTauxNormal)} % au-delà" else "Impôt sur le revenu (pas d'IS ni de dividende)"}" +
                " · dividende : ${nombre(s.distributionPct)} % du distribuable · prélèvement forfaitaire ${nombre(s.pfuPct)} %\n")
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

    /** Retire une installation d'un client (et son historique), la fiche client et les autres installations restent. */
    private suspend fun installationSupprimer(a: JSONObject): ToolResult {
        val (client, erreur) = clientParNom(a.s("client").ifBlank { a.s("nom") })
        if (client == null) return err(erreur!!)
        val ref = a.s("installation").ifBlank { a.s("site_nom") }.trim()
        if (client.sites.isEmpty()) return err("${client.nom} n'a aucune installation enregistrée.")
        val site = (if (ref.isBlank()) null else siteDe(client, ref))
            ?: return err("Installation « $ref » introuvable ou ambiguë pour ${client.nom}. Installations : " +
                client.sites.joinToString { it.nom.ifBlank { it.adresse.ifBlank { it.id } } } + ".")
        val nomSite = site.nom.ifBlank { site.adresse.ifBlank { site.id } }
        val documents = svc.repo.refresh().docs.filter { it.clientId == client.id && it.siteId == site.id }
        val question = "Supprimer l'installation « $nomSite »" + (if (site.adresse.isNotBlank() && site.adresse != nomSite) " (${site.adresse})" else "") +
            " du client « ${client.nom} » ?" +
            (if (site.interventions.isNotEmpty()) " Ses ${site.interventions.size} intervention(s) et leurs rapports seront aussi supprimés." else "") +
            (if (documents.isNotEmpty()) " ${documents.size} devis/facture(s) la mentionnent : ils sont conservés." else "") +
            " La fiche client et ses autres installations sont conservées."
        if (!confirm(question, true)) return err("Suppression annulée : l'installation « $nomSite » est conservée.")
        // Historique de l'installation : rapports Obsidian/PDF et photos nettoyés par la suppression de chaque intervention.
        val nettoyage = site.interventions.map { runCatching { svc.supprimerIntervention(client.id, site.id, it.id) }.getOrDefault("") }
        try {
            svc.repo.update { data -> data.copy(clients = data.clients.map { c -> if (c.id != client.id) c else c.copy(sites = c.sites.filterNot { it.id == site.id }) }) }
        } catch (e: Exception) {
            return err("❌ L'installation « $nomSite » n'a pas pu être supprimée : ${e.message ?: e.javaClass.simpleName}. Rien n'est annoncé comme supprimé.")
        }
        val relu = svc.repo.refresh().client(client.id)
        if (relu == null || relu.sites.any { it.id == site.id }) return err("❌ L'installation « $nomSite » est toujours présente après la tentative de suppression.")
        val fiche = runCatching { svc.exporterClient(relu).second }.getOrNull()
        runCatching { svc.exporterKml() }
        val restantes = relu.sites.joinToString { it.nom.ifBlank { it.adresse } }.ifBlank { "aucune" }
        return ok("🗑️ Installation « $nomSite » supprimée de la fiche ${client.nom}" +
            (if (site.interventions.isNotEmpty()) " (${site.interventions.size} intervention(s) et leurs rapports supprimés${if (nettoyage.any { it.startsWith("❌") || it.startsWith("Échec") }) ", certains fichiers restent à vérifier" else ""})" else "") +
            ".\nInstallations restantes : $restantes." + (fiche?.let { "\nFiche mise à jour : $it" } ?: ""))
    }

    private suspend fun clientSupprimer(a: JSONObject): ToolResult {
        val nom = a.s("nom")
        if (nom.isBlank()) return err("Nom du client manquant.")
        // Hermès peut appeler cet outil après une création faite en arrière-plan :
        // ne jamais chercher dans l'instantané potentiellement périmé du service.
        val donnees = svc.repo.refresh()
        val trouves = donnees.chercherClients(nom)
        val exact = donnees.clients.filter { normaliser(it.nom) == normaliser(nom) }
        val cible = when {
            exact.size == 1 -> exact.first()
            exact.size > 1 -> return err("Plusieurs fiches portent ce nom. Précise un autre identifiant.")
            trouves.size == 1 -> trouves.first()
            trouves.size > 1 -> return err("Plusieurs clients correspondent : ${trouves.joinToString { it.nom }}. Précise le nom exact.")
            else -> return err("Aucune fiche client trouvée pour « $nom ».")
        }
        val documents = donnees.docs.filter { it.clientId == cible.id }
        if (documents.isNotEmpty()) {
            val liste = documents.take(8).joinToString(", ") { it.numero }
            return err("La fiche « ${cible.nom} » est liée à ${documents.size} devis/facture(s) ($liste${if (documents.size > 8) "…" else ""}). Je bloque la suppression pour ne pas casser l’historique comptable. Il faut d’abord transférer ou traiter ces documents.")
        }
        if (!confirm("Supprimer définitivement la fiche client « ${cible.nom} » et ses ${cible.sites.size} installation(s) de la base Société ? Cette action est irréversible.", true))
            return err("Suppression refusée. La fiche « ${cible.nom} » est conservée.")
        svc.repo.update { data -> data.copy(clients = data.clients.filterNot { it.id == cible.id }) }
        val removed = svc.repo.refresh().clients.none { it.id == cible.id }
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
        val baseInitiale = runCatching { svc.repo.refresh() }.getOrElse {
            return err("❌ Impossible de relire la base Société avant la création ou la modification : ${it.message ?: it.javaClass.simpleName}")
        }
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
            a.put("_gps_sur_place", true)
            if (adresseIci) { if (adr != null) a.put("site_adresse", adr.libelle) else a.remove("site_adresse") }
            infoPos = "\n📍 Position du téléphone : ${Geo.texteGps(p)} (± ${precision.toInt()} m)" +
                (adr?.let { "\n🏠 Adresse à cet endroit : ${it.libelle}" + (it.distance?.takeIf { d -> d > 15 }?.let { d -> " (point d'adresse le plus proche, ~$d m)" } ?: "") }
                    ?: "\n🏠 Aucune adresse postale trouvée à cet endroit : seules les coordonnées GPS sont enregistrées.")
        }
        if (a.hv("site_nom") && estToutes(a.s("site_nom"))) {
            val c = baseInitiale.chercherClients(a.s("nom")).let { l -> l.firstOrNull { normaliser(it.nom) == normaliser(a.s("nom")) } ?: l.singleOrNull() }
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
        val nom = a.s("nom").trim()
        if (nom.isBlank()) return err("Le nom du client est obligatoire : aucun enregistrement ni fichier n'a été créé.")
        // Toujours repartir de la base disque : l'agent ou l'écran peut avoir enregistré
        // un client depuis le dernier chargement de ce module.
        val donnees = runCatching { svc.repo.refresh() }.getOrElse {
            return err("❌ Impossible de relire la base Société avant l'enregistrement : ${it.message ?: it.javaClass.simpleName}")
        }
        val trouves = donnees.chercherClients(nom)
        val exacts = donnees.clients.filter { normaliser(it.nom) == normaliser(nom) }
        if (exacts.size > 1) return err("Plusieurs fiches portent exactement le nom « $nom ». Aucune n'a été modifiée ; corrige les doublons dans Société → Clients.")
        val existant = exacts.singleOrNull()
        // Ne jamais transformer une simple correspondance partielle en modification d'un autre client.
        if (existant == null && trouves.isNotEmpty())
            return err("Aucun client ne porte exactement le nom « $nom ». J'ai trouvé : ${trouves.joinToString { it.nom }}. Confirme le nom exact avant de créer une nouvelle fiche.")
        val nouveau = existant == null
        var c: Client = existant ?: Client(svc.newId(), nom, depuis = LocalDate.now().toString())
        if (a.hv("type")) c = c.copy(type = if (normaliser(a.s("type")).startsWith("pro")) "professionnel" else "particulier")
        if (a.hv("contact")) c = c.copy(contact = a.s("contact"))
        if (a.hv("tel")) c = c.copy(tel = a.s("tel"))
        if (a.hv("email")) c = c.copy(email = a.s("email"))
        if (a.hv("adresse") && normaliser(a.s("adresse")) != normaliser(c.adresse)) c = c.copy(
            adresse = a.s("adresse"), distanceKm = null, distanceDureeMin = null, distanceCle = ""
        )
        if (a.hv("depuis")) {
            val dep = parseDepuis(a.s("depuis"))
            if (dep.isNotBlank()) c = c.copy(depuis = dep)
        }
        if (a.hv("notes")) c = c.copy(notes = a.s("notes"))
        var siteTxt = ""
        val gpsSaisi = if (a.hv("site_gps")) lireGps(a.s("site_gps")) ?: return err("Coordonnées GPS illisibles : « ${a.s("site_gps")} ». $aideGps") else null
        if (a.hv("site_nom") || a.hv("site_adresse") || a.hv("kwc") || a.hv("onduleur") || a.hv("nb_panneaux") || a.hv("mise_en_service") || a.hv("site_notes") || gpsSaisi != null) {
            val ref = a.s("site_nom").ifBlank { a.s("site_adresse") }
            val gpsSurPlace = a.optBoolean("_gps_sur_place")
            if (gpsSurPlace && a.s("site_nom").isBlank() && c.sites.size > 1)
                return err("${c.nom} a plusieurs installations (${c.sites.joinToString { it.nom }}). Pour enregistrer ce point GPS, précise l'installation concernée (site_nom), ou dis « toutes » si tu es sur place pour chacune.")
            if (gpsSaisi != null && ref.isBlank() && c.sites.size > 1)
                return err("${c.nom} a plusieurs installations (${c.sites.joinToString { it.nom }}). Pour laquelle sont ces coordonnées GPS ? (site_nom, ou « toutes » pour toutes ses installations)")
            val trouve = c.sites.firstOrNull { x -> ref.isNotBlank() && (normaliser(x.nom) == normaliser(ref) || normaliser(x.adresse) == normaliser(ref)) }
                ?: (if (c.sites.size == 1 && (ref.isBlank() || (gpsSurPlace && a.s("site_nom").isBlank()))) c.sites.first() else null)
            val isNew = trouve == null
            var site: Site = trouve ?: Site(svc.newId(), a.s("site_nom").ifBlank { "Installation ${c.sites.size + 1}" }, a.s("site_adresse").ifBlank { c.adresse })
            if (a.hv("site_nom")) site = site.copy(nom = a.s("site_nom"))
            // Nouvelle adresse : l'ancienne position GPS ne vaut plus (elle sera recalculée), sauf coordonnées fournies.
            var distanceClientAInvalider = false
            if (a.hv("site_adresse") && normaliser(a.s("site_adresse")) != normaliser(site.adresse)) {
                distanceClientAInvalider = true
                site = site.copy(adresse = a.s("site_adresse"), distanceKm = null, distanceCle = "", lat = null, lon = null, gpsPrecision = "", gpsTrouve = "")
            }
            if (gpsSaisi != null) {
                distanceClientAInvalider = true
                site = site.copy(
                    lat = gpsSaisi.lat, lon = gpsSaisi.lon, gpsPrecision = Geo.Precision.MANUELLE.name, gpsTrouve = a.s("_gps_source").ifBlank { "coordonnées GPS saisies" },
                    distanceKm = null, dureeMin = null, distanceCle = ""
                )
            }
            val kwc = a.dbl("kwc")
            if (kwc != null) site = site.copy(kwc = kwc)
            if (a.hv("mise_en_service")) site = site.copy(miseEnService = a.s("mise_en_service"))
            if (a.hv("onduleur")) site = site.copy(onduleur = a.s("onduleur"))
            val nbp = a.s("nb_panneaux").toIntOrNull()
            if (nbp != null) site = site.copy(nbPanneaux = nbp)
            if (a.hv("site_notes")) site = site.copy(notes = a.s("site_notes"))
            val fin: Site = site
            c = c.copy(sites = if (isNew) c.sites + fin else c.sites.map { if (it.id == fin.id) fin else it })
            if (distanceClientAInvalider) c = c.copy(distanceKm = null, distanceDureeMin = null, distanceCle = "")
            siteTxt = "\n☀️ Installation : ${fin.nom} — ${nombre(fin.kwc)} kWc — ${fin.adresse}" +
                (if (gpsSaisi != null) "\n🛰️ GPS : ${Geo.texteGps(gpsSaisi)} (${a.s("_gps_source").ifBlank { "coordonnées saisies" }}, format Google Maps) — ${Geo.texteDms(gpsSaisi)}" else "")
        }
        val final: Client = c
        var idEnregistre = final.id
        try {
            svc.repo.update { current ->
                // Upsert atomique : gère aussi deux créations simultanées du même nom sans perdre la fiche.
                val index = current.clients.indexOfFirst {
                    it.id == final.id || normaliser(it.nom) == normaliser(final.nom)
                }
                if (index >= 0) {
                    idEnregistre = current.clients[index].id
                    current.copy(clients = current.clients.mapIndexed { i, client ->
                        if (i == index) final.copy(id = idEnregistre) else client
                    })
                } else current.copy(clients = current.clients + final)
            }
        } catch (e: Exception) {
            return err("❌ Impossible d'enregistrer « ${final.nom} » dans la base Société : ${e.message ?: e.javaClass.simpleName}. Aucun fichier de remplacement ne doit être créé.")
        }
        val baseRelue = runCatching { svc.repo.refresh() }.getOrElse {
            return err("❌ L'enregistrement de « ${final.nom} » a été tenté, mais la base Société n'a pas pu être relue pour confirmer le résultat : ${it.message ?: it.javaClass.simpleName}")
        }
        val relu = baseRelue.clients.firstOrNull {
            it.id == idEnregistre && normaliser(it.nom) == normaliser(final.nom)
        } ?: return err("❌ Écriture disque effectuée mais relecture impossible pour « ${final.nom} » (ID $idEnregistre). Le client n'est pas confirmé dans l'onglet Société → Clients.")
        if (gpsSaisi != null && relu.sites.none { it.lat == gpsSaisi.lat && it.lon == gpsSaisi.lon })
            return err("La fiche « ${final.nom} » a été enregistrée, mais le point GPS n'apparaît pas dans les données relues. Je ne confirme pas son ajout.")
        val r = baseRelue.remiseClient(relu)
        val emplacement = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { svc.exporterClient(relu).second }
        }.getOrElse { "⚠️ Client enregistré dans la base, mais export Obsidian impossible : ${it.message ?: it.javaClass.simpleName}" }
        return ok("✅ Client « ${final.nom} » ${if (nouveau) "créé" else "mis à jour"} dans Société → Clients (ID $idEnregistre).$siteTxt\n📁 Fiche : $emplacement\n🎁 Remise applicable aujourd'hui : " + (if (r.pct > 0) "${nombre(r.pct)} % (${r.detail})" else "aucune"))
    }

    private fun clientParNom(nom: String): Pair<Client?, String?> {
        // Les consultations (fiche, interventions, documents) doivent voir les créations
        // réalisées par un autre écran ou par un agent depuis le dernier instantané.
        val base = runCatching { svc.repo.refresh() }.getOrElse {
            return null to "Impossible de relire la base Société : ${it.message ?: it.javaClass.simpleName}"
        }
        val l = base.chercherClients(nom)
        return when {
            l.isEmpty() -> null to "Client « $nom » introuvable. Le créer avec client_ecrire."
            l.size > 1 && l.none { normaliser(it.nom) == normaliser(nom) } -> null to "Plusieurs clients correspondent : ${l.joinToString { it.nom }}. Préciser le nom."
            else -> (l.firstOrNull { normaliser(it.nom) == normaliser(nom) } ?: l.first()) to null
        }
    }

    private fun siteDe(c: Client, ref: String): Site? {
        if (ref.isBlank()) return c.sites.singleOrNull()
        val n = normaliser(ref)
        val exact = c.sites.filter { normaliser(it.nom) == n || normaliser(it.adresse) == n }
        if (exact.size == 1) return exact.first()
        if (exact.size > 1) return null
        val partiels = c.sites.filter { normaliser(it.nom).contains(n) || normaliser(it.adresse).contains(n) }
        return partiels.singleOrNull()
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

    private fun interventionEnregistrer(a: JSONObject): ToolResult {
        val (client, erreur) = clientParNom(a.s("client"))
        if (client == null) return err(erreur!!)
        val site = siteDe(client, a.s("installation"))
            ?: return err(if (client.sites.isEmpty()) "Aucune installation n'est enregistrée. Enregistre d'abord le site avec client_ecrire." else "Plusieurs installations existent pour " + client.nom + " : " + client.sites.joinToString { it.nom } + ". Précise installation.")
        if (a.s("description").isBlank()) return err("La description de l'intervention est obligatoire.")
        // Fidélité : seulement ce que l'utilisateur a dit (rien d'inventé : valeurs, actions, constats).
        val garde = garder(a, client, site, null)
        val description = garde.champs["description"].orEmpty().ifBlank { "Intervention de ${a.s("categorie").ifBlank { "maintenance" }}." }
        // Barrière anti-fausse fiche : Hermes ne doit jamais enregistrer un simple « test » pour simuler une suppression.
        if (description.trim().equals("test", true) || description.trim().matches(Regex("(?i)(test|essai) (de )?(suppression|intervention)"))) {
            return err("Création refusée : une intervention de test ne peut pas être ajoutée à l'historique. Pour supprimer une fiche existante, utilise interventions_liste puis intervention_supprimer avec son ID exact.")
        }
        val date = a.s("date").ifBlank { LocalDate.now().toString() }
        if (runCatching { LocalDate.parse(date) }.isFailure) return err("Date invalide : utilise yyyy-MM-dd.")
        val prochaine = a.s("prochaine_maintenance")
        if (prochaine.isNotBlank() && runCatching { LocalDate.parse(prochaine) }.isFailure) return err("Date de prochaine maintenance invalide : utilise yyyy-MM-dd.")
        val statut = a.s("statut").ifBlank { "terminee" }.lowercase()
        if (statut !in setOf("terminee", "a_suivre", "en_attente")) return err("Statut invalide : terminee, a_suivre ou en_attente.")
        val categorie = a.s("categorie").ifBlank { "maintenance" }
        val descriptionNormalisee = description.trim().replace(Regex("\\s+"), " ").lowercase()
        val doublon = site.interventions.firstOrNull { existante ->
            existante.date == date &&
                existante.categorie.trim().equals(categorie.trim(), true) &&
                existante.description.trim().replace(Regex("\\s+"), " ").lowercase() == descriptionNormalisee &&
                existante.diagnostic.trim().equals(a.s("diagnostic").trim(), true) &&
                existante.actions.trim().equals(a.s("actions").trim(), true) &&
                existante.pieces.trim().equals(a.s("pieces").trim(), true) &&
                existante.mesures.trim().equals(a.s("mesures").trim(), true)
        }
        if (doublon != null) return err("⚠️ Intervention potentiellement en double : la fiche #${doublon.id} du $date (${doublon.categorie}) contient déjà exactement les mêmes éléments pour ${client.nom} — ${site.nom}. Aucune nouvelle fiche créée. Consulte interventions_liste ; modifie ou supprime la fiche existante avec l’outil adapté. Si ce sont deux interventions réellement distinctes, précise leur différence dans la description.")
        val intervention = Intervention(
            id = svc.newId(), date = date, categorie = categorie, description = description,
            diagnostic = garde.champs["diagnostic"].orEmpty(), actions = garde.champs["actions"].orEmpty(), pieces = garde.champs["pieces"].orEmpty(),
            mesures = garde.champs["mesures"].orEmpty(), statut = statut, prochaineMaintenance = prochaine, notes = garde.champs["notes"].orEmpty()
        )
        val siteMaj = site.copy(interventions = (site.interventions + intervention).sortedByDescending { it.date })
        svc.repo.update { data -> data.copy(clients = data.clients.map { c ->
            if (c.id != client.id) c else c.copy(sites = c.sites.map { s -> if (s.id == site.id) siteMaj else s })
        }) }
        val (emplacement, pdf) = svc.ecrireRapport(client, site, intervention)
        return ok("✅ Intervention enregistrée pour " + client.nom + " — " + site.nom + ".\n📅 " + date + " · " + categorie + " · " + statut + "\n📁 " + emplacement + "\n📄 " + pdf +
            (if (prochaine.isNotBlank()) "\n🔧 Prochaine maintenance : " + prochaine else "") +
            "\n\nLe suivi est conservé dans la fiche de l'installation et dans Obsidian." + garde.message())
    }

    /** Résultat du contrôle de fidélité d'un rapport. */
    private class Garde(val champs: Map<String, String>, val retires: List<String>, val verifie: Boolean) {
        fun message(): String = when {
            retires.isNotEmpty() -> "\n\n🛡️ Non repris dans le rapport (tu ne l'as pas dit) :\n" + retires.joinToString("\n") { "• ${it.take(160)}" } +
                "\nSi c'est réellement fait ou mesuré, redis-le-moi et je l'ajoute."
            else -> ""
        }
    }

    /** Ce que l'utilisateur a dit récemment (messages de l'appli, de la voiture) : la seule source admise d'un rapport. */
    private fun paroles(): String {
        val limite = System.currentTimeMillis() - 4 * 3_600_000L
        val msgs = runCatching { app.chat.toList() }.getOrDefault(emptyList()) + fr.dd.jarvis.service.CarBridge.contexte()
        return msgs.filter { it.fromUser && it.ts >= limite }.takeLast(40).joinToString("\n") { it.text }
    }

    /**
     * Filtre les champs texte d'un rapport : une ligne n'est gardée que si ses nombres et l'essentiel de ses mots
     * viennent de ce que l'utilisateur a dit (ou de ce que la fiche / le rapport contenaient déjà). Si la demande vient
     * d'ailleurs (aucune parole correspondante ici, ex. Hermès sur une autre messagerie), rien n'est filtré.
     */
    private fun garder(a: JSONObject, client: Client, site: Site, ancienne: Intervention?): Garde {
        val cles = listOf("description", "diagnostic", "actions", "pieces", "mesures", "notes")
        val champs = cles.filter { a.has(it) }.associateWith { a.s(it) }
        val dits = paroles()
        val connu = listOf(client.nom, site.nom, site.adresse, site.onduleur, site.notes,
            if (site.kwc > 0) site.kwc.toString() else "", if (site.nbPanneaux > 0) site.nbPanneaux.toString() else "",
            a.s("categorie"), ancienne?.let { listOf(it.description, it.diagnostic, it.actions, it.pieces, it.mesures, it.notes).joinToString("\n") }.orEmpty()
        ).joinToString("\n")
        val tout = champs.values.joinToString("\n")
        val nomCite = Fidelite.normaliser(dits).let { d -> listOf(client.nom, site.nom).any { n -> n.length >= 3 && d.contains(Fidelite.normaliser(n).take(8)) } }
        val ici = dits.isNotBlank() && (nomCite || Fidelite.appui(tout, dits) >= 0.15 || Fidelite.nombres(tout).any { it in Fidelite.nombres(dits) })
        if (!ici) return Garde(champs, emptyList(), false)
        val support = dits + "\n" + connu
        val retires = mutableListOf<String>()
        val gardes = champs.mapValues { (_, v) -> Fidelite.verifier(v, support).also { retires += it.retires }.garde }
        return Garde(gardes, retires, true)
    }

    private fun interventionModifier(a: JSONObject): ToolResult {
        val (client, erreur) = clientParNom(a.s("client"))
        if (client == null) return err(erreur!!)
        val site = siteDe(client, a.s("installation"))
            ?: return err("Installation introuvable ou ambiguë pour ${client.nom}.")
        val id = a.s("intervention_id")
        val ancienne = site.interventions.firstOrNull { it.id == id }
            ?: return err("Intervention $id introuvable pour ${client.nom} — ${site.nom}. Consulte d'abord interventions_liste pour obtenir son identifiant.")
        fun valeur(cle: String, precedente: String): String {
            if (!a.has(cle)) return precedente
            val v = a.s(cle)
            return if (v.equals("effacer", true) || v.equals("vider", true) || v.equals("aucun", true)) "" else v.ifBlank { precedente }
        }
        val date = valeur("date", ancienne.date)
        if (runCatching { LocalDate.parse(date) }.isFailure) return err("Date invalide : utilise yyyy-MM-dd.")
        val prochaine = valeur("prochaine_maintenance", ancienne.prochaineMaintenance)
        if (prochaine.isNotBlank() && runCatching { LocalDate.parse(prochaine) }.isFailure)
            return err("Date de prochaine maintenance invalide : utilise yyyy-MM-dd ou « effacer ».")
        val statut = valeur("statut", ancienne.statut).lowercase()
        if (statut !in setOf("terminee", "a_suivre", "en_attente"))
            return err("Statut invalide : terminee, a_suivre ou en_attente.")
        // Fidélité : les nouveaux textes ne gardent que ce que l'utilisateur a dit (ou ce que le rapport contenait déjà).
        val garde = garder(a, client, site, ancienne)
        fun champ(cle: String, precedente: String): String {
            if (!a.has(cle)) return precedente
            val v = a.s(cle)
            if (v.equals("effacer", true) || v.equals("vider", true) || v.equals("aucun", true)) return ""
            return garde.champs[cle].orEmpty().ifBlank { if (cle == "description") precedente else "" }
        }
        val maj = ancienne.copy(
            date = date,
            categorie = valeur("categorie", ancienne.categorie),
            description = champ("description", ancienne.description),
            diagnostic = champ("diagnostic", ancienne.diagnostic),
            actions = champ("actions", ancienne.actions),
            pieces = champ("pieces", ancienne.pieces),
            mesures = champ("mesures", ancienne.mesures),
            statut = statut,
            prochaineMaintenance = prochaine,
            notes = champ("notes", ancienne.notes)
        )
        return ok(svc.modifierIntervention(client.id, site.id, id, maj) + garde.message())
    }

    private suspend fun interventionSupprimer(a: JSONObject): ToolResult {
        val (client, erreur) = clientParNom(a.s("client"))
        if (client == null) return err(erreur!!)
        val site = siteDe(client, a.s("installation")) ?: return err("Installation introuvable ou ambiguë pour ${client.nom}.")
        val id = a.s("intervention_id")
        val cible = site.interventions.firstOrNull { it.id == id }
            ?: return err("Intervention $id introuvable. Consulte interventions_liste et reprends son identifiant exact.")
        val confirmation = a.s("confirmation").trim().lowercase()
        if (confirmation != "oui") return err("Aucune suppression effectuée. Présente d'abord la fiche #$id (${cible.date}, ${cible.categorie}, ${cible.description.take(120)}) à l'utilisateur et attends son « oui » explicite. Ne crée aucune autre intervention.")
        val resultat = svc.supprimerIntervention(client.id, site.id, id)
        val baseVerifiee = runCatching { svc.repo.refresh() }.getOrNull()
        return if (baseVerifiee?.client(client.id)?.sites?.firstOrNull { it.id == site.id }?.interventions?.none { it.id == id } == true)
            ok(resultat + "\nSuppression vérifiée après relecture de la base Société.")
        else err("Suppression non confirmée après relecture de la base Société : $resultat")
    }

    private fun interventionsListe(a: JSONObject): ToolResult {
        val (client, erreur) = clientParNom(a.s("client"))
        if (client == null) return err(erreur!!)
        val installation = a.s("installation").trim()
        val sites = if (installation.isBlank()) client.sites else listOfNotNull(siteDe(client, installation))
        if (sites.isEmpty()) return err(
            if (client.sites.isEmpty()) "Aucune installation enregistrée pour " + client.nom + "."
            else "Installation introuvable ou ambiguë pour " + client.nom + ". Installations disponibles : " + client.sites.joinToString { it.nom } + "."
        )
        val avecInterventions = sites.map { site -> site to site.interventions.sortedByDescending { it.date } }
            .filter { it.second.isNotEmpty() }
        if (avecInterventions.isEmpty()) return ok(
            if (installation.isBlank()) "Aucune intervention enregistrée pour les installations de " + client.nom + "."
            else "Aucune intervention enregistrée pour " + client.nom + " — " + sites.first().nom + "."
        )
        return ok("# Historique des interventions — " + client.nom + "\n\n" +
            avecInterventions.joinToString("\n\n") { (site, liste) ->
                "## Installation : " + site.nom + "\n\n" + liste.joinToString("\n\n") { item ->
                    buildString {
                        append("### " + item.date + " · " + item.categorie + " · " + item.statut + " · ID #" + item.id + "\n")
                        append(item.description)
                        if (item.diagnostic.isNotBlank()) append("\n- Diagnostic : " + item.diagnostic)
                        if (item.actions.isNotBlank()) append("\n- Actions : " + item.actions)
                        if (item.pieces.isNotBlank()) append("\n- Pièces : " + item.pieces)
                        if (item.mesures.isNotBlank()) append("\n- Mesures : " + item.mesures)
                        if (item.prochaineMaintenance.isNotBlank()) append("\n- Prochaine maintenance : " + item.prochaineMaintenance)
                        if (item.notes.isNotBlank()) append("\n- Notes : " + item.notes)
                    }
                }
            })
    }


    /** Synthèse factuelle multi-sites : les tendances ne sont signalées que si elles figurent dans les données enregistrées. */
    private fun interventionsSynthese(a: JSONObject): ToolResult {
        val clientQuery = a.s("client")
        val siteQuery = a.s("installation")
        val donnees = runCatching { svc.repo.refresh() }.getOrElse {
            return err("Impossible de relire l'historique Société : ${it.message ?: it.javaClass.simpleName}")
        }
        val clients = donnees.clients.filter { client ->
            (clientQuery.isBlank() || client.nom.contains(clientQuery, true)) &&
                client.sites.any { siteQuery.isBlank() || it.nom.contains(siteQuery, true) }
        }
        if (clients.isEmpty()) return ok("Aucun client ou site ne correspond aux critères.")
        val sites = clients.flatMap { client ->
            client.sites.filter { siteQuery.isBlank() || it.nom.contains(siteQuery, true) }
                .map { client to it }
        }
        val all = sites.flatMap { (client, site) -> site.interventions.map { Triple(client, site, it) } }
        if (all.isEmpty()) return ok("Aucune intervention photovoltaïque enregistrée pour les sites sélectionnés.")
        val today = LocalDate.now()
        val upcoming = all.filter { (_, _, intervention) ->
            intervention.prochaineMaintenance.isNotBlank() &&
                runCatching { !LocalDate.parse(intervention.prochaineMaintenance).isAfter(today.plusDays(30)) }.getOrDefault(false)
        }.sortedBy { it.third.prochaineMaintenance }
        val pending = all.filter { it.third.statut.equals("a_suivre", true) || it.third.statut.equals("en_attente", true) }
        val byCategory = all.groupingBy { it.third.categorie.ifBlank { "non précisée" } }.eachCount()
        val out = StringBuilder("# Bilan des interventions photovoltaïques\n\n")
        out.append("- Clients : ${clients.size}\n- Installations : ${sites.size}\n- Interventions enregistrées : ${all.size}\n")
        out.append("- Dernière intervention : ${all.maxByOrNull { it.third.date }?.let { "${it.third.date} · ${it.first.nom} / ${it.second.nom}" } ?: "inconnue"}\n")
        out.append("\n## Répartition par catégorie\n")
        byCategory.entries.sortedByDescending { it.value }.forEach { (category, count) -> out.append("- $category : $count\n") }
        val byYear = all.groupingBy { it.third.date.take(4).ifBlank { "date inconnue" } }.eachCount()
        out.append("\n## Activité par année\n")
        byYear.entries.sortedByDescending { it.key }.forEach { (year, count) ->
            out.append("- $year : $count intervention(s)\n")
        }
        out.append("\n## Interventions à suivre / en attente\n")
        if (pending.isEmpty()) out.append("- Aucune intervention avec ce statut.\n")
        else pending.sortedByDescending { it.third.date }.forEach { (client, site, item) ->
            out.append("- ${item.date} · ${client.nom} / ${site.nom} · ${item.statut} : ${item.description.take(180)}\n")
        }
        out.append("\n## Maintenances à échéance dans les 30 jours ou en retard\n")
        if (upcoming.isEmpty()) out.append("- Aucune date de prochaine maintenance dans les données sélectionnées.\n")
        else upcoming.forEach { (client, site, item) ->
            val date = runCatching { LocalDate.parse(item.prochaineMaintenance) }.getOrNull()
            val label = if (date != null && date.isBefore(today)) "EN RETARD" else "À VENIR"
            out.append("- ${item.prochaineMaintenance} · $label · ${client.nom} / ${site.nom}\n")
        }
        out.append("\n## Dernières interventions\n")
        all.sortedByDescending { it.third.date }.take(12).forEach { (client, site, item) ->
            out.append("- ${item.date} · ${client.nom} / ${site.nom} · ${item.categorie} · ${item.statut} : ${item.description.take(180)}\n")
            if (item.diagnostic.isNotBlank()) out.append("  Diagnostic constaté : ${item.diagnostic.take(220)}\n")
            if (item.actions.isNotBlank()) out.append("  Actions réalisées : ${item.actions.take(220)}\n")
            if (item.pieces.isNotBlank()) out.append("  Pièces : ${item.pieces.take(160)}\n")
            if (item.mesures.isNotBlank()) out.append("  Mesures relevées : ${item.mesures.take(180)}\n")
            if (item.prochaineMaintenance.isNotBlank()) out.append("  Prochaine maintenance : ${item.prochaineMaintenance}\n")
        }
        out.append("\nBilan calculé uniquement à partir des interventions et dates effectivement enregistrées ; aucune mesure ni panne n'est déduite.")
        return ok(out.toString())
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
                    (if (prov != null) "\n💰 Mis en réserve : **${euro(prov.montant)}** (${nombre(prov.pct)} % de ${euro(prov.baseHt)} HT)" else "") +
                    runCatching { "\n\n" + svc.repartitionPaiement(f.numero) }.getOrDefault(""))
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
        if (app.store.gmailAccounts.isEmpty()) return err("Envoyer un mail demande un compte Google connecté (Réglages › Comptes › Mails) — pas disponible en IMAP.")
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
        // Cette consultation doit refléter le JSON réellement persisté, pas un ancien instantané mémoire.
        svc.repo.refresh()
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
        val cible = normaliser(a.s("cible")).let {
            when {
                it == "factures" -> "facture"
                it == "fiche client" || it == "clients" -> "client"
                it.startsWith("rapport") || it.startsWith("intervention") -> "rapport"
                else -> it
            }
        }
        if (cible !in setOf("client", "devis", "facture", "paie", "planning", "rapport")) return err("Cible inconnue : ${a.s("cible")} (client, devis, facture, paie, planning, rapport).")
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
            // Rapports : les rapports récents (note + PDF) prennent tout de suite la nouvelle présentation.
            if (cible == "rapport") Thread { runCatching { svc.regenererRapports() } }.start()
        }
        return when (normaliser(a.s("action"))) {
            "voir" -> ok("```\n$courant\n```")
            "reinitialiser", "reset" -> { appliquer(null); ok("✅ Présentation « $cible » remise au modèle d'origine (tu peux annuler).") }
            "annuler" -> {
                val prec = d.modelesPrec[cible] ?: return err("Rien à annuler pour « $cible ».")
                svc.repo.update { x -> x.copy(modeles = x.modeles + (cible to prec), modelesPrec = x.modelesPrec - cible) }
                if (cible == "planning") Prompts.planningFormat = prec
                if (cible == "rapport") Thread { runCatching { svc.regenererRapports() } }.start()
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
                        (if (cible == "rapport") "Rapport d'intervention : garde les sections conditionnelles {{#a_…}} (une section vide ne s'affiche pas), les lignes d'images {{photo}} / {{signature}} seules sur leur ligne, et n'ajoute aucun texte de contenu inventé.\n" else "") +
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
