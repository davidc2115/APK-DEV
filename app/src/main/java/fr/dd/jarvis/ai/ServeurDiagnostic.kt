package fr.dd.jarvis.ai

/**
 * Diagnostics SSH strictement en lecture seule.
 *
 * Le serveur Debian est l'hôte Docker. Les appels API Home Assistant sont faits séparément
 * par ServeurTools ; ce script ne tente jamais de piloter HA ni de modifier les services.
 */
internal object ServeurDiagnostic {
    /**
     * Script de diagnostic. [sudo] : l'utilisateur SSH n'est pas root. [toutEnRoot] : tout le script passe par « sudo »
     * avec le mot de passe enregistré (journaux du noyau, SMART, ports avec leurs processus… lisibles) ; sinon
     * « sudo -n » (seulement si sudo ne demande pas de mot de passe).
     */
    fun commande(cibleBrute: String, plexToken: String, lignes: Int = 80, sudo: Boolean = false, toutEnRoot: Boolean = false): String {
        val cible = fr.dd.jarvis.societe.core.normaliser(cibleBrute).ifBlank { "complet" }
        val cibleValide = cible in setOf("complet", "debian", "ssh", "plex", "home assistant", "homeassistant", "ha", "discord", "bagbot")
        require(cibleValide) { "Cible de diagnostic inconnue : $cibleBrute" }
        val n = lignes.coerceIn(20, 200)
        val enRoot = sudo && toutEnRoot && cible !in setOf("discord", "bagbot")
        val rootCmd = if (sudo && !enRoot) "sudo -n " else ""
        val dockerFiltre = when (cible) {
            "plex" -> "plex"
            "ha", "home assistant", "homeassistant" -> "home.?assistant|hass"
            "ssh", "discord", "bagbot" -> ""
            else -> "plex|home.?assistant|hass"
        }
        val tokenHeader = plexToken.trim().takeIf { it.isNotBlank() }
            ?.let { "-H ${Serveur.q("X-Plex-Token: $it")}" }.orEmpty()
        val dockerSection = if (dockerFiltre.isBlank()) "" else """
            echo
            echo '== Docker : conteneurs Plex / Home Assistant =='
            ${rootCmd}docker ps -a --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}\t{{.Ports}}' 2>&1
            for c in ${'$'}(${rootCmd}docker ps -a --format '{{.Names}}|{{.Image}}' | awk -F'|' 'tolower(${'$'}1 FS ${'$'}2) ~ /$dockerFiltre/ {print ${'$'}1}' | head -8); do
              echo
              echo "--- Conteneur : ${'$'}c ---"
              ${rootCmd}docker inspect -f 'état={{.State.Status}} code={{.State.ExitCode}} erreur={{.State.Error}} OOM={{.State.OOMKilled}} démarré={{.State.StartedAt}} redémarrages={{.RestartCount}} relance={{.HostConfig.RestartPolicy.Name}}{{if .State.Health}} santé={{.State.Health.Status}}{{else}} santé=non-configurée{{end}}' "${'$'}c" 2>&1
              ${rootCmd}docker stats --no-stream --format 'CPU={{.CPUPerc}} mémoire={{.MemUsage}} réseau={{.NetIO}} disque={{.BlockIO}}' "${'$'}c" 2>&1
              echo 'Volumes (hôte -> conteneur) :'
              ${rootCmd}docker inspect -f '{{range .Mounts}}{{println .Source "->" .Destination "rw=" .RW}}{{end}}' "${'$'}c" 2>&1
              echo 'Erreurs récentes :'
              ${rootCmd}docker logs --tail $n "${'$'}c" 2>&1 | grep -iE 'error|exception|failed|fatal|warning|timeout|database|corrupt|permission|no space|oom|unavailable' | tail -15
            done
            echo
            echo '== Espace Docker =='
            ${rootCmd}docker system df 2>&1
        """.trimIndent()
        val plexLibrarySection = if (tokenHeader.isNotBlank()) """
            PLEX_LIB=${'$'}(curl -sS --connect-timeout 2 --max-time 8 $tokenHeader http://127.0.0.1:32400/library/sections 2>&1)
            echo 'Bibliothèques :'
            printf '%s' "${'$'}PLEX_LIB" | sed 's/></>/\n</g' | grep -E '<Directory ' | sed -E 's/(key|uuid|agent|scanner)="[^"]+"/\1="[masqué]"/g' | head -20
            PLEX_SESS=${'$'}(curl -sS --connect-timeout 2 --max-time 6 $tokenHeader http://127.0.0.1:32400/status/sessions 2>&1)
            echo "Lectures actives : ${'$'}(printf '%s' ${'$'}PLEX_SESS | grep -o '<Video ' | wc -l | tr -d ' ')"
            echo "Transcodages actifs : ${'$'}(printf '%s' ${'$'}PLEX_SESS | grep -o '<TranscodeSession ' | wc -l | tr -d ' ')"
            echo 'Détails des lectures (titres/clients, identifiants masqués) :'
            printf '%s' "${'$'}PLEX_SESS" | sed 's/></>/\n</g' | grep -E '<Video |<Player |<TranscodeSession ' | sed -E 's/(key|ratingKey|sessionKey|machineIdentifier|clientIdentifier)="[^"]+"/\1="[masqué]"/g' | head -18
        """.trimIndent() else """
            echo 'Jeton Plex absent : identité seulement, bibliothèques et sessions non interrogées.'
            echo 'Bibliothèques : non interrogées (jeton absent)'
            echo 'Transcodages actifs : non vérifiables (jeton absent)'
            echo 'Détails des lectures : non disponibles (jeton absent)'
        """.trimIndent()
        val plexSection = if (cible in setOf("complet", "plex")) """
            echo
            echo '== Plex Media Server : API locale =='
            echo 'Service systemd Plex (si installé) :' 
            (${rootCmd}systemctl is-active plexmediaserver 2>/dev/null || true)
            (${rootCmd}journalctl -u plexmediaserver -n 20 --no-pager 2>/dev/null || true) | tail -20
            if command -v curl >/dev/null 2>&1; then
              PLEX_ID=${'$'}(curl -sS --connect-timeout 2 --max-time 6 -H 'Accept: application/xml' $tokenHeader -w '\nHTTP=%{http_code}' http://127.0.0.1:32400/identity 2>&1)
              printf '%s\n' "${'$'}PLEX_ID" | sed -E 's/(machineIdentifier|claimedIdentifier)="[^"]+"/\1="[masqué]"/g' | head -8
              $plexLibrarySection
            else
              echo 'curl absent sur Debian : test API Plex ignoré.'
            fi
        """.trimIndent() else ""
        val sshSection = if (cible in setOf("complet", "debian", "ssh")) """
            echo
            echo '== SSH : service, écoute et configuration effective =='
            (${rootCmd}systemctl is-active ssh 2>/dev/null || ${rootCmd}systemctl is-active sshd 2>/dev/null || true)
            (${rootCmd}ss -lntp 2>/dev/null | grep -E '(:22|sshd)' || true)
            if [ -x /usr/sbin/sshd ]; then
              ${rootCmd}/usr/sbin/sshd -T 2>/dev/null | grep -E '^(port|listenaddress|passwordauthentication|pubkeyauthentication|permitrootlogin|maxauthtries|allowusers|denyusers) ' | head -20
            elif command -v sshd >/dev/null 2>&1; then
              ${rootCmd}sshd -T 2>/dev/null | grep -E '^(port|listenaddress|passwordauthentication|pubkeyauthentication|permitrootlogin|maxauthtries|allowusers|denyusers) ' | head -20
            else
              echo 'Binaire sshd non trouvé dans le PATH.'
            fi
            echo 'Derniers événements SSH (identifiants et adresses peuvent apparaître) :'
            (${rootCmd}journalctl -u ssh -u sshd -n 25 --no-pager 2>/dev/null || true) | tail -25
            echo 'Authentifications récentes (journal fichier, si présent) :'
            (${rootCmd}grep -hEi 'Failed password|Invalid user|Accepted (password|publickey)|authentication failure' /var/log/auth.log /var/log/secure 2>/dev/null || true) | tail -20
            echo 'Fail2ban :'
            FAIL2BAN_JAILS=$(${rootCmd}fail2ban-client status 2>/dev/null | sed -n 's/.*Jail list:[[:space:]]*//p' | tr ',' ' ')
            if [ -n "${'$'}FAIL2BAN_JAILS" ]; then
              echo "Fail2ban : actif — jails : $(printf '%s' "${'$'}FAIL2BAN_JAILS" | xargs)"
              for jail in ${'$'}FAIL2BAN_JAILS; do
                [ -n "${'$'}jail" ] || continue
                echo "--- Fail2ban : ${'$'}jail ---"
                ${rootCmd}fail2ban-client status "${'$'}jail" 2>/dev/null | grep -E 'Currently failed|Total failed|Currently banned|Total banned' | head -8
              done
            else
              echo 'Fail2ban absent, inactif, sans jail ou droits insuffisants.'
            fi
            echo 'Pare-feu UFW :'
            (${rootCmd}ufw status 2>/dev/null || echo 'UFW absent ou droits insuffisants') | head -20
        """.trimIndent() else ""
        val discordSection = if (cible in setOf("discord", "bagbot")) """
            echo
            echo '== Bagbot / Discord : état PM2 (lecture seule) ==' 
            if ! command -v pm2 >/dev/null 2>&1; then
              echo 'PM2 introuvable pour cet utilisateur SSH.'
              echo 'Le démon PM2 est propre à chaque utilisateur Linux ; le bot peut être lancé sous un autre compte.'
            elif [ ! -S "$(getent passwd "$(id -u)" | cut -d: -f6)/.pm2/rpc.sock" ]; then
              echo 'Démon PM2 non actif pour cet utilisateur SSH (socket absent) ; aucune tentative de démarrage.'
            elif ! command -v node >/dev/null 2>&1; then
              echo 'Node.js absent : état PM2 simplifié.'
              pm2 ls 2>&1 | head -20
            else
              echo 'Processus PM2 Bagbot/Discord (métadonnées non sensibles uniquement) :'
              pm2 jlist 2>/dev/null | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const a=JSON.parse(s);const f=a.filter(p=>/bagbot|discord/i.test([p.name,p.pm2_env&&p.pm2_env.pm_exec_path,p.pm2_env&&p.pm2_env.pm_cwd].join(" ")));if(!f.length){console.log("Aucun processus Bagbot/Discord trouvé pour cet utilisateur.");return;}for(const p of f){const e=p.pm2_env||{};const m=p.monit||{};console.log("nom="+p.name+" | id="+p.pm_id+" | état="+(e.status||"?")+" | pid="+p.pid+" | redémarrages="+(e.restart_time??"?")+" | CPU="+(m.cpu??"?")+"% | mémoire="+(m.memory??"?")+" octets | script="+(e.pm_exec_path||"?")+" | dossier="+(e.pm_cwd||"?"));}}catch(e){console.log("Réponse PM2 illisible.");}})'
              echo 'Journaux : comptage seulement, aucun contenu de message ni variable d’environnement affiché.'
              pm2 logs --lines $n --nostream 2>&1 | grep -iE 'bagbot|discord' | grep -ciE 'error|exception|failed|fatal|warning|timeout|disconnect|reconnect|ready|login|gateway|intent|missing access|forbidden|rate.?limit|401|403|429' | awk '{print "Événements/erreurs surveillés : " $1}'
            fi
            echo 'Aucun redémarrage, arrêt ou changement de configuration effectué.'
        """.trimIndent() else ""
        val bilan = if (cible in setOf("discord", "bagbot")) "" else bilan(rootCmd, cible, sudo && !enRoot)
        val reperesSsh = if (cible in setOf("complet", "debian", "ssh")) reperesSsh(rootCmd) else ""
        return (if (enRoot) "sudo true\n" else "") + """
            set +e
            echo '== Hôte Debian =='
            hostname
            date -Is 2>/dev/null
            uptime
            echo
            echo '== Ressources système =='
            free -h 2>&1 | head -3
            df -hT 2>&1 | head -12
            df -i 2>&1 | head -12
            echo
            echo '== Réseau et ports utiles =='
            (ip -brief address 2>/dev/null || hostname -I 2>/dev/null)
            (ss -lnt 2>/dev/null | grep -E ':(22|80|443|8123|32400)\b' || true)
            $bilan
            $sshSection
            $reperesSsh
            $dockerSection
            $plexSection
            $discordSection
            echo
            echo '== Processus les plus consommateurs =='
            ps -eo pid,comm,%cpu,%mem,rss --sort=-%cpu 2>/dev/null | head -8
            echo
            echo 'Diagnostic terminé (aucune modification effectuée).'
        """.trimIndent()
    }

