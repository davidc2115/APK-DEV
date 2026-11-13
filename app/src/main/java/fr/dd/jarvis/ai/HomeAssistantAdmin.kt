package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Administration COMPLÈTE de Home Assistant (avec un jeton d'un compte administrateur) : registre des entités (renommer,
 * changer l'identifiant, pièce, icône, désactiver, masquer, SUPPRIMER), appareils, pièces, intégrations (ajouter avec
 * leur formulaire de configuration, options, recharger, supprimer), automatisations / scripts / scènes (lire, créer,
 * modifier, supprimer), aides (interrupteurs virtuels, nombres, listes, minuteurs…), HACS (installer HACS, chercher et
 * installer une intégration ou une carte, ajouter un dépôt), système (vérifier la configuration, journal, redémarrer,
 * modèles Jinja) et accès brut à toute l'API WebSocket et REST.
 */
class HomeAssistantAdmin(
    urlBrute: String,
    jetonBrut: String,
    private val confirm: suspend (String, Boolean) -> Boolean,
    /** Commande sur le serveur SSH (null si SSH non configuré) : sert à installer HACS. */
    private val ssh: ((String, Int) -> Pair<Int, String>)? = null,
    private val conteneur: () -> String? = { null },
) {
    constructor(app: JarvisApp, confirm: suspend (String, Boolean) -> Boolean) : this(
        Serveur.haBaseDirecte(app), app.store.haToken.value, confirm,
        if (Serveur.sshConfigure(app)) { c, t -> Serveur.exec(app, c, t) } else null,
        { Serveur.haConteneur(app) })

    private val base = urlBrute.trim().trimEnd('/').let { if (it.isBlank() || it.startsWith("http")) it else "http://$it" }
    private val jeton = jetonBrut.trim()

    companion object {
        private val ids = AtomicInteger(1)
        private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
        val AIDES = listOf("input_boolean", "input_number", "input_select", "input_text", "input_datetime", "input_button", "counter", "timer", "schedule")
    }

    // ------------------------------------------------------------------ WebSocket

    private class Ws(private val base: String, private val jeton: String) : WebSocketListener(), AutoCloseable {
        private val recu = LinkedBlockingQueue<String>()
        private var ws: WebSocket? = null
        override fun onMessage(webSocket: WebSocket, text: String) { recu.offer(text) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { recu.offer(JSONObject().put("_echec", t.message ?: "WebSocket indisponible").toString()) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { recu.offer(JSONObject().put("_echec", "connexion fermée : $reason").toString()) }
        private fun suivant(delaiS: Long): JSONObject {
            val o = JSONObject(recu.poll(delaiS, TimeUnit.SECONDS) ?: throw IllegalStateException("Home Assistant ne répond pas (WebSocket)."))
            if (o.has("_echec")) throw IllegalStateException("Home Assistant : ${o.optString("_echec")}")
            return o
        }
        fun ouvrir(): Ws {
            if (base.isBlank() || jeton.isBlank()) throw IllegalStateException("Home Assistant non configuré : adresse et jeton dans Réglages › Maison › Serveur.")
            val url = (if (base.startsWith("https://")) "wss://" + base.removePrefix("https://") else "ws://" + base.removePrefix("http://")) + "/api/websocket"
            ws = client.newWebSocket(Request.Builder().url(url).build(), this)
            if (suivant(20).optString("type") != "auth_required") throw IllegalStateException("Réponse WebSocket Home Assistant inattendue.")
            ws!!.send(JSONObject().put("type", "auth").put("access_token", jeton).toString())
            val auth = suivant(20)
            if (auth.optString("type") != "auth_ok") throw IllegalStateException("Jeton Home Assistant refusé : ${auth.optString("message", auth.optString("type"))}")
            return this
        }
        /** Commande ; renvoie le « result » (objet, liste ou valeur). */
        fun cmd(type: String, args: JSONObject = JSONObject(), delaiS: Long = 60): Any? {
            val id = ids.getAndIncrement()
            if (ws?.send(JSONObject(args.toString()).put("id", id).put("type", type).toString()) != true) throw IllegalStateException("Envoi impossible : $type")
            val fin = System.currentTimeMillis() + delaiS * 1000
            while (System.currentTimeMillis() < fin) {
                val r = suivant(delaiS)
                if (r.optInt("id", -1) != id || r.optString("type") != "result") continue
                if (!r.optBoolean("success")) {
                    val e = r.optJSONObject("error")
                    throw IllegalStateException("Home Assistant refuse « $type » : ${e?.optString("message") ?: r}" +
                        (if (e?.optString("code") == "unauthorized") " — le jeton doit venir d'un compte ADMINISTRATEUR." else ""))
                }
                return r.opt("result")
            }
            throw IllegalStateException("Pas de réponse à « $type ».")
        }
        override fun close() { ws?.close(1000, "fin") }
    }

    private fun <T> ws(f: (Ws) -> T): T = Ws(base, jeton).ouvrir().use(f)

    private fun liste(v: Any?): List<JSONObject> = (v as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    // ------------------------------------------------------------------ REST (GET, POST, DELETE)

    private fun rest(methode: String, chemin: String, corps: Any? = null): Any? {
        if (base.isBlank() || jeton.isBlank()) throw IllegalStateException("Home Assistant non configuré : adresse et jeton dans Réglages › Maison › Serveur.")
        val rb = if (methode == "GET" || methode == "DELETE" && corps == null) null
            else (corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(base + chemin).header("Authorization", "Bearer $jeton").method(methode, rb).build()
        client.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw IllegalStateException("Home Assistant ${r.code} : il faut un jeton d'un compte ADMINISTRATEUR (Profil › Sécurité › Jetons d'accès longue durée). ${t.take(200)}")
            if (!r.isSuccessful) throw IllegalStateException("Home Assistant ${r.code} sur $chemin : ${t.take(500)}")
            return runCatching { if (t.trimStart().startsWith("[")) JSONArray(t) else JSONObject(t) }.getOrDefault(t)
        }
    }

    private fun json(a: JSONObject, cle: String): JSONObject? = when (val v = a.opt(cle)) {
        is JSONObject -> v
        is String -> v.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        else -> null
    }

    private fun oui(v: String) = normaliser(v) in setOf("oui", "true", "1", "vrai", "yes", "on")
    private fun non(v: String) = normaliser(v) in setOf("non", "false", "0", "faux", "no", "off")

    // ------------------------------------------------------------------ recherche (registres)

    private fun pieces(w: Ws) = liste(w.cmd("config/area_registry/list"))
    private fun pieceId(w: Ws, nom: String, creer: Boolean = false): String? {
        if (nom.isBlank()) return null
        val l = pieces(w)
        l.firstOrNull { it.optString("area_id") == nom || normaliser(it.optString("name")) == normaliser(nom) }?.let { return it.optString("area_id") }
        l.firstOrNull { normaliser(it.optString("name")).contains(normaliser(nom)) }?.let { return it.optString("area_id") }
        return if (creer) (w.cmd("config/area_registry/create", JSONObject().put("name", nom)) as? JSONObject)?.optString("area_id") else null
    }

    private fun integrations(): List<JSONObject> = liste(rest("GET", "/api/config/config_entries/entry"))
    private fun integration(nom: String): JSONObject {
        val l = integrations()
        val n = normaliser(nom)
        return l.firstOrNull { it.optString("entry_id") == nom } ?: l.filter { normaliser(it.optString("title")) == n || it.optString("domain") == nom }.singleOrNull()
            ?: l.filter { normaliser(it.optString("title")).contains(n) || normaliser(it.optString("domain")).contains(n) }.let {
                when (it.size) { 1 -> it[0]; 0 -> throw IllegalStateException("Aucune intégration « $nom ». Utilise action=integrations pour la liste.")
                    else -> throw IllegalStateException("Plusieurs intégrations correspondent à « $nom » : " + it.joinToString { e -> "${e.optString("title")} (${e.optString("domain")}, ${e.optString("entry_id")})" }) }
            }
    }

    private fun appareil(w: Ws, nom: String): JSONObject {
        val l = liste(w.cmd("config/device_registry/list"))
        val n = normaliser(nom)
        fun nomDe(d: JSONObject) = d.optString("name_by_user").ifBlank { d.optString("name") }
        return l.firstOrNull { it.optString("id") == nom } ?: l.filter { normaliser(nomDe(it)) == n }.singleOrNull()
            ?: l.filter { normaliser(nomDe(it)).contains(n) }.let {
                when (it.size) { 1 -> it[0]; 0 -> throw IllegalStateException("Aucun appareil « $nom ».")
                    else -> throw IllegalStateException("Plusieurs appareils : " + it.take(10).joinToString { d -> "${nomDe(d)} (${d.optString("id")})" }) }
            }
    }

    // ------------------------------------------------------------------ flux de configuration (intégrations, options)

    /** Présente une étape de flux : formulaire à remplir, menu, lien externe, fin. */
    private fun etape(r: Any?, chemin: String): String {
        val o = r as? JSONObject ?: return "Réponse : $r"
        val flow = o.optString("flow_id")
        val ph = o.optJSONObject("description_placeholders")
        return when (o.optString("type")) {
            "create_entry" -> "✅ Configuration terminée : « ${o.optString("title")} »" + (o.optJSONObject("result")?.optString("entry_id")?.let { " (entry_id $it)" } ?: "")
            "abort" -> "⛔ Abandon : ${o.optString("reason")}" + (ph?.let { " $it" } ?: "")
            "external", "external_step" -> "🔗 Étape externe : ouvre ${o.optString("url")} puis continue avec action=integration_etape flow_id=$flow."
            "progress", "show_progress" -> "⏳ En cours (${o.optString("progress_action")}) : " + (ph?.toString() ?: "") + "\nQuand c'est fait : action=integration_etape flow_id=$flow (sans données) pour voir la suite."
            "menu" -> "📋 Choix (flow_id=$flow, étape ${o.optString("step_id")}) : " + (o.optJSONArray("menu_options")?.toString() ?: o.optJSONObject("menu_options")?.toString()) +
                "\nRéponds avec action=integration_etape flow_id=$flow donnees={\"next_step_id\":\"<choix>\"}."
            "form" -> {
                val champs = liste(o.optJSONArray("data_schema")).joinToString("\n") { c ->
                    "- ${c.optString("name")}" + (if (c.optBoolean("required")) " (obligatoire)" else "") +
                        (c.optString("type").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                        (c.optJSONObject("selector")?.keys()?.asSequence()?.firstOrNull()?.let { " · $it" } ?: "") +
                        (c.opt("default")?.let { " · défaut $it" } ?: "") + (c.optJSONArray("options")?.let { " · choix $it" } ?: "")
                }
                "📝 Formulaire « ${o.optString("step_id")} » (flow_id=$flow) :\n$champs" +
                    (o.optJSONObject("errors")?.takeIf { it.length() > 0 }?.let { "\n⚠️ Erreurs : $it" } ?: "") +
                    (ph?.takeIf { it.length() > 0 }?.let { "\nInfos : $it" } ?: "") +
                    "\nDemande les valeurs à l'utilisateur si besoin, puis action=integration_etape flow_id=$flow donnees={…}."
            }
            else -> o.toString().take(1500)
        }.let { "$it\n(flux : $chemin)" }
    }

    // ------------------------------------------------------------------ actions

    suspend fun executer(actionBrute: String, a: JSONObject): String {
        val action = normaliser(actionBrute).replace(' ', '_')
        suspend fun ok(q: String) = confirm("Home Assistant : $q", true)
        return when (action) {
            // ---- entités (registre)
            "entites", "entites_registre" -> ws { w ->
                val n = normaliser(a.optString("recherche"))
                val dom = a.optString("domaine").trim()
                val integ = a.optString("integration").trim()
                val l = liste(w.cmd("config/entity_registry/list")).filter { e ->
                    (dom.isBlank() || e.optString("entity_id").startsWith("$dom.")) && (integ.isBlank() || e.optString("platform") == integ) &&
                        (n.isBlank() || normaliser(e.optString("entity_id") + " " + e.optString("name") + " " + e.optString("original_name")).contains(n))
                }
                if (l.isEmpty()) "Aucune entité enregistrée ne correspond." else "# 🏷️ ${l.size} entité(s)\n" + l.take(80).joinToString("\n") { e ->
                    "- `${e.optString("entity_id")}` · ${e.optString("name").ifBlank { e.optString("original_name") }} · ${e.optString("platform")}" +
                        (e.optString("area_id").takeIf { it.isNotBlank() && it != "null" }?.let { " · pièce $it" } ?: "") +
                        (if (!e.isNull("disabled_by")) " · ⛔ désactivée" else "") + (if (!e.isNull("hidden_by")) " · masquée" else "")
                } + (if (l.size > 80) "\n… (${l.size - 80} de plus : affine la recherche)" else "")
            }
            "entite_modifier" -> ws { w ->
                val id = a.optString("entity_id").trim().ifBlank { throw IllegalArgumentException("entity_id requis.") }
                val m = JSONObject().put("entity_id", id)
                a.optString("nom").takeIf { it.isNotBlank() }?.let { m.put("name", it) }
                a.optString("nouvel_id").takeIf { it.isNotBlank() }?.let { m.put("new_entity_id", it) }
                a.optString("icone").takeIf { it.isNotBlank() }?.let { m.put("icon", it) }
                a.optString("piece").takeIf { it.isNotBlank() }?.let { m.put("area_id", pieceId(w, it, creer = true)) }
                a.optString("desactiver").let { if (oui(it)) m.put("disabled_by", "user") else if (non(it)) m.put("disabled_by", JSONObject.NULL) else Unit }
                a.optString("masquer").let { if (oui(it)) m.put("hidden_by", "user") else if (non(it)) m.put("hidden_by", JSONObject.NULL) else Unit }
                val r = w.cmd("config/entity_registry/update", m) as? JSONObject
                "✅ Entité mise à jour : `${r?.optJSONObject("entity_entry")?.optString("entity_id") ?: id}`" +
                    (r?.optBoolean("require_restart")?.takeIf { it }?.let { " (redémarrage nécessaire)" } ?: "") +
                    (r?.optBoolean("reload_delay")?.takeIf { it }?.let { " (rechargement dans 30 s)" } ?: "")
            }
            "entite_supprimer" -> {
                val id = a.optString("entity_id").trim().ifBlank { throw IllegalArgumentException("entity_id requis.") }
                if (!ok("SUPPRIMER l'entité $id ?")) return "Annulé."
                ws { w ->
                    val enregistree = liste(w.cmd("config/entity_registry/list")).any { it.optString("entity_id") == id }
                    if (enregistree) {
                        try {
                            w.cmd("config/entity_registry/remove", JSONObject().put("entity_id", id))
                            "🗑️ Entité $id supprimée du registre. Si l'intégration la recrée au prochain rechargement, désactive-la plutôt (action=entite_modifier desactiver=oui) ou retire l'appareil."
                        } catch (e: IllegalStateException) {
                            "❌ ${e.message}\nHome Assistant refuse de supprimer une entité encore fournie par son intégration : désactive-la (action=entite_modifier desactiver=oui), supprime l'appareil (appareil_supprimer) ou l'intégration."
                        }
                    } else {
                        // Entité sans identifiant unique (YAML) ou simple état créé par l'API.
                        runCatching { rest("DELETE", "/api/states/$id") }.fold(
                            { "🗑️ État $id supprimé (entité sans identifiant unique). Si elle vient d'un fichier YAML, retire-la du fichier (ha_config) sinon elle reviendra au redémarrage." },
                            { "❌ $id n'est pas dans le registre et son état n'a pas pu être supprimé : elle est sans doute définie en YAML → retire-la avec ha_config (lire puis écrire le fichier)." })
                    }
                }
            }
            // ---- appareils
            "appareils" -> ws { w ->
                val n = normaliser(a.optString("recherche"))
                val l = liste(w.cmd("config/device_registry/list")).filter { d -> n.isBlank() || normaliser(d.optString("name") + " " + d.optString("name_by_user") + " " + d.optString("manufacturer") + " " + d.optString("model")).contains(n) }
                if (l.isEmpty()) "Aucun appareil." else "# 📟 ${l.size} appareil(s)\n" + l.take(60).joinToString("\n") { d ->
                    "- ${d.optString("name_by_user").ifBlank { d.optString("name") }} · ${d.optString("manufacturer")} ${d.optString("model")} · id ${d.optString("id")}" +
                        (d.optString("area_id").takeIf { it.isNotBlank() && it != "null" }?.let { " · pièce $it" } ?: "") + (if (!d.isNull("disabled_by")) " · ⛔ désactivé" else "")
                }
            }
            "appareil_modifier" -> ws { w ->
                val d = appareil(w, a.optString("appareil").ifBlank { a.optString("device_id") })
                val m = JSONObject().put("device_id", d.optString("id"))
                a.optString("nom").takeIf { it.isNotBlank() }?.let { m.put("name_by_user", it) }
                a.optString("piece").takeIf { it.isNotBlank() }?.let { m.put("area_id", pieceId(w, it, creer = true)) }
                a.optString("desactiver").let { if (oui(it)) m.put("disabled_by", "user") else if (non(it)) m.put("disabled_by", JSONObject.NULL) else Unit }
                w.cmd("config/device_registry/update", m)
                "✅ Appareil « ${d.optString("name_by_user").ifBlank { d.optString("name") }} » mis à jour."
            }
            "appareil_supprimer" -> {
                val nom = a.optString("appareil").ifBlank { a.optString("device_id") }
                if (!ok("SUPPRIMER l'appareil « $nom » (et ses entités) ?")) return "Annulé."
                ws { w ->
                    val d = appareil(w, nom)
                    val entrees = d.optJSONArray("config_entries")?.let { x -> (0 until x.length()).map { x.getString(it) } }.orEmpty()
                    val res = entrees.map { e -> runCatching { w.cmd("config/device_registry/remove_config_entry", JSONObject().put("device_id", d.optString("id")).put("config_entry_id", e)) } }
                    if (res.all { it.isSuccess }) "🗑️ Appareil « ${d.optString("name_by_user").ifBlank { d.optString("name") }} » supprimé."
                    else "❌ L'intégration de cet appareil ne permet pas de le supprimer ici : ${res.mapNotNull { it.exceptionOrNull()?.message }.first()}. Supprime-le depuis l'intégration (ou supprime l'intégration)."
                }
            }
            // ---- pièces
            "pieces" -> ws { w -> pieces(w).joinToString("\n", "# 🏠 Pièces\n") { "- ${it.optString("name")} (${it.optString("area_id")})" } }
            "piece_creer" -> ws { w -> val r = w.cmd("config/area_registry/create", JSONObject().put("name", a.optString("nom"))) as? JSONObject; "✅ Pièce « ${r?.optString("name")} » créée (${r?.optString("area_id")})." }
            "piece_renommer" -> ws { w ->
                val id = pieceId(w, a.optString("piece").ifBlank { a.optString("nom") }) ?: throw IllegalStateException("Pièce introuvable.")
                w.cmd("config/area_registry/update", JSONObject().put("area_id", id).put("name", a.optString("nouveau_nom"))); "✅ Pièce renommée en « ${a.optString("nouveau_nom")} »."
            }
            "piece_supprimer" -> {
                if (!ok("supprimer la pièce « ${a.optString("piece").ifBlank { a.optString("nom") }} » ?")) return "Annulé."
                ws { w -> val id = pieceId(w, a.optString("piece").ifBlank { a.optString("nom") }) ?: throw IllegalStateException("Pièce introuvable."); w.cmd("config/area_registry/delete", JSONObject().put("area_id", id)); "🗑️ Pièce supprimée." }
            }
            // ---- intégrations
            "integrations" -> integrations().let { l -> if (l.isEmpty()) "Aucune intégration." else "# 🧩 ${l.size} intégration(s)\n" + l.joinToString("\n") {
                "- ${it.optString("title")} · ${it.optString("domain")} · ${it.optString("state")} · entry_id ${it.optString("entry_id")}" + (if (!it.isNull("disabled_by")) " · ⛔ désactivée" else "") } }
            "integration_ajouter" -> {
                val dom = a.optString("domaine").trim().ifBlank { throw IllegalArgumentException("domaine requis (ex. hacs, mqtt, tuya, shelly…).") }
                val r = rest("POST", "/api/config/config_entries/flow", JSONObject().put("handler", dom).put("show_advanced_options", true)) as? JSONObject
                val d = json(a, "donnees")
                if (d != null && r != null && r.optString("type") == "form") etape(rest("POST", "/api/config/config_entries/flow/${r.optString("flow_id")}", d), "intégration $dom")
                else etape(r, "intégration $dom")
            }
            "integration_etape" -> {
                val flow = a.optString("flow_id").trim().ifBlank { throw IllegalArgumentException("flow_id requis.") }
                val options = normaliser(a.optString("type")) == "options"
                val racine = if (options) "/api/config/config_entries/options/flow/" else "/api/config/config_entries/flow/"
                val d = json(a, "donnees")
                try { etape(if (d == null) rest("GET", racine + flow) else rest("POST", racine + flow, d), if (options) "options" else "intégration") }
                catch (e: IllegalStateException) {
                    if (e.message?.contains(" 404 ") != true) throw e
                    "ℹ️ Ce flux de configuration est terminé (ou a expiré). Vérifie le résultat avec action=integrations (ou hacs_etat pour HACS) ; sinon relance integration_ajouter."
                }
            }
            "integration_annuler" -> { rest("DELETE", "/api/config/config_entries/flow/" + a.optString("flow_id").trim()); "Flux annulé." }
            "integration_options" -> {
                val e = integration(a.optString("integration").ifBlank { a.optString("entry_id") })
                etape(rest("POST", "/api/config/config_entries/options/flow", JSONObject().put("handler", e.optString("entry_id")).put("show_advanced_options", true)), "options de ${e.optString("title")} — continue avec type=options")
            }
            "integration_recharger" -> { val e = integration(a.optString("integration").ifBlank { a.optString("entry_id") }); rest("POST", "/api/config/config_entries/entry/${e.optString("entry_id")}/reload"); "🔄 « ${e.optString("title")} » rechargée." }
            "integration_supprimer" -> {
                val e = integration(a.optString("integration").ifBlank { a.optString("entry_id") })
                if (!ok("SUPPRIMER l'intégration « ${e.optString("title")} » (${e.optString("domain")}) et ses appareils ?")) return "Annulé."
                val r = rest("DELETE", "/api/config/config_entries/entry/${e.optString("entry_id")}") as? JSONObject
                "🗑️ Intégration « ${e.optString("title")} » supprimée." + (if (r?.optBoolean("require_restart") == true) " Redémarrage de Home Assistant nécessaire." else "")
            }
            // ---- automatisations, scripts, scènes (gérés par l'interface)
            "automatisations", "scripts", "scenes" -> {
                val dom = mapOf("automatisations" to "automation", "scripts" to "script", "scenes" to "scene").getValue(action)
                val l = liste(rest("GET", "/api/states")).filter { it.optString("entity_id").startsWith("$dom.") }
                if (l.isEmpty()) "Aucun(e) $action." else "# ⚙️ ${l.size} $action\n" + l.joinToString("\n") { s ->
                    val at = s.optJSONObject("attributes")
                    "- ${at?.optString("friendly_name")} · `${s.optString("entity_id")}` · ${s.optString("state")}" + (at?.optString("id")?.takeIf { it.isNotBlank() }?.let { " · id $it" } ?: "") +
                        (at?.optString("last_triggered")?.takeIf { it.isNotBlank() && it != "null" }?.let { " · dernier $it" } ?: "")
                }
            }
            "automatisation_lire", "script_lire", "scene_lire" -> rest("GET", chemin(action, a)).toString().let { if (it.length > 6000) it.take(6000) + "…" else it }
            "automatisation_ecrire", "script_ecrire", "scene_ecrire" -> {
                val conf = json(a, "configuration") ?: throw IllegalArgumentException("configuration (JSON complet) requise : lis d'abord l'existante si tu modifies.")
                if (!ok("${if (a.optString("id").isBlank()) "créer" else "modifier"} « ${conf.optString("alias").ifBlank { a.optString("id") }} » ?")) return "Annulé."
                rest("POST", chemin(action, a, creer = true), conf); "✅ Enregistré et rechargé : « ${conf.optString("alias").ifBlank { a.optString("id") }} »."
            }
            "automatisation_supprimer", "script_supprimer", "scene_supprimer" -> {
                if (!ok("SUPPRIMER « ${a.optString("id").ifBlank { a.optString("entity_id") }} » ?")) return "Annulé."
                rest("DELETE", chemin(action, a)); "🗑️ Supprimé."
            }
            // ---- aides (helpers)
            "aides" -> ws { w -> AIDES.flatMap { t -> runCatching { liste(w.cmd("$t/list")).map { "- $t · ${it.optString("name")} · id ${it.optString("id")}" } }.getOrDefault(emptyList()) }
                .let { if (it.isEmpty()) "Aucune aide créée depuis l'interface." else "# 🧰 Aides\n" + it.joinToString("\n") } }
            "aide_creer" -> ws { w ->
                val t = a.optString("type").trim().let { if (it in AIDES) it else throw IllegalArgumentException("type parmi : ${AIDES.joinToString()}") }
                val p = (json(a, "options") ?: JSONObject()).put("name", a.optString("nom").ifBlank { throw IllegalArgumentException("nom requis.") })
                val r = w.cmd("$t/create", p) as? JSONObject
                "✅ Aide créée : $t.${r?.optString("id")} (« ${r?.optString("name")} »)."
            }
            "aide_supprimer" -> {
                val t = a.optString("type").trim()
                if (!ok("supprimer l'aide $t « ${a.optString("id")} » ?")) return "Annulé."
                ws { w -> w.cmd("$t/delete", JSONObject().put("${t}_id", a.optString("id").substringAfter('.'))); "🗑️ Aide supprimée." }
            }
            // ---- HACS
            "hacs_etat" -> runCatching { ws { w -> w.cmd("hacs/info") }.toString().take(800) }.getOrElse { "HACS n'est pas installé (ou pas encore configuré) : action=hacs_installer." }
            "hacs_installer" -> installerHacs()
            "hacs_chercher" -> ws { w ->
                val n = normaliser(a.optString("recherche"))
                val cat = a.optString("categorie").trim()
                val l = liste(w.cmd("hacs/repositories/list", if (cat.isNotBlank()) JSONObject().put("categories", JSONArray().put(cat)) else JSONObject(), 90))
                    .filter { r -> n.isBlank() || normaliser(r.optString("name") + " " + r.optString("full_name") + " " + r.optString("description")).contains(n) }
                    .sortedByDescending { it.optInt("stars") }
                if (l.isEmpty()) "Aucun dépôt HACS ne correspond." else "# 🛒 HACS (${l.size})\n" + l.take(15).joinToString("\n") { r ->
                    "- ${r.optString("name")} · ${r.optString("full_name")} · ${r.optString("category")} · ⭐ ${r.optInt("stars")} · id ${r.optString("id")}" +
                        (if (r.optBoolean("installed")) " · ✅ installé ${r.optString("installed_version")}" else "") + "\n  ${r.optString("description").take(140)}"
                }
            }
            "hacs_telecharger" -> {
                val depot = a.optString("depot").trim().ifBlank { throw IllegalArgumentException("depot requis (id HACS ou propriétaire/dépôt).") }
                if (!ok("installer « $depot » depuis HACS ?")) return "Annulé."
                ws { w ->
                    val id = idHacs(w, depot)
                    w.cmd("hacs/repository/download", JSONObject().put("repository", id).apply { a.optString("version").takeIf { it.isNotBlank() }?.let { put("version", it) } }, 180)
                    "✅ « $depot » téléchargé par HACS. Pour une intégration : redémarre Home Assistant (action=redemarrer) puis ajoute-la (action=integration_ajouter domaine=<domaine>). Pour une carte : elle est disponible dans les tableaux de bord."
                }
            }
            "hacs_depot_ajouter" -> ws { w ->
                val cat = a.optString("categorie").trim().lowercase().ifBlank { "integration" }
                val depot = a.optString("depot").trim().removePrefix("https://").removePrefix("http://").removePrefix("github.com/").removeSuffix("/").removeSuffix(".git")
                // HACS répond toujours « {} » : les erreurs partent en notification. On vérifie donc la présence du dépôt.
                w.cmd("hacs/repositories/add", JSONObject().put("repository", depot).put("category", cat), 120)
                val r = liste(w.cmd("hacs/repositories/list", JSONObject().put("categories", JSONArray().put(cat)), 90)).firstOrNull { it.optString("full_name").equals(depot, true) }
                if (r == null) "❌ HACS n'a pas ajouté « $depot » (catégorie $cat) : dépôt introuvable, mauvaise catégorie, ou pas un dépôt compatible HACS. Le détail est dans les notifications de Home Assistant (action=journal)."
                else "✅ Dépôt « ${r.optString("full_name")} » ajouté à HACS (catégorie $cat, id ${r.optString("id")}). Installe-le avec action=hacs_telecharger depot=${r.optString("full_name")}."
            }
            "hacs_supprimer" -> {
                if (!ok("désinstaller « ${a.optString("depot")} » de HACS ?")) return "Annulé."
                ws { w -> w.cmd("hacs/repository/remove", JSONObject().put("repository", idHacs(w, a.optString("depot").trim())), 120); "🗑️ Désinstallé. Redémarre Home Assistant si c'était une intégration." }
            }
            // ---- système
            "verifier" -> rest("POST", "/api/config/core/check_config").toString()
            "journal" -> rest("GET", "/api/error_log").toString().lines().takeLast(a.optString("lignes").toIntOrNull() ?: 60).joinToString("\n").takeLast(6000)
            "modele" -> rest("POST", "/api/template", JSONObject().put("template", a.optString("modele"))).toString().take(4000)
            "redemarrer" -> {
                val v = rest("POST", "/api/config/core/check_config") as? JSONObject
                if (v?.optString("result") == "invalid") return "⛔ Configuration invalide, redémarrage annulé : ${v.optString("errors")}"
                if (!ok("REDÉMARRER Home Assistant (coupure ~1 à 2 min) ?")) return "Annulé."
                runCatching { rest("POST", "/api/services/homeassistant/restart") }
                "🔄 Redémarrage lancé (1 à 2 minutes)."
            }
            // ---- accès brut (tout le reste)
            "ws" -> {
                val type = a.optString("type").trim().ifBlank { throw IllegalArgumentException("type WebSocket requis (ex. config/entity_registry/list).") }
                val lecture = Regex("(^|/)(list|get|info|config|subscribe)[a-z_]*$|/list|/get").containsMatchIn(type)
                if (!lecture && !ok("commande WebSocket « $type » ?")) return "Annulé."
                ws { w -> w.cmd(type, json(a, "donnees") ?: JSONObject(), 120) }.toString().let { if (it.length > 8000) it.take(8000) + "…" else it }
            }
            "rest" -> {
                val m = a.optString("methode").uppercase().ifBlank { "GET" }
                if (m != "GET" && !ok("$m ${a.optString("chemin")} ?")) return "Annulé."
                rest(m, a.optString("chemin").trim().let { if (it.startsWith("/")) it else "/$it" }, json(a, "donnees")).toString().let { if (it.length > 8000) it.take(8000) + "…" else it }
            }
            else -> "Action inconnue « $actionBrute »."
        }
    }

    private fun chemin(action: String, a: JSONObject, creer: Boolean = false): String {
        val type = action.substringBefore('_')
        val dom = mapOf("automatisation" to "automation", "script" to "script", "scene" to "scene").getValue(type)
        var id = a.optString("id").trim()
        if (id.isBlank()) a.optString("entity_id").trim().takeIf { it.isNotBlank() }?.let { eid ->
            id = if (dom == "script") eid.substringAfter('.') else
                (runCatching { (rest("GET", "/api/states/$eid") as? JSONObject)?.optJSONObject("attributes")?.optString("id") }.getOrNull() ?: "")
        }
        if (id.isBlank() && creer) id = if (dom == "script") normaliser(json(a, "configuration")?.optString("alias").orEmpty()).replace(' ', '_').ifBlank { "script_${System.currentTimeMillis()}" }
            else System.currentTimeMillis().toString()
        if (id.isBlank()) throw IllegalArgumentException("id (ou entity_id) requis.")
        return "/api/config/$dom/config/$id"
    }

    private fun idHacs(w: Ws, depot: String): String {
        if (depot.all { it.isDigit() }) return depot
        val n = normaliser(depot)
        val l = liste(w.cmd("hacs/repositories/list", JSONObject(), 90))
        return (l.firstOrNull { it.optString("full_name").equals(depot, true) } ?: l.filter { normaliser(it.optString("name")) == n || normaliser(it.optString("full_name")).endsWith(n) }.singleOrNull())
            ?.optString("id") ?: throw IllegalStateException("Dépôt HACS « $depot » introuvable (action=hacs_chercher, ou hacs_depot_ajouter pour un dépôt personnalisé).")
    }

    /** Installe HACS dans le conteneur Home Assistant (script officiel), redémarre, puis lance sa configuration. */
    private suspend fun installerHacs(): String {
        if (runCatching { ws { w -> w.cmd("hacs/info") } }.isSuccess) return "✅ HACS est déjà installé et configuré."
        val ssh = ssh ?: return "❌ Il faut l'accès SSH au serveur (Réglages › Maison › Serveur) pour installer HACS."
        if (!confirm("Home Assistant : installer HACS (script officiel get.hacs.xyz) puis redémarrer Home Assistant ?", true)) return "Annulé."
        val ctn = conteneur() ?: return "❌ Conteneur Home Assistant introuvable sur le serveur."
        var r = ssh("docker exec -w /config $ctn bash -c 'wget -O - https://get.hacs.xyz | bash -' 2>&1", 300)
        if (r.first != 0 && r.second.contains("permission denied", true)) r = ssh("sudo docker exec -w /config $ctn bash -c 'wget -O - https://get.hacs.xyz | bash -' 2>&1", 300)
        if (!r.second.contains("custom_components/hacs", true) && r.first != 0) return "❌ Installation de HACS impossible :\n" + r.second.takeLast(800)
        runCatching { rest("POST", "/api/services/homeassistant/restart") }
        // Attente du retour de Home Assistant (jusqu'à 4 min).
        val fin = System.currentTimeMillis() + 240_000
        kotlinx.coroutines.delay(20_000)
        while (System.currentTimeMillis() < fin && runCatching { rest("GET", "/api/") }.isFailure) kotlinx.coroutines.delay(5000)
        // Configuration de HACS : conditions à accepter, puis connexion GitHub (code à saisir sur github.com/login/device).
        val f = rest("POST", "/api/config/config_entries/flow", JSONObject().put("handler", "hacs")) as? JSONObject ?: return "HACS installé ; ajoute l'intégration HACS (action=integration_ajouter domaine=hacs)."
        var etat: Any? = f
        if (f.optString("type") == "form") {
            val accord = JSONObject()
            liste(f.optJSONArray("data_schema")).forEach { c -> if (c.optString("type") == "boolean" || c.optJSONObject("selector")?.has("boolean") == true) accord.put(c.optString("name"), true) }
            etat = rest("POST", "/api/config/config_entries/flow/${f.optString("flow_id")}", accord)
        }
        val o = etat as? JSONObject
        val ph = o?.optJSONObject("description_placeholders")
        return "✅ HACS installé, Home Assistant redémarré.\n" + (ph?.optString("code")?.takeIf { it.isNotBlank() }?.let {
            "🔑 Dernière étape : ouvre ${ph.optString("url").ifBlank { "https://github.com/login/device" }} et saisis le code **$it** (connexion GitHub de HACS).\n" +
                "Home Assistant termine tout seul dès que le code est validé ; dis-moi « c'est fait » et je vérifierai avec action=hacs_etat."
        } ?: etape(etat, "HACS"))
    }
}
