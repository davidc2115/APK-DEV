package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.PhoneControl
import fr.dd.jarvis.device.ShizukuShell
import fr.dd.jarvis.societe.SocieteTools
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
        put(tool("create_event", "Crée un rendez-vous dans l'agenda.",
            mapOf(
                "title" to prop("Titre du rendez-vous."),
                "start" to prop("Début, yyyy-MM-dd'T'HH:mm (heure locale)."),
                "end" to prop("Fin, yyyy-MM-dd'T'HH:mm. Par défaut début + 1 h."),
                "calendar" to prop("Nom de l'agenda (optionnel)."),
                "location" to prop("Lieu (optionnel)."),
                "description" to prop("Notes (optionnel).")
            ), listOf("title", "start")))
        put(tool("update_event", "Modifie un rendez-vous existant (identifiant visible dans le planning : #id).",
            mapOf(
                "event_id" to prop("Identifiant numérique de l'événement."),
                "title" to prop("Nouveau titre (optionnel)."),
                "start" to prop("Nouveau début yyyy-MM-dd'T'HH:mm (optionnel)."),
                "end" to prop("Nouvelle fin (optionnel)."),
                "location" to prop("Nouveau lieu (optionnel).")
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
        put(tool("trier_mails", "Analyse les derniers mails, les classe par catégorie (Urgent, Clients, Factures, Chantiers, Administratif, Notifications, Newsletters, Perso) et peut les ranger dans Gmail (libellés Urgent, Clients…).",
            mapOf(
                "compte" to prop("Compte mail précis (adresse ou partie), même s'il est désactivé. Vide = comptes actifs seulement."),
                "count" to prop("Nombre de mails à trier (5 à 500). Par défaut 100 ; mets 300-500 pour « toute la boîte »."),
                "unread_only" to prop("« true » pour ne trier que les non lus."),
                "toute_la_boite" to prop("« true » pour inclure aussi les mails déjà archivés (pas seulement la boîte de réception) : utile pour (re)trier tout un compte."),
                "ranger" to prop("« true » pour appliquer les libellés dans Gmail. Un re-tri remplace les anciens libellés de catégorie par les bons."),
                "archiver" to prop("« true » pour aussi retirer de la boîte de réception les newsletters et notifications (avec ranger).")
            )))
        put(tool("itineraire", "Itinéraire et temps de route. Sans destination : tournée des rendez-vous (avec lieu) de l'agenda d'un jour, enchaînés depuis la position ou le siège, avec heures de départ. Avec destination : trajet simple.",
            mapOf(
                "destination" to prop("Adresse de destination (trajet simple). Vide pour la tournée de l'agenda."),
                "depuis" to prop("Départ : « position » (défaut), « siege » ou une adresse."),
                "date" to prop("Jour de la tournée yyyy-MM-dd. Par défaut aujourd'hui."),
                "restants" to prop("« true » (défaut) pour ne garder que les rendez-vous restants aujourd'hui, « false » pour toute la journée."),
                "calendar" to prop("Nom d'un agenda précis (optionnel).")
            )))
        put(tool("marquer_mails_traites", "Marque des mails comme traités (lus + libellé Traité, archivés si demandé). Compte Google requis.",
            mapOf(
                "compte" to prop("Compte mail précis (adresse ou partie), même s'il est désactivé. Vide = comptes actifs seulement."),
                "recherche" to prop("Texte à retrouver dans l'expéditeur ou l'objet. Vide ou « tous » = tous les mails récents non lus."),
                "count" to prop("Nombre de mails récents à considérer (5 à 60). Par défaut 30."),
                "archiver" to prop("« true » pour aussi les retirer de la boîte de réception.")
            )))
        put(tool("lire_sms", "Lit les SMS récents (reçus et envoyés).",
            mapOf(
                "count" to prop("Nombre de SMS (1 à 30). Par défaut 10."),
                "contact" to prop("Nom ou numéro pour filtrer (optionnel)."),
                "non_lus" to prop("« true » pour les non lus seulement.")
            )))
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
                "modele" to prop("Optionnel : « flux » (flux-2-dev, le plus réaliste/précis), « flux-klein » (flux-2-klein, qualité proche mais plus rapide) ou « flux-schnell » (le plus rapide, qualité moindre). Par défaut le modèle choisi dans Réglages › Images.")), listOf("prompt")))
        put(tool("animer_image", "Anime une image déjà générée par JARVIS avec fal.ai / Wan 2.2 (image vers vidéo). Nécessite une clé fal.ai configurée dans Réglages › Vidéo IA. Le MP4 est téléchargé puis enregistré dans JARVIS/Vidéos IA et dans Obsidian. Génération payante selon le tarif fal.ai.",
            mapOf("image" to prop("Nom exact de l’image (jarvis-img:...) ou « derniere » pour la plus récente."), "prompt" to prop("Mouvement souhaité : caméra, sujet, ambiance, gestes (optionnel)."), "duree" to prop("Durée du clip en secondes, 0.5 à 4.5 (défaut 3.5).")), listOf("image")))
        put(tool("agenda_afficher", "Affiche ou masque durablement un agenda entier (« ne plus afficher le planning de Thomas », « réaffiche-le »). Un agenda masqué disparaît du planning, de l'itinéraire, du point du jour et de l'écran Agenda tant qu'on ne le réaffiche pas.",
            mapOf("agenda" to prop("Nom (ou partie du nom) de l'agenda."), "afficher" to prop("true pour réafficher, false pour masquer.")), listOf("agenda", "afficher")))
        put(tool("creer_fichier", "Crée un fichier téléchargeable que l'utilisateur peut ouvrir ou partager : pdf, docx (Word), xlsx (Excel), csv, vcf (contacts ; contenu « tous » ou nom = exporte les contacts du téléphone), ics, txt, md, html, json, xml, zip… Le texte (docx/pdf/html) accepte du Markdown (# titres, - puces, tableaux |a|b|). Pour xlsx/csv : contenu en lignes « a;b;c » ou JSON [[..],[..]] (une formule commence par =). Pour un PDF avec des images déjà générées (generer_image) — livre de coloriage, planche, album — utilise le paramètre « images » : chaque image devient une page entière, dans l'ordre donné, APRÈS le texte de « contenu ».",
            mapOf("nom" to prop("Nom du fichier sans extension."), "format" to prop("pdf, docx, xlsx, csv, vcf, ics, txt, md, html, json, xml, zip…"),
                "contenu" to prop("Contenu du fichier (voir la description). Pour vcf : texte vCard, ou « tous »."), "titre" to prop("Titre du document (optionnel)."),
                "feuille" to prop("Nom de la feuille Excel (optionnel)."), "recherche" to prop("vcf : filtre sur le nom des contacts à exporter (optionnel)."),
                "inclure" to prop("zip : noms des fichiers déjà créés à inclure, séparés par des virgules."),
                "images" to prop("Pour format=pdf uniquement : noms de fichiers d'images déjà générées par generer_image (ex. img_20261031_120000_123.png, visibles dans jarvis-img: des résultats précédents), séparés par une virgule ou un retour à la ligne — une par page, dans l'ordre. Ajoute une légende par image avec « nomFichier.png::légende ». Si tu ne connais pas les noms exacts, mets « dernieres » (10 dernières), « dernieres:N » ou « toutes » plutôt que de deviner, ou appelle d'abord fichiers_generes.")),
            listOf("nom", "format")))
        put(tool("fichiers_generes", "Liste les fichiers déjà créés par JARVIS (images de generer_image, documents de creer_fichier), les plus récents en premier, avec leur nom exact. À utiliser dès que l'utilisateur fait référence à une image ou un fichier déjà généré sans te redonner son nom exact, avant de dire que tu ne le retrouves pas.",
            mapOf("quoi" to prop("images, fichiers (documents) ou tous (par défaut)."), "max" to prop("Nombre maximum à lister (par défaut 20).")), listOf()))
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
        put(tool("alarme_supprimer", "Supprime une alarme de l'Horloge (par heure HH:MM ou par libellé).",
            mapOf("heure" to prop("Heure de l'alarme (optionnel)."), "libelle" to prop("Libellé de l'alarme (optionnel)."))))
        put(tool("minuteur_creer", "Lance un minuteur avec compte à rebours en notification, SANS ouvrir aucune application.",
            mapOf("secondes" to prop("Durée totale en secondes (ex. 10 min = 600)."), "libelle" to prop("Nom du minuteur (optionnel).")), listOf("secondes")))
        put(tool("chronometre", "Chronomètre sans ouvrir d'application (notification avec compteur).", mapOf("action" to prop("demarrer (défaut) | arreter | etat"))))
        put(tool("alarmes_liste", "Liste les alarmes et minuteurs en cours."))
         put(tool("horloge_diagnostic", "Vérifie les alarmes, minuteurs, notifications et Shizuku en cas de panne."))
        put(tool("applis_musique", "Liste les applications de musique RÉELLEMENT installées sur le téléphone (jamais une liste inventée)."))
        put(tool("jouer_musique", "Lance la lecture d'un titre, artiste ou playlist dans une application de musique du téléphone, en arrière-plan. Si l'application n'est pas précisée et qu'aucune n'est mémorisée et qu'il y en a plusieurs d'installées, l'outil demande laquelle (liste réelle) : repose alors la question à l'utilisateur (et s'il veut « toujours » ou « une seule fois »), puis rappelle l'outil.",
            mapOf("requete" to prop("Titre, artiste ou style à écouter."), "appli" to prop("Nom exact d'une application RENVOYÉE PAR applis_musique ou déjà mémorisée (jamais une application supposée sans l'avoir vérifiée)."), "toujours" to prop("« true » pour toujours utiliser cette application pour la musique."))))
        put(tool("controle_media", "Contrôle la lecture en cours : play, pause, next, previous, stop.", mapOf("action" to prop("play | pause | next | previous | stop")), listOf("action")))
        put(tool("naviguer", "Lance la navigation GPS vers une adresse et démarre l'itinéraire (Waze, Google Maps ou autre appli). Si l'application n'est pas précisée et qu'aucune n'est mémorisée, l'outil demande laquelle : repose alors la question à l'utilisateur (et s'il veut « toujours » celle-ci ou « une seule fois »), puis rappelle l'outil.",
            mapOf("destination" to prop("Adresse ou lieu."), "mode" to prop("voiture (défaut), marche ou velo"),
                "appli" to prop("Application : Waze, Google Maps, ou autre (optionnel)."),
                "toujours" to prop("« true » si l'utilisateur veut toujours utiliser cette application pour ce type de demande ; « false » pour cette fois seulement.")), listOf("destination")))
        put(tool("appli_par_defaut", "Définit (ou efface) l'application utilisée par défaut pour un type de demande.",
            mapOf("type" to prop("navigation | musique"), "appli" to prop("Nom de l'application, ou « aucune » pour redemander à chaque fois.")), listOf("type", "appli")))
        put(tool("obsidian_chercher", "Cherche du texte dans les notes du coffre Obsidian (noms et contenu).", mapOf("requete" to prop("Texte recherché.")), listOf("requete")))
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
        put(tool("memoire_lire", "Affiche les consignes enregistrées dans la mémoire JARVIS."))
        put(tool("reglage_jarvis", "Consulte ou modifie directement les réglages non sensibles de JARVIS depuis le chat ou la voix. N'utilise jamais cet outil pour les clés API, mots de passe, tokens, adresses serveur ou paramètres de sécurité.",
            mapOf("action" to prop("lire | modifier"), "reglage" to prop("confirmations | analyse_gemini | conversation | reponses_vocales | ecoute_continue | annonces | agents | agents_mails | agents_factures | agents_planning | agents_notifications | notifications_banniere | bips | duree_conversation"), "valeur" to prop("true/false pour les interrupteurs ; nombre de secondes pour duree_conversation.")), listOf("action", "reglage")))
        put(tool("memoire_oublier", "Supprime la consigne d'un sujet.", mapOf("sujet" to prop("Sujet à oublier.")), listOf("sujet")))
        put(tool("factures_impayees", "Liste les factures émises non encore payées (avec retards)."))
        put(tool("export_kml", "Génère un fichier KML (Google Earth / Google My Maps) avec les points GPS des clients et installations, enregistré dans Obsidian et partageable."))
        put(tool("ma_position", "Donne la position GPS actuelle du téléphone (adresse approximative)."))
        put(tool("phone_action", "Agit sur le téléphone. Actions : torch_on, torch_off, open_app, open_url, volume, wifi_on, wifi_off, bluetooth_on, bluetooth_off, data_on, data_off, brightness, call, back, home, recents, notifications, quick_settings, lock, screenshot, read_screen, click_text, type_text, scroll_down, scroll_up, tap.",
            mapOf(
                "action" to prop("Nom de l'action."),
                "value" to prop("Paramètre : nom d'app, URL, pourcentage, numéro, texte à cliquer ou à saisir, ou « x,y » pour tap.")
            ), listOf("action")))
        put(tool("run_shell", "Exécute une commande shell sur le téléphone via adb/Shizuku (réservé aux besoins que phone_action ne couvre pas).",
            mapOf("command" to prop("Commande shell.")), listOf("command")))
        put(tool("stockage_telephone", "Accède au stockage du téléphone (Téléchargements, Films, Musique, DCIM, etc., en dehors du dossier privé de JARVIS) : liste un dossier, lit un fichier texte, renomme, déplace, supprime, crée un dossier. Pratique pour réorganiser une bibliothèque de fichiers (ex. noms de films compatibles Plex).",
            mapOf(
                "action" to prop("lister | lire | renommer | deplacer | supprimer | dossier"),
                "chemin" to prop("Chemin relatif à la racine du stockage (ex. « Movies/film.avi », vide = racine)."),
                "destination" to prop("renommer/deplacer uniquement : le nouveau chemin complet (ex. « Movies/Dirty Dancing (1987).avi »).")
            ), listOf("action")))
    }

    /** Les outils Société ne sont proposés au modèle que si la conversation les concerne (économie de contexte). */
    fun schemaFor(recentUserText: String): JSONArray {
        val soc = SocieteTools.concerned(recentUserText); val srv = ServeurTools.concerned(recentUserText)
        val dis = DiscordTools.concerned(recentUserText); val tik = TikTokTools.concerned(recentUserText)
        if (!soc && !srv && !dis && !tik) return schema
        return JSONArray().also { out ->
            for (i in 0 until schema.length()) out.put(schema.get(i))
            if (soc) societe.schema.forEach { out.put(it) }
            if (srv) serveur.schema.forEach { out.put(it) }
            if (dis) discord.schema.forEach { out.put(it) }
            if (tik) tiktok.schema.forEach { out.put(it) }
        }
    }

    fun schemaAll(): JSONArray = JSONArray().also { out ->
        for (i in 0 until schema.length()) out.put(schema.get(i))
        societe.schema.forEach { out.put(it) }
        serveur.schema.forEach { out.put(it) }
        discord.schema.forEach { out.put(it) }
        tiktok.schema.forEach { out.put(it) }
    }

    // ------------------------------------------------------------------ exécution

    suspend fun execute(name: String, a: JSONObject): ToolResult { return when (name) {
        "get_planning" -> getPlanning(a)
        "create_event" -> createEvent(a)
        "update_event" -> updateEvent(a)
        "delete_event" -> deleteEvent(a)
        "set_event_color" -> setEventColor(a)
        "set_calendar_color" -> setCalendarColor(a)
        "summarize_mails" -> summarizeMails(a)
        "morning_briefing" -> ToolResult(briefing(), direct = true)
        "trier_mails" -> trierMails(a)
        "mail_compte" -> mailCompte(a)
        "mail_envoyer" -> mailEnvoyer(a)
        "marquer_mails_traites" -> marquerTraites(a)
        "lire_sms" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fr.dd.jarvis.device.Telephony.lire(app, (a.optString("count").toIntOrNull() ?: 10).coerceIn(1, 30), a.optString("contact"), a.optString("non_lus").equals("true", true))
        }, direct = true)
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
        "animer_image" -> try {
            val image = fr.dd.jarvis.ai.ImageGen.fichier(app, a.optString("image"))
                ?: return ToolResult("❌ Image introuvable : ${a.optString("image")}. Utilise fichiers_generes pour retrouver son nom exact.")
            val (video, loc) = fr.dd.jarvis.ai.ImageToVideoGen.generate(app, image, a.optString("prompt"), a.optString("duree").toDoubleOrNull() ?: 3.5)
            ToolResult("🎬 Vidéo créée : ${video.name}\n\n[▶ Lire la vidéo](jarvis-video-ia:${video.name})\n\n📁 Enregistrée dans Jarvis › Vidéos IA" + (if (loc.isNotBlank()) "\n🗂️ Obsidian : $loc" else ""), direct = true)
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
                else { fr.dd.jarvis.work.TacheWorker.lancer(app, d); ToolResult("🛠️ Tâche lancée en arrière-plan : « ${d.take(120)} ». Tu recevras une notification à la fin (même si l'app est fermée).") }
            }
        }
        "alarme_creer" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.device.Horloge.creerAlarme(app, a.optString("heure"), a.optString("libelle"), a.optString("jours")) }, direct = true)
        "alarme_supprimer" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.device.Horloge.supprimerAlarme(app, a.optString("heure"), a.optString("libelle")) })
        "minuteur_creer" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.device.Horloge.creerMinuteur(app, a.optString("secondes").toDoubleOrNull()?.toInt() ?: 0, a.optString("libelle")) }, direct = true)
        "chronometre" -> ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.dd.jarvis.device.Horloge.chronometre(app, a.optString("action")) }, direct = true)
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
                ToolResult(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.phone.playMusic(a.optString("requete"), pkgChoisi) }, direct = true)
            }
        }
        "controle_media" -> ToolResult(app.phone.mediaKey(a.optString("action")))
        "naviguer" -> {
            var appli = a.optString("appli").trim()
            val toujours = a.optString("toujours").trim().lowercase() in setOf("true", "oui", "1", "vrai")
            if (appli.isBlank()) appli = app.store.appPrefs["navigation"].orEmpty()
            val dispo = app.phone.navApps().keys
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
            val chemin = a.optString("chemin"); val append = a.optString("ajouter").equals("true", true)
            val existe = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.obsidian.readNote(chemin) != null }
            if (existe && !append && !confirm("Remplacer le contenu de la note « $chemin » ?", false)) ToolResult("Refusé par l'utilisateur.")
            else ToolResult("✅ Note enregistrée : " + kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.obsidian.writeNote(chemin, a.optString("contenu"), append) })
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
                if (ok) ToolResult("🗑️ Fiche « $chemin » supprimée.")
                else ToolResult("❌ La fiche « $chemin » n'a pas pu être supprimée (problème d'accès au coffre Obsidian).")
            }
        }
        "lire_notifications" -> {
            val svc = fr.dd.jarvis.service.JarvisNotificationListener
            if (!svc.active) ToolResult("Accès aux notifications non activé : Réglages › Autorisations › « Accès aux notifications ».")
            else {
                val n = (a.optString("count").toIntOrNull() ?: 15).coerceIn(1, 40)
                val app0 = a.optString("appli").trim().lowercase()
                val since = a.optString("minutes").toLongOrNull()?.let { System.currentTimeMillis() - it * 60_000 } ?: 0L
                val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH)
                val l = svc.snapshot().filter { it.time >= since && (app0.isBlank() || it.app.lowercase().contains(app0) || it.pkg.lowercase().contains(app0)) }.take(n)
                ToolResult(if (l.isEmpty()) "Aucune notification." else "# 🔔 Notifications\n\n" + l.joinToString("\n") { "- **${it.app}** _(${fmt.format(java.util.Date(it.time))})_ ${it.titre} — ${it.texte.take(200)}" }, direct = true)
            }
        }
        "memoire_ajouter" -> ToolResult("✅ Consigne « ${a.optString("sujet")} » enregistrée (" + app.memo.set(a.optString("sujet"), a.optString("texte")) + ").")
        "reglage_jarvis" -> reglerJarvis(a)
        "memoire_lire" -> ToolResult(app.memo.all().entries.joinToString("\n\n") { "**${it.key}** : ${it.value}" }.ifBlank { "Aucune consigne enregistrée." }, direct = true)
        "memoire_oublier" -> ToolResult(if (app.memo.remove(a.optString("sujet"))) "Consigne oubliée." else "Aucune consigne pour ce sujet.")
        "factures_impayees" -> ToolResult(app.societe.impayees().ifBlank { "✅ Aucune facture impayée." }, direct = true)
        "export_kml" -> ToolResult(app.societe.exporterKml(), direct = true)
        "ma_position" -> ToolResult(fr.dd.jarvis.device.Locator.describe(app))
        "phone_action" -> phoneAction(a)
        "run_shell" -> runShell(a)
        "stockage_telephone" -> {
            val act = a.optString("action").lowercase()
            if (act == "supprimer" && !confirm("Supprimer « ${a.optString("chemin")} » du stockage du téléphone ?", true)) ToolResult("Refusé par l'utilisateur.")
            else try { ToolResult(fr.dd.jarvis.ai.Stockage.action(act, a.optString("chemin"), a.optString("destination"))) }
            catch (e: Exception) { ToolResult("❌ ${e.message ?: e.javaClass.simpleName}") }
        }
        in SocieteTools.NAMES -> societe.execute(name, a)
        in ServeurTools.NAMES -> serveur.execute(name, a)
        in DiscordTools.NAMES -> discord.execute(name, a)
        in TikTokTools.NAMES -> tiktok.execute(name, a)
        else -> ToolResult("Outil inconnu : $name")
    } }

    private fun reglerJarvis(a: JSONObject): ToolResult {
        val action = a.optString("action").trim().lowercase()
        val key = a.optString("reglage").trim().lowercase()
        val raw = a.optString("valeur").trim().lowercase()
        val bool = when(raw) { "true", "oui", "on", "active", "activé", "activer" -> true; "false", "non", "off", "désactive", "désactivé", "desactive", "desactivé" -> false; else -> null }
        val s = app.store
        val labels = mapOf("confirmations" to "confirmation des actions sensibles", "analyse_gemini" to "analyse Gemini", "conversation" to "mode conversation", "reponses_vocales" to "réponses vocales", "ecoute_continue" to "écoute continue", "annonces" to "annonces vocales", "agents" to "agents autonomes", "agents_mails" to "agent mails", "agents_factures" to "agent factures/devis", "agents_planning" to "agent planning", "agents_notifications" to "agent notifications", "notifications_banniere" to "bannières de notification", "bips" to "bips d'écoute")
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

    private suspend fun createEvent(a: JSONObject): ToolResult {
        if (!app.calendar.hasPermission()) return ToolResult("Permission agenda manquante.")
        val title = a.optString("title")
        val start = parseDateTime(a.optString("start")) ?: return ToolResult("Date de début invalide.")
        val end = parseDateTime(a.optString("end")) ?: start.plusHours(1)
        val cal = app.calendar.findCalendar(a.optString("calendar"), writableOnly = true) ?: return ToolResult("Aucun agenda modifiable trouvé.")
        val id = app.calendar.create(cal.id, title, start, end, a.optString("location"), a.optString("description"))
        return if (id != null) ToolResult("Événement « $title » créé le $start dans « ${app.calendar.displayName(cal)} » (#$id).")
        else ToolResult("Échec de la création.")
    }

    private suspend fun updateEvent(a: JSONObject): ToolResult {
        val id = a.optString("event_id").trim().removePrefix("#").toLongOrNull() ?: return ToolResult("Identifiant invalide.")
        val ok = app.calendar.update(
            id,
            a.optString("title").ifBlank { null },
            parseDateTime(a.optString("start")),
            parseDateTime(a.optString("end")),
            a.optString("location").ifBlank { null },
            null
        )
        return ToolResult(if (ok) "Événement #$id modifié." else "Rien à modifier ou événement introuvable.")
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
        if (store.gmailAccounts.isEmpty()) return ToolResult("Envoyer un mail demande un compte Google connecté (Réglages › Mails) — pas disponible en IMAP.")
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
        if (store.gmailAccounts.isEmpty()) return ToolResult("Marquer comme traité demande un compte Google connecté (Réglages › Mails).")
        val count = (a.optString("count").toIntOrNull() ?: 30).coerceIn(5, 60)
        val q = a.optString("recherche").trim().lowercase().let { if (it == "tous" || it == "tout") "" else it }
        val mails = try { app.mail.fetchRecent(count, q.isBlank(), a.optString("compte")) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return ToolResult("❌ Impossible de lire les mails : ${e.message}")
        }
        val cibles = mails.filter { it.id.isNotBlank() && (q.isBlank() || it.from.lowercase().contains(q) || it.subject.lowercase().contains(q)) }
        if (cibles.isEmpty()) return ToolResult("Aucun mail correspondant.")
        val archiver = a.optString("archiver").equals("true", true)
        if (!confirm("Marquer ${cibles.size} mail(s) comme traités" + (if (archiver) " et les archiver" else "") + " ?\n\n" + cibles.take(8).joinToString("\n") { "• ${it.from} — ${it.subject}" }, false))
            return ToolResult("Refusé par l'utilisateur.")
        return try {
            val n = fr.dd.jarvis.device.GmailApi.marquerTraites(app, cibles.groupBy({ it.account }, { it.id }), archiver)
            ToolResult("✅ $n mail(s) marqués comme traités (lus + libellé Traité${if (archiver) ", archivés" else ""}).")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ToolResult("❌ ${e.message}") }
    }

    private suspend fun trierMails(a: JSONObject): ToolResult {
        if (!store.mailConfigured()) return ToolResult("Les mails ne sont pas configurés (Réglages › Mails).")
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
                    "✅ ${fr.dd.jarvis.ai.MailTri.ranger(app, classes, archiver)} mail(s) rangés dans Gmail (libellés Urgent, Clients…)."
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "❌ Rangement impossible : ${e.message}" }
            }
        }
        return ToolResult(fr.dd.jarvis.ai.MailTri.presenter(classes, note), direct = true)
    }

    private suspend fun summarizeMails(a: JSONObject): ToolResult {
        if (!store.mailConfigured()) return ToolResult("Les mails ne sont pas configurés : renseigner le serveur IMAP dans Réglages › Mails.")
        val count = (a.optString("count").toIntOrNull() ?: 15).coerceIn(5, 40)
        val unread = a.optString("unread_only").equals("true", ignoreCase = true)
        val mails = try {
            app.mail.fetchRecent(count, unread, a.optString("compte"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult("❌ Impossible de lire les mails : ${e.message ?: e.javaClass.simpleName}\nVérifie Réglages › Mails (compte Google connecté ou IMAP).", direct = true)
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
        val planning = if (app.calendar.hasPermission()) {
            // « seulement le planning xx » : un agenda nommé dans la consigne est utilisé comme filtre
            val cible = app.calendar.calendars().map { app.calendar.displayName(it) }.firstOrNull { consignes.contains(it.lowercase()) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { planningData(LocalDate.now(), 1, cible) }
                .ifBlank { "Aucun événement aujourd'hui." }
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
        val user = "Utilisateur : ${store.userName.value.ifBlank { "l'utilisateur" }}\nMaintenant : ${Prompts.now()}\n\n" +
            "=== AGENDA DU JOUR ===\n$planning\n\n=== MAILS NON LUS ===\n$mails\n\n=== FACTURES IMPAYÉES ===\n${impayes.ifBlank { "Aucune." }}"
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
        val found = app.contacts.search(a.optString("name"), 5)
        if (found.isEmpty()) return ToolResult("Contact introuvable.")
        if (found.size > 1 && found.none { it.name.equals(a.optString("name"), true) })
            return ToolResult("Plusieurs contacts correspondent : ${found.joinToString { it.name }}. Préciser le nom.")
        val c = found.firstOrNull { it.name.equals(a.optString("name"), true) } ?: found.first()
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
        if (!fr.dd.jarvis.device.PhoneShell.ready(app)) return ToolResult("Aucun accès adb : activer l'ADB intégré (ou Shizuku) dans Réglages › Contrôle du téléphone.")
        if (!confirm("Exécuter cette commande shell ?\n\n$cmd", true)) return ToolResult("Commande refusée par l'utilisateur.")
        return ToolResult(fr.dd.jarvis.device.PhoneShell.exec(app, cmd))
    }
}
