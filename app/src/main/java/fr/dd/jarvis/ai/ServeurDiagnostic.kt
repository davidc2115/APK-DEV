package fr.dd.jarvis.ai

/**
 * Diagnostics SSH strictement en lecture seule.
 *
 * Le serveur Debian est l'hôte Docker. Les appels API Home Assistant sont faits séparément
 * par ServeurTools ; ce script ne tente jamais de piloter HA ni de modifier les services.
 */
internal object ServeurDiagnostic {
    fun commande(cibleBrute: String, plexToken: String, lignes: Int = 80, sudo: Boolean = false): String {
        val cible = fr.dd.jarvis.societe.core.normaliser(cibleBrute).ifBlank { "complet" }
        val cibleValide = cible in setOf("complet", "debian", "ssh", "plex", "home assistant", "homeassistant", "ha", "discord", "bagbot")
        require(cibleValide) { "Cible de diagnostic inconnue : $cibleBrute" }
        val n = lignes.coerceIn(20, 200)
        val rootCmd = if (sudo) "sudo " else ""
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
        return """
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
            $sshSection
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

    fun resumeHomeAssistant(app: fr.dd.jarvis.JarvisApp): String {
        if (!Serveur.haConfigure(app)) return "API Home Assistant non configurée : contrôle API ignoré."
        return runCatching {
            val states = org.json.JSONArray(Serveur.ha(app, "GET", "/api/states"))
            val config = runCatching { org.json.JSONObject(Serveur.ha(app, "GET", "/api/config")) }.getOrNull()
            HomeAssistantDiagnosticResume.formater(states, config?.optString("version").orEmpty())
        }.getOrElse { "API Home Assistant : échec — ${it.message?.take(220) ?: it.javaClass.simpleName}." }
    }
}
