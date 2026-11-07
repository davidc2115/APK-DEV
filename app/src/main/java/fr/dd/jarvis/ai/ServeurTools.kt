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
        val KEYWORDS = Regex("home ?assistant|domotique|\\bha\\b|ssh|serveur|server|yaml|docker|lumi[èe]re|volet|thermostat|automatisation|automation|sc[èe]ne\\b|configuration\\.yaml|debian|raspberry|esphome|zigbee|mqtt|capteur|\\bprise\\b|interrupteur|box|livebox|freebox|bbox|vpn|wireguard|openvpn|redirection|\\bnat\\b|dmz|dhcp|disque|nas|\\bftp\\b|\\bsmb\\b|r[ée]seau|wifi|wi-fi|imprim|scann|\\bip\\b|adresse ip|github|\\bgit\\b|canon|epson|brother|\\bhp\\b|appareils?\\b|script|\\bcode\\b|ping|port|volume|\\bson\\b|t[ée]l[ée]\\b|t[ée]l[ée]vision|\\btv\\b|chauffage|climatisation|temp[ée]rature|consigne|po[êe]le|poil|degr[ée]s|granul[ée]s|pellet|chaudi[èe]re|radiateur|thermique|climate|media_player|lecteur multim[ée]dia|luminosit[ée]|store\\b|persienne|\\ballume\\b|allumer|[ée]teins?\\b|[ée]teindre", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = KEYWORDS.containsMatchIn(t)
        val NAMES = setOf("ha_entites", "ha_etat", "ha_service", "ha_config", "ha_dashboard", "ssh_commande", "ssh_fichier", "serveur_diagnostic", "serveur_scan_ports", "routeur_diagnostiquer", "reseau_infos", "reseau_scanner", "appareil_sonder", "appareil_page", "imprimantes_trouver", "imprimer", "ssh_script", "github", "appareils_liste", "appareil_nommer", "ip_publique", "box_disque", "freebox")
    }

    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun tool(name: String, desc: String, props: Map<String, JSONObject>, req: List<String> = emptyList()): JSONObject {
        val p = JSONObject(); props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(req))))
    }

    val schema: List<JSONObject> = listOf(
        tool("ha_entites", "Home Assistant : liste/recherche les appareils et capteurs (lumières, volets, températures…) avec leur état. La liste est paginée : si total > affichées, rappelle l'outil avec le décalage suivant pour parcourir toutes les entités.",
            mapOf("recherche" to prop("Mot du nom ou de l'identifiant (optionnel)."), "domaine" to prop("light, switch, sensor, climate, cover, automation… (optionnel)."), "decalage" to prop("Index de départ (0 par défaut ; utiliser affichées comme prochain décalage)."), "limite" to prop("Nombre d'entités à afficher (défaut 100, maximum 250)."))),
        tool("ha_etat", "Home Assistant : état détaillé d'une entité.", mapOf("entity_id" to prop("ex. light.salon")), listOf("entity_id")),
        tool("ha_service", "Home Assistant : exécute réellement une commande sur un appareil. Pour un poêle/thermostat : climate.set_temperature avec donnees {temperature: 20}, climate.set_hvac_mode si pris en charge. Pour une télévision/lecteur : media_player.turn_on, media_player.turn_off, media_player.volume_set avec {volume_level: 0.35}, media_player.volume_up, media_player.volume_down, media_player.media_play_pause, media_player.select_source avec {source: \"…\"}. Pour une lumière : light.turn_on/off. Cherche d'abord l'identifiant exact avec ha_entites, puis utilise ha_etat pour connaître les attributs et capacités. N'annonce jamais le succès avant le résultat de l'appel.",
            mapOf("domaine" to prop("Domaine HA exact : climate, media_player, light, switch, cover, automation, scene, script…"), "service" to prop("Service HA exact : set_temperature, turn_on, turn_off, volume_set, volume_up, volume_down, select_source, media_play_pause…"),
                "entity_id" to prop("Identifiant exact de l'entité, ex. climate.poele ou media_player.television."), "donnees" to JSONObject().put("type", "object").put("description", "Objet JSON de paramètres du service. Exemples : {temperature: 20}, {volume_level: 0.35}, {source: \"HDMI 1\"}. Laisser vide si aucun paramètre n'est nécessaire.")), listOf("domaine", "service")),
        tool("ha_config", "Home Assistant : configuration complète sur le serveur (fichiers YAML dans le dossier de config : configuration.yaml, automations.yaml, scripts.yaml, scenes.yaml, secrets.yaml, packages/…). Actions : lister | lire | ecrire (sauvegarde auto + validation, restauration si invalide) | sauvegardes | restaurer | valider | recharger | redemarrer | journal (erreurs). Toujours lire le fichier avant de le modifier et réécrire le fichier ENTIER.",
            mapOf("action" to prop("lister | lire | ecrire | sauvegardes | restaurer | valider | recharger | redemarrer | journal"), "fichier" to prop("Chemin relatif au dossier de config, ex. automations.yaml (ou dossier pour lister)."),
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
        tool("ssh_commande", "Serveur SSH : exécute une commande shell sur le serveur (Debian) et retourne la sortie. Contrôle complet : docker, systemctl, apt, logs, réseau… Les commandes destructrices demandent une confirmation. PAS pour consulter ou piloter Home Assistant (utilise les outils ha_*), seulement pour la maintenance du serveur demandée explicitement.",
            mapOf("commande" to prop("Commande shell."), "delai" to prop("Délai maximal en secondes (défaut 60)."),
                "hote" to prop("Autre machine à joindre en SSH (IP/nom) au lieu du serveur des Réglages (optionnel)."), "utilisateur" to prop("Utilisateur de cette autre machine."),
                "mot_de_passe" to prop("Mot de passe de cette autre machine."), "port" to prop("Port SSH (défaut 22)."), "via_serveur" to prop("« oui » : joindre cette machine DU RÉSEAU LOCAL à travers le serveur Debian (connexion à distance).")), listOf("commande")),
        tool("ssh_fichier", "Serveur SSH : lit, écrit (sauvegarde auto) ou liste des fichiers du serveur.",
            mapOf("action" to prop("lire | ecrire | lister"), "chemin" to prop("Chemin absolu sur le serveur."), "contenu" to prop("ecrire : contenu complet.")), listOf("action", "chemin")),
        tool("ssh_script", "Serveur SSH : envoie et exécute un script complet (bash ou python3, plusieurs lignes) sur le serveur Debian ; sert à installer, configurer, récupérer des données (git clone, curl…), automatiser.",
            mapOf("code" to prop("Code du script (plusieurs lignes)."), "langage" to prop("bash (défaut) ou python3."), "delai" to prop("Délai maximal en secondes (défaut 120).")), listOf("code")),
        tool("github", "GitHub (public, ou privé avec un jeton enregistré dans Réglages › Serveur) : lit un fichier, liste un dossier, derniers commits, dernière version (release), infos ou recherche de dépôts. Si le jeton a les droits d'écriture (« repo » / « Contents: Read and write »), peut aussi CRÉER ou MODIFIER un fichier directement sur GitHub (action=ecrire), ou COPIER un fichier d'un chemin/dépôt vers un autre (action=copier) — sans passer par le serveur SSH ni un clone local.",
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
        tool("appareils_liste", "Réseau : liste les appareils enregistrés lors des analyses (IP, nom, type, ports, dernière vue) et les IP publiques vues.", emptyMap()),
        tool("appareil_nommer", "Mémoire réseau : attribue un nom humain à une adresse IP (ex. « Imprimante Canon bureau », « Freebox », « NAS Debian »). Le nom est conservé lors des prochains scans ; si le scan connaît l'adresse MAC, Jarvis rattache automatiquement le même appareil à sa nouvelle IP DHCP et conserve l'ancien IP dans l'historique. Peut aussi créer une fiche d'identification avant le prochain scan.", mapOf("ip" to prop("Adresse IPv4 privée actuelle de l'appareil."), "nom" to prop("Nom clair à mémoriser pour cet appareil.")), listOf("ip", "nom")),
        tool("ip_publique", "Réseau : détecte l'adresse IP publique actuelle (téléphone et, si SSH configuré, serveur) et l'enregistre pour une connexion à distance ; signale si elle a changé.", emptyMap()),
        tool("freebox", "Freebox : PILOTAGE COMPLET de la Freebox via Freebox OS, avec les droits accordés à JARVIS dans Freebox OS (Gestion des accès › Applications). Lire : fichiers (contenu du DISQUE DUR de la box : paramètre chemin, ex. « Téléchargements » ou « Vidéos/Films » ; vide = liste des disques), telechargements (gestionnaire de téléchargements de la box), etat (connexion, débits, IP publique), appareils (appareils connectés au réseau — PAS le disque), redirections (ports), vpn (serveurs VPN et état), vpn_utilisateurs, wifi, dmz, baux_dhcp (IP réservées), appels (journal de la ligne fixe), droits. Modifier (confirmation demandée) : ajouter_redirection, supprimer_redirection, activer_redirection, desactiver_redirection, activer_vpn, desactiver_vpn, ajouter_vpn_utilisateur, supprimer_vpn_utilisateur, config_vpn (génère le fichier WireGuard/OpenVPN à importer), wifi_activer, wifi_desactiver, dmz (etat=activer + appareil/ip, ou etat=desactiver), ajouter_bail, redemarrer. FICHIERS DU DISQUE DE LA BOX (sans SMB/FTP) : chercher_fichier (nom), renommer (chemin + nouveau_nom), deplacer / copier (chemin — plusieurs séparés par « ; », jokers « Téléchargements/*.mkv » acceptés — + destination, dossier créé s'il manque), supprimer_fichier (chemin, confirmation), creer_dossier (chemin), envoyer_fichier (fichier du téléphone + destination), recuperer_fichier (chemin → copie sur le téléphone), lire_fichier (fichier texte). Utilise CET outil pour toute demande sur la Freebox : ne réponds jamais que tu ne peux pas la configurer.",
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
                "nombre" to prop("appels : nombre d'appels à afficher (défaut 15).")), listOf("action")),
        tool("box_disque", "Box internet : accède au disque dur branché sur la box (Freebox associée : par Freebox OS, sans réglage ; autres box : SMB ou FTP, selon Réglages › Box) : liste les partages et dossiers, lit un fichier texte, le télécharge dans l'appli, y envoie un fichier créé par JARVIS, crée un dossier, supprime, renomme ou déplace un fichier/dossier (utile pour réorganiser une bibliothèque de films/séries pour Plex, par exemple), ou lance directement la lecture d'une vidéo dans le lecteur vidéo du téléphone (aucune app spécifique requise, pas de téléchargement).",
            mapOf("action" to prop("partages | lister | lire | telecharger | envoyer | supprimer | dossier | renommer | deplacer | lire_telephone"), "chemin" to prop("Chemin sur la box : partage/dossier/fichier (ex. « Disque dur/Photos/2026 »). Pour renommer/deplacer : le chemin ACTUEL (source)."), "fichier" to prop("envoyer : nom du fichier créé par JARVIS à copier sur la box. renommer/deplacer : le NOUVEAU chemin complet sur la box (ex. « Disque dur/Films/Dirty Dancing (1987).avi »).")), listOf("action")),
        tool("serveur_diagnostic", "Diagnostic rapide du serveur : charge, mémoire, disque, conteneurs Docker et derniers logs de Home Assistant.", emptyMap()),
        tool("serveur_scan_ports", "Dépannage réseau : teste DIRECTEMENT DEPUIS LE TÉLÉPHONE (pas besoin que SSH fonctionne) quels ports répondent sur le serveur — utile en premier recours quand SSH refuse de se connecter (mauvais port probable). Teste les ports SSH non standards les plus courants et quelques ports de services courants, identifie lequel répond vraiment comme un serveur SSH (bannière « SSH-… »), et peut corriger le port SSH dans Réglages si tu le demandes et que l'utilisateur confirme. Ne fonctionne que si le téléphone peut atteindre cette adresse (même réseau local que le serveur, ou port déjà redirigé vers l'extérieur).",
            mapOf("hote" to prop("Adresse à tester (optionnel, défaut : l'adresse du serveur dans Réglages)."), "ports" to prop("Ports supplémentaires à tester, séparés par des virgules (optionnel, en plus des ports courants).")))
    )

    private fun sortie(r: Pair<Int, String>, max: Int = 5500): String {
        val t = r.second.trim().ifBlank { "(aucune sortie)" }
        return (if (t.length > max) "…" + t.takeLast(max) else t) + (if (r.first != 0) "\n[code de sortie ${r.first}]" else "")
    }

    suspend fun execute(name: String, a: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try { run(name, a) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ToolResult("❌ ${e.message ?: e.javaClass.simpleName}") }
    }

    private suspend fun run(name: String, a: JSONObject): ToolResult {
        fun ssh() { if (!Serveur.sshConfigure(app)) throw IllegalStateException("SSH non configuré (Réglages › Serveur : adresse, utilisateur, mot de passe ou clé).") }
        return when (name) {
            "ha_dashboard" -> {
                val action = a.optString("action").lowercase()
                if (action in setOf("creer", "modifier", "refaire", "carte_ajouter", "vue_ajouter", "supprimer")) {
                    val cible = a.optString("url_path").ifBlank { "tableau par défaut" }
                    if (!confirm("Home Assistant : effectuer « $action » sur $cible ? Une sauvegarde sera créée avant modification.", true)) return ToolResult("Opération annulée.")
                }
                ToolResult(HomeAssistantDashboard.execute(app, action, a), direct = true)
            }
            "ha_entites" -> ToolResult(Serveur.haEntites(app, a.optString("recherche"), a.optString("domaine"), a.optString("decalage").toIntOrNull() ?: 0, a.optString("limite").toIntOrNull() ?: 100), direct = true)
            "ha_etat" -> ToolResult(Serveur.haEtat(app, a.optString("entity_id").trim()))
            "ha_service" -> {
                val d = a.optString("domaine").trim(); val s = a.optString("service").trim(); val e = a.optString("entity_id").trim()
                val sensible = (d in setOf("lock", "alarm_control_panel") || s in setOf("restart", "stop", "unlock", "alarm_disarm", "open_cover") && d != "light") || (d == "homeassistant" && s in setOf("restart", "stop"))
                if (sensible && !confirm("Home Assistant : exécuter $d.$s ${e}?", true)) return ToolResult("Refusé par l'utilisateur.")
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
                if ((Serveur.dangereuse(cmd) || cible != null) && !confirm("Exécuter sur ${cible?.hote ?: app.store.sshHost.value} :\n\n$cmd", true)) return ToolResult("Refusé par l'utilisateur.")
                ToolResult("```\n" + sortie(Serveur.exec(app, cmd, (a.optString("delai").toIntOrNull() ?: 60).coerceIn(5, 600), cible)) + "\n```")
            }
            "ssh_fichier" -> {
                ssh()
                val p = a.optString("chemin").trim()
                when (a.optString("action").lowercase()) {
                    "lire" -> ToolResult("```\n" + Serveur.lire(app, p).take(9000) + "\n```")
                    "lister" -> ToolResult("```\n" + Serveur.lister(app, p) + "\n```")
                    "ecrire" -> {
                        val c = a.optString("contenu")
                        if (!confirm("Écrire le fichier $p sur le serveur (${c.length} caractères, sauvegarde automatique) ?", true)) return ToolResult("Refusé par l'utilisateur.")
                        val bak = Serveur.ecrire(app, p, c)
                        ToolResult("✅ $p écrit." + (bak?.let { " Sauvegarde : $it" } ?: ""))
                    }
                    else -> ToolResult("Action inconnue (lire, ecrire, lister).")
                }
            }
            "serveur_diagnostic" -> {
                ssh()
                val r = Serveur.exec(app, "uptime; echo; free -h | head -2; echo; df -h / | tail -1; echo; docker ps -a --format 'table {{.Names}}\\t{{.Status}}\\t{{.Image}}' 2>&1 | head -20; echo; n=\$(docker ps -a --format '{{.Names}}\\t{{.Image}}' 2>/dev/null | grep -i home-assistant | head -1 | cut -f1); [ -n \"\$n\" ] && { echo \"== logs \$n ==\"; docker logs --tail 25 \$n 2>&1; }; echo; echo \"== Home Assistant (local, port 8123) ==\"; curl -s -o /dev/null -m 5 -w 'HTTP %{http_code} en %{time_total}s\\n' http://localhost:8123/ 2>&1 || echo \"injoignable en local sur le serveur (curl a échoué)\"", 60)
                ToolResult("# 🖥️ Serveur\n```\n" + sortie(r, 6000) + "\n```" +
                    "\n\n_Le test « Home Assistant (local) » est fait DEPUIS le serveur Debian : s'il répond en local mais pas depuis le téléphone, le problème est le réseau entre le téléphone et le serveur (ou l'adresse/port dans Réglages › Serveur), pas Home Assistant lui-même._")
            }
            "serveur_scan_ports" -> {
                val hote = a.optString("hote").trim().ifBlank { app.store.sshHost.value.trim() }
                if (hote.isBlank()) return ToolResult("Aucune adresse à tester : précise « hote », ou renseigne l'adresse du serveur dans Réglages › Serveur.")
                val extra = a.optString("ports").split(",").mapNotNull { it.trim().toIntOrNull() }
                val ports = (Serveur.PORTS_SSH_COURANTS + Serveur.PORTS_SERVICES_COURANTS + extra).distinct()
                val ouverts = Serveur.scannerPorts(hote, ports).filter { it.second }.map { it.first }
                if (ouverts.isEmpty()) return ToolResult(
                    "Aucun des ${ports.size} ports testés ne répond sur $hote. Soit le téléphone n'a pas accès à cette adresse depuis ce réseau (es-tu sur le même Wi-Fi que le serveur ? sinon le port doit être redirigé vers l'extérieur sur la box, avec l'adresse publique/DDNS renseignée dans Réglages › Serveur › Adresse distante), soit le serveur est éteint.")
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
                    } else sb.append("Tu peux le corriger toi-même dans Réglages › Serveur.")
                }
                ToolResult(sb.toString(), direct = true)
            }
            "ha_config" -> haConfig(a)
            "ssh_script" -> {
                ssh()
                val code = a.optString("code")
                if (code.isBlank()) return ToolResult("Script vide.")
                val py = a.optString("langage").lowercase().startsWith("py")
                if (!confirm("Exécuter ce script ${if (py) "Python" else "bash"} sur le serveur ${app.store.sshHost.value} ?\n\n" + code.take(900), true)) return ToolResult("Refusé par l'utilisateur.")
                val f = "/tmp/jarvis_script_${System.currentTimeMillis()}" + if (py) ".py" else ".sh"
                Serveur.envoyerOctets(app, f, (if (py) code else "set -e\n$code").toByteArray())
                val r = Serveur.exec(app, "${if (py) "python3" else "bash"} $f; c=\$?; rm -f $f; exit \$c", (a.optString("delai").toIntOrNull() ?: 120).coerceIn(10, 900))
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
                    if (h.isBlank()) return ToolResult("Adresse de la box inconnue : connecte-toi à son Wi-Fi ou renseigne-la dans Réglages › Box.")
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
                val liste = Reseau.scanner(app)
                val imps = Reseau.decouvrir(app, listOf("_ipp._tcp", "_printer._tcp"), 3500)
                val neufs = Registre.appareils(app, liste, "Wi-Fi ${i.ip.substringBeforeLast('.')}.x")
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
                if (a.optString("via").lowercase().startsWith("serv")) {
                    ssh()
                    if (!Reseau.prive(ip)) return ToolResult("Réseau local uniquement : $ip n'est pas une adresse privée.")
                    val url = (if (port == 443 || port == 8443) "https" else "http") + "://$ip:$port/" + a.optString("chemin").trimStart('/')
                    ToolResult("```\n" + sortie(Serveur.exec(app, "curl -sk -m 15 ${Serveur.q(url)} | sed -e 's/<[^>]*>/ /g' | tr -s ' \\n\\t' ' ' | head -c 3500", 30)) + "\n```")
                } else ToolResult("```\n" + Reseau.lirePage(ip, port, a.optString("chemin")) + "\n```")
            }
            "imprimantes_trouver" -> {
                val via = a.optString("via").lowercase().let { if (it.startsWith("serv")) "serveur" else "local" }
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

    private val gh = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()

    private fun ghGet(url: String): String {
        val b = okhttp3.Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", "JARVIS")
        app.store.ghToken.value.trim().takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        gh.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 404) throw IllegalStateException("Introuvable sur GitHub (dépôt privé ? ajoute un jeton dans Réglages › Serveur).")
            if (r.code == 403 && t.contains("rate limit", true)) throw IllegalStateException("Limite GitHub atteinte (ajoute un jeton GitHub dans Réglages › Serveur).")
            if (!r.isSuccessful) throw IllegalStateException("GitHub ${r.code}")
            return t
        }
    }

    /** Écriture GitHub (création/modification d'un fichier) : demande toujours un jeton avec les droits d'écriture. */
    private fun ghPut(url: String, body: JSONObject): JSONObject {
        val token = app.store.ghToken.value.trim()
        if (token.isBlank()) throw IllegalStateException("Écriture GitHub impossible : ajoute un jeton GitHub (droits « Contents: Read and write », ou scope « repo » pour un ancien jeton) dans Réglages › Serveur.")
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
        when (act) {
            "journal" -> return ToolResult("```\n" + Serveur.ha(app, "GET", "/api/error_log").takeLast(5000) + "\n```")
            "recharger" -> {
                val q = a.optString("quoi").ifBlank { "all" }.lowercase()
                val (d, s) = when (q) { "automation", "automations" -> "automation" to "reload"; "script", "scripts" -> "script" to "reload"; "scene", "scenes" -> "scene" to "reload"; "core" -> "homeassistant" to "reload_core_config"; else -> "homeassistant" to "reload_all" }
                Serveur.ha(app, "POST", "/api/services/$d/$s")
                return ToolResult("🔄 Rechargement ($d.$s) demandé.")
            }
            "redemarrer" -> {
                if (!confirm("Redémarrer Home Assistant ?", true)) return ToolResult("Refusé par l'utilisateur.")
                val err = Serveur.haValider(app)
                if (err != null) return ToolResult("⛔ Redémarrage annulé : $err")
                Serveur.ha(app, "POST", "/api/services/homeassistant/restart")
                return ToolResult("🔁 Redémarrage de Home Assistant lancé (1 à 2 minutes).")
            }
            "valider" -> return ToolResult(Serveur.haValider(app)?.let { "❌ $it" } ?: "✅ Configuration valide.")
        }
        if (!Serveur.sshConfigure(app)) return ToolResult("SSH non configuré : l'accès aux fichiers de configuration passe par le serveur (Réglages › Serveur).")
        val dir = Serveur.haDossier(app) ?: return ToolResult("Dossier de configuration Home Assistant introuvable : indique-le dans Réglages › Serveur (là où se trouve configuration.yaml).")
        fun chemin(f: String): String {
            val r = f.trim().trimStart('/').ifBlank { "configuration.yaml" }
            if (r.contains("..")) throw IllegalArgumentException("Chemin non autorisé.")
            return "$dir/$r"
        }
        return when (act) {
            "lister" -> ToolResult("📁 $dir\n```\n" + Serveur.lister(app, if (a.optString("fichier").isBlank()) dir else chemin(a.optString("fichier"))) + "\n```")
            "lire" -> ToolResult("```yaml\n" + Serveur.lire(app, chemin(a.optString("fichier"))).take(12000) + "\n```")
            "sauvegardes" -> ToolResult("```\n" + Serveur.exec(app, "ls -1t ${Serveur.q(dir)}/*.jarvis-bak-* ${Serveur.q(dir)}/*/*.jarvis-bak-* 2>/dev/null | head -30").second.ifBlank { "Aucune sauvegarde." } + "\n```")
            "ecrire" -> {
                val f = chemin(a.optString("fichier")); val c = a.optString("contenu")
                if (c.isBlank()) return ToolResult("Contenu vide : rien écrit.")
                if (c.contains('\t') && f.endsWith(".yaml") ) return ToolResult("❌ Le YAML n'accepte pas les tabulations : réécris avec des espaces.")
                if (!confirm("Modifier ${f.removePrefix("$dir/")} dans la configuration Home Assistant (sauvegarde + validation automatiques) ?", true)) return ToolResult("Refusé par l'utilisateur.")
                val bak = Serveur.ecrire(app, f, if (c.endsWith("\n")) c else c + "\n")
                val err = if (f.endsWith(".yaml") || f.endsWith(".yml")) Serveur.haValider(app) else null
                if (err != null && !err.startsWith("Validation impossible")) {
                    if (bak != null) Serveur.exec(app, "cp -p ${Serveur.q(bak)} ${Serveur.q(f)} 2>/dev/null || sudo cp -p ${Serveur.q(bak)} ${Serveur.q(f)}")
                    ToolResult("⛔ Fichier refusé par la validation, ancienne version restaurée.\n$err")
                } else ToolResult("✅ ${f.removePrefix("$dir/")} enregistré." + (bak?.let { " Sauvegarde : ${it.substringAfterLast('/')}." } ?: "") +
                    (if (err != null) "\n⚠️ $err" else if (f.endsWith(".yaml")) "\nConfiguration valide — dis « recharge » (ou « redémarre ») pour l'appliquer." else ""))
            }
            "restaurer" -> {
                val nom = a.optString("contenu").trim().substringAfterLast('/')
                if (!nom.contains(".jarvis-bak-")) return ToolResult("Indique le nom de la sauvegarde (voir action « sauvegardes »).")
                val src = Serveur.exec(app, "find ${Serveur.q(dir)} -name ${Serveur.q(nom)} | head -1").second.trim()
                if (src.isBlank()) return ToolResult("Sauvegarde introuvable.")
                val cible = src.substringBefore(".jarvis-bak-")
                if (!confirm("Restaurer ${cible.removePrefix("$dir/")} depuis cette sauvegarde ?", true)) return ToolResult("Refusé par l'utilisateur.")
                val r = Serveur.exec(app, "cp -p ${Serveur.q(src)} ${Serveur.q(cible)} 2>/dev/null || sudo cp -p ${Serveur.q(src)} ${Serveur.q(cible)}")
                ToolResult(if (r.first == 0) "↩️ ${cible.removePrefix("$dir/")} restauré." else "❌ ${r.second.take(200)}")
            }
            else -> ToolResult("Action inconnue : $act")
        }
    }
}