    /** Écrit en shell avec « ¤ » à la place de « $ » (pas de gabarits Kotlin à échapper), « SUDO » à la place du préfixe root. */
    private fun sh(script: String, rootCmd: String) = script.trimIndent().replace("¤", "$").replace("SUDO ", rootCmd)

    /**
     * Repères lisibles par JARVIS (lignes « @CLÉ valeurs ») : charge, mémoire, disques, démarrage, mémoire saturée (OOM),
     * services en échec, températures, santé des disques, erreurs disque du noyau, heure, DNS / Internet, mises à jour,
     * journaux, Docker et ses conteneurs. Rien n'est modifié.
     */
    private fun bilan(rootCmd: String, cible: String, testerSudo: Boolean): String = sh("""
        echo
        echo '== Bilan de santé =='
        ${if (testerSudo) "sudo -n true 2>/dev/null && echo '@DROITS ok' || echo '@DROITS non'" else "echo '@DROITS ok'"}
        echo "@CHARGE ¤(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null) ¤(nproc 2>/dev/null || echo 1)"
        free -m 2>/dev/null | awk '/^Mem:/ {print "@MEM", ¤2, ¤3, ¤7} /^Swap:/ {print "@SWAP", ¤2, ¤3}'
        df -P -x tmpfs -x devtmpfs -x overlay -x squashfs 2>/dev/null | awk 'NR>1 {gsub("%","",¤5); print "@DISQUE", ¤5, ¤6, ¤4}'
        df -iP -x tmpfs -x devtmpfs -x overlay -x squashfs 2>/dev/null | awk 'NR>1 && ¤5 != "-" {gsub("%","",¤5); print "@INODES", ¤5, ¤6}'
        echo "@DEMARRAGE ¤(uptime -s 2>/dev/null)"
        [ -f /var/run/reboot-required ] && echo '@REBOOT_REQUIS'
        OOM=¤(SUDO journalctl -k --since '-7 days' --no-pager 2>/dev/null | grep -iE 'out of memory|oom-kill|killed process')
        echo "@OOM ¤(printf '%s' "¤OOM" | grep -ciE 'killed process')"
        printf '%s\n' "¤OOM" | grep -iE 'killed process' | tail -3 | sed -E 's/.*(Killed process [0-9]+ \(([^)]*)\)).*/@OOMDETAIL \2/'
        systemctl --failed --no-legend --plain 2>/dev/null | awk '{print "@ECHEC", ¤1}' | head -10
        for z in /sys/class/thermal/thermal_zone*; do [ -r "¤z/temp" ] && echo "@TEMP ¤(cat "¤z/type" 2>/dev/null) ¤(( ¤(cat "¤z/temp") / 1000 ))"; done 2>/dev/null | head -8
        if command -v sensors >/dev/null 2>&1; then sensors 2>/dev/null | grep -E '^(Package id|Core [0-9]+|temp[0-9]+|Composite)' | sed -E 's/^([^:]+):[^0-9+-]*\+?([0-9]+).*/@TEMP \1 \2/' | tr -s ' ' | head -6; fi
        if command -v smartctl >/dev/null 2>&1; then
          for d in ¤(lsblk -dno NAME,TYPE 2>/dev/null | awk '¤2=="disk" {print ¤1}' | head -4); do
            echo "@SMART ¤d ¤(SUDO smartctl -H /dev/¤d 2>/dev/null | grep -oiE 'PASSED|FAILED|OK' | head -1)"
          done
        else echo '@SMART_ABSENT'; fi
        echo "@ERRDISQUE ¤(SUDO journalctl -k -p err --since '-7 days' --no-pager 2>/dev/null | grep -ciE 'I/O error|EXT4-fs error|blk_update_request|ata[0-9]+.*(error|failed)|nvme.*(error|timeout)')"
        echo "@NTP ¤(timedatectl show -p NTPSynchronized --value 2>/dev/null)"
        getent hosts deb.debian.org >/dev/null 2>&1 && echo '@DNS ok' || echo '@DNS ko'
        (ping -c1 -W2 1.1.1.1 >/dev/null 2>&1 || ping -c1 -W2 8.8.8.8 >/dev/null 2>&1) && echo '@INTERNET ok' || echo '@INTERNET ko'
        if command -v apt >/dev/null 2>&1; then
          MAJ=¤(apt list --upgradable 2>/dev/null | grep -c upgradable)
          echo "@MAJ ¤MAJ ¤(apt list --upgradable 2>/dev/null | grep -ci security)"
        fi
        echo "@JOURNAUX ¤(SUDO journalctl --disk-usage 2>/dev/null | grep -oE '[0-9.]+[KMGT]' | head -1) ¤(SUDO du -sh /var/log 2>/dev/null | cut -f1)"
    """, rootCmd) + if (cible == "ssh") "" else "\n" + sh("""
        if command -v docker >/dev/null 2>&1; then
          echo "@DOCKER ¤(systemctl is-active docker 2>/dev/null)"
          SUDO docker ps -aq 2>/dev/null | head -20 | while read id; do SUDO docker inspect -f '@CONTENEUR {{.Name}}|{{.State.Status}}|{{.State.ExitCode}}|{{.State.OOMKilled}}|{{.RestartCount}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}|{{.State.StartedAt}}' "¤id" 2>/dev/null; done
          echo "@DOCKER_RECUP ¤(SUDO docker system df 2>/dev/null | awk '/^Images/ {print ¤5}')"
        else echo '@DOCKER absent'; fi
    """, rootCmd)

