package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray

/**
 * « Que sais-tu faire ? », « liste complète de tes capacités », « que sais-tu faire avec les mails ? », « liste tous tes
 * outils » : réponse directe, sans IA ni Hermès, à partir d'un CATALOGUE qui couvre chaque outil de Jarvis (un test
 * vérifie qu'aucun outil n'y manque), avec l'état des intégrations et ce qu'Hermès sait faire en plus.
 */
object Capacites {
    /** Qui traite la demande : réflexe instantané (sans IA), Jarvis (IA rapide + ses outils), Hermès (moteur approfondi). */
    enum class Voie(val icone: String) { REFLEXE("⚡"), JARVIS("🤖"), HERMES("🪽") }

    data class Item(val texte: String, val exemples: List<String>, val outils: List<String>, val voie: Voie = Voie.JARVIS)
    data class Categorie(val id: String, val emoji: String, val titre: String, val mots: Regex, val items: List<Item>)

    /** Demande reconnue : domaines voulus (vide = tout) et liste technique des outils ou non. */
    data class Demande(val categories: List<String>, val technique: Boolean)

    private fun r(s: String) = Regex("\\b($s)")
    private fun i(texte: String, exemples: String, outils: String, voie: Voie = Voie.JARVIS) =
        Item(texte, exemples.split('|').map { it.trim() }.filter { it.isNotBlank() }, outils.split(',').map { it.trim() }.filter { it.isNotBlank() }, voie)

