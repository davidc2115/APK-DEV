package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Administration du serveur Debian uniquement, en plus de ssh_commande / ssh_script :
 *  - serveur_service : un service par son nom (« Home Assistant », « Plex », « bot Discord »…) qu'il soit conteneur Docker
 *    (compose ou non), service systemd ou processus pm2 : état, journaux, démarrer, arrêter, redémarrer, mettre à jour,
 *    lancement automatique ;
 *  - serveur_tache : tâches longues détachées (restauration, mises à jour, copies) suivies par leur journal ;
 *  - ha_sauvegarde : sauvegardes Home Assistant (lister, créer, RESTAURER même quand Home Assistant est arrêté, y compris
 *    une sauvegarde chiffrée) et remise en service ;
 *  - serveur_fichiers : chercher, infos, copier, déplacer, supprimer, archiver, extraire, récupérer sur le téléphone, envoyer.
 */
class ServeurAdminTools(private val app: JarvisApp, private val confirm: suspend (String, Boolean) -> Boolean) {
    companion object {
        val NAMES = setOf("serveur_service", "serveur_tache", "ha_sauvegarde", "serveur_fichiers")
        private val MOTS = Regex("home ?assistant|\\bha\\b|stockage|volumes?|sauvegarde|restaur|backup|remettre en (service|route)|r[ée]par|ne d[ée]marre|plant[ée]|\\bbot\\b|discord|plex|\\bservices?\\b|conteneur|\\blogs?\\b|journaux|t[âa]che|mise à jour|mettre à jour|fichiers? (du|sur le) serveur|ssh|serveur|debian|docker", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = MOTS.containsMatchIn(t)
        private const val TACHES = "/tmp/jarvis_taches"

        /** Mots d'un service → motifs probables de son nom de conteneur / unité / processus. */
        /** Home Assistant reste piloté par son API ; SSH Debian sert à inspecter les volumes en lecture seule. */
        internal fun actionLectureSeuleHa(action: String): Boolean =
            fr.dd.jarvis.societe.core.normaliser(action).replace(' ', '_') in setOf("etat", "logs", "stockage")

        internal fun motifs(service: String): List<String> {
            val n = fr.dd.jarvis.societe.core.normaliser(service)
            return when {
                Regex("home ?assistant|\\bha\\b|domotique|hass").containsMatchIn(n) -> listOf("homeassistant", "home-assistant", "home_assistant", "hass")
                Regex("plex").containsMatchIn(n) -> listOf("plex")
                Regex("discord|\\bbot\\b").containsMatchIn(n) -> listOf("discord", "bot")
                Regex("ollama").containsMatchIn(n) -> listOf("ollama")
                Regex("nginx|proxy").containsMatchIn(n) -> listOf("nginx", "proxy", "traefik", "caddy")
                Regex("portainer").containsMatchIn(n) -> listOf("portainer")
                Regex("mosquitto|mqtt").containsMatchIn(n) -> listOf("mosquitto", "mqtt")
                Regex("zigbee|z2m").containsMatchIn(n) -> listOf("zigbee2mqtt", "zigbee")
                else -> n.split(' ').filter { it.length >= 3 && it !in setOf("le", "les", "service", "serveur", "conteneur", "mon", "docker") }
            }
        }

        /** Commande shell, valeur protégée entre apostrophes. */
        internal fun q(s: String) = Serveur.q(s)
    }

    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun tool(name: String, description: String, props: Map<String, JSONObject>, required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                .put("required", JSONArray(required))))

