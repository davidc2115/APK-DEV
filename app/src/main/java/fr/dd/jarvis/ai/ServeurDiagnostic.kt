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
        val cibleValide = cible in setOf("complet", "debian", "ssh", "plex", "home assistant", "homeassistant", "ha")
        require(cibleValide) { "Cible de diagnostic inconnue : $cibleBrute" }
        val n = lignes.coerceIn(20, 200)
        val rootCmd = if (sudo) "sudo " else ""
        val dockerFiltre = when (cible) {
            "plex" -> "plex"
            "ha", "home assistant", "homeassistant" -> "home.?assistant|hass"
            "ssh" -> ""
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
        """.trimIndent() else "echo 'Jeton Plex absent : identité seulement, bibliothèques et sessions non interrogées.'"
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
            (fail2ban-client status 2>/dev/null || echo 'Fail2ban absent ou inactif') | head -20
            echo 'Pare-feu UFW :'
            (${rootCmd}ufw status 2>/dev/null || echo 'UFW absent ou droits insuffisants') | head -20
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
}(printf '%s' ${' else "echo 'Jeton Plex absent : identité seulement, bibliothèques et sessions non interrogées.'"
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
            echo 'Pare-feu UFW :'
            (${rootCmd}ufw status 2>/dev/null || echo 'UFW absent ou droits insuffisants') | head -20
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
            var indisponibles = 0
            var inconnus = 0
            for (i in 0 until states.length()) when (states.optJSONObject(i)?.optString("state")) {
                "unavailable" -> indisponibles++
                "unknown" -> inconnus++
            }
            val config = runCatching { org.json.JSONObject(Serveur.ha(app, "GET", "/api/config")) }.getOrNull()
            "API Home Assistant : OK · version ${config?.optString("version").orEmpty().ifBlank { "non fournie" }} · ${states.length()} entités · $indisponibles indisponible(s) · $inconnus état(s) inconnu(s)."
        }.getOrElse { "API Home Assistant : échec — ${it.message?.take(220) ?: it.javaClass.simpleName}." }
    }
}
}PLEX_SESS | grep -o '<TranscodeSession ' | wc -l | tr -d ' ')"
            echo 'Détails des lectures (titres/clients, identifiants masqués) :'
            printf '%s' "${' else "echo 'Jeton Plex absent : identité seulement, bibliothèques et sessions non interrogées.'"
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
            echo 'Pare-feu UFW :'
            (${rootCmd}ufw status 2>/dev/null || echo 'UFW absent ou droits insuffisants') | head -20
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
            var indisponibles = 0
            var inconnus = 0
            for (i in 0 until states.length()) when (states.optJSONObject(i)?.optString("state")) {
                "unavailable" -> indisponibles++
                "unknown" -> inconnus++
            }
            val config = runCatching { org.json.JSONObject(Serveur.ha(app, "GET", "/api/config")) }.getOrNull()
            "API Home Assistant : OK · version ${config?.optString("version").orEmpty().ifBlank { "non fournie" }} · ${states.length()} entités · $indisponibles indisponible(s) · $inconnus état(s) inconnu(s)."
        }.getOrElse { "API Home Assistant : échec — ${it.message?.take(220) ?: it.javaClass.simpleName}." }
    }
}
}PLEX_SESS" | sed 's/></>\\n</g' | grep -E '<Video |<Player |<TranscodeSession ' | sed -E 's/(key|ratingKey|sessionKey|machineIdentifier|clientIdentifier)="[^"]+"/\\1="[masqué]"/g' | head -18
        """.trimIndent() else "echo 'Jeton Plex absent : identité seulement, bibliothèques et sessions non interrogées.'"
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
            echo 'Pare-feu UFW :'
            (${rootCmd}ufw status 2>/dev/null || echo 'UFW absent ou droits insuffisants') | head -20
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
            var indisponibles = 0
            var inconnus = 0
            for (i in 0 until states.length()) when (states.optJSONObject(i)?.optString("state")) {
                "unavailable" -> indisponibles++
                "unknown" -> inconnus++
            }
            val config = runCatching { org.json.JSONObject(Serveur.ha(app, "GET", "/api/config")) }.getOrNull()
            "API Home Assistant : OK · version ${config?.optString("version").orEmpty().ifBlank { "non fournie" }} · ${states.length()} entités · $indisponibles indisponible(s) · $inconnus état(s) inconnu(s)."
        }.getOrElse { "API Home Assistant : échec — ${it.message?.take(220) ?: it.javaClass.simpleName}." }
    }
}
