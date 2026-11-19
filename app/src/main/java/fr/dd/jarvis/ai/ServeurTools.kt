package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Outils « serveur » : Home Assistant (appareils, configuration YAML) et SSH (commandes, fichiers). Proposés seulement si la conversation les concerne. */
class ServeurTools(private val app: JarvisApp, private val confirm: suspend (String, Boolean) -> Boolean) {

    companion object {
        val KEYWORDS = Regex("home ?assistant|domotique|\\bha\\b|hacs|int[ée]gration|entit[ée]s?\\b|helper|\\baides?\\b|ssh|serveur|server|yaml|docker|lumi[èe]re|volet|thermostat|automatisation|automation|sc[èe]ne\\b|configuration\\.yaml|debian|raspberry|esphome|zigbee|mqtt|capteur|\\bprise\\b|interrupteur|box|livebox|freebox|bbox|vpn|wireguard|openvpn|redirection|\\bnat\\b|dmz|dhcp|disque|nas|\\bftp\\b|\\bsmb\\b|plex|bagbot|diagnostic (du )?(bot )?discord|diagnostic bagbot|pm2.{0,24}(discord|bot)|bot discord.{0,32}(serveur|debian|ssh|pm2)|discord.{0,32}(pm2|serveur debian|journaux|processus)|media server|transcodage|transcoder|biblioth[èe]que multim[ée]dia|r[ée]seau|wifi|wi-fi|imprim|scann|indisponible|injoignable|hors ligne|offline|unavailable|inaccessible|déconnecté|deconnecte|\\bip\\b|adresse ip|github|\\bgit\\b|canon|epson|brother|\\bhp\\b|appareils?\\b|script|\\bcode\\b|ping|port|volume|\\bson\\b|t[ée]l[ée]\\b|t[ée]l[ée]vision|\\btv\\b|chauffage|climatisation|temp[ée]rature|consigne|po[êe]le|poil|degr[ée]s|granul[ée]s|pellet|chaudi[èe]re|radiateur|thermique|climate|media_player|lecteur multim[ée]dia|localis|o[uù] (est|se trouve)|position d|luminosit[ée]|store\\b|persienne|\\ballume\\b|allumer|[ée]teins?\\b|[ée]teindre", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = KEYWORDS.containsMatchIn(t)
        /** Une demande explicitement Home Assistant + indisponibilité doit rester dans l'API HA, jamais dans le scan Wi-Fi. */
        fun prioriteHaDisponibilite(t: String): Boolean {
            val n = fr.dd.jarvis.societe.core.normaliser(t)
            val parleHa = Regex("\\bhome assistant\\b|\\bha\\b").containsMatchIn(n)
            val indisponible = Regex("\\b(indisponibles?|injoignables?|hors ligne|offline|unavailable|inaccessibles?|deconnectes?|deconnectees?)\\b").containsMatchIn(n)
            return parleHa && indisponible
        }
        val NAMES = setOf("ha_entites", "ha_position", "ha_etat", "ha_service", "ha_config", "ha_dashboard", "ha_admin", "ssh_commande", "ssh_fichier", "serveur_diagnostic", "serveur_scan_ports", "routeur_diagnostiquer", "reseau_infos", "reseau_scanner", "appareil_sonder", "appareil_page", "imprimantes_trouver", "imprimer", "ssh_script", "github", "appareils_liste", "appareil_nommer", "ip_publique", "box_disque", "freebox", "appareil_acces_distant", "appareil_ouvrir", "appareil_masquer", "tele")
    }

    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun tool(name: String, desc: String, props: Map<String, JSONObject>, req: List<String> = emptyList()): JSONObject {
        val p = JSONObject(); props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(req))))
    }

    val schema: List<JSONObject> = listOf(
        tool("ha_entites", "Home Assistant (INSTANCE, par son API — adresse et jeton des Réglages, sans SSH) : liste/recherche les entités ET filtre leur état réel. OBLIGATOIRE pour « appareils indisponibles/injoignables/hors ligne sur Home Assistant » : utilise etat=indisponible (état HA unavailable), ou etat=inconnu (unknown), jamais le scan Wi-Fi ni les appareils liés à Jarvis. La liste est paginée.",
            mapOf("recherche" to prop("Mot du nom ou de l'identifiant (optionnel)."), "domaine" to prop("light, switch, sensor, climate, cover, automation… (optionnel)."), "decalage" to prop("Index de départ (0 par défaut ; utiliser affichées comme prochain décalage)."), "limite" to prop("Nombre d'entités à afficher (défaut 100, maximum 250)."), "etat" to prop("Filtre d'état Home Assistant : indisponible/injoignable/hors ligne = unavailable ; inconnu = unknown ; vide = tous les états."))),
        tool("ha_etat", "Home Assistant (INSTANCE, par son API — adresse et jeton des Réglages, sans SSH) : état détaillé d'une entité.", mapOf("entity_id" to prop("ex. light.salon")), listOf("entity_id")),
        tool("ha_position", "Home Assistant : OÙ EST une personne (entités person / device_tracker) — zone, adresse précise, coordonnées GPS, précision, fraîcheur, distance et lien de carte. À utiliser pour toute question « où est X », « localisation de X ». Si la position est inconnue, le dire tel quel.",
            mapOf("personne" to prop("Prénom ou nom de la personne (vide = toutes les personnes suivies).")), emptyList()),
        tool("ha_service", "Home Assistant (INSTANCE, par son API — adresse et jeton des Réglages, sans SSH) : exécute réellement une commande sur un appareil. Pour un poêle/thermostat : climate.set_temperature avec donnees {temperature: 20}, climate.set_hvac_mode si pris en charge. Pour une télévision/lecteur : media_player.turn_on, media_player.turn_off, media_player.volume_set avec {volume_level: 0.35}, media_player.volume_up, media_player.volume_down, media_player.media_play_pause, media_player.select_source avec {source: \"…\"}. Pour une lumière : light.turn_on/off. Cherche d'abord l'identifiant exact avec ha_entites, puis utilise ha_etat pour connaître les attributs et capacités. N'annonce jamais le succès avant le résultat de l'appel.",
            mapOf("domaine" to prop("Domaine HA exact : climate, media_player, light, switch, cover, automation, scene, script…"), "service" to prop("Service HA exact : set_temperature, turn_on, turn_off, volume_set, volume_up, volume_down, select_source, media_play_pause…"),
                "entity_id" to prop("Identifiant exact de l'entité, ex. climate.poele ou media_player.television."), "donnees" to JSONObject().put("type", "object").put("description", "Objet JSON de paramètres du service. Exemples : {temperature: 20}, {volume_level: 0.35}, {source: \"HDMI 1\"}. Laisser vide si aucun paramètre n'est nécessaire.")), listOf("domaine", "service")),
        tool("ha_config", "Home Assistant : journal (API, avec diagnostic de secours en lecture seule du conteneur et des journaux via l'hôte Debian si l'API est indisponible), valider, recharger et redemarrer via l'API. Le SSH Debian ne pilote jamais HA ; il ne sert qu'à inspecter son conteneur, ses volumes et ses logs en lecture seule. L'accès aux fichiers YAML, sauvegardes disque et restauration reste suspendu tant qu'un profil SSH HA indépendant n'est pas configuré.",
            mapOf("action" to prop("valider | recharger | redemarrer | journal"), "fichier" to prop("Chemin relatif au dossier de config, ex. automations.yaml (ou dossier pour lister)."),
                "contenu" to prop("ecrire : contenu complet du fichier. restaurer : nom de la sauvegarde (.jarvis-bak-…)."), "quoi" to prop("recharger : all | automation | script | scene | core (défaut all).")), listOf("action")),
        tool("ha_dashboard", "Home Assistant Lovelace : crée, liste, lit et refait les tableaux de bord en mode stockage via WebSocket. Peut créer un tableau de bord complet, remplacer toute sa présentation avec une configuration JSON Lovelace, ajouter des cartes dans une vue/section, ajouter une vue ou supprimer un tableau. Avant toute modification, Jarvis sauvegarde automatiquement l'ancienne configuration dans son stockage local. Pour modifier, lire d'abord puis fournir la configuration complète. Les actions destructrices demandent confirmation.",
            mapOf("action" to prop("lister | lire | creer | modifier | refaire | carte_ajouter | vue_ajouter | supprimer"),
                "url_path" to prop("Chemin du tableau de bord (ex. maison, energie, salon). Vide pour le tableau par défaut lors de lire."),
                "titre" to prop("Titre du nouveau tableau lors de creer."),
                "icone" to prop("Icône MDI (ex. mdi:home, mdi:lightbulb-group)."),
                "configuration" to prop("JSON Lovelace complet pour creer/modifier/refaire. Inclure views et les cartes/sections souhaitées."),
                "vue" to prop("Index de la vue (0 par défaut) pour carte_ajouter."),
                "section" to prop("Index de la section (0 par défaut) dans une vue Sections."),
                "carte" to prop("JSON d'une carte Lovelace à ajouter, ex. {\"type\":\"tile\",\"entity\":\"light.salon\"}."),
                "vue_json" to prop("JSON complet de la nouvelle vue à ajouter.")), listOf("action")),
        tool("ha_admin", "Home Assistant : ADMINISTRATION COMPLÈTE (accès administrateur). " +
            "Entités : entites (registre : recherche, domaine, integration) | entite_modifier (entity_id + nom, nouvel_id, icone, piece, desactiver=oui/non, masquer=oui/non) | entite_supprimer (entity_id). " +
            "Appareils : appareils | appareil_modifier (appareil + nom, piece, desactiver) | appareil_supprimer. Pièces : pieces | piece_creer (nom) | piece_renommer (piece, nouveau_nom) | piece_supprimer. " +
            "Intégrations : integrations | integration_ajouter (domaine, ex. hacs, mqtt, shelly, tuya, zha… → renvoie le formulaire à remplir) | integration_etape (flow_id + donnees ; type=options pour un flux d'options) | integration_options (integration) | integration_recharger | integration_supprimer | integration_annuler (flow_id). " +
            "Automatisations/scripts/scènes de l'interface : automatisations | scripts | scenes | automatisation_lire / script_lire / scene_lire (id ou entity_id) | automatisation_ecrire / script_ecrire / scene_ecrire (id vide = création ; configuration = JSON complet : alias, triggers, conditions, actions…) | automatisation_supprimer / script_supprimer / scene_supprimer. " +
            "Aides (interrupteurs virtuels, nombres, listes, textes, dates, boutons, compteurs, minuteurs, plannings) : aides | aide_creer (type + nom + options JSON, ex. {\"min\":0,\"max\":100,\"step\":1}) | aide_supprimer (type + id). " +
            "HACS : hacs_etat | hacs_installer (installe HACS sur le serveur, redémarre, puis donne le code GitHub à saisir) | hacs_chercher (recherche, categorie integration/plugin/theme…) | hacs_telecharger (depot = propriétaire/dépôt ou id, version optionnelle) | hacs_depot_ajouter (dépôt personnalisé + categorie) | hacs_supprimer. " +
            "Système : verifier (configuration) | journal (lignes) | modele (modèle Jinja à évaluer) | redemarrer. " +
            "Accès brut à TOUT le reste : ws (type de commande WebSocket + donnees) | rest (methode GET/POST/DELETE + chemin /api/… + donnees). " +
            "Les suppressions, redémarrages et commandes qui modifient demandent confirmation. Pour modifier une automatisation : la LIRE d'abord puis renvoyer la configuration COMPLÈTE.",
            mapOf("action" to prop("Une des actions listées."),
                "entity_id" to prop("Identifiant d'entité (ex. light.salon, automation.reveil)."),
                "recherche" to prop("Filtre texte (entites, appareils, hacs_chercher)."), "domaine" to prop("entites : domaine (light, sensor…). integration_ajouter : domaine de l'intégration (hacs, mqtt…)."),
                "integration" to prop("Nom, domaine ou entry_id d'une intégration."), "appareil" to prop("Nom ou id d'un appareil."), "piece" to prop("Nom d'une pièce (créée si elle n'existe pas pour entite_modifier / appareil_modifier)."),
                "nom" to prop("Nouveau nom (entité, appareil, pièce, aide)."), "nouvel_id" to prop("entite_modifier : nouvel entity_id complet (ex. light.lampe_salon)."), "nouveau_nom" to prop("piece_renommer : nouveau nom."),
                "icone" to prop("Icône MDI (ex. mdi:lamp)."), "desactiver" to prop("oui / non."), "masquer" to prop("oui / non."),
                "id" to prop("Id d'une automatisation / scène / script / aide."), "configuration" to prop("JSON complet d'une automatisation, d'un script ou d'une scène."),
                "flow_id" to prop("Identifiant du flux de configuration en cours."), "donnees" to prop("JSON : valeurs du formulaire (integration_etape), paramètres (ws, rest)."),
                "type" to prop("aide_creer/aide_supprimer : input_boolean, input_number, input_select, input_text, input_datetime, input_button, counter, timer, schedule. ws : type de commande WebSocket. integration_etape : options pour un flux d'options."),
                "options" to prop("aide_creer : JSON des options de l'aide."), "depot" to prop("HACS : propriétaire/dépôt (ex. custom-cards/button-card) ou id."), "categorie" to prop("HACS : integration, plugin (cartes), theme, python_script, template, appdaemon."),
                "version" to prop("HACS : version à installer (optionnel)."), "methode" to prop("rest : GET, POST ou DELETE."), "chemin" to prop("rest : chemin de l'API, ex. /api/config/config_entries/entry."),
                "modele" to prop("modele : modèle Jinja, ex. {{ states('sensor.temperature') }}."), "lignes" to prop("journal : nombre de lignes (défaut 60).")), listOf("action")),
        tool("ssh_commande", "Serveur SSH : exécute une commande shell sur le serveur (Debian) et retourne la sortie. Contrôle complet : docker, systemctl, apt, logs, réseau… Les commandes destructrices demandent une confirmation. PAS pour consulter ou piloter Home Assistant (utilise les outils ha_*), seulement pour la maintenance du serveur demandée explicitement.",
            mapOf("commande" to prop("Commande shell."), "delai" to prop("Délai maximal en secondes (défaut 60)."),
                "hote" to prop("Autre machine à joindre en SSH (IP/nom) au lieu du serveur des Réglages (optionnel)."), "utilisateur" to prop("Utilisateur de cette autre machine."),
                "mot_de_passe" to prop("Mot de passe de cette autre machine."), "port" to prop("Port SSH (défaut 22)."), "via_serveur" to prop("« oui » : joindre cette machine DU RÉSEAU LOCAL à travers le serveur Debian (connexion à distance).")), listOf("commande")),
        tool("appareil_acces_distant", "Réseau de la maison : rend un appareil du Wi-Fi (caméra, NAS, Home Assistant, Plex, imprimante, PC…) joignable DEPUIS INTERNET, automatiquement — réserve son adresse IP (bail DHCP fixe), choisit un port externe libre, crée la redirection sur la Freebox, relève l'IP publique et retient l'adresse obtenue. action=ouvrir (défaut) | fermer | inventaire (analyse le Wi-Fi et met à jour l'onglet Appareils Wi-Fi).",
            mapOf("action" to prop("ouvrir | fermer | inventaire"), "appareil" to prop("Nom donné, nom réseau ou IP de l'appareil."), "port" to prop("Port du service sur l'appareil (optionnel : service principal détecté).")), listOf()),
        tool("ssh_fichier", "Serveur Debian (SSH) : LIRE, CHERCHER, MODIFIER, CRÉER ou RÉÉCRIRE un fichier du serveur — ou d'un conteneur Docker du serveur (paramètre conteneur). Pour trouver le dossier hôte du stockage Home Assistant ou Plex, utilise d'abord serveur_service action=stockage ; ne suppose jamais le chemin. Les fichiers YAML Home Assistant ne doivent pas être modifiés par cet outil générique : utilise les fonctions HA sécurisées. " +
            "C'est LE moyen de modifier un fichier du serveur (les outils de fichiers natifs d'Hermès agissent sur le téléphone). Actions : " +
            "lire (lignes numérotées ; debut/fin pour un long fichier) | chercher (motif → lignes numérotées) | remplacer (ancien → nouveau : passage exact recopié SANS numéros ; tout=oui pour toutes les occurrences) | " +
            "inserer (contenu après la ligne contenant « apres », avant celle contenant « avant », ou à la ligne n° « ligne ») | ajouter (contenu en fin de fichier) | " +
            "remplacer_lignes (debut, fin, contenu) | supprimer_lignes (debut, fin) | ecrire (contenu COMPLET : crée ou réécrit tout le fichier) | restaurer (remet la dernière sauvegarde) | lister (dossier). " +
            "Pour changer quelques lignes, préfère remplacer / remplacer_lignes à ecrire. Sauvegarde automatique avant chaque modification, droits conservés (sudo si nécessaire), contenu vérifié après écriture ; « ~ » = dossier de l'utilisateur SSH.",
            mapOf("action" to prop("lire | chercher | remplacer | inserer | ajouter | remplacer_lignes | supprimer_lignes | ecrire | restaurer | lister"),
                "chemin" to prop("Chemin du fichier (ou du dossier pour lister) : absolu, ou relatif / « ~/… » depuis le dossier de l'utilisateur SSH ; dans le conteneur si « conteneur » est donné."),
                "contenu" to prop("ecrire : contenu complet ; inserer / ajouter / remplacer_lignes : lignes à mettre."),
                "ancien" to prop("remplacer : passage exact actuel (une ou plusieurs lignes)."), "nouveau" to prop("remplacer : nouveau passage (vide = supprimer le passage)."),
                "tout" to prop("remplacer : « oui » pour remplacer toutes les occurrences."),
                "apres" to prop("inserer : texte d'une ligne repère (insertion après elle)."), "avant" to prop("inserer : texte d'une ligne repère (insertion avant elle)."),
                "ligne" to prop("inserer : numéro de ligne où insérer."), "debut" to prop("lire / remplacer_lignes / supprimer_lignes : première ligne."),
                "fin" to prop("lire / remplacer_lignes / supprimer_lignes : dernière ligne."), "motif" to prop("chercher : texte à trouver (sans tenir compte des majuscules)."),
                "conteneur" to prop("Nom du conteneur Docker (ex. homeassistant) pour un fichier À L'INTÉRIEUR du conteneur ; vide = fichier du serveur lui-même.")),
            listOf("action", "chemin")),
        tool("ssh_script", "Serveur SSH : envoie et exécute un script complet (bash ou python3, plusieurs lignes) sur le serveur Debian ; sert à installer, configurer, récupérer des données (git clone, curl…), automatiser.",
            mapOf("code" to prop("Code du script (plusieurs lignes)."), "langage" to prop("bash (défaut) ou python3."), "delai" to prop("Délai maximal en secondes (défaut 120 ; au-delà de 15 min, utilise serveur_tache)."),
                "root" to prop("« oui » : exécuter tout le script en root (automatique si le script contient sudo).")), listOf("code")),
        tool("github", "GitHub (public, ou privé avec un jeton enregistré dans Réglages › Maison › Serveur) : lit un fichier, liste un dossier, derniers commits, dernière version (release), infos ou recherche de dépôts. Si le jeton a les droits d'écriture (« repo » / « Contents: Read and write »), peut aussi CRÉER ou MODIFIER un fichier directement sur GitHub (action=ecrire), ou COPIER un fichier d'un chemin/dépôt vers un autre (action=copier) — sans passer par le serveur SSH ni un clone local.",
            mapOf("action" to prop("fichier | liste | commits | release | depot | recherche | ecrire | copier"), "depot" to prop("owner/repo (ex. home-assistant/core), ou mots de la recherche."), "chemin" to prop("Chemin du fichier ou dossier (chemin SOURCE pour « copier »)."), "ref" to prop("Branche, tag ou commit à lire (optionnel)."),
                "contenu" to prop("ecrire : contenu complet du fichier à écrire."), "message" to prop("ecrire/copier : message de commit (optionnel)."), "branche" to prop("ecrire/copier : branche de destination (optionnel, défaut : branche par défaut du dépôt)."),
                "vers_depot" to prop("copier : dépôt de destination (optionnel, défaut : même dépôt que « depot »)."), "vers_chemin" to prop("copier : chemin de destination.")), listOf("action", "depot")),
        tool("routeur_diagnostiquer", "Box Internet : identifie la passerelle, recherche son fabricant/modèle et détecte en lecture seule la prise en charge UPnP IGD (WANIPConnection/WANPPPConnection). N’effectue aucune modification et ne crée aucune redirection. À utiliser avant toute opération d’accès distant.", emptyMap()),
        tool("reseau_infos", "Réseau : adresse IP privée du téléphone, passerelle (box), adresse IP publique ; avec le serveur SSH configuré, ses adresses IP privée(s) et publique.",
            mapOf("serveur" to prop("« oui » pour inclure aussi les adresses du serveur Debian."))),
        tool("reseau_scanner", "Réseau : analyse complète du réseau Wi-Fi local (appareils connectés, adresses IP, noms, ports ouverts, type : imprimante, caméra, TV, serveur, NAS, Home Assistant…), enregistre tous les appareils et l'IP publique pour la connexion à distance. Réseau local uniquement. via=serveur analyse le réseau du serveur Debian (avec adresses MAC), utile à distance.", mapOf("via" to prop("local (défaut, Wi-Fi du téléphone) ou serveur (réseau du serveur Debian)."))),
        tool("appareil_sonder", "Réseau : examine un appareil du réseau local (ports ouverts, type, titre de sa page web).", mapOf("ip" to prop("Adresse IP privée.")), listOf("ip")),
        tool("appareil_page", "Réseau : lit la page web d'un appareil local (administration d'une imprimante, d'une box, d'une caméra…) pour consulter niveaux d'encre, état, configuration.",
            mapOf("ip" to prop("Adresse IP privée."), "port" to prop("Port (défaut 80)."), "chemin" to prop("Chemin de la page (défaut /)."), "via" to prop("local (défaut) ou serveur (à distance, depuis le réseau du serveur).")), listOf("ip")),
        tool("imprimantes_trouver", "Impression : trouve les imprimantes réseau (Canon, Epson, HP, Brother, Samsung, Xerox…) et indique modèle, état, formats acceptés.",
            mapOf("via" to prop("local (depuis le téléphone, défaut) ou serveur (depuis le réseau du serveur Debian)."), "ip" to prop("Adresse d'une imprimante précise (optionnel)."))),
        tool("imprimer", "Impression : imprime un fichier créé par JARVIS (PDF, image, texte) ou un texte sur une imprimante réseau (protocole IPP, sans pilote). À distance : via=serveur envoie le travail depuis le serveur Debian vers l'imprimante de son réseau.",
            mapOf("ip" to prop("Adresse de l'imprimante (IP privée ou publique/nom d'hôte si accessible)."), "fichier" to prop("Nom d'un fichier créé par JARVIS (optionnel)."), "texte" to prop("Texte (Markdown) à imprimer (optionnel)."),
                "titre" to prop("Titre du travail."), "copies" to prop("Nombre de copies (défaut 1)."), "via" to prop("local (défaut) ou serveur.")), listOf("ip")),
        tool("appareil_ouvrir", "Réseau : se connecte DIRECTEMENT à l'interface (page de réglages) d'un appareil — imprimante, Home Assistant, Plex, Freebox, caméra, NAS… — et l'ouvre sur le téléphone : à la maison par son adresse locale, dehors par son adresse publique ou, à défaut, par un tunnel sécurisé à travers le serveur (rien à ouvrir sur la box).",
            mapOf("appareil" to prop("Nom (« imprimante », « Plex », « caméra du garage ») ou adresse IP de l'appareil."), "port" to prop("Port de l'interface web (optionnel).")), listOf("appareil")),
        tool("appareil_masquer", "Réseau : retire un appareil de la liste « Liés à Jarvis » ou de la liste du Wi-Fi pour qu'il ne s'affiche plus (ou le remet avec remettre=oui).",
            mapOf("appareil" to prop("Nom ou adresse IP de l'appareil."), "remettre" to prop("« oui » pour le réafficher.")), listOf("appareil")),
        tool("tele", "Télé : connexion DIRECTE (sans Home Assistant) pour la piloter et changer ses réglages — Android TV / Google TV (Sony Bravia, Philips, TCL, Xiaomi, Chromecast…) par le débogage réseau via le serveur de la maison (marche aussi dehors), et réglages d'image/son des Sony Bravia (API IP). " +
            "Actions : detecter | configurer (ip, type) | connecter | etat | touche (nom : allumer_eteindre, veille, reveil, accueil, retour, haut, bas, gauche, droite, ok, volume_plus, volume_moins, muet, reglages, source, lecture_pause, chaine_plus, chaine_moins… ; fois) | " +
            "reglages (page : general, image, son, reseau, bluetooth, applis, date, langue, stockage, accessibilite, a_propos) | appli (nom : YouTube, Netflix, Plex…) | applis | texte | parametre (espace system/secure/global, cle, valeur vide = lire) | capture (écran de la télé dans la conversation) | " +
            "image (Bravia : quoi = brightness, pictureMode, contrast, color… ; valeur vide = lister) | son (Bravia) | alimentation (allumer / eteindre) | entree (hdmi1, hdmi2, tv) | commande (adb shell brut, confirmation).",
            mapOf("action" to prop("detecter | configurer | connecter | etat | touche | reglages | appli | applis | texte | parametre | capture | image | son | alimentation | entree | commande"),
                "nom" to prop("touche, appli ou entrée."), "fois" to prop("touche : nombre d'appuis."), "page" to prop("reglages : page."), "texte" to prop("texte à saisir."),
                "espace" to prop("parametre : system, secure ou global."), "cle" to prop("parametre / image / son : nom du réglage."), "valeur" to prop("nouvelle valeur (vide = lire)."),
                "ip" to prop("configurer : adresse IP de la télé."), "type" to prop("configurer : androidtv, bravia, samsung ou lg."), "commande" to prop("commande : commande adb shell.")), listOf("action")),
        tool("appareils_liste", "Réseau : liste les appareils enregistrés lors des analyses (IP, nom, type, ports, dernière vue) et les IP publiques vues.", emptyMap()),
        tool("appareil_nommer", "Mémoire réseau : attribue un nom humain à une adresse IP (ex. « Imprimante Canon bureau », « Freebox », « NAS Debian »). Le nom est conservé lors des prochains scans ; si le scan connaît l'adresse MAC, Jarvis rattache automatiquement le même appareil à sa nouvelle IP DHCP et conserve l'ancien IP dans l'historique. Peut aussi créer une fiche d'identification avant le prochain scan.", mapOf("ip" to prop("Adresse IPv4 privée actuelle de l'appareil."), "nom" to prop("Nom clair à mémoriser pour cet appareil.")), listOf("ip", "nom")),
        tool("ip_publique", "Réseau : détecte l'adresse IP publique actuelle (téléphone et, si SSH configuré, serveur) et l'enregistre pour une connexion à distance ; signale si elle a changé.", emptyMap()),
        tool("freebox", "Freebox : PILOTAGE COMPLET de la Freebox via Freebox OS, avec les droits accordés à JARVIS dans Freebox OS (Gestion des accès › Applications). Lire : fichiers (contenu du DISQUE DUR de la box : paramètre chemin, ex. « Téléchargements » ou « Vidéos/Films » ; vide = liste des disques), telechargements (gestionnaire de téléchargements de la box), etat (connexion, débits, IP publique), appareils (appareils connectés au réseau et connus de la box — PAS le disque ; filtre=sans_nom|hors_ligne), redirections (ports), vpn (serveurs VPN et état), vpn_utilisateurs, wifi, dmz, baux_dhcp (IP réservées), appels (journal de la ligne fixe), droits. Modifier (confirmation demandée) : ajouter_redirection, supprimer_redirection, activer_redirection, desactiver_redirection, activer_vpn, desactiver_vpn, ajouter_vpn_utilisateur, supprimer_vpn_utilisateur, config_vpn (génère le fichier WireGuard/OpenVPN à importer), wifi_activer, wifi_desactiver, dmz (etat=activer + appareil/ip, ou etat=desactiver), ajouter_bail, redemarrer, supprimer_appareil (retire de la liste des appareils de la box : appareil = nom, IP, MAC ou identifiant — plusieurs séparés par « ; » —, ou filtre = sans_nom | hors_ligne pour tous ceux-là ; seuls les appareils déconnectés sont retirés), renommer_appareil (appareil + nouveau_nom). FICHIERS DU DISQUE DE LA BOX (sans SMB/FTP) : chercher_fichier (nom), renommer (chemin + nouveau_nom), deplacer / copier (chemin — plusieurs séparés par « ; », jokers « Téléchargements/*.mkv » acceptés — + destination, dossier créé s'il manque), supprimer_fichier (chemin, confirmation), creer_dossier (chemin), envoyer_fichier (fichier du téléphone + destination), recuperer_fichier (chemin → copie sur le téléphone), lire_fichier (fichier texte), diagnostic (si un disque paraît vide ou inaccessible : renvoie les réponses brutes de la box — montre-les telles quelles à l'utilisateur, n'invente pas de cause matérielle). Utilise CET outil pour toute demande sur la Freebox : ne réponds jamais que tu ne peux pas la configurer.",
            mapOf("action" to prop("Une des actions listées (ex. fichiers, telechargements, redirections, ajouter_redirection, vpn, activer_vpn, config_vpn)."),
                "chemin" to prop("Disque de la box : dossier à lister, ou fichier/dossier visé (renommer, deplacer, copier, supprimer_fichier, recuperer_fichier, lire_fichier, creer_dossier). Nom approximatif accepté (ex. Téléchargements, Vidéos/Films, Téléchargements/film.mkv, Téléchargements/*.mkv)."),
                "destination" to prop("deplacer / copier / envoyer_fichier : dossier de destination sur la box (créé s'il n'existe pas)."),
                "nouveau_nom" to prop("renommer : nouveau nom (sans dossier). envoyer_fichier : nom à donner sur la box (optionnel)."),
                "fichier" to prop("envoyer_fichier : fichier du téléphone (chemin complet, ex. /storage/emulated/0/Download/photo.jpg, ou nom d'un fichier créé par Jarvis)."),
                "nom" to prop("chercher_fichier : nom (ou partie du nom, ou joker *.mkv) à chercher sur tout le disque."),
                "remplacer" to JSONObject().put("type", "boolean").put("description", "deplacer / copier / envoyer_fichier : remplacer un fichier du même nom (défaut : non, les deux sont gardés)."),
                "appareil" to prop("Nom d'un appareil du réseau (tel que la Freebox le connaît) — alternative à ip."),
                "ip" to prop("Adresse IP locale cible (redirection, DMZ, bail DHCP, réservation VPN)."),
                "port" to prop("Port de l'appareil (redirection), ou port d'une redirection à supprimer."),
                "port_externe" to prop("Port public ouvert sur internet (défaut : même que port)."),
                "port_externe_fin" to prop("Fin de plage de ports publics (optionnel)."),
                "protocole" to prop("tcp (défaut), udp ou les deux."),
                "commentaire" to prop("Commentaire de la règle (optionnel)."),
                "source" to prop("IP source autorisée (défaut 0.0.0.0 = tout internet)."),
                "id" to prop("Identifiant (#) d'une redirection."),
                "serveur" to prop("Serveur VPN : wireguard (défaut s'il existe), openvpn_routed, openvpn_bridge, ipsec, pptp."),
                "login" to prop("Identifiant d'un utilisateur VPN."),
                "mot_de_passe" to prop("Mot de passe VPN (8 à 32 caractères ; généré si absent)."),
                "mac" to prop("Adresse MAC (bail DHCP)."),
                "etat" to prop("dmz : activer ou desactiver."),
                "nombre" to prop("appels : nombre d'appels à afficher (défaut 15)."),
                "filtre" to prop("appareils / supprimer_appareil : sans_nom (appareils sans nom) ou hors_ligne (tous les appareils déconnectés).")), listOf("action")),
        tool("box_disque", "Box internet : accède au disque dur branché sur la box (Freebox associée : par Freebox OS, sans réglage ; autres box : SMB ou FTP, selon Réglages › Maison › Box Internet) : liste les partages et dossiers, lit un fichier texte, le télécharge dans l'appli, y envoie un fichier créé par JARVIS, crée un dossier, supprime, renomme ou déplace un fichier/dossier (utile pour réorganiser une bibliothèque de films/séries pour Plex, par exemple), ou lance directement la lecture d'une vidéo dans le lecteur vidéo du téléphone (aucune app spécifique requise, pas de téléchargement).",
            mapOf("action" to prop("partages | lister | lire | telecharger | envoyer | supprimer | dossier | renommer | deplacer | lire_telephone"), "chemin" to prop("Chemin sur la box : partage/dossier/fichier (ex. « Disque dur/Photos/2026 »). Pour renommer/deplacer : le chemin ACTUEL (source)."), "fichier" to prop("envoyer : nom du fichier créé par JARVIS à copier sur la box. renommer/deplacer : le NOUVEAU chemin complet sur la box (ex. « Disque dur/Films/Dirty Dancing (1987).avi »).")), listOf("action")),
        tool("serveur_diagnostic", "Diagnostic approfondi en lecture seule. cible=complet (défaut) vérifie Debian, SSH, Docker, Plex et Home Assistant ; cible=plex vérifie le conteneur, les volumes, ressources, erreurs, API Plex, bibliothèques et sessions si le jeton est configuré ; cible=home assistant vérifie les entités indisponibles via l’API et le conteneur/volumes/logs côté hôte Debian ; cible=ssh vérifie service SSH, ports d’écoute, paramètres effectifs, pare-feu et journaux ; cible=debian vérifie ressources, disques, Docker et processus ; cible=discord ou bagbot inspecte les processus Bagbot/Discord du PM2 de l’utilisateur SSH et compte les événements des journaux sans afficher de variables d’environnement, jetons ou contenus de messages. Le démon PM2 étant propre à chaque utilisateur Linux, ce diagnostic utilise le compte SSH configuré. Ne modifie rien.", mapOf("cible" to prop("complet | debian | ssh | plex | home assistant | discord | bagbot (défaut complet)."), "lignes" to prop("Nombre de lignes de logs à examiner (20 à 200, défaut 80)."))),
        tool("serveur_scan_ports", "Dépannage réseau : teste DIRECTEMENT DEPUIS LE TÉLÉPHONE (pas besoin que SSH fonctionne) quels ports répondent sur le serveur — utile en premier recours quand SSH refuse de se connecter (mauvais port probable). Teste les ports SSH non standards les plus courants et quelques ports de services courants, identifie lequel répond vraiment comme un serveur SSH (bannière « SSH-… »), et peut corriger le port SSH dans Réglages si tu le demandes et que l'utilisateur confirme. Ne fonctionne que si le téléphone peut atteindre cette adresse (même réseau local que le serveur, ou port déjà redirigé vers l'extérieur).",
            mapOf("hote" to prop("Adresse à tester (optionnel, défaut : l'adresse du serveur dans Réglages)."), "ports" to prop("Ports supplémentaires à tester, séparés par des virgules (optionnel, en plus des ports courants).")))
    )

    private fun sortie(r: Pair<Int, String>, max: Int = 5500): String {
        val t = r.second.trim().ifBlank { "(aucune sortie)" }
        return (if (t.length > max) "…" + t.takeLast(max) else t) + (if (r.first != 0 && r.first > -2) "\n[code de sortie ${r.first}]" else "")
    }

    suspend fun execute(name: String, a: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try { run(name, a) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ToolResult("❌ ${e.message ?: e.javaClass.simpleName}") }
    }

    /** ssh_fichier : lecture, recherche, modifications ciblées, réécriture et restauration (serveur ou conteneur Docker). */
    private suspend fun sshFichier(a: JSONObject): ToolResult {
        val ct = a.optString("conteneur").trim()
        if (Regex("home.?assistant|\\bhass\\b", RegexOption.IGNORE_CASE).containsMatchIn(ct)) {
            return ToolResult("⛔ Accès aux fichiers du conteneur Home Assistant bloqué depuis le SSH Debian. Le profil SSH configuré cible Debian/Jarvis, pas la machine HA. Les opérations YAML nécessitent un profil SSH HA indépendant.")
        }
        val p = a.optString("chemin").trim().let { if (ct.isBlank() && it.isNotBlank()) Serveur.absolu(app, it) else it }
        if (p.isBlank()) return ToolResult("Indique « chemin » (fichier, ou dossier pour lister).")
        val ou = if (ct.isBlank()) "sur le serveur" else "dans le conteneur $ct"
        fun nombre(k: String) = a.optString(k).trim().toIntOrNull()
        val action = fr.dd.jarvis.societe.core.normaliser(a.optString("action")).replace(' ', '_')
        val lecture = setOf("lire", "chercher", "lister")
        if (action in lecture) return when (action) {
            "lister" -> ToolResult("📁 $p ($ou)\n```\n" + FichiersServeur.lister(app, p, ct) + "\n```")
            "chercher" -> ToolResult("🔎 $p ($ou)\n```\n" + FichiersServeur.chercher(FichiersServeur.lire(app, p, ct), a.optString("motif").ifBlank { a.optString("ancien") }) + "\n```")
            else -> ToolResult("📄 $p ($ou) " + FichiersServeur.numeroter(FichiersServeur.lire(app, p, ct), nombre("debut"), nombre("fin")).let { t ->
                t.substringBefore('\n') + "\n```\n" + t.substringAfter('\n', "") + "\n```" })
        }
        // Modifications : version actuelle (vide pour un nouveau fichier), changement appliqué ici, puis écriture vérifiée.
        val actuel: String? = if (action == "ecrire") runCatching { FichiersServeur.lire(app, p, ct) }.getOrNull()
            else if (action == "restaurer") null
            else runCatching { FichiersServeur.lire(app, p, ct) }.getOrElse { e ->
                if (action in setOf("ajouter", "inserer") && (e.message ?: "").contains("introuvable", true)) "" else throw e
            }
        val avant = actuel.orEmpty()
        val (apres, resume) = when (action) {
            "ecrire" -> {
                if (!a.has("contenu")) return ToolResult("Indique « contenu » (contenu complet du fichier).")
                a.optString("contenu") to (if (actuel == null) "fichier créé" else "fichier réécrit")
            }
            "remplacer" -> FichiersServeur.remplacer(avant, a.optString("ancien"), a.optString("nouveau"), a.optString("tout").lowercase() in setOf("oui", "true", "1")).let { it.texte to it.resume }
            "inserer" -> FichiersServeur.inserer(avant, a.optString("contenu"), a.optString("apres").ifBlank { null }, a.optString("avant").ifBlank { null }, nombre("ligne")).let { it.texte to it.resume }
            "ajouter" -> FichiersServeur.ajouter(avant, a.optString("contenu")).let { it.texte to it.resume }
            "remplacer_lignes" -> {
                val d = nombre("debut") ?: return ToolResult("Indique « debut » (et « fin »).")
                FichiersServeur.remplacerLignes(avant, d, nombre("fin") ?: d, a.optString("contenu")).let { it.texte to it.resume }
            }
            "supprimer_lignes" -> {
                val d = nombre("debut") ?: return ToolResult("Indique « debut » (et « fin »).")
                FichiersServeur.supprimerLignes(avant, d, nombre("fin") ?: d).let { it.texte to it.resume }
            }
            "restaurer" -> {
                val bak = FichiersServeur.derniereSauvegarde(app, p, ct) ?: return ToolResult("Aucune sauvegarde « .jarvis-bak-… » de $p $ou.")
                val courant = runCatching { FichiersServeur.lire(app, p, ct) }.getOrDefault("")
                val ancienne = FichiersServeur.lire(app, bak, ct)
                if (!confirm("Restaurer $p $ou depuis $bak ?\n\n" + FichiersServeur.difference(courant, ancienne, 20).take(900), true)) return ToolResult("Refusé par l'utilisateur.")
                val b2 = FichiersServeur.ecrire(app, p, ancienne, ct)
                return ToolResult("✅ $p restauré depuis $bak." + (b2?.let { " Version remplacée sauvegardée : $it" } ?: ""))
            }
            else -> return ToolResult("Action inconnue : lire, chercher, remplacer, inserer, ajouter, remplacer_lignes, supprimer_lignes, ecrire, restaurer, lister.")
        }
        if (actuel != null && apres == actuel) return ToolResult("Aucun changement : $p ($ou) contient déjà exactement ce contenu.")
        val diff = if (actuel == null) "(nouveau fichier, ${apres.lines().size} lignes)" else FichiersServeur.difference(avant, apres)
        val configHa = runCatching { HaSur.estConfigHa(app, p, ct) }.getOrDefault(false)
        if (configHa && apres.contains('\t')) return ToolResult("❌ Le YAML de Home Assistant n'accepte pas les tabulations : réécris avec des espaces. $p n'a pas été modifié.")
        if (!confirm("Modifier $p $ou ($resume) ?" + (if (configHa) " Configuration de Home Assistant : vérifiée après écriture, ancienne version remise si elle est refusée." else "") + "\n\n" + diff.take(900), true))
            return ToolResult("Refusé par l'utilisateur : $p n'a pas été modifié.")
        val bak = FichiersServeur.ecrire(app, p, apres, ct)
        // Configuration de Home Assistant : vérifiée tout de suite. Refusée → l'ancienne version revient (sinon Home Assistant
        // redémarrerait en mode de récupération, entités indisponibles). Acceptée → rechargée à chaud, sans coupure.
        val suite = if (!configHa) "" else {
            val err = runCatching { HaSur.verifier(app) }.getOrNull()
            if (err != null) {
                runCatching { if (actuel != null) FichiersServeur.ecrire(app, p, actuel, ct) else FichiersServeur.supprimer(app, p, ct) }
                return ToolResult("⛔ Home Assistant refuse cette modification : l'ancienne version de $p a été remise (rien n'a changé, Home Assistant reste en service).\n```\n$err\n```")
            }
            "\n✅ Configuration de Home Assistant valide. " + HaSur.recharger(app) + " Si le changement concerne une intégration non rechargeable, dis « redémarre Home Assistant »."
        }
        return ToolResult("✅ $p modifié $ou : $resume ; contenu vérifié après écriture." + (bak?.let { "\nSauvegarde de l'ancienne version : $it (action=restaurer pour revenir en arrière)." } ?: "") +
            suite + "\n```diff\n" + diff.take(3500) + "\n```")
    }

    private suspend fun run(name: String, a: JSONObject): ToolResult {
        fun ssh() { if (!Serveur.sshConfigure(app)) throw IllegalStateException("SSH non configuré (Réglages › Maison › Serveur : adresse, utilisateur, mot de passe ou clé).") }
        return when (name) {
            "ha_dashboard" -> {
                val action = a.optString("action").lowercase()
                if (action in setOf("creer", "modifier", "refaire", "carte_ajouter", "vue_ajouter", "supprimer")) {
                    val cible = a.optString("url_path").ifBlank { "tableau par défaut" }
                    if (!confirm("Home Assistant : effectuer « $action » sur $cible ? Une sauvegarde sera créée avant modification.", true)) return ToolResult("Opération annulée.")
                }
                ToolResult(HomeAssistantDashboard.execute(app, action, a), direct = true)
            }
            "ha_entites" -> ToolResult(Serveur.haEntites(app, a.optString("recherche"), a.optString("domaine"), a.optString("decalage").toIntOrNull() ?: 0, a.optString("limite").toIntOrNull() ?: 100, a.optString("etat")), direct = true)
            "ha_etat" -> ToolResult(Serveur.haEtat(app, a.optString("entity_id").trim()))
            "ha_position" -> ToolResult(Serveur.haPosition(app, a.optString("personne").trim()), direct = true)
            "ha_service" -> {
                val d = a.optString("domaine").trim(); val s = a.optString("service").trim(); val e = a.optString("entity_id").trim()
                val sensible = (d in setOf("lock", "alarm_control_panel") || s in setOf("restart", "stop", "unlock", "alarm_disarm", "open_cover") && d != "light") || (d == "homeassistant" && s in setOf("restart", "stop"))
                if (sensible && !confirm("Home Assistant : exécuter $d.$s ${e}?", true)) return ToolResult("Refusé par l'utilisateur.")
                // Redémarrage : configuration vérifiée avant, retour en ligne attendu (jamais « redémarré » à l'aveugle).
                if (d == "homeassistant" && s == "restart") return ToolResult(HaSur.redemarrer(app), direct = true)
                val data = when (val raw = a.opt("donnees")) {
                    is JSONObject -> JSONObject(raw.toString())
                    is String -> raw.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    else -> null
                }
                ToolResult(Serveur.haService(app, d, s, e, data))
            }
            "ssh_commande" -> {
                val autre = a.optString("hote").trim().takeIf { it.isNotBlank() }
                if (autre == null) ssh()
                val cible = autre?.let { Serveur.Cible(it, a.optString("port").toIntOrNull() ?: 22, a.optString("utilisateur").ifBlank { app.store.sshUser.value }, a.optString("mot_de_passe"), a.optString("via_serveur").lowercase() in setOf("oui", "true", "1")) }
                if (cible?.jump == true) ssh()
                val cmd = a.optString("commande").trim()
                if (cmd.isBlank()) return ToolResult("Commande vide.")
                // Aucun accès Docker/CLI HA via le SSH générique : il cible exclusivement Debian/Jarvis.
                if (cible == null && (Regex("127\\.0\\.0\\.1:8123|localhost:8123", RegexOption.IGNORE_CASE).containsMatchIn(cmd) ||
                    (Regex("(?:docker|docker-compose|docker\\s+compose|ha\\s+core)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cmd) &&
                        Regex("home.?assistant|\\bhass\\b", RegexOption.IGNORE_CASE).containsMatchIn(cmd)))) {
                    return ToolResult("⛔ Commande refusée : elle cible Home Assistant depuis le SSH Debian. Les commandes système HA doivent passer par l'API HA ou par un futur profil SSH HA indépendant.")
                }
                // Redémarrage / mise à jour de Home Assistant « à la main » : remplacés par les versions sûres.
                if (cible == null) runCatching { HaSur.interception(cmd, HaSur.conteneur(app)?.nom) }.getOrNull()?.let { quoi ->
                    return if (quoi == "redemarrer") {
                        if (!confirm("Redémarrer Home Assistant ? (configuration vérifiée avant, arrêt propre, retour en ligne attendu)", true)) ToolResult("Refusé par l'utilisateur.")
                        else ToolResult(HaSur.redemarrer(app), direct = true)
                    } else {
                        if (!confirm("Mettre à jour Home Assistant ? (sauvegarde, contrôle de la place, retour automatique à l'ancienne version si la nouvelle ne démarre pas)", true)) ToolResult("Refusé par l'utilisateur.")
                        else ToolResult(HaSur.mettreAJour(app) { code, nom, root -> ServeurAdminTools(app, confirm).lancerTache(code, nom, root) }, direct = true)
                    }
                }
                // Commande coupée (réponse d'IA tronquée) : refusée AVANT toute confirmation ou exécution.
                Serveur.commandeIncomplete(cmd)?.let { return ToolResult("⛔ Commande incomplète, rien n'a été exécuté : $it. Renvoie la commande COMPLÈTE.") }
                if ((Serveur.dangereuse(cmd) || cible != null) && !confirm("Exécuter sur ${cible?.hote ?: app.store.sshHost.value} :\n\n$cmd", true)) return ToolResult("Refusé par l'utilisateur.")
                val delai = (a.optString("delai").toIntOrNull() ?: 90).coerceIn(5, 900)
                // Serveur de la maison : exécution vérifiée (syntaxe) et détachée — jamais tuée en plein milieu par un délai.
                val r = if (cible == null) Serveur.execComplet(app, cmd, delai) else Serveur.exec(app, cmd, delai, cible)
                ToolResult("```\n" + sortie(r) + "\n```")
            }
            "ssh_fichier" -> { ssh(); sshFichier(a) }
            "serveur_diagnostic" -> {
                val cible = a.optString("cible").trim().ifBlank { "complet" }
                val lignes = a.optString("lignes").toIntOrNull() ?: 80
                val sortieHote = if (!Serveur.sshConfigure(app)) {
                    "Connexion SSH Debian non configurée : diagnostic hôte ignoré."
                } else runCatching {
                    val sudo = app.store.sshUser.value.trim() != "root" && app.store.sshPass.value.isNotBlank()
                    val commande = ServeurDiagnostic.commande(cible, app.store.plexToken.value, lignes, sudo)
                    sortie(Serveur.exec(app, commande, 120), 8500)
                }.getOrElse { "Diagnostic SSH Debian impossible : ${it.message?.take(220) ?: it.javaClass.simpleName}." }
                val ha = if (cible.equals("complet", true) || cible.equals("home assistant", true) || cible.equals("homeassistant", true) || cible.equals("ha", true)) {
                    "\n\n## Home Assistant — contrôle API (instance configurée)\n" + ServeurDiagnostic.resumeHomeAssistant(app)
                } else ""
                ToolResult("# 🧰 Diagnostic approfondi — ${cible}\n\n```\n" + sortieHote + "\n```" + ha +
                    "\n\n_Contrôle SSH en lecture seule : aucune modification n’a été effectuée._", direct = true)
            }
            "serveur_scan_ports" -> {
                val hote = a.optString("hote").trim().ifBlank { app.store.sshHost.value.trim() }
                if (hote.isBlank()) return ToolResult("Aucune adresse à tester : précise « hote », ou renseigne l'adresse du serveur dans Réglages › Maison › Serveur.")
                val extra = a.optString("ports").split(",").mapNotNull { it.trim().toIntOrNull() }
                val ports = (Serveur.PORTS_SSH_COURANTS + Serveur.PORTS_SERVICES_COURANTS + extra).distinct()
                val ouverts = Serveur.scannerPorts(hote, ports).filter { it.second }.map { it.first }
                if (ouverts.isEmpty()) return ToolResult(
                    "Aucun des ${ports.size} ports testés ne répond sur $hote. Soit le téléphone n'a pas accès à cette adresse depuis ce réseau (es-tu sur le même Wi-Fi que le serveur ? sinon le port doit être redirigé vers l'extérieur sur la box, avec l'adresse publique/DDNS renseignée dans Réglages › Maison › Serveur › Adresse distante), soit le serveur est éteint.")
                val sb = StringBuilder("# 🔎 Ports ouverts sur $hote\n")
                val sshTrouves = mutableListOf<Int>()
                ouverts.sorted().forEach { p ->
                    val banniere = Serveur.banniereSsh(hote, p)
                    if (banniere != null) { sshTrouves += p; sb.append("- **$p** 🔑 confirmé SSH (« ${banniere.take(60)} »)\n") } else sb.append("- $p\n")
                }
                val portActuel = app.store.sshPort.value.toIntOrNull() ?: 22
                if (sshTrouves.isNotEmpty() && portActuel !in sshTrouves) {
                    sb.append("\n⚠️ Le port SSH des Réglages ($portActuel) ne répond pas comme SSH, mais le port **${sshTrouves.first()}** si. ")
                    if (confirm("Mettre à jour le port SSH dans Réglages sur ${sshTrouves.first()} ?", true)) {
                        app.store.sshPort.set(sshTrouves.first().toString())
                        sb.append("✅ Port SSH mis à jour sur ${sshTrouves.first()}.")
                    } else sb.append("Tu peux le corriger toi-même dans Réglages › Maison › Serveur.")
                }
                ToolResult(sb.toString(), direct = true)
            }
            "ha_config" -> haConfig(a)
            "ha_admin" -> ToolResult(HomeAssistantAdmin(app, confirm).executer(a.optString("action"), a))
            "ssh_script" -> {
                ssh()
                val code = a.optString("code")
                if (code.isBlank()) return ToolResult("Script vide.")
                if (Regex("(?:docker|docker-compose|docker\\s+compose|ha\\s+core)\\b", RegexOption.IGNORE_CASE).containsMatchIn(code) &&
                    Regex("home.?assistant|\\bhass\\b|127\\.0\\.0\\.1:8123|localhost:8123", RegexOption.IGNORE_CASE).containsMatchIn(code)) {
                    return ToolResult("⛔ Script refusé : il cible Home Assistant depuis le SSH Debian. Utilise l'API HA ou un profil SSH HA indépendant.")
                }
                val py = a.optString("langage").lowercase().startsWith("py")
                if (!py) Serveur.commandeIncomplete(code)?.let { return ToolResult("⛔ Script incomplet, rien n'a été exécuté : $it. Renvoie le script COMPLET.") }
                if (!confirm("Exécuter ce script ${if (py) "Python" else "bash"} sur le serveur ${app.store.sshHost.value} ?\n\n" + code.take(900), true)) return ToolResult("Refusé par l'utilisateur.")
                // Script avec sudo (ou root demandé) : tout le script tourne en root, ses « sudo » internes passent sans mot de passe.
                val root = a.optString("root").lowercase() in setOf("oui", "true", "1") || Regex("\\bsudo\\b").containsMatchIn(code)
                val delai = (a.optString("delai").toIntOrNull() ?: 180).coerceIn(10, 900)
                val r = if (py) {
                    val f = "/tmp/jarvis_script_${System.currentTimeMillis()}.py"
                    Serveur.envoyerOctets(app, f, code.toByteArray())
                    // Script coupé ou mal formé : refusé avant exécution.
                    val v = Serveur.exec(app, "out=\$(python3 -c 'import ast,sys; ast.parse(open(sys.argv[1]).read())' $f 2>&1); echo \"JARVIS_RC=\$?\"; printf '%s' \"\$out\" | tail -3", 20)
                    if (!v.second.contains("JARVIS_RC=0")) { Serveur.exec(app, "rm -f $f", 10); -3 to "⛔ Script Python incomplet ou mal formé, rien n'a été exécuté :\n" + v.second.replace(Regex("JARVIS_RC=\\d+"), "").trim() }
                    else Serveur.execComplet(app, (if (root) "sudo " else "") + "python3 $f; c=\$?; rm -f $f; exit \$c", delai, "script Python")
                } else Serveur.execComplet(app, (if (root && !Regex("\\bsudo\\b").containsMatchIn(code)) "sudo true\n" else "") + "set -e\n$code", delai, "script bash")
                ToolResult("```\n" + sortie(r) + "\n```")
            }
            "github" -> {
                val act = a.optString("action").lowercase()
                if (act in setOf("ecrire", "copier")) {
                    val cible = if (act == "copier") "${a.optString("depot")}/${a.optString("chemin")} → ${a.optString("vers_depot").ifBlank { a.optString("depot") }}/${a.optString("vers_chemin")}"
                        else "${a.optString("depot")}/${a.optString("chemin")}"
                    if (!confirm("GitHub : $act $cible ?", true)) return ToolResult("Refusé par l'utilisateur.")
                    ToolResult(github(a), direct = true)
                } else ToolResult(github(a))
            }
            "appareils_liste" -> ToolResult(Registre.liste(app), direct = true)
            "appareil_acces_distant" -> when (a.optString("action").lowercase()) {
                "inventaire", "analyser", "scanner" -> ToolResult(AccesDistant.inventorier(app) + "\n\n" + Registre.liste(app), direct = true)
                "fermer", "supprimer" -> ToolResult(AccesDistant.fermer(app, a.optString("appareil"), a.optString("port").toIntOrNull(), confirm))
                else -> ToolResult(AccesDistant.configurer(app, a.optString("appareil"), a.optString("port").filter { it.isDigit() }.toIntOrNull(), confirm))
            }
            "appareil_nommer" -> {
                val ip = a.optString("ip").trim()
                val nom = a.optString("nom").trim()
                if (Registre.nommer(app, ip, nom)) ToolResult("✅ Adresse **$ip** mémorisée sous le nom « $nom ». Jarvis réutilisera ce nom dans sa liste réseau. Si l'appareil est identifié par MAC lors d'un scan depuis le serveur, le nom suivra aussi ses changements d'adresse DHCP.", direct = true)
                else ToolResult("❌ Adresse IP privée ou nom invalide. Indique une IPv4 locale et un nom non vide.")
            }
            "ip_publique" -> {
                val sb = StringBuilder("# 🌍 Adresses IP\n")
                // Mémoriser le Wi-Fi avant la requête publique pour la lier à la box.
                val i = Reseau.infos(app)
                val tel = Reseau.ippPublique()
                if (tel.isBlank()) sb.append("- Téléphone : IP publique introuvable (pas d'accès Internet ?).\n") else {
                    sb.append("- 📱 Téléphone/box : **$tel**" + (if (i != null && i.wifi) " (réseau Wi-Fi, privée ${i.ip})" else " (données mobiles)") + "\n")
                    if (i != null && i.wifi) Registre.ipPublique(app, tel, "Wi-Fi ${i.ip.substringBeforeLast('.')}.x").takeIf { it.isNotBlank() }?.let { sb.append("- $it\n") }
                }
                if (Serveur.sshConfigure(app)) runCatching {
                    val r = Serveur.exec(app, "echo \"privée(s): \$(hostname -I)\"; echo \"publique: \$(curl -s -m 8 https://api.ipify.org)\"", 25).second.trim()
                    sb.append("- 🖥️ Serveur :\n```\n$r\n```\n")
                    Regex("publique:\\s*([0-9.]+)").find(r)?.groupValues?.get(1)?.let { ip -> if (ip != tel) Registre.ipPublique(app, ip, "Serveur") }
                }
                val der = Registre.derniereIpPublique(app)
                if (der.isNotBlank()) sb.append("\n✅ Enregistrée : $der. Avant tout accès distant, compare cette IP à l'IPv4 WAN affichée dans l'interface de la box : une WAN en 100.64.0.0/10, 10.0.0.0/8, 172.16.0.0/12 ou 192.168.0.0/16, ou une IP WAN différente, signale du CGNAT ou un double NAT (la redirection IPv4 directe ne fonctionnera pas sans correction opérateur/routeur). Privilégie un VPN privé (WireGuard/Tailscale ou VPN intégré à la Freebox) pour SSH, imprimantes, interfaces d'administration et passerelles MCP. N'expose jamais directement sur Internet l'interface d'une box, une imprimante ou un MCP. Si une redirection ciblée est réellement nécessaire : bail DHCP fixe, seul port/protocole indispensable, pare-feu actif et authentification forte ; pour SSH, clés uniquement, mot de passe désactivé et protection anti-bruteforce. Le DDNS ne remplace ni un VPN ni une IP WAN joignable.")
                ToolResult(sb.toString(), direct = true)
            }
            "freebox" -> ToolResult(FreeboxOs.gerer(app, a.optString("action"), a, confirm))
            "box_disque" -> {
                val act = a.optString("action").lowercase()
                // Freebox associée : tout passe par Freebox OS (droit « gestionnaire de fichiers »), sans partage SMB/FTP à configurer.
                if (app.store.freeboxAppToken.value.isNotBlank() && act != "lire_telephone")
                    return ToolResult(FreeboxOs.gererDisque(app, act, a.optString("chemin"), a.optString("fichier"), confirm), direct = act == "telecharger")
                if (act in setOf("supprimer", "envoyer") && !confirm("Box : $act « ${a.optString("chemin").ifBlank { a.optString("fichier") }} » ?", true)) return ToolResult("Refusé par l'utilisateur.")
                if (act == "lire_telephone") {
                    val h = Box.hote(app)
                    if (h.isBlank()) return ToolResult("Adresse de la box inconnue : connecte-toi à son Wi-Fi ou renseigne-la dans Réglages › Maison › Box Internet.")
                    val chemin = a.optString("chemin").trim()
                    if (chemin.isBlank()) return ToolResult("Précise le fichier à lire (paramètre « chemin »).")
                    val url = fr.dd.jarvis.device.MediaServeur.url(app, chemin)
                    return ToolResult(app.phone.lireVideoDistante(url, Box.mimeVideo(chemin)))
                }
                ToolResult(Box.action(app, act, a.optString("chemin"), a.optString("fichier")), direct = act == "telecharger")
            }
            "routeur_diagnostiquer" -> {
                val r = Reseau.diagnostiquerRouteur(app)
                    ?: return ToolResult("Impossible de détecter la passerelle : connecte le téléphone au Wi-Fi du routeur.")
                val sb = StringBuilder("# 📡 Diagnostic de la box — lecture seule\n")
                sb.append("- Passerelle : **\${r.ip}**\n")
                if (r.fabricant.isNotBlank()) sb.append("- Fabricant : \${r.fabricant}\n")
                if (r.modele.isNotBlank()) sb.append("- Modèle : \${r.modele}\n")
                if (r.services.isNotEmpty()) {
                    sb.append("- Contrôle NAT standard : **UPnP IGD détecté**\n")
                    r.services.forEach { sb.append("  - \${it}\n") }
                    sb.append("\nJarvis a identifié une interface standard potentiellement exploitable pour lire l’état NAT et, si tu le demandes, préparer une règle ciblée. Aucune règle n’a été créée.")
                } else {
                    sb.append("- Contrôle NAT standard : UPnP IGD non détecté (cela ne prouve pas qu’il est absent).\n")
                    sb.append("\nLa box peut proposer une API constructeur (Freebox OS, Bbox, Livebox, etc.), TR-064, PCP/NAT-PMP ou une interface web propriétaire. Il faudra identifier le modèle et ses capacités avant de choisir un adaptateur. Aucune connexion d’administration ni modification n’a été tentée.")
                }
                ToolResult(sb.toString(), direct = true)
            }
            "reseau_infos" -> {
                val i = Reseau.infos(app)
                val sb = StringBuilder("# 🌐 Réseau\n")
                if (i == null) sb.append("- 📵 Pas de connexion réseau.\n") else sb.append("- 📱 Téléphone : **${i.ip}**/${i.prefix}${if (i.wifi) " (Wi-Fi)" else " (données mobiles — le réseau local n'est pas visible)"}\n" + (if (i.passerelle.isNotBlank()) "- 📡 Passerelle (box) : **${i.passerelle}**\n" else ""))
                Reseau.ippPublique().takeIf { it.isNotBlank() }?.let { sb.append("- 🌍 IP publique (téléphone) : **$it**\n") }
                if (a.optString("serveur").lowercase() in setOf("oui", "true", "1") && Serveur.sshConfigure(app)) {
                    val r = Serveur.exec(app, "echo \"privées: \$(hostname -I)\"; echo \"publique: \$(curl -s -m 8 https://api.ipify.org)\"; ip route | head -1", 25)
                    sb.append("- 🖥️ Serveur ${app.store.sshHost.value} :\n```\n${r.second.trim()}\n```\n")
                }
                ToolResult(sb.toString(), direct = true)
            }
            "reseau_scanner" -> {
                if (a.optString("via").lowercase().startsWith("serv")) {
                    ssh()
                    val l = Serveur.scanReseau(app)
                    val neufs = Registre.appareils(app, l, "Réseau du serveur")
                    val alias = Registre.alias(app)
                    return ToolResult("# 📡 Réseau du serveur — ${l.size} appareil(s)\n\n| IP | Nom mémorisé | MAC | Type | Ports |\n|---|---|---|---|---|\n" + l.joinToString("\n") { "| ${it.ip} | ${alias[it.ip].orEmpty().ifBlank { "—" }} | ${it.info.removePrefix("MAC ")} | ${it.type} | ${it.ports.joinToString(",")} |" } + "\n\n💾 $neufs nouvel(s) appareil(s) enregistré(s).", direct = true)
                }
                val i = Reseau.infos(app) ?: return ToolResult("Pas de réseau.")
                if (!i.wifi) return ToolResult("Le téléphone n'est pas connecté au Wi-Fi : je ne peux pas analyser le réseau local.")
                val brut = Reseau.scanner(app)
                // La Freebox sait qui est vraiment connecté : elle fait foi (plus d'appareils fantômes).
                val fbx = FreeboxOs.appareilsReseau(app)
                val liste = if (fbx == null) brut else { val actifs = fbx.map { it.ip }.toSet()
                    (brut.filter { it.ip in actifs || !it.faible } + fbx.filter { f -> brut.none { it.ip == f.ip } }).sortedBy { d -> d.ip.split(".").map { it.toIntOrNull() ?: 0 }.fold(0L) { x, y -> x * 256 + y } } }
                val imps = Reseau.decouvrir(app, listOf("_ipp._tcp", "_printer._tcp"), 3500)
                val neufs = Registre.appareils(app, liste, "Wi-Fi ${i.ip.substringBeforeLast('.')}.x")
                if (fbx != null) { Registre.appareils(app, fbx, "Wi-Fi ${i.ip.substringBeforeLast('.')}.x"); Registre.marquerAbsents(app, liste.map { it.ip }.toSet()); Registre.purger(app) }
                val alias = Registre.alias(app)
                val pub = Reseau.ippPublique()
                val chg = if (pub.isNotBlank()) Registre.ipPublique(app, pub, "Wi-Fi ${i.ip.substringBeforeLast('.')}.x") else ""
                val sb = StringBuilder("# 📡 Réseau ${i.ip}/${i.prefix} — ${liste.size} appareil(s)\n\n| IP | Nom | Type | Ports | Page web |\n|---|---|---|---|---|\n")
                val nom = { d: Reseau.Appareil -> (alias[d.ip] ?: d.info.ifBlank { d.nom }).take(34) }
                liste.forEach { d -> sb.append("| ${d.ip}${if (d.ip == i.ip) " (moi)" else if (d.ip == i.passerelle) " (box)" else ""} | ${nom(d)} | ${d.type} | ${d.ports.joinToString(",")} | ${d.titre.take(30)} |\n") }
                if (imps.isNotEmpty()) sb.append("\n**🖨️ Imprimantes (mDNS)** : " + imps.distinctBy { it.host }.joinToString(" · ") { "${it.nom} (${it.host})" } + "\n")
                sb.append("\n💾 $neufs nouvel(s) appareil(s) enregistré(s)." + (if (pub.isNotBlank()) " 🌍 IP publique : **$pub**." else "") + (if (chg.isNotBlank()) "\n$chg" else ""))
                sb.append("\n\n🔎 ${Reseau.diag}")
                if (liste.size <= 2 && Serveur.sshConfigure(app)) {
                    val l2 = runCatching { Serveur.scanReseau(app) }.getOrDefault(emptyList())
                    if (l2.isNotEmpty()) {
                        val n2 = Registre.appareils(app, l2, "Réseau du serveur")
                        sb.append("\n\n## 🖥️ Vus depuis ton serveur Debian (${l2.size})\n| IP | MAC | Type | Ports |\n|---|---|---|---|\n" + l2.joinToString("\n") { "| ${it.ip} | ${it.info.removePrefix("MAC ")} | ${it.type} | ${it.ports.joinToString(",")} |" } + "\n💾 $n2 nouvel(s) enregistré(s).")
                    }
                } else if (liste.size <= 2) sb.append("\n⚠️ Très peu d'appareils vus : la box isole peut-être les appareils Wi-Fi (« isolation AP/clients »), ou un VPN est actif. Essaie « scanne le réseau via le serveur » pour une analyse complète depuis le réseau du serveur.")
                ToolResult(sb.toString(), direct = true)
            }
            "appareil_sonder" -> {
                val ip = a.optString("ip").trim()
                if (!Reseau.prive(ip)) return ToolResult("Réseau local uniquement : $ip n'est pas une adresse privée.")
                val d = Reseau.appareil(ip) ?: return ToolResult("Aucune réponse de $ip.")
                ToolResult("# ${d.type} — ${d.ip}\n- Nom : ${d.nom.ifBlank { "—" }}\n- Ports ouverts : ${d.ports.joinToString(", ").ifBlank { "aucun des ports courants" }}\n- Page web : ${d.titre.ifBlank { "—" }}", direct = true)
            }
            "appareil_page" -> {
                val ip = a.optString("ip").trim(); val port = a.optString("port").toIntOrNull() ?: 80
                // Dehors : la page est lue depuis le serveur de la maison (le téléphone ne joint pas le réseau local).
                val parServeur = a.optString("via").lowercase().startsWith("serv") ||
                    (a.optString("via").isBlank() && Reseau.prive(ip) && Serveur.sshConfigure(app) && !AppareilsLies.aLaMaison(app))
                if (parServeur) {
                    ssh()
                    if (!Reseau.prive(ip)) return ToolResult("Réseau local uniquement : $ip n'est pas une adresse privée.")
                    val url = (if (port == 443 || port == 8443) "https" else "http") + "://$ip:$port/" + a.optString("chemin").trimStart('/')
                    ToolResult("```\n" + sortie(Serveur.exec(app, "curl -sk -m 15 ${Serveur.q(url)} | sed -e 's/<[^>]*>/ /g' | tr -s ' \\n\\t' ' ' | head -c 3500", 30)) + "\n```")
                } else ToolResult("```\n" + Reseau.lirePage(ip, port, a.optString("chemin")) + "\n```")
            }
            "imprimantes_trouver" -> {
                val via = a.optString("via").lowercase().let { v -> if (v.startsWith("serv")) "serveur" else if (v.isBlank() && Serveur.sshConfigure(app) &&
                    Reseau.prive(a.optString("ip").trim().substringBefore(":")) && !AppareilsLies.aLaMaison(app)) "serveur" else "local" }
                val ip = a.optString("ip").trim()
                if (via == "serveur") ssh()
                Impression.trace = StringBuilder()
                val chemins = HashMap<String, String>()
                val ips = LinkedHashSet<String>()
                var scan: List<Reseau.Appareil> = emptyList()
                if (ip.isNotBlank()) ips += ip else if (via == "local") {
                    val i = Reseau.infos(app)
                    if (i == null || !i.wifi) return ToolResult("Le téléphone n'est pas connecté au Wi-Fi : impossible de chercher une imprimante sur le réseau local. Connecte-toi au Wi-Fi de l'imprimante, ou donne-moi son adresse IP et utilise « via serveur » si elle est sur le réseau du serveur.")
                    Reseau.decouvrir(app, listOf("_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp"), 4500).forEach { ips += it.host; if (it.chemin.isNotBlank()) chemins[it.host] = "/" + it.chemin.trimStart('/') }
                    scan = Reseau.scanner(app)
                    scan.filter { 631 in it.ports || 9100 in it.ports || 515 in it.ports || it.type.startsWith("🖨") }.forEach { ips += it.ip }
                } else return ToolResult("Précise l'adresse IP de l'imprimante du réseau du serveur (ip=…).")
                val rep = ips.mapNotNull { Impression.interroger(app, it, via, chemins[it]) }
                if (rep.isNotEmpty()) return ToolResult("# 🖨️ Imprimantes\n\n" + rep.joinToString("\n") { p ->
                    "- **${p.nom}** (${p.modele.ifBlank { "modèle inconnu" }}) · ${p.ip} · ${p.etat}" + (if (p.raisons.isNotEmpty()) " ⚠️ ${p.raisons.joinToString()}" else "") + " · formats : ${p.formats.filter { it.startsWith("application/pdf") || it.startsWith("image/") || it.startsWith("text/") }.take(5).joinToString()}" }, direct = true)
                val sb = StringBuilder("# 🖨️ Aucune imprimante n'a répondu\n\n")
                if (ips.isNotEmpty()) sb.append("Appareils d'impression vus : ${ips.joinToString()} — mais sans réponse IPP.\n")
                if (scan.isNotEmpty()) sb.append("\n**Appareils du réseau (${scan.size})** :\n" + scan.take(40).joinToString("\n") { "- ${it.ip} · ${it.type} · ports ${it.ports.joinToString(",")}${if (it.titre.isNotBlank()) " · « ${it.titre.take(40)} »" else ""}" } + "\n")
                sb.append("\n**Détail des essais** :\n```\n${Impression.trace.toString().take(1200).ifBlank { "(aucune tentative)" }}\n```\n")
                sb.append("\nPistes : imprimante allumée et sur CE Wi-Fi (pas le réseau invité, pas Wi-Fi Direct) ; « isolation des appareils / AP isolation » désactivée sur la box ; IPP activé dans l'interface de l'imprimante (page web, rubrique Réseau/AirPrint/IPP). Tu peux aussi me donner son adresse IP (affichée sur son écran ou dans la page de la box).")
                ToolResult(sb.toString(), direct = true)
            }
            "tele" -> ToolResult(teleOutil(a), direct = true)
            "appareil_ouvrir" -> {
                val q = a.optString("appareil").trim()
                val n = fr.dd.jarvis.societe.core.normaliser(q)
                val lien = AppareilsLies.tousLiens(app).filter { it.web != null }.let { l ->
                    l.firstOrNull { fr.dd.jarvis.societe.core.normaliser(it.nom) == n || it.web!!.contains("//$q") }
                        ?: l.firstOrNull { val m = fr.dd.jarvis.societe.core.normaliser(it.nom); n.isNotBlank() && (m.contains(n) || n.contains(m)) }
                        ?: l.firstOrNull { it.emoji == "🖨️" && Regex("imprim").containsMatchIn(n) }
                } ?: Registre.trouver(app, q)?.let { e ->
                    val ip = e.optString("ip")
                    val ports = e.optJSONArray("ports")?.let { x -> (0 until x.length()).map { x.optInt(it) } }.orEmpty()
                    val p = a.optString("port").toIntOrNull() ?: listOf(80, 443, 8080, 8443, 8000, 5000, 9000, 8123, 32400).firstOrNull { it in ports } ?: 80
                    AppareilsLies.Lien("wifi_$ip", "", Registre.nomDe(e), "", "", "", true, web = "${if (p in setOf(443, 8443)) "https" else "http"}://$ip" + (if (p in setOf(80, 443)) "" else ":$p"))
                } ?: return ToolResult("Appareil « $q » introuvable : analyse le Wi-Fi (onglet Wi-Fi) ou donne son adresse IP.")
                val url = runCatching { AppareilsLies.adresseOuverture(app, lien) }.getOrElse { return ToolResult("❌ ${it.message}") }
                runCatching { app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                ToolResult("🔌 ${lien.nom} : $url" + (if (url.contains("127.0.0.1")) "\n🔐 Connexion par un tunnel sécurisé à travers ton serveur (tu es hors de la maison) : l'adresse reste valable tant que JARVIS est ouvert." else ""))
            }
            "appareil_masquer" -> {
                val q = a.optString("appareil").trim(); val remettre = a.optString("remettre").lowercase() in setOf("oui", "true", "1")
                val n = fr.dd.jarvis.societe.core.normaliser(q)
                AppareilsLies.tousLiens(app).firstOrNull { val m = fr.dd.jarvis.societe.core.normaliser(it.nom); m == n || (n.length >= 3 && (m.contains(n) || n.contains(m))) }?.let { l ->
                    AppareilsLies.masquer(app, l.id, !remettre)
                    return ToolResult(if (remettre) "↩️ ${l.nom} est de nouveau dans la liste « Liés à Jarvis »." else "🙈 ${l.nom} retiré de la liste « Liés à Jarvis » (Wi-Fi › Liés à Jarvis › Appareils retirés pour le remettre).")
                }
                val e = Registre.tous(app, avecMasques = true).firstOrNull { it.optString("ip") == q } ?: Registre.trouver(app, q)
                    ?: return ToolResult("Appareil « $q » introuvable.")
                Registre.masquer(app, e, !remettre)
                ToolResult(if (remettre) "↩️ ${Registre.nomDe(e)} réaffiché dans la liste du Wi-Fi." else "🙈 ${Registre.nomDe(e)} masqué de la liste du Wi-Fi.")
            }
            "imprimer" -> {
                val via = a.optString("via").lowercase().let { if (it.startsWith("serv")) "serveur" else "local" }
                if (via == "serveur") ssh()
                val ip = a.optString("ip").trim().removePrefix("http://").removePrefix("ipp://").substringBefore("/").substringBefore(":")
                if (a.optString("fichier").isBlank() && a.optString("texte").isBlank()) return ToolResult("Rien à imprimer : indique un fichier ou un texte.")
                if (!confirm("Imprimer sur $ip${if (via == "serveur") " (via le serveur)" else ""} : " + (a.optString("fichier").ifBlank { a.optString("texte").take(80) }) + " ?", true)) return ToolResult("Refusé par l'utilisateur.")
                ToolResult(Impression.imprimer(app, ip, a.optString("fichier"), a.optString("texte"), a.optString("titre"), a.optString("copies").toIntOrNull() ?: 1, via))
            }
            else -> ToolResult("Outil serveur inconnu.")
        }
    }

    private suspend fun teleOutil(a: JSONObject): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val act = fr.dd.jarvis.societe.core.normaliser(a.optString("action")).replace(' ', '_')
        if (act == "detecter") {
            val l = Tele.detecter(app)
            if (l.size == 1 && !Tele.configuree(app)) { app.store.teleIp.set(l[0].ip); app.store.teleType.set(l[0].type) }
            return@withContext if (l.isEmpty()) "Aucune télé repérée dans l'inventaire du Wi-Fi : analyse le Wi-Fi (télé allumée), ou donne son adresse IP (tele configurer ip=…)."
                else "📺 Télé(s) repérée(s) :\n" + l.joinToString("\n") { "• ${it.nom} — ${it.ip} — ${it.raison}" } + (if (Tele.configuree(app)) "\nTélé utilisée : ${Tele.ip(app)} (${Tele.type(app)})." else "")
        }
        if (act == "configurer") {
            val ip = a.optString("ip").trim()
            require(Reseau.prive(ip.substringBefore(':'))) { "adresse IP locale attendue (ex. 192.168.1.40)." }
            app.store.teleIp.set(ip); a.optString("type").trim().lowercase().takeIf { it in setOf("androidtv", "bravia", "samsung", "lg") }?.let { app.store.teleType.set(it) }
            return@withContext "✅ Télé : $ip (${Tele.type(app)}). Dis « connecte la télé » pour l'autoriser."
        }
        if (!Tele.configuree(app)) {
            val l = Tele.detecter(app)
            if (l.size == 1) { app.store.teleIp.set(l[0].ip); app.store.teleType.set(l[0].type) }
            else return@withContext "Quelle télé ? " + (if (l.isEmpty()) "Aucune repérée : analyse le Wi-Fi (télé allumée) ou donne son adresse IP." else l.joinToString(", ") { "${it.nom} (${it.ip})" })
        }
        val bravia = Tele.type(app) == "bravia" && app.store.teleCle.value.isNotBlank()
        runCatching {
            when (act) {
                "connecter" -> Tele.connecter(app)
                "etat" -> Tele.etat(app)
                "touche" -> Tele.touche(app, a.optString("nom"), a.optString("fois").toIntOrNull() ?: 1)
                "reglages" -> Tele.reglages(app, a.optString("page"))
                "appli" -> Tele.ouvrirAppli(app, a.optString("nom"))
                "applis" -> "📺 Applications : " + Tele.applis(app).joinToString(", ")
                "texte" -> Tele.texte(app, a.optString("texte"))
                "parametre" -> {
                    val v = a.optString("valeur").trim().ifBlank { null }
                    if (v != null && !confirm("Modifier le réglage ${a.optString("espace")}/${a.optString("cle")} de la télé → $v ?", true)) "Refusé."
                    else Tele.parametre(app, a.optString("espace"), a.optString("cle"), v)
                }
                "capture" -> Tele.capture(app)
                "image", "son" -> if (!bravia) "Réglages d'image/son directs : réservés aux Sony Bravia avec la clé pré-partagée (Réglages › Maison › Télé). Sinon : « ouvre les réglages image de la télé » puis navigue (haut/bas/ok)."
                    else Tele.braviaReglage(app, a.optString("cle").ifBlank { if (act == "son") "sound" else "" }.let { if (act == "son" && it.isBlank()) "volume" else it }, a.optString("valeur").trim().ifBlank { null })
                "alimentation" -> { val on = Regex("allum|on|reveil").containsMatchIn(fr.dd.jarvis.societe.core.normaliser(a.optString("nom") + a.optString("valeur")))
                    if (bravia) Tele.braviaAlimentation(app, on) else Tele.touche(app, if (on) "reveil" else "veille") }
                "entree" -> if (bravia) Tele.braviaEntree(app, a.optString("nom")) else Tele.touche(app, "source")
                "commande" -> { val c = a.optString("commande").trim(); if (c.isBlank()) "Commande vide." else if (!confirm("Exécuter sur la télé : $c ?", true)) "Refusé." else Tele.commande(app, c) }
                else -> "Action inconnue."
            }
        }.getOrElse { "❌ ${it.message}" }
    }

    private val gh = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()

    private fun ghGet(url: String): String {
        val b = okhttp3.Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", "JARVIS")
        app.store.ghToken.value.trim().takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        gh.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 404) throw IllegalStateException("Introuvable sur GitHub (dépôt privé ? ajoute un jeton dans Réglages › Maison › Serveur).")
            if (r.code == 403 && t.contains("rate limit", true)) throw IllegalStateException("Limite GitHub atteinte (ajoute un jeton GitHub dans Réglages › Maison › Serveur).")
            if (!r.isSuccessful) throw IllegalStateException("GitHub ${r.code}")
            return t
        }
    }

    /** Écriture GitHub (création/modification d'un fichier) : demande toujours un jeton avec les droits d'écriture. */
    private fun ghPut(url: String, body: JSONObject): JSONObject {
        val token = app.store.ghToken.value.trim()
        if (token.isBlank()) throw IllegalStateException("Écriture GitHub impossible : ajoute un jeton GitHub (droits « Contents: Read and write », ou scope « repo » pour un ancien jeton) dans Réglages › Maison › Serveur.")
        val b = okhttp3.Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", "JARVIS").header("Authorization", "Bearer $token")
            .put(body.toString().toRequestBody("application/json".toMediaType()))
        gh.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw IllegalStateException("GitHub ${r.code} : jeton refusé ou droits insuffisants (il faut la permission « Contents: Read and write » sur ce dépôt).")
            if (r.code == 404) throw IllegalStateException("Dépôt ou chemin introuvable sur GitHub (« $url »).")
            if (r.code == 409) throw IllegalStateException("Conflit GitHub : le fichier a changé entre-temps sur GitHub, relis-le puis réessaie.")
            if (!r.isSuccessful) throw IllegalStateException("GitHub ${r.code} : ${t.take(300)}")
            return JSONObject(t)
        }
    }

    /** Lit le sha courant d'un fichier GitHub s'il existe déjà (nécessaire pour le modifier, pas pour le créer). */
    private fun ghSha(depot: String, chemin: String, ref: String): String? = runCatching {
        JSONObject(ghGet("https://api.github.com/repos/$depot/contents/$chemin" + if (ref.isNotBlank()) "?ref=$ref" else "")).optString("sha").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun github(a: JSONObject): String {
        val d = a.optString("depot").trim().removePrefix("https://github.com/").trim('/')
        val ref = a.optString("ref").trim(); val ch = a.optString("chemin").trim().trim('/')
        return when (a.optString("action").lowercase()) {
            "ecrire" -> {
                if (ch.isBlank()) return "Précise le chemin du fichier (paramètre « chemin »)."
                val branche = a.optString("branche").trim()
                val sha = ghSha(d, ch, branche)
                val body = JSONObject()
                    .put("message", a.optString("message").trim().ifBlank { (if (sha != null) "Modifié" else "Créé") + " par JARVIS : $ch" })
                    .put("content", android.util.Base64.encodeToString(a.optString("contenu").toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
                sha?.let { body.put("sha", it) }
                branche.takeIf { it.isNotBlank() }?.let { body.put("branch", it) }
                val r = ghPut("https://api.github.com/repos/$d/contents/$ch", body)
                "✅ `$ch` " + (if (sha != null) "modifié" else "créé") + " sur `$d`" + (if (branche.isNotBlank()) " (branche $branche)" else "") +
                    " — commit `${r.optJSONObject("commit")?.optString("sha")?.take(7)}`."
            }
            "copier" -> {
                if (ch.isBlank()) return "Précise le chemin source (paramètre « chemin »)."
                val versChemin = a.optString("vers_chemin").trim().trim('/')
                if (versChemin.isBlank()) return "Précise le chemin de destination (paramètre « vers_chemin »)."
                val versDepot = a.optString("vers_depot").trim().removePrefix("https://github.com/").trim('/').ifBlank { d }
                val src = JSONObject(ghGet("https://api.github.com/repos/$d/contents/$ch" + if (ref.isNotBlank()) "?ref=$ref" else ""))
                if (src.optString("type") != "file") return "« $ch » n'est pas un fichier (copie de dossier non supportée pour l'instant)."
                val brancheDest = a.optString("branche").trim()
                val shaDest = ghSha(versDepot, versChemin, brancheDest)
                val body = JSONObject()
                    .put("message", a.optString("message").trim().ifBlank { "Copié par JARVIS : $d/$ch → $versDepot/$versChemin" })
                    .put("content", src.optString("content").replace("\n", ""))
                shaDest?.let { body.put("sha", it) }
                brancheDest.takeIf { it.isNotBlank() }?.let { body.put("branch", it) }
                ghPut("https://api.github.com/repos/$versDepot/contents/$versChemin", body)
                "✅ Copié : `$d/$ch` → `$versDepot/$versChemin`" + (if (brancheDest.isNotBlank()) " (branche $brancheDest)" else "") + "."
            }
            "fichier" -> {
                val j = JSONObject(ghGet("https://api.github.com/repos/$d/contents/$ch" + if (ref.isNotBlank()) "?ref=$ref" else ""))
                val txt = String(android.util.Base64.decode(j.optString("content").replace("\n", ""), android.util.Base64.DEFAULT), Charsets.UTF_8)
                "```\n" + txt.take(9000) + (if (txt.length > 9000) "\n… (tronqué)" else "") + "\n```"
            }
            "liste" -> {
                val arr = JSONArray(ghGet("https://api.github.com/repos/$d/contents/$ch" + if (ref.isNotBlank()) "?ref=$ref" else ""))
                (0 until arr.length()).joinToString("\n") { arr.getJSONObject(it).let { o -> (if (o.optString("type") == "dir") "📁 " else "📄 ") + o.optString("name") + (if (o.optString("type") != "dir") " (${o.optLong("size") / 1024} Ko)" else "") } }.ifBlank { "Dossier vide." }
            }
            "commits" -> {
                val arr = JSONArray(ghGet("https://api.github.com/repos/$d/commits?per_page=8" + if (ref.isNotBlank()) "&sha=$ref" else ""))
                (0 until arr.length()).joinToString("\n") { arr.getJSONObject(it).let { o -> "- `${o.optString("sha").take(7)}` ${o.getJSONObject("commit").optString("message").lineSequence().first().take(90)} — ${o.getJSONObject("commit").getJSONObject("author").optString("date").take(10)}" } }
            }
            "release" -> JSONObject(ghGet("https://api.github.com/repos/$d/releases/latest")).let { "**${it.optString("tag_name")}** · ${it.optString("name")} · ${it.optString("published_at").take(10)}\n${it.optString("body").take(1500)}" }
            "depot" -> JSONObject(ghGet("https://api.github.com/repos/$d")).let { "**${it.optString("full_name")}** — ${it.optString("description")}\n⭐ ${it.optInt("stargazers_count")} · branche ${it.optString("default_branch")} · langage ${it.optString("language")} · maj ${it.optString("pushed_at").take(10)}" }
            else -> JSONObject(ghGet("https://api.github.com/search/repositories?per_page=6&q=" + java.net.URLEncoder.encode(d, "UTF-8"))).getJSONArray("items").let { arr ->
                (0 until arr.length()).joinToString("\n") { arr.getJSONObject(it).let { o -> "- **${o.optString("full_name")}** ⭐${o.optInt("stargazers_count")} — ${o.optString("description").take(90)}" } }.ifBlank { "Aucun résultat." }
            }
        }
    }

    private suspend fun haConfig(a: JSONObject): ToolResult {
        val act = a.optString("action").lowercase()
        return when (act) {
            "journal" -> ToolResult(runCatching { "```\n" + Serveur.ha(app, "GET", "/api/error_log").takeLast(5000) + "\n```" }.getOrElse { "API Home Assistant indisponible : ${it.message?.take(180)}.\n\n" + HaSur.diagnostic(app) }, direct = true)
            "recharger" -> {
                val q = a.optString("quoi").ifBlank { "all" }.lowercase()
                val (d, s) = when (q) {
                    "automation", "automations" -> "automation" to "reload"
                    "script", "scripts" -> "script" to "reload"
                    "scene", "scenes" -> "scene" to "reload"
                    "core" -> "homeassistant" to "reload_core_config"
                    else -> "homeassistant" to "reload_all"
                }
                Serveur.ha(app, "POST", "/api/services/$d/$s")
                ToolResult("🔄 Rechargement ($d.$s) demandé.")
            }
            "redemarrer" -> {
                if (!confirm("Redémarrer Home Assistant ? (configuration vérifiée avant ; coupure de quelques minutes)", true))
                    ToolResult("Refusé par l'utilisateur.")
                else ToolResult(HaSur.redemarrer(app), direct = true)
            }
            "valider" -> ToolResult(Serveur.haValider(app)?.let { "❌ $it" } ?: "✅ Configuration valide.")
            "lister", "lire", "ecrire", "sauvegardes", "restaurer" ->
                ToolResult("⛔ Fichiers YAML Home Assistant non accessibles pour le moment : le SSH configuré cible Debian/Jarvis et non la machine HA. Les fonctions API restent disponibles ; un profil SSH HA séparé sera nécessaire pour réactiver ces opérations.")
            else -> ToolResult("Action inconnue : $act")
        }
    }
}