    val schema = listOf(
        tool("serveur_service", "Serveur Debian via SSH : distingue l’hôte Debian et les services qui y tournent. Plex : état, journaux, stockage Docker, démarrer/arrêter/redémarrer/mettre à jour. Home Assistant : état, journaux et stockage Docker en lecture seule ; les entités, états domotiques, services et redémarrages normaux passent par l’API Home Assistant (ha_*), jamais par SSH. « stockage » affiche les chemins hôte ↔ conteneur des volumes montés, sans lire ni modifier les fichiers. Actions : lister | etat | logs | stockage | demarrer | arreter | redemarrer | mettre_a_jour | activer | desactiver.",
            mapOf("action" to prop("lister | etat | logs | stockage | demarrer | arreter | redemarrer | mettre_a_jour | activer | desactiver"),
                "service" to prop("Nom du service (« Home Assistant », « Plex », « bot Discord »…) ou nom exact du conteneur / de l'unité."),
                "lignes" to prop("Logs : nombre de lignes (défaut 80).")), listOf("action")),
        tool("serveur_tache", "Serveur Debian : TÂCHE LONGUE en arrière-plan (au-delà de quelques minutes : restauration, mise à jour, copie, compilation…) qui continue même si la connexion se coupe ; puis suivi de son journal. Actions : lancer (code bash) | etat | liste | arreter.",
            mapOf("action" to prop("lancer | etat | liste | arreter"), "code" to prop("lancer : script bash complet."), "nom" to prop("lancer : nom court de la tâche."),
                "root" to prop("« oui » : exécuter en root (automatique si le script contient sudo)."), "id" to prop("etat / arreter : identifiant (défaut : la dernière tâche).")), listOf("action")),
        tool("ha_sauvegarde", "DÉSACTIVÉ : aucune sauvegarde, inspection de conteneur ou restauration Home Assistant ne doit passer par le SSH Debian. Utilise les fonctions API Home Assistant ; les archives hors ligne nécessiteront un profil SSH HA indépendant.",
            mapOf("action" to prop("lister | creer | restaurer | etat"),
                "sauvegarde" to prop("restaurer : chemin complet, nom, ou date de la sauvegarde (« derniere » = la plus récente) ; voir lister."),
                "nom" to prop("creer : nom de la sauvegarde (optionnel).")), listOf("action")),
        tool("serveur_fichiers", "Serveur Debian : fichiers et dossiers. Pour localiser le stockage Home Assistant ou Plex, utilise d’abord serveur_service action=stockage (inspection des volumes Docker en lecture seule) ; ne déduis jamais un chemin depuis le nom du service. Les fichiers YAML Home Assistant ne doivent pas être modifiés ici : utilise les fonctions HA sécurisées. Actions : chercher (nom ou motif, dans un dossier) | info (taille, droits, contenu d'un dossier) | copier | deplacer | supprimer | archiver (tar.gz) | extraire (tar, tar.gz, zip) | recuperer (télécharge le fichier sur le téléphone, dossier Download/Jarvis) | envoyer (fichier du téléphone → serveur).",
            mapOf("action" to prop("chercher | info | copier | deplacer | supprimer | archiver | extraire | recuperer | envoyer"),
                "chemin" to prop("Chemin sur le serveur (source ; pour envoyer : destination)."), "destination" to prop("copier / deplacer / archiver / extraire : chemin de destination."),
                "motif" to prop("chercher : nom ou motif (ex. *.tar, backup)."), "fichier_telephone" to prop("envoyer : chemin du fichier sur le téléphone."),
                "root" to prop("« oui » : en root (fichiers système, dossiers d'autres utilisateurs).")), listOf("action")),
    )

    private fun sortie(r: Pair<Int, String>, max: Int = 6000): String {
        val t = r.second.trim().ifBlank { "(aucune sortie)" }
        return (if (t.length > max) "…" + t.takeLast(max) else t) + (if (r.first != 0 && r.first > -2) "\n[code de sortie ${r.first}]" else "")
    }

    /** Droits root possibles sans terminal (mot de passe enregistré, ou compte root). */
    private fun root() = app.store.sshUser.value.trim() == "root" || app.store.sshPass.value.isNotBlank()
    private fun su(cmd: String) = if (root()) "sudo $cmd" else cmd
    private fun ssh() { if (!Serveur.sshConfigure(app)) throw IllegalStateException("Serveur SSH non configuré (Réglages › Maison › Serveur).") }

