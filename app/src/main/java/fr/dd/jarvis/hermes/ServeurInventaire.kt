package fr.dd.jarvis.hermes

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Serveur
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fiche « infrastructure » donnée à Hermès quand la demande touche le serveur Debian : ce que les Réglages de Jarvis
 * savent (sans aucun secret) + la liste réelle des services qui tournent sur le serveur (Docker, systemd, pm2, processus),
 * relevée en SSH et gardée en cache. Sans elle, Hermès devinait : il confondait le bot Discord, Home Assistant et Plex,
 * et tentait des connexions SSH depuis son propre terminal, sans identifiants.
 */
object ServeurInventaire {
    private val R_SERVEUR = Regex(
        "serveur|\\bssh\\b|debian|docker|conteneurs?|containers?|systemctl|\\bservices?\\b|\\bbot\\b|plex|ollama|discord|" +
            "home ?assistant|\\bha\\b|domotique|\\blogs?\\b|journaux|red[ée]marr|\\bapt\\b|nginx|portainer|\\bpm2\\b|crontab|" +
            "\\bdisque\\b|\\bcpu\\b|\\bram\\b|m[ée]moire vive|tourne|plant[ée]|en ligne|hors ligne",
        RegexOption.IGNORE_CASE
    )
    private const val VALIDITE_MS = 6 * 3_600_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var enCours: Deferred<String?>? = null

    fun concerne(demande: String) = demande.isNotBlank() && R_SERVEUR.containsMatchIn(demande)

    /** Bloc à ajouter au prompt d'Hermès (vide si la demande ne concerne pas le serveur ou si rien n'est configuré). */
    suspend fun bloc(app: JarvisApp, demande: String): String {
        if (!concerne(demande)) return ""
        val reglages = reglages(app)
        if (reglages.isBlank()) return ""
        val s = app.store
        // Le relevé appartient au serveur pour lequel il a été fait : un autre serveur dans les Réglages l'invalide.
        var releve = s.serveurServices.value.takeIf { it.startsWith(cleServeur(app) + "\n") }?.substringAfter('\n').orEmpty()
        val age = System.currentTimeMillis() - (s.serveurServicesLe.value.toLongOrNull() ?: 0L)
        if (Serveur.sshConfigure(app)) {
            // Premier relevé : on l'attend un peu (sinon Hermès devinerait) ; ensuite, mise à jour en arrière-plan.
            if (releve.isBlank()) releve = withTimeoutOrNull(12_000) { rafraichir(app).await() }.orEmpty()
            else if (age > VALIDITE_MS) rafraichir(app)
        }
        val date = s.serveurServicesLe.value.toLongOrNull()?.takeIf { it > 0 }?.let {
            SimpleDateFormat("d/MM HH:mm", Locale.FRENCH).format(Date(it))
        }
        return buildString {
            append("\n\nINFRASTRUCTURE DE L'UTILISATEUR — d'après les Réglages de Jarvis (source de vérité ; les identifiants sont enregistrés dans Jarvis et utilisés par ses outils) :\n")
            append(reglages)
            if (releve.isNotBlank()) {
                append("\nSERVICES RÉELLEMENT PRÉSENTS SUR LE SERVEUR DEBIAN")
                if (date != null) append(" (relevé SSH du $date)")
                append(" :\n").append(releve.trim().take(3_500)).append('\n')
            } else if (Serveur.sshConfigure(app)) {
                append("\n(Relevé des services pas encore disponible : si la demande porte sur un service précis, commence par mcp_jarvis_ssh_commande « docker ps -a ; systemctl list-units --type=service --state=running --no-pager ».)\n")
            }
            append(
                "RÈGLES SERVEUR : chaque service est une chose distincte — Home Assistant ≠ Plex ≠ bot Discord ≠ Ollama ; ne les confonds jamais. " +
                    "Identifie un service par son nom EXACT dans le relevé (conteneur Docker, unité systemd, processus pm2/node/python) et cite ce nom. " +
                    "Le bot Discord a deux facettes : son compte sur Discord (outils mcp_jarvis_discord_* : salons, rôles, messages) et son programme sur le serveur (logs, redémarrage, code : SSH). " +
                    "Avant de redémarrer, arrêter ou modifier un service, vérifie qu'il existe sous ce nom (docker ps -a / systemctl status) et annonce lequel tu touches. " +
                    "Toute commande sur le serveur passe par mcp_jarvis_ssh_commande (ou ssh_fichier / ssh_script) — jamais par ssh dans ton terminal. " +
                    "Si un service attendu n'apparaît pas dans le relevé, vérifie avec ssh_commande au lieu de supposer."
            )
        }
    }

