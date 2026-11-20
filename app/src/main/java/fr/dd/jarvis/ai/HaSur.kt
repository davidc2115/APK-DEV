package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.delay

/**
 * Home Assistant = DEUX ACCÈS SÉPARÉS, jamais confondus :
 *  - l'INSTANCE Home Assistant, par son API (adresse + jeton longue durée des Réglages) : entités, services, validation
 *    et rechargement de la configuration, redémarrage propre ;
 *  - l'HÔTE DEBIAN, par SSH, où tourne le CONTENEUR Docker de Home Assistant : fichiers de configuration (dossier
 *    /config de ce conteneur), démarrage / arrêt du conteneur quand l'API ne répond plus, mise à jour de l'image,
 *    sauvegardes. Rien d'autre que ce conteneur n'est visé.
 *
 * Opérations SÛRES sur Home Assistant installé en Docker sur le serveur (2 Go de mémoire : chaque redémarrage est long).
 *
 * Avant, une modification ou un redémarrage par SSH laissait souvent Home Assistant indisponible :
 *  - un YAML modifié par ssh_fichier n'était jamais vérifié → Home Assistant redémarrait en « mode de récupération »
 *    (intégrations non chargées, entités indisponibles) ;
 *  - « docker restart » coupait Home Assistant au bout de 10 s (base de l'historique endommagée, démarrage encore plus long) ;
 *  - rien n'attendait son retour : on annonçait « redémarré » alors qu'il démarrait encore (ou ne revenait jamais) ;
 *  - la mise à jour tirait la nouvelle image sans place disque ni sauvegarde ni retour arrière.
 * Ici : vérification de la configuration avant tout redémarrage, remise automatique de l'ancien fichier si Home Assistant
 * le refuse, rechargement à chaud quand c'est possible (pas de coupure), arrêt propre (2 min accordées), attente du retour
 * en ligne, et mise à jour en tâche de fond avec sauvegarde, contrôle de la place et retour à l'ancienne version si besoin.
 */
object HaSur {
    private fun q(s: String) = Serveur.q(s)

    /** Commande docker avec sudo si l'utilisateur SSH n'a pas accès au démon. */
    fun docker(app: JarvisApp, cmd: String, delai: Int = 90): Pair<Int, String> {
        val r = Serveur.exec(app, cmd, delai)
        if (r.first != 0 && Regex("permission denied|docker\\.sock|connect to the docker daemon", RegexOption.IGNORE_CASE).containsMatchIn(r.second))
            return Serveur.exec(app, "sudo $cmd", delai)
        return r
    }

    /** Commande docker qui doit aller jusqu'au bout (détachée, jamais interrompue), avec sudo si besoin. */
    fun dockerComplet(app: JarvisApp, cmd: String, delai: Int = 240): Pair<Int, String> {
        val r = Serveur.execComplet(app, cmd, delai)
        if (r.first > 0 && Regex("permission denied|docker\\.sock|connect to the docker daemon", RegexOption.IGNORE_CASE).containsMatchIn(r.second))
            return Serveur.execComplet(app, "sudo $cmd", delai)
        return r
    }

    data class Conteneur(val nom: String, val config: String, val dossierCompose: String, val serviceCompose: String, val image: String)

    @Volatile private var cache: Pair<Long, Conteneur?>? = null