    /** Repères SSH : service, test de la configuration, port configuré / ports écoutés, sécurité, échecs, droits des clés. */
    private fun reperesSsh(rootCmd: String): String = sh("""
        echo
        echo '== SSH : repères =='
        echo "@SSH_SERVICE ¤(systemctl is-active ssh 2>/dev/null || systemctl is-active sshd 2>/dev/null)"
        SSHD=¤(command -v sshd 2>/dev/null || echo /usr/sbin/sshd)
        if [ -x "¤SSHD" ]; then
          T=¤(SUDO "¤SSHD" -t 2>&1)
          if [ -z "¤T" ]; then echo '@SSH_TEST ok'; else echo "@SSH_TEST ¤(printf '%s' "¤T" | head -2 | tr '\n' ' ')"; fi
          SUDO "¤SSHD" -T 2>/dev/null | awk '¤1=="port" {print "@SSH_PORT_CONF", ¤2} ¤1=="passwordauthentication" {print "@SSH_MOTDEPASSE", ¤2} ¤1=="permitrootlogin" {print "@SSH_ROOT", ¤2} ¤1=="pubkeyauthentication" {print "@SSH_CLE", ¤2}'
        fi
        echo "@ECOUTE ¤(ss -lnt 2>/dev/null | awk 'NR>1 {print ¤4}' | sed 's/.*://' | sort -un | tr '\n' ' ')"
        echo "@SSH_ECHECS_24H ¤(SUDO journalctl -u ssh -u sshd --since '-24 hours' --no-pager 2>/dev/null | grep -ciE 'Failed password|Invalid user|authentication failure')"
        echo "@SSH_IP_ECHECS ¤(SUDO journalctl -u ssh -u sshd --since '-24 hours' --no-pager 2>/dev/null | grep -iE 'Failed password|Invalid user' | grep -oE 'from [0-9a-f.:]+' | sort | uniq -c | sort -rn | head -3 | awk '{print ¤3 "×" ¤1}' | tr '\n' ' ')"
        echo "@SSH_PERMS ¤(stat -c %a "¤HOME" 2>/dev/null) ¤(stat -c %a "¤HOME/.ssh" 2>/dev/null) ¤(stat -c %a "¤HOME/.ssh/authorized_keys" 2>/dev/null) ¤(grep -cE '^(ssh-|ecdsa-|sk-)' "¤HOME/.ssh/authorized_keys" 2>/dev/null)"
        if command -v fail2ban-client >/dev/null 2>&1; then echo "@FAIL2BAN ¤(SUDO fail2ban-client ping 2>/dev/null | grep -q pong && echo actif || echo inactif)"; else echo '@FAIL2BAN absent'; fi
        echo "@UFW ¤(SUDO ufw status 2>/dev/null | head -1 | awk '{print ¤2}')"
    """, rootCmd)

