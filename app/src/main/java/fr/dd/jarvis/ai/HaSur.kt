package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.delay

/**
 * Opérations Home Assistant via l'API configurée (DuckDNS/HTTPS).
 *
 * Le profil SSH général désigne l'hôte Debian, qui peut héberger les conteneurs Home Assistant et Plex.
 * L'API Home Assistant reste le plan de contrôle des entités et des services ; SSH Debian sert uniquement
 * à inspecter l'hôte et le conteneur HA en lecture seule (état, volumes, ressources, journaux).
 * Les opérations de cycle de vie Docker et les modifications YAML restent suspendues tant qu'un flux
 * dédié, confirmé et vérifié n'est pas disponible.
 */
object HaSur {
    private fun q(s: String) = Serveur.q(s)

    /** Les opérations Docker sur HA sont désactivées ici ; seul ServeurDiagnostic inspecte le conteneur en lecture seule. */
    fun docker(app: JarvisApp, cmd: String, delai: Int = 90): Pair<Int, String> =
        -1 to "Opérations Docker Home Assistant désactivées : le SSH Debian est réservé au diagnostic en lecture seule."

    fun dockerComplet(app: JarvisApp, cmd: String, delai: Int = 240): Pair<Int, String> =
        docker(app, cmd, delai)

    data class Conteneur(val nom: String, val config: String, val dossierCompose: String, val serviceCompose: String, val image: String)

    /** Conteneur Home Assistant, son dossier de configuration et, s'il vient de docker compose, son projet. */
    fun conteneur(app: JarvisApp, frais: Boolean = false): Conteneur? = null

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
        if (!Serveur.haConfigure(app)) return "Validation impossible : configure l'URL DuckDNS/API et le jeton Home Assistant. Aucun accès SSH/Docker de secours n'est autorisé."
        return try {
            val o = org.json.JSONObject(Serveur.ha(app, "POST", "/api/config/core/check_config"))
            when (o.optString("result")) {
                "valid" -> null
                "invalid" -> o.optString("errors").ifBlank { o.toString() }.take(1200)
                else -> "Home Assistant n'a pas confirmé la validité de sa configuration : $o"
            }
        } catch (e: Exception) {
            "Validation Home Assistant impossible via l'API (${e.message?.take(180)}). Aucun repli vers le SSH Debian n'a été tenté."
        }
    }

    // ------------------------------------------------------------------ attente du retour en ligne

    /** Code HTTP de Home Assistant vu depuis le serveur (401/200 = il répond). */
    private fun repond(app: JarvisApp): Boolean =
        Serveur.haConfigure(app) && runCatching { Serveur.ha(app, "GET", "/api/") }.isSuccess

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
            val sudo = app.store.sshUser.value.trim() != "root"
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
        if (!parApi) return "❌ Redémarrage non effectué : l'API Home Assistant n'a pas confirmé la commande. Aucun repli Docker/SSH vers le serveur Debian n'est autorisé."
        return "🔁 Redémarrage de Home Assistant" + (if (parApi) " (arrêt propre par Home Assistant lui-même)" else " (arrêt propre du conteneur)") + "…\n" + attendre(app).second
    }

    /** Démarrage du conteneur arrêté, puis attente. */
    suspend fun demarrer(app: JarvisApp): String =
        "⛔ Démarrage Docker désactivé : configure d'abord un profil SSH Home Assistant indépendant du serveur Debian. Les opérations via l'API restent disponibles."

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
        val c = conteneur(app, frais = true) ?: return "❌ Mise à jour Docker désactivée : aucun profil SSH Home Assistant dédié n'est configuré. Aucune commande ne sera envoyée au serveur Debian."
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