    /** Conteneur Home Assistant, son dossier de configuration et, s'il vient de docker compose, son projet. */
    fun conteneur(app: JarvisApp, frais: Boolean = false): Conteneur? {
        cache?.takeIf { !frais && System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        if (!Serveur.sshConfigure(app)) return null
        val l = docker(app, "docker ps -a --format '{{.Names}}|{{.Image}}'", 30).second.lines().mapNotNull { it.split('|').takeIf { p -> p.size == 2 } }
        val nom = (l.firstOrNull { it[1].contains(Regex("home-?assistant", RegexOption.IGNORE_CASE)) }
            ?: l.firstOrNull { it[0].contains(Regex("home.?assistant|^hass$|^ha$", RegexOption.IGNORE_CASE)) })?.get(0)?.trim()
        val c = nom?.let { n ->
            val i = docker(app, "docker inspect -f '{{range .Mounts}}{{if eq .Destination \"/config\"}}{{.Source}}{{end}}{{end}}|{{index .Config.Labels \"com.docker.compose.project.working_dir\"}}|{{index .Config.Labels \"com.docker.compose.service\"}}|{{.Config.Image}}' ${q(n)}", 30).second.trim().split('|')
            Conteneur(n, i.getOrElse(0) { "" }, i.getOrElse(1) { "" }.removePrefix("<no value>"), i.getOrElse(2) { "" }.removePrefix("<no value>"), i.getOrElse(3) { "" })
        }
        cache = System.currentTimeMillis() to c
        return c
    }

    /** Le fichier [chemin] (sur le serveur, ou dans [conteneurFichier]) fait-il partie de la configuration de Home Assistant ? */
    fun estConfigHa(app: JarvisApp, chemin: String, conteneurFichier: String): Boolean {
        if (!Regex("\\.ya?ml$", RegexOption.IGNORE_CASE).containsMatchIn(chemin)) return false
        val c = runCatching { conteneur(app) }.getOrNull() ?: return false
        if (conteneurFichier.isNotBlank()) return conteneurFichier == c.nom && (chemin.startsWith("/config/") || !chemin.startsWith("/"))
        val abs = runCatching { Serveur.absolu(app, chemin) }.getOrDefault(chemin)
        val dossier = c.config.ifBlank { app.store.haDir.value.trim() }.trimEnd('/')
        return dossier.isNotBlank() && abs.startsWith("$dossier/")
    }

    // ------------------------------------------------------------------ vérification

    /** null si la configuration est valide ; sinon le message d'erreur de Home Assistant. */
    fun verifier(app: JarvisApp): String? {
        if (Serveur.haConfigure(app)) runCatching {
            val o = org.json.JSONObject(Serveur.ha(app, "POST", "/api/config/core/check_config"))
            if (o.optString("result") == "valid") return null
            if (o.optString("result") == "invalid") return o.optString("errors").ifBlank { o.toString() }.take(1200)
        }
        val c = runCatching { conteneur(app) }.getOrNull() ?: return null.also { }
        // Home Assistant ne répond pas : vérification hors ligne, dans le conteneur (code de sortie = nombre d'erreurs).
        val r = docker(app, "docker exec ${q(c.nom)} python3 -m homeassistant --script check_config -c /config > /tmp/jarvis_ha_check.txt 2>&1; rc=\$?; echo \"JARVIS_RC=\$rc\"; tail -40 /tmp/jarvis_ha_check.txt; rm -f /tmp/jarvis_ha_check.txt; exit \$rc", 240)
        val rc = Regex("JARVIS_RC=(\\d+)").find(r.second)?.groupValues?.get(1)?.toIntOrNull()
        val sortie = r.second.replace(Regex("JARVIS_RC=\\d+"), "").trim()
        return when {
            rc == 0 -> null
            rc == null && !Regex("Failed config|Invalid config|ERROR", RegexOption.IGNORE_CASE).containsMatchIn(sortie) -> null
            else -> sortie.lines().filter { it.isNotBlank() && !it.startsWith("Testing configuration") }.takeLast(20).joinToString("\n").take(1200)
        }
    }

    // ------------------------------------------------------------------ attente du retour en ligne

    /** Code HTTP de Home Assistant vu depuis le serveur (401/200 = il répond). */
    private fun codeLocal(app: JarvisApp): Int = Serveur.exec(app, "curl -s -o /dev/null -m 4 -w '%{http_code}' http://127.0.0.1:8123/api/ 2>/dev/null || echo 000", 15).second.trim().takeLast(3).toIntOrNull() ?: 0

    private fun repond(app: JarvisApp): Boolean =
        (Serveur.haConfigure(app) && runCatching { Serveur.ha(app, "GET", "/api/") }.isSuccess) ||
            (Serveur.sshConfigure(app) && runCatching { codeLocal(app) in setOf(200, 401) }.getOrDefault(false))

    /** Attend que Home Assistant réponde (jusqu'à [maxS] s) ; null si revenu (avec la durée), sinon le diagnostic. */
    suspend fun attendre(app: JarvisApp, maxS: Int = 480): Pair<Boolean, String> {
        val debut = System.currentTimeMillis()
        delay(15_000)
        while (System.currentTimeMillis() - debut < maxS * 1000L) {
            if (repond(app)) return true to "✅ Home Assistant est de nouveau en ligne (au bout de ${(System.currentTimeMillis() - debut) / 1000} s)."
            delay(6_000)
        }
        return false to "⚠️ Home Assistant ne répond toujours pas après ${maxS / 60} min.\n" + diagnostic(app)
    }

    /**
     * Diagnostic croisé : l'API décrit l'instance HA ; le SSH Debian inspecte uniquement son conteneur,
     * ses ressources, volumes et journaux. Aucun fichier ni service n'est modifié.
     */
    fun diagnostic(app: JarvisApp): String {
        val api = ServeurDiagnostic.resumeHomeAssistant(app)
        val hote = if (!Serveur.sshConfigure(app)) {
            "Inspection du conteneur non disponible : connexion SSH Debian non configurée."
        } else runCatching {
            val sudo = app.store.sshUser.value.trim() != "root" && app.store.sshPass.value.isNotBlank()
            val r = Serveur.exec(app, ServeurDiagnostic.commande("home assistant", "", 80, sudo), 90)
            if (r.first == 0) r.second.trim().take(6500)
            else "Inspection du conteneur via SSH Debian impossible (code ${r.first}) : ${r.second.take(500)}"
        }.getOrElse { "Inspection du conteneur via SSH Debian impossible : ${it.message?.take(180)}" }
        return "## API Home Assistant\n$api\n\n## Hôte Debian — conteneur Home Assistant (lecture seule)\n```\n$hote\n```"
    }

    // ------------------------------------------------------------------ rechargement et redémarrage

    /** Recharge à chaud tout le YAML rechargeable (automatisations, scripts, scènes, modèles…) : aucune coupure. */
    fun recharger(app: JarvisApp): String = runCatching {
        Serveur.ha(app, "POST", "/api/services/homeassistant/reload_all"); "🔄 Configuration rechargée à chaud (sans redémarrage)."
    }.getOrElse { "ℹ️ Rechargement à chaud impossible (${it.message?.take(80)}) : un redémarrage appliquera le changement." }

    /**
     * Redémarrage sûr : configuration vérifiée (sinon rien n'est redémarré), arrêt propre, attente du retour en ligne,
     * diagnostic s'il ne revient pas.
     */
    suspend fun redemarrer(app: JarvisApp, verifierAvant: Boolean = true): String {
        if (verifierAvant) verifier(app)?.let { return "⛔ Redémarrage annulé : Home Assistant refuserait sa configuration (il démarrerait en mode de récupération, entités indisponibles).\n```\n$it\n```\nCorrige le fichier en cause (ou dis « restaure le dernier fichier modifié »)." }
        val parApi = Serveur.haConfigure(app) && runCatching { Serveur.ha(app, "POST", "/api/services/homeassistant/restart") }.isSuccess
        if (!parApi) {
            val c = conteneur(app) ?: return "❌ Ni l'API de Home Assistant ni son conteneur Docker ne sont joignables."
            // Arrêt propre : 2 min accordées (10 s par défaut → base de l'historique endommagée, redémarrage encore plus long).
            val r = dockerComplet(app, "docker restart -t 120 ${q(c.nom)}", 240)
            if (r.first > 0) return "❌ Redémarrage refusé : ${r.second.take(300)}"
        }
        return "🔁 Redémarrage de Home Assistant" + (if (parApi) " (arrêt propre par Home Assistant lui-même)" else " (arrêt propre du conteneur)") + "…\n" + attendre(app).second
    }

    /** Démarrage du conteneur arrêté, puis attente. */
    suspend fun demarrer(app: JarvisApp): String {
        val c = conteneur(app, frais = true) ?: return "❌ Conteneur Home Assistant introuvable."
        verifier(app)?.let { return "⛔ Démarrage annulé : configuration refusée par Home Assistant.\n```\n$it\n```" }
        val r = dockerComplet(app, "docker start ${q(c.nom)}", 120)
        if (r.first > 0) return "❌ ${r.second.take(300)}"
        return "▶️ ${c.nom} démarré…\n" + attendre(app).second
    }

    // ------------------------------------------------------------------ mise à jour

    /** Script de mise à jour (tâche de fond sur le serveur) : place, sauvegarde, image précédente gardée, retour arrière. */
    internal fun scriptMiseAJour(c: Conteneur): String = """
set -u -o pipefail
C=${q(c.nom)}; DIR=${q(c.dossierCompose)}; SVC=${q(c.serviceCompose)}; CFG=${q(c.config)}
log(){ echo "${'$'}(date +%H:%M:%S) ${'$'}*"; }
compose(){ cd "${'$'}DIR" && { docker compose "${'$'}@" 2>&1 || docker-compose "${'$'}@" 2>&1; }; }
en_ligne(){ for i in ${'$'}(seq 1 ${'$'}1); do c=${'$'}(curl -s -o /dev/null -m 4 -w '%{http_code}' http://127.0.0.1:8123/api/); { [ "${'$'}c" = 401 ] || [ "${'$'}c" = 200 ]; } && { log "✅ Home Assistant répond (après ${'$'}((i*5)) s)"; return 0; }; sleep 5; done; return 1; }
IMG=${'$'}(docker inspect -f '{{.Config.Image}}' "${'$'}C"); AVANT=${'$'}(docker inspect -f '{{.Image}}' "${'$'}C")
LIBRE=${'$'}(df -Pm /var/lib/docker 2>/dev/null | awk 'NR==2{print ${'$'}4}'); [ -n "${'$'}LIBRE" ] || LIBRE=${'$'}(df -Pm / | awk 'NR==2{print ${'$'}4}')
log "Image : ${'$'}IMG · place libre : ${'$'}LIBRE Mo"
[ "${'$'}LIBRE" -lt 2500 ] && { log "❌ Pas assez de place (il faut ~2,5 Go libres) : mise à jour annulée, rien n'a changé."; exit 2; }
if [ -n "${'$'}CFG" ] && [ -d "${'$'}CFG" ]; then
  SAUV="${'$'}(dirname "${'$'}CFG")/ha-avant-maj-${'$'}(date +%Y%m%d-%H%M).tar.gz"
  log "💾 Sauvegarde de la configuration → ${'$'}SAUV"
  tar czf "${'$'}SAUV" -C "${'$'}CFG" --exclude='home-assistant_v2.db*' --exclude='backups' --exclude='*.log*' . || log "⚠️ Sauvegarde incomplète"
  ls -1t "${'$'}(dirname "${'$'}CFG")"/ha-avant-maj-*.tar.gz 2>/dev/null | tail -n +4 | xargs -r rm -f
fi
docker tag "${'$'}AVANT" jarvis/ha-precedente:latest
log "⬇️ Téléchargement de la nouvelle version (peut être long)"
compose pull "${'$'}SVC" | tail -5 || { log "❌ Téléchargement impossible : rien n'a changé."; exit 3; }
APRES=${'$'}(docker image inspect -f '{{.Id}}' "${'$'}IMG")
[ "${'$'}APRES" = "${'$'}AVANT" ] && { log "✅ Home Assistant est déjà à jour."; exit 0; }
log "🔁 Arrêt propre puis démarrage de la nouvelle version"
docker stop -t 120 "${'$'}C" >/dev/null 2>&1
compose up -d "${'$'}SVC" | tail -5
log "⏳ Attente du démarrage (jusqu'à 12 min ; la première fois après une mise à jour est plus longue)"
if en_ligne 144; then docker image prune -f >/dev/null 2>&1; log "🎉 Mise à jour terminée."; exit 0; fi
log "⚠️ La nouvelle version ne démarre pas. Dernières erreurs :"
docker logs --tail 200 "${'$'}C" 2>&1 | grep -iE 'error|exception|failed' | tail -15
log "↩️ Retour à la version précédente"
docker stop -t 60 "${'$'}C" >/dev/null 2>&1
docker tag jarvis/ha-precedente:latest "${'$'}IMG"
compose up -d "${'$'}SVC" | tail -5
en_ligne 96 && { log "✅ Ancienne version remise en service."; exit 4; }
log "❌ Home Assistant ne répond pas non plus avec l'ancienne version : sauvegarde de la configuration dans ${'$'}{SAUV:-(aucune)}"
exit 5
""".trimIndent()

    /** Lance la mise à jour sûre en tâche de fond ; renvoie l'identifiant de tâche ou un message. */
    fun mettreAJour(app: JarvisApp, lancer: (String, String, Boolean) -> String): String {
        val c = conteneur(app, frais = true) ?: return "❌ Conteneur Home Assistant introuvable."
        if (c.dossierCompose.isBlank() || c.serviceCompose.isBlank())
            return "« ${c.nom} » n'a pas été créé avec docker compose : sa mise à jour demande de le recréer avec les mêmes réglages. Dis « recrée Home Assistant avec docker compose » et je préparerai le fichier compose à partir de sa configuration actuelle."
        val id = lancer(scriptMiseAJour(c), "Mise à jour de Home Assistant", true)
        return "▶️ Mise à jour de Home Assistant lancée en arrière-plan (tâche **$id**) : place disque vérifiée, configuration sauvegardée, ancienne version gardée — " +
            "si la nouvelle ne démarre pas en 12 min, retour automatique à l'ancienne. Demande « où en est la mise à jour de Home Assistant ? » pour suivre."
    }

    /** Commande SSH qui redémarre / arrête / met à jour Home Assistant « à la main » : on l'intercepte. */
    internal fun interception(cmd: String, nomConteneur: String?): String? {
        val c = cmd.trim()
        val ha = "(?:${Regex.escape(nomConteneur ?: "homeassistant")}|home-?assistant|hass)"
        return when {
            Regex("^(sudo\\s+)?docker\\s+restart\\s+(-t\\s+\\d+\\s+)?$ha\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(c) -> "redemarrer"
            Regex("^(sudo\\s+)?docker(-|\\s+)compose\\b.*\\brestart\\b.*$ha", RegexOption.IGNORE_CASE).containsMatchIn(c) -> "redemarrer"
            Regex("^(sudo\\s+)?ha\\s+core\\s+restart", RegexOption.IGNORE_CASE).containsMatchIn(c) -> "redemarrer"
            Regex("^(sudo\\s+)?docker(-|\\s+)compose\\b.*\\bpull\\b", RegexOption.IGNORE_CASE).containsMatchIn(c) && Regex(ha, RegexOption.IGNORE_CASE).containsMatchIn(c) -> "mettre_a_jour"
            Regex("^(sudo\\s+)?docker\\s+pull\\s+\\S*home-?assistant", RegexOption.IGNORE_CASE).containsMatchIn(c) -> "mettre_a_jour"
            else -> null
        }
    }
}