    val CATEGORIES: List<Categorie> = listOf(
        Categorie("telephone", "📱", "Téléphone", r("telephone|portable|smartphone|ecran|applis?|applications?|batterie|wi ?fi|bluetooth|torche|volume|luminosite|vibreur|silencieux|ne pas deranger|stockage"), listOf(
            i("Lampe torche, volume, luminosité, Wi-Fi, Bluetooth, données mobiles, verrouillage, capture d'écran", "allume la torche|volume à 40 %|coupe le Wi-Fi|verrouille le téléphone", "phone_action", Voie.REFLEXE),
            i("Mode sonore : ne pas déranger, vibreur, sonnerie + vibreur", "mets le téléphone en vibreur|active ne pas déranger|remets la sonnerie", "mode_sonore", Voie.REFLEXE),
            i("Mode d'alimentation du téléphone : économie, équilibré, performance", "passe le téléphone en mode économie", "telephone_mode_energie", Voie.REFLEXE),
            i("Ouvrir une application installée, lister les applications", "ouvre WhatsApp|lance Spotify", "phone_action", Voie.REFLEXE),
            i("Heure, date, niveau de batterie", "quelle heure est-il ?|combien de batterie ?", "", Voie.REFLEXE),
            i("Plusieurs actions dans une seule phrase", "allume la torche et mets le téléphone en vibreur", "", Voie.REFLEXE),
            i("Regarder l'écran et piloter n'importe quelle application (cliquer, écrire, faire défiler, vérifier)", "regarde mon écran|dans Leboncoin, cherche un vélo", "voir_ecran,piloter_telephone,run_shell"),
            i("Diagnostic du téléphone : batterie, température, stockage, mémoire, permissions, alarmes", "fais un diagnostic du téléphone|pourquoi mes alarmes ne sonnent pas ?", "phone_health_report,horloge_diagnostic"),
            i("Stockage du téléphone : chercher, ranger, déplacer, renommer des fichiers et des dossiers", "trouve le dossier coloriage pompier|range les photos de chantier", "stockage_telephone", Voie.REFLEXE),
            i("Lire les notifications reçues (WhatsApp, banque, mails…)", "lis mes notifications", "lire_notifications", Voie.REFLEXE),
            i("Montre connectée : relayer Jarvis sur la montre", "active Jarvis sur la montre", "montre"),
        )),
        Categorie("communication", "📞", "Appels, SMS et contacts", r("appels?|appeler|telephoner|sms|textos?|contacts?|numeros?"), listOf(
            i("Appeler un contact ou un numéro (avec confirmation)", "appelle Angeline|compose le 06 12 34 56 78", "appeler_contact", Voie.REFLEXE),
            i("Envoyer un SMS (le texte exact est confirmé avant l'envoi)", "envoie un SMS à Paul : j'arrive dans 10 minutes", "envoyer_sms", Voie.REFLEXE),
            i("Lire, rechercher les SMS et y répondre", "lis mes derniers SMS|retrouve le SMS de Martin sur le devis|réponds à Paul que c'est d'accord", "lire_sms,rechercher_sms,repondre_sms"),
            i("Contacts : chercher, créer, modifier, supprimer, nettoyer tous les numéros", "quel est le numéro de Paul ?|crée le contact Jean Dupont 06…", "search_contacts,create_contact,update_contact,delete_contact,contacts_normaliser_numeros"),
            i("Fiches contact dans le coffre Obsidian", "crée la fiche contact de Martin", "create_contact_card,supprimer_contact_card"),
        )),
        Categorie("mails", "📧", "Mails", r("mails?|e ?mails?|courriels?|gmail|boite de reception|boite mail"), listOf(
            i("Trier les mails dans tes libellés (non lus par défaut, tous sur demande) et changer la règle", "trie mes mails|trie tous mes mails|désormais trie tous les mails", "trier_mails,regler_tri_mails", Voie.REFLEXE),
            i("Marquer comme lus, non lus ou traités : tous, ou seulement certains", "marque tous mes mails comme lus|marque les mails d'Amazon comme traités", "marquer_mails_traites", Voie.REFLEXE),
            i("Résumer, rechercher, répondre dans le fil, envoyer un mail", "résume mes mails|retrouve le mail des impôts|réponds à ce mail…", "summarize_mails,rechercher_mails,repondre_email,mail_envoyer"),
            i("Grand ménage de toute la boîte Gmail", "fais le grand ménage de ma boîte mail", "grand_menage_mails"),
            i("Comptes mails : lister, activer, désactiver", "désactive le compte pro", "mail_compte"),
        )),
        Categorie("agenda", "📅", "Agenda, rappels et alarmes", r("agenda|planning|rendez vous|rdv|calendrier|rappels?|alarmes?|reveils?|minuteurs?|chrono|taches?|journee|programme"), listOf(
            i("Planning du jour, de demain ou de la semaine", "mon planning de demain|qu'est-ce que j'ai cette semaine ?", "get_planning", Voie.REFLEXE),
            i("Créer, modifier, déplacer, supprimer, colorer des rendez-vous ; mettre le GPS dans le lieu", "ajoute un rendez-vous chez Martin jeudi à 14 h|mets le rendez-vous de demain en rouge", "create_event,update_event,delete_event,set_event_color,set_calendar_color,agenda_lieu_gps"),
            i("Agendas : lister, masquer, réafficher", "ne plus afficher le planning de Thomas", "agendas_liste,agenda_afficher"),
            i("Alarmes, minuteurs, chronomètre (sans ouvrir d'application)", "réveille-moi à 6 h 30|minuteur de 10 minutes|lance le chrono", "alarme_creer,alarme_modifier,alarme_supprimer,alarmes_liste,minuteur_creer,chronometre", Voie.REFLEXE),
            i("Rappels, une fois ou répétés, et tout ce qui est programmé", "rappelle-moi d'appeler Martin demain à 9 h|qu'est-ce qui est programmé ?", "rappel_creer,rappels_liste,rappel_supprimer", Voie.REFLEXE),
            i("Tâches personnelles", "ajoute la tâche commander les panneaux|mes tâches", "tache_ajouter,taches_lister,tache_terminer"),
            i("Point du jour et journal de bord", "fais-moi le point du jour|journal d'hier", "morning_briefing,journal_de_bord", Voie.REFLEXE),
            i("Heure de départ pour un rendez-vous (trafic compris)", "à quelle heure dois-je partir pour mon prochain rendez-vous ?", "heure_depart"),
        )),
        Categorie("trajets", "🧭", "Position, trajets et météo", r("position|localisation|gps|ou suis je|trajets?|itineraire|navigation|waze|maps|route|tournee|meteo|temps qu il fait|pluie|ou est"), listOf(
            i("Ma position avec l'adresse précise", "où suis-je ?", "ma_position", Voie.REFLEXE),
            i("Où se trouve une personne (Home Assistant)", "où est Angeline ?", "ha_position", Voie.REFLEXE),
            i("Navigation dans Waze ou Google Maps", "emmène-moi chez Martin|guide-moi jusqu'à Dijon", "naviguer,appli_par_defaut", Voie.REFLEXE),
            i("Itinéraire, distances et tournée optimisée des clients", "itinéraire de ma tournée de demain|tournée optimisée Martin, Durand, Petit", "itineraire,tournee_optimisee"),
            i("Météo ici ou ailleurs, maintenant ou les jours suivants", "quel temps fait-il ?|météo de demain à Dijon", "meteo", Voie.REFLEXE),
        )),
        Categorie("musique", "🎵", "Musique, films et séries", r("musique|chansons?|playlist|spotify|deezer|plex|films?|series?|episodes?|tele|television|tv|cinema|video"), listOf(
            i("Contrôler la lecture : pause, reprise, suivante, précédente, volume, ce qui joue", "pause|chanson suivante|reprends la musique", "controle_media", Voie.REFLEXE),
            i("Écouter un titre, un artiste ou une playlist dans tes applis de musique", "joue Daft Punk sur Spotify|mets la playlist sport", "jouer_musique,applis_musique,musique_etat,musique_commander,musique_rechercher,musique_resultats,musique_choisir,musique_bibliotheque,musique_action,musique_file", Voie.REFLEXE),
            i("Plex : lancer un film ou une série sur la télé, pause, reprise, avance, volume", "lance Avatar sur la télé|mets en pause Plex", "plex_lancer,plex_controle,plex_appareils", Voie.REFLEXE),
            i("Plex : rechercher, parcourir, récents, à reprendre, détails, vus / non vus, playlists, sessions, bibliothèques, serveur", "qu'est-ce qui a été ajouté sur Plex ?|marque la saison 2 comme vue", "plex_rechercher,plex_parcourir,plex_recents,plex_en_cours,plex_details,plex_marquer,plex_playlists,plex_sessions,plex_bibliotheques,plex_serveur,plex_supprimer,plex_api"),
            i("Films du disque dur branché sur la box", "cherche Interstellar sur le disque de la box", "rechercher_videos_box,lire_video_box"),
        )),
        Categorie("maison", "🏠", "Maison connectée", r("maison|domotique|home ?assistant|lumieres?|lampes?|volets?|stores?|prises?|chauffage|poele|thermostat|routines?|modes?"), listOf(
            i("Allumer, éteindre, ouvrir, fermer : lumières, volets, prises, ventilateurs, télé", "allume la lumière du salon|ferme les volets de la chambre", "ha_service,ha_entites,ha_etat", Voie.REFLEXE),
            i("Chauffage, poêle, thermostat (d'après tes notes)", "mets le poêle à 20 degrés", "ha_service,notes_lire"),
            i("Tableaux de bord, configuration et administration de Home Assistant", "crée un tableau de bord énergie|recharge les automatisations", "ha_dashboard,ha_config,ha_admin"),
            i("Modes de Jarvis (cinéma, absence…) qui pilotent plusieurs appareils d'un coup", "crée un mode cinéma qui ferme les volets et baisse la lumière|active le mode absence", "modes_jarvis", Voie.REFLEXE),
            i("Routines déclenchées par une phrase (« Jarvis, je pars »)", "crée une routine je pars|lance la routine du matin", "routines,routine_contextuelle", Voie.REFLEXE),
        )),
        Categorie("reseau", "🌐", "Box, réseau et impression", r("freebox|box|reseau|wi ?fi|vpn|ports?|redirections?|appareils?|imprim\\w*|ip publique|acces a distance"), listOf(
            i("Freebox : état, Wi-Fi, appareils connectés (renommer, supprimer), VPN, redirections, appels, téléchargements, redémarrage", "qui est connecté à la box ?|supprime les appareils sans nom de la box|active le VPN", "freebox", Voie.REFLEXE),
            i("Disque dur de la box : parcourir les fichiers", "montre le dossier Téléchargements de la box", "box_disque", Voie.REFLEXE),
            i("Réseau Wi-Fi : scanner, nommer et examiner les appareils, adresse IP publique", "scanne le réseau|nomme 192.168.1.20 Imprimante bureau", "reseau_scanner,reseau_infos,appareils_liste,appareil_nommer,appareil_sonder,appareil_page,ip_publique,routeur_diagnostiquer"),
            i("Rendre un appareil du Wi-Fi joignable à distance (bail fixe, redirection, adresse publique)", "rends la caméra accessible à distance", "appareil_acces_distant", Voie.REFLEXE),
            i("Appareils liés à Jarvis et comment il les joint, en local et à distance (onglet Wi-Fi › Liés à Jarvis)", "quels appareils contrôles-tu ?", "", Voie.REFLEXE),
            i("Imprimantes : trouver, imprimer un document de Jarvis", "trouve les imprimantes|imprime la facture F-2026-012", "imprimantes_trouver,imprimer"),
        )),
        Categorie("societe", "🧾", "Société", r("societe|entreprise|clients?|factures?|devis|paie|bulletins?|salaires?|salaries?|interventions?|chantiers?|installations?|tarifs?|urssaf|site web|site internet|kml|google earth"), listOf(
            i("Clients et installations : fiches, GPS, carte Google Earth, import depuis l'agenda", "crée la fiche client Martin|mets à jour la fiche Martin avec ma position", "client_ecrire,client_fiche,client_supprimer,installation_supprimer,clients_gps,clients_exporter_kml,export_kml,kml_couleurs_set,analyser_planning_clients,distance_deplacement", Voie.REFLEXE),
            i("Devis et factures : créer, marquer envoyé / accepté / payé, envoyer par mail, annuler, supprimer, impayés", "fais un devis pour Martin|la facture F-2026-012 est payée|supprime toutes les factures", "document_creer,document_action,document_email,documents_supprimer,factures_impayees", Voie.REFLEXE),
            i("Fiches de paie : générer, lister, imprimer, supprimer ; salariés et dirigeant", "fais la fiche de paie d'octobre de Paul|supprime le bulletin de septembre", "paie,bulletins,employe_ecrire", Voie.REFLEXE),
            i("Interventions photovoltaïques : rapports PDF, historique, bilan", "enregistre l'intervention d'aujourd'hui chez Martin|bilan des interventions", "intervention_enregistrer,intervention_modifier,intervention_supprimer,interventions_liste,interventions_synthese"),
            i("Informations, tarifs, remises, résultat, barèmes URSSAF, réserve, présentation des documents", "quel est le résultat de la société ?|ajoute le tarif nettoyage", "societe_info_get,societe_info_set,societe_liste,tarif_set,tarif_delete,regles_set,societe_resultat,societe_baremes,reserve_bilan,presentation_modifier"),
            i("Site web de la société : créer, modifier, publier", "crée le site web de la société", "site_web", Voie.REFLEXE),
        )),
        Categorie("notes", "📝", "Notes, fichiers et mémoire", r("notes?|carnet|fichiers?|documents?|dossiers?|obsidian|coffre|pdf|word|excel|memoire|souviens|conversations?|graphiques?"), listOf(
            i("Carnet de notes : prendre note, relire, supprimer", "prends note que le code du portail est changé|relis mes notes sur le poêle", "note_prendre,notes_lire,note_supprimer", Voie.REFLEXE),
            i("Coffre Obsidian : chercher, lire, écrire, ranger, supprimer", "cherche « onduleur » dans mes notes|range les images coloriage dans un sous-dossier", "obsidian_chercher,obsidian_lire,obsidian_lister,obsidian_ecrire,obsidian_fichiers,obsidian_supprimer,graphe_suggere_lien", Voie.REFLEXE),
            i("Créer des fichiers (PDF, Word, Excel, CSV…) et retrouver ceux déjà créés", "fais un tableau Excel de mes clients|quels fichiers as-tu créés ?", "creer_fichier,fichiers_generes"),
            i("Documents importés : lire, analyser, résumer", "résume le PDF que je t'ai envoyé", "documents_lister,document_lire,document_analyser"),
            i("Graphiques dans la conversation", "fais un graphique du chiffre d'affaires par mois", "graphique"),
            i("Mémoire : consignes, faits (lieux, surnoms), préférences apprises", "souviens-toi que ma femme c'est Angeline|qu'as-tu retenu ?", "memoire_ajouter,memoire_lire,memoire_oublier,memoire_faits,memoire_apprendre,memoire_apprentissages"),
            i("Conversations : retrouver et reprendre une ancienne conversation", "reprends la conversation sur le devis Martin", "conversations"),
        )),
        Categorie("creation", "🎨", "Images, vidéos et réseaux sociaux", r("images?|dessins?|coloriages?|photos?|videos?|montages?|tiktok|discord|reels?"), listOf(
            i("Générer des images, les analyser, les animer en vidéo", "dessine un dragon en coloriage|anime cette image", "generer_image,analyser_image,animer_image"),
            i("Vidéos montées de la durée voulue (plans IA, tes photos et vidéos, musique, voix off)", "crée une vidéo de 40 s sur le chantier|fais 3 TikToks sur les panneaux solaires", "generer_video,video_montage,tiktok_video", Voie.REFLEXE),
            i("TikTok : connexion, profil, statistiques, analyse, script", "analyse mes vidéos TikTok|écris un script TikTok sur l'entretien des panneaux", "tiktok_connexion,tiktok_deconnexion,tiktok_profil,tiktok_mes_videos,tiktok_analyse,tiktok_script"),
            i("Analyser une vidéo partagée vers Jarvis", "que penses-tu de cette vidéo ?", "analyser_video"),
            i("Discord : serveurs, salons, messages, rôles et permissions", "lis les derniers messages du salon général|crée le rôle Modérateur", "discord_guildes,discord_salons,discord_audit_serveur,discord_messages_lire,discord_messages_rechercher,discord_fil_lire,discord_message_envoyer,discord_roles,discord_role_creer,discord_role_modifier,discord_role_supprimer,discord_role_position,discord_role_attribuer,discord_salon_permission"),
        )),
        Categorie("serveur", "🖥️", "Serveur (quand tu dis « serveur »)", r("serveur|ssh|docker|debian|conteneurs?|github"), listOf(
            i("Commandes, scripts et fichiers sur le serveur, ou dans un conteneur", "sur le serveur, montre l'espace disque", "ssh_commande,ssh_script,ssh_fichier,serveur_fichiers", Voie.HERMES),
            i("Services (Docker, systemd), tâches longues, diagnostic, ports", "redémarre le conteneur Plex sur le serveur|diagnostic du serveur", "serveur_service,serveur_tache,serveur_diagnostic,serveur_scan_ports", Voie.HERMES),
            i("Sauvegardes et remise en service de Home Assistant", "liste les sauvegardes de Home Assistant sur le serveur", "ha_sauvegarde", Voie.HERMES),
            i("GitHub : fichiers, commits, versions", "dernière version de Home Assistant sur GitHub", "github"),
        )),
        Categorie("jarvis", "⚙️", "Jarvis lui-même", r("jarvis|reglages?|parametres?|voix|ia|cles? api|gemini|groq|agents?|mode (eco|economie|performance)|reprise|recommence"), listOf(
            i("Mode de Jarvis : économie, équilibré, performance", "passe Jarvis en mode économie", "", Voie.REFLEXE),
            i("Réglages et voix de Jarvis", "active l'écoute continue|prends une voix de femme", "reglage_jarvis,jarvis_reglages,changer_voix"),
            i("Moteurs d'IA : état, test des clés, configuration, deuxième avis", "teste les clés Gemini|quels modèles utilises-tu ?", "jarvis_ai_status,tester_cles_api,configurer_fournisseur_ia,jarvis_ai_agent"),
            i("Agents autonomes et tâches longues en arrière-plan", "état des agents|trie tous mes mails en arrière-plan", "agents_centre,tache_arriere_plan"),
            i("Reprendre une demande interrompue", "recommence|reprends là où tu en étais", "", Voie.REFLEXE),
            i("Cette liste, par domaine ou en détail", "que sais-tu faire avec les mails ?|liste tous tes outils", "", Voie.REFLEXE),
        )),
        Categorie("hermes", "🪽", "Hermès (moteur approfondi)", r("hermes|internet|web|recherche|automatis\\w*|surveill\\w*|redaction|code"), listOf(
            i("Recherches sur internet, explications, conseils, rédaction, code", "cherche sur internet le prix des onduleurs|explique-moi le TURPE", "hermes_deleguer", Voie.HERMES),
            i("Automatismes et surveillances (tous les matins, dès que je reçois…)", "tous les lundis, envoie-moi les factures impayées|préviens-moi quand Plex s'arrête", "hermes_deleguer", Voie.HERMES),
            i("Gestion d'Hermès : état, démarrage, skills, plugins et connecteurs MCP", "état d'Hermès|installe le skill météo", "hermes,gerer_extension_hermes"),
        )),
    )