    /** Ce que Jarvis sait de l'infrastructure, sans mot de passe, clé ni jeton. */
    fun reglages(app: JarvisApp): String {
        val s = app.store
        val lignes = mutableListOf<String>()
        if (s.sshHost.value.isNotBlank()) {
            val auth = when {
                s.sshKey.value.isNotBlank() -> "clé SSH enregistrée"
                s.sshPass.value.isNotBlank() -> "mot de passe enregistré"
                else -> "AUCUN identifiant enregistré (à compléter dans Réglages › Serveur)"
            }
            val distant = s.sshHostDistant.value.trim().takeIf { it.isNotBlank() }
                ?.let { " ; accès hors de la maison : $it:${s.sshPortDistant.value.ifBlank { s.sshPort.value.ifBlank { "22" } }}" }.orEmpty()
            lignes += "- Serveur Debian : ${s.sshUser.value.ifBlank { "?" }}@${s.sshHost.value.trim()}:${s.sshPort.value.ifBlank { "22" }} ($auth$distant) → outils mcp_jarvis_ssh_commande / ssh_fichier / ssh_script / serveur_diagnostic / serveur_scan_ports."
        }
        if (s.haUrl.value.isNotBlank()) {
            val dossier = s.haDir.value.trim().takeIf { it.isNotBlank() }?.let { " ; configuration YAML sur le serveur : $it" }.orEmpty()
            val jeton = if (s.haToken.value.isNotBlank()) "jeton API enregistré" else "AUCUN jeton API enregistré"
            lignes += "- Home Assistant : ${s.haUrl.value.trim()} ($jeton$dossier) → outils mcp_jarvis_ha_entites / ha_etat / ha_service / ha_config / ha_dashboard / ha_admin (API d'abord ; SSH seulement pour le conteneur, les logs ou si l'API ne répond pas)."
        }
        val plex = runCatching { s.plexToken.value.isNotBlank() }.getOrDefault(false)
        if (plex || s.sshHost.value.isNotBlank()) lignes += "- Plex Media Server : sur le serveur Debian, port 32400, joint par le tunnel SSH de Jarvis (" +
            (if (plex) "jeton Plex enregistré" else "jeton Plex NON enregistré") + ") → outils mcp_jarvis_plex_rechercher / plex_appareils / plex_lancer ; administration du service (logs, redémarrage, bibliothèques sur disque) par SSH."
        if (s.discordToken.value.isNotBlank()) lignes += "- Bot Discord : jeton du bot enregistré dans Jarvis" +
            (s.discordGuildId.value.trim().takeIf { it.isNotBlank() }?.let { ", serveur Discord $it" }.orEmpty()) +
            " → outils mcp_jarvis_discord_* (API Discord). Son programme, s'il tourne sur le serveur Debian, se gère par SSH."
        if (lignes.isNotEmpty()) lignes += "- IA : uniquement le routeur IA de Jarvis (Gemini, Groq…). L'Ollama du serveur n'est PAS utilisé : ne l'appelle jamais comme modèle ou sous-agent."
        return lignes.joinToString("\n")
    }

    /** Relevé SSH des services (mutualisé : un seul relevé à la fois). */
    @Synchronized fun rafraichir(app: JarvisApp): Deferred<String?> {
        enCours?.takeIf { it.isActive }?.let { return it }
        return scope.async {
            val (code, sortie) = runCatching { Serveur.exec(app, COMMANDE, timeoutS = 40) }.getOrElse { return@async null }
            val texte = nettoyer(sortie)
            if (code == 0 || texte.isNotBlank()) {
                app.store.serveurServices.set(cleServeur(app) + "\n" + texte)
                app.store.serveurServicesLe.set(System.currentTimeMillis().toString())
            }
            texte.takeIf { it.isNotBlank() }
        }.also { enCours = it }
    }

    private fun cleServeur(app: JarvisApp) = "@" + app.store.sshUser.value.trim() + "@" + app.store.sshHost.value.trim()

    internal fun nettoyer(sortie: String): String = sortie.lines()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        // Une section sans contenu n'apporte rien à Hermès.
        .let { l -> l.filterIndexed { i, x -> !(x.startsWith("## ") && (i == l.lastIndex || l[i + 1].startsWith("## "))) } }
        .joinToString("\n") { it.take(200) }
        .take(4_000)

    // « § » remplace « $ » (évite les gabarits Kotlin). PATH élargi : pm2/node installés via npm global ou nvm.
    private val COMMANDE = """
export PATH="§PATH:/usr/local/bin:/usr/sbin:/sbin:§HOME/.local/bin:§HOME/.npm-global/bin"
for d in §HOME/.nvm/versions/node/*/bin; do [ -d "§d" ] && PATH="§PATH:§d"; done
echo '## Docker (nom | image | état | ports)'
F='{{.Names}} | {{.Image}} | {{.Status}} | {{.Ports}}'
{ docker ps -a --format "§F" 2>/dev/null || sudo -n docker ps -a --format "§F" 2>/dev/null; } | head -40
echo '## Services systemd actifs (hors système)'
systemctl list-units --type=service --state=running --no-legend --plain --no-pager 2>/dev/null | awk '{print §1}' | grep -vE '^(systemd-|dbus|getty|serial-getty|user@|cron|rsyslog|ssh|polkit|udisks|accounts-daemon|networking|NetworkManager|ModemManager|wpa_supplicant|unattended|irqbalance|haveged|chrony|ntp|avahi|bluetooth|rtkit|packagekit|upower|colord|cups|snapd|containerd|thermald|lvm2|blk|multipathd|fwupd|kmod|apparmor|console|keyboard|plymouth|smartd|atd|anacron|exim4|postfix|qemu-guest|open-vm|vgauth|power-profiles|switcheroo|systemd)' | head -40
echo '## pm2'
pm2 jlist 2>/dev/null | python3 -c 'import json,sys
for p in json.load(sys.stdin):
    e=p.get("pm2_env",{}); print(p.get("name"),"|",e.get("status"),"|",e.get("pm_cwd",""))' 2>/dev/null | head -20
echo '## Programmes node / python / java lancés à la main'
ps -eo user=,args= 2>/dev/null | grep -E '(^| )(/[^ ]*/)?(node|nodejs|python3?|java|deno|bun) ' | grep -vE 'unattended|networkd-dispatcher|grep -E|pm2|dockerd|containerd|/usr/share/' | cut -c1-180 | head -20
echo '## Ports en écoute'
{ ss -ltnpH 2>/dev/null || sudo -n ss -ltnpH 2>/dev/null; } | awk '{print §4, §6}' | sort -u | head -40
""".trimIndent().replace('§', '$')
}
