package fr.dd.jarvis.hermes

import android.util.Log
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.ToolBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Pont MCP « jarvis » (Streamable HTTP, réponses JSON) : expose à Hermès TOUS les outils de Jarvis (téléphone, agenda,
 * SMS, mails, musique, Obsidian, société, domotique, localisation…). Écoute seulement sur 127.0.0.1 et exige le jeton
 * Bearer écrit dans la configuration d'Hermès : aucune autre app ni machine du réseau ne peut s'en servir.
 *
 * Il reçoit aussi la progression du script d'installation (POST /progres), lancé en arrière-plan dans Termux.
 */
object PontMcp {
    const val PORT = 38291
    private const val TAG = "PontMcp"
    private const val maxOutilsExpose = 80
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var serveur: ServerSocket? = null
    @Volatile private var fournisseur: Fournisseur? = null

    /** Ce que le pont expose : jeton attendu, outils, exécution, réception de la progression. */
    interface Fournisseur {
        fun jeton(): String
        fun outils(): JSONArray
        /** Exécute l'outil : (texte, erreur ?). */
        fun appeler(nom: String, args: JSONObject): Pair<String, Boolean>
        fun progres(ligne: String)
    }

    /** Outils de la conversation en cours (avec SES confirmations) ; sinon une boîte d'outils autonome. */
    @Volatile var boiteActive: ToolBox? = null

    /** Outils jamais exposés à Hermès (gestion d'Hermès lui-même → pas de boucle). */
    private val EXCLUS = setOf("hermes", "hermes_deleguer")

    /** Dernières lignes de progression envoyées par le script d'installation. */
    val progression = androidx.compose.runtime.mutableStateListOf<String>()
    @Volatile var dernierSignal = 0L

    fun url() = "http://127.0.0.1:$PORT/mcp"