    /** Outils cités par le catalogue (un test vérifie qu'il couvre tous les outils de Jarvis). */
    val outilsCouverts: Set<String> get() = CATEGORIES.flatMap { c -> c.items.flatMap { it.outils } }.toSet()

    // ------------------------------------------------------------------ reconnaissance de la demande

    private const val VERBES_POUVOIR = "tu sais|sais tu|tu peux|peux tu|tu es capable de|es tu capable de|tu saurais|saurais tu|tu pourrais|pourrais tu|tu fais|est ce que tu sais|est ce que tu peux"
    private const val SUJET = "(?:\\s+(?:avec|pour|en|sur|dans|niveau|cote|question|concernant|a propos d[eu]?s?|au niveau d[eu]?s?|en matiere de)\\s+(.{1,60}))?"
    private val INTRO = "^(?:(?:dis moi|alors|bon|ok|et|mais|jarvis|montre moi|explique moi|rappelle moi|au fait)\\s+)*"
    private val GENERIQUE = Regex(
        INTRO + "(?:(?:qu est ce que|qu est ce qu|que|quoi|qu)\\s+)?(?:$VERBES_POUVOIR)\\s+faire(?:\\s+quoi)?" +
            "(?:\\s+(?:d autre|de plus|encore|exactement|en tout|au juste|concretement|pour moi|comme choses?|comme actions?|de beau|en fait))*$SUJET$"
    )
    private val CAPABLE = Regex(
        INTRO + "(?:de quoi (?:es tu|tu es|est tu) capable|a quoi (?:tu sers|sers tu|tu peux (?:me )?servir|peux tu (?:me )?servir)|qu est ce que je peux te demander|" +
            "que (?:puis je|je peux) te demander|je peux te demander quoi|comment (?:je peux |on peut |puis je )?(?:t utiliser|me servir de toi|bien t utiliser)|" +
            "aide|help|(?:quelles sont |c est quoi )?tes (?:capacites|fonctionnalites|competences|fonctions|possibilites|commandes|aptitudes|outils))$SUJET$"
    )
    private val LISTE = Regex(
        "\\b(liste|lister|listing|inventaire|catalogue|panorama|recapitulatif|recap|tour d horizon|apercu|resume|donne|montre|affiche|enumere|presente|detaille|quelles? sont|quels sont|c est quoi|fais)\\b" +
            ".{0,40}\\b(?:tes|vos|toutes tes|tous tes|les) (?:capacites|fonctionnalites|fonctions|competences|possibilites|aptitudes|commandes|outils|pouvoirs|actions possibles)\\b" +
            "|\\b(liste|listing|inventaire|recapitulatif)\\b.{0,30}\\b(?:de |d )?(?:tout )?ce que tu (?:sais|peux|es capable de) faire\\b"
    )

