package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Outils « serveur » : Home Assistant (appareils, configuration YAML) et SSH (commandes, fichiers). Proposés seulement si la conversation les concerne. */
class ServeurTools(private val app: JarvisApp, private val confirm: suspend (String, Boolean) -> Boolean) {

    companion object {
        val KEYWORDS = Regex("home ?assistant|domotique|\\bha\\b|ssh|serveur|server|yaml|docker|lumi[èe]re|volet|thermostat|automatisation|automation|sc[èe]ne\\b|configuration\\.yaml|debian|raspberry|esphome|zigbee|mqtt|capteur|\\bprise\\b|interrupteur", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = KEYWORDS.containsMatchIn(t)
        val NAMES = setOf("ha_entites", "ha_etat", "ha_service", "ha_config", "ssh_commande", "ssh_fichier", "serveur_diagnostic")
    }

    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun tool(name: String, desc: String, props: Map<String, JSONObject>, req: List<String> = emptyList()): JSONObject {
        val p = JSONObject(); props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(req))))
    }

    val schema: List<JSONObject> = listOf(
        tool("ha_entites", "Home Assistant : liste/recherche les appareils et capteurs (lumières, volets, températures…) avec leur état.",
            mapOf("recherche" to prop("Mot du nom ou de l'identifiant (optionnel)."), "domaine" to prop("light, switch, sensor, climate, cover, automation… (optionnel)."))),
        tool("ha_etat", "Home Assistant : état détaillé d'une entité.", mapOf("entity_id" to prop("ex. light.salon")), listOf("entity_id")),
        tool("ha_service", "Home Assistant : commande un appareil ou lance un service (allumer, éteindre, ouvrir un volet, régler un thermostat, déclencher une automatisation…).",
            mapOf("domaine" to prop("ex. light, switch, climate, cover, automation, scene, script, homeassistant."), "service" to prop("ex. turn_on, turn_off, toggle, set_temperature, trigger, reload_all."),
                "entity_id" to prop("Entité(s), séparées par des virgules (optionnel)."), "donnees" to prop("Paramètres JSON (optionnel), ex. {\"brightness_pct\":50}.")), listOf("domaine", "service")),
        tool("ha_config", "Home Assistant : configuration complète sur le serveur (fichiers YAML dans le dossier de config : configuration.yaml, automations.yaml, scripts.yaml, scenes.yaml, secrets.yaml, packages/…). Actions : lister | lire | ecrire (sauvegarde auto + validation, restauration si invalide) | sauvegardes | restaurer | valider | recharger | redemarrer | journal (erreurs). Toujours lire le fichier avant de le modifier et réécrire le fichier ENTIER.",
            mapOf("action" to prop("lister | lire | ecrire | sauvegardes | restaurer | valider | recharger | redemarrer | journal"), "fichier" to prop("Chemin relatif au dossier de config, ex. automations.yaml (ou dossier pour lister)."),
                "contenu" to prop("ecrire : contenu complet du fichier. restaurer : nom de la sauvegarde (.jarvis-bak-…)."), "quoi" to prop("recharger : all | automation | script | scene | core (défaut all).")), listOf("action")),
        tool("ssh_commande", "Serveur SSH : exécute une commande shell sur le serveur (Debian) et retourne la sortie. Contrôle complet : docker, systemctl, apt, logs, réseau… Les commandes destructrices demandent une confirmation.",
            mapOf("commande" to prop("Commande shell."), "delai" to prop("Délai maximal en secondes (défaut 60).")), listOf("commande")),
        tool("ssh_fichier", "Serveur SSH : lit, écrit (sauvegarde auto) ou liste des fichiers du serveur.",
            mapOf("action" to prop("lire | ecrire | lister"), "chemin" to prop("Chemin absolu sur le serveur."), "contenu" to prop("ecrire : contenu complet.")), listOf("action", "chemin")),
        tool("serveur_diagnostic", "Diagnostic rapide du serveur : charge, mémoire, disque, conteneurs Docker et derniers logs de Home Assistant.", emptyMap())
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
            "ha_entites" -> ToolResult(Serveur.haEntites(app, a.optString("recherche"), a.optString("domaine")), direct = true)
            "ha_etat" -> ToolResult(Serveur.haEtat(app, a.optString("entity_id").trim()))
            "ha_service" -> {
                val d = a.optString("domaine").trim(); val s = a.optString("service").trim(); val e = a.optString("entity_id").trim()
                val sensible = (d in setOf("lock", "alarm_control_panel") || s in setOf("restart", "stop", "unlock", "alarm_disarm", "open_cover") && d != "light") || (d == "homeassistant" && s in setOf("restart", "stop"))
                if (sensible && !confirm("Home Assistant : exécuter $d.$s ${e}?", true)) return ToolResult("Refusé par l'utilisateur.")
                val data = a.optString("donnees").trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
                ToolResult(Serveur.haService(app, d, s, e, data))
            }
            "ssh_commande" -> {
                ssh()
                val cmd = a.optString("commande").trim()
                if (cmd.isBlank()) return ToolResult("Commande vide.")
                if (Serveur.dangereuse(cmd) && !confirm("Exécuter sur le serveur ${app.store.sshHost.value} :\n\n$cmd", true)) return ToolResult("Refusé par l'utilisateur.")
                ToolResult("```\n" + sortie(Serveur.exec(app, cmd, (a.optString("delai").toIntOrNull() ?: 60).coerceIn(5, 600))) + "\n```")
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
                val r = Serveur.exec(app, "uptime; echo; free -h | head -2; echo; df -h / | tail -1; echo; docker ps -a --format 'table {{.Names}}\\t{{.Status}}\\t{{.Image}}' 2>&1 | head -20; echo; n=\$(docker ps -a --format '{{.Names}}\\t{{.Image}}' 2>/dev/null | grep -i home-assistant | head -1 | cut -f1); [ -n \"\$n\" ] && { echo \"== logs \$n ==\"; docker logs --tail 25 \$n 2>&1; }", 60)
                ToolResult("# 🖥️ Serveur\n```\n" + sortie(r, 6000) + "\n```")
            }
            "ha_config" -> haConfig(a)
            else -> ToolResult("Outil serveur inconnu.")
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