    private class FournisseurJarvis(val app: JarvisApp) : Fournisseur {
        private val defaut by lazy { ToolBox(app) { _, force -> !force } }
        private fun boite() = boiteActive ?: defaut
        override fun jeton() = app.store.mcpJeton.value
        override fun outils(): JSONArray = convertirOutils(boite().schemaAll())
        override fun appeler(nom: String, args: JSONObject): Pair<String, Boolean> {
            if (nom in EXCLUS) return "Outil indisponible : $nom" to true
            // Certains clients Hermes préfixent les noms MCP par mcp_jarvis_.
            val nomCanonique = nom.removePrefix("mcp_jarvis_")
            if (nomCanonique == "jarvis_rechercher_outils") {
                val termes = args.optString("requete").trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (termes.isEmpty()) return "Précise le nom ou le domaine de l'outil recherché." to true
                val tous = boite().schemaAll()
                val candidats = mutableListOf<Pair<Int, JSONObject>>()
                for (i in 0 until tous.length()) {
                    val fn = tous.optJSONObject(i)?.optJSONObject("function") ?: continue
                    val nomOutil = fn.optString("name")
                    if (nomOutil.isBlank() || nomOutil in EXCLUS || nomOutil == "jarvis_rechercher_outils" || nomOutil == "jarvis_appeler_outil") continue
                    val texte = (nomOutil + " " + fn.optString("description")).lowercase()
                    val demandeSuppressionIntervention =
                        termes.any { it.contains("intervention") } &&
                            termes.any { it in setOf("supprimer", "suppression", "effacer", "delete", "remove", "retirer", "enlever", "annuler") }
                    val score = termes.count { texte.contains(it) } +
                        if (demandeSuppressionIntervention && nomOutil in setOf("interventions_liste", "intervention_supprimer")) 1000 else 0
                    if (score > 0) candidats += score to fn
                }
                val resultat = JSONArray()
                candidats.sortedByDescending { it.first }.take(args.optInt("limite", 12).coerceIn(1, 20)).forEach { (_, fn) ->
                    resultat.put(JSONObject()
                        .put("name", fn.optString("name"))
                        .put("description", fn.optString("description"))
                        .put("inputSchema", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject())))
                }
                return JSONObject().put("outils", resultat).toString() to false
            }
            // Alias directs de secours : toujours exposés, indépendants de la recherche du catalogue.
            val aliasIntervention = when (nomCanonique) {
                "jarvis_interventions_lister" -> "interventions_liste"
                "jarvis_intervention_supprimer" -> "intervention_supprimer"
                else -> null
            }
            val cible = aliasIntervention ?: if (nomCanonique == "jarvis_appeler_outil") args.optString("nom_outil").removePrefix("mcp_jarvis_") else nomCanonique
            if (cible == "jarvis_appeler_outil" || cible == "jarvis_rechercher_outils" ||
                cible in EXCLUS || cible.isBlank())
                return "Outil cible invalide ou interdit : $cible" to true
            val arguments = if (nomCanonique == "jarvis_appeler_outil") args.optJSONObject("arguments") ?: JSONObject() else args
            val res = runCatching { runBlocking(Dispatchers.IO) { withTimeoutOrNull(170_000) { boite().execute(cible, arguments) } } }
            return res.fold(
                { r -> if (r == null) "⏱️ L'outil $cible a dépassé le délai." to true else r.text to false },
                { e -> "❌ ${e.message ?: e.javaClass.simpleName}" to true }
            )
        }
        override fun progres(ligne: String) {
            if (ligne != "pong") android.os.Handler(android.os.Looper.getMainLooper()).post {
                progression.add(ligne); while (progression.size > 40) progression.removeAt(0)
            }
            HermesTermux.surProgression(app, ligne)
        }
    }

    @Synchronized fun demarrer(a: JarvisApp): Boolean {
        if (a.store.mcpJeton.value.isBlank()) a.store.mcpJeton.set(UUID.randomUUID().toString().replace("-", ""))
        if (fournisseur !is FournisseurJarvis) fournisseur = FournisseurJarvis(a)
        val ok = ecouter(PORT)
        if (!ok) Log.e(TAG, "Impossible de démarrer le serveur de progression/MCP sur 127.0.0.1:$PORT")
        return ok && actif()
    }

    /** Démarre l'écoute avec un fournisseur donné (tests hors Android). */
    @Synchronized internal fun demarrerAvec(f: Fournisseur, port: Int): Boolean { fournisseur = f; return ecouter(port) }

    @Synchronized internal fun arreter() { runCatching { serveur?.close() }; serveur = null }

    private fun ecouter(port: Int): Boolean {
        serveur?.let { if (!it.isClosed) return true }
        val s = try { ServerSocket(port, 32, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) {
            runCatching { Log.w(TAG, "port $port indisponible : ${e.message}") }; return false
        }
        serveur = s
        pool.execute {
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (_: Exception) { break }
                pool.execute { runCatching { traiter(sock) }.onFailure { e -> runCatching { Log.w(TAG, "requête : ${e.message}") } } }
            }
        }
        return true
    }

    fun actif() = serveur?.isClosed == false

    // ------------------------------------------------------------------ HTTP minimal

    private class Requete(val methode: String, val chemin: String, val entetes: Map<String, String>, val corps: String)

    private fun lireLigne(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c == -1) return if (b.size() == 0) null else b.toString(Charsets.ISO_8859_1.name())
            if (c == '\n'.code) return b.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            b.write(c)
        }
    }

    private fun lire(i: InputStream): Requete? {
        val premiere = lireLigne(i) ?: return null
        val methode = premiere.substringBefore(' ').uppercase()
        val chemin = premiere.substringAfter(' ', "").substringBefore(' ').substringBefore('?')
        val h = HashMap<String, String>()
        while (true) {
            val l = lireLigne(i) ?: break
            if (l.isEmpty()) break
            val k = l.substringBefore(':').trim().lowercase()
            if (k.isNotEmpty()) h[k] = l.substringAfter(':', "").trim()
        }
        val corps = when {
            h["transfer-encoding"]?.contains("chunked", true) == true -> {
                val out = ByteArrayOutputStream()
                while (true) {
                    val taille = lireLigne(i)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
                    if (taille == 0) { lireLigne(i); break }
                    val buf = ByteArray(taille); var lu = 0
                    while (lu < taille) { val n = i.read(buf, lu, taille - lu); if (n < 0) break; lu += n }
                    out.write(buf, 0, lu); lireLigne(i)
                }
                out.toString(Charsets.UTF_8.name())
            }
            else -> {
                val n = h["content-length"]?.toIntOrNull() ?: 0
                val buf = ByteArray(n); var lu = 0
                while (lu < n) { val k = i.read(buf, lu, n - lu); if (k < 0) break; lu += k }
                String(buf, 0, lu, Charsets.UTF_8)
            }
        }
        return Requete(methode, chemin, h, corps)
    }

    private fun repondre(s: Socket, code: Int, corps: String = "", type: String = "application/json", extra: Map<String, String> = emptyMap()) {
        val b = corps.toByteArray(Charsets.UTF_8)
        val statut = when (code) { 200 -> "OK"; 202 -> "Accepted"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        val h = StringBuilder("HTTP/1.1 $code $statut\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n")
        extra.forEach { (k, v) -> h.append("$k: $v\r\n") }
        h.append("\r\n")
        s.getOutputStream().apply { write(h.toString().toByteArray(Charsets.ISO_8859_1)); write(b); flush() }
    }

    private fun traiter(sock: Socket) = sock.use { s ->
        s.soTimeout = 30_000
        val r = lire(BufferedInputStream(s.getInputStream())) ?: return@use
        val f = fournisseur ?: return@use repondre(s, 404)
        val jeton = f.jeton()
        if (jeton.isBlank() || r.entetes["authorization"] != "Bearer $jeton") return@use repondre(s, 401, """{"error":"unauthorized"}""")
        when {
            r.chemin.trimEnd('/') == "/progres" && r.methode == "POST" -> {
                val ligne = r.corps.trim().take(300)
                if (ligne.isNotEmpty()) { dernierSignal = System.currentTimeMillis(); f.progres(ligne) }
                repondre(s, 200, "{}")
            }
            r.methode == "GET" -> repondre(s, 405, "", extra = mapOf("Allow" to "POST, DELETE"))
            r.methode == "DELETE" -> repondre(s, 200, "{}")
            r.methode == "POST" -> {
                val txt = r.corps.trim()
                if (txt.startsWith("[")) {
                    val lot = JSONArray(txt); val out = JSONArray()
                    for (k in 0 until lot.length()) rpc(f, lot.getJSONObject(k))?.let { out.put(it) }
                    if (out.length() == 0) repondre(s, 202) else repondre(s, 200, out.toString())
                } else {
                    val o = JSONObject(txt.ifBlank { "{}" })
                    val rep = rpc(f, o)
                    val extra = if (o.optString("method") == "initialize") mapOf("Mcp-Session-Id" to UUID.randomUUID().toString()) else emptyMap()
                    if (rep == null) repondre(s, 202, extra = extra) else repondre(s, 200, rep.toString(), extra = extra)
                }
            }
            else -> repondre(s, 405)
        }
    }

    // ------------------------------------------------------------------ JSON-RPC MCP

    private fun rpc(f: Fournisseur, req: JSONObject): JSONObject? {
        if (!req.has("id")) return null // notification (initialized, cancelled…)
        val id = req.get("id")
        val p = req.optJSONObject("params") ?: JSONObject()
        fun ok(res: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", res)
        fun ko(code: Int, msg: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", msg))
        return when (req.optString("method")) {
            "initialize" -> ok(JSONObject()
                .put("protocolVersion", p.optString("protocolVersion").ifBlank { "2025-06-18" })
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", true)))
                .put("serverInfo", JSONObject().put("name", "jarvis").put("version", "1.0"))
                .put("instructions", "Outils du smartphone Android de l'utilisateur, fournis par l'app Jarvis : appels, SMS, agenda, mails, " +
                    "musique, réveils, domotique, coffre Obsidian, société (clients, devis, factures, paie), localisation, images/vidéos IA, réglages Jarvis. " +
                    "IMPORTANT pour les interventions photovoltaïques : les noms exacts des outils MCP sont interventions_liste, intervention_supprimer et intervention_modifier (sans préfixe). Ils sont exposés par le pont local Jarvis. " +
                    "Si ces outils ne sont pas visibles, utilise tool_search avec les termes exacts interventions_liste et intervention_supprimer ; ne remplace jamais cette recherche par une recherche de PDF dans Obsidian. " +
                    "Pour supprimer une intervention, utilise obligatoirement interventions_liste pour obtenir son ID, puis intervention_supprimer avec cet ID et confirmation='oui' après accord explicite. " +
                    "N'utilise jamais les outils génériques du coffre Obsidian pour supprimer une intervention et n'appelle jamais intervention_enregistrer pour simuler une suppression. " +
                    "Les actions sensibles (SMS, appels, suppressions) doivent respecter les confirmations de l'utilisateur."))
            "ping" -> ok(JSONObject())
            "tools/list" -> ok(JSONObject().put("tools", f.outils()))
            "tools/call" -> {
                val (texte, erreur) = f.appeler(p.optString("name"), p.optJSONObject("arguments") ?: JSONObject())
                ok(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", texte.take(30_000))))
                    .put("isError", erreur))
            }
            "resources/list" -> ok(JSONObject().put("resources", JSONArray()))
            "resources/templates/list" -> ok(JSONObject().put("resourceTemplates", JSONArray()))
            "prompts/list" -> ok(JSONObject().put("prompts", JSONArray()))
            else -> ko(-32601, "Méthode inconnue : ${req.optString("method")}")
        }
    }

    /** Schéma « function » (OpenAI) des outils de Jarvis → outils MCP (name, description, inputSchema). */
    internal fun convertirOutils(tous: JSONArray): JSONArray {
        // Groq accepte au maximum 128 outils par requête. On plafonne Jarvis à 80
        // pour laisser une marge aux autres serveurs MCP connectés dans Hermès.
        val prioritaires = listOf(
            "interventions_liste", "intervention_supprimer", "intervention_modifier",
            "intervention_enregistrer", "interventions_synthese"
        )
        val fonctions = mutableListOf<JSONObject>()
        val nomsDejaVus = mutableSetOf<String>()
        for (i in 0 until tous.length()) {
            val f = tous.optJSONObject(i)?.optJSONObject("function") ?: continue
            val nom = f.optString("name")
            if (nom.isNotBlank() && nom !in EXCLUS && nomsDejaVus.add(nom)) fonctions += f
        }
        // Les outils métier indispensables restent toujours visibles, puis on complète
        // avec les autres outils sans dépasser le budget transmis aux modèles.
        fonctions.sortBy { f ->
            val index = prioritaires.indexOf(f.optString("name"))
            if (index < 0) prioritaires.size else index
        }
        val out = JSONArray()
        out.put(JSONObject()
            .put("name", "jarvis_rechercher_outils")
            .put("description", "Recherche dans le catalogue complet des outils JARVIS, y compris ceux non affichés dans la liste courte. À utiliser si un outil métier est absent ou pour trouver une fonction par domaine.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("requete", JSONObject().put("type", "string").put("description", "Nom ou mots-clés du domaine recherché"))
                .put("limite", JSONObject().put("type", "integer").put("description", "Nombre maximal de résultats (1 à 20)")))
                .put("required", JSONArray().put("requete"))))
        out.put(JSONObject()
            .put("name", "jarvis_interventions_lister")
            .put("description", "Consulte directement l'historique des interventions d'un client dans la base Société de Jarvis. Toujours exposé, sans recherche préalable.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("client", JSONObject().put("type", "string").put("description", "Nom exact du client"))
                .put("installation", JSONObject().put("type", "string").put("description", "Installation exacte, facultative ; vide pour toutes")))
                .put("required", JSONArray().put("client"))))
        out.put(JSONObject()
            .put("name", "jarvis_intervention_supprimer")
            .put("description", "Supprime une intervention précise dans la base Société. Exige l'ID exact renvoyé par jarvis_interventions_lister et confirmation='oui' après accord explicite.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("client", JSONObject().put("type", "string").put("description", "Nom exact du client"))
                .put("installation", JSONObject().put("type", "string").put("description", "Nom exact de l'installation"))
                .put("intervention_id", JSONObject().put("type", "string").put("description", "Identifiant exact de l'intervention"))
                .put("confirmation", JSONObject().put("type", "string").put("description", "Oui uniquement après accord explicite")))
                .put("required", JSONArray().put("client").put("installation").put("intervention_id").put("confirmation"))))
        out.put(JSONObject()
            .put("name", "jarvis_appeler_outil")
            .put("description", "Exécute un outil JARVIS découvert avec jarvis_rechercher_outils. Transmets son nom exact et les arguments conformes à son schéma.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("nom_outil", JSONObject().put("type", "string").put("description", "Nom exact de l'outil découvert"))
                .put("arguments", JSONObject().put("type", "object").put("description", "Arguments de l'outil cible")))
                .put("required", JSONArray().put("nom_outil"))))
        for (f in fonctions.take(maxOutilsExpose)) {
            val nom = f.optString("name")
            val schema = f.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject())
            if (!schema.has("type")) schema.put("type", "object")
            out.put(JSONObject().put("name", nom).put("description", f.optString("description")).put("inputSchema", schema))
        }
        return out
    }
}