    /** Demande de la liste des capacités (et domaine visé), ou null. */
    fun demande(texte: String): Demande? {
        val n = normaliser(texte).replace(Regex("\\b(s il te plait|s il vous plait|stp|svp|jarvis)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        // Adressée à Hermès (« Hermès, que sais-tu faire ? ») : c'est lui qui répond.
        if (n.isBlank() || n.split(' ').size > 25 || Regex("^(?:(?:dis|ok|eh|et) )?hermes\\b").containsMatchIn(n)) return null
        val m = GENERIQUE.matchEntire(n) ?: CAPABLE.matchEntire(n)
        val sujet = when {
            m != null -> m.groupValues.last()
            LISTE.containsMatchIn(n) -> n
            else -> return null
        }
        val technique = Regex("\\boutils?\\b|\\btechnique\\b").containsMatchIn(n)
        val cats = if (sujet.isBlank()) emptyList() else CATEGORIES.filter { it.mots.containsMatchIn(sujet) }.map { it.id }
        // « Que sais-tu faire avec ce fichier ? » : un objet précis (pièce jointe…), pas la liste.
        if (m != null && Regex("^(ce|cet|cette|ces|ca|cela|celui|celle|le mien|la mienne)\\b").containsMatchIn(sujet.trim())) return null
        // « Que sais-tu faire avec X » où X n'est aucun domaine : ce n'est pas une demande de liste (« … avec ce fichier »).
        if (m != null && sujet.isNotBlank() && cats.isEmpty() && !Regex("^(d autre|de plus|en tout|tout|exactement|pour moi|ici|la|maintenant|aujourd hui)$").matches(sujet.trim())) return null
        return Demande(cats, technique)
    }

    // ------------------------------------------------------------------ réponse

    /** Nom de chaque domaine dans la phrase dite à voix haute. */
    private val ORAL = mapOf("telephone" to "le téléphone", "communication" to "les appels et les SMS", "mails" to "les mails", "agenda" to "l'agenda et les rappels",
        "trajets" to "les trajets et la météo", "musique" to "la musique et Plex", "maison" to "la maison connectée", "reseau" to "la box et le réseau",
        "societe" to "la société", "notes" to "les notes et les fichiers", "creation" to "les images et les vidéos", "serveur" to "le serveur",
        "jarvis" to "mes propres réglages", "hermes" to "Hermès")

    /** Fin du texte DIT à voix haute : la suite (la liste détaillée) est seulement affichée. */
    const val FIN_ORAL = fr.dd.jarvis.voice.TexteParle.FIN_ORAL

    private fun ligne(it: Item) = "- ${it.voie.icone} **${it.texte}**" + (if (it.exemples.isEmpty()) "" else " — " + it.exemples.take(3).joinToString(" · ") { "« $it »" })

    /** État d'une intégration pour l'en-tête d'un domaine (null : rien à signaler). */
    private fun etat(app: JarvisApp, id: String): String? = runCatching {
        val s = app.store
        fun etat(ok: Boolean, nom: String) = if (ok) "✅ $nom connecté" else "⚙️ $nom à configurer dans les Réglages"
        when (id) {
            "maison" -> etat(Serveur.haConfigure(app), "Home Assistant")
            "mails" -> if (s.gmailAccounts.isNotEmpty()) "✅ ${s.gmailAccounts.size} compte(s) Gmail" else "⚙️ aucun compte Gmail connecté (Réglages › Comptes › Mails)"
            "musique" -> etat(s.plexToken.value.isNotBlank(), "Plex")
            "reseau" -> etat(s.freeboxAppToken.value.isNotBlank(), "Freebox")
            "serveur" -> etat(s.sshHost.value.isNotBlank(), "Serveur SSH")
            "creation" -> listOf(
                if (s.tiktokAccessToken.value.isNotBlank()) "✅ TikTok" else "⚙️ TikTok non connecté",
                if (s.discordToken.value.isNotBlank()) "✅ Discord" else "⚙️ Discord non configuré",
                if (s.falApiKey.value.isNotBlank()) "✅ vidéos IA (fal.ai)" else "⚙️ vidéos IA : clé fal.ai à ajouter"
            ).joinToString(" · ")
            "notes" -> if (s.obsidianUri.value.isNotBlank()) "✅ coffre Obsidian relié" else "⚙️ coffre Obsidian à choisir dans les Réglages"
            "hermes" -> when {
                s.hermesMode.value == "off" -> "⚪ désactivé (Jarvis seul)"
                !s.hermesInstalle.value -> "⚙️ pas encore installé"
                fr.dd.jarvis.hermes.Hermes.enLigneConnu() -> "🟢 en ligne"
                else -> "⚪ hors ligne pour l'instant"
            }
            else -> null
        }
    }.getOrNull()

    /** Ce qu'Hermès sait faire en plus (skills, connecteurs, plugins, automatismes synchronisés). */
    private fun extrasHermes(app: JarvisApp): String = runCatching {
        val skills = app.memo.skillsHermes()
        val auto = app.memo.automatismesHermes()
        val mcp = app.memo.connecteursHermes()
        val plugins = app.memo.pluginsHermes()
        buildString {
            if (skills.isNotEmpty()) append("\n- 🪽 **Compétences ajoutées (${skills.size})** : ").append(skills.take(25).joinToString(" · ") { "${it.first}" + (it.second.takeIf { d -> d.isNotBlank() }?.let { d -> " (${d.take(70).trimEnd()}${if (d.length > 70) "…" else ""})" } ?: "") })
                .append(if (skills.size > 25) " · …" else "")
            if (mcp.isNotEmpty()) append("\n- 🪽 **Connecteurs MCP** : ").append(mcp.joinToString(", "))
            if (plugins.isNotEmpty()) append("\n- 🪽 **Plugins** : ").append(plugins.joinToString(", "))
            if (auto.isNotEmpty()) append("\n- 🪽 **Automatismes en place (${auto.size})** : ").append(auto.take(12).joinToString(" · ") { "${it.optString("nom")} (${it.optString("quand")})" })
        }
    }.getOrDefault("")

    /** Éléments créés par l'utilisateur (modes, routines). */
    private fun persos(app: JarvisApp, id: String): String = runCatching {
        when (id) {
            "maison" -> {
                val modes = fr.dd.jarvis.securite.Systemes.liste(app).map { it.nom }
                val routines = fr.dd.jarvis.device.Routines.liste(app).map { it.nom }
                (if (modes.isNotEmpty()) "\n- 🧩 **Tes modes** : " + modes.joinToString(", ") else "") +
                    (if (routines.isNotEmpty()) "\n- ⚙️ **Tes routines** : " + routines.joinToString(", ") else "")
            }
            else -> ""
        }
    }.getOrDefault("")

    /** Réponse complète : un résumé (dit à voix haute), puis la liste détaillée (affichée). */
    fun repondre(app: JarvisApp, d: Demande, outils: JSONArray): String {
        if (d.technique) return technique(outils, d.categories)
        val cats = if (d.categories.isEmpty()) CATEGORIES else CATEGORIES.filter { it.id in d.categories }
        val oral = if (cats.size == 1) {
            val c = cats.first()
            "Pour « ${c.titre.substringBefore(" (")} », je sais : " + c.items.joinToString(" ; ") { it.texte.substringBefore(" (").substringBefore(" :").replaceFirstChar { x -> x.lowercase() } } + "."
        } else "Voici tout ce que je sais faire, dans ${cats.size} domaines : " + cats.map { ORAL[it.id] ?: it.titre }.let { l -> l.dropLast(1).joinToString(", ") + " et " + l.last() } +
            ". Pour le détail d'un seul domaine, demande par exemple « que sais-tu faire avec les mails ? » ; pour la liste technique, « liste tous tes outils »."
        val nbOutils = outils.length()
        val entete = "# 🧰 " + (if (cats.size == 1) "Ce que je sais faire : ${cats.first().titre}" else "Tout ce que je sais faire") + "\n" +
            "_⚡ instantané, sans IA · 🤖 Jarvis (IA rapide avec ses outils) · 🪽 Hermès (moteur approfondi)_" +
            (if (cats.size > 1) "\n_$nbOutils outils · les actions passent par Jarvis ; si Jarvis n'y arrive pas, Hermès prend le relais tout seul._" else "")
        val corps = cats.joinToString("\n") { c ->
            "\n## ${c.emoji} ${c.titre}" + (etat(app, c.id)?.let { " — $it" } ?: "") + "\n" +
                c.items.joinToString("\n") { ligne(it) } + persos(app, c.id) + (if (c.id == "hermes") extrasHermes(app) else "")
        }
        return "$oral\n$FIN_ORAL\n\n$entete\n$corps"
    }

    /** Liste technique : chaque outil, rangé par domaine, avec sa description courte. */
    fun technique(outils: JSONArray, categories: List<String> = emptyList()): String {
        val desc = (0 until outils.length()).mapNotNull { outils.optJSONObject(it)?.optJSONObject("function") }
            .associate { it.optString("name") to it.optString("description") }
        fun court(s: String) = s.replace(Regex("\\s+"), " ").let { t -> Regex("^(.{20,140}?[.!?])(\\s|$)").find(t)?.groupValues?.get(1) ?: (t.take(140) + if (t.length > 140) "…" else "") }
        val places = mutableSetOf<String>()
        val cats = if (categories.isEmpty()) CATEGORIES else CATEGORIES.filter { it.id in categories }
        val blocs = cats.mapNotNull { c ->
            val noms = c.items.flatMap { it.outils }.distinct().filter { it in desc && it !in places }
            places += noms
            if (noms.isEmpty()) null else "\n## ${c.emoji} ${c.titre} (${noms.size})\n" + noms.joinToString("\n") { "- `$it` — ${court(desc.getValue(it))}" }
        }
        val autres = if (categories.isEmpty()) desc.keys.filter { it !in places }.sorted() else emptyList()
        val total = places.size + autres.size
        return "J'ai $total outils, rangés par domaine dans la liste affichée.\n$FIN_ORAL\n\n# 🛠️ Mes outils ($total)\n" + blocs.joinToString("\n") +
            (if (autres.isEmpty()) "" else "\n\n## Autres (${autres.size})\n" + autres.joinToString("\n") { "- `$it` — ${court(desc.getValue(it))}" })
    }
}
