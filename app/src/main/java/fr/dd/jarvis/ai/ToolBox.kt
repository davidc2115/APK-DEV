package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.PhoneControl
import fr.dd.jarvis.device.ShizukuShell
import fr.dd.jarvis.societe.SocieteTools
import fr.dd.jarvis.societe.core.Client
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** [direct] = le texte est déjà mis en forme et est affiché tel quel (pas de seconde passe du cerveau). */
data class ToolResult(val text: String, val direct: Boolean = false)

class ToolBox(
    private val app: JarvisApp,
    private val confirm: suspend (String, Boolean) -> Boolean
) {
    private val societe = SocieteTools(app, confirm)
    private val serveur = ServeurTools(app, confirm)
    private val discord = DiscordTools(app, confirm)
    private val tiktok = TikTokTools(app, confirm)
    private val musique = MusicTools(app)
    private val plex = PlexTools(app, confirm)
    private val store get() = app.store
    private val llm get() = app.llm

    // ------------------------------------------------------------------ schéma des outils (format OpenAI)

    private fun prop(desc: String) = JSONObject().put("type", "string").put("description", desc)

    private fun tool(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        val params = JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
        return JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", desc).put("parameters", params))
    }

    val schema: JSONArray = JSONArray().apply {
        put(tool("get_planning", "Affiche le planning (agenda) mis en forme pour une période.",
            mapOf(
                "date" to prop("Date de début, yyyy-MM-dd. Par défaut aujourd'hui."),
                "days" to prop("Nombre de jours à afficher (1 à 14). Par défaut 1."),
                "calendar" to prop("Nom d'un agenda précis (optionnel)."),
                "question" to prop("Question précise de l'utilisateur sur le planning (optionnel).")
            )))
        put(tool("analyser_planning_clients", "Analyse les rendez-vous de l'agenda pour compléter les fiches clients Société. Gère plusieurs clients dans un même événement et extrait uniquement les informations explicitement présentes : téléphone, adresse, puissance kWc, onduleur, contact et détails du rendez-vous. Demande confirmation avant toute modification.", mapOf(
            "date" to prop("Date de début yyyy-MM-dd. Par défaut aujourd'hui."),
            "days" to prop("Nombre de jours à analyser (1 à 90). Par défaut 30."),
            "calendar" to prop("Nom d'un agenda précis (optionnel)."),
            "event_id" to prop("Identifiant exact du rendez-vous (#id), pour n'importer que cet événement."),
            "event_title" to prop("Titre ou expression distinctive d'un seul rendez-vous à importer ; si plusieurs résultats correspondent, demande une précision."),
            "client" to prop("Nom exact ou distinctif d'un seul client : limite l'import aux rendez-vous de ce client uniquement."),
            "all" to prop("true uniquement si l'utilisateur demande explicitement de créer/mettre à jour les fiches de TOUS les rendez-vous du planning.")
        )))
        put(tool("create_event", "Crée un rendez-vous dans l'agenda.",
            mapOf(
                "title" to prop("Titre du rendez-vous."),
                "start" to prop("Début, yyyy-MM-dd'T'HH:mm (heure locale)."),
                "end" to prop("Fin, yyyy-MM-dd'T'HH:mm. Par défaut début + 1 h."),
                "calendar" to prop("Nom de l'agenda (optionnel)."),
                "location" to prop("Adresse ou coordonnées du lieu (optionnel) : géocode le point. Google Agenda reçoit uniquement un lien GPS cliquable dans le champ Lieu ; l’adresse postale reste dans la description."),
                "description" to prop("Notes (optionnel)."),
                "client" to prop("Nom exact ou distinctif d’une fiche client Société : utilise l’adresse et le GPS de la bonne installation. Le champ Lieu Google Agenda ne doit contenir que le lien GPS cliquable ; l’adresse complète reste dans la description."),
                "installation" to prop("Nom de l’installation du client si plusieurs ; optionnel.")
            ), listOf("title", "start")))
        put(tool("update_event", "Modifie un rendez-vous existant (identifiant visible dans le planning : #id).",
            mapOf(
                "event_id" to prop("Identifiant numérique de l'événement."),
                "title" to prop("Nouveau titre (optionnel)."),
                "start" to prop("Nouveau début yyyy-MM-dd'T'HH:mm (optionnel)."),
                "end" to prop("Nouvelle fin (optionnel)."),
                "location" to prop("Nouvelle adresse ou coordonnées (optionnel) : géocode le point ; le champ Lieu Google Agenda reçoit uniquement le lien GPS cliquable et l’adresse reste dans la description.")
            ), listOf("event_id")))
        put(tool("set_event_color", "Change la couleur d'un rendez-vous (synchronisée avec Google Agenda). Identifiant #id visible dans le planning ; sinon cherche par titre.",
            mapOf(
                "event_id" to prop("Identifiant(s) numérique(s), séparés par des virgules (optionnel)."),
                "title" to prop("Mot ou début du titre : TOUS les rendez-vous correspondants des 30 prochains jours (ou de la date donnée) sont colorés."),
                "date" to prop("Jour (AAAA-MM-JJ) dont tous les rendez-vous sont à colorer (optionnel)."),
                "days" to prop("Nombre de jours à partir de date (optionnel)."),
                "calendar" to prop("Limiter à un agenda (optionnel)."),
                "tous" to prop("« true » pour colorer tous les rendez-vous de la période."),
                "color" to prop("Couleur : rouge, orange, jaune, vert, vert foncé, turquoise, bleu, lavande, violet, rose, gris, ou #RRGGBB. « defaut » pour reprendre la couleur de l'agenda.")
            ), listOf("color")))
        put(tool("set_calendar_color", "Change la couleur d'un agenda entier (synchronisée avec Google Agenda).",
            mapOf(
                "calendar" to prop("Nom de l'agenda."),
                "color" to prop("Couleur : rouge, orange, jaune, vert, turquoise, bleu, violet, rose, gris ou #RRGGBB. « defaut » pour la couleur d'origine.")
            ), listOf("calendar", "color")))
        put(tool("delete_event", "Supprime un rendez-vous.", mapOf("event_id" to prop("Identifiant numérique de l'événement.")), listOf("event_id")))
        put(tool("summarize_mails", "Trie et résume les derniers mails de la boîte de réception.",
            mapOf(
                "compte" to prop("Compte mail précis (adresse ou partie), même s'il est désactivé. Vide = comptes actifs seulement."),
                "count" to prop("Nombre de mails à analyser (5 à 40). Par défaut 20."),
                "unread_only" to prop("« true » pour ne lire que les non lus.")
            )))
        put(tool("rechercher_mails", "Retrouve des emails par expéditeur, contenu/objet et plage de dates. Affiche le contenu complet et l'identifiant du message pour répondre dans le fil d'origine.",
            mapOf(
                "compte" to prop("Compte Google précis (optionnel)."),
                "expediteur" to prop("Adresse ou nom de l'expéditeur (optionnel)."),
                "recherche" to prop("Mots ou expression à rechercher dans l'objet et le contenu (optionnel)."),
                "date_debut" to prop("Date de début incluse au format AAAA-MM-JJ (optionnel)."),
                "date_fin" to prop("Date de fin incluse au format AAAA-MM-JJ (optionnel)."),
                "count" to prop("Nombre maximal de résultats (1 à 20). Par défaut 5.")
            )))
        put(tool("repondre_email", "Répond à un email précis dans son fil Gmail d'origine. Affiche le destinataire, l'objet et le texte, puis exige une validation explicite avant envoi.",
            mapOf(
                "compte" to prop("Adresse du compte Gmail."),
                "message_id" to prop("Identifiant exact fourni par rechercher_mails."),
                "texte" to prop("Texte complet de la réponse.")
            ), listOf("compte", "message_id", "texte")))
        put(tool("trier_mails", "Analyse les derniers mails, les classe dans les libellés de l'utilisateur (Facebook, Super U/…, Impôts, Pubs, Réseaux sociaux, Banque et crédit, Maison, Enfants, Santé, Achats et livraisons, Comptes et sécurité, Administratif, Travail, Personnel) et peut les ranger dans Gmail. Pour TOUTE la boîte, utilise grand_menage_mails.",
            mapOf(
                "compte" to prop("Compte mail précis (adresse ou partie), même s'il est désactivé. Vide = comptes actifs seulement."),
                "count" to prop("Nombre de mails à trier (5 à 500). Par défaut 100 ; mets 300-500 pour « toute la boîte »."),
                "unread_only" to prop("« true » pour ne trier que les non lus."),
                "toute_la_boite" to prop("« true » pour inclure aussi les mails déjà archivés (pas seulement la boîte de réception) : utile pour (re)trier tout un compte."),
                "ranger" to prop("« true » pour appliquer les libellés dans Gmail. Un re-tri remplace les anciens libellés de catégorie par les bons."),
                "archiver" to prop("« true » pour aussi retirer de la boîte de réception les mails rangés (ils restent dans leur libellé).")
            )))
        put(tool("changer_voix", "Change la voix de JARVIS (personnage) : homme, femme, jeune, âgé(e), grave, douce, IA/robot, narrateur, animatrice radio, murmure, Jarvis (par défaut), ou un nom de voix Gemini (Kore, Puck…). Sans précision : liste les voix.",
            mapOf("voix" to prop("Description de la voix voulue, ex. « femme âgée », « jeune homme », « robot », « par défaut ». Vide = lister."),
                "description" to prop("Facultatif : description libre (âge, ton, accent) pour une voix sur mesure, ex. « homme de 60 ans avec un accent du Sud »."))))
        put(tool("routines", "Routines de JARVIS (plusieurs actions d'un coup) : lister, lancer, creer/modifier, supprimer. Déclencheurs : phrase (« je pars »), depart_maison, arrivee_maison, arrivee_chantier, depart_chantier, voiture (connexion Android Auto), heure (+ jours : tous, semaine, weekend). Les actions sont des demandes en français que JARVIS exécutera dans l'ordre.",
            mapOf("action" to prop("lister | lancer | creer | supprimer | activer | desactiver"),
                "nom" to prop("Nom de la routine (ex. « Je pars »)."),
                "declencheur" to prop("creer : phrase | depart_maison | arrivee_maison | arrivee_chantier | depart_chantier | voiture | heure"),
                "phrases" to prop("creer, déclencheur phrase : phrases qui la lancent, séparées par « ; » (ex. « je pars ; on y va »)."),
                "heure" to prop("creer, déclencheur heure : HH:MM."),
                "jours" to prop("creer, déclencheur heure : tous | semaine | weekend."),
                "actions" to prop("creer : actions séparées par « ; » ou des retours à la ligne, ex. « éteins les lumières ; lance ma playlist ; envoie un SMS à Thomas : je pars ».")),
            listOf("action")))
        put(tool("journal_de_bord", "Journal de bord (note Obsidian « Journal/… ») : jour = journal d'une journée (rendez-vous, interventions, devis/factures/paiements, km estimés, demandes à JARVIS, résumé) ; semaine = bilan de la semaine.",
            mapOf("periode" to prop("jour (défaut) | semaine"), "date" to prop("Date yyyy-MM-dd (défaut : aujourd'hui)."))))
        put(tool("heure_depart", "Calcule l'heure à laquelle partir pour le prochain rendez-vous (ou celui indiqué) depuis la position actuelle : durée du trajet, trafic (temps réel avec une clé TomTom, sinon heures de pointe), marge de 10 min.",
            mapOf("rendez_vous" to prop("Titre (ou partie) du rendez-vous ; vide = le prochain avec une adresse."))))
        put(tool("montre", "JARVIS sur la montre connectée (conversation relayée sur la montre : réponse à la voix ou réponses toutes faites) : activer ou desactiver.",
            mapOf("etat" to prop("activer | desactiver")), listOf("etat")))
        put(tool("grand_menage_mails", "GRAND MÉNAGE de toute la boîte Gmail : chaque mail rangé dans son libellé (organisation de l'utilisateur, en couleur : Facebook, Super U/…, Impôts, Pubs, Réseaux sociaux, Banque et crédit, Maison, Enfants, Santé, Achats et livraisons, Comptes et sécurité, Administratif, Travail, Personnel), marqué lu + Traité et retiré de la boîte de réception ; anciens libellés supprimés. Tourne en arrière-plan (notification de progression). action=lancer ou etat.",
            mapOf("action" to prop("lancer | etat"), "compte" to prop("Adresse Gmail (vide = le premier compte Google connecté)."))))
        put(tool("tournee_optimisee", "Tournée OPTIMISÉE d'une liste de clients à visiter (sans horaires imposés) : ordre le plus court, trajets, heures d'arrivée, météo et ensoleillement sur chaque installation (utile pour les mesures de production), lien Google Maps de toute la tournée. Pour des rendez-vous déjà fixés dans l'agenda, utilise itineraire.",
            mapOf(
                "clients" to prop("Clients (ou adresses) à visiter, séparés par des virgules ou « ; »."),
                "date" to prop("Date de la tournée yyyy-MM-dd (défaut : aujourd'hui)."),
                "heure" to prop("Heure de départ (ex. 8h30 ; défaut : maintenant aujourd'hui, 8h00 un autre jour)."),
                "duree" to prop("Minutes passées chez chaque client (défaut 60)."),
                "depuis" to prop("Départ : « position » (défaut), « siege » ou une adresse."),
                "retour" to prop("oui (défaut) pour revenir au point de départ, non sinon.")), listOf("clients")))
        put(tool("itineraire", "Calcule et présente les distances, temps de route et liens cartographiques. Sans destination : tournée des rendez-vous de l’agenda. Avec destination : calcule un trajet simple sans démarrer le guidage GPS. Pour lancer réellement la navigation GPS vers une adresse, utilise naviguer.",
            mapOf(
                "destination" to prop("Adresse de destination (trajet simple). Vide pour la tournée de l'agenda."),
                "depuis" to prop("Départ : « position » (défaut), « siege » ou une adresse."),
                "date" to prop("Jour de la tournée yyyy-MM-dd. Par défaut aujourd'hui."),
                "restants" to prop("« true » (défaut) pour ne garder que les rendez-vous restants aujourd'hui, « false » pour toute la journée."),
                "calendar" to prop("Nom d'un agenda précis (optionnel).")
            )))
        put(tool("marquer_mails_traites", "Marque des mails comme traités OU simplement comme lus (« marque comme lu ») : ils sont d'abord triés automatiquement dans leur libellé (catégorie/expéditeur, toutes les newsletters dans un seul libellé), puis marqués lus (+ libellé Traité si traités), archivés si demandé. Compte Google requis.",
            mapOf(
                "traite" to prop("« false » si l'utilisateur demande seulement de marquer comme LU (pas de libellé Traité). Par défaut true."),
                "compte" to prop("Compte mail précis (adresse ou partie), même s'il est désactivé. Vide = comptes actifs seulement."),
                "recherche" to prop("Texte à retrouver dans l'expéditeur ou l'objet. Vide ou « tous » = tous les mails récents non lus."),
                "count" to prop("Nombre de mails récents à considérer (5 à 60). Par défaut 30."),
                "archiver" to prop("Par défaut : traités → rangés dans leur libellé (retirés de la boîte de réception) ; lus → restent dans la boîte. « false » / « true » pour forcer.")
            )))
        put(tool("lire_sms", "Lit les SMS récents (reçus et envoyés).",
            mapOf(
                "count" to prop("Nombre de SMS (1 à 30). Par défaut 10."),
                "contact" to prop("Nom ou numéro pour filtrer (optionnel)."),
                "non_lus" to prop("« true » pour les non lus seulement.")
            )))
        put(tool("rechercher_sms", "Recherche les SMS par contact, contenu et dates inclusives. Affiche le texte complet, l'expéditeur et l'heure précise.",
            mapOf(
                "contact" to prop("Nom du contact ou numéro (optionnel)."),
                "recherche" to prop("Mot ou expression du message (optionnel)."),
                "date_debut" to prop("Date de début incluse AAAA-MM-JJ (optionnel)."),
                "date_fin" to prop("Date de fin incluse AAAA-MM-JJ (optionnel)."),
                "count" to prop("Nombre maximal de résultats (1 à 30). Par défaut 10.")
            )))
        put(tool("repondre_sms", "Répond par SMS à un contact ou au numéro de l'expéditeur. La validation du destinataire et du texte exact est toujours obligatoire.",
            mapOf("destinataire" to prop("Contact ou numéro de l'expéditeur du SMS."), "texte" to prop("Réponse SMS complète.")), listOf("destinataire", "texte")))
        put(tool("envoyer_sms", "Envoie un SMS à un contact ou un numéro.",
            mapOf("destinataire" to prop("Nom du contact ou numéro."), "texte" to prop("Contenu du SMS.")), listOf("destinataire", "texte")))
        put(tool("appeler_contact", "Appelle un contact (ou un numéro) directement.",
            mapOf("contact" to prop("Nom du contact ou numéro.")), listOf("contact")))
        put(tool("mail_envoyer", "Envoie un email depuis un compte Gmail connecté (compte Google, pas IMAP), texte seul, SANS pièce jointe. Pour envoyer ou renvoyer un devis ou une facture, utilise document_email à la place (il joint le PDF). Demande toujours confirmation avant d'envoyer.",
            mapOf(
                "destinataire" to prop("Adresse email du destinataire."),
                "sujet" to prop("Objet du mail."),
                "corps" to prop("Contenu du mail (texte)."),
                "compte" to prop("Compte Gmail à utiliser pour l'envoi (adresse ou partie). Vide = le seul compte connecté, ou demande si plusieurs.")
            ), listOf("destinataire", "sujet", "corps")))
        put(tool("mail_compte", "Liste, active ou désactive un compte mail (désactivé = conservé mais ignoré par JARVIS, ses agents et le point du matin).",
            mapOf("action" to prop("lister | activer | desactiver"), "compte" to prop("Adresse ou partie de l'adresse (ou « imap »). Inutile pour lister.")), listOf("action")))
        put(tool("morning_briefing", "Point du jour : planning du jour et mails importants."))
        put(tool("search_contacts", "Cherche des contacts du téléphone (nom, numéro, email, société).",
            mapOf("query" to prop("Texte recherché.")), listOf("query")))
        put(tool("create_contact", "Crée un contact dans le téléphone.",
            mapOf(
                "name" to prop("Nom complet."),
                "phone" to prop("Téléphone (optionnel)."),
                "email" to prop("Email (optionnel)."),
                "company" to prop("Société (optionnel)."),
                "note" to prop("Notes (optionnel).")
            ), listOf("name")))
        put(tool("update_contact", "Modifie un contact existant (trouvé par son nom).",
            mapOf(
                "name" to prop("Nom du contact à modifier."),
                "new_name" to prop("Nouveau nom (optionnel)."),
                "phone" to prop("Nouveau téléphone (optionnel)."),
                "email" to prop("Nouvel email (optionnel)."),
                "company" to prop("Nouvelle société (optionnel)."),
                "note" to prop("Nouvelles notes (optionnel).")
            ), listOf("name")))
        put(tool("contacts_normaliser_numeros", "Nettoie EN UNE FOIS les numéros de TOUS les contacts du téléphone : même format (« 06 12 34 56 78 » ou international) et suppression des numéros en double d'un même contact. À utiliser pour toute demande en masse sur les numéros (ne boucle pas contact par contact).",
            mapOf("format" to prop("« xx xx xx xx xx » (défaut : 06 12 34 56 78) ou « international » (+33 6 12 34 56 78)."),
                "supprimer_doublons" to prop("« false » pour garder les doublons ; par défaut true."),
                "apercu" to prop("« true » pour seulement montrer ce qui changerait, sans rien modifier."))))
        put(tool("create_contact_card", "Génère la fiche contact complète (Markdown Obsidian) d'un contact et l'enregistre dans le dossier Contacts (ou un sous-dossier). Vérifie déjà seul si une fiche existe pour ce nom (n'importe où sous Contacts) et la met à jour à sa place au lieu d'en créer une deuxième : ne jamais appeler obsidian_lire ou chercher une fiche existante avant, c'est inutile et consomme des tokens pour rien.",
            mapOf("name" to prop("Nom du contact."), "sous_dossier" to prop("Sous-dossier de « Contacts » où ranger la fiche (ex. « Clients », « Fournisseurs », « Famille »). Optionnel : vide = directement dans Contacts.")), listOf("name")))
        put(tool("meteo", "Météo précise (maintenant, prochaines heures, prévisions) à la position du téléphone, ou pour un lieu donné.",
            mapOf("lieu" to prop("Ville ou adresse (optionnel : par défaut la position actuelle du téléphone)."), "jours" to prop("Nombre de jours de prévision (1 à 7). Par défaut 3."))))
        put(tool("generer_image", "Génère une ou plusieurs images à partir d'une description (Cloudflare). Les images sont affichées dans le chat et enregistrées dans Obsidian. Pour plusieurs images (planche, livre de coloriage, série d'illustrations), mets une description par ligne dans « prompt » : chacune est générée séparément et le nom de fichier de chacune est renvoyé (utile ensuite pour creer_fichier avec format=pdf et le paramètre images).",
            mapOf("prompt" to prop("Description détaillée de l'image, rédigée en anglais (sujet, style, lumière, cadrage). Pour plusieurs images, une description par ligne."),
                "modele" to prop("Optionnel : « flux » (flux-2-dev, le plus réaliste/précis), « flux-klein » (flux-2-klein, qualité proche mais plus rapide) ou « flux-schnell » (le plus rapide, qualité moindre). Par défaut le modèle choisi dans Réglages › IA › Images & vidéo.")), listOf("prompt")))
        put(tool("jarvis_ai_status", "Indique les moteurs IA réellement configurés dans JARVIS (Gemini, Groq, Cloudflare), les modèles actifs et le nombre de clés disponibles. N'affiche jamais les clés ni les secrets."))
        put(tool("tester_cles_api", "Teste les clés API IA enregistrées dans les réglages de JARVIS : chaque clé Groq et Gemini séparément, les comptes Cloudflare et les fournisseurs externes Z.ai, OpenRouter, NVIDIA, Mistral et Hugging Face. Distingue clé refusée, permissions, quota, modèle/endpoint et panne fournisseur. Ne révèle jamais les secrets. Les tests d'inférence utilisent au maximum un jeton par clé lorsque le modèle est configuré.",
            mapOf("fournisseur" to prop("Optionnel : tous (défaut), groq, gemini, cloudflare, zai, openrouter, nvidia, mistral ou huggingface."))))
        put(tool("configurer_fournisseur_ia", "Enregistre une clé API fournie par l'utilisateur dans la conversation, dans le stockage chiffré de JARVIS, puis configure Hermès et ses routes de délégation. Ne jamais afficher ni répéter la clé. Fournisseurs : zai, openrouter, nvidia, mistral, huggingface. Demande confirmation avant l'enregistrement et la synchronisation.",
            mapOf("fournisseur" to prop("zai, openrouter, nvidia, mistral ou huggingface."),
                "cle_api" to prop("Clé API exacte fournie par l'utilisateur. Ne pas la recopier dans la réponse."),
                "modele" to prop("Identifiant exact du modèle à utiliser, si connu. Optionnel."),
                "endpoint" to prop("URL OpenAI-compatible personnalisée si nécessaire. Optionnel.")), listOf("fournisseur", "cle_api")))
        put(tool("gerer_extension_hermes", "Découvre et gère les Skills, Plugins et serveurs MCP Hermes. Skills : skill_catalogue, skill_rechercher, skill_liste, skill_inspecter, skill_installer, skill_verifier, skill_mettre_a_jour. Plugins : plugin_catalogue, plugin_liste, plugin_installer, plugin_activer. MCP : mcp_catalogue, mcp_liste, mcp_installer. Pour un skill, recherche et inspecte toujours avant installation ; le scan de sécurité intégré reste obligatoire. Les installations/activations de plugins et MCP demandent confirmation.",
            mapOf("action" to prop("skill_catalogue, skill_rechercher, skill_liste, skill_inspecter, skill_installer, skill_verifier, skill_mettre_a_jour, plugin_catalogue, plugin_liste, plugin_installer, plugin_activer, mcp_catalogue, mcp_liste ou mcp_installer."),
                "nom" to prop("Mot-clé de recherche, nom de skill ou identifiant exact source/chemin du catalogue.")), listOf("action")))
        put(tool("jarvis_ai_agent", "Consulte directement l'agent IA interne de JARVIS : Gemini avec rotation de ses clés, puis Groq en secours. À utiliser pour obtenir un deuxième avis, une analyse spécialisée ou vérifier une conclusion ; cet agent ne pilote pas le téléphone et ne lance aucune action.", mapOf(
            "system" to prop("Rôle et consignes de l'agent spécialisé."),
            "prompt" to prop("Question autonome et contexte complet à analyser.")
        ), listOf("system", "prompt")))
        put(tool("animer_image", "Anime une image déjà générée par JARVIS avec fal.ai / Wan 2.2 (image vers vidéo). Nécessite une clé fal.ai configurée dans Réglages › IA › Images & vidéo. Le MP4 est téléchargé puis enregistré dans JARVIS/Vidéos IA et dans Obsidian. Génération payante selon le tarif fal.ai.",
            mapOf("image" to prop("Nom exact de l’image (jarvis-img:...) ou « derniere » pour la plus récente."), "prompt" to prop("Mouvement souhaité : caméra, sujet, ambiance, gestes (optionnel)."), "duree" to prop("Durée du clip en secondes, 0.5 à 4.5 (défaut 3.5).")), listOf("image")))
        put(tool("generer_video", "Génère une vidéo IA à partir d'une description, de N'IMPORTE QUELLE DURÉE : jusqu'à 5 s c'est un clip unique ; au-delà (10 s, 40 s, 2 min…) le montage de plusieurs plans se fait automatiquement (scénario et prompts précis écrits d'abord par l'agent Gemini). Ne réponds JAMAIS qu'une durée longue est impossible. Nécessite une clé fal.ai configurée dans Réglages › IA › Images & vidéo. Pour animer une image existante, passe son nom dans « image » ou utilise animer_image. Pour une histoire en plusieurs plans, un TikTok/Reel/Short ou un montage avec durée totale, voix off ou musique, utilise tiktok_video (ou video_montage pour un montage général) : ces outils peuvent utiliser Hugging Face si son jeton est configuré. Le MP4 est enregistré dans JARVIS/Vidéos IA et Obsidian, et affiché dans le chat.",
            mapOf("prompt" to prop("Description détaillée de la scène, rédigée en anglais (sujet, action, caméra, lumière, style)."),
                "duree" to prop("Durée totale en secondes demandée par l'utilisateur (défaut 5 ; plus de 5 = montage automatique de plusieurs plans)."), "format" to prop("vertical (défaut), horizontal ou carre."),
                "image" to prop("Optionnel : image à animer (jarvis-img:... ou « derniere »).")), listOf("prompt")))
        put(tool("agenda_afficher", "Affiche ou masque durablement un agenda entier (« ne plus afficher le planning de Thomas », « réaffiche-le »). Un agenda masqué disparaît du planning, de l'itinéraire, du point du jour et de l'écran Agenda tant qu'on ne le réaffiche pas.",
            mapOf("agenda" to prop("Nom (ou partie du nom) de l'agenda."), "afficher" to prop("true pour réafficher, false pour masquer.")), listOf("agenda", "afficher")))
        put(tool("creer_fichier", "Crée un fichier (PDF, DOCX/Word, XLSX/Excel, CSV, VCF, ICS, TXT, MD, HTML, JSON, XML, ZIP…) et l’enregistre dans le coffre Obsidian configuré, sous JARVIS/Documents, avec une copie privée temporaire pour l’aperçu et le partage depuis le chat. Ne crée jamais de dossier ni de copie dans Téléchargements ou à la racine du stockage du téléphone. Si le coffre configuré est inaccessible ou en lecture seule, signale l’erreur : ne bascule pas vers un autre emplacement.  Le texte (docx/pdf/html) accepte du Markdown (# titres, - puces, tableaux |a|b|). Pour xlsx/csv : contenu en lignes « a;b;c » ou JSON [[..],[..]] (une formule commence par =). Pour un PDF avec des images déjà générées (generer_image) — livre de coloriage, planche, album — utilise le paramètre « images » : chaque image devient une page entière, dans l'ordre donné, APRÈS le texte de « contenu ».",
            mapOf("nom" to prop("Nom du fichier sans extension."), "format" to prop("pdf, docx, xlsx, csv, vcf, ics, txt, md, html, json, xml, zip…"),
                "contenu" to prop("Contenu du fichier (voir la description). Pour vcf : texte vCard, ou « tous »."), "titre" to prop("Titre du document (optionnel)."),
                "feuille" to prop("Nom de la feuille Excel (optionnel)."), "recherche" to prop("vcf : filtre sur le nom des contacts à exporter (optionnel)."),
                "inclure" to prop("zip : noms des fichiers déjà créés à inclure, séparés par des virgules."),
                "images" to prop("Pour format=pdf uniquement : noms de fichiers d'images déjà générées par generer_image (ex. img_20261031_120000_123.png, visibles dans jarvis-img: des résultats précédents), séparés par une virgule ou un retour à la ligne — une par page, dans l'ordre. Ajoute une légende par image avec « nomFichier.png::légende ». Si tu ne connais pas les noms exacts, mets « dernieres » (10 dernières), « dernieres:N » ou « toutes » plutôt que de deviner, ou appelle d'abord fichiers_generes.")),
            listOf("nom", "format")))
        put(tool("fichiers_generes", "Liste les fichiers déjà créés par JARVIS (images de generer_image, documents de creer_fichier), les plus récents en premier, avec leur nom exact. À utiliser dès que l'utilisateur fait référence à une image ou un fichier déjà généré sans te redonner son nom exact, avant de dire que tu ne le retrouves pas.",
            mapOf("quoi" to prop("images, fichiers (documents) ou tous (par défaut)."), "max" to prop("Nombre maximum à lister (par défaut 20).")), listOf()))
        put(tool("documents_lister", "Liste les documents importés dans la bibliothèque privée de JARVIS, avec leurs noms exacts.", mapOf()))
        put(tool("document_lire", "Extrait le texte d'un document importé pour l'analyser, le résumer, le convertir ou réutiliser son contenu. Extrait toutes les pages des PDF texte ; pour un PDF scanné, utilise document_analyser afin de lancer l'OCR visuel page par page.", mapOf("nom" to prop("Nom exact du fichier affiché dans la bibliothèque documentaire.")), listOf("nom")))
        put(tool("document_analyser", "Analyse les documents importés : PDF, Word, Excel, PowerPoint, texte, images et vidéos. Pour les documents bureautiques, restitue les titres, tableaux, feuilles et diapositives.", mapOf("nom" to prop("Nom exact du fichier importé."), "question" to prop("Question ou objectif d'analyse.")), listOf("nom")))
        put(tool("analyser_image", "Analyse une image déjà générée par generer_image (ou envoyée par l'utilisateur) : décrit son contenu, répond à une question dessus, lit un texte qu'elle contient, etc.",
            mapOf("image" to prop("Nom de fichier de l'image (vu dans jarvis-img:... ou via fichiers_generes), ou « derniere » pour la dernière générée."),
                "question" to prop("Ce qu'il faut regarder ou dire sur l'image (optionnel ; par défaut une description détaillée).")), listOf("image")))
        put(tool("rechercher_videos_box", "Recherche des films sur le disque dur USB branché à la box (partages SMB/FTP). Retourne les chemins exacts ; ne lance pas la lecture.", mapOf("recherche" to prop("Titre ou fragment du nom du film (optionnel pour lister les vidéos)."), "dossier" to prop("Dossier de départ sur le partage (optionnel)."))))
        put(tool("lire_video_box", "Lit un film stocké sur le disque dur branché à la box : ouvre le sélecteur Android avec les lecteurs vidéo compatibles installés, sans télécharger le film entier.", mapOf("chemin" to prop("Chemin exact du film retourné par rechercher_videos_box, sans smb://.")), listOf("chemin")))
        put(tool("analyser_video", "Analyse une vidéo que l'utilisateur a partagée à JARVIS (bouton « Partager » de n'importe quelle appli, ex. TikTok, vers JARVIS) : avis sur l'accroche, le rythme/montage, la qualité, ce qui plaît ou non, et suggestions concrètes pour améliorer l'engagement (vues, likes, abonnés). Ne fonctionne que sur une vidéo déjà reçue par JARVIS, jamais sur un simple lien.",
            mapOf("video" to prop("Nom de fichier de la vidéo reçue (vu dans jarvis-video:... ou via fichiers_generes), ou « derniere » pour la dernière partagée."),
                "question" to prop("Ce qu'il faut regarder précisément (optionnel ; par défaut un avis général de créateur de contenu).")), listOf("video")))
        put(tool("graphique", "Dessine un graphique (courbe, barres, camembert, aire) et l'affiche directement dans la conversation. Colle la sortie telle quelle dans la réponse.",
            mapOf("type" to prop("ligne, barres, camembert, donut ou aire."), "titre" to prop("Titre du graphique."),
                "etiquettes" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Libellés de l'axe X (ou des parts)."),
                "series" to JSONObject().put("type", "array").put("description", "Liste de séries {nom, valeurs:[nombres]}.").put("items", JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("nom", JSONObject().put("type", "string")).put("valeurs", JSONObject().put("type", "array").put("items", JSONObject().put("type", "number"))))),
                "unite" to prop("Unité (optionnelle), ex. €, kWh.")), listOf("type", "etiquettes", "series")))
        put(tool("conversations", "Gère les conversations de l'appli : liste/recherche les anciennes conversations (par mots-clés, ou « emojis » pour celles avec beaucoup d'emojis), les REPREND (« remonte la conversation… », « reprends celle sur… ») ou démarre une nouvelle conversation. La mémoire (consignes) est commune à toutes les conversations.",
            mapOf("action" to prop("liste | reprendre | nouvelle"), "recherche" to prop("Mots-clés décrivant la conversation (optionnel)."), "id" to prop("Identifiant exact d'une conversation (optionnel, vu dans la liste).")), listOf("action")))
        put(tool("agendas_liste", "Liste les agendas du téléphone et indique lesquels sont masqués."))
        put(tool("tache_arriere_plan", "Lance en arrière-plan une demande LONGUE ou en masse (trier beaucoup de mails, renommer ou modifier plusieurs contacts, ranger…) : elle continue même si l'app est fermée et le résultat arrive par notification. À utiliser quand l'utilisateur dit « en arrière-plan », « pendant que je fais autre chose », ou pour un gros volume.",
            mapOf("demande" to prop("La demande complète et autonome, comme l'utilisateur la formulerait (ex. « trie 300 mails du compte x, range-les dans Gmail et archive les pubs »).")), listOf("demande")))
        put(tool("alarme_creer", "Règle une alarme (sonnerie répétée jusqu'à l'arrêt) SANS ouvrir aucune application ; visible dans la barre d'état.",
            mapOf("heure" to prop("Heure au format HH:MM (24 h)."), "libelle" to prop("Nom de l'alarme (optionnel)."),
                "jours" to prop("Répétition (optionnel) : « lun mar ven », « semaine », « tous les jours ». Vide = une seule fois.")), listOf("heure")))
        put(tool("alarme_supprimer", "Supprime une alarme de l'Horloge native. Aucune permission Android spéciale n'est nécessaire ; cela dépend du support de l'action standard DISMISS_ALARM par l'application Horloge installée. Si elle ne le prend pas en charge, ouvre sa liste pour une suppression manuelle.",
            mapOf("heure" to prop("Heure de l'alarme (optionnel)."), "libelle" to prop("Libellé de l'alarme (optionnel)."))))
        put(tool("alarme_modifier", "Modifie un réveil existant sans créer un doublon. Ne jamais appeler alarme_creer pour une demande de modification. Si l'édition exacte et vérifiable n'est pas disponible, ne rien modifier et ne pas ouvrir l'Horloge.",
            mapOf("ancienne_heure" to prop("Heure actuelle HH:MM de l'alarme à remplacer."), "ancien_libelle" to prop("Libellé actuel (optionnel, si l'heure n'est pas connue)."), "nouvelle_heure" to prop("Nouvelle heure HH:MM."), "nouveau_libelle" to prop("Nouveau libellé (optionnel)."), "jours" to prop("Répétition de la nouvelle alarme : lun, mar, semaine, tous les jours… (optionnel).")), listOf("nouvelle_heure")))
        put(tool("minuteur_creer", "Lance un minuteur avec compte à rebours en notification, SANS ouvrir aucune application.",
            mapOf("secondes" to prop("Durée totale en secondes (ex. 10 min = 600)."), "libelle" to prop("Nom du minuteur (optionnel).")), listOf("secondes")))
        put(tool("chronometre", "Chronomètre sans ouvrir d'application (notification avec compteur).", mapOf("action" to prop("demarrer (défaut) | arreter | etat"))))
        put(tool("alarmes_liste", "Liste les alarmes et minuteurs en cours."))
        put(tool("rappel_creer", "Programme un RAPPEL (« rappelle-moi de … ») : notification sur le téléphone à l'heure exacte, avec « Fait » et « Plus tard », une fois ou en répétition. À utiliser pour tout rappel simple, plutôt qu'une alarme (réservée au réveil / à une sonnerie) ou un évènement d'agenda (sauf demande explicite). Termine ta réponse par le lien renvoyé.",
            mapOf(
                "texte" to prop("Ce qu'il faut rappeler, formulé pour l'utilisateur (ex. « Appeler Martin pour le devis »)."),
                "quand" to prop("Moment : de préférence « AAAA-MM-JJ HH:MM » calculé depuis la date actuelle ; accepte aussi « dans 20 min », « demain 9h », « vendredi 17h30 »."),
                "repetition" to prop("Optionnel, vide = une seule fois. Recopie la fréquence dite par l'utilisateur en français (« tous les jours », « un jour sur deux », « tous les deux jours », « tous les 15 jours », « un dimanche sur deux », « toutes les 3 semaines », « tous les mois », « tous les deux mois », « chaque trimestre », « tous les ans », « tous les lundis et jeudis », « du lundi au vendredi », « le week-end »…) ou un code (2:jour, 3:semaine, 2:mois, 1:an, jours:1,4). Pour « un X sur N », « quand » = la PROCHAINE bonne date (demande laquelle si ce n'est pas clair).")
            ), listOf("texte", "quand")))
        put(tool("rappels_liste", "Liste TOUT ce qui est programmé : rappels, alarmes, minuteurs, routines, automatismes d'Hermès (tâches planifiées) et de Jarvis, en cours ou à venir (et les passés si demandé). Renvoie aussi le lien vers la page « Rappels et automatisations ».",
            mapOf("passes" to prop("« true » pour inclure ce qui s'est déclenché ces 7 derniers jours."))))
        put(tool("rappel_supprimer", "Supprime un ou plusieurs rappels à venir dont le texte contient les mots donnés (demande confirmation s'il y en a plusieurs).",
            mapOf("texte" to prop("Mots du rappel à supprimer (ex. « Martin »).")), listOf("texte")))
         put(tool("horloge_diagnostic", "Vérifie les alarmes, minuteurs, notifications et Shizuku en cas de panne."))
        put(tool("applis_musique", "Liste les applications de musique RÉELLEMENT installées sur le téléphone (jamais une liste inventée)."))
        put(tool("jouer_musique", "Lance la lecture d'un titre, artiste ou playlist dans une application de musique du téléphone, en arrière-plan. Si l'application n'est pas précisée et qu'aucune n'est mémorisée et qu'il y en a plusieurs d'installées, l'outil demande laquelle (liste réelle) : repose alors la question à l'utilisateur (et s'il veut « toujours » ou « une seule fois »), puis rappelle l'outil.",
            mapOf("requete" to prop("Titre, artiste ou style à écouter."), "appli" to prop("Nom exact d'une application RENVOYÉE PAR applis_musique ou déjà mémorisée (jamais une application supposée sans l'avoir vérifiée)."), "toujours" to prop("« true » pour toujours utiliser cette application pour la musique."))))
        put(tool("controle_media", "Contrôle la lecture en cours : play, pause, next, previous, stop.", mapOf("action" to prop("play | pause | next | previous | stop")), listOf("action")))
        put(tool("naviguer", "OBLIGATOIRE pour toute demande de lancement/démarrage d’un itinéraire GPS vers une adresse ou un lieu : transmet la destination à Waze, Google Maps ou l’application choisie afin de démarrer le guidage, pas seulement d’ouvrir sa page d’accueil. Si l’application n’est pas précisée et qu’aucune n’est mémorisée, demande laquelle (Waze / Google Maps…) et si le choix doit être conservé (« toujours ») ou ponctuel (« une seule fois »), puis rappelle l’outil.",
            mapOf("destination" to prop("Adresse ou lieu."), "mode" to prop("voiture (défaut), marche ou velo"),
                "appli" to prop("Application : Waze, Google Maps, ou autre (optionnel)."),
                "toujours" to prop("« true » si l'utilisateur veut toujours utiliser cette application pour ce type de demande ; « false » pour cette fois seulement.")), listOf("destination")))
        put(tool("appli_par_defaut", "Définit (ou efface) l'application utilisée par défaut pour un type de demande.",
            mapOf("type" to prop("navigation | musique"), "appli" to prop("Nom de l'application, ou « aucune » pour redemander à chaque fois.")), listOf("type", "appli")))
        put(tool("obsidian_chercher", "Cherche du texte dans les notes du coffre Obsidian (noms et contenu).", mapOf("requete" to prop("Texte recherché.")), listOf("requete")))
        put(tool("obsidian_fichiers", "Gère les dossiers et fichiers DU COFFRE OBSIDIAN configuré dans les Réglages (jamais le stockage du téléphone) : lister un dossier, CHERCHER des fichiers/dossiers par nom ou sujet dans tout le coffre, créer un dossier ou sous-dossier, déplacer des notes / images / fichiers / dossiers vers un dossier du coffre (créé au besoin), renommer. Les chemins peuvent être approximatifs (majuscules, accents, singulier/pluriel : « Jarvis/image » trouve « JARVIS/Images »), et un dossier comme « images/Coloriages » est créé DANS le dossier « Images » existant du coffre plutôt qu'à côté. Les images générées par JARVIS sont dans « JARVIS/Images » (référence jarvis-img:NOM) et se retrouvent aussi par leur description (en anglais, ex. « coloring firefighter »). Pour déplacer les dernières images générées : chemins = « dernieres_images:N ». En cas de doute sur un emplacement, commence par chercher ou lister.",
            mapOf(
                "action" to prop("lister | chercher | dossier | deplacer | trier | renommer. « trier » range d'un coup TOUS les fichiers dont le NOM contient des mots (motif) dans un dossier : utilise-le dès que l'utilisateur parle de fichiers « nommés / qui contiennent / qui comportent X » — ne demande JAMAIS les noms exacts."),
                "types" to prop("trier : images | pdf | notes | videos | audio | tous (défaut : tous)."),
                "sous_dossier" to prop("trier : « true » (défaut) = une destination sans « / » est créée dans le dossier où se trouvent les fichiers."),
                "motif" to prop("chercher / trier : mots du nom ou du sujet (ex. « coloriage pompier », « coloring firefighter »), ou motif avec * (ex. « *pompier* »)."),
                "chemin" to prop("lister/dossier : chemin du dossier dans le coffre (ex. « Images/Coloriages ») ; renommer : élément à renommer."),
                "chemins" to prop("deplacer : éléments à déplacer, séparés par « ; » ou des retours à la ligne — chemins du coffre, noms jarvis-img:…, simples noms de fichiers, ou « dernieres_images:N »."),
                "destination" to prop("deplacer : dossier du coffre de destination (ex. « Images/Coloriages »), créé s'il n'existe pas."),
                "nouveau_nom" to prop("renommer : nouveau nom (avec extension).")
            ), listOf("action")))
        put(tool("obsidian_lire", "Lit une note Obsidian par son chemin relatif (ex. Contacts/Dupont.md).", mapOf("chemin" to prop("Chemin de la note.")), listOf("chemin")))
        put(tool("obsidian_lister", "Liste les notes du coffre Obsidian, éventuellement d'un dossier.", mapOf("dossier" to prop("Dossier (optionnel)."))))
        put(tool("obsidian_ecrire", "Crée, remplace ou complète une note Obsidian.", mapOf(
            "chemin" to prop("Chemin relatif de la note (ex. JARVIS/Idées.md)."), "contenu" to prop("Contenu Markdown."),
            "ajouter" to prop("« true » pour ajouter à la fin de la note existante au lieu de la remplacer.")), listOf("chemin", "contenu")))
        put(tool("obsidian_supprimer", "Supprime DÉFINITIVEMENT une note du coffre Obsidian (fiche contact, devis, facture, ou toute autre note). Utilise obsidian_lister ou obsidian_chercher avant si le chemin exact n'est pas connu. Pour une fiche contact, utilise plutôt supprimer_contact_card (plus simple : juste le nom).",
            mapOf("chemin" to prop("Chemin relatif exact de la note (ex. Contacts/Dupont.md, Société/Devis/D-2026-012 Dupont.md).")), listOf("chemin")))
        put(tool("supprimer_contact_card", "Supprime DÉFINITIVEMENT la fiche contact (Markdown Obsidian) d'un contact, où qu'elle soit rangée sous Contacts. Trouve la fiche à partir du seul nom, pas besoin du chemin exact.",
            mapOf("name" to prop("Nom du contact dont la fiche doit être supprimée.")), listOf("name")))
        put(tool("delete_contact", "Supprime DÉFINITIVEMENT un contact du téléphone (trouvé par son nom).",
            mapOf("name" to prop("Nom du contact à supprimer.")), listOf("name")))
        put(tool("lire_notifications", "Lit les notifications récentes du téléphone (WhatsApp, SMS, mails, banque…). Nécessite l'accès aux notifications.",
            mapOf("count" to prop("Nombre maximum (1 à 40). Par défaut 15."), "appli" to prop("Filtrer par application (optionnel)."), "minutes" to prop("Seulement celles des N dernières minutes (optionnel)."))))
        put(tool("memoire_ajouter", "Enregistre une consigne durable de l'utilisateur dans la note Obsidian « Mémoire JARVIS » (remplace la consigne du même sujet).",
            mapOf(
                "sujet" to prop("contacts (fiches contact) | briefing (point du matin) | planning | mails | societe | general"),
                "texte" to prop("La consigne complète, formulée clairement (structure, rubriques, calendrier à utiliser, exemples).")
            ), listOf("sujet", "texte")))
        put(tool("note_prendre", "Ajoute une note au CARNET DE NOTES de l'utilisateur (« prends note », « note que », « mets ça dans mes notes »). La note est ajoutée à sa rubrique (jamais remplacée), datée et recopiée dans Obsidian (JARVIS/Notes). Prendre note n'est JAMAIS une action : n'appelle ni Home Assistant ni une autre application pour une demande de note, même si elle parle du poêle, de la télé ou d'un réglage.",
            mapOf(
                "texte" to prop("La note complète, telle que dite par l'utilisateur (garde les valeurs, noms d'entités, codes, réglages)."),
                "sujet" to prop("Rubrique (optionnel) : appareil ou thème, ex. « poêle », « télé », « portail ». Déduite du texte si absente.")
            ), listOf("texte")))
        put(tool("notes_lire", "Relit le carnet de notes de l'utilisateur : une rubrique (« poêle »), une recherche, ou tout le carnet. À appeler avant d'agir sur un appareil ou un sujet pour lequel l'utilisateur a pris des notes (entité Home Assistant à utiliser, réglages, procédure).",
            mapOf("sujet" to prop("Rubrique ou mots recherchés (optionnel ; vide = tout le carnet).")) ))
        put(tool("note_supprimer", "Supprime des notes du carnet : la dernière note, ou les notes d'une rubrique (toutes, ou celles qui contiennent un texte). Demande confirmation avant de supprimer une rubrique entière.",
            mapOf(
                "sujet" to prop("Rubrique concernée, ou « derniere » pour la dernière note prise."),
                "contenant" to prop("Ne supprimer que les notes contenant ce texte (optionnel).")
            ), listOf("sujet")))
        put(tool("tache_ajouter", "Crée une tâche personnelle locale à JARVIS, avec échéance et priorité facultatives.", mapOf(
            "titre" to prop("Intitulé clair de la tâche."),
            "echeance" to prop("Date d'échéance yyyy-MM-dd, optionnelle."),
            "notes" to prop("Détails utiles, optionnels."),
            "priorite" to prop("basse | normale | haute | urgente.")
        ), listOf("titre")))
        put(tool("taches_lister", "Affiche les tâches personnelles non terminées, triées par échéance."))
        put(tool("tache_terminer", "Marque une tâche comme terminée à partir de son identifiant (#id).", mapOf(
            "id" to prop("Identifiant exact retourné lors de la création ou de la liste.")
        ), listOf("id")))
        put(tool("memoire_apprendre", "Soumet une préférence candidate à l'apprentissage progressif. À utiliser uniquement après une correction claire ou une observation réellement répétée ; trois preuves distinctes sont requises avant promotion en habitude durable.", mapOf(
            "sujet" to prop("Catégorie courte : préférences, rédaction, planning, musique, travail, projet, etc."),
            "preference" to prop("Règle stable et réutilisable, formulée concrètement."),
            "preuve" to prop("Exemple distinct et factuel de correction ou de choix validé qui soutient cette préférence.")
        ), listOf("sujet", "preference", "preuve")))
        put(tool("memoire_apprentissages", "Affiche les préférences en cours d'apprentissage et leur nombre de preuves."))
        put(tool("agents_centre", "Centre de contrôle des agents IA : consulte leur état, active/désactive un agent, lance un cycle immédiat ou règle la fréquence. Respecte l'interrupteur général des agents autonomes.", mapOf(
            "action" to prop("etat | historique | activer | desactiver | lancer | intervalle"),
            "agent" to prop("global | mails | factures | planning | notifications | rangement_gmail | archivage_gmail"),
            "minutes" to prop("Fréquence en minutes : 15, 30 ou 60 (minimum Android : 15).")
        ), listOf("action")))
        put(tool("graphe_suggere_lien", "Ajoute une relation SUGGESTED entre deux notes Obsidian du graphe. Les deux titres doivent être exacts et uniques ; la relation reste une suggestion non confirmée.", mapOf(
            "source" to prop("Titre exact de la note source déjà indexée."),
            "cible" to prop("Titre exact de la note cible déjà indexée."),
            "type" to prop("RELATED_TO | PROJECT_CONTEXT | SUPPORTS | PREFERENCE_FOR | PART_OF.")
        ), listOf("source", "cible", "type")))
        put(tool("routine_contextuelle", "Prépare le contexte réel d'une routine : départ travail, chantier, retour, ou préparation de demain. Consulte l'agenda et, selon le cas, le trajet ou la fiche client. N'exécute aucune action sensible.", mapOf(
            "situation" to prop("preparer_demain | depart_travail | chantier | retour."),
            "date" to prop("Date yyyy-MM-dd optionnelle ; défaut aujourd'hui ou demain selon la situation."),
            "client" to prop("Nom du client du chantier, si connu."),
            "installation" to prop("Nom de l'installation photovoltaïque si le client en possède plusieurs (optionnel pour consulter tout son historique)."),
            "destination" to prop("Adresse de destination pour le trajet retour ; obligatoire pour calculer un retour vers le domicile."),
            "calendar" to prop("Nom d'un agenda précis, optionnel.")
        ), listOf("situation")))
        put(tool("memoire_lire", "Affiche les consignes enregistrées dans la mémoire JARVIS."))
        put(tool("reglage_jarvis", "Consulte ou modifie directement les réglages non sensibles de JARVIS depuis le chat ou la voix. N'utilise jamais cet outil pour les clés API, mots de passe, tokens, adresses serveur ou paramètres de sécurité.",
            mapOf("action" to prop("lire | modifier"), "reglage" to prop("confirmations | analyse_gemini | conversation | reponses_vocales | ecoute_continue | annonces | agents | agents_mails | agents_factures | agents_planning | agents_notifications | notifications_banniere | bips | duree_conversation"), "valeur" to prop("true/false pour les interrupteurs ; nombre de secondes pour duree_conversation.")), listOf("action", "reglage")))
        put(tool("memoire_oublier", "Supprime la consigne d'un sujet.", mapOf("sujet" to prop("Sujet à oublier.")), listOf("sujet")))
        put(tool("factures_impayees", "Liste les factures émises non encore payées (avec retards)."))
        put(tool("export_kml", "Génère un fichier KML (Google Earth / Google My Maps) avec les points GPS des clients et installations, enregistré dans Obsidian et partageable."))
        put(tool("hermes", "Gère Hermès, l'agent IA installé dans Termux qui sert de cerveau principal : état, installation, configuration (clés, modèle), démarrage, arrêt, mode de répartition.",
            mapOf("action" to prop("etat | installer | configurer | demarrer | arreter | mode"),
                "mode" to prop("Pour action=mode : auto (Hermès reçoit toutes les demandes et choisit lui-même de répondre ou de déléguer aux agents Gemini/Groq/Cloudflare/autres) | demande (Hermès seulement quand on le nomme) | off (Jarvis utilise son moteur interne).")), listOf("action")))
        put(tool("hermes_deleguer", "Confie à Hermès (agent autonome : recherche web, analyse, rédaction, code, tâches en plusieurs étapes, automatismes, serveur/SSH, ses notes, skills, plugins et sa mémoire long terme) une tâche que tes outils ne couvrent pas, ou une information que tu n'as pas (identifiants, réglages, historique). À utiliser plutôt que de répondre « je ne peux pas » ou de redemander une information à l'utilisateur. Décris la tâche complète et le résultat attendu.",
            mapOf("tache" to prop("La tâche détaillée, avec tout le contexte utile.")), listOf("tache")))
        put(tool("ma_position", "Donne la position GPS actuelle du téléphone (fix frais, format Google Maps) et l'ADRESSE PRÉCISE correspondante (numéro, rue, code postal, ville). Pour enregistrer cette position sur une fiche client, utilise client_ecrire avec site_gps = « ma position » (et site_adresse = « ma position » pour l'adresse)."))
        put(tool("phone_health_report", "Produit un diagnostic local en lecture seule du téléphone : batterie, température, stockage, RAM, réseau, Shizuku, accessibilité, notifications, alarmes exactes, optimisation batterie, service vocal et présence d'un rapport de plantage. N'affiche aucune donnée personnelle ni aucun secret.", mapOf("detail" to prop("Optionnel : bref, complet (défaut) ou historique des mesures locales."))))
        put(tool("phone_power_mode", "Change le MODE D’ALIMENTATION GLOBAL du téléphone Xiaomi/HyperOS, pas seulement celui de JARVIS. Modes : economie (Battery Saver), equilibre/normal (mode quotidien recommandé), performance (priorité aux performances, batterie et chauffe accrues), etat (lecture de l’état accessible). Utilise ADB/Shizuku pour l’économie Android ; les modes équilibré/performance Xiaomi passent par le sélecteur HyperOS, avec pilotage visuel si l’accessibilité JARVIS et Gemini sont disponibles. N’affirme un changement que si l’interface ou Android le confirme.", mapOf("mode" to prop("economie | equilibre (ou normal) | performance | etat")), listOf("mode")))
        put(tool("phone_action", "Agit sur le téléphone et pilote les applications Android réellement installées. Actions : list_apps (inventaire réel), open_app, open_url, volume, wifi_on, wifi_off, wifi_connect (ouvre les réglages Wi-Fi), bluetooth_on, bluetooth_off, bluetooth_pair (ouvre les réglages Bluetooth), data_on, data_off, brightness, torch_on, torch_off, call, back, home, recents, notifications, quick_settings, lock, screenshot, read_screen, click_text, click_best_match (sélection sémantique du meilleur élément visible), type_text, scroll_down, scroll_up, tap, inspect_screen et watch_screen (arbre détaillé + journal des changements d'applications, fenêtres, clics, défilements et modifications d'interface observés en arrière-plan). Pour une tâche dans une application : list_apps si le nom est incertain, open_app, inspect_screen, interaction par étapes, puis inspect_screen/watch_screen pour vérifier et récupérer les événements récents. Le journal est alimenté en temps réel par Android lorsque l'accessibilité est active ; il ne conserve pas le texte saisi ni le contenu privé des événements. L'accessibilité JARVIS doit être active pour lire et manipuler l'interface. Les champs sécurisés, écrans protégés, validations Android et confirmations sensibles restent sous le contrôle du système ou de l'utilisateur.",
            mapOf(
                "action" to prop("Nom de l'action."),
                "value" to prop("Paramètre : nom d'app, URL, pourcentage, numéro, texte à cliquer ou à saisir, ou « x,y » pour tap.")
            ), listOf("action")))
        put(tool("voir_ecran", "REGARDE l'écran du téléphone (capture réelle analysée par la vision Gemini) : ce qui est affiché, l'application ouverte, l'état (lecture en cours, erreur, formulaire…) et réponse à une question précise. La capture est jointe dans la conversation.",
            mapOf("question" to prop("Ce qu'il faut vérifier ou décrire (optionnel).")), emptyList()))
        put(tool("piloter_telephone", "CONTRÔLE COMPLET du téléphone : Jarvis voit l'écran et agit dans N'IMPORTE QUELLE application comme une personne (ouvrir l'appli, chercher, appuyer sur les boutons, saisir du texte, faire défiler, fermer les pop-ups…), en vérifiant visuellement après chaque geste, jusqu'à atteindre l'objectif ; capture finale jointe. À utiliser quand une action n'a pas d'outil dédié, ou pour terminer/vérifier une action qui n'a pas abouti. Les envois, paiements, suppressions et appels non demandés explicitement sont soumis à confirmation.",
            mapOf("objectif" to prop("Ce qu'il faut obtenir à l'écran, précis et complet (ex. « dans Spotify, lance l'album Discovery de Daft Punk et vérifie que la lecture a démarré »)."),
                "application" to prop("Application à ouvrir d'abord (optionnel).")), listOf("objectif")))
        put(tool("run_shell", "Exécute une commande shell sur le téléphone via adb/Shizuku (réservé aux besoins que phone_action ne couvre pas).",
            mapOf("command" to prop("Commande shell.")), listOf("command")))
        put(tool("stockage_telephone", "Accède au stockage partagé du téléphone ET recherche aussi dans le coffre Obsidian : recherche récursive en direct dans tous les dossiers accessibles (y compris images générées, renommées ou déplacées), liste, lit, renomme, déplace, supprime, crée un dossier. Utilise chercher dès que l’utilisateur demande de retrouver un fichier ou une image ; ne te limite jamais au dossier courant.",
            mapOf(
                "action" to prop("chercher | lister | lire | renommer | deplacer | supprimer | dossier. chercher = recherche récursive sur tout le stockage partagé accessible et le coffre Obsidian."),
                "chemin" to prop("Chemin relatif à la racine du stockage (ex. « Movies/film.avi », vide = racine)."),
                "destination" to prop("renommer/deplacer uniquement : le nouveau chemin complet (ex. « Movies/Dirty Dancing (1987).avi »).")
            ), listOf("action")))
    }

    /** Les outils Société ne sont proposés au modèle que si la conversation les concerne (économie de contexte). */
    fun schemaFor(recentUserText: String): JSONArray {
        val soc = SocieteTools.concerned(recentUserText); val srv = ServeurTools.concerned(recentUserText)
        val dis = DiscordTools.concerned(recentUserText); val tik = TikTokTools.concerned(recentUserText)
        val mus = MusicTools.concerned(recentUserText)
        val plx = PlexTools.concerned(recentUserText)
        if (!soc && !srv && !dis && !tik && !mus && !plx) return schema
        return JSONArray().also { out ->
            for (i in 0 until schema.length()) out.put(schema.get(i))
            if (soc) societe.schema.forEach { out.put(it) }
            if (srv) serveur.schema.forEach { out.put(it) }
            if (dis) discord.schema.forEach { out.put(it) }
            if (tik) tiktok.schema.forEach { out.put(it) }
            if (mus) musique.schema.forEach { out.put(it) }
            if (plx) plex.schema.forEach { out.put(it) }
        }
    }

    fun schemaAll(): JSONArray = JSONArray().also { out ->
        for (i in 0 until schema.length()) out.put(schema.get(i))
        societe.schema.forEach { out.put(it) }
        serveur.schema.forEach { out.put(it) }
        discord.schema.forEach { out.put(it) }
        tiktok.schema.forEach { out.put(it) }
        musique.schema.forEach { out.put(it) }
        plex.schema.forEach { out.put(it) }
    }

    private suspend fun hermesGerer(action: String, mode: String): String {
        val h = fr.dd.jarvis.hermes.HermesTermux
        return when (action.lowercase()) {
            "installer" -> when {
                !h.termuxInstalle(app) -> "Termux n'est pas installé : ouvre Réglages › IA › Hermès (Termux) et touche « 1. Installer Termux »."
                !h.permissionOk(app) -> "Accorde d'abord la permission Termux à JARVIS : Réglages › IA › Hermès (Termux) › « 2. Autoriser »."
                else -> h.installer(app)
            }
            "configurer" -> h.configurer(app)
            "demarrer" -> h.demarrer(app)
            "arreter" -> h.arreter(app)
            "mode" -> {
                val m = mode.lowercase().let { if (it.startsWith("dem")) "demande" else if (it.startsWith("of") || it.startsWith("dés") || it.startsWith("des")) "off" else "auto" }
                app.store.hermesMode.set(m)
                "Mode Hermès : " + when (m) { "auto" -> "cerveau principal (Groq pour les commandes rapides)"; "demande" -> "seulement quand tu le nommes"; else -> "désactivé" } + "."
            }
            else -> {
                val enLigne = fr.dd.jarvis.hermes.Hermes.enLigne(force = true)
                "Hermès : " + (if (enLigne) "🟢 en ligne" else "⚪ hors ligne") +
                    " · Termux " + (if (h.termuxInstalle(app)) "installé" else "absent") +
                    " · mode " + app.store.hermesMode.value +
                    (if (h.message.isNotBlank()) "\nDernière étape : ${h.message}" else "")
            }
        }
    }

    // ------------------------------------------------------------------ exécution

    suspend fun execute(name: String, a: JSONObject): ToolResult { return when (name) {
        "configurer_fournisseur_ia" -> {
            val provider = a.optString("fournisseur").trim().lowercase()
            val key = a.optString("cle_api").trim()
            val model = a.optString("modele").trim()
            val endpoint = a.optString("endpoint").trim()
            val allowed = setOf("zai", "openrouter", "nvidia", "mistral", "huggingface")
            if (provider !in allowed || key.length < 8 || key.any { it.isWhitespace() }) ToolResult("Fournisseur ou clé API invalide. Clé non enregistrée.")
            else if (endpoint.isNotBlank() && !(endpoint.startsWith("https://") && endpoint.length < 300)) ToolResult("Endpoint refusé : une URL HTTPS valide est obligatoire.")
            else if (!confirm("Enregistrer la clé API $provider dans le stockage chiffré de JARVIS et la synchroniser avec Hermès${if (model.isNotBlank()) " (modèle $model)" else ""} ? La clé ne sera pas affichée.", true)) ToolResult("Configuration annulée. Aucune clé enregistrée.")
            else try {
                store.setExternalProvider(provider, key, model, endpoint)
                val sync = fr.dd.jarvis.hermes.HermesTermux.synchroniserCles(app)
                ToolResult("🔐 Clé $provider enregistrée de façon chiffrée. $sync")
            } catch (e: Exception) { ToolResult("❌ Enregistrement impossible : ${e.message ?: "erreur inconnue"}.") }
        }
        "gerer_extension_hermes" -> {
            val action = a.optString("action").trim().lowercase()
            val name = a.optString("nom").trim()
            val mutating = action == "mcp_installer" || action == "plugin_installer" || action.endsWith("_activer") || action == "skill_mettre_a_jour"
            if (mutating && !confirm("Autoriser Hermès à exécuter « $action » pour « $name » dans Termux ?", true))
                ToolResult("Action annulée.")
            else ToolResult(fr.dd.jarvis.hermes.HermesTermux.gererExtension(app, action, name))
        }
        "get_planning" -> getPlanning(a)
        "analyser_planning_clients" -> analyserPlanningClients(a)
        "create_event" -> createEvent(a)
        "update_event" -> updateEvent(a)
        "delete_event" -> deleteEvent(a)
        "set_event_color" -> setEventColor(a)
        "set_calendar_color" -> setCalendarColor(a)
        "rechercher_mails" -> rechercherMails(a)
        "repondre_email" -> repondreEmail(a)
        "summarize_mails" -> summarizeMails(a)
        "morning_briefing" -> ToolResult(briefing(), direct = true)
        "trier_mails" -> trierMails(a)
        "mail_compte" -> mailCompte(a)
        "mail_envoyer" -> mailEnvoyer(a)
        "marquer_mails_traites" -> marquerTraites(a)
        "changer_voix" -> changerVoix(a)
        "grand_menage_mails" -> {
            if (a.optString("action").startsWith("eta", true)) ToolResult(fr.dd.jarvis.device.GrandMenage.etat(app))
            else {
                val compte = store.gmailAccounts.firstOrNull { c -> a.optString("compte").isBlank() || c.contains(a.optString("compte").trim(), true) }
                if (compte == null) ToolResult("Aucun compte Google connecté correspondant (Mails › Connecter mon compte Google).")
                else if (!confirm("Grand ménage de $compte : TOUS les mails rangés dans leur libellé, marqués lus + Traité et retirés de la boîte de réception ; anciens libellés de Jarvis supprimés. Lancer ?", true)) ToolResult("Annulé.")
                else { fr.dd.jarvis.device.GrandMenage.lancer(app, compte); ToolResult("🧹 Grand ménage lancé pour $compte : il tourne en arrière-plan (quelques minutes, notification de progression). Demande-moi « où en est le ménage des mails ? » pour le bilan.") }
            }
        }
        "routines" -> routines(a)
        "journal_de_bord" -> {
            val d = runCatching { java.time.LocalDate.parse(a.optString("date").trim()) }.getOrDefault(java.time.LocalDate.now())
            val (chemin, md) = if (a.optString("periode").startsWith("sem", true)) fr.dd.jarvis.work.Journal.bilanSemaine(app, d) else fr.dd.jarvis.work.Journal.ecrire(app, d)
            ToolResult("$md\n\n📓 Enregistré dans Obsidian : $chemin", direct = true)
        }
        "heure_depart" -> heureDepart(a)
        "montre" -> {
            val oui = !a.optString("etat").startsWith("desac", true)
            fr.dd.jarvis.service.Poignet.activer(app, oui)
            ToolResult(if (oui) "⌚ JARVIS est sur ta montre : réponds au message « JARVIS » (à la voix ou avec une réponse proposée) pour me parler." else "⌚ JARVIS n'est plus relayé sur la montre.")
        }
        "rechercher_sms" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fr.dd.jarvis.device.Telephony.rechercher(app, a.optString("contact"), a.optString("recherche"), a.optString("date_debut"), a.optString("date_fin"), a.optString("count").toIntOrNull() ?: 10)
        }, direct = true)
        "lire_sms" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fr.dd.jarvis.device.Telephony.lire(app, (a.optString("count").toIntOrNull() ?: 10).coerceIn(1, 30), a.optString("contact"), a.optString("non_lus").equals("true", true))
        }, direct = true)
        "repondre_sms" -> {
            val r = fr.dd.jarvis.device.Telephony.resolve(app, a.optString("destinataire"))
            val txt = a.optString("texte").trim()
            if (r == null) ToolResult("Expéditeur du SMS introuvable : ${a.optString("destinataire")}.")
            else if (txt.isBlank()) ToolResult("Réponse SMS vide.")
            else if (!confirm("Confirmer la réponse SMS ?\n\nÀ : ${r.first} (${r.second})\n\n$txt", true)) ToolResult("Réponse SMS refusée : aucun message envoyé.", direct = true)
            else ToolResult(fr.dd.jarvis.device.Telephony.envoyer(app, r.second, txt), direct = true)
        }
        "envoyer_sms" -> {
            val r = fr.dd.jarvis.device.Telephony.resolve(app, a.optString("destinataire"))
            val txt = a.optString("texte")
            if (r == null) ToolResult("Destinataire introuvable (ou sans numéro) : ${a.optString("destinataire")}.")
            else if (txt.isBlank()) ToolResult("Texte du SMS vide.")
            else if (!confirm("Envoyer ce SMS à ${r.first} (${r.second}) ?\n\n« $txt »", true)) ToolResult("Envoi refusé par l'utilisateur.")
            else ToolResult(fr.dd.jarvis.device.Telephony.envoyer(app, r.second, txt))
        }
        "appeler_contact" -> {
            val r = fr.dd.jarvis.device.Telephony.resolve(app, a.optString("contact"))
            if (r == null) ToolResult("Contact introuvable (ou sans numéro) : ${a.optString("contact")}.")
            else if (!confirm("Appeler ${r.first} (${r.second}) ?", true)) ToolResult("Appel refusé par l'utilisateur.")
            else ToolResult(
                if (fr.dd.jarvis.device.Telephony.callGranted(app)) fr.dd.jarvis.device.Telephony.appeler(app, r.second)
                else app.phone.perform("call", r.second)
            )
        }
        "tournee_optimisee" -> {
            val liste = a.optString("clients").split(Regex("\\s*[,;\\n]\\s*")).filter { it.isNotBlank() }
            if (liste.isEmpty()) return ToolResult("Indique les clients à visiter (paramètre clients). Pour les rendez-vous de l'agenda, utilise itineraire.")
            val txt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                fr.dd.jarvis.societe.Trajet.optimisee(app, liste,
                    a.optString("date").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: LocalDate.now(),
                    a.optString("depuis"), a.optString("heure"), a.optString("duree").filter { it.isDigit() }.toIntOrNull()?.coerceIn(5, 480) ?: 60,
                    !a.optString("retour").trim().lowercase().let { it == "non" || it == "false" || it == "0" })
            }
            ToolResult(txt, direct = true)
        }
        "itineraire" -> {
            val dest = a.optString("destination").trim()
            val depuis = a.optString("depuis")
            val txt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (dest.isNotBlank()) fr.dd.jarvis.societe.Trajet.simple(app, dest, depuis)
                else fr.dd.jarvis.societe.Trajet.tournee(
                    app,
                    a.optString("date").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: LocalDate.now(),
                    !a.optString("restants").equals("false", true), depuis, a.optString("calendar")
                )
            }
            ToolResult(txt, direct = true)
        }
        "search_contacts" -> searchContacts(a)
        "create_contact" -> createContact(a)
        "contacts_normaliser_numeros" -> {
            val apercu = a.optString("apercu").trim().lowercase() in setOf("true", "oui", "1")
            val doublons = a.optString("supprimer_doublons").trim().lowercase() !in setOf("false", "non", "0")
            val fmt = a.optString("format").ifBlank { "xx xx xx xx xx" }.lowercase()
            if (!apercu && !confirm("Reformater les numéros de TOUS les contacts (${if (fmt.startsWith("inter")) "format international" else "xx xx xx xx xx"})" + (if (doublons) " et supprimer les doublons" else "") + " ? Pense à faire une sauvegarde (export .vcf) avant.", false)) ToolResult("Refusé par l'utilisateur.")
            else ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.contacts.normaliserNumeros(fmt, doublons, apercu) })
        }
        "update_contact" -> updateContact(a)
        "create_contact_card" -> createCard(a)
        "delete_contact" -> deleteContact(a)
        "meteo" -> ToolResult(fr.dd.jarvis.ai.Meteo.get(app, a.optString("lieu"), a.optString("jours").toIntOrNull() ?: 3))
        "tester_cles_api" -> ToolResult(llm.testerClesApi(a.optString("fournisseur", "tous")), direct = true)
        "jarvis_ai_status" -> ToolResult(
            "Moteurs IA JARVIS : ${llm.keyStatus()}\n" +
                "Modèles configurés : Gemini ${store.geminiModel.value.ifBlank { "non défini" }} ; " +
                "Groq ${store.groqModel.value.ifBlank { "non défini" }} ; " +
                "Cloudflare ${store.cfChatModel.value.ifBlank { "non défini" }}. " +
                "Les secrets ne sont jamais exposés."
        )
        "jarvis_ai_agent" -> {
            val system = a.optString("system").trim().take(6000)
            val prompt = a.optString("prompt").trim().take(14000)
            if (system.isBlank() || prompt.isBlank()) ToolResult("Il faut fournir un rôle et une demande à l'agent IA JARVIS.")
            else try {
                ToolResult("🧠 Avis de l'agent IA JARVIS (Gemini puis Groq en secours) :\n" + llm.agent(system, prompt))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { ToolResult("❌ Agent IA JARVIS indisponible : ${e.message ?: e.javaClass.simpleName}") }
        }
        "generer_image" -> try {
            val modele = a.optString("modele")
            val prompts = a.optString("prompt").lines().map { it.trim() }.filter { it.isNotBlank() }
            if (prompts.size <= 1) {
                val p = prompts.firstOrNull() ?: a.optString("prompt")
                val (f, loc) = fr.dd.jarvis.ai.ImageGen.generate(app, p, modele)
                ToolResult("![image](jarvis-img:${f.name})\n\n_${p.take(160)}_" + (if (loc.isNotBlank()) "\n📁 $loc" else ""), direct = true)
            } else {
                val faites = fr.dd.jarvis.ai.ImageGen.generateMany(app, prompts, modele)
                val manquees = prompts.size - faites.size
                val texte = faites.joinToString("\n\n") { (p, f) -> "![image](jarvis-img:${f.name})\n\n_${p.take(160)}_" } +
                    (if (manquees > 0) "\n\n⚠️ $manquees image(s) ont échoué." else "")
                ToolResult(texte, direct = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ToolResult("❌ Image impossible : ${e.message}")
        }
        "generer_video" -> try {
            val brief = a.optString("prompt").trim()
            val duree = a.optString("duree").replace(',', '.').filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 5.0
            val nomImage = a.optString("image").trim()
            val format = a.optString("format").ifBlank { "vertical" }
            if (duree > 5.5 && nomImage.isBlank()) return ToolResult(fr.dd.jarvis.ai.Montage.lancer(app, fr.dd.jarvis.ai.Montage.Demande(
                sujet = brief, dureeS = duree.toInt(), format = format,
                tiktok = Regex("tiktok|reel|short", RegexOption.IGNORE_CASE).containsMatchIn(brief))), direct = true)
            if (nomImage.isBlank() && !fr.dd.jarvis.ai.ImageToVideoGen.disponible(app) &&
                app.store.huggingFaceToken.value.isBlank() && !fr.dd.jarvis.ai.VideoProviderGen.disponible(app))
                return ToolResult("❌ Aucun fournisseur vidéo configuré. Ajoute un token Hugging Face, une clé UseAPI Flow ou une clé BytePlus Dreamina dans Réglages › IA › Images & vidéo.")
            if (nomImage.isNotBlank() && !fr.dd.jarvis.ai.ImageToVideoGen.disponible(app))
                return ToolResult("❌ L'animation d'image nécessite actuellement une clé fal.ai configurée dans Réglages › IA › Images & vidéo.")
            val prompt = fr.dd.jarvis.ai.VideoGen.preparerPrompt(app, brief, duree, format, nomImage.isNotBlank())
            val (video, loc) = when {
                nomImage.isNotBlank() -> {
                    val image = fr.dd.jarvis.ai.ImageGen.fichier(app, nomImage)
                        ?: return ToolResult("❌ Image introuvable : " + nomImage + ". Utilise fichiers_generes pour retrouver son nom exact.")
                    fr.dd.jarvis.ai.ImageToVideoGen.generate(app, image, prompt, duree)
                }
                fr.dd.jarvis.ai.ImageToVideoGen.disponible(app) -> fr.dd.jarvis.ai.ImageToVideoGen.texteVersVideo(app, prompt, duree, format)
                else -> {
                    val vertical = !format.trim().lowercase().let { it.startsWith("hori") || it == "16:9" || it.startsWith("pays") }
                    val bytes = try { fr.dd.jarvis.ai.VideoGen.genereClip(app, prompt, duree.toInt().coerceIn(2, 5), vertical) }
                    catch (hf: Exception) { fr.dd.jarvis.ai.VideoProviderGen.genererClipSecours(app, prompt, duree.toInt().coerceIn(4, 8), vertical) ?: throw hf }
                    val file = java.io.File(fr.dd.jarvis.ai.VideoGen.dossier(app), "video_" + System.currentTimeMillis() + ".mp4").apply { writeBytes(bytes) }
                    val location = runCatching { app.obsidian.saveBytes("JARVIS/Vidéos IA", file.name, bytes, "video/mp4") }.getOrDefault("")
                    file to location
                }
            }
            ToolResult("🎬 Vidéo créée après analyse et préparation du prompt : " + video.name + "\n\n[▶ Lire la vidéo](jarvis-video-ia:" + video.name + ")\n\n📁 Enregistrée dans Jarvis › Vidéos IA" + (if (loc.isNotBlank()) "\n🗂️ Obsidian : " + loc else ""), direct = true)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ToolResult("❌ Vidéo impossible : " + e.message)
        }
        "animer_image" -> try {
            val imageName = a.optString("image")
            val image = fr.dd.jarvis.ai.ImageGen.fichier(app, imageName)
                ?: return ToolResult("❌ Image introuvable : $imageName. Utilise fichiers_generes pour retrouver son nom exact.")
            val duree = a.optString("duree").toDoubleOrNull() ?: 3.5
            val prompt = fr.dd.jarvis.ai.VideoGen.preparerPrompt(app, a.optString("prompt").ifBlank { "Anime naturellement le sujet avec un mouvement discret et fluide, en préservant exactement l’image source." }, duree, "auto", true)
            val (video, loc) = fr.dd.jarvis.ai.ImageToVideoGen.generate(app, image, prompt, duree)
            ToolResult("🎬 Animation créée après analyse de la demande : ${video.name}\n\n[▶ Lire la vidéo](jarvis-video-ia:${video.name})\n\n📁 Enregistrée dans Jarvis › Vidéos IA" + (if (loc.isNotBlank()) "\n🗂️ Obsidian : $loc" else ""), direct = true)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ToolResult("❌ Animation impossible : ${e.message}")
        }
        "agendas_liste" -> ToolResult(app.calendar.calendars().joinToString("\n") { (if (it.id in app.store.hiddenCals) "🚫 masqué : " else "✅ affiché : ") + app.calendar.displayName(it) }.ifBlank { "Aucun agenda." }, direct = true)
        "agenda_afficher" -> {
            val q = a.optString("agenda").trim().lowercase()
            val show = a.optString("afficher").trim().lowercase() in setOf("true", "oui", "1", "vrai")
            val hits = app.calendar.calendars().filter { q.isNotBlank() && (app.calendar.displayName(it).lowercase().contains(q) || it.name.lowercase().contains(q)) }
            if (hits.isEmpty()) ToolResult("Aucun agenda ne correspond à « $q ». Agendas : " + app.calendar.calendars().joinToString(", ") { app.calendar.displayName(it) })
            else {
                hits.forEach { c -> if ((c.id in app.store.hiddenCals) == show) app.store.toggleCalendar(c.id) }
                ToolResult((if (show) "👁️ Réaffiché : " else "🚫 Masqué partout (planning, itinéraire, point du jour) : ") + hits.joinToString(", ") { app.calendar.displayName(it) } + (if (show) "" else ". Pour le revoir : « réaffiche cet agenda »."))
            }
        }
        "creer_fichier" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { fr.dd.jarvis.ai.Fichiers.creer(app, a) }.getOrElse { "❌ Création impossible : ${it.message}" } }, direct = true)
        "fichiers_generes" -> ToolResult(fr.dd.jarvis.ai.Fichiers.lister(app, a.optString("quoi"), a.optString("max").toIntOrNull() ?: 20))
        "documents_lister" -> ToolResult(fr.dd.jarvis.ai.DocumentStore.list(app))
        "document_lire" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.ai.DocumentStore.extract(app, a.optString("nom")) }, direct = true)
        "document_analyser" -> try {
            val f = fr.dd.jarvis.ai.DocumentStore.find(app, a.optString("nom"))
                ?: return ToolResult("Document introuvable : ${a.optString("nom")}")
            val question = a.optString("question").ifBlank { "Décris précisément ce fichier et son contenu, en français." }
            if (f.length() > 18L * 1024 * 1024) return ToolResult("Fichier trop volumineux pour l'analyse directe (limite 18 Mo).")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                when (f.extension.lowercase()) {
                    "pdf" -> {
                        val extracted = fr.dd.jarvis.ai.DocumentStore.extractPdf(app, f)
                        if (!extracted.startsWith("PDF_SANS_TEXTE|") && !extracted.startsWith("ÉCHEC_EXTRACTION_PDF|")) {
                            ToolResult("Extraction PDF de toutes les pages pour la demande « $question ». Préserve les titres, les noms exacts, les tableaux et les listes ; signale toute limite.\n\n" + extracted)
                        } else {
                            val fd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                            val renderer = android.graphics.pdf.PdfRenderer(fd)
                            try {
                                val pageCount = renderer.pageCount
                                val analyses = ArrayList<String>(pageCount)
                                for (index in 0 until pageCount) {
                                    val page = renderer.openPage(index)
                                    try {
                                        val width = 1400
                                        val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        val bytes = java.io.ByteArrayOutputStream().use { out ->
                                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                                            bitmap.recycle()
                                            out.toByteArray()
                                        }
                                        val prompt = "DOCUMENT COMPLET — PAGE ${index + 1}/$pageCount. Objectif : $question. Lis tous les éléments visibles, tableaux, noms exacts, catégories, ordre, descriptions, numéros et notes. Ne rien inventer. Réponds en français."
                                        analyses += "PAGE ${index + 1}/$pageCount : " + app.llm.gemini.analyserImage(bytes, "image/jpeg", prompt)
                                    } finally { page.close() }
                                }
                                ToolResult("OCR visuel de toutes les pages du PDF scanné ${f.name} : ${analyses.size}/$pageCount pages traitées.\n\n" + analyses.joinToString("\n\n"))
                            } finally { renderer.close(); fd.close() }
                        }
                    }
                    "jpg", "jpeg", "png", "webp", "gif", "bmp" -> {
                        val mime = when (f.extension.lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; else -> "image/png" }
                        ToolResult(app.llm.gemini.analyserImage(f.readBytes(), mime, question))
                    }
                    "mp4", "mov", "mkv", "webm", "avi", "3gp" -> {
                        val mime = when (f.extension.lowercase()) { "mov" -> "video/quicktime"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "3gp" -> "video/3gpp"; else -> "video/mp4" }
                        ToolResult(app.llm.gemini.analyserImage(f.readBytes(), mime, question))
                    }
                    else -> ToolResult(fr.dd.jarvis.ai.DocumentStore.extract(app, f.name))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ToolResult("❌ Analyse du document impossible : ${e.message}")
        }
        "rechercher_videos_box" -> try {
            ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.ai.Box.rechercherVideos(app, a.optString("recherche"), a.optString("dossier")) }, direct = true)
        } catch (e: Exception) { ToolResult("❌ Recherche sur le disque de la box impossible : ${e.message}", direct = true) }
        "lire_video_box" -> try {
            val chemin = a.optString("chemin").trim().trimStart('/')
            if (chemin.isBlank()) ToolResult("Chemin du film manquant.")
            else ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { fr.dd.jarvis.device.MediaServeur.ouvrir(app, chemin) }, direct = true)
        } catch (e: Exception) { ToolResult("❌ Impossible d'ouvrir ce film : ${e.message}", direct = true) }
        "analyser_video" -> try {
            val f = fr.dd.jarvis.ai.Fichiers.videoFichier(app, a.optString("video"))
                ?: return ToolResult("❌ Vidéo « ${a.optString("video")} » introuvable. Partage-la à JARVIS avec le bouton « Partager » de l'appli (ex. TikTok → Partager → JARVIS), puis réessaie.")
            if (f.length() > 18L * 1024 * 1024) return ToolResult("❌ Vidéo trop lourde (${f.length() / 1024 / 1024} Mo) pour être analysée directement : il faudrait une version plus courte ou plus compressée.")
            val mime = when (f.extension.lowercase()) { "mov" -> "video/quicktime"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "3gp" -> "video/3gpp"; else -> "video/mp4" }
            val question = a.optString("question").ifBlank { "Donne un avis de créateur de contenu sur cette vidéo courte (type TikTok) : l'accroche des 3 premières secondes, le rythme et le montage, la qualité d'image/son, ce qui plaît et ce qui ne va pas, puis 2-3 suggestions concrètes pour améliorer l'engagement (vues, likes, abonnés). En français." }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ToolResult(app.llm.gemini.analyserImage(f.readBytes(), mime, question))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ToolResult("❌ Analyse impossible : ${e.message}")
        }
        "analyser_image" -> try {
            val f = fr.dd.jarvis.ai.ImageGen.fichier(app, a.optString("image"))
                ?: return ToolResult("❌ Image « ${a.optString("image")} » introuvable. Appelle fichiers_generes pour voir les noms exacts.")
            val mime = when (f.extension.lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/png" }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ToolResult(app.llm.gemini.analyserImage(f.readBytes(), mime, a.optString("question")))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ToolResult("❌ Analyse impossible : ${e.message}")
        }
        "graphique" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { fr.dd.jarvis.ai.Graphique.creer(app, a) }.getOrElse { "❌ Graphique impossible : ${it.message}" } }, direct = true)
        "conversations" -> {
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRENCH)
            val act = a.optString("action").lowercase()
            val q = a.optString("recherche").trim(); val id = a.optString("id").trim()
            fun ligne(c: Conversations.Info) = "- id=${c.id} · ${fmt.format(java.util.Date(c.ts))} · « ${c.titre} » (${c.count} msg" + (if (c.emojis > 5) ", ${c.emojis} emojis" else "") + ")" + (if (c.apercu.isNotBlank()) " — ${c.apercu.take(80)}" else "")
            when {
                act.startsWith("nouv") -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Conversations.archiverCourante(app); app.chat.clear(); app.convId = ""; app.chatSauver(); ToolResult("➕ Nouvelle conversation démarrée (la précédente est dans l'historique).", direct = true) }
                act.startsWith("rep") || act.startsWith("ouv") -> {
                    val cible = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (id.isNotBlank()) Conversations.liste(app).firstOrNull { it.id == id } else Conversations.chercher(app, q).filter { it.id != app.convId }.firstOrNull()
                    }
                    val cands = if (cible == null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Conversations.liste(app).filter { it.id != app.convId }.take(8) } else emptyList()
                    if (cible == null) ToolResult("Aucune conversation ne correspond clairement. Dernières conversations :\n" + cands.joinToString("\n") { ligne(it) } + "\nChoisis la plus probable (rappelle avec son id) ou demande à l'utilisateur laquelle.")
                    else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (Conversations.ouvrir(app, cible.id)) ToolResult("↩️ Conversation reprise : « ${cible.titre} » (${cible.count} messages, ${fmt.format(java.util.Date(cible.ts))}). Tu peux continuer là où on s'était arrêtés.", direct = true)
                        else ToolResult("Conversation introuvable.")
                    }
                }
                else -> {
                    val l = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Conversations.chercher(app, q).take(12) }
                    ToolResult(if (l.isEmpty()) "Aucune conversation trouvée." else "# 🗂️ Conversations\n\n" + l.joinToString("\n") { ligne(it) })
                }
            }
        }
        "tache_arriere_plan" -> {
            if (fr.dd.jarvis.work.TacheWorker.enCours) ToolResult("Je suis déjà dans une tâche d'arrière-plan : fais cette demande directement.")
            else {
                val d = a.optString("demande").trim()
                if (d.isBlank()) ToolResult("Demande vide.")
                else { fr.dd.jarvis.work.TacheWorker.lancer(app, d); ToolResult("🛠️ Tâche lancée en arrière-plan : « ${d.take(120)} ». Sa progression s'affiche dans une notification et le résultat arrivera ici et en notification (même si l'app est fermée)." +
                (fr.dd.jarvis.work.SuiviTaches.bloquees(app)?.let { "\n$it" } ?: "")) }
            }
        }
        "alarme_creer" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.device.Horloge.creerAlarme(app, a.optString("heure"), a.optString("libelle"), a.optString("jours")) }, direct = true)
        "alarme_supprimer" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val interne = fr.dd.jarvis.device.Horloge.supprimerAlarme(app, a.optString("heure"), a.optString("libelle"))
            if (interne.startsWith("Aucune alarme")) app.phone.supprimerAlarme(a.optString("heure"), a.optString("libelle")) else interne
        }, direct = true)
        "alarme_modifier" -> {
            val ancienneHeure = a.optString("ancienne_heure").trim()
            val ancienLibelle = a.optString("ancien_libelle").trim()
            val nouvelleHeure = a.optString("nouvelle_heure").trim()
            if (nouvelleHeure.isBlank()) ToolResult("Précise la nouvelle heure.")
            else if (ancienneHeure.isBlank() && ancienLibelle.isBlank()) ToolResult("Précise l'heure ou le libellé actuel du réveil ; aucune modification effectuée.")
            else ToolResult("Je n'ai pas encore de méthode fiable pour éditer et vérifier le réveil existant dans l'Horloge Xiaomi. Je n'en crée pas un deuxième et je n'ouvre plus l'Horloge automatiquement. Aucun changement effectué.", direct = true)
        }
        "minuteur_creer" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.device.Horloge.creerMinuteur(app, a.optString("secondes").toDoubleOrNull()?.toInt() ?: 0, a.optString("libelle")) }, direct = true)
        "chronometre" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.device.Horloge.chronometre(app, a.optString("action")) }, direct = true)
        "rappel_creer" -> ToolResult(fr.dd.jarvis.device.Rappels.creer(app, a.optString("texte"), a.optString("quand"), a.optString("repetition")))
        "rappels_liste" -> ToolResult(fr.dd.jarvis.device.Echeancier.texte(app, a.optString("passes").equals("true", true)))
        "rappel_supprimer" -> {
            val cle = a.optString("texte").trim()
            val n = fr.dd.jarvis.societe.core.normaliser(cle)
            val cibles = fr.dd.jarvis.device.Rappels.liste(app).filter { n.isNotBlank() && fr.dd.jarvis.societe.core.normaliser(it.texte).contains(n) && (it.repetition.isNotBlank() || it.at > System.currentTimeMillis()) }
            when {
                cibles.isEmpty() -> ToolResult("Aucun rappel à venir ne contient « $cle ».\n" + fr.dd.jarvis.device.Echeancier.texte(app))
                cibles.size > 1 && !confirm("Supprimer ${cibles.size} rappels ?\n" + cibles.joinToString("\n") { "• ${it.texte} (${fr.dd.jarvis.device.Rappels.format(it.at)})" }, true) -> ToolResult("Annulé : aucun rappel supprimé.")
                else -> ToolResult("🗑️ Rappel(s) supprimé(s) : " + fr.dd.jarvis.device.Rappels.supprimerParTexte(app, cle).joinToString(" ; ") { "« ${it.texte} »" } + "\n[📋 Voir tous mes rappels et automatisations](jarvis://ecran/rappels)")
            }
        }
        "alarmes_liste" -> ToolResult(fr.dd.jarvis.device.Horloge.liste(app), direct = true)
         "horloge_diagnostic" -> ToolResult(fr.dd.jarvis.device.Horloge.diagnostic(app), direct = true)
        "applis_musique" -> {
            val m = app.phone.musicApps()
            ToolResult(if (m.isEmpty()) "Aucune application de musique détectée sur ce téléphone." else "Applications de musique installées : " + m.keys.joinToString(", "), direct = true)
        }
        "jouer_musique" -> {
            var appli = a.optString("appli").trim()
            val toujours = a.optString("toujours").trim().lowercase() in setOf("true", "oui", "1", "vrai")
            if (appli.isBlank()) appli = app.store.appPrefs["musique"].orEmpty()
            val dispo = app.phone.musicApps()
            if (appli.isBlank() && dispo.size > 1)
                ToolResult("CHOIX_REQUIS : demande à l'utilisateur avec laquelle de ces applications lancer la musique (${dispo.keys.joinToString(" ou ")}) et s'il veut la garder toujours ou une seule fois, puis rappelle jouer_musique avec appli et toujours.")
            else {
                if (appli.isBlank()) appli = dispo.keys.firstOrNull().orEmpty()
                // Mémorise le PAQUET exact (pas le texte dicté) : la prochaine fois, la résolution est sans ambiguïté.
                val pkgChoisi = dispo[appli] ?: appli
                if (toujours && appli.isNotBlank()) app.store.setAppPref("musique", pkgChoisi)
                val requete = a.optString("requete")
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.phone.playMusic(requete, pkgChoisi) }
                // Lecture non confirmée : Jarvis prend l'écran, termine dans l'appli (recherche, bon résultat, lecture) et vérifie.
                if (requete.isNotBlank() && !r.contains("est en cours de lecture") && !r.startsWith("🎵 En lecture") && fr.dd.jarvis.device.PiloteEcran.disponible(app) &&
                    !r.contains("n'est pas installée") && !r.contains("Accès aux notifications")) {
                    val nom = app.phone.musicApps().entries.firstOrNull { it.value == pkgChoisi || it.key == pkgChoisi }?.key ?: pkgChoisi
                    val pilote = fr.dd.jarvis.device.PiloteEcran.piloter(app,
                        "Dans l'application $nom, lance la lecture de « $requete » : ouvre l'application si elle n'est pas affichée, utilise la recherche si besoin, appuie sur le résultat qui correspond le mieux puis sur lecture. Termine quand la lecture a visiblement démarré (bouton pause, bon titre).",
                        confirmer = { q -> confirm(q, true) },
                        verifier = { app.phone.lectureEnCours(pkgChoisi)?.let { t -> "🎵 Lecture confirmée par le lecteur : $t" } })
                    ToolResult("$r\n\n👁️ Je termine moi-même dans l'application :\n$pilote", direct = true)
                } else ToolResult(r, direct = true)
            }
        }
        "controle_media" -> ToolResult(app.phone.mediaKey(a.optString("action")))
        "naviguer" -> {
            // En voiture : Android Auto lance son propre GPS (Waze / Maps de la voiture), sans question ni écran du téléphone.
            if (app.enVoiture && a.optString("destination").isNotBlank()) {
                val pt = runCatching { fr.dd.jarvis.societe.Geo.geocode(a.optString("destination")) }.getOrNull()
                if (fr.dd.jarvis.car.NavigationVoiture.lancer(a.optString("destination"), pt?.let { it.lat to it.lon }))
                    return ToolResult("🧭 Guidage lancé dans Android Auto vers ${a.optString("destination")}.")
            }
            var appli = a.optString("appli").trim()
            val toujours = a.optString("toujours").trim().lowercase() in setOf("true", "oui", "1", "vrai")
            if (appli.isBlank()) appli = app.store.appPrefs["navigation"].orEmpty()
            val dispo = app.phone.navApps().keys
            // En voiture, pas de question : Waze s'il est installé (c'est le GPS d'Android Auto le plus courant).
            if (appli.isBlank() && app.enVoiture) appli = dispo.firstOrNull { it.contains("Waze") } ?: dispo.firstOrNull().orEmpty()
            if (appli.isBlank() && dispo.size > 1)
                ToolResult("CHOIX_REQUIS : demande à l'utilisateur avec quelle application lancer l'itinéraire (${dispo.joinToString(" ou ")}) et s'il veut la garder toujours ou une seule fois, puis rappelle naviguer avec appli et toujours.")
            else {
                if (appli.isBlank()) appli = dispo.firstOrNull().orEmpty()
                if (toujours && a.optString("appli").isNotBlank()) app.store.setAppPref("navigation", a.optString("appli").trim())
                val pt = if (appli.lowercase().contains("waze") || !appli.lowercase().contains("google")) runCatching { fr.dd.jarvis.societe.Geo.geocode(a.optString("destination")) }.getOrNull() else null
                ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.phone.navigate(a.optString("destination"), a.optString("mode"), appli, pt?.let { it.lat to it.lon }) +
                        (if (toujours && a.optString("appli").isNotBlank()) " (${a.optString("appli")} mémorisé pour les itinéraires)" else "")
                })
            }
        }
        "appli_par_defaut" -> {
            val t = a.optString("type").trim().lowercase().let { if (it.startsWith("nav") || it.contains("itin")) "navigation" else if (it.startsWith("mus")) "musique" else it }
            val ap = a.optString("appli").trim()
            if (ap.lowercase() in setOf("aucune", "aucun", "rien", "demander", "")) { app.store.setAppPref(t, null); ToolResult("Plus d'application par défaut pour « $t » : je te demanderai à chaque fois.") }
            else { app.store.setAppPref(t, ap); ToolResult("✅ $ap sera toujours utilisée pour « $t ».") }
        }
        "obsidian_chercher" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val r = app.obsidian.searchNotes(a.optString("requete"))
            if (r.isEmpty()) "Aucune note ne contient « ${a.optString("requete")} »." else r.joinToString("\n") { "- ${it.first}" + (if (it.second.isNotBlank()) " : …${it.second}…" else "") }
        })
        "obsidian_lire" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.obsidian.readNote(a.optString("chemin"))?.take(6000) ?: "Note introuvable : ${a.optString("chemin")}" })
        "obsidian_lister" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val l = app.obsidian.listNotes(a.optString("dossier")); if (l.isEmpty()) "Aucune note." else l.take(120).joinToString("\n") + (if (l.size > 120) "\n… (${l.size} notes)" else "")
        })
        "obsidian_ecrire" -> {
            val chemin = a.optString("chemin")
            val cheminNormalise = java.text.Normalizer.normalize(chemin, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "").replace('\\', '/').trimStart('/').lowercase()
            if (cheminNormalise.startsWith("societe/clients/") || cheminNormalise == "societe/clients")
                ToolResult("⛔ Écriture refusée : les fiches du dossier Société/Clients doivent obligatoirement être créées par client_ecrire, qui enregistre d'abord le client dans la base interne affichée par l'application. Aucun fichier n'a été écrit.")
            else {
                val append = a.optString("ajouter").equals("true", true)
                val existe = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.obsidian.readNote(chemin) != null }
                if (existe && !append && !confirm("Remplacer le contenu de la note « $chemin » ?", false)) ToolResult("Refusé par l'utilisateur.")
                else ToolResult("✅ Note enregistrée : " + kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.obsidian.writeNote(chemin, a.optString("contenu"), append) })
            }
        }
        "obsidian_fichiers" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val o = app.obsidian
                when (a.optString("action").trim().lowercase()) {
                    "lister" -> ToolResult(o.listerDossier(a.optString("chemin")), direct = true)
                    "chercher", "rechercher" -> {
                        val motif = a.optString("motif").ifBlank { a.optString("chemins") }.ifBlank { a.optString("chemin") }
                        val r = o.chercherFichiers(motif, dossiers = true)
                        ToolResult(if (r.isEmpty()) "Aucun fichier ni dossier du coffre ne correspond à « $motif »." else
                            "🔎 ${r.size} élément(s) du coffre pour « $motif » :\n" + r.take(80).joinToString("\n") { "- $it" } + (if (r.size > 80) "\n…" else ""))
                    }
                    "dossier", "creer", "créer" -> ToolResult("📁 Dossier prêt : " + o.creerDossier(a.optString("chemin").ifBlank { a.optString("destination") }), direct = true)
                    "renommer" -> ToolResult("✏️ " + o.renommer(a.optString("chemin"), a.optString("nouveau_nom")), direct = true)
                    "trier", "ranger", "classer" -> ToolResult(o.trier(a.optString("motif").ifBlank { a.optString("chemins") }, a.optString("destination"),
                        a.optString("types").ifBlank { "tous" }, a.optString("chemin"), !a.optString("sous_dossier").equals("false", true)), direct = true)
                    "deplacer", "déplacer" -> {
                        val dest = a.optString("destination").trim().trim('/')
                        if (dest.isBlank()) return@withContext ToolResult("Précise le dossier de destination dans le coffre (paramètre destination).")
                        if (a.optString("chemins").isBlank() && a.optString("motif").isNotBlank())
                            return@withContext ToolResult(o.trier(a.optString("motif"), dest, a.optString("types").ifBlank { "tous" }, a.optString("chemin")), direct = true)
                        val brut = a.optString("chemins").ifBlank { a.optString("chemin") }
                        // Références acceptées : chemin du coffre, jarvis-img:NOM, simple nom de fichier, ou les N dernières images générées.
                        val sources = brut.split(';', '\n').map { it.trim().trim('"', '\'') }.filter { it.isNotBlank() }.flatMap { r ->
                            val m = Regex("(?i)^(dernieres?|derni[eè]res?)_images?:(\\d+)$").find(r)
                            val images = o.resoudre("JARVIS/Images") ?: "JARVIS/Images"
                            when {
                                m != null -> o.fichiersDe(images).filter { !it.endsWith(".json") }.take(m.groupValues[2].toInt().coerceIn(1, 100)).map { "$images/$it" }
                                r.startsWith("jarvis-img:") -> listOf(r.removePrefix("jarvis-img:").trim().let { n -> o.resoudre("$images/$n") ?: o.chercherParNom(n) ?: "$images/$n" })
                                // Motif (*) ou description : TOUS les fichiers qui correspondent.
                                r.contains('*') || r.contains('?') -> o.chercherFichiers(r).ifEmpty { listOf(r) }
                                o.resoudre(r) != null -> listOf(o.resoudre(r)!!)
                                !r.contains('/') && o.resoudre("$images/$r") != null -> listOf(o.resoudre("$images/$r")!!)
                                else -> listOfNotNull(o.chercherParNom(r.substringAfterLast('/'))).ifEmpty { o.chercherFichiers(r).ifEmpty { listOf(r) } }
                            }
                        }.distinct()
                        if (sources.isEmpty()) return@withContext ToolResult("Aucun élément à déplacer : donne les noms (jarvis-img:…) ou « dernieres_images:N ».")
                        val ok = mutableListOf<String>(); val ko = mutableListOf<String>()
                        sources.forEach { src -> runCatching { o.deplacer(src, dest) }.onSuccess { ok += it }.onFailure { ko += "$src : ${it.message}" } }
                        ToolResult(buildString {
                            if (ok.isNotEmpty()) append("📦 ${ok.size} élément(s) déplacé(s) dans le coffre :\n" + ok.joinToString("\n") { "- $it" })
                            if (ko.isNotEmpty()) append((if (ok.isNotEmpty()) "\n\n" else "") + "❌ Non déplacé(s) :\n" + ko.joinToString("\n") { "- $it" })
                        }, direct = true)
                    }
                    else -> ToolResult("Action inconnue (lister, dossier, deplacer, renommer).")
                }
            } catch (e: Exception) { ToolResult("❌ ${e.message ?: e.javaClass.simpleName}") }
        }
        "obsidian_supprimer" -> {
            val chemin = a.optString("chemin")
            if (chemin.isBlank()) ToolResult("Chemin manquant.")
            else if (!confirm("Supprimer DÉFINITIVEMENT la note « $chemin » du coffre Obsidian ?", true)) ToolResult("Suppression refusée par l'utilisateur.")
            else {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.obsidian.supprimerNote(chemin) }
                if (ok) ToolResult("🗑️ Note « $chemin » supprimée.")
                else ToolResult("❌ Note introuvable à cet emplacement (déjà supprimée, ou chemin différent) : « $chemin ». Vérifie le chemin exact avec obsidian_lister.")
            }
        }
        "supprimer_contact_card" -> {
            val name = a.optString("name")
            val chemin = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.obsidian.ficheExistante("Contacts", name) }
            if (chemin == null) ToolResult("Aucune fiche trouvée pour « $name » sous Contacts.")
            else if (!confirm("Supprimer DÉFINITIVEMENT la fiche « $chemin » ?", true)) ToolResult("Suppression refusée par l'utilisateur.")
            else {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.obsidian.supprimerNote(chemin) }
                val encorePresente = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { chemin in app.obsidian.listNotes("Contacts") }
                if (ok && !encorePresente) ToolResult("🗑️ Fiche « $chemin » supprimée et absence vérifiée dans le coffre.")
                else ToolResult("❌ La fiche « $chemin » est toujours présente ou le coffre n'a pas confirmé sa suppression. Vérifie les droits d'écriture et l'emplacement Obsidian.")
            }
        }
        "lire_notifications" -> {
            val svc = fr.dd.jarvis.service.JarvisNotificationListener
            if (!svc.active) ToolResult("Accès aux notifications non activé : Réglages › Général › Autorisations › « Accès aux notifications ».")
            else {
                val n = (a.optString("count").toIntOrNull() ?: 15).coerceIn(1, 40)
                val app0 = a.optString("appli").trim().lowercase()
                val since = a.optString("minutes").toLongOrNull()?.let { System.currentTimeMillis() - it * 60_000 } ?: 0L
                val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH)
                val l = svc.snapshot().filter { it.time >= since && (app0.isBlank() || it.app.lowercase().contains(app0) || it.pkg.lowercase().contains(app0)) }.take(n)
                ToolResult(if (l.isEmpty()) "Aucune notification." else "# 🔔 Notifications\n\n" + l.joinToString("\n") { "- **${it.app}** _(${fmt.format(java.util.Date(it.time))})_ ${it.titre} — ${it.texte.take(200)}" }, direct = true)
            }
        }
        "tache_ajouter" -> ToolResult(app.tasks.create(a.optString("titre"), a.optString("echeance"), a.optString("notes"), a.optString("priorite", "normale")))
        "taches_lister" -> ToolResult(app.tasks.list().let { tasks ->
            if (tasks.isEmpty()) "Aucune tâche personnelle en attente."
            else tasks.joinToString("\n") { task ->
                (if (task.priority == "urgente" || task.priority == "haute") "🔴 " else "▫️ ") +
                    task.title + (if (task.dueDate.isNotBlank()) " — échéance " + task.dueDate else " — sans échéance") +
                    " (#" + task.id + ")" + (if (task.notes.isNotBlank()) "\n  " + task.notes else "")
            }
        }, direct = true)
        "tache_terminer" -> ToolResult(app.tasks.complete(a.optString("id")))
        "memoire_ajouter" -> ToolResult("✅ Consigne « ${a.optString("sujet")} » enregistrée (" + app.memo.set(a.optString("sujet"), a.optString("texte")) + ").")
        "memoire_apprendre" -> ToolResult(app.memo.apprendre(a.optString("sujet"), a.optString("preference"), a.optString("preuve")))
        "memoire_apprentissages" -> ToolResult(app.memo.apprentissagesEnCours(), direct = true)
        "agents_centre" -> agentsCentre(a)
        "graphe_suggere_lien" -> ToolResult(app.knowledgeRepository.suggestRelation(a.optString("source"), a.optString("cible"), a.optString("type", "RELATED_TO")))
        "routine_contextuelle" -> routineContextuelle(a)
        "reglage_jarvis" -> reglerJarvis(a)
        "note_prendre" -> {
            val texte = a.optString("texte").trim()
            if (texte.isBlank()) ToolResult("Aucun texte à noter : demande à l'utilisateur ce qu'il faut noter.")
            else ToolResult(fr.dd.jarvis.device.Carnet.noter(app, texte, a.optString("sujet").ifBlank { null }))
        }
        "notes_lire" -> ToolResult(fr.dd.jarvis.device.Carnet.lire(app, a.optString("sujet").ifBlank { a.optString("recherche") }))
        "note_supprimer" -> {
            val sujet = a.optString("sujet").trim()
            if (fr.dd.jarvis.societe.core.normaliser(sujet) in setOf("derniere", "la derniere", "derniere note"))
                ToolResult(fr.dd.jarvis.device.Carnet.supprimerDerniere(app)?.let { "🗑️ Note supprimée de « ${it.sujet} » : ${it.texte.take(200)}" } ?: "Le carnet est déjà vide.")
            else {
                val n = fr.dd.jarvis.device.Carnet.supprimer(app, sujet, a.optString("contenant"))
                ToolResult(if (n == 0) "Aucune note ne correspond à « $sujet »${a.optString("contenant").takeIf { it.isNotBlank() }?.let { " contenant « $it »" } ?: ""}." else "🗑️ $n note(s) supprimée(s) de « $sujet ».")
            }
        }
        "memoire_lire" -> ToolResult(app.memo.all().entries.joinToString("\n\n") { "**${it.key}** : ${it.value}" }.ifBlank { "Aucune consigne enregistrée." } +
            app.memo.hermesPourPrompt().let { if (it.isBlank()) "" else "\n\n---\n**Mémoire d'Hermès (synchronisée)**" + it.substringAfter(":") }, direct = true)
        "memoire_oublier" -> ToolResult(if (app.memo.remove(a.optString("sujet"))) "Consigne oubliée." else "Aucune consigne pour ce sujet.")
        "factures_impayees" -> ToolResult(app.societe.impayees().ifBlank { "✅ Aucune facture impayée." }, direct = true)
        "export_kml" -> ToolResult(app.societe.exporterKml(), direct = true)
        "ma_position" -> ToolResult(fr.dd.jarvis.device.Locator.describe(app))
        "hermes" -> ToolResult(hermesGerer(a.optString("action"), a.optString("mode")))
        "hermes_deleguer" -> fr.dd.jarvis.hermes.Hermes.deleguer(app, a.optString("tache"), this, app.store.userName.value)
            ?.let { ToolResult(it, direct = true) }
            ?: ToolResult("Hermès est indisponible pour l'instant (non installé ou arrêté). Réponds toi-même au mieux.")
        "phone_health_report" -> ToolResult(if (a.optString("detail").equals("historique", true)) fr.dd.jarvis.device.PhoneHealth.historyReport(app) else fr.dd.jarvis.device.PhoneHealth.report(app), direct = true)
        "phone_power_mode" -> ToolResult(fr.dd.jarvis.device.PhonePowerMode.appliquer(app, a.optString("mode")), direct = true)
        "phone_action" -> phoneAction(a)
        "voir_ecran" -> ToolResult(fr.dd.jarvis.device.PiloteEcran.voir(app, a.optString("question")), direct = true)
        "piloter_telephone" -> {
            val objectif = a.optString("objectif").trim()
            if (objectif.isBlank()) ToolResult("Précise l'objectif à atteindre sur le téléphone.")
            else if (!confirm("Je prends la main sur l'écran pour : « ${objectif.take(160)} ». Continuer ?", false)) ToolResult("Pilotage annulé.")
            else {
                val appli = a.optString("application").trim()
                if (appli.isNotBlank()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.phone.openApp(appli) }.also { kotlinx.coroutines.delay(2500) }
                ToolResult(fr.dd.jarvis.device.PiloteEcran.piloter(app, objectif + (if (appli.isNotBlank()) " (application : $appli, déjà ouverte)" else ""),
                    confirmer = { q -> confirm(q, true) }), direct = true)
            }
        }
        "run_shell" -> runShell(a)
        "stockage_telephone" -> {
            val act = a.optString("action").lowercase()
            val o = app.obsidian
            if (act == "chercher" || act == "rechercher") return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val q = a.optString("chemin").ifBlank { a.optString("requete") }
                if (q.isBlank()) return@withContext ToolResult("Indique le nom ou quelques mots du fichier à retrouver.")
                // Fichiers ET dossiers (« trouve le dossier coloriage pompier »), dans le coffre puis dans le reste du stockage.
                val coffre = runCatching { o.chercherFichiers(q, dossiers = true, limite = 250) }.getOrDefault(emptyList())
                    .sortedBy { c -> c.substringAfterLast('/').contains('.') }
                val telephone = runCatching { fr.dd.jarvis.ai.Stockage.action("chercher", q, "") }.getOrElse { "Stockage partagé : " + (it.message ?: "erreur") }
                // Le coffre est souvent DANS le stockage du téléphone : on n'affiche pas deux fois les mêmes éléments.
                val physique = runCatching { o.cheminPhysiqueCoffre() }.getOrNull()?.trim('/')
                val lignesTel = telephone.lines().filter { it.startsWith("📄 ") || it.startsWith("📁 ") }
                    .filter { l -> physique.isNullOrBlank() || coffre.isEmpty() || !l.substringAfter("/").startsWith("$physique/") }
                // Dossiers du coffre : nombre de fichiers, et leur contenu quand il y en a peu (le bon dossier saute aux yeux,
                // même si un dossier homonyme vide existe ailleurs).
                val tout = if (coffre.any { !it.substringAfterLast('/').contains('.') }) runCatching { o.toutLister() }.getOrDefault(emptyList()) else emptyList()
                val dossiersCoffre = coffre.filter { c -> tout.any { it.second && it.first == c } }
                val lignes = (coffre.map { c ->
                    if (c in dossiersCoffre) "📁 Coffre Obsidian › $c — ${o.fichiersSous(c, tout).size} fichier(s)"
                    else (if (c.substringAfterLast('/').contains('.')) "📦 Coffre Obsidian › " else "📁 Coffre Obsidian › ") + c
                } + lignesTel).distinct()
                val details = if (dossiersCoffre.size in 1..3) dossiersCoffre.map { d -> d to o.fichiersSous(d, tout) }.filter { it.second.isNotEmpty() }
                    .map { (d, f) -> "\n📂 Contenu de « $d » :\n" + f.sorted().take(60).joinToString("\n") { "   📄 " + it.removePrefix("$d/") } + (if (f.size > 60) "\n   … (${f.size} fichiers)" else "") }
                    else emptyList()
                val erreurAcces = telephone.takeIf { it.startsWith("Stockage partagé :") }?.let { "⚠️ $it" }
                val contenu = (lignes + listOfNotNull(erreurAcces)).take(250) + details
                ToolResult(if (contenu.isEmpty()) "Aucun résultat pour « $q » dans le stockage accessible et le coffre Obsidian." else "🔎 Recherche globale : « $q »\n" + contenu.joinToString("\n"), direct = true)
            }
            // Un chemin qui pointe DANS le coffre Obsidian (ex. « Documents/Obsidian jarvis/JARVIS/Images ») est traité dans le coffre.
            val dansCoffre = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { o.estDansCoffre(a.optString("chemin")) }.getOrDefault(false) }
            if (dansCoffre && act != "supprimer" && act != "lire") return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val c = o.relatif(a.optString("chemin")); val d = o.relatif(a.optString("destination"))
                    when (act) {
                        "lister" -> ToolResult(o.listerDossier(c), direct = true)
                        "dossier" -> ToolResult("📁 Dossier prêt : " + o.creerDossier(c), direct = true)
                        "deplacer" -> ToolResult("📦 " + o.deplacer(c, if (d.substringAfterLast('/').contains('.')) d.substringBeforeLast('/', "") else d), direct = true)
                        "renommer" -> ToolResult("✏️ " + o.renommer(c, d.substringAfterLast('/')), direct = true)
                        else -> ToolResult(o.listerDossier(c), direct = true)
                    }
                } catch (e: Exception) { ToolResult("❌ ${e.message ?: e.javaClass.simpleName}") }
            }
            if (act == "supprimer" && !confirm("Supprimer « ${a.optString("chemin")} » du stockage du téléphone ?", true)) ToolResult("Refusé par l'utilisateur.")
            else try { ToolResult(fr.dd.jarvis.ai.Stockage.action(act, a.optString("chemin"), a.optString("destination"))) }
            catch (e: Exception) { ToolResult("❌ ${e.message ?: e.javaClass.simpleName}") }
        }
        in SocieteTools.NAMES -> societe.execute(name, a)
        in ServeurTools.NAMES -> serveur.execute(name, a)
        in DiscordTools.NAMES -> discord.execute(name, a)
        in TikTokTools.NAMES -> tiktok.execute(name, a)
        in MusicTools.NAMES -> musique.execute(name, a)
        in PlexTools.NAMES -> plex.execute(name, a)
        else -> ToolResult("Outil inconnu : $name")
    } }

    private fun reglerJarvis(a: JSONObject): ToolResult {
        val action = a.optString("action").trim().lowercase()
        val key = a.optString("reglage").trim().lowercase()
        val raw = a.optString("valeur").trim().lowercase()
        val bool = when(raw) { "true", "oui", "on", "active", "activé", "activer" -> true; "false", "non", "off", "désactive", "désactivé", "desactive", "desactivé" -> false; else -> null }
        val s = app.store
        val labels = mapOf("confirmations" to "confirmation des actions sensibles", "analyse_gemini" to "analyse Gemini", "conversation" to "mode conversation", "reponses_vocales" to "réponses vocales", "ecoute_continue" to "écoute continue", "annonces" to "annonces vocales", "agents" to "agents autonomes", "agents_mails" to "agent mails", "agents_factures" to "agent factures/devis", "agents_planning" to "agent planning", "agents_notifications" to "agent notifications", "agents_rangement_gmail" to "rangement Gmail", "agents_archivage_gmail" to "archivage Gmail", "notifications_banniere" to "bannières de notification", "bips" to "bips d'écoute")
        fun current(): Boolean? = when(key) {
            "confirmations" -> s.confirmSensitive.value
            "analyse_gemini" -> s.analyseGemini.value
            "conversation" -> s.conversationMode.value
            "reponses_vocales" -> s.speakReplies.value
            "ecoute_continue" -> s.wakeOn.value
            "annonces" -> s.annOn.value
            "agents" -> s.agentsOn.value
            "agents_mails" -> s.agentMails.value
            "agents_factures" -> s.agentFactures.value
            "agents_planning" -> s.agentPlanning.value
            "agents_notifications" -> s.agentNotifs.value
            "agents_rangement_gmail" -> s.agentRanger.value
            "agents_archivage_gmail" -> s.agentArchiver.value
            "notifications_banniere" -> s.notifLive.value
            "bips" -> !s.muteBeeps.value
            else -> null
        }
        if (key == "duree_conversation") {
            if (action == "lire") return ToolResult("Durée de conversation : ${s.convSeconds.value} secondes.", direct = true)
            val n = a.optString("valeur").toIntOrNull()?.coerceIn(5, 180) ?: return ToolResult("Indique une durée entre 5 et 180 secondes.")
            s.convSeconds.set(n)
            return ToolResult("✅ Durée de conversation réglée sur $n secondes.", direct = true)
        }
        if (key !in labels) return ToolResult("Réglage non pris en charge ou sensible. Réglages modifiables : ${labels.values.joinToString(", ")} et durée de conversation.")
        if (action == "lire") return ToolResult("${labels[key]} : ${if (current() == true) "activé" else "désactivé"}.", direct = true)
        if (action != "modifier" || bool == null) return ToolResult("Indique si tu veux activer ou désactiver ${labels[key]}.")
        when(key) {
            "confirmations" -> s.confirmSensitive.set(bool)
            "analyse_gemini" -> s.analyseGemini.set(bool)
            "conversation" -> s.conversationMode.set(bool)
            "reponses_vocales" -> s.speakReplies.set(bool)
            "ecoute_continue" -> { s.wakeOn.set(bool); fr.dd.jarvis.service.JarvisService.stop(app); if (bool) fr.dd.jarvis.service.JarvisService.start(app) }
            "annonces" -> s.annOn.set(bool)
            "agents" -> { s.agentsOn.set(bool); fr.dd.jarvis.work.AgentsScheduler.apply(app, s) }
            "agents_mails" -> s.agentMails.set(bool)
            "agents_factures" -> s.agentFactures.set(bool)
            "agents_planning" -> s.agentPlanning.set(bool)
            "agents_notifications" -> s.agentNotifs.set(bool)
            "agents_rangement_gmail" -> s.agentRanger.set(bool)
            "agents_archivage_gmail" -> s.agentArchiver.set(bool)
            "notifications_banniere" -> s.notifLive.set(bool)
            "bips" -> s.muteBeeps.set(!bool)
        }
        return ToolResult("✅ ${labels.getValue(key).replaceFirstChar { it.uppercase() }} ${if (bool) "activé" else "désactivé"} dans les réglages JARVIS.", direct = true)
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRENCH)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    private fun parseDateTime(s: String?): LocalDateTime? {
        if (s.isNullOrBlank()) return null
        val t = s.trim().replace(' ', 'T')
        return try {
            LocalDateTime.parse(t)
        } catch (e: Exception) {
            try {
                LocalDate.parse(t.take(10)).atTime(9, 0)
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** Texte compact des événements, destiné à l'agent Planning. */
    fun planningData(from: LocalDate, days: Int, calendarFilter: String?): String {
        val cal = app.calendar
        val cals = cal.calendars().associateBy { it.id }
        val zone = ZoneId.systemDefault()
        val filterId = calendarFilter?.takeIf { it.isNotBlank() }?.let { cal.findCalendar(it, false)?.id }
        val events = cal.events(from, days, onlyVisible = filterId == null).filter { filterId == null || it.calId == filterId }
        if (events.isEmpty()) return ""
        return events.joinToString("\n") { e ->
            val s = java.time.Instant.ofEpochMilli(e.begin).atZone(if (e.allDay) java.time.ZoneOffset.UTC else zone)
            val en = java.time.Instant.ofEpochMilli(e.end).atZone(if (e.allDay) java.time.ZoneOffset.UTC else zone)
            val when_ = if (e.allDay) "${s.format(dayFmt)} journée entière" else "${s.format(dayFmt)} ${s.format(timeFmt)}→${en.format(timeFmt)}"
            val calName = cals[e.calId]?.let { cal.displayName(it) } ?: "Agenda"
            "• #${e.id} | $when_ | agenda : $calName | ${e.title}" +
                (if (e.location.isNotBlank()) " | lieu : ${e.location}" else "") +
                (if (e.description.isNotBlank()) " | notes : ${fr.dd.jarvis.device.HtmlText.text(e.description).take(400)}" else "")
        }
    }


    private fun agentsCentre(a: JSONObject): ToolResult {
        val store = app.store
        val action = a.optString("action").trim().lowercase()
        val agent = a.optString("agent").trim().lowercase().ifBlank { "global" }
        val label = when (agent) {
            "global", "tous" -> "Agents autonomes"
            "mails", "mail" -> "Agent mails"
            "factures", "facture" -> "Agent factures et devis"
            "planning", "agenda" -> "Agent planning et tâches"
            "notifications", "notification" -> "Agent notifications"
            "rangement_gmail", "rangement" -> "Agent rangement Gmail"
            "archivage_gmail", "archivage" -> "Archivage Gmail"
            else -> return ToolResult("Agent inconnu. Agents disponibles : global, mails, factures, planning, notifications, rangement_gmail.")
        }
        fun enabled(): Boolean = when (agent) {
            "global", "tous" -> store.agentsOn.value
            "mails", "mail" -> store.agentMails.value
            "factures", "facture" -> store.agentFactures.value
            "planning", "agenda" -> store.agentPlanning.value
            "notifications", "notification" -> store.agentNotifs.value
            else -> store.agentRanger.value
        }
        if (action == "etat") {
            val status = listOf(
                "Général" to store.agentsOn.value,
                "Mails" to store.agentMails.value,
                "Factures/devis" to store.agentFactures.value,
                "Planning/tâches" to store.agentPlanning.value,
                "Notifications" to store.agentNotifs.value,
                "Rangement Gmail" to store.agentRanger.value,
                "Archivage Gmail" to store.agentArchiver.value
            )
            val lastRun = store.agentLastRunAt.value.toLongOrNull()?.let { millis ->
                runCatching { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) }.getOrNull()
            } ?: "aucun cycle enregistré"
            val phone = fr.dd.jarvis.device.PhoneHealth.dashboard(app)
            val prerequisites = listOf(
                "Connexion réseau" to phone.network,
                "Compte mail configuré" to if (store.mailConfigured()) "oui" else "non",
                "Accès agenda" to if (app.calendar.hasPermission()) "autorisé" else "manquant",
                "Accès aux notifications du téléphone" to if (phone.notificationListener) "autorisé" else "manquant",
                "Notifications Android" to if (phone.notificationsAllowed) "autorisées" else "bloquées",
                "Optimisation batterie ignorée" to if (phone.unrestrictedBattery) "oui" else "non (Android peut différer les cycles)"
            )
            return ToolResult("# Centre de contrôle des agents IA\n\n" +
                status.joinToString("\n") { "- ${it.first} : ${if (it.second) "activé" else "désactivé"}" } +
                "\n\nFréquence : ${store.agentMinutes.value} min" +
                "\nDernier cycle : ${lastRun}" +
                "\nRésultat : ${store.agentLastRunStatus.value}" +
                "\nDernière synthèse proactive : ${store.proactiveSummary.value.ifBlank { "aucune" }}" +
                "\n\n## Prérequis réellement observés" +
                prerequisites.joinToString("\n") { "- ${it.first} : ${it.second}" } +
                "\n\nUn agent activé mais privé d'un prérequis ne peut pas être considéré comme opérationnel.", direct = true)
        }
        if (action == "historique") {
            val history = store.agentRunHistory.value.lines().filter { it.isNotBlank() }.takeLast(20).asReversed()
            return ToolResult("# Historique des agents autonomes\n\n" +
                if (history.isEmpty()) "Aucun cycle enregistré pour le moment." else history.joinToString("\n") { "- $it" },
                direct = true)
        }
        if (action == "intervalle") {
            val minutes = a.optString("minutes").toIntOrNull()?.takeIf { it in setOf(15, 30, 60) }
                ?: return ToolResult("Choisis 15, 30 ou 60 minutes.")
            store.agentMinutes.set(minutes)
            fr.dd.jarvis.work.AgentsScheduler.apply(app, store)
            return ToolResult("✅ Fréquence des agents réglée à $minutes minutes.", direct = true)
        }
        if (action == "lancer") {
            if (!store.agentsOn.value) return ToolResult("Les agents autonomes sont désactivés. Active d'abord l'agent global.")
            fr.dd.jarvis.work.AgentsScheduler.runNow(app)
            return ToolResult("✅ Un cycle des agents a été demandé. Android peut le différer selon les contraintes système.", direct = true)
        }
        if (action !in setOf("activer", "desactiver", "désactiver")) return ToolResult("Action attendue : etat, historique, activer, desactiver, lancer ou intervalle.")
        val on = action == "activer"
        when (agent) {
            "global", "tous" -> store.agentsOn.set(on)
            "mails", "mail" -> store.agentMails.set(on)
            "factures", "facture" -> store.agentFactures.set(on)
            "planning", "agenda" -> store.agentPlanning.set(on)
            "notifications", "notification" -> store.agentNotifs.set(on)
            "rangement_gmail", "rangement" -> store.agentRanger.set(on)
            else -> store.agentArchiver.set(on)
        }
        fr.dd.jarvis.work.AgentsScheduler.apply(app, store)
        return ToolResult("✅ $label ${if (on) "activé" else "désactivé"}. ${if (on && !store.agentsOn.value && agent != "global" && agent != "tous") "L'agent est configuré mais le commutateur général reste désactivé." else ""}", direct = true)
    }

    private suspend fun routineContextuelle(a: JSONObject): ToolResult {
        val situation = a.optString("situation").trim().lowercase()
        val today = LocalDate.now()
        val date = a.optString("date").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            ?: if (situation == "preparer_demain") today.plusDays(1) else today
        val calendar = a.optString("calendar")
        val out = StringBuilder()
        when (situation) {
            "preparer_demain" -> {
                out.append("PRÉPARATION DE DEMAIN\n")
                val agenda = getPlanning(JSONObject().put("date", date.toString()).put("days", "1").put("calendar", calendar))
                out.append(agenda.text)
                val tasksDue = app.tasks.pendingDue(date)
                out.append("\n\nTÂCHES À FAIRE (en retard ou échéance au plus tard demain)\n")
                out.append(if (tasksDue.isEmpty()) "Aucune tâche en retard ou échue demain." else tasksDue.joinToString("\n") { "- " + it.title + (if (it.dueDate.isNotBlank()) " — " + it.dueDate else "") + " (#" + it.id + ")" })
                val mails = execute("summarize_mails", JSONObject().put("count", "10").put("unread_only", "true"))
                out.append("\n\nMAILS NON LUS À VÉRIFIER\n").append(mails.text.take(5000))
            }
            "depart_travail" -> {
                out.append("DÉPART AU TRAVAIL — ").append(date).append("\n")
                out.append(getPlanning(JSONObject().put("date", date.toString()).put("days", "1").put("calendar", calendar)).text)
                val dueTasks = app.tasks.pendingDue(date)
                if (dueTasks.isNotEmpty()) out.append("\n\nTÂCHES À ÉCHÉANCE\n").append(dueTasks.joinToString("\n") { "- " + it.title + " (#" + it.id + ")" })
                out.append("\n\nTRAJET ET RENDEZ-VOUS RESTANTS\n")
                out.append(execute("itineraire", JSONObject().put("date", date.toString()).put("depuis", "position").put("restants", "true").put("calendar", calendar)).text)
            }
            "chantier" -> {
                out.append("CONTEXTE CHANTIER — ").append(date).append("\n")
                out.append(getPlanning(JSONObject().put("date", date.toString()).put("days", "1").put("calendar", calendar).put("question", "Identifie les rendez-vous de chantier, leurs lieux, horaires et informations utiles.")).text)
                val client = a.optString("client").trim()
                if (client.isNotBlank()) {
                    out.append("\n\nFICHE CLIENT\n")
                    out.append(societe.execute("client_fiche", JSONObject().put("nom", client)).text)
                    out.append("\n\nHISTORIQUE PHOTOVOLTAÏQUE DU CLIENT\n")
                    out.append(societe.execute("interventions_synthese", JSONObject()
                        .put("client", client)
                        .put("installation", a.optString("installation"))).text)
                } else out.append("\nAucun client n'a été précisé : identifie le chantier depuis l'agenda et demande confirmation si plusieurs correspondent.")
            }
            "retour" -> {
                out.append("CONTEXTE DE RETOUR — ").append(date).append("\n")
                out.append(getPlanning(JSONObject().put("date", date.toString()).put("days", "1").put("calendar", calendar).put("question", "Quels rendez-vous ou engagements restent aujourd'hui ?")).text)
                val destination = a.optString("destination").trim()
                if (destination.isNotBlank()) {
                    out.append("\n\nTRAJET DEPUIS LA POSITION ACTUELLE\n")
                    out.append(execute("itineraire", JSONObject().put("destination", destination).put("depuis", "position")).text)
                } else out.append("\nDestination de retour non renseignée : ne suppose pas l'adresse du domicile et demande-la avant de calculer le trajet.")
            }
            else -> return ToolResult("Situation inconnue. Choisis preparer_demain, depart_travail, chantier ou retour.")
        }
        return ToolResult(out.toString(), direct = true)
    }

    private suspend fun getPlanning(a: JSONObject): ToolResult {
        if (!app.calendar.hasPermission()) return ToolResult("Permission agenda manquante : l'autoriser dans l'onglet Agenda.")
        val from = try {
            a.optString("date").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.take(10)) } ?: LocalDate.now()
        } catch (e: Exception) {
            LocalDate.now()
        }
        val days = (a.optString("days").toIntOrNull() ?: 1).coerceIn(1, 14)
        val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { planningData(from, days, a.optString("calendar")) }
        if (data.isBlank()) {
            val title = from.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH))
            return ToolResult("# 📅 $title\n\nAucun événement sur cette période. 🎉", direct = true)
        }
        val q = a.optString("question")
        // Présentation standard : mise en forme locale instantanée. L'agent IA n'intervient que pour une question
        // précise ou si l'utilisateur a personnalisé la présentation du planning.
        if (q.isBlank() && app.societe.data.modeles["planning"] == null) {
            return ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { localPlanning(from, days, a.optString("calendar")) }, direct = true)
        }
        val user = "Aujourd'hui : ${Prompts.now()}\nPériode : du $from sur $days jour(s)\n" +
            (if (q.isNotBlank()) "Question : $q\n" else "") + "Événements :\n$data"
        // L'agent peut être lent ou en erreur (quota, modèle) : au-delà de 40 s ou en cas d'échec, mise en forme locale.
        var raison = ""
        val text = try {
            llm.agent(Prompts.planning() + app.memo.pour("planning"), user)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            raison = e.message ?: ""
            null
        }
        val out = text ?: (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { localPlanning(from, days, a.optString("calendar")) } +
            "\n\n_ℹ️ Mise en forme locale — agent IA indisponible : ${raison.take(200)}_")
        return ToolResult(out, direct = true)
    }

    /** Planning mis en forme sans IA (même présentation : 📅 jour, blocs séparés, 🕗 horaires, 📍 Maps, 📞 tel:). */
    fun localPlanning(from: LocalDate, days: Int, calendarFilter: String?): String {
        val cal = app.calendar
        val zone = ZoneId.systemDefault()
        val filterId = calendarFilter?.takeIf { it.isNotBlank() }?.let { cal.findCalendar(it, false)?.id }
        val events = cal.events(from, days, onlyVisible = filterId == null).filter { filterId == null || it.calId == filterId }
        val dayTitle = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
        val phone = Regex("(?:\\+33|0)\\s?[1-9](?:[\\s.-]?\\d{2}){4}")
        val sb = StringBuilder()
        var last: LocalDate? = null
        events.forEach { e ->
            val z = if (e.allDay) java.time.ZoneOffset.UTC else zone
            val s = java.time.Instant.ofEpochMilli(e.begin).atZone(z)
            val en = java.time.Instant.ofEpochMilli(e.end).atZone(z)
            if (s.toLocalDate() != last) {
                last = s.toLocalDate()
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("# 📅 ").append(s.format(dayTitle).replaceFirstChar { it.uppercase() }).append("\n\n")
            } else sb.append("---\n\n")
            val horaire = if (e.allDay) "Journée entière" else "${s.format(timeFmt)} – ${en.format(timeFmt)}"
            val notes = fr.dd.jarvis.device.HtmlText.lines(e.description)
            val urls = notes.flatMap { fr.dd.jarvis.device.HtmlText.url.findAll(it).map { m -> m.value }.toList() }.distinct()
            val texte = notes.map { fr.dd.jarvis.device.HtmlText.url.replace(it, "").trim() }.filter { it.isNotBlank() && it != "·" }
            sb.append("## 🕗 $horaire · ${e.title}\n")
            if (e.location.isNotBlank()) {
                val q = java.net.URLEncoder.encode(e.location, "UTF-8")
                sb.append("📍 [${e.location}](https://www.google.com/maps/search/?api=1&query=$q)\n")
            }
            urls.forEach { u ->
                val maps = u.contains("maps.app.goo.gl") || u.contains("google.com/maps") || u.contains("goo.gl/maps")
                sb.append(if (maps) "🧭 [Itinéraire]($u)\n" else "🔗 [${u.removePrefix("https://").removePrefix("http://").take(40)}]($u)\n")
            }
            phone.findAll(e.title + " " + e.location + " " + texte.joinToString(" ")).map { it.value }.distinct().forEach { n ->
                val digits = n.replace(Regex("[^0-9+]"), "").let { if (it.startsWith("0")) "+33" + it.drop(1) else it }
                sb.append("📞 [$n](tel:$digits)\n")
            }
            if (texte.isNotEmpty()) sb.append("⚠️ ${texte.joinToString(" · ").take(300)}\n")
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun cleClient(s: String): String =
        java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private suspend fun analyserPlanningClients(a: JSONObject): ToolResult {
        if (!app.calendar.hasPermission()) return ToolResult("Permission de lecture de l'agenda manquante.")
        val from = runCatching {
            val date = a.optString("date").trim()
            if (date.isBlank()) LocalDate.now() else LocalDate.parse(date.take(10))
        }.getOrElse { LocalDate.now() }
        val days = (a.optString("days").toIntOrNull() ?: 30).coerceIn(1, 90)
        val eventId = a.optString("event_id").trim().removePrefix("#").takeIf { it.isNotBlank() }
        val eventTitle = a.optString("event_title").trim()
        val clientQuery = a.optString("client").trim()
        val allRequested = a.optString("all").trim().lowercase() in setOf("true", "oui", "1", "vrai")
        if (eventId == null && eventTitle.isBlank() && clientQuery.isBlank() && !allRequested) {
            return ToolResult("Pour éviter de créer toutes les fiches du planning par erreur, précise le rendez-vous (titre ou identifiant #id) ou le nom du client. Je ne traiterai tout le planning que si tu me le demandes explicitement.", direct = true)
        }
        val calendarName = a.optString("calendar").trim()
        val selectedCalendar = if (calendarName.isBlank()) null else app.calendar.findCalendar(calendarName, false)
            ?: return ToolResult("Agenda introuvable : " + calendarName)
        val periodEvents = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.calendar.events(from, days, onlyVisible = true)
        }.filter { selectedCalendar == null || it.calId == selectedCalendar.id }
        val scopedEvents = periodEvents.filter { event ->
            (eventId == null || event.id.toString() == eventId) &&
                (eventTitle.isBlank() || event.title.contains(eventTitle, ignoreCase = true)) &&
                (clientQuery.isBlank() || cleClient(event.title + " " + event.description).contains(cleClient(clientQuery)))
        }
        if (eventId != null && scopedEvents.isEmpty()) return ToolResult("Aucun rendez-vous trouvé avec l'identifiant #$eventId.", direct = true)
        if (eventTitle.isNotBlank() && scopedEvents.size > 1) return ToolResult("Plusieurs rendez-vous correspondent à « $eventTitle ». Donne l'identifiant #id ou une date pour que je n'en sélectionne qu'un.", direct = true)
        if (clientQuery.isNotBlank() && scopedEvents.isEmpty()) return ToolResult("Aucun rendez-vous ne correspond au client « $clientQuery » sur la période demandée.", direct = true)
        val events = (if (allRequested && eventId == null && eventTitle.isBlank() && clientQuery.isBlank()) periodEvents else scopedEvents).take(80)
        if (events.isEmpty()) return ToolResult("Aucun rendez-vous à analyser sur la période demandée.", direct = true)

        val clients = app.societe.data.clients
        val known = if (clients.isEmpty()) "Aucune fiche client existante : les nouveaux clients peuvent être créés uniquement si leur nom est explicitement identifiable dans le titre ou la description d'un rendez-vous." else clients.joinToString("\n") { c ->
            "- " + c.nom + if (c.sites.isEmpty()) "" else " | installations : " +
                c.sites.joinToString("; ") { (it.nom + " " + it.adresse).trim() }
        }
        val zone = ZoneId.systemDefault()
        val eventById = events.associateBy { it.id.toString() }
        val source = events.joinToString("\n\n") { e ->
            val z = if (e.allDay) java.time.ZoneOffset.UTC else zone
            val st = java.time.Instant.ofEpochMilli(e.begin).atZone(z)
            val en = java.time.Instant.ofEpochMilli(e.end).atZone(z)
            val date = if (e.allDay) st.toLocalDate().toString() else
                st.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "–" +
                    en.format(DateTimeFormatter.ofPattern("HH:mm"))
            "ID=" + e.id + " | DATE=" + date + "\nTITRE=" + e.title +
                "\nLIEU=" + e.location + "\nDESCRIPTION=" +
                fr.dd.jarvis.device.HtmlText.lines(e.description).joinToString("\n").take(5000)
        }
        val system = listOf(
            "Tu extrais les données des rendez-vous pour compléter des fiches clients d'une entreprise photovoltaïque.",
            "Retourne uniquement un tableau JSON valide, sans Markdown ni commentaire.",
            "Une entrée par client ET par installation photovoltaïque dans chaque rendez-vous. Si un client possède deux installations décrites (ex. BRISCOT 1 et BRISCOT 2), retourne DEUX objets avec le même event_id et le même client, un objet par installation. Si plusieurs clients sont cités, sépare aussi chaque client. Ne fusionne jamais deux installations.",
            "Si le client existe, reprends exactement son nom dans la liste connue. Si aucune fiche ne correspond, tu peux extraire un nouveau nom de client UNIQUEMENT s'il est explicitement identifiable dans le titre ou la description (ex. « CE - BRISCOT 1/2 - téléphone » => client « BRISCOT »). Ne crée jamais un nom à partir d'une supposition. Ignore les événements sans nom client certain.",
            "N'extrais que les informations explicitement présentes dans le titre, le lieu ou la description. N'infère jamais un téléphone, une adresse, une puissance ou un modèle d'onduleur.",
            "Chaque objet utilise les clés : event_id, client, contact, phone, email, client_address, site_name, site_address, kwc, inverter, panels, commissioning_date, notes.",
            "Les champs absents sont des chaînes vides ou null. kwc doit toujours être converti en kWc : une valeur en Wc se divise par 1000 (99 830 Wc = 99.83 kWc ; 35 640 Wc = 35.64 kWc). panels un entier. Distingue l'adresse du client de l'adresse du site.",
            "Le lieu du rendez-vous n'est une adresse de site que s'il s'agit clairement du lieu d'intervention.",
            "Clients et installations existants :",
            known
        ).joinToString("\n")
        val user = "Période : " + from + " sur " + days + " jour(s).\nÉvénements :\n" + source
        val raw = try {
            app.llm.agent(system, user)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult("Analyse IA impossible : " + (e.message ?: "erreur inconnue").take(180))
        }
        val first = raw.indexOf('[')
        val last = raw.lastIndexOf(']')
        if (first < 0 || last <= first) return ToolResult("Réponse structurée absente. Aucune fiche client n'a été modifiée.")
        val rows = try {
            JSONArray(raw.substring(first, last + 1))
        } catch (e: Exception) {
            return ToolResult("Réponse JSON illisible. Aucune fiche client n'a été modifiée.")
        }

        data class Pending(val clientId: String, val name: String, val args: JSONObject, val tag: String, val note: String)
        val pending = mutableListOf<Pending>()
        val ignored = linkedSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val event = eventById[row.optString("event_id").trim()] ?: continue
            var name = row.optString("client").trim().takeUnless { it.equals("null", true) || it.equals("undefined", true) }.orEmpty()
            // Certains modèles renvoient "null" lorsqu'aucune fiche n'existe encore.
            // Dans ce cas, récupère uniquement le nom explicite placé après le préfixe CE/RDV du titre.
            if (name.isBlank()) {
                val titleMatch = Regex("""(?i)^(?:CE|RDV|RENDEZ[- ]?VOUS|CLIENT)\s*[-:]\s*(.+?)(?:\s*[-–|].*)?$""").find(event.title.trim())
                name = titleMatch?.groupValues?.getOrNull(1)?.trim()
                    ?.replace(Regex("""\s+\d+(?:\s*/\s*\d+)?$"""), "")
                    ?.trim().orEmpty()
            }
            val existingMatches = clients.filter { cleClient(it.nom) == cleClient(name) }
            val existing = existingMatches.singleOrNull()
            val sourceText = cleClient(event.title + " " + event.description)
            val explicitNewName = name.length >= 3 && cleClient(name).let { normalized ->
                normalized.isNotBlank() && sourceText.contains(normalized)
            }
            if (existingMatches.size > 1 || (existing == null && !explicitNewName)) {
                if (name.isNotBlank()) ignored += name
                continue
            }
            val current = app.societe.data.clients.firstOrNull { it.id == existing?.id }
                ?: existing
                ?: Client(app.societe.newId(), name, depuis = LocalDate.now().toString())
            fun value(key: String): String {
                if (row.isNull(key)) return ""
                return row.optString(key, "").trim().takeUnless { it == "null" }.orEmpty()
            }
            val args = JSONObject().put("nom", current.nom)
            fun fill(key: String, existing: String, output: String, valid: (String) -> Boolean) {
                val extracted = value(key)
                if (existing.isBlank() && extracted.isNotBlank() && valid(extracted)) args.put(output, extracted)
            }
            fill("contact", current.contact, "contact") { true }
            fill("phone", current.tel, "tel") { it.count { ch -> ch.isDigit() } in 9..15 }
            if (current.tel.isBlank() && !args.has("tel")) {
                val phone = Regex("""(?<!\d)(?:0[1-9](?:[ .-]?\d{2}){4}|\+33[ .-]?[1-9](?:[ .-]?\d{2}){4})(?!\d)""").find(event.title + " " + event.description)?.value
                if (phone != null) args.put("tel", phone)
            }
            fill("email", current.email, "email") { it.contains("@") }
            fill("client_address", current.adresse, "adresse") { it.length >= 6 }
            if (current.adresse.isBlank() && !args.has("adresse") && event.location.isNotBlank()) args.put("adresse", event.location.trim())

            val siteName = value("site_name")
            val siteAddress = value("site_address").ifBlank {
                // Le champ LIEU de Google Agenda est l'adresse d'intervention ; il complète aussi la fiche du site.
                if (value("kwc").isNotBlank() || value("inverter").isNotBlank()) event.location.trim() else ""
            }
            val reference = listOf(siteName, siteAddress).firstOrNull { it.isNotBlank() }.orEmpty()
            val site = current.sites.firstOrNull {
                reference.isNotBlank() && (cleClient(it.nom) == cleClient(reference) || cleClient(it.adresse) == cleClient(reference))
            } ?: if (reference.isBlank() && current.sites.size == 1) current.sites.first() else null
            val kwcRaw = value("kwc").trim()
            val kwcNumber = kwcRaw.replace(',', '.').replace(Regex("[^0-9.]"), "").toDoubleOrNull()
            val kwc = kwcNumber?.let { if (Regex("""\bWc\b""", RegexOption.IGNORE_CASE).containsMatchIn(kwcRaw) && !Regex("""kWc""", RegexOption.IGNORE_CASE).containsMatchIn(kwcRaw)) it / 1000.0 else it }
                ?.takeIf { it in 0.1..5000.0 }
            val inverter = value("inverter")
            val panels = value("panels").toIntOrNull()?.takeIf { it in 1..100000 }
            val commissioning = value("commissioning_date")
            val hasSiteData = siteName.isNotBlank() || siteAddress.isNotBlank() || kwc != null ||
                inverter.isNotBlank() || panels != null || commissioning.isNotBlank()
            val safeSite = site != null || reference.isNotBlank() || (current.sites.isEmpty() && hasSiteData)
            if (safeSite && hasSiteData) {
                if (site != null) {
                    if (site.nom.isNotBlank()) args.put("site_nom", site.nom)
                    else if (siteName.isNotBlank()) args.put("site_nom", siteName)
                    if (site.adresse.isNotBlank()) args.put("site_adresse", site.adresse)
                    else if (siteAddress.isNotBlank()) args.put("site_adresse", siteAddress)
                } else {
                    if (siteName.isNotBlank()) args.put("site_nom", siteName)
                    if (siteAddress.isNotBlank()) args.put("site_adresse", siteAddress)
                    if (current.sites.isEmpty() && siteName.isBlank() && siteAddress.isBlank()) args.put("site_nom", "Installation principale")
                }
                if (kwc != null && (site == null || site.kwc <= 0.0)) args.put("kwc", kwc.toString())
                if (inverter.isNotBlank() && (site == null || site.onduleur.isBlank())) args.put("onduleur", inverter)
                if (panels != null && (site == null || site.nbPanneaux <= 0)) args.put("nb_panneaux", panels.toString())
                if (commissioning.isNotBlank() && (site == null || site.miseEnService.isBlank())) args.put("mise_en_service", commissioning)
            }

            val z = if (event.allDay) java.time.ZoneOffset.UTC else zone
            val date = if (event.allDay) java.time.Instant.ofEpochMilli(event.begin).atZone(z).toLocalDate().toString()
                else java.time.Instant.ofEpochMilli(event.begin).atZone(z).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            val tag = "agenda #" + event.id
            val note = "📅 Rendez-vous " + date + " — " + event.title + " (" + tag + ")" +
                event.location.takeIf { it.isNotBlank() }?.let { " · " + it }.orEmpty() +
                value("notes").takeIf { it.isNotBlank() }?.let { " · " + it }.orEmpty()
            if (current.notes.contains(tag) && args.length() == 1) continue
            pending += Pending(current.id, current.nom, args, tag, note)
        }
        if (pending.isEmpty()) {
            val extra = if (ignored.isNotEmpty()) "\nNoms non reconnus : " + ignored.joinToString() else ""
            return ToolResult("Analyse terminée : aucune donnée client nouvelle et vérifiable." + extra, direct = true)
        }
        val preview = pending.joinToString("\n") { p ->
            val fields = listOf(
                "tel" to "📞", "adresse" to "📍", "contact" to "👤", "email" to "✉️",
                "kwc" to "⚡", "onduleur" to "🔌", "site_adresse" to "🏠", "site_nom" to "☀️"
            ).mapNotNull { pair ->
                val v = p.args.optString(pair.first)
                if (v.isBlank()) null else pair.second + " " + v
            }
            "• " + p.name + " : " + (fields + "📅 rendez-vous").joinToString(" · ")
        }
        val question = "J'ai trouvé " + pending.size + " association(s) rendez-vous/client. Les champs déjà renseignés ne seront pas écrasés.\n\n" +
            preview + "\n\nConfirmer l'enregistrement ?"
        if (!confirm(question, false)) return ToolResult("Aucune fiche modifiée : confirmation refusée.", direct = true)

        var saved = 0
        for (p in pending) {
            val live = app.societe.data.clients.firstOrNull { it.id == p.clientId }
            val args = JSONObject(p.args.toString())
            if (live != null && !live.notes.contains(p.tag)) {
                val prefix = live.notes.trimEnd() + if (live.notes.isBlank()) "" else "\n"
                args.put("notes", prefix + p.note)
            }
            val result = societe.execute("client_ecrire", args)
            if (result.text.startsWith("✅")) saved++
        }
        val unknown = if (ignored.isNotEmpty()) "\nNoms non reconnus, ignorés : " + ignored.joinToString() else ""
        return ToolResult("✅ " + saved + " fiche(s) client mise(s) à jour à partir du planning.\n\n" + preview + unknown, direct = true)
    }

    private suspend fun createEvent(a: JSONObject): ToolResult {
        if (!app.calendar.hasPermission()) return ToolResult("Permission agenda manquante.")
        val title = a.optString("title")
        val start = parseDateTime(a.optString("start")) ?: return ToolResult("Date de début invalide.")
        val end = parseDateTime(a.optString("end")) ?: start.plusHours(1)
        val cal = app.calendar.findCalendar(a.optString("calendar"), writableOnly = true) ?: return ToolResult("Aucun agenda modifiable trouvé.")
        var address = a.optString("location").trim()
        var location = ""
        var description = a.optString("description").trim()
        val clientName = a.optString("client").trim()
        var clientLabel = ""
        var siteLabel = ""
        var position: fr.dd.jarvis.societe.Geo.Localisation? = null
        if (clientName.isNotBlank()) {
            val matches = app.societe.data.clients.filter { it.nom.equals(clientName, true) || it.nom.contains(clientName, true) }
            if (matches.size != 1) return ToolResult(if (matches.isEmpty()) "Fiche client introuvable : « $clientName »." else "Plusieurs clients correspondent à « $clientName » : ${matches.joinToString { it.nom }}. Précise lequel.")
            val client = matches.single()
            val siteName = a.optString("installation").trim()
            val site = when {
                siteName.isNotBlank() -> client.sites.firstOrNull { it.nom.equals(siteName, true) || it.nom.contains(siteName, true) }
                client.sites.size == 1 -> client.sites.first()
                client.sites.size > 1 && client.adresse.isBlank() -> return ToolResult("La fiche de ${client.nom} possède plusieurs installations. Précise le nom de l’installation pour placer le bon point GPS dans l’agenda.")
                else -> null
            }
            if (siteName.isNotBlank() && site == null) return ToolResult("Installation « $siteName » introuvable pour ${client.nom}.")
            if (address.isBlank()) address = site?.adresse?.takeIf { it.isNotBlank() } ?: client.adresse
            if (address.isBlank()) return ToolResult("La fiche de ${client.nom} ne contient pas d’adresse. Ajoute une adresse avant de créer ce rendez-vous.")
            position = app.societe.localiser(client, site)
            clientLabel = client.nom
            siteLabel = site?.nom.orEmpty()
        } else if (address.isNotBlank()) {
            position = fr.dd.jarvis.societe.Geo.localiser(address)
        }
        // Google Agenda : le champ Lieu ne contient que le lien GPS ; l’adresse postale reste dans la description.
        val point = position?.takeIf { it.precision.rang >= fr.dd.jarvis.societe.Geo.Precision.LIEU_DIT.rang }?.point
        if (point != null) location = fr.dd.jarvis.societe.Geo.lienGoogleMaps(point)
        val details = buildList {
            if (clientLabel.isNotBlank()) add("Client : $clientLabel")
            if (siteLabel.isNotBlank()) add("Installation : $siteLabel")
            if (address.isNotBlank()) add("Adresse : $address")
            if (point == null && address.isNotBlank()) add("Point GPS non trouvé avec une précision suffisante : le champ Lieu est laissé vide.")
        }
        description = listOf(description, details.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n")
        val id = app.calendar.create(cal.id, title, start, end, location, description)
        return if (id != null) ToolResult("Événement « $title » créé le $start dans « ${app.calendar.displayName(cal)} » (#$id)." +
            if (point != null) " Le champ Lieu contient uniquement le lien GPS cliquable ; l’adresse est dans la description."
            else if (address.isNotBlank()) " Adresse conservée dans la description ; aucun point GPS assez précis n’a été trouvé, le champ Lieu reste vide."
            else "")
        else ToolResult("Échec de la création.")
    }

    private suspend fun updateEvent(a: JSONObject): ToolResult {
        val id = a.optString("event_id").trim().removePrefix("#").toLongOrNull() ?: return ToolResult("Identifiant invalide.")
        val old = app.calendar.event(id) ?: return ToolResult("Événement introuvable ou permission agenda manquante.")
        val rawAddress = a.optString("location").trim().takeIf { it.isNotBlank() }
        var gpsLocation: String? = null
        var newDescription: String? = null
        if (rawAddress != null) {
            val position = fr.dd.jarvis.societe.Geo.localiser(rawAddress)
            val point = position?.takeIf { it.precision.rang >= fr.dd.jarvis.societe.Geo.Precision.LIEU_DIT.rang }?.point
            gpsLocation = point?.let { fr.dd.jarvis.societe.Geo.lienGoogleMaps(it) } ?: ""
            val lines = old.description.lines().filterNot { it.trim().startsWith("Adresse :", true) || it.trim().startsWith("Point GPS non trouvé", true) }
            newDescription = (lines + "Adresse : $rawAddress" + if (point == null) "Point GPS non trouvé avec une précision suffisante : le champ Lieu reste vide." else "")
                .filter { it.isNotBlank() }.joinToString("\n")
        }
        val ok = app.calendar.update(
            id,
            a.optString("title").ifBlank { null },
            parseDateTime(a.optString("start")),
            parseDateTime(a.optString("end")),
            gpsLocation,
            newDescription
        )
        return ToolResult(if (ok) "Événement #$id modifié." + if (rawAddress != null) {
            if (gpsLocation.isNullOrBlank()) " Adresse conservée dans la description ; aucun point GPS assez précis n’a été trouvé, le champ Lieu est vidé."
            else " Le champ Lieu contient uniquement le lien GPS cliquable ; l’adresse est dans la description."
        } else "" else "Rien à modifier ou événement introuvable.")
    }

    private fun couleurDemandee(txt: String): Pair<Boolean, Int?> {
        val t = txt.trim().lowercase()
        if (t in setOf("defaut", "défaut", "default", "origine", "agenda")) return true to null
        val c = fr.dd.jarvis.device.CalendarRepo.parseColor(t)
        return (c != null) to c
    }

    private suspend fun setEventColor(a: JSONObject): ToolResult {
        if (!app.calendar.hasPermission()) return ToolResult("Permission agenda manquante.")
        val (ok, col) = couleurDemandee(a.optString("color"))
        if (!ok) return ToolResult("Couleur non reconnue : « ${a.optString("color")} ». Essaie rouge, orange, jaune, vert, turquoise, bleu, violet, rose, gris ou #RRGGBB.")
        val ids = linkedSetOf<Long>()
        a.optString("event_id").split(Regex("[^0-9]+")).mapNotNull { it.toLongOrNull() }.forEach { ids.add(it) }
        if (ids.isEmpty()) {
            val q = a.optString("title").trim().lowercase()
            val dateTxt = a.optString("date").trim()
            val tous = a.optString("tous").trim().lowercase() in setOf("true", "oui", "1", "vrai")
            val cal = a.optString("calendar").trim().takeIf { it.isNotBlank() }?.let { app.calendar.findCalendar(it, false)?.id }
            if (q.isBlank() && dateTxt.isBlank() && !tous) return ToolResult("Indique les rendez-vous : identifiant, titre, date, ou « tous ».")
            val from = runCatching { LocalDate.parse(dateTxt.take(10)) }.getOrNull() ?: LocalDate.now()
            val days = (a.optString("days").toIntOrNull() ?: if (dateTxt.isNotBlank()) 1 else if (q.isNotBlank()) 30 else 1).coerceIn(1, 120)
            val evs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.calendar.events(from, days) }
                .filter { (q.isBlank() || it.title.lowercase().contains(q)) && (cal == null || it.calId == cal) }
            if (evs.isEmpty()) return ToolResult("Aucun rendez-vous trouvé" + (if (q.isNotBlank()) " pour « $q »" else "") + " du $from sur $days jour(s).")
            evs.forEach { ids.add(it.id) }
        }
        if (ids.size > 60) return ToolResult("Trop de rendez-vous (${ids.size}) : précise une date ou un titre.")
        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ids.map { it to app.calendar.setEventColor(it, col) } }
        val bons = res.count { !it.second.contains("seulement") && !it.second.contains("introuvable") && !it.second.contains("manquante") }
        val locaux = res.count { it.second.contains("JARVIS seulement") }
        return ToolResult("🎨 Couleur changée sur $bons rendez-vous sur ${res.size}" + (if (locaux > 0) " ; $locaux enregistrés dans JARVIS seulement (Google Agenda a refusé : agenda en lecture seule ou sans droit de modification)" else "") +
            (if (res.size <= 3) " — " + res.joinToString(" ; ") { "#${it.first} ${it.second}" } else ""))
    }

    private suspend fun setCalendarColor(a: JSONObject): ToolResult {
        if (!app.calendar.hasPermission()) return ToolResult("Permission agenda manquante.")
        val (ok, col) = couleurDemandee(a.optString("color"))
        if (!ok) return ToolResult("Couleur non reconnue : « ${a.optString("color")} ».")
        val cal = app.calendar.findCalendar(a.optString("calendar"), false) ?: return ToolResult("Agenda introuvable.")
        return ToolResult("${app.calendar.displayName(cal)} : " + app.calendar.setCalendarColor(cal, col))
    }

    private suspend fun deleteEvent(a: JSONObject): ToolResult {
        val id = a.optString("event_id").trim().removePrefix("#").toLongOrNull() ?: return ToolResult("Identifiant invalide.")
        if (!confirm("Supprimer l'événement #$id de l'agenda ?", false)) return ToolResult("Suppression refusée par l'utilisateur.")
        return ToolResult(if (app.calendar.delete(id)) "Événement #$id supprimé." else "Événement introuvable.")
    }

    private fun mailCompte(a: JSONObject): ToolResult {
        val all = store.gmailAccounts.toList() + (if (store.gmailAccounts.isEmpty() && store.mailConfigured()) listOf("imap") else emptyList())
        val action = a.optString("action").lowercase()
        if (action.startsWith("list") || a.optString("compte").isBlank()) {
            return ToolResult(if (all.isEmpty()) "Aucun compte mail configuré." else all.joinToString("\n") { (if (store.mailActive(it)) "🟢 " else "⚪ désactivé : ") + it })
        }
        val q = a.optString("compte").trim().lowercase()
        val cible = all.filter { it.lowercase().contains(q) }
        if (cible.isEmpty()) return ToolResult("Aucun compte ne correspond à « ${a.optString("compte")} ». Comptes : ${all.joinToString(", ")}")
        if (cible.size > 1) return ToolResult("Plusieurs comptes correspondent : ${cible.joinToString(", ")}. Précise l'adresse.")
        val on = action.startsWith("activ") && !action.startsWith("desactiv") && !action.startsWith("désactiv")
        store.setMailActive(cible[0], on)
        return ToolResult(if (on) "🟢 ${cible[0]} activé : JARVIS le prend de nouveau en compte." else "⚪ ${cible[0]} désactivé (conservé) : ignoré par JARVIS, les agents et le point du matin, sauf si tu le demandes explicitement.")
    }

    private suspend fun mailEnvoyer(a: JSONObject): ToolResult {
        if (store.gmailAccounts.isEmpty()) return ToolResult("Envoyer un mail demande un compte Google connecté (Réglages › Comptes › Mails) — pas disponible en IMAP.")
        val dest = a.optString("destinataire").trim()
        val sujet = a.optString("sujet").trim()
        val corps = a.optString("corps")
        if (dest.isBlank() || !dest.contains("@")) return ToolResult("Adresse destinataire manquante ou invalide.")
        val q = a.optString("compte").trim().lowercase()
        val candidats = if (q.isBlank()) store.gmailAccounts.toList() else store.gmailAccounts.filter { it.lowercase().contains(q) }
        if (candidats.isEmpty()) return ToolResult("Aucun compte Gmail ne correspond à « ${a.optString("compte")} ». Comptes : ${store.gmailAccounts.joinToString(", ")}")
        if (candidats.size > 1) return ToolResult("Plusieurs comptes Gmail correspondent : ${candidats.joinToString(", ")}. Précise lequel utiliser.")
        val compte = candidats[0]
        // force=true : une vraie confirmation à chaque fois (même si « toujours confirmer les actions sensibles » est désactivé),
        // et direct=true sur les résultats ci-dessous : le ✅/❌ réel s'affiche tel quel, jamais reformulé par le modèle
        // (qui pouvait annoncer un envoi réussi alors que l'appel avait en fait échoué).
        if (!confirm("Envoyer ce mail depuis $compte ?\n\nÀ : $dest\nObjet : $sujet\n\n${corps.take(500)}", true))
            return ToolResult("Envoi refusé par l'utilisateur.", direct = true)
        return try {
            val id = fr.dd.jarvis.device.GmailApi.envoyer(app, compte, dest, sujet, corps)
            ToolResult("✅ Mail envoyé à $dest depuis $compte (id $id, visible dans le dossier « Envoyés » de $compte).", direct = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult("❌ Envoi impossible : ${e.message ?: e.javaClass.simpleName}", direct = true)
        }
    }

    private suspend fun marquerTraites(a: JSONObject): ToolResult {
        if (store.gmailAccounts.isEmpty()) return ToolResult("Marquer comme traité ou lu demande un compte Google connecté (Réglages › Comptes › Mails).")
        val count = (a.optString("count").toIntOrNull() ?: 30).coerceIn(5, 60)
        val q = a.optString("recherche").trim().lowercase().let { if (it == "tous" || it == "tout") "" else it }
        val traite = !a.optString("traite").equals("false", true)
        val mails = try { app.mail.fetchRecent(count, q.isBlank(), a.optString("compte"), full = true) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return ToolResult("❌ Impossible de lire les mails : ${e.message}")
        }
        val cibles = mails.filter { m -> m.id.isNotBlank() && store.gmailAccounts.any { it.equals(m.account, true) } &&
            (q.isBlank() || m.from.lowercase().contains(q) || m.fromAddr.contains(q) || m.subject.lowercase().contains(q)) }
        if (cibles.isEmpty()) return ToolResult("Aucun mail correspondant.")
        // « Traité » = rangé dans son libellé (hors de la boîte de réception), sauf demande contraire ; « lu » reste dans la boîte.
        val archiver = a.optString("archiver").let { if (it.isBlank()) traite else it.equals("true", true) }
        val quoi = if (traite) "traités" else "lus"
        if (!confirm("Ranger dans leur libellé et marquer ${cibles.size} mail(s) comme $quoi" + (if (archiver) " et les archiver" else "") + " ?\n\n" + cibles.take(8).joinToString("\n") { "• ${it.from} — ${it.subject}" }, false))
            return ToolResult("Refusé par l'utilisateur.")
        // 1) Tri automatique : chaque mail rejoint son libellé (catégorie/expéditeur ; les newsletters dans un seul libellé).
        val tri = try {
            val classes = fr.dd.jarvis.ai.MailTri.classer(app, cibles)
            val n = fr.dd.jarvis.ai.MailTri.ranger(app, classes, archiver)
            "📂 $n rangé(s) : " + classes.groupingBy { it.label }.eachCount().entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key}${if (it.value > 1) " ×${it.value}" else ""}" }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "⚠️ Tri dans les libellés impossible : ${e.message}" }
        // 2) Marquage lu / traité.
        return try {
            val n = fr.dd.jarvis.device.GmailApi.marquerTraites(app, cibles.groupBy({ it.account }, { it.id }), archiver, traite)
            val detail = listOfNotNull(if (traite) "lus + libellé Traité" else null, if (archiver) "archivés" else null)
            ToolResult("✅ $n mail(s) marqués comme $quoi" + (if (detail.isEmpty()) "" else " (" + detail.joinToString(", ") + ")") + ".\n$tri")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ToolResult("❌ ${e.message}\n$tri") }
    }

    private suspend fun routines(a: JSONObject): ToolResult {
        val R = fr.dd.jarvis.device.Routines
        val l = R.liste(app)
        val nom = a.optString("nom").trim()
        fun trouver() = l.firstOrNull { R.norm(it.nom) == R.norm(nom) } ?: l.firstOrNull { nom.isNotBlank() && R.norm(it.nom).contains(R.norm(nom)) }
        fun decoupe(t: String) = t.split(Regex("\\s*(;|\\n)\\s*")).map { it.trim() }.filter { it.isNotBlank() }
        return when (a.optString("action").trim().lowercase()) {
            "lister", "" -> ToolResult(R.presenter(l), direct = true)
            "lancer" -> trouver()?.let { ToolResult(R.executer(app, it, tools = this)) } ?: ToolResult("Aucune routine « $nom ». " + R.presenter(l))
            "supprimer" -> trouver()?.let { r ->
                if (!confirm("Supprimer la routine « ${r.nom} » ?", false)) ToolResult("Annulé.") else { R.supprimer(app, r.id); ToolResult("🗑️ Routine « ${r.nom} » supprimée.") }
            } ?: ToolResult("Aucune routine « $nom ».")
            "activer", "desactiver" -> trouver()?.let { r ->
                R.ajouterOuModifier(app, r.copy(active = a.optString("action").startsWith("act", true)))
                ToolResult("Routine « ${r.nom} » ${if (a.optString("action").startsWith("act", true)) "activée" else "désactivée"}.")
            } ?: ToolResult("Aucune routine « $nom ».")
            "creer", "modifier" -> {
                if (nom.isBlank()) return ToolResult("Donne un nom à la routine.")
                val existante = trouver()
                val decl = a.optString("declencheur").trim().ifBlank { existante?.declencheur ?: "phrase" }
                if (decl !in R.DECLENCHEURS) return ToolResult("Déclencheur inconnu « $decl » : ${R.DECLENCHEURS.keys.joinToString(", ")}.")
                val actions = decoupe(a.optString("actions")).ifEmpty { existante?.actions.orEmpty() }
                if (actions.isEmpty()) return ToolResult("Précise les actions de la routine (séparées par « ; »).")
                val phrases = decoupe(a.optString("phrases")).ifEmpty { existante?.phrases ?: listOf(nom) }
                val r = fr.dd.jarvis.device.Routine(existante?.id ?: R.nouvelId(nom), nom, decl, if (decl == "phrase") phrases else emptyList(),
                    a.optString("heure").trim().ifBlank { existante?.heure.orEmpty() }, a.optString("jours").trim().ifBlank { existante?.jours ?: "tous" }, actions, true)
                R.ajouterOuModifier(app, r)
                ToolResult("✅ Routine enregistrée :\n" + R.presenter(listOf(r)), direct = true)
            }
            else -> ToolResult("Action inconnue : lister, lancer, creer, supprimer, activer, desactiver.")
        }
    }

    private suspend fun heureDepart(a: JSONObject): ToolResult {
        if (!app.calendar.hasPermission()) return ToolResult("Autorise l'agenda pour que je trouve tes rendez-vous.")
        val maintenant = System.currentTimeMillis()
        val q = fr.dd.jarvis.societe.core.normaliser(a.optString("rendez_vous"))
        val e = app.calendar.events(java.time.LocalDate.now(), 7).filter { !it.allDay && it.location.isNotBlank() && it.begin > maintenant }
            .filter { q.isBlank() || fr.dd.jarvis.societe.core.normaliser(it.title + " " + it.location).contains(q) }.minByOrNull { it.begin }
            ?: return ToolResult("Aucun rendez-vous à venir avec une adresse" + (if (q.isNotBlank()) " correspondant à « ${a.optString("rendez_vous")} »" else "") + ".")
        val hq = app.societe.data.societe
        val ici = runCatching { fr.dd.jarvis.device.Locator.point(app) }.getOrNull() ?: if (hq.lat != null && hq.lon != null) fr.dd.jarvis.societe.Geo.Point(hq.lat, hq.lon) else
            return ToolResult("Je ne connais pas ta position (autorise la localisation).")
        val dest = fr.dd.jarvis.societe.Geo.geocode(e.location) ?: return ToolResult("Adresse introuvable : ${e.location}")
        val d = fr.dd.jarvis.work.Depart.duree(app, ici, dest, e.begin) ?: return ToolResult("Itinéraire indisponible.")
        val depart = fr.dd.jarvis.work.Depart.heureDepart(e.begin, d.minutes)
        val f = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.FRENCH)
        fun t(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(f)
        return ToolResult("🚗 **${e.title}** — ${t(e.begin)}\n📍 ${e.location}\n⏱️ Trajet : ${d.minutes} min (${"%.0f".format(d.km)} km)" +
            (if (d.tempsReel) (if (d.retardTrafic >= 3) ", dont ${d.retardTrafic} min de bouchons (trafic en temps réel)" else ", trafic fluide (temps réel)") else if (d.retardTrafic >= 3) ", heure de pointe : +${d.retardTrafic} min estimées" else "") +
            "\n🕗 **Pars à ${t(depart)}** (marge de 10 min)" + (if (depart < maintenant) " — ⚠️ c'est déjà l'heure !" else "") +
            "\n[🧭 Itinéraire](https://www.google.com/maps/dir/?api=1&destination=${java.net.URLEncoder.encode(e.location, "UTF-8")}&travelmode=driving)", direct = true)
    }

    private fun changerVoix(a: JSONObject): ToolResult {
        val vn = fr.dd.jarvis.voice.VoixNaturelle
        val demande = a.optString("voix").trim()
        val description = a.optString("description").trim()
        val liste = vn.PERSONNAGES.groupBy { it.groupe }.entries.joinToString("\n") { (g, l) -> "$g : " + l.joinToString(", ") { it.libelle } }
        if (demande.isBlank() && description.isBlank())
            return ToolResult("🎙️ Voix actuelle : ${vn.personnage(app).libelle}\n\n$liste\n\nOu une voix sur mesure décrite librement (âge, ton, accent).", direct = true)
        val p = vn.trouver("$demande $description".trim())
        if (description.isNotBlank()) {
            // Voix sur mesure : la description libre guide l'interprétation, sur la voix Gemini la plus proche.
            val base = p?.voix ?: vn.trouver(description)?.voix ?: vn.personnage(app).voix
            store.voixNom.set(base); store.voixStyle.set(description); store.voixPersonnage.set(vn.SUR_MESURE)
            vn.reactiver()
            return ToolResult("🎙️ Voix sur mesure (base $base) : « $description ». Je parle maintenant avec cette voix.")
        }
        if (p == null) return ToolResult("Je n'ai pas trouvé de voix correspondant à « $demande ». Voix disponibles :\n$liste")
        if (p.id == vn.SUR_MESURE) { store.voixNom.set(p.voix); store.voixPersonnage.set(vn.SUR_MESURE) } else store.voixPersonnage.set(p.id)
        vn.reactiver()
        return ToolResult("🎙️ Voix changée : ${p.libelle}. Je parle maintenant avec cette voix.")
    }

    private suspend fun trierMails(a: JSONObject): ToolResult {
        if (!store.mailConfigured()) return ToolResult("Les mails ne sont pas configurés (Réglages › Comptes › Mails).")
        val count = (a.optString("count").toIntOrNull() ?: 100).coerceIn(5, 500)
        val mails = try {
            app.mail.fetchRecent(count, a.optString("unread_only").equals("true", true), a.optString("compte"), full = true, everywhere = a.optString("toute_la_boite").equals("true", true))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return ToolResult("❌ Impossible de lire les mails : ${e.message}", direct = true)
        }
        if (mails.isEmpty()) return ToolResult("📭 Aucun mail à trier.", direct = true)
        val classes = try {
            fr.dd.jarvis.ai.MailTri.classer(app, mails)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return ToolResult("❌ Tri impossible : ${e.message}\n\n" + app.mail.localList(mails), direct = true)
        }
        var note = ""
        if (a.optString("ranger").equals("true", true)) {
            if (store.gmailAccounts.isEmpty()) note = "ℹ️ Rangement dans la boîte : disponible avec un compte Google connecté (pas en IMAP)."
            else {
                val archiver = a.optString("archiver").equals("true", true)
                if (!confirm("Ranger ${classes.size} mails dans Gmail avec les libellés Urgent, Clients…" + (if (archiver) " et archiver newsletters/notifications ?" else " ?"), false)) note = "Rangement refusé."
                else note = try {
                    "✅ ${fr.dd.jarvis.ai.MailTri.ranger(app, classes, archiver)} mail(s) rangés dans Gmail (dans tes libellés)."
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "❌ Rangement impossible : ${e.message}" }
            }
        }
        return ToolResult(fr.dd.jarvis.ai.MailTri.presenter(classes, note), direct = true)
    }


    private suspend fun rechercherMails(a: JSONObject): ToolResult {
        if (!store.mailConfigured()) return ToolResult("Les comptes mail ne sont pas configurés (Réglages › Comptes › Mails).", direct = true)
        val count = (a.optString("count").toIntOrNull() ?: 5).coerceIn(1, 20)
        val q = a.optString("compte").trim().lowercase()
        val accounts = if (q.isBlank()) store.gmailAccounts.filter { store.mailActive(it) } else store.gmailAccounts.filter { it.lowercase().contains(q) }
        if (accounts.isEmpty()) {
            val recent = try { app.mail.fetchRecent(500, false, a.optString("compte"), full = true) } catch (e: Exception) { return ToolResult("Recherche mail impossible : ${e.message}", direct = true) }
            val term = a.optString("recherche")
            val sender = a.optString("expediteur")
            val zone = java.time.ZoneId.systemDefault()
            fun borneDate(value: String, fin: Boolean): Long? = runCatching {
                val d = LocalDate.parse(value.take(10))
                (if (fin) d.plusDays(1) else d).atStartOfDay(zone).toInstant().toEpochMilli()
            }.getOrNull()
            val debut = borneDate(a.optString("date_debut"), false)
            val fin = borneDate(a.optString("date_fin"), true)
            val filtered = recent.filter { m ->
                val time = m.date?.time
                (sender.isBlank() || m.from.contains(sender, true) || m.fromAddr.contains(sender, true)) &&
                (term.isBlank() || m.subject.contains(term, true) || m.body.contains(term, true)) &&
                (debut == null || (time != null && time >= debut)) &&
                (fin == null || (time != null && time < fin))
            }
            return ToolResult(if (filtered.isEmpty()) "Aucun mail correspondant dans les 500 derniers messages IMAP consultés." else filtered.take(count).joinToString("\n\n") { "📧 ${it.from} — ${it.subject}\nDate : ${it.date}\n${it.body.ifBlank { it.snippet }}" }, direct = true)
        }
        return try {
            val items = fr.dd.jarvis.device.GmailApi.rechercher(app, accounts, a.optString("expediteur"), a.optString("recherche"), a.optString("date_debut"), a.optString("date_fin"), count)
            if (items.isEmpty()) ToolResult("Aucun email correspondant.", direct = true)
            else ToolResult(items.joinToString("\n\n") { m ->
                "📧 **${m.from}** — ${m.subject}\nCompte : ${m.account}\nDate : ${m.date?.let { java.text.SimpleDateFormat("EEEE dd MMMM yyyy 'à' HH:mm:ss", java.util.Locale.FRENCH).format(it) } ?: "inconnue"}\nID : ${m.id}\nTHREAD : ${m.threadId}\n\n${m.body.ifBlank { m.snippet }}"
            }, direct = true)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ToolResult("Recherche Gmail impossible : ${e.message}", direct = true) }
    }

    private suspend fun repondreEmail(a: JSONObject): ToolResult {
        val account = a.optString("compte").trim()
        val messageId = a.optString("message_id").trim()
        val body = a.optString("texte").trim()
        if (account.isBlank() || messageId.isBlank() || body.isBlank()) return ToolResult("Il faut le compte, l'identifiant du mail et le texte de la réponse.")
        if (store.gmailAccounts.none { it.equals(account, true) }) return ToolResult("Ce compte Gmail n'est pas connecté dans JARVIS.")
        return try {
            val target = fr.dd.jarvis.device.GmailApi.cibleReponse(app, account, messageId)
            val confirmation = "Confirmer l'envoi de cette réponse dans le fil d'origine ?\n\nCompte : $account\nÀ : ${target.to}\nObjet : ${target.subject}\n\n$body"
            if (!confirm(confirmation, true)) ToolResult("Réponse non envoyée : validation refusée.", direct = true)
            else {
                val id = fr.dd.jarvis.device.GmailApi.repondre(app, account, target, body)
                ToolResult("✅ Réponse envoyée dans le fil Gmail d'origine (id $id).", direct = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ToolResult("❌ Réponse non envoyée : ${e.message}", direct = true) }
    }

    private suspend fun summarizeMails(a: JSONObject): ToolResult {
        if (!store.mailConfigured()) return ToolResult("Les mails ne sont pas configurés : renseigner le serveur IMAP dans Réglages › Comptes › Mails.")
        val count = (a.optString("count").toIntOrNull() ?: 15).coerceIn(5, 40)
        val unread = a.optString("unread_only").equals("true", ignoreCase = true)
        val mails = try {
            app.mail.fetchRecent(count, unread, a.optString("compte"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult("❌ Impossible de lire les mails : ${e.message ?: e.javaClass.simpleName}\nVérifie Réglages › Comptes › Mails (compte Google connecté ou IMAP).", direct = true)
        }
        if (mails.isEmpty()) return ToolResult("📭 Aucun mail ${if (unread) "non lu" else "récent"}.", direct = true)
        val text = try {
            llm.agent(Prompts.MAIL + app.memo.pour("mails"), "Aujourd'hui : ${Prompts.now()}\n\n" + app.mail.toPrompt(mails))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            app.mail.localList(mails) + "\n\n_ℹ️ Liste brute — agent IA indisponible : ${(e.message ?: "").take(200)}_"
        }
        return ToolResult(text, direct = true)
    }

    /** Point du jour : agenda + mails, rédigé par l'agent Briefing. */
    suspend fun briefing(): String {
        val consignes = app.memo.pour("briefing").lowercase()
        val sansMails = Regex("(sans|pas de|aucun|ni) (les )?(mail|e-mail|courriel)").containsMatchIn(consignes)
        // « seulement le planning xx » : un agenda nommé dans la consigne est utilisé comme filtre.
        val cible = if (app.calendar.hasPermission()) app.calendar.calendars().map { app.calendar.displayName(it) }.firstOrNull { consignes.contains(it.lowercase()) } else null
        val planning = if (app.calendar.hasPermission()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { planningData(LocalDate.now(), 1, cible) }
                .ifBlank { "Aucun événement aujourd'hui." }
        } else "Permission agenda non accordée."
        val planningDemain = if (app.calendar.hasPermission()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { planningData(LocalDate.now().plusDays(1), 1, cible) }
                .ifBlank { "Aucun événement prévu demain." }
        } else "Permission agenda non accordée."
        val mails = if (sansMails) "(non demandés)" else if (store.mailConfigured()) {
            try {
                val m = app.mail.fetchRecent(15, true)
                if (m.isEmpty()) "Aucun mail non lu." else app.mail.toPrompt(m)
            } catch (e: Exception) {
                "Mails indisponibles : ${e.message}"
            }
        } else "Mails non configurés."
        val impayes = app.societe.impayees()
        val tasksDue = app.tasks.pendingDue(LocalDate.now().plusDays(1))
        val taches = if (tasksDue.isEmpty()) "Aucune tâche en retard ou à échéance demain." else tasksDue.joinToString("\n") { "- " + it.title + (if (it.dueDate.isNotBlank()) " — " + it.dueDate else "") + " (#" + it.id + ")" }
        val user = "Utilisateur : ${store.userName.value.ifBlank { "l'utilisateur" }}\nMaintenant : ${Prompts.now()}\n\n" +
            "=== AGENDA DU JOUR ===\n$planning\n\n=== AGENDA DE DEMAIN ===\n$planningDemain\n\n=== TÂCHES PERSONNELLES ===\n$taches\n\n=== MAILS NON LUS ===\n$mails\n\n=== FACTURES IMPAYÉES ===\n${impayes.ifBlank { "Aucune." }}"
        val texte = llm.agent(Prompts.briefing() + app.memo.pour("briefing"), user)
        // Archive chaque point du matin dans son propre dossier du coffre Obsidian (un fichier par jour).
        runCatching { app.obsidian.saveIn("Points du matin", LocalDate.now().toString(), texte) }
        return texte
    }

    private suspend fun searchContacts(a: JSONObject): ToolResult {
        if (!app.contacts.hasPermission()) return ToolResult("Permission contacts manquante : l'autoriser dans l'onglet Contacts.")
        val list = app.contacts.search(a.optString("query"), 15)
        if (list.isEmpty()) return ToolResult("Aucun contact trouvé.")
        return ToolResult(list.joinToString("\n---\n") { it.toPrompt() })
    }

    private suspend fun createContact(a: JSONObject): ToolResult {
        if (!app.contacts.hasPermission()) return ToolResult("Permission contacts manquante.")
        val name = a.optString("name")
        if (name.isBlank()) return ToolResult("Nom manquant.")
        app.contacts.create(name, a.optString("phone"), a.optString("email"), a.optString("company"), a.optString("note"))
        return ToolResult("Contact « $name » créé.")
    }

    private suspend fun updateContact(a: JSONObject): ToolResult {
        if (!app.contacts.hasPermission()) return ToolResult("Permission contacts manquante.")
        val nomDemande = a.optString("name").trim()
        if (nomDemande.isBlank()) return ToolResult("Nom du contact manquant.")
        fun cleNom(value: String) = java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("\\s+"), " ")
        val found = app.contacts.search(nomDemande, 20)
        if (found.isEmpty()) return ToolResult("Contact introuvable.")
        val exact = found.filter { cleNom(it.name) == cleNom(nomDemande) }
        if (exact.size > 1) return ToolResult("Plusieurs contacts portent exactement ce nom : ${exact.joinToString { it.name + " (ID " + it.id + ")" }}. Précise lequel.")
        if (exact.isEmpty() && found.size > 1) return ToolResult("Plusieurs contacts correspondent : ${found.joinToString { it.name }}. Précise le nom exact.")
        val c = exact.singleOrNull() ?: found.singleOrNull() ?: return ToolResult("Contact ambigu : précise son nom complet.")
        val ok = app.contacts.update(
            c.id, a.optString("new_name").ifBlank { null }, a.optString("phone").ifBlank { null },
            a.optString("email").ifBlank { null }, a.optString("company").ifBlank { null }, a.optString("note").ifBlank { null }
        )
        return ToolResult(if (ok) "Contact « ${c.name} » mis à jour." else "Échec de la mise à jour.")
    }

    private suspend fun deleteContact(a: JSONObject): ToolResult {
        if (!app.contacts.hasPermission()) return ToolResult("Permission contacts manquante.")
        val found = app.contacts.search(a.optString("name"), 5)
        if (found.isEmpty()) return ToolResult("Contact introuvable.")
        if (found.size > 1 && found.none { it.name.equals(a.optString("name"), true) })
            return ToolResult("Plusieurs contacts correspondent : ${found.joinToString { it.name }}. Préciser le nom.")
        val c = found.firstOrNull { it.name.equals(a.optString("name"), true) } ?: found.first()
        if (!confirm("Supprimer DÉFINITIVEMENT le contact « ${c.name} » du téléphone ?", true)) return ToolResult("Suppression refusée par l'utilisateur.")
        val ok = app.contacts.delete(c.id)
        return ToolResult(if (ok) "🗑️ Contact « ${c.name} » supprimé du téléphone." else "❌ Échec de la suppression du contact « ${c.name} ».")
    }

    private suspend fun createCard(a: JSONObject): ToolResult {
        if (!app.contacts.hasPermission()) return ToolResult("Permission contacts manquante.")
        val found = app.contacts.search(a.optString("name"), 5)
        val c = found.firstOrNull { it.name.equals(a.optString("name"), true) } ?: found.firstOrNull()
            ?: return ToolResult("Contact introuvable.")
        return ToolResult(buildCard(c, a.optString("sous_dossier")), direct = true)
    }

    /**
     * Génère la fiche via l'agent Contacts puis l'écrit dans le dossier Obsidian (Contacts, ou un sous-dossier).
     * Cherche d'abord une fiche existante pour ce nom (n'importe où sous Contacts, insensible aux
     * accents/majuscules/espaces) afin de ne jamais créer de doublon : si elle existe déjà et que les
     * coordonnées n'ont pas changé, on la renvoie telle quelle sans repasser par l'IA (plus rapide,
     * aucun token consommé) ; sinon on met à jour CETTE fiche (même emplacement) au lieu d'en créer une autre.
     */
    suspend fun buildCard(c: fr.dd.jarvis.device.ContactInfo, sousDossier: String = ""): String {
        val cheminExistant = app.obsidian.ficheExistante("Contacts", c.name)
        if (cheminExistant != null) {
            val contenuActuel = app.obsidian.readNote(cheminExistant)
            if (contenuActuel != null && coordonneesInchangees(contenuActuel, c)) {
                return "✅ Fiche « ${c.name} » déjà à jour : coffre Obsidian › $cheminExistant\n\n---\n\n$contenuActuel"
            }
        }
        val md = llm.agent(Prompts.CONTACT_CARD + app.memo.pour("contacts", "fiche", "fiches"), "Date du jour : ${LocalDate.now()}\n\n${c.toPrompt()}")
        val where = if (cheminExistant != null) {
            // Réécrit la fiche trouvée à son emplacement actuel (même sous-dossier) : jamais de doublon.
            app.obsidian.writeNote(cheminExistant, md, append = false)
        } else {
            val dossier = "Contacts" + sousDossier.trim().trim('/').let { if (it.isBlank()) "" else "/$it" }
            app.obsidian.saveIn(dossier, c.name, md)
        }
        return "✅ Fiche « ${c.name} » enregistrée : $where\n\n---\n\n$md"
    }

    /** Vrai si les coordonnées du contact (téléphones/emails/société) apparaissent déjà telles quelles dans la fiche. */
    private fun coordonneesInchangees(fiche: String, c: fr.dd.jarvis.device.ContactInfo): Boolean {
        val f = fiche
        if (c.company.isNotBlank() && !f.contains(c.company)) return false
        if (c.phones.any { it.isNotBlank() && !f.contains(it) }) return false
        if (c.emails.any { it.isNotBlank() && !f.contains(it) }) return false
        return true
    }

    private suspend fun phoneAction(a: JSONObject): ToolResult {
        val action = a.optString("action").trim()
        val value = a.optString("value")
        if (action in PhoneControl.SENSITIVE && !confirm("Exécuter l'action « $action »" + (if (value.isNotBlank()) " ($value)" else "") + " sur le téléphone ?", false)) {
            return ToolResult("Action refusée par l'utilisateur.")
        }
        return ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.phone.perform(action, value) })
    }

    private suspend fun runShell(a: JSONObject): ToolResult {
        val cmd = a.optString("command").trim()
        if (cmd.isBlank()) return ToolResult("Commande vide.")
        if (!fr.dd.jarvis.device.PhoneShell.ready(app)) return ToolResult("Aucun accès adb : activer l'ADB intégré (ou Shizuku) dans Réglages › Téléphone › Contrôle.")
        if (!confirm("Exécuter cette commande shell ?\n\n$cmd", true)) return ToolResult("Commande refusée par l'utilisateur.")
        return ToolResult(fr.dd.jarvis.device.PhoneShell.exec(app, cmd))
    }
}