    suspend fun execute(name: String, a: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            ssh()
            when (name) {
                "serveur_service" -> service(a)
                "serveur_tache" -> tache(a)
                "ha_sauvegarde" -> sauvegarde(a)
                "serveur_fichiers" -> fichiers(a)
                else -> ToolResult("Outil inconnu : $name")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ToolResult("❌ ${e.message ?: e.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------------ services

    private data class Candidat(val type: String, val nom: String, val image: String = "", val etat: String = "", val dossier: String = "", val svcCompose: String = "")

    private fun candidats(): List<Candidat> {
        val l = mutableListOf<Candidat>()
        val d = Serveur.exec(app, su("docker ps -a --format '{{.Names}}|{{.Image}}|{{.Status}}|{{.Label \"com.docker.compose.project.working_dir\"}}|{{.Label \"com.docker.compose.service\"}}'"), 30).second
        d.lines().filter { it.count { c -> c == '|' } >= 4 }.forEach { x -> val p = x.split('|'); l += Candidat("docker", p[0].trim(), p[1], p[2], p[3], p[4]) }
        val s = Serveur.exec(app, "systemctl list-units --type=service --all --no-legend --plain --no-pager 2>/dev/null | awk '{print \$1\"|\"\$3\"|\"\$4}'", 30).second
        s.lines().filter { it.contains(".service|") }.forEach { x -> val p = x.split('|'); l += Candidat("systemd", p[0].trim(), etat = p.drop(1).joinToString(" ")) }
        val pm = Serveur.exec(app, "export PATH=\"\$PATH:/usr/local/bin:\$HOME/.npm-global/bin\"; for d in \$HOME/.nvm/versions/node/*/bin; do [ -d \"\$d\" ] && PATH=\"\$PATH:\$d\"; done; " +
            "pm2 jlist 2>/dev/null | python3 -c 'import json,sys\nfor p in json.load(sys.stdin):\n e=p.get(\"pm2_env\",{}); print(p.get(\"name\"),\"|\",e.get(\"status\"),\"|\",e.get(\"pm_cwd\",\"\"))' 2>/dev/null", 30).second
        pm.lines().filter { it.contains(" | ") }.forEach { x -> val p = x.split(" | "); l += Candidat("pm2", p[0].trim(), etat = p.getOrElse(1) { "" }.trim(), dossier = p.getOrElse(2) { "" }.trim()) }
        return l
    }

    private val SYSTEME = Regex("^(systemd-|dbus|getty|serial-getty|user@|cron|rsyslog|ssh|polkit|udisks|accounts-daemon|networking|NetworkManager|ModemManager|wpa_supplicant|unattended|irqbalance|haveged|chrony|ntp|avahi|bluetooth|rtkit|packagekit|upower|colord|cups|snapd|containerd|thermald|lvm2|blk|multipathd|fwupd|kmod|apparmor|console|keyboard|plymouth|smartd|atd|anacron|e2scrub|ifup|setvbuf|emergency|rescue|getty|modprobe|plymouth)")

    private fun trouver(service: String, l: List<Candidat>): List<Candidat> {
        val exact = l.filter { it.nom.equals(service.trim(), true) || it.nom.removeSuffix(".service").equals(service.trim(), true) }
        if (exact.isNotEmpty()) return exact
        val m = motifs(service)
        if (m.isEmpty()) return emptyList()
        return l.filter { c -> !(c.type == "systemd" && SYSTEME.containsMatchIn(c.nom)) && m.any { k -> c.nom.contains(k, true) || c.image.contains(k, true) || c.dossier.contains(k, true) } }
            .sortedBy { when (it.type) { "docker" -> 0; "pm2" -> 1; else -> 2 } }
    }

    private suspend fun service(a: JSONObject): ToolResult {
        val action = fr.dd.jarvis.societe.core.normaliser(a.optString("action")).replace(' ', '_')
        val l = candidats()
        if (action == "lister" || a.optString("service").isBlank()) {
            val utiles = l.filter { !(it.type == "systemd" && (SYSTEME.containsMatchIn(it.nom) || !it.etat.contains("running"))) }
            return ToolResult("# 🖥️ Services du serveur\n" + utiles.joinToString("\n") { c ->
                "- **${c.nom}** · ${c.type}" + (if (c.image.isNotBlank()) " · ${c.image}" else "") + " · ${c.etat}" + (if (c.dossier.isNotBlank()) " · ${c.dossier}" else "")
            }.ifBlank { "(aucun service trouvé)" })
        }
        val service = a.optString("service").trim()
        val trouves = trouver(service, l)
        if (trouves.isEmpty()) return ToolResult("Aucun service « $service » trouvé sur le serveur (conteneurs Docker, services systemd, pm2). Dis « liste les services du serveur » pour voir les noms exacts.")
        if (trouves.size > 1 && action !in setOf("etat", "logs")) return ToolResult("Plusieurs services correspondent à « $service » : " +
            trouves.joinToString { "${it.nom} (${it.type})" } + ". Précise lequel (nom exact).")
        val n = (a.optString("lignes").toIntOrNull() ?: 80).coerceIn(10, 400)
        val c = trouves.first()
        val estHaIdentifie = Regex("home.?assistant|\\bhass\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(c.nom + " " + c.image + " " + c.dossier + " " + c.svcCompose + " " + service)
        if (estHaIdentifie && !actionLectureSeuleHa(action)) return ToolResult("⛔ Cette action concerne le cycle de vie Home Assistant : utilise son API dédiée (ha_service / ha_config). Le SSH Debian reste distinct et n'est autorisé ici que pour l'état, les journaux et l'inspection en lecture seule des volumes du conteneur (action=stockage). Aucun fichier ni conteneur n'a été modifié.")
        val sensible = action in setOf("arreter", "redemarrer", "mettre_a_jour", "desactiver", "demarrer")
        val estHa = c.type == "docker" && runCatching { HaSur.conteneur(app)?.nom == c.nom }.getOrDefault(false)
        if (sensible && !confirm("${libelleAction(action)} « ${c.nom} » (${c.type}) sur le serveur ?" + (if (estHa && action != "arreter")
                " (Home Assistant : configuration vérifiée avant, arrêt propre, retour en ligne attendu" + (if (action == "mettre_a_jour") ", sauvegarde et retour automatique à l'ancienne version si besoin)" else ")") else ""), true))
            return ToolResult("Refusé par l'utilisateur.")
        // Home Assistant : opérations sûres (sinon il restait indisponible après un redémarrage ou une mise à jour par SSH).
        if (estHa) when (action) {
            "redemarrer" -> return ToolResult(HaSur.redemarrer(app), direct = true)
            "demarrer" -> return ToolResult(HaSur.demarrer(app), direct = true)
            "mettre_a_jour" -> return ToolResult(HaSur.mettreAJour(app) { code, nom, root -> lancerTache(code, nom, root) }, direct = true)
        }
        val nq = q(c.nom)
        val pm2 = "export PATH=\"\$PATH:/usr/local/bin:\$HOME/.npm-global/bin\"; for d in \$HOME/.nvm/versions/node/*/bin; do [ -d \"\$d\" ] && PATH=\"\$PATH:\$d\"; done; "
        val (cmd, delai) = when (c.type) {
            "docker" -> when (action) {
                "stockage" -> su("docker inspect -f 'Conteneur: {{.Name}}\\nImage: {{.Config.Image}}\\n{{range .Mounts}}{{.Type}} | hôte={{.Source}} | conteneur={{.Destination}} | lecture-écriture={{.RW}}{{println}}{{end}}' $nq") to 40
                "etat" -> su("docker ps -a --filter name=^/${c.nom}\$ --format 'table {{.Names}}\\t{{.Status}}\\t{{.Image}}\\t{{.Ports}}'; docker inspect -f 'État : {{.State.Status}} · démarré {{.State.StartedAt}} · redémarrages {{.RestartCount}} · relance auto {{.HostConfig.RestartPolicy.Name}}{{if .State.Health}} · santé {{.State.Health.Status}}{{end}}' $nq; echo; docker logs --tail 15 $nq 2>&1") to 40
                "logs" -> su("docker logs --tail $n $nq 2>&1") to 40
                "demarrer" -> su("docker start $nq && sleep 3 && docker ps --filter name=^/${c.nom}\$ --format '{{.Names}} {{.Status}}'") to 90
                "arreter" -> su("docker stop -t ${if (estHa) 120 else 30} $nq") to 200
                "redemarrer" -> su("docker restart -t 30 $nq && sleep 3 && docker ps --filter name=^/${c.nom}\$ --format '{{.Names}} {{.Status}}'") to 120
                "activer" -> su("docker update --restart unless-stopped $nq") to 30
                "desactiver" -> su("docker update --restart no $nq") to 30
                "mettre_a_jour" -> if (c.dossier.isNotBlank() && c.svcCompose.isNotBlank())
                    su("bash -c " + q("cd ${q(c.dossier)} && (docker compose pull ${q(c.svcCompose)} && docker compose up -d ${q(c.svcCompose)} || (docker-compose pull ${q(c.svcCompose)} && docker-compose up -d ${q(c.svcCompose)})) 2>&1 | tail -25")) to 600
                    else return ToolResult("« ${c.nom} » n'a pas été créé avec docker compose : sa mise à jour demande de le recréer avec la nouvelle image et les mêmes réglages (volumes, ports, variables). " +
                        "Je peux le faire avec un script (dis « recrée le conteneur ${c.nom} avec la dernière image ») après avoir relevé sa configuration (docker inspect ${c.nom}).")
                else -> return ToolResult("Action inconnue.")
            }
            "systemd" -> when (action) {
                "stockage" -> "systemctl show $nq -p FragmentPath -p WorkingDirectory -p ExecStart --no-pager" to 30
                "etat" -> "systemctl status $nq --no-pager -n 15 2>&1" to 30
                "logs" -> su("journalctl -u $nq -n $n --no-pager 2>&1") to 40
                "demarrer" -> su("systemctl start $nq && systemctl is-active $nq") to 90
                "arreter" -> su("systemctl stop $nq; systemctl is-active $nq") to 90
                "redemarrer" -> su("systemctl restart $nq && sleep 2 && systemctl is-active $nq") to 120
                "activer" -> su("systemctl enable $nq") to 30
                "desactiver" -> su("systemctl disable $nq") to 30
                "mettre_a_jour" -> su("bash -c " + q("p=\$(dpkg -S \$(systemctl show -p FragmentPath --value ${q(c.nom)}) 2>/dev/null | cut -d: -f1 | head -1); [ -n \"\$p\" ] || { echo \"Paquet introuvable pour ${c.nom}\"; exit 1; }; " +
                    "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y --only-upgrade \"\$p\" 2>&1 | tail -15 && systemctl restart ${q(c.nom)} && systemctl is-active ${q(c.nom)}")) to 600
                else -> return ToolResult("Action inconnue.")
            }
            else -> when (action) {   // pm2 : toujours sous le compte SSH (pm2 est propre à chaque utilisateur)
                "stockage" -> "${pm2}pm2 describe $nq 2>&1 | grep -E 'script path|exec cwd|pm_exec_path|pm_cwd' | head -12" to 30
                "etat" -> "${pm2}pm2 describe $nq 2>&1 | head -40" to 30
                "logs" -> "${pm2}pm2 logs $nq --lines $n --nostream 2>&1 | tail -$n" to 40
                "demarrer" -> "${pm2}pm2 start $nq 2>&1 | tail -15" to 60
                "arreter" -> "${pm2}pm2 stop $nq 2>&1 | tail -15" to 60
                "redemarrer" -> "${pm2}pm2 restart $nq 2>&1 | tail -15" to 60
                "activer" -> "${pm2}pm2 save 2>&1 | tail -5; pm2 startup 2>&1 | tail -3" to 60
                "desactiver" -> "${pm2}pm2 delete $nq 2>&1 | tail -5; pm2 save 2>&1 | tail -3" to 60
                "mettre_a_jour" -> "${pm2}cd ${q(c.dossier.ifBlank { "." })} && (git pull 2>&1 | tail -5) && ([ -f package.json ] && npm install --omit=dev 2>&1 | tail -5 || true) && " +
                    "([ -f requirements.txt ] && pip3 install -r requirements.txt 2>&1 | tail -5 || true) && pm2 restart $nq 2>&1 | tail -10" to 600
                else -> return ToolResult("Action inconnue.")
            }
        }
        // Actions qui modifient : exécution détachée et vérifiée, jamais interrompue en plein milieu (docker compose, apt…).
        val r = if (sensible || action in setOf("activer", "desactiver", "mettre_a_jour")) Serveur.execComplet(app, cmd, delai, "${libelleAction(action)} ${c.nom}") else Serveur.exec(app, cmd, delai)
        val autres = if (trouves.size > 1) "\n_(Autres services correspondants : ${trouves.drop(1).joinToString { "${it.nom} (${it.type})" }}.)_" else ""
        return ToolResult("**${c.nom}** (${c.type}) — ${libelleAction(action)}\n```\n" + sortie(r) + "\n```" + autres)
    }

    private fun libelleAction(a: String) = when (a) {
        "etat" -> "état"; "logs" -> "journaux"; "demarrer" -> "Démarrer"; "arreter" -> "Arrêter"; "redemarrer" -> "Redémarrer"
        "mettre_a_jour" -> "Mettre à jour"; "activer" -> "Activer la relance automatique de"; "desactiver" -> "Désactiver la relance automatique de"; else -> a
    }

    // ------------------------------------------------------------------ tâches longues

    /** Lance [code] détaché (survit à la coupure SSH) ; journal et code de sortie dans /tmp/jarvis_taches. */
    internal fun lancerTache(code: String, nom: String, root: Boolean): String {
        val id = "t" + (System.currentTimeMillis() / 1000)
        Serveur.exec(app, "mkdir -p $TACHES && chmod 777 $TACHES", 15)
        Serveur.envoyerOctets(app, "$TACHES/$id.sh", code.toByteArray())
        Serveur.envoyerOctets(app, "$TACHES/$id.nom", nom.ifBlank { "tâche" }.toByteArray())
        val lanceur = "chmod 700 $TACHES/$id.sh; setsid nohup bash -c " + q("bash $TACHES/$id.sh > $TACHES/$id.log 2>&1; echo \$? > $TACHES/$id.rc; rm -f $TACHES/$id.sh") +
            " > /dev/null 2>&1 < /dev/null & echo lancé"
        val r = Serveur.exec(app, (if (root) "sudo " else "") + lanceur, 20)
        if (!r.second.contains("lancé")) throw IllegalStateException("La tâche n'a pas pu démarrer : ${r.second.take(300)}")
        return id
    }

    private fun etatTache(id: String): String {
        val r = Serveur.exec(app, "cd $TACHES 2>/dev/null || { echo 'Aucune tâche.'; exit 0; }; i=${q(id)}; [ \"\$i\" = derniere ] && i=\$(ls -t *.log 2>/dev/null | head -1 | sed 's/.log\$//'); " +
            "[ -n \"\$i\" ] && [ -f \"\$i.log\" ] || { echo 'Tâche introuvable.'; exit 0; }; echo \"Tâche \$i : \$(cat \$i.nom 2>/dev/null)\"; " +
            "if [ -f \$i.rc ]; then echo \"Terminée — code de sortie \$(cat \$i.rc)\"; else echo \"En cours depuis \$(( \$(date +%s) - \$(stat -c %Y \$i.nom) )) s\"; fi; echo ---; tail -n 60 \$i.log", 30)
        return r.second
    }

    private suspend fun tache(a: JSONObject): ToolResult {
        return when (fr.dd.jarvis.societe.core.normaliser(a.optString("action"))) {
            "lancer" -> {
                val code = a.optString("code").trim()
                if (code.isBlank()) return ToolResult("Script vide.")
                if (Regex("(?:docker|docker-compose|docker\\s+compose|ha\\s+core)\\b", RegexOption.IGNORE_CASE).containsMatchIn(code) &&
                    Regex("home.?assistant|\\bhass\\b|127\\.0\\.0\\.1:8123|localhost:8123", RegexOption.IGNORE_CASE).containsMatchIn(code)) {
                    return ToolResult("⛔ Tâche refusée : elle cible Home Assistant depuis le SSH Debian. Utilise l'API HA ou un profil SSH HA indépendant.")
                }
                if (!confirm("Lancer cette tâche en arrière-plan sur le serveur ${app.store.sshHost.value} ?\n\n" + code.take(900), true)) return ToolResult("Refusé par l'utilisateur.")
                val root = a.optString("root").lowercase() in setOf("oui", "true", "1") || Regex("\\bsudo\\b").containsMatchIn(code)
                val id = lancerTache(code.replace(Regex("\\bsudo\\s+"), if (root) "" else "sudo "), a.optString("nom"), root)
                ToolResult("▶️ Tâche **$id** lancée en arrière-plan. Demande « où en est la tâche » (serveur_tache etat) pour suivre son journal.")
            }
            "etat", "suivre" -> ToolResult("```\n" + etatTache(a.optString("id").ifBlank { "derniere" }) + "\n```")
            "liste" -> ToolResult("```\n" + Serveur.exec(app, "cd $TACHES 2>/dev/null && for f in \$(ls -t *.nom 2>/dev/null | head -15); do i=\${f%.nom}; s=en_cours; [ -f \$i.rc ] && s=\"terminée (code \$(cat \$i.rc))\"; echo \"\$i · \$(cat \$f) · \$s\"; done || echo 'Aucune tâche.'", 20).second.ifBlank { "Aucune tâche." } + "\n```")
            "arreter" -> {
                val id = a.optString("id").trim().ifBlank { return ToolResult("Précise l'identifiant de la tâche (serveur_tache liste).") }
                if (!confirm("Arrêter la tâche $id sur le serveur ?", true)) return ToolResult("Refusé par l'utilisateur.")
                ToolResult("```\n" + sortie(Serveur.exec(app, su("pkill -f ${q("$TACHES/$id")}") + "; echo 130 > $TACHES/$id.rc; echo arrêtée", 20)) + "\n```")
            }
            else -> ToolResult("Action inconnue (lancer, etat, liste, arreter).")
        }
    }

    /**
     * Les sauvegardes HA ne doivent jamais être cherchées/restaurées sur le SSH Debian :
     * Home Assistant est une machine distincte. La sauvegarde officielle reste accessible
     * via l'API HA (outil ha_config / service backup), sans accès disque distant.
     */
    private suspend fun sauvegarde(a: JSONObject): ToolResult =
        ToolResult("⛔ Sauvegardes locales/restauration Home Assistant suspendues : le SSH configuré cible le serveur Debian Jarvis, pas la machine Home Assistant. Aucune commande Docker, lecture de disque ou restauration n'a été envoyée à Debian. Utilise la sauvegarde officielle depuis l'API Home Assistant ; un profil SSH HA indépendant sera nécessaire pour les archives hors ligne.\n")

    private suspend fun fichiers(a: JSONObject): ToolResult {
        val action = fr.dd.jarvis.societe.core.normaliser(a.optString("action"))
        val chemin = a.optString("chemin").trim()
        val dest = a.optString("destination").trim()
        val r = a.optString("root").lowercase() in setOf("oui", "true", "1")
        fun x(cmd: String, delai: Int = 120) = Serveur.exec(app, if (r) su(cmd) else cmd, delai)
        return when (action) {
            "chercher" -> {
                val motif = a.optString("motif").trim().ifBlank { return ToolResult("Précise le nom ou le motif à chercher.") }
                val m = if (motif.contains('*')) motif else "*$motif*"
                ToolResult("```\n" + sortie(x("find ${q(chemin.ifBlank { "/" })} -xdev -iname ${q(m)} -not -path '*/proc/*' 2>/dev/null | head -60", 120)) + "\n```")
            }
            "info" -> ToolResult("```\n" + sortie(x("ls -lAh --time-style=long-iso ${q(chemin)} 2>&1 | head -60; echo; du -sh ${q(chemin)} 2>/dev/null; stat -c 'propriétaire %U:%G · droits %a' ${q(chemin)} 2>/dev/null")) + "\n```")
            "copier", "deplacer" -> {
                if (chemin.isBlank() || dest.isBlank()) return ToolResult("Précise « chemin » et « destination ».")
                if (!confirm("${if (action == "copier") "Copier" else "Déplacer"} sur le serveur :\n$chemin → $dest ?", true)) return ToolResult("Refusé par l'utilisateur.")
                ToolResult("```\n" + sortie(x(if (action == "copier") "cp -a ${q(chemin)} ${q(dest)} && echo copié" else "mv ${q(chemin)} ${q(dest)} && echo déplacé", 900)) + "\n```")
            }
            "supprimer" -> {
                if (chemin.isBlank() || chemin.trim('/').isBlank() || Regex("^/(etc|bin|boot|usr|lib|var|sbin|proc|sys|dev)?/?$").matches(chemin)) return ToolResult("Chemin refusé.")
                if (!confirm("SUPPRIMER définitivement sur le serveur :\n$chemin ?", true)) return ToolResult("Refusé par l'utilisateur.")
                ToolResult("```\n" + sortie(x("rm -rf -- ${q(chemin)} && echo supprimé")) + "\n```")
            }
            "archiver" -> {
                val cible = dest.ifBlank { chemin.trimEnd('/') + "-" + java.time.LocalDate.now() + ".tar.gz" }
                ToolResult("```\n" + sortie(x("tar -czf ${q(cible)} -C ${q(File(chemin).parent ?: "/")} ${q(File(chemin).name)} && du -h ${q(cible)}", 900)) + "\n```")
            }
            "extraire" -> {
                val cible = dest.ifBlank { File(chemin).parent ?: "." }
                if (!confirm("Extraire $chemin dans $cible (des fichiers peuvent être remplacés) ?", true)) return ToolResult("Refusé par l'utilisateur.")
                val cmd = if (chemin.endsWith(".zip")) "unzip -o ${q(chemin)} -d ${q(cible)} | tail -5" else "mkdir -p ${q(cible)} && tar -xf ${q(chemin)} -C ${q(cible)} && echo extrait"
                ToolResult("```\n" + sortie(x(cmd, 900)) + "\n```")
            }
            "recuperer" -> {
                val octets = Serveur.lireOctets(app, chemin, 200_000_000)
                val dossier = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Jarvis").apply { mkdirs() }
                val f = File(dossier, File(chemin).name)
                f.writeBytes(octets)
                ToolResult("✅ ${File(chemin).name} (${octets.size / 1024} Ko) récupéré sur le téléphone : ${f.absolutePath}")
            }
            "envoyer" -> {
                val local = File(a.optString("fichier_telephone").trim().let { if (it.startsWith("/")) it else File(fr.dd.jarvis.ai.Stockage.racine(), it).path })
                if (!local.isFile) return ToolResult("Fichier introuvable sur le téléphone : ${local.path}")
                val cible = if (chemin.isBlank() || chemin.endsWith("/")) chemin.ifBlank { "/tmp/" } + local.name else chemin
                if (!confirm("Envoyer ${local.name} (${local.length() / 1024} Ko) sur le serveur vers $cible ?", false)) return ToolResult("Refusé par l'utilisateur.")
                Serveur.envoyerOctets(app, cible, local.readBytes())
                ToolResult("✅ ${local.name} envoyé sur le serveur : $cible")
            }
            else -> ToolResult("Action inconnue (chercher, info, copier, deplacer, supprimer, archiver, extraire, recuperer, envoyer).")
        }
    }
}
