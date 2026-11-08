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
        /** Relais Groq compact pour Hermès : (code HTTP, corps JSON chat.completion). */
        fun groq(corps: JSONObject): Pair<Int, String> = 404 to """{"error":{"message":"relais indisponible"}}"""
        fun groqModeles(): String = """{"object":"list","data":[]}"""
        /** Relais Ollama (serveur de la maison) pour Hermès : (code HTTP, corps JSON chat.completion). */
        fun ollama(corps: JSONObject): Pair<Int, String> = 404 to """{"error":{"message":"relais indisponible"}}"""
        fun ollamaModeles(): String = """{"object":"list","data":[]}"""
        /** Mémoire d'Hermès reçue (MEMORY.md + USER.md). */
        fun memoireHermes(texte: String) {}
        /** Mémoire de Jarvis à recopier dans Hermès. */
        fun memoireJarvis(): String = ""
        /** Capacités propres à Hermès (skills ajoutés, MCP, plugins, automatismes), JSON. */
        fun capacitesHermes(json: String) {}
    }

    /** Outils de la conversation en cours (avec SES confirmations) ; sinon une boîte d'outils autonome. */
    @Volatile var boiteActive: ToolBox? = null
    /** Ligne d'état de la conversation en cours (« Hermès → Jarvis : … »), posée par Hermes.appeler. */
    @Volatile var statutActif: ((String) -> Unit)? = null
    /** Médias (images, vidéos, fichiers) produits par les outils pendant la demande d'Hermès en cours. */
    @Volatile var mediasActifs: MutableList<fr.dd.jarvis.ai.Medias.Media>? = null

    /** Outils jamais exposés à Hermès (gestion d'Hermès lui-même → pas de boucle). */
    private val EXCLUS = setOf("hermes", "hermes_deleguer")

    /** Dernières lignes de progression envoyées par le script d'installation. */
    val progression = androidx.compose.runtime.mutableStateListOf<String>()
    @Volatile var dernierSignal = 0L

    fun url() = "http://127.0.0.1:$PORT/mcp"

    private class FournisseurJarvis(val app: JarvisApp) : Fournisseur {
        override fun groq(corps: JSONObject) = RelaisGroq.repondre(app, corps)
        override fun groqModeles() = RelaisGroq.modeles(app)
        override fun ollama(corps: JSONObject) = fr.dd.jarvis.ai.Ollama.relais(app, corps)
        override fun ollamaModeles() = fr.dd.jarvis.ai.Ollama.modelesJson(app)
        override fun memoireHermes(texte: String) { runCatching { app.memo.majHermes(texte) } }
        override fun memoireJarvis(): String = runCatching { app.memo.pourHermes() }.getOrDefault("")
        override fun capacitesHermes(json: String) { runCatching { app.memo.majCapacitesHermes(json) } }
        private val defaut by lazy { ToolBox(app) { _, force -> !force } }
        private fun boite() = boiteActive ?: defaut
        override fun jeton() = app.store.mcpJeton.value
        override fun outils(): JSONArray = convertirOutils(boite().schemaAll())
        override fun appeler(nom: String, args: JSONObject): Pair<String, Boolean> =
            appelerOutil(nom, args).also { (texte, _) ->
                // Images, vidéos et fichiers créés : notés pour être affichés même si Hermès les reformule ou les oublie.
                runCatching { mediasActifs?.let { l -> synchronized(l) { l.addAll(fr.dd.jarvis.ai.Medias.extraire(texte)) } } }
            }

        private fun appelerOutil(nom: String, args: JSONObject): Pair<String, Boolean> {
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
                    // Une demande de coordonnées d’installation est une opération métier locale :
                    // ne jamais proposer SSH/shell comme candidat, même si leurs descriptions parlent de fichiers.
                    val demandeGpsInstallation = termes.any { it in setOf("gps", "coordonnees", "latitude", "longitude", "geolocalisation", "localisation", "point") } &&
                        termes.any { it.contains("installation") || it == "site" || it == "client" }
                    if (demandeGpsInstallation && (nomOutil.startsWith("ssh_") || nomOutil == "run_shell" || nomOutil.contains("shell", ignoreCase = true))) continue
                    val demandeSuppression = termes.any { it in setOf("supprimer", "suppression", "effacer", "delete", "remove", "retirer", "enlever", "annuler") }
                    val demandeSuppressionIntervention = termes.any { it.contains("intervention") } && demandeSuppression
                    val demandeSuppressionInstallation = termes.any { it.contains("installation") || it == "site" || it == "centrale" || it == "toiture" } && demandeSuppression
                    val demandeSuppressionClient = termes.any { it.contains("client") || it.contains("fiche") } && demandeSuppression && !demandeSuppressionInstallation
                    if (demandeSuppressionInstallation && nomOutil == "client_supprimer") continue
                    val score = termes.count { texte.contains(it) } +
                        (if (demandeSuppressionIntervention && nomOutil in setOf("interventions_liste", "intervention_supprimer")) 1000 else 0) +
                        (if (demandeSuppressionClient && nomOutil == "client_supprimer") 1000 else 0) +
                        (if (demandeSuppressionInstallation && nomOutil == "installation_supprimer") 1500 else 0) +
                        (if (demandeGpsInstallation && nomOutil == "client_ecrire") 1200 else 0) +
                        (if (demandeGpsInstallation && nomOutil == "societe_liste") 900 else 0) +
                        (if (demandeGpsInstallation && nomOutil == "clients_gps") 400 else 0)
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
            // Hermès les voit sous les noms mcp_jarvis_agent et mcp_jarvis_notifier.
            if (nomCanonique == "agent" || nomCanonique == "jarvis_agent") return agentJarvis(args)
            if (nomCanonique == "notifier" || nomCanonique == "jarvis_notifier") return notifier(args)
            // Alias directs de secours : toujours exposés, indépendants de la recherche du catalogue.
            val aliasIntervention = when (nomCanonique) {
                "jarvis_interventions_lister" -> "interventions_liste"
                "jarvis_intervention_supprimer" -> "intervention_supprimer"
                "jarvis_client_supprimer" -> "client_supprimer"
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
        /** Hermès confie une mission à Jarvis : son cerveau complet (raccourcis fiables + outils natifs) l'exécute. */
        private fun agentJarvis(args: JSONObject): Pair<String, Boolean> {
            val demande = args.optString("demande").ifBlank { args.optString("consigne") }.trim()
            if (demande.isBlank()) return "Donne la consigne complète à exécuter dans « demande »." to true
            val contexte = args.optString("contexte").trim()
            statutActif?.invoke("🪽 Hermès → 🤖 Jarvis : " + demande.take(70))
            val texte = if (contexte.isBlank()) demande else "$demande\n\n(Contexte fourni par Hermès : ${contexte.take(3000)})"
            val res = runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(170_000) {
                        fr.dd.jarvis.ai.Brain(app.llm, boite(), app) { app.store.userName.value }
                            .reply(listOf(fr.dd.jarvis.ai.ChatMsg(true, texte)), pourHermes = true) { s -> statutActif?.invoke("🤖 Jarvis : $s") }
                    }
                }
            }
            statutActif?.invoke("🪽 Hermès reprend…")
            return res.fold(
                { r -> if (r == null) "⏱️ Jarvis n'a pas terminé à temps : découpe la mission en étapes plus courtes." to true else r to false },
                { e -> "❌ Jarvis : ${e.message ?: e.javaClass.simpleName}" to true }
            )
        }

        /** Message d'Hermès à l'utilisateur (fin d'automatisme, tâche longue) : notification + conversation Jarvis. */
        private fun notifier(args: JSONObject): Pair<String, Boolean> {
            val message = args.optString("message").trim()
            if (message.isBlank()) return "Le champ « message » est vide." to true
            val titre = args.optString("titre").trim().ifBlank { "JARVIS" }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                app.chatAjouter(fr.dd.jarvis.ai.ChatMsg(false, "🔔 **$titre**\n\n$message"))
            }
            val ok = fr.dd.jarvis.work.SuiviTaches.bloquees(app) == null
            fr.dd.jarvis.work.SuiviTaches.termine(app, 7320 + (System.currentTimeMillis() % 200).toInt() * 2, titre.take(60), message)
            return (if (ok) "✅ Message envoyé à l'utilisateur (notification + conversation Jarvis)." else "✅ Message ajouté à la conversation Jarvis (notifications non autorisées).") to false
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

    /** Réponse chat.completion → flux SSE équivalent (un bloc de contenu / d'appels d'outils, puis la fin). */
    internal fun enFlux(c: JSONObject): String {
        val choix = c.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        val msg = choix.optJSONObject("message") ?: JSONObject()
        fun bloc(delta: JSONObject, fin: Any?) = "data: " + JSONObject().put("id", c.optString("id")).put("object", "chat.completion.chunk")
            .put("created", c.optLong("created")).put("model", c.optString("model"))
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", fin ?: JSONObject.NULL))) + "\n\n"
        val delta = JSONObject()
        if (msg.has("content") && !msg.isNull("content")) delta.put("content", msg.optString("content"))
        msg.optJSONArray("tool_calls")?.let { a ->
            val avecIndex = JSONArray()
            for (i in 0 until a.length()) avecIndex.put(JSONObject(a.getJSONObject(i).toString()).put("index", i))
            delta.put("tool_calls", avecIndex)
        }
        val fin = "data: " + JSONObject().put("id", c.optString("id")).put("object", "chat.completion.chunk").put("created", c.optLong("created"))
            .put("model", c.optString("model")).put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", JSONObject()).put("finish_reason", choix.optString("finish_reason", "stop"))))
            .put("usage", c.optJSONObject("usage") ?: JSONObject()) + "\n\n"
        return bloc(JSONObject().put("role", "assistant"), null) + bloc(delta, null) + fin + "data: [DONE]\n\n"
    }

    private fun repondre(s: Socket, code: Int, corps: String = "", type: String = "application/json", extra: Map<String, String> = emptyMap()) {
        val b = corps.toByteArray(Charsets.UTF_8)
        val statut = when (code) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 503 -> "Service Unavailable"; else -> "Error" }
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
            r.chemin.trimEnd('/') == "/health" && r.methode == "GET" -> {
                repondre(s, 200, JSONObject()
                    .put("ok", true)
                    .put("port", PORT)
                    .put("lastProgressAt", dernierSignal)
                    .put("progressCount", progression.size).toString())
            }
            r.chemin.trimEnd('/') == "/progres" && r.methode == "POST" -> {
                val ligne = r.corps.trim().take(300)
                if (ligne.isEmpty()) {
                    repondre(s, 400, """{"error":"empty_progress"}""")
                } else {
                    dernierSignal = System.currentTimeMillis()
                    f.progres(ligne)
                    repondre(s, 200, """{"accepted":true}""")
                }
            }
            // Mémoire partagée : le hook « jarvis-memoire » d'Hermès envoie la sienne et recopie celle de Jarvis.
            r.chemin.trimEnd('/') == "/memoire/hermes" && r.methode == "POST" -> { f.memoireHermes(r.corps); repondre(s, 200, """{"ok":true}""") }
            r.chemin.trimEnd('/') == "/memoire/jarvis" && r.methode == "GET" -> repondre(s, 200, f.memoireJarvis(), type = "text/plain")
            r.chemin.trimEnd('/') == "/memoire/hermes-capacites" && r.methode == "POST" -> { f.capacitesHermes(r.corps); repondre(s, 200, """{"ok":true}""") }
            r.chemin.trimEnd('/') == "/groq/v1/models" && r.methode == "GET" -> repondre(s, 200, f.groqModeles())
            r.chemin.trimEnd('/') == "/groq/v1/chat/completions" && r.methode == "POST" -> {
                val corps = runCatching { JSONObject(r.corps) }.getOrElse { return@use repondre(s, 400, """{"error":{"message":"JSON invalide"}}""") }
                val (code, rep) = f.groq(corps)
                if (code == 200 && corps.optBoolean("stream", false)) repondre(s, 200, enFlux(JSONObject(rep)), type = "text/event-stream")
                else repondre(s, code, rep)
            }
            r.chemin.trimEnd('/') == "/ollama/v1/models" && r.methode == "GET" -> repondre(s, 200, f.ollamaModeles())
            r.chemin.trimEnd('/') == "/ollama/v1/chat/completions" && r.methode == "POST" -> {
                val corps = runCatching { JSONObject(r.corps) }.getOrElse { return@use repondre(s, 400, """{"error":{"message":"JSON invalide"}}""") }
                val (code, rep) = f.ollama(corps)
                if (code == 200 && corps.optBoolean("stream", false)) repondre(s, 200, enFlux(JSONObject(rep)), type = "text/event-stream")
                else repondre(s, code, rep)
            }
            r.methode == "GET" -> repondre(s, 405, "", extra = mapOf("Allow" to "GET, POST, DELETE"))
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

    /** Texte d'un résultat d'outil ; un échec est marqué en tête (❌ ÉCHEC) au lieu du drapeau isError. */
    internal fun texteResultat(texte: String, erreur: Boolean): String {
        val t = texte.take(30_000)
        if (!erreur) return t
        return if (t.trimStart().startsWith("❌") || t.trimStart().startsWith("⚠")) "ÉCHEC DE L'OUTIL (le serveur Jarvis fonctionne) — $t"
        else "❌ ÉCHEC DE L'OUTIL (le serveur Jarvis fonctionne) — $t"
    }

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
                    "SOURCE DE VÉRITÉ CONFIGURATION : les réglages de fournisseurs, modèles, clés et modes de Jarvis sont ceux enregistrés dans l'application Jarvis ; ne modifie pas directement config.yaml ni les variables de Termux pour contourner ces réglages. Utilise les outils de configuration Jarvis et signale toute divergence au lieu de créer une seconde configuration concurrente. " +
                    "COFFRE OBSIDIAN — DESTINATION OBLIGATOIRE : le dossier sélectionné dans Réglages › Comptes › Obsidian est la racine unique de tous les fichiers et dossiers que l’utilisateur demande de créer/ranger dans son coffre. Utilise obsidian_fichiers, obsidian_ecrire ou creer_fichier selon le besoin. Les documents générés vont dans JARVIS/Documents, les images dans JARVIS/Images, les vidéos IA dans JARVIS/Vidéos IA et les vidéos TikTok dans JARVIS/Vidéos TikTok ; les notes/fiches restent dans leurs dossiers métier dédiés. N’utilise jamais stockage_telephone, un chemin /storage/emulated/0, Download/Téléchargements, ni un shell/ADB/Shizuku pour créer un dossier ou fichier qui appartient au coffre. Ne crée pas un deuxième dossier Jarvis à côté du coffre. Si le coffre est inaccessible ou en lecture seule, arrête l’écriture et rapporte l’erreur exacte ; ne bascule pas silencieusement vers Téléchargements ou un autre emplacement. Une copie privée temporaire de l’application pour l’aperçu/partage n’est pas une destination de classement. " +
                    "TÉLÉPHONE : Jarvis est le contrôleur Android et Hermès le cerveau principal. Pour observer ce qui se passe, appelle phone_action action=watch_screen : tu reçois l'écran accessible et le journal des changements d'applications/fenêtres/interactions détectés en arrière-plan. Appelle inspect_screen/watch_screen avant une action, exécute une action ciblée, puis réinspecte pour vérifier son résultat. Utilise list_apps si nécessaire et open_app pour naviguer. Le journal événementiel est actualisé par Android en temps réel lorsque le service d'accessibilité est actif ; Hermès le consulte à la demande (ce n'est pas un flux vidéo continu). Les champs mot de passe sont masqués et aucun texte saisi n'est conservé dans le journal. L'arbre d'accessibilité n'est pas une capture d'écran : pour voir les pixels, il faudra une capture MediaProjection avec autorisation Android et un modèle multimodal compatible. " +
                    "SOCIÉTÉ / CLIENTS / INTERVENTIONS : les données métier ne sont PAS stockées dans GitHub, Termux ni dans un fichier que l’utilisateur doit t’indiquer. La base de vérité est la base Société locale de l’application Jarvis (stockage privé, fichier societe/data.json avec sauvegarde data.json.bak) ; Obsidian/Société/Clients et Société/Interventions contient des copies/rapports complémentaires. Si l’utilisateur demande la liste, la recherche ou l’historique des clients, appelle immédiatement societe_liste avec quoi=clients (et filtre si demandé). Pour un historique d’intervention, identifie d’abord le client puis appelle interventions_liste. Ne dis jamais qu’il n’existe aucune fiche sans avoir interrogé l’outil et rapporté exactement son résultat. Ne demande jamais à l’utilisateur où se trouvent les données : Jarvis connaît et expose déjà cette base. Si l’outil échoue, rapporte l’erreur réelle sans conclure que la base est vide. N’utilise pas une recherche Obsidian comme substitut à la base Société. " +
                    "RÈGLE GPS PRIORITAIRE : si la demande mentionne un point/coordonnées GPS et un client, une fiche client ou une installation (ex. « ajouter des points GPS à Clairotte »), tu DOIS agir dans la base Société avec les outils societe_liste puis client_ecrire. Ces deux outils sont exposés directement dans MCP. Ne réponds jamais que tu n’as pas accès aux fiches, ne demande jamais de les importer dans la bibliothèque, n’utilise jamais document_lire et ne propose jamais de créer une note de remplacement. Appelle réellement les outils avant de répondre. " +
                    "GPS D’INSTALLATION : opération exclusivement métier et locale à la base Société Jarvis. N’utilise JAMAIS ssh_commande, ssh_fichier, ssh_script, run_shell, Termux, ADB, Shizuku, stockage_telephone, obsidian_fichiers ni un chemin système pour ajouter/modifier ces coordonnées ; le serveur Debian et SSH n’ont aucun rôle dans cette opération. Même si ces outils sont visibles, ignore-les pour toute demande GPS/client/installation. Ne cherche JAMAIS un fichier Markdown, un contact Android ou un chemin Obsidian. Interroge d’abord societe_liste (quoi=clients, filtre sur le nom fourni) pour retrouver le nom exact du client dans la base Société ; si nécessaire, appelle client_fiche pour vérifier le nom exact de l’installation. Ensuite appelle obligatoirement client_ecrire avec nom=nom exact du client, site_nom=nom exact de l’installation et site_gps=coordonnées originales fournies, sans les reformater ni les recalculer. Ne modifie pas l’adresse ni les autres champs. N’annonce la réussite que si client_ecrire confirme l’enregistrement et la relecture du GPS. Si le client ou l’installation reste ambigu, demande uniquement la précision manquante. " +
                    "MUSIQUE : pour rechercher ET lancer un titre, utilise musique_rechercher (requete, application facultative) ; cette fonction utilise le contrôleur multi-applications Android et vérifie la session de lecture. Morphe est pris en charge en plus de Spotify, YouTube Music, Deezer, Amazon Music et VLC. Ne réponds pas « introuvable » avant d'avoir consulté les applications installées et tenté l'application musicale appropriée. " +
                    "IMPORTANT pour les interventions photovoltaïques : les noms exacts des outils MCP sont interventions_liste, intervention_supprimer et intervention_modifier (sans préfixe). Ils sont exposés par le pont local Jarvis. " +
                    "Si ces outils ne sont pas visibles, utilise tool_search avec les termes exacts interventions_liste et intervention_supprimer ; ne remplace jamais cette recherche par une recherche de PDF dans Obsidian. " +
                    "SOCIÉTÉ / CLIENTS : la suppression d'une fiche client est une opération autorisée par Jarvis. Si l'utilisateur demande de supprimer un client, utilise client_supprimer ; ne refuse jamais au nom d'une règle générale de conservation et n'invente pas une interdiction. L'outil vérifie les devis/factures liés, demande lui-même une confirmation explicite avant l'action irréversible, supprime la fiche de la base et sa copie Obsidian, puis vérifie le résultat. Si des documents comptables sont liés, explique le blocage précis renvoyé par l'outil ; sinon laisse l'outil exécuter après confirmation. " +
                    "Pour supprimer une intervention, utilise obligatoirement interventions_liste pour obtenir son ID, puis intervention_supprimer avec cet ID et confirmation='oui' après accord explicite. " +
                    "N'utilise jamais les outils génériques du coffre Obsidian pour supprimer une intervention et n'appelle jamais intervention_enregistrer pour simuler une suppression. " +
                    "DISCORD : les outils discord_role_creer/modifier/supprimer/position, discord_role_attribuer et discord_salon_permission sont des actions d'écriture et d'administration, pas de simples outils de lecture. Utilise-les quand la demande porte sur une modification autorisée. discord_audit_serveur est volontairement en lecture seule uniquement pour l'inventaire ; cela ne limite pas les autres outils Discord. Les droits restent ceux du bot et de sa hiérarchie de rôles. " +
                    "Les actions sensibles (SMS, appels, suppressions, administration Discord) doivent respecter les confirmations de l'utilisateur."))
            "ping" -> ok(JSONObject())
            "tools/list" -> ok(JSONObject().put("tools", f.outils()))
            "tools/call" -> {
                val (texte, erreur) = f.appeler(p.optString("name"), p.optJSONObject("arguments") ?: JSONObject())
                // Jamais isError=true : le disjoncteur MCP d'Hermès compte ces réponses comme des pannes du serveur et, après
                // 3 d'affilée (« entité introuvable », appareil éteint…), coupe TOUS les outils Jarvis ~60 s (« serveur
                // injoignable »). L'échec reste explicite dans le texte, que le modèle lit.
                ok(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", texteResultat(texte, erreur))))
                    .put("isError", false))
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
            // Capacités téléphone et musique : Hermès doit les voir directement sans dépendre
            // d'une découverte différée ou d'un catalogue tronqué à 80 outils.
            "phone_action", "piloter_telephone", "voir_ecran", "phone_health_report", "client_supprimer", "societe_liste", "client_fiche", "client_ecrire", "clients_gps", "musique_rechercher", "musique_etat", "musique_commander",
            "jouer_musique", "applis_musique", "controle_media",
            "reglage_jarvis", "hermes",
            // Les outils métier sensibles doivent rester visibles même lorsque le catalogue
            // dépasse le budget transmis au modèle (80 outils).
            "interventions_liste", "intervention_supprimer", "intervention_modifier", "installation_supprimer",
            "intervention_enregistrer", "interventions_synthese",
            // Gestion des extensions Hermès : garder la découverte des skills/plugins/MCP accessible.
            "gerer_extension_hermes",
            // Discord : exposer en priorité les outils de lecture ET d'administration.
            "discord_guildes", "discord_roles", "discord_salons", "discord_audit_serveur",
            "discord_role_creer", "discord_role_modifier", "discord_role_supprimer",
            "discord_role_position", "discord_role_attribuer", "discord_salon_permission", "discord_message_envoyer",
            // Réseau et accès distant : garder les diagnostics, la découverte et les outils SSH visibles pour Hermès.
            // Freebox (redirections, VPN, Wi-Fi…) et recherche globale de fichiers/dossiers : toujours visibles.
            "freebox", "stockage_telephone", "obsidian_fichiers",
            "routeur_diagnostiquer", "reseau_infos", "ip_publique", "reseau_scanner", "imprimantes_trouver",
            "appareil_sonder", "appareil_page", "ssh_commande", "ssh_fichier", "ssh_script"
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
        // L'équipe d'Hermès : l'agent téléphone (Jarvis) et le canal vers l'utilisateur. Toujours en tête de liste.
        out.put(JSONObject()
            .put("name", "agent")
            .put("description", "AGENT TÉLÉPHONE JARVIS — à privilégier pour toute action ou recherche sur le téléphone et les données de l'utilisateur : fichiers et dossiers (coffre Obsidian + stockage), clients/devis/factures/interventions, agenda, mails, SMS, appels, contacts, musique, Freebox, domotique, position GPS, images, vidéos, réglages. Donne une consigne complète et autonome en français (une étape ou un petit enchaînement) ; Jarvis l'exécute avec ses outils natifs et renvoie le résultat réel. Ne cherche jamais ces données dans ton propre système de fichiers.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("demande", JSONObject().put("type", "string").put("description", "Consigne à exécuter, formulée comme l'utilisateur la dirait à Jarvis (ex. « trouve le dossier coloriage pompier », « ajoute un rendez-vous demain 9h chez Martin »)"))
                .put("contexte", JSONObject().put("type", "string").put("description", "Informations utiles déjà connues (facultatif)")))
                .put("required", JSONArray().put("demande"))))
        out.put(JSONObject()
            .put("name", "notifier")
            .put("description", "Envoie un message à l'utilisateur sur son téléphone (notification + conversation Jarvis). OBLIGATOIRE à la fin de chaque automatisme (cronjob) et de chaque tâche longue pour lui livrer le résultat.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("titre", JSONObject().put("type", "string").put("description", "Titre court (ex. « Briefing du matin »)"))
                .put("message", JSONObject().put("type", "string").put("description", "Contenu à livrer, concis et lisible")))
                .put("required", JSONArray().put("message"))))
        out.put(JSONObject()
            .put("name", "jarvis_rechercher_outils")
            .put("description", "Recherche dans le catalogue complet des outils JARVIS, y compris ceux non affichés dans la liste courte. À utiliser si un outil métier est absent ou pour trouver une fonction par domaine.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("requete", JSONObject().put("type", "string").put("description", "Nom ou mots-clés du domaine recherché"))
                .put("limite", JSONObject().put("type", "integer").put("description", "Nombre maximal de résultats (1 à 20)")))
                .put("required", JSONArray().put("requete"))))
        // Alias canoniques toujours exposés : pas de dépendance à tool_search pour ces opérations métier.
        // Outils GPS Société exposés explicitement : ne jamais dépendre du tri/troncage
        // du catalogue complet, ni demander à l’utilisateur d’importer ses fiches Obsidian.
        out.put(JSONObject()
            .put("name", "societe_liste")
            .put("description", "Recherche et liste les fiches clients déjà enregistrées dans la base Société locale de Jarvis. Pour toute demande GPS sur un client, commence obligatoirement ici avec quoi=clients et filtre=nom du client. Ces fiches ne sont pas des documents à importer.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("quoi", JSONObject().put("type", "string").put("enum", JSONArray().put("clients").put("devis").put("factures").put("employes").put("tarifs")))
                .put("filtre", JSONObject().put("type", "string").put("description", "Nom du client ou filtre facultatif")))
                .put("required", JSONArray().put("quoi"))))
        out.put(JSONObject()
            .put("name", "client_ecrire")
            .put("description", "OUTIL OBLIGATOIRE pour créer OU MODIFIER une fiche client/installation dans la base Société locale de Jarvis. Pour ajouter un GPS : après societe_liste, appelle cet outil avec nom=nom exact du client, site_nom=installation exacte si nécessaire, site_gps=coordonnées originales. Les fiches sont déjà dans la base Société ; ne demande jamais document_lire, importation ou création d’une nouvelle note. N’utilise aucun outil de fichiers.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("nom", JSONObject().put("type", "string").put("description", "Nom exact du client existant"))
                .put("site_nom", JSONObject().put("type", "string").put("description", "Nom exact de l’installation ; facultatif si une seule installation"))
                .put("site_gps", JSONObject().put("type", "string").put("description", "Coordonnées GPS originales, décimales, DMS ou lien Google Maps ; ne pas recalculer"))
                .put("site_adresse", JSONObject().put("type", "string").put("description", "Adresse de l’installation, uniquement si l’utilisateur la fournit ou demande ma position")))
                .put("required", JSONArray().put("nom"))))
        out.put(JSONObject()
            .put("name", "client_supprimer")
            .put("description", "Supprime une fiche client de la base Société Jarvis et sa copie Obsidian. L'outil demande une confirmation explicite et bloque uniquement si des devis/factures sont encore liés. La suppression n'est pas interdite par défaut.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("nom", JSONObject().put("type", "string").put("description", "Nom exact du client à supprimer")))
                .put("required", JSONArray().put("nom"))))
        out.put(JSONObject()
            .put("name", "interventions_liste")
            .put("description", "Consulte l’historique des interventions dans la base Société Jarvis. Obligatoire avant toute suppression ; renvoie les ID exacts.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("client", JSONObject().put("type", "string").put("description", "Nom du client"))
                .put("installation", JSONObject().put("type", "string").put("description", "Installation exacte, facultative ; vide pour toutes")))
                .put("required", JSONArray().put("client"))))
        out.put(JSONObject()
            .put("name", "intervention_supprimer")
            .put("description", "Supprime une intervention précise de la base Société après confirmation explicite. Requiert l’ID exact obtenu avec interventions_liste.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("client", JSONObject().put("type", "string").put("description", "Nom du client"))
                .put("installation", JSONObject().put("type", "string").put("description", "Nom exact de l’installation"))
                .put("intervention_id", JSONObject().put("type", "string").put("description", "ID exact de l’intervention"))
                .put("confirmation", JSONObject().put("type", "string").put("description", "Doit être oui après confirmation explicite")))
                .put("required", JSONArray().put("client").put("installation").put("intervention_id").put("confirmation"))))
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
        val nomsDejaExpose = mutableSetOf<String>()
        for (i in 0 until out.length()) nomsDejaExpose += out.optJSONObject(i)?.optString("name").orEmpty()
        for (f in fonctions.take(maxOutilsExpose)) {
            val nom = f.optString("name")
            if (nom in nomsDejaExpose) continue
            val schema = f.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject())
            if (!schema.has("type")) schema.put("type", "object")
            out.put(JSONObject().put("name", nom).put("description", f.optString("description")).put("inputSchema", schema))
            nomsDejaExpose += nom
        }
        return out
    }
}
