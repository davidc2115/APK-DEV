package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Administration complète du serveur Debian, en plus de ssh_commande / ssh_script :
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
        private val MOTS = Regex("sauvegarde|restaur|backup|remettre en (service|route)|r[ée]par|ne d[ée]marre|plant[ée]|\\bbot\\b|discord|plex|\\bservices?\\b|conteneur|\\blogs?\\b|journaux|t[âa]che|mise à jour|mettre à jour|fichiers? (du|sur le) serveur|ssh|serveur|debian|docker", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = MOTS.containsMatchIn(t)
        private const val TACHES = "/tmp/jarvis_taches"

        /** Mots d'un service → motifs probables de son nom de conteneur / unité / processus. */
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
        tool("serveur_service", "Serveur Debian : gère un SERVICE par son nom, quel que soit son type (conteneur Docker ou docker compose, service systemd, processus pm2) — Home Assistant, Plex, bot Discord, Ollama, Mosquitto… Actions : lister | etat | logs | demarrer | arreter | redemarrer | mettre_a_jour | activer (relance automatique au démarrage) | desactiver. Trouve tout seul le bon conteneur / unité / processus.",
            mapOf("action" to prop("lister | etat | logs | demarrer | arreter | redemarrer | mettre_a_jour | activer | desactiver"),
                "service" to prop("Nom du service (« Home Assistant », « Plex », « bot Discord »…) ou nom exact du conteneur / de l'unité."),
                "lignes" to prop("Logs : nombre de lignes (défaut 80).")), listOf("action")),
        tool("serveur_tache", "Serveur Debian : TÂCHE LONGUE en arrière-plan (au-delà de quelques minutes : restauration, mise à jour, copie, compilation…) qui continue même si la connexion se coupe ; puis suivi de son journal. Actions : lancer (code bash) | etat | liste | arreter.",
            mapOf("action" to prop("lancer | etat | liste | arreter"), "code" to prop("lancer : script bash complet."), "nom" to prop("lancer : nom court de la tâche."),
                "root" to prop("« oui » : exécuter en root (automatique si le script contient sudo)."), "id" to prop("etat / arreter : identifiant (défaut : la dernière tâche).")), listOf("action")),
        tool("ha_sauvegarde", "Home Assistant (conteneur Docker sur le serveur Debian) : SAUVEGARDES et REMISE EN SERVICE, même quand Home Assistant est arrêté ou cassé. Actions : lister (sauvegardes trouvées sur le serveur, avec date, nom, taille, chiffrée ou non) | creer | restaurer (une sauvegarde choisie : arrêt du conteneur, ancienne configuration conservée à côté, restauration, redémarrage, vérification) | etat (conteneur, dossier de configuration, réponse HTTP, dernières erreurs). Les sauvegardes chiffrées utilisent la clé enregistrée dans Réglages › Sécurité › Systèmes.",
            mapOf("action" to prop("lister | creer | restaurer | etat"),
                "sauvegarde" to prop("restaurer : chemin complet, nom, ou date de la sauvegarde (« derniere » = la plus récente) ; voir lister."),
                "nom" to prop("creer : nom de la sauvegarde (optionnel).")), listOf("action")),
        tool("serveur_fichiers", "Serveur Debian : fichiers et dossiers. Actions : chercher (nom ou motif, dans un dossier) | info (taille, droits, contenu d'un dossier) | copier | deplacer | supprimer | archiver (tar.gz) | extraire (tar, tar.gz, zip) | recuperer (télécharge le fichier sur le téléphone, dossier Download/Jarvis) | envoyer (fichier du téléphone → serveur).",
            mapOf("action" to prop("chercher | info | copier | deplacer | supprimer | archiver | extraire | recuperer | envoyer"),
                "chemin" to prop("Chemin sur le serveur (source ; pour envoyer : destination)."), "destination" to prop("copier / deplacer / archiver / extraire : chemin de destination."),
                "motif" to prop("chercher : nom ou motif (ex. *.tar, backup)."), "fichier_telephone" to prop("envoyer : chemin du fichier sur le téléphone."),
                "root" to prop("« oui » : en root (fichiers système, dossiers d'autres utilisateurs).")), listOf("action")),
    )

    private fun sortie(r: Pair<Int, String>, max: Int = 6000): String {
        val t = r.second.trim().ifBlank { "(aucune sortie)" }
        return (if (t.length > max) "…" + t.takeLast(max) else t) + (if (r.first != 0) "\n[code de sortie ${r.first}]" else "")
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
        val sensible = action in setOf("arreter", "redemarrer", "mettre_a_jour", "desactiver", "demarrer")
        if (sensible && !confirm("${libelleAction(action)} « ${c.nom} » (${c.type}) sur le serveur ?", true)) return ToolResult("Refusé par l'utilisateur.")
        val nq = q(c.nom)
        val pm2 = "export PATH=\"\$PATH:/usr/local/bin:\$HOME/.npm-global/bin\"; for d in \$HOME/.nvm/versions/node/*/bin; do [ -d \"\$d\" ] && PATH=\"\$PATH:\$d\"; done; "
        val (cmd, delai) = when (c.type) {
            "docker" -> when (action) {
                "etat" -> su("docker ps -a --filter name=^/${c.nom}\$ --format 'table {{.Names}}\\t{{.Status}}\\t{{.Image}}\\t{{.Ports}}'; docker inspect -f 'État : {{.State.Status}} · démarré {{.State.StartedAt}} · redémarrages {{.RestartCount}} · relance auto {{.HostConfig.RestartPolicy.Name}}{{if .State.Health}} · santé {{.State.Health.Status}}{{end}}' $nq; echo; docker logs --tail 15 $nq 2>&1") to 40
                "logs" -> su("docker logs --tail $n $nq 2>&1") to 40
                "demarrer" -> su("docker start $nq && sleep 3 && docker ps --filter name=^/${c.nom}\$ --format '{{.Names}} {{.Status}}'") to 90
                "arreter" -> su("docker stop $nq") to 90
                "redemarrer" -> su("docker restart $nq && sleep 3 && docker ps --filter name=^/${c.nom}\$ --format '{{.Names}} {{.Status}}'") to 120
                "activer" -> su("docker update --restart unless-stopped $nq") to 30
                "desactiver" -> su("docker update --restart no $nq") to 30
                "mettre_a_jour" -> if (c.dossier.isNotBlank() && c.svcCompose.isNotBlank())
                    su("bash -c " + q("cd ${q(c.dossier)} && (docker compose pull ${q(c.svcCompose)} && docker compose up -d ${q(c.svcCompose)} || (docker-compose pull ${q(c.svcCompose)} && docker-compose up -d ${q(c.svcCompose)})) 2>&1 | tail -25")) to 600
                    else return ToolResult("« ${c.nom} » n'a pas été créé avec docker compose : sa mise à jour demande de le recréer avec la nouvelle image et les mêmes réglages (volumes, ports, variables). " +
                        "Je peux le faire avec un script (dis « recrée le conteneur ${c.nom} avec la dernière image ») après avoir relevé sa configuration (docker inspect ${c.nom}).")
                else -> return ToolResult("Action inconnue.")
            }
            "systemd" -> when (action) {
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
        val r = Serveur.exec(app, cmd, delai)
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

    // ------------------------------------------------------------------ sauvegardes Home Assistant

    /** Conteneur et dossier de configuration de Home Assistant (réglage « dossier HA », sinon détectés). */
    private fun detection(): String = """
C=${'$'}(docker ps -a --format '{{.Names}} {{.Image}}' | awk 'tolower(${'$'}2) ~ /home-assistant|homeassistant/ {print ${'$'}1; exit}')
[ -z "${'$'}C" ] && C=${'$'}(docker ps -a --format '{{.Names}}' | grep -iE 'home.?assistant|^hass${'$'}|^ha${'$'}' | head -1)
CFG=${q(app.store.haDir.value.trim())}
[ -z "${'$'}CFG" ] && [ -n "${'$'}C" ] && CFG=${'$'}(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/config"}}{{.Source}}{{end}}{{end}}' "${'$'}C")
""".trimIndent()

    private val LISTE = """
echo "Conteneur : ${'$'}{C:-introuvable}"; echo "Configuration : ${'$'}{CFG:-introuvable}"
for f in ${'$'}( { ls -1 "${'$'}CFG"/backups/*.tar "${'$'}CFG"/backups/*.tar.gz /backup/*.tar 2>/dev/null; find /home /root /opt /srv /mnt /media -maxdepth 6 \( -path '*backups/*.tar' -o -iname '*homeassistant*.tar*' -o -iname '*home-assistant*.tar*' -o -iname 'ha-config*.tar*' \) 2>/dev/null; } | sort -u ); do
  t=${'$'}(du -h "${'$'}f" | cut -f1); d=${'$'}(date -r "${'$'}f" '+%Y-%m-%d %H:%M')
  m=${'$'}(tar -xOf "${'$'}f" ./backup.json 2>/dev/null || tar -xOf "${'$'}f" backup.json 2>/dev/null)
  if [ -n "${'$'}m" ]; then
    i=${'$'}(printf '%s' "${'$'}m" | python3 -c 'import json,sys
b=json.load(sys.stdin); print(b.get("name",""),"|",b.get("date","")[:16].replace("T"," "),"|","CHIFFRÉE" if b.get("protected") else "non chiffrée","|","HA "+str(b.get("homeassistant",{}).get("version","") if isinstance(b.get("homeassistant"),dict) else b.get("homeassistant","")))' 2>/dev/null)
    echo "${'$'}d | ${'$'}t | ${'$'}f | ${'$'}i"
  else echo "${'$'}d | ${'$'}t | ${'$'}f | archive de configuration"; fi
done | sort -r
""".trimIndent()

    private fun restauration(fichier: String, cle: String): String = """
set -u
${detection()}
F=${q(fichier)}; KEY=${q(cle)}
log() { echo "[${'$'}(date +%T)] ${'$'}*"; }
[ -n "${'$'}C" ] || { log "❌ Conteneur Home Assistant introuvable"; exit 1; }
[ -n "${'$'}CFG" ] && [ -d "${'$'}CFG" ] || { log "❌ Dossier de configuration introuvable (${'$'}CFG) : renseigne-le dans Réglages › Maison › Serveur"; exit 1; }
[ -f "${'$'}F" ] || { log "❌ Sauvegarde introuvable : ${'$'}F"; exit 1; }
T=${'$'}(mktemp -d /tmp/jarvis_restau.XXXXXX); mkdir -p "${'$'}T/x"; log "📦 Préparation de ${'$'}F"
case "${'$'}F" in
  *.tar.gz|*.tgz) tar -xzf "${'$'}F" -C "${'$'}T/x" || { log "❌ Archive illisible"; exit 1; } ;;
  *)
    tar -xf "${'$'}F" -C "${'$'}T" || { log "❌ Archive illisible"; exit 1; }
    IN=${'$'}(cd "${'$'}T" && ls homeassistant.tar.gz homeassistant.tar 2>/dev/null | head -1)
    if [ -z "${'$'}IN" ]; then
      [ -f "${'$'}T/configuration.yaml" ] && cp -a "${'$'}T"/. "${'$'}T/x"/ || { log "❌ Pas de configuration Home Assistant dans cette sauvegarde (contenu : ${'$'}(ls ${'$'}T | tr '\n' ' '))"; exit 1; }
    else
      PROT=${'$'}(python3 -c 'import json;print(json.load(open("'"${'$'}T"'/backup.json")).get("protected",False))' 2>/dev/null)
      GZ=${'$'}(python3 -c 'import json;print(json.load(open("'"${'$'}T"'/backup.json")).get("compressed",True))' 2>/dev/null)
      if [ "${'$'}PROT" = "True" ]; then
        [ -n "${'$'}KEY" ] || { log "❌ Sauvegarde CHIFFRÉE : enregistre sa clé de chiffrement dans JARVIS › Réglages › Sécurité › Systèmes, puis relance la restauration"; exit 2; }
        IMG=${'$'}(docker inspect -f '{{.Config.Image}}' "${'$'}C"); log "🔐 Déchiffrement avec l'image ${'$'}IMG"
        cat > "${'$'}T/dechiffrer.py" <<'PY'
import hashlib, os, sys
from pathlib import Path
pw = os.environ["CLE"]
try:
    from homeassistant.components.backup.util import password_to_key
    key = password_to_key(pw)
except Exception:
    key = pw.encode()
    for _ in range(100):
        key = hashlib.sha256(key).digest()
    key = key[:16]
from securetar import SecureTarFile
gz = sys.argv[3] != "False"
try:
    tar = SecureTarFile(Path(sys.argv[1]), "r", key=key, gzip=gz)
except TypeError:
    tar = SecureTarFile(Path(sys.argv[1]), "r", password=pw, gzip=gz)
with tar as t:
    t.extractall(path=sys.argv[2])
print("déchiffrée")
PY
        docker run --rm --entrypoint python3 -e CLE="${'$'}KEY" -v "${'$'}T":/w "${'$'}IMG" /w/dechiffrer.py "/w/${'$'}IN" /w/x "${'$'}GZ" || { log "❌ Déchiffrement impossible (clé incorrecte ?)"; rm -rf "${'$'}T"; exit 3; }
      else
        tar -xzf "${'$'}T/${'$'}IN" -C "${'$'}T/x" 2>/dev/null || tar -xf "${'$'}T/${'$'}IN" -C "${'$'}T/x" || { log "❌ Extraction impossible"; exit 1; }
      fi
    fi ;;
esac
SRC="${'$'}T/x"; [ -d "${'$'}T/x/data" ] && SRC="${'$'}T/x/data"
[ -f "${'$'}SRC/configuration.yaml" ] || [ -d "${'$'}SRC/.storage" ] || { log "❌ Pas de configuration Home Assistant dans l'archive :"; ls -la "${'$'}SRC"; rm -rf "${'$'}T"; exit 1; }
log "⏹️ Arrêt de ${'$'}C"; docker stop "${'$'}C" >/dev/null
OWN=${'$'}(stat -c %u:%g "${'$'}CFG")
OLD="${'$'}CFG.avant-restauration-${'$'}(date +%Y%m%d-%H%M%S)"
mv "${'$'}CFG" "${'$'}OLD" && mkdir -p "${'$'}CFG" && cp -a "${'$'}SRC"/. "${'$'}CFG"/ || { log "❌ Copie impossible : remise de l'ancienne configuration"; rm -rf "${'$'}CFG"; mv "${'$'}OLD" "${'$'}CFG"; docker start "${'$'}C"; exit 1; }
if [ -d "${'$'}OLD/backups" ]; then mkdir -p "${'$'}CFG/backups"; for b in "${'$'}OLD"/backups/*; do [ -e "${'$'}CFG/backups/${'$'}(basename "${'$'}b")" ] || mv "${'$'}b" "${'$'}CFG/backups/"; done; fi
chown -R "${'$'}OWN" "${'$'}CFG"
log "▶️ Redémarrage de ${'$'}C"; docker start "${'$'}C" >/dev/null
ok=0; for i in ${'$'}(seq 1 72); do code=${'$'}(curl -s -o /dev/null -w '%{http_code}' -m 4 http://localhost:8123/ || true); if [ "${'$'}code" = 200 ]; then log "✅ Home Assistant répond (HTTP 200) après ${'$'}((i*5)) s"; ok=1; break; fi; sleep 5; done
[ ${'$'}ok = 1 ] || log "⚠️ Home Assistant ne répond pas encore après 6 min (premier démarrage parfois long : installation des intégrations)."
echo "--- dernières lignes du journal ---"; docker logs --tail 25 "${'$'}C" 2>&1 | tail -25
log "📁 Ancienne configuration conservée dans ${'$'}OLD (à supprimer quand tout fonctionne)."
rm -rf "${'$'}T"
""".trimIndent()

    private suspend fun sauvegarde(a: JSONObject): ToolResult {
        return when (fr.dd.jarvis.societe.core.normaliser(a.optString("action"))) {
            "lister", "liste" -> ToolResult("# 💾 Sauvegardes Home Assistant\n```\n" + sortie(Serveur.exec(app, su("bash -c " + q(detection() + "\n" + LISTE)), 120), 7000) + "\n```" +
                "\nPour restaurer : « restaure la sauvegarde du <date> » (ou « la dernière »).")
            "etat" -> ToolResult("```\n" + sortie(Serveur.exec(app, su("bash -c " + q(detection() + """
echo "Conteneur : ${'$'}{C:-introuvable}"; echo "Configuration : ${'$'}{CFG:-introuvable}"
[ -n "${'$'}C" ] && docker inspect -f 'État : {{.State.Status}} · démarré {{.State.StartedAt}} · redémarrages {{.RestartCount}} · image {{.Config.Image}}' "${'$'}C"
echo "HTTP local : ${'$'}(curl -s -o /dev/null -w '%{http_code}' -m 5 http://localhost:8123/ || echo injoignable)"
[ -n "${'$'}CFG" ] && { ls -la "${'$'}CFG" | head -20; du -sh "${'$'}CFG" 2>/dev/null; ls -d "${'$'}CFG".avant-restauration-* 2>/dev/null; }
[ -n "${'$'}C" ] && { echo "--- erreurs récentes ---"; docker logs --tail 200 "${'$'}C" 2>&1 | grep -iE 'error|exception|failed|critical' | tail -15; }
""")), 60)) + "\n```")
            "creer" -> {
                val nom = a.optString("nom").trim().ifBlank { "Jarvis " + java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ') }
                // Home Assistant en marche : sauvegarde officielle ; sinon archive de la configuration.
                val api = runCatching { Serveur.ha(app, "POST", "/api/services/backup/create", JSONObject()); true }.getOrDefault(false)
                if (api) return ToolResult("✅ Sauvegarde Home Assistant demandée (format officiel). Elle apparaîtra dans quelques minutes : « liste les sauvegardes Home Assistant ».")
                if (!confirm("Home Assistant ne répond pas : créer une archive de sa configuration sur le serveur (« $nom ») ?", false)) return ToolResult("Annulé.")
                val r = Serveur.exec(app, su("bash -c " + q(detection() + "\n[ -n \"\$CFG\" ] || { echo 'Dossier de configuration introuvable'; exit 1; }; mkdir -p \"\$CFG/backups\"; " +
                    "f=\"\$CFG/backups/ha-config-\$(date +%Y%m%d-%H%M%S).tar.gz\"; tar --exclude=./backups -czf \"\$f\" -C \"\$CFG\" . && du -h \"\$f\"")), 900)
                ToolResult("```\n" + sortie(r) + "\n```")
            }
            "restaurer" -> {
                val demande = a.optString("sauvegarde").trim().ifBlank { "derniere" }
                val liste = Serveur.exec(app, su("bash -c " + q(detection() + "\n" + LISTE)), 120).second
                val lignes = liste.lines().filter { it.count { c -> c == '|' } >= 3 }
                val choisie = if (fr.dd.jarvis.societe.core.normaliser(demande).let { it.startsWith("dern") || it == "plus recente" }) lignes.firstOrNull()
                    else lignes.firstOrNull { it.split('|')[2].trim() == demande } ?: lignes.firstOrNull { it.contains(demande, true) }
                        ?: lignes.firstOrNull { l -> fr.dd.jarvis.societe.core.normaliser(l).contains(fr.dd.jarvis.societe.core.normaliser(demande)) }
                if (choisie == null) return ToolResult("Sauvegarde « $demande » introuvable. Sauvegardes trouvées :\n```\n${liste.take(3000)}\n```")
                val fichier = choisie.split('|')[2].trim()
                if (!confirm("Restaurer Home Assistant avec la sauvegarde :\n$choisie\n\nLe conteneur sera arrêté ; la configuration actuelle est conservée à côté (dossier « .avant-restauration-… »).", true))
                    return ToolResult("Restauration annulée.")
                val cle = runCatching { app.store.cleSauvegardeHa.value }.getOrDefault("")
                val id = lancerTache(restauration(fichier, cle), "Restauration Home Assistant", root())
                kotlinx.coroutines.delay(20_000)
                ToolResult("▶️ Restauration lancée en arrière-plan (tâche **$id**) depuis :\n$choisie\n\nPremières étapes :\n```\n" + etatTache(id) + "\n```\n" +
                    "Elle continue même si la connexion se coupe ; dis « où en est la restauration ? » pour suivre (serveur_tache etat).")
            }
            else -> ToolResult("Action inconnue (lister, creer, restaurer, etat).")
        }
    }

    // ------------------------------------------------------------------ fichiers

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