    /** Constat du bilan : [niveau] 2 = problème, 1 = à surveiller, 0 = bon ; [remede] : ce que JARVIS propose. */
    data class Constat(val niveau: Int, val sujet: String, val texte: String, val remede: String? = null)

    private fun reperes(sortie: String): Map<String, List<String>> = sortie.lineSequence().map { it.trim() }
        .filter { it.startsWith("@") }.groupBy({ it.substringBefore(' ').removePrefix("@") }, { it.substringAfter(' ', "").trim() })

    private fun nombre(t: String?): Double? = t?.trim()?.replace(',', '.')?.toDoubleOrNull()

    /** Taille « 1.5G », « 800M », « 2.3GB » → Mo. */
    internal fun enMo(t: String?): Double? {
        val m = Regex("([0-9.]+)\\s*([KMGT])", RegexOption.IGNORE_CASE).find(t ?: return null) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2].uppercase()) { "K" -> v / 1024; "M" -> v; "G" -> v * 1024; else -> v * 1024 * 1024 }
    }

    /**
     * Lit les repères du script et dit ce qui va, ce qui ne va pas, et quoi faire. [maintenant] : pour la durée depuis
     * le démarrage (remplaçable dans les tests).
     */
    internal fun analyser(sortie: String, maintenant: java.time.LocalDateTime = java.time.LocalDateTime.now()): List<Constat> {
        val r = reperes(sortie)
        val out = mutableListOf<Constat>()
        fun un(k: String) = r[k]?.firstOrNull()
        // Charge
        un("CHARGE")?.split(' ')?.let { c ->
            val l5 = nombre(c.getOrNull(1)); val cpu = nombre(c.getOrNull(3)) ?: 1.0
            if (l5 != null) out += when {
                l5 > cpu * 3 -> Constat(2, "charge", "Processeur saturé : charge ${c[1]} sur 5 min pour ${cpu.toInt()} cœur(s).", "Regarder quel processus consomme (« processus du serveur ») ; souvent un transcodage Plex ou un conteneur en boucle.")
                l5 > cpu * 1.5 -> Constat(1, "charge", "Processeur chargé : charge ${c[1]} pour ${cpu.toInt()} cœur(s).")
                else -> Constat(0, "charge", "Processeur : charge ${c[1]} (${cpu.toInt()} cœur(s)).")
            }
        }
        // Mémoire et swap
        un("MEM")?.split(' ')?.let { m ->
            val total = nombre(m.getOrNull(0)); val dispo = nombre(m.getOrNull(2))
            if (total != null && dispo != null && total > 0) {
                val pct = dispo / total * 100
                out += when {
                    pct < 10 -> Constat(2, "memoire", "Mémoire presque pleine : ${dispo.toInt()} Mo libres sur ${total.toInt()} Mo.", "Arrêter un service gourmand ou limiter la mémoire de Plex ; ajouter un fichier d'échange (swap) si le serveur n'en a pas.")
                    pct < 20 -> Constat(1, "memoire", "Mémoire juste : ${dispo.toInt()} Mo libres sur ${total.toInt()} Mo.")
                    else -> Constat(0, "memoire", "Mémoire : ${dispo.toInt()} Mo libres sur ${total.toInt()} Mo.")
                }
            }
        }
        un("SWAP")?.split(' ')?.let { sw ->
            val t = nombre(sw.getOrNull(0)) ?: 0.0; val u = nombre(sw.getOrNull(1)) ?: 0.0
            if (t == 0.0) out += Constat(1, "swap", "Aucun fichier d'échange (swap) : quand la mémoire est pleine, le noyau tue un processus (Home Assistant, Plex…).", "Créer un swap de 2 Go (je peux te donner la commande, ou le faire si tu me le demandes avec ssh).")
            else if (u / t > 0.6) out += Constat(1, "swap", "Fichier d'échange utilisé à ${(u / t * 100).toInt()} % (${u.toInt()} Mo) : la mémoire manque régulièrement.")
        }
        // Mémoire saturée : processus tués par le noyau
        nombre(un("OOM"))?.toInt()?.takeIf { it > 0 }?.let { n ->
            val qui = r["OOMDETAIL"].orEmpty().filter { it.isNotBlank() }.distinct().joinToString(", ")
            out += Constat(2, "oom", "Mémoire saturée : le noyau a tué $n processus en 7 jours${if (qui.isNotBlank()) " ($qui)" else ""} — cause classique des redémarrages de Home Assistant ou de Plex.",
                "Ajouter du swap, limiter la mémoire des conteneurs, ou arrêter ce qui ne sert pas.")
        }
        // Disques et inodes
        r["DISQUE"].orEmpty().forEach { l ->
            val p = l.split(' '); val pct = nombre(p.getOrNull(0)) ?: return@forEach; val point = p.getOrNull(1).orEmpty()
            when {
                pct >= 95 -> out += Constat(2, "disque", "Disque $point plein à ${pct.toInt()} %.", "« nettoie le disque du serveur » : journaux au-delà de 200 Mo, cache des paquets, images Docker inutilisées.")
                pct >= 85 -> out += Constat(1, "disque", "Disque $point rempli à ${pct.toInt()} %.", "« nettoie le disque du serveur » libère en général plusieurs centaines de Mo.")
            }
        }
        if (r["DISQUE"].orEmpty().isNotEmpty() && out.none { it.sujet == "disque" })
            out += Constat(0, "disque", "Disques : " + r.getValue("DISQUE").mapNotNull { l -> l.split(' ').let { p -> p.getOrNull(1)?.let { "$it ${p[0]} %" } } }.take(4).joinToString(", ") + ".")
        r["INODES"].orEmpty().forEach { l ->
            val p = l.split(' '); val pct = nombre(p.getOrNull(0)) ?: return@forEach
            if (pct >= 90) out += Constat(2, "inodes", "Plus de place pour de nouveaux fichiers sur ${p.getOrNull(1)} (${pct.toInt()} % des inodes) : des milliers de petits fichiers (journaux, cache).", "Chercher le dossier qui contient le plus de fichiers et le vider.")
        }
        // Démarrage récent, redémarrage requis
        un("DEMARRAGE")?.let { d -> runCatching { java.time.LocalDateTime.parse(d.trim().replace(' ', 'T')) }.getOrNull() }?.let { boot ->
            val h = java.time.Duration.between(boot, maintenant).toHours()
            if (h in 0..23) out += Constat(1, "demarrage", "Le serveur a redémarré il y a ${if (h == 0L) "moins d'une heure" else "$h h"} (${boot.toLocalTime().withNano(0)}) — coupure de courant, plantage ou redémarrage voulu ?")
            else out += Constat(0, "demarrage", "Serveur allumé depuis ${h / 24} jour(s).")
        }
        if (r.containsKey("REBOOT_REQUIS")) out += Constat(1, "reboot", "Mises à jour (noyau) en attente d'un redémarrage du serveur.", "Redémarrer le serveur à un moment calme.")
        // Services en échec
        r["ECHEC"].orEmpty().filter { it.isNotBlank() }.let { u ->
            if (u.isNotEmpty()) out += Constat(2, "echec", "Service(s) en échec : ${u.joinToString(", ")}.", "« relance les services en échec du serveur » (je les redémarre puis je vérifie).")
        }
        // Températures
        r["TEMP"].orEmpty().mapNotNull { l -> l.substringAfterLast(' ').toIntOrNull()?.let { l.substringBeforeLast(' ') to it } }
            .filter { it.second in 1..130 }.maxByOrNull { it.second }?.let { (nom, t) ->
                out += when {
                    t >= 85 -> Constat(2, "temperature", "Surchauffe : $t °C ($nom).", "Dépoussiérer le PC et vérifier son ventilateur : la surchauffe provoque des arrêts brutaux.")
                    t >= 75 -> Constat(1, "temperature", "Température élevée : $t °C ($nom).")
                    else -> Constat(0, "temperature", "Température : $t °C au plus.")
                }
            }
        // Disques : santé SMART et erreurs du noyau
        r["SMART"].orEmpty().forEach { l ->
            val p = l.split(' '); val etat = p.getOrNull(1).orEmpty()
            if (etat.isNotBlank() && !etat.equals("PASSED", true) && !etat.equals("OK", true))
                out += Constat(2, "smart", "Le disque ${p[0]} se signale défaillant (SMART : $etat).", "Sauvegarder tout de suite (Home Assistant, Plex) et prévoir son remplacement.")
        }
        nombre(un("ERRDISQUE"))?.toInt()?.takeIf { it > 0 }?.let {
            out += Constat(2, "erreurs_disque", "$it erreur(s) de lecture/écriture disque dans le journal du noyau (7 jours).", "Vérifier le câble / le disque et sauvegarder : un disque qui lâche corrompt la base de Home Assistant.")
        }
        // Heure, DNS, Internet
        when (un("NTP")?.lowercase()) { "no" -> out += Constat(1, "heure", "L'heure du serveur n'est pas synchronisée (NTP).", "« synchronise l'heure du serveur ».") }
        if (un("DNS") == "ko") out += Constat(2, "dns", "Le serveur n'arrive pas à résoudre les noms (DNS) : mises à jour, intégrations cloud (poêles Aguaiot…) en échec.", "Vérifier /etc/resolv.conf et la box.")
        if (un("INTERNET") == "ko") out += Constat(2, "internet", "Le serveur n'a pas accès à Internet.", "Vérifier le câble réseau / la box ; les intégrations cloud de Home Assistant ne marcheront pas.")
        // Mises à jour
        un("MAJ")?.split(' ')?.let { m ->
            val n = nombre(m.getOrNull(0))?.toInt() ?: 0; val sec = nombre(m.getOrNull(1))?.toInt() ?: 0
            if (sec > 0) out += Constat(1, "maj", "$n mise(s) à jour disponible(s), dont $sec de sécurité.", "Les installer (apt upgrade) à un moment calme.")
            else if (n > 0) out += Constat(0, "maj", "$n mise(s) à jour disponible(s).")
        }
        // Journaux
        un("JOURNAUX")?.split(' ')?.let { j ->
            val mo = enMo(j.getOrNull(0)); if (mo != null && mo > 1024) out += Constat(1, "journaux", "Les journaux système occupent ${j[0]}.", "« nettoie le disque du serveur » les ramène à 200 Mo.")
        }
        // Docker et conteneurs
        when (un("DOCKER")) {
            null, "", "absent", "active" -> Unit   // vide : état inconnu (pas de systemd), pas une panne
            else -> out += Constat(2, "docker", "Docker n'est pas actif (${un("DOCKER")}) : Home Assistant et Plex sont arrêtés.", "Redémarrer Docker (« redémarre docker sur le serveur »).")
        }
        r["CONTENEUR"].orEmpty().forEach { l ->
            val c = l.split('|'); if (c.size < 6) return@forEach
            val nom = c[0].trim().removePrefix("/"); val etat = c[1]; val code = c[2]; val oom = c[3] == "true"; val red = c[4].toIntOrNull() ?: 0; val sante = c[5]
            when {
                etat == "restarting" -> out += Constat(2, "conteneur", "Le conteneur $nom redémarre en boucle (code $code, $red redémarrage(s)).", "Lire ses journaux (« journaux du conteneur $nom ») : erreur de configuration ou mémoire.")
                etat == "exited" && code != "0" -> out += Constat(2, "conteneur", "Le conteneur $nom est arrêté sur une erreur (code $code${if (code == "137") " : tué, souvent par manque de mémoire" else ""}).", "« redémarre le conteneur $nom » puis vérifier ses journaux.")
                oom -> out += Constat(2, "conteneur", "Le conteneur $nom a été tué par manque de mémoire.", "Limiter sa mémoire ou en libérer sur le serveur.")
                sante == "unhealthy" -> out += Constat(2, "conteneur", "Le conteneur $nom est déclaré en mauvaise santé.", "Lire ses journaux et le redémarrer.")
                red >= 5 -> out += Constat(1, "conteneur", "Le conteneur $nom a déjà redémarré $red fois.")
            }
        }
        val conteneurs = r["CONTENEUR"].orEmpty().mapNotNull { it.split('|').takeIf { c -> c.size >= 2 } }
        if (conteneurs.isNotEmpty() && out.none { it.sujet == "conteneur" || it.sujet == "docker" })
            out += Constat(0, "conteneur", "Docker : ${conteneurs.count { it[1] == "running" }} conteneur(s) en marche sur ${conteneurs.size}.")
        enMo(un("DOCKER_RECUP"))?.takeIf { it > 1024 }?.let { out += Constat(1, "docker_place", "Images Docker inutilisées : ${un("DOCKER_RECUP")} récupérables.", "« nettoie le disque du serveur ».") }
        // SSH
        when (un("SSH_SERVICE")) {
            null, "", "active" -> Unit
            else -> out += Constat(2, "ssh", "Le service SSH n'est pas actif (${un("SSH_SERVICE")}).", "Le démarrer depuis le serveur lui-même (écran/clavier) : « sudo systemctl start ssh ».")
        }
        un("SSH_TEST")?.takeIf { it != "ok" && it.isNotBlank() }?.let { out += Constat(2, "ssh", "La configuration SSH contient une erreur : « ${it.take(160)} » — au prochain redémarrage, SSH ne repartira pas.", "Corriger /etc/ssh/sshd_config (je peux le lire et te proposer la correction).") }
        val ecoute = un("ECOUTE").orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
        un("SSH_PORT_CONF")?.let { port -> if (ecoute.isNotEmpty() && port !in ecoute) out += Constat(2, "ssh", "SSH est configuré sur le port $port mais rien n'écoute sur ce port.", "Redémarrer SSH (« sudo systemctl restart ssh ») après avoir vérifié la configuration.") }
        val echecs = nombre(un("SSH_ECHECS_24H"))?.toInt() ?: 0
        val f2b = un("FAIL2BAN")
        if (echecs >= 50 && f2b != "actif") out += Constat(1, "ssh_securite", "$echecs tentatives de connexion SSH ratées en 24 h${un("SSH_IP_ECHECS")?.takeIf { it.isNotBlank() }?.let { " (surtout $it)" } ?: ""}, sans Fail2ban pour les bloquer.", "Installer Fail2ban et n'autoriser que les clés SSH.")
        else if (echecs >= 50) out += Constat(0, "ssh_securite", "$echecs tentatives SSH ratées en 24 h, bloquées par Fail2ban.")
        if (un("SSH_MOTDEPASSE") == "yes" && un("SSH_ROOT") == "yes") out += Constat(1, "ssh_securite", "Connexion SSH de root par mot de passe autorisée : risqué si le port est ouvert sur Internet.", "Mettre « PermitRootLogin prohibit-password » (ou no) dans sshd_config.")
        un("SSH_PERMS")?.split(' ')?.let { p ->
            val maison = p.getOrNull(0)?.toIntOrNull(8); val dossier = p.getOrNull(1)?.toIntOrNull(8); val cles = p.getOrNull(2)?.toIntOrNull(8)
            val tropOuvert = (maison != null && maison and "022".toInt(8) != 0) || (dossier != null && dossier and "077".toInt(8) != 0) || (cles != null && cles and "022".toInt(8) != 0)
            if (tropOuvert) out += Constat(1, "ssh_cles", "Droits trop ouverts sur le dossier personnel ou ~/.ssh (${p.take(3).joinToString(" / ")}) : SSH refuse alors la connexion par clé.", "chmod 755 ~ ; chmod 700 ~/.ssh ; chmod 600 ~/.ssh/authorized_keys.")
        }
        if (un("SSH_SERVICE") == "active" && out.none { it.sujet == "ssh" }) out += Constat(0, "ssh", "SSH : service actif" + (un("SSH_PORT_CONF")?.let { ", port $it" } ?: "") + (if (un("SSH_TEST") == "ok") ", configuration valide" else "") + ".")
        if (un("DROITS") == "non") out += Constat(1, "sudo", "Une partie du bilan demande les droits administrateur (sudo) : enregistre le mot de passe SSH dans Réglages › Maison › Serveur pour un bilan complet.")
        return out
    }

    /** Bilan lisible : problèmes, points à surveiller, ce qui va bien, et le dépannage proposé. */
    internal fun rapport(constats: List<Constat>): String {
        if (constats.isEmpty()) return "Aucun repère lisible dans la sortie du serveur (script interrompu ou droits insuffisants)."
        val graves = constats.filter { it.niveau == 2 }; val attention = constats.filter { it.niveau == 1 }; val bons = constats.filter { it.niveau == 0 }
        val sb = StringBuilder()
        sb.append(when {
            graves.isNotEmpty() -> "## 🩺 Bilan : ${graves.size} problème(s)" + (if (attention.isNotEmpty()) ", ${attention.size} point(s) à surveiller" else "")
            attention.isNotEmpty() -> "## 🩺 Bilan : rien de grave, ${attention.size} point(s) à surveiller"
            else -> "## 🩺 Bilan : tout va bien"
        }).append("\n")
        graves.forEach { sb.append("🔴 ").append(it.texte).append("\n") }
        attention.forEach { sb.append("🟠 ").append(it.texte).append("\n") }
        bons.forEach { sb.append("🟢 ").append(it.texte).append("\n") }
        val remedes = (graves + attention).mapNotNull { it.remede }.distinct()
        if (remedes.isNotEmpty()) {
            sb.append("\n## 🛠️ Dépannage proposé\n")
            remedes.forEachIndexed { i, r -> sb.append("${i + 1}. ").append(r).append("\n") }
        }
        return sb.toString().trimEnd()
    }

    /** Sortie brute sans les repères « @… » (déjà résumés dans le bilan). */
    internal fun sansReperes(sortie: String) = sortie.lineSequence().filterNot { it.trimStart().startsWith("@") }.joinToString("\n")

    /** Remèdes que JARVIS sait appliquer (avec ta confirmation). */
    enum class Remede(val question: String) {
        NETTOYER_DISQUE("Nettoyer le disque du serveur ? (journaux réduits à 200 Mo, cache des paquets vidé, images Docker et cache de construction inutilisés supprimés — aucun conteneur, volume ni donnée)"),
        RELANCER_ECHECS("Redémarrer les services systemd en échec du serveur, puis vérifier leur état ?"),
        SYNCHRONISER_HEURE("Activer la synchronisation automatique de l'heure (NTP) du serveur ?"),
        REDEMARRER_DOCKER("Redémarrer Docker sur le serveur ? (Home Assistant, Plex et les autres conteneurs s'arrêtent ~1 min)")
    }

    fun remede(nom: String): Remede? = when (fr.dd.jarvis.societe.core.normaliser(nom).replace(' ', '_')) {
        "nettoyer_disque", "nettoyage", "disque", "nettoyer" -> Remede.NETTOYER_DISQUE
        "relancer_echecs", "relancer_services", "services_en_echec", "echecs" -> Remede.RELANCER_ECHECS
        "synchroniser_heure", "heure", "ntp" -> Remede.SYNCHRONISER_HEURE
        "redemarrer_docker", "docker" -> Remede.REDEMARRER_DOCKER
        else -> null
    }

    /** Commande d'un remède : « sudo » (mot de passe enregistré utilisé), mesure avant / après. */
    fun commandeRemede(r: Remede): String = when (r) {
        Remede.NETTOYER_DISQUE -> sh("""
            avant=¤(df -Pm / | awk 'NR==2 {print ¤4}')
            sudo journalctl --vacuum-size=200M 2>&1 | tail -1
            sudo apt-get clean 2>&1 | tail -1
            if command -v docker >/dev/null 2>&1; then sudo docker image prune -f 2>&1 | tail -1; sudo docker builder prune -f 2>&1 | tail -1; fi
            apres=¤(df -Pm / | awk 'NR==2 {print ¤4}')
            echo "@LIBERE ¤((apres - avant)) ¤apres"
        """, "")
        Remede.RELANCER_ECHECS -> sh("""
            U=¤(systemctl --failed --no-legend --plain 2>/dev/null | awk '{print ¤1}' | head -10)
            [ -z "¤U" ] && echo 'Aucun service en échec.'
            for u in ¤U; do sudo systemctl restart "¤u" 2>&1 | tail -2; sleep 2; echo "¤u → ¤(systemctl is-active "¤u" 2>/dev/null)"; done
            sudo systemctl reset-failed 2>/dev/null
        """, "")
        Remede.SYNCHRONISER_HEURE -> "sudo timedatectl set-ntp true 2>&1; sleep 3; echo \"synchronisée : $(timedatectl show -p NTPSynchronized --value 2>/dev/null)\""
        Remede.REDEMARRER_DOCKER -> "sudo systemctl restart docker 2>&1 | tail -2; sleep 5; echo \"docker : $(systemctl is-active docker)\"; sudo docker ps --format '{{.Names}} {{.Status}}' 2>&1 | head -15"
    }

    /** Compte rendu d'un remède appliqué. */
    fun resultatRemede(r: Remede, sortie: String): String = when (r) {
        Remede.NETTOYER_DISQUE -> Regex("@LIBERE (-?\\d+) (\\d+)").find(sortie)?.let { m ->
            val lib = m.groupValues[1].toLong(); val libre = m.groupValues[2].toLong()
            String.format(java.util.Locale.FRANCE, "🧹 Disque nettoyé : %s, %.1f Go libres sur /.", if (lib > 0) "$lib Mo libérés" else "rien de plus à libérer", libre / 1024.0)
        } ?: ("🧹 Nettoyage lancé :\n" + sortie.take(800))
        else -> "✅ " + r.question.substringBefore(" ?").substringBefore(" (") + " : fait.\n" + sortie.trim().take(1200)
    }

    /**
     * Demande de diagnostic du serveur dite en clair (« diagnostique le serveur », « le serveur rame », « pourquoi je
     * n'arrive pas à me connecter en SSH », « bilan de santé du serveur »…) → cible du diagnostic, ou null.
     * Les redémarrages de Home Assistant ont leur propre enquête (HaSur).
     */
    fun demande(texte: String): String? {
        val n = " " + fr.dd.jarvis.societe.core.normaliser(texte) + " "
        val serveur = Regex(" (serveur|server|debian|machine|pc du salon|nas|ssh) ").containsMatchIn(n)
        if (!serveur) return null
        val verbe = Regex(" (diagnosti\\w*|bilan|check\\w*|verifi\\w*|controle\\w*|analyse\\w*|depann\\w*|inspecte\\w*|etat|sante|comment va|ca va|regarde|examine|teste|probleme\\w*|panne|rame\\w*|lent|lente|plante\\w*|bloque\\w*|repond (plus|pas)|marche (plus|pas)|fonctionne (plus|pas)|arrive (plus|pas) a|n arrive|impossible de|pourquoi|surchauffe|plein) ").containsMatchIn(n)
        if (!verbe) return null
        // Actions (redémarrer, arrêter, installer, copier…) : pas un diagnostic — sauf « lance un diagnostic / un bilan ».
        val explicite = Regex(" (diagnosti\\w*|bilan|check\\w*|verifi\\w*|depann\\w*) ").containsMatchIn(n)
        if (!explicite && Regex(" (redemarre|redemarrer|reboot|arrete|eteins|eteindre|installe|installer|supprime|nettoie|mets a jour|met a jour|lance|demarre|cree|ajoute|copie|envoie|telecharge|ouvre|lis|ecris|modifie) ").containsMatchIn(n)) return null
        return when {
            Regex(" ssh ").containsMatchIn(n) -> "ssh"
            Regex(" plex ").containsMatchIn(n) -> "plex"
            Regex(" (bot|discord|bagbot) ").containsMatchIn(n) -> "discord"
            else -> "complet"
        }
    }

    /** « nettoie le disque du serveur », « relance les services en échec du serveur »… → remède, ou null. */
    fun demandeRemede(texte: String): Remede? {
        val n = " " + fr.dd.jarvis.societe.core.normaliser(texte) + " "
        if (!Regex(" (serveur|server|debian) ").containsMatchIn(n)) return null
        return when {
            Regex(" (nettoie|nettoyer|fais (de la )?place|libere|liberer|vide|vider) .{0,30}(disque|place|espace|stockage|serveur)").containsMatchIn(n) -> Remede.NETTOYER_DISQUE
            Regex(" (relance|redemarre|repare) .{0,20}services? (en echec|plantes?|en panne)").containsMatchIn(n) -> Remede.RELANCER_ECHECS
            Regex(" (synchronise|regle|remets? a l heure|corrige) .{0,20}(l )?heure").containsMatchIn(n) -> Remede.SYNCHRONISER_HEURE
            Regex(" redemarre (le service )?docker").containsMatchIn(n) -> Remede.REDEMARRER_DOCKER
            else -> null
        }
    }

    fun resumeHomeAssistant(app: fr.dd.jarvis.JarvisApp): String {
        if (!Serveur.haConfigure(app)) return "API Home Assistant non configurée : contrôle API ignoré."
        return runCatching {
            val states = org.json.JSONArray(Serveur.ha(app, "GET", "/api/states"))
            val config = runCatching { org.json.JSONObject(Serveur.ha(app, "GET", "/api/config")) }.getOrNull()
            HomeAssistantDiagnosticResume.formater(states, config?.optString("version").orEmpty())
        }.getOrElse { "API Home Assistant : échec — ${it.message?.take(220) ?: it.javaClass.simpleName}." }
    }
}
