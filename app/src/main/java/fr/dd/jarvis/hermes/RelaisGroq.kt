package fr.dd.jarvis.hermes

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.ApiException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Relais Groq pour Hermès (http://127.0.0.1:38291/groq/v1, jeton du pont).
 *
 * L'offre gratuite de Groq plafonne chaque modèle à quelques milliers de jetons par MINUTE (8 000 pour gpt-oss-120b) :
 * un tour d'agent Hermès brut (consignes, mémoire, outils, historique) les dépasse et Groq répond 413, d'où le
 * message « conversation has grown too large… /new ». Le relais remet chaque requête à la taille du budget avant de
 * l'envoyer : consignes raccourcies (début + fin conservés), historique ancien résumé, résultats d'outils tronqués,
 * seuls les outils utiles à la demande en cours (et ceux déjà utilisés) gardés, descriptions abrégées. Il utilise les
 * clés Groq de Jarvis (rotation automatique, jamais copiées dans Termux) et réessaie plus compact si Groq refuse encore.
 *
 * Quand TOUTES les clés Groq sont épuisées (quota) ou absentes, le relais ne renvoie plus d'erreur à Hermès : il
 * répond avec Gemini (toutes les clés de Jarvis, en rotation, requête complète) puis Cloudflare (requête compactée).
 * Hermès ne voit une erreur que si les trois fournisseurs sont à court.
 */
object RelaisGroq {
    /** Budget d'entrée visé (jetons estimés) pour tenir sous ~8 000 jetons/min avec la réponse. */
    private val PALIERS = listOf(
        // Premier passage plus léger : moins de prompt et d'outils à traiter à chaque tour, tout en gardant la découverte MCP.
        Palier(budget = 4800, systeme = 6500, outils = 18, desc = 130, descParam = 55, historique = 8, texte = 700, resultat = 1800),
        Palier(budget = 4000, systeme = 4800, outils = 14, desc = 100, descParam = 40, historique = 6, texte = 450, resultat = 1200),
        Palier(budget = 3200, systeme = 3000, outils = 9, desc = 75, descParam = 0, historique = 4, texte = 280, resultat = 750),
        Palier(budget = 2400, systeme = 1700, outils = 5, desc = 55, descParam = 0, historique = 2, texte = 180, resultat = 450)
    )
    private data class Palier(val budget: Int, val systeme: Int, val outils: Int, val desc: Int, val descParam: Int, val historique: Int, val texte: Int, val resultat: Int)

    /**
     * Grand contexte (Gemini : des centaines de milliers de jetons par minute, contexte d'un million) : la requête
     * d'Hermès passe presque entière — consignes, mémoire, notes, date et outils utiles avec leurs descriptions. Avec
     * Groq, ces consignes étaient coupées en leur milieu et les outils réduits à 18 descriptions de 130 caractères :
     * c'est ce qui rendait les réponses lentes (quota par minute vite atteint) et imprécises.
     */
    private val GRAND = Palier(budget = 60_000, systeme = 32_000, outils = 64, desc = 700, descParam = 220, historique = 12, texte = 4500, resultat = 9000)

    /** Requête telle qu'envoyée à Gemini (testable). */
    internal fun pourGemini(messages: JSONArray, outils: JSONArray?): Pair<JSONArray, JSONArray?> = compacter(messages, GRAND) to reduireOutils(outils, messages, GRAND)

    private fun jetons(s: String) = s.length / 3

    fun modele(app: JarvisApp) = app.store.groqModel.value.ifBlank { "llama-3.3-70b-versatile" }

    fun modeles(app: JarvisApp): String = JSONObject().put("object", "list").put("data", JSONArray()
        .put(JSONObject().put("id", modele(app)).put("object", "model").put("owned_by", "groq").put("context_window", 131072))).toString()

    /** Traite une requête chat.completions (format OpenAI) ; renvoie (code HTTP, corps JSON non streamé). */
    fun repondre(app: JarvisApp, corps: JSONObject): Pair<Int, String> {
        val messages = corps.optJSONArray("messages") ?: JSONArray()
        val outils = corps.optJSONArray("tools")
        val sortie = corps.optInt("max_tokens", corps.optInt("max_completion_tokens", 1024)).coerceIn(256, 1200)
        val forceText = corps.optString("tool_choice") == "none"
        // Gemini d'abord (réglage par défaut, si Jarvis a des clés Gemini) : requête quasi complète, un seul appel, pas
        // d'attente de quota Groq. En cas d'échec (quota, panne), Groq compacté prend le relais comme avant.
        var geminiEchoue = false
        if (app.store.relaisHermes.value != "groq" && app.llm.geminiChat.disponible()) {
            try {
                val (msgs, tls) = pourGemini(messages, outils)
                val m = runBlocking { app.llm.geminiChat.chat(msgs, tls, maxOf(sortie, 1600), forceText) }
                app.llm.noterFournisseurHermes("Gemini")
                return 200 to completion(app, m, estimer(msgs, tls))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { geminiEchoue = true }
        }
        if (!app.llm.groq.disponible()) return relais(app, messages, outils, forceText, ApiException(503, "Aucune clé Groq dans Jarvis."), geminiEchoue)
        var derniere: ApiException? = null
        // Palier de départ : le premier dont l'estimation tient dans le budget.
        val depart = PALIERS.indexOfFirst { p -> estimer(compacter(messages, p), reduireOutils(outils, messages, p)) <= p.budget }.let { if (it < 0) PALIERS.lastIndex else it }
        for (i in depart until PALIERS.size) {
            val p = PALIERS[i]
            val msgs = compacter(messages, p)
            val tls = reduireOutils(outils, messages, p)
            try {
                val m = runBlocking { app.llm.groq.chat(msgs, tls, sortie, forceText) }
                app.llm.noterFournisseurHermes("Groq")
                return 200 to completion(app, m, estimer(msgs, tls))
            } catch (e: ApiException) {
                derniere = e
                val tropGros = e.code == 413 || (e.code == 429 && (e.message ?: "").contains("tokens per minute", true) && (e.message ?: "").contains("Requested", true))
                if (!tropGros) break
            } catch (e: Exception) {
                return relais(app, messages, outils, forceText, e, geminiEchoue)
            }
        }
        return relais(app, messages, outils, forceText, derniere ?: ApiException(502, "Groq indisponible."), geminiEchoue)
    }

    /** Groq à court : Gemini puis Cloudflare (cf. Llm.relaisApresGroq) ; erreur pour Hermès seulement si tout échoue. */
    private fun relais(app: JarvisApp, messages: JSONArray, outils: JSONArray?, forceText: Boolean, origine: Exception, sansGemini: Boolean = false): Pair<Int, String> {
        // Cloudflare (Llama 3.3, contexte court) reçoit la requête compactée comme pour Groq.
        val compact = reduire(messages, if (forceText) null else outils, 1)
        return try {
            val m = runBlocking { app.llm.relaisApresGroq(messages, outils, forceText, origine, compact, sansGemini) }
            200 to completionDe(m, modele(app), estimer(messages, outils))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            val brut = (e as? ApiException)?.code ?: (origine as? ApiException)?.code ?: 502
            // Jamais 401/403 vers Hermès : pour lui ce serait « le relais refuse MA clé » (il abandonnerait ce fournisseur),
            // alors que ce sont les clés Groq/Gemini de Jarvis qui sont refusées en amont → panne passagère du fournisseur.
            val code = when (brut) { 429 -> 429; 401, 403 -> 503; in 500..599 -> brut; else -> 503 }
            code to erreur("Groq, Gemini et Cloudflare à court (relais Jarvis) : ${e.message ?: e.javaClass.simpleName}",
                if (code == 429) "rate_limit_exceeded" else "upstream_error")
        }
    }

    /** Même compactage que pour Groq (palier [niveau]) : historique, consignes (début + fin), outils utiles. */
    internal fun reduire(messages: JSONArray, outils: JSONArray?, niveau: Int): Pair<JSONArray, JSONArray?> =
        PALIERS[niveau.coerceIn(0, PALIERS.lastIndex)].let { p -> compacter(messages, p) to reduireOutils(outils, messages, p) }

    internal fun erreurJson(msg: String, code: String) = erreur(msg, code)

    /** Réponse chat.completion (format OpenAI) à partir d'un message assistant. */
    internal fun completionDe(m: JSONObject, modele: String, entree: Int): String =
        JSONObject(completion(null, m, entree)).put("model", modele).toString()

    private fun erreur(msg: String, code: String) = JSONObject().put("error", JSONObject().put("message", msg).put("type", "invalid_request_error").put("code", code)).toString()

    private fun completion(app: JarvisApp?, m: JSONObject, entree: Int): String {
        val msg = JSONObject().put("role", "assistant")
        val appels = m.optJSONArray("tool_calls")
        if (m.has("content") && !m.isNull("content")) msg.put("content", m.optString("content")) else msg.put("content", JSONObject.NULL)
        if (appels != null && appels.length() > 0) msg.put("tool_calls", appels)
        val fin = if (appels != null && appels.length() > 0) "tool_calls" else "stop"
        val sortie = jetons(msg.toString())
        return JSONObject().put("id", "chatcmpl-jarvis-" + System.nanoTime()).put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000).put("model", app?.let { modele(it) } ?: "jarvis")
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", msg).put("finish_reason", fin)))
            .put("usage", JSONObject().put("prompt_tokens", entree).put("completion_tokens", sortie).put("total_tokens", entree + sortie)).toString()
    }

    private fun estimer(messages: JSONArray, outils: JSONArray?) = jetons(messages.toString()) + jetons(outils?.toString().orEmpty())

    private fun couper(s: String, max: Int): String = if (s.length <= max) s
    else if (max < 400) s.take(max) + "…"
    else s.take((max * 0.7).toInt()) + "\n[…consignes abrégées…]\n" + s.takeLast((max * 0.3).toInt())

    /** Historique ramené au palier, en gardant les paires appel d'outil / résultat cohérentes. */
    internal fun compacterPourTest(messages: JSONArray, niveau: Int) = compacter(messages, PALIERS[niveau])
    internal fun outilsPourTest(outils: JSONArray, messages: JSONArray, niveau: Int) = reduireOutils(outils, messages, PALIERS[niveau])
    internal fun estimationPourTest(messages: JSONArray, outils: JSONArray?) = estimer(messages, outils)

    private fun compacter(messages: JSONArray, p: Palier): JSONArray {
        val src = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
        val systemes = src.filter { it.optString("role") in setOf("system", "developer") }
        val conversation = src.filter { it.optString("role") !in setOf("system", "developer") }
        val dernierUser = conversation.indexOfLast { it.optString("role") == "user" }
        // Tout ce qui suit la dernière demande (le tour en cours, avec ses appels d'outils) est gardé ; avant, les derniers messages seulement.
        val avant = if (dernierUser > 0) conversation.subList(0, dernierUser) else emptyList()
        val tour = if (dernierUser >= 0) conversation.subList(dernierUser, conversation.size) else conversation
        var garde = avant.takeLast(p.historique)
        while (garde.isNotEmpty() && garde.first().optString("role") == "tool") garde = garde.drop(1) // pas de résultat orphelin
        val out = JSONArray()
        if (systemes.isNotEmpty()) {
            val texte = systemes.joinToString("\n\n") { contenu(it) }
            out.put(JSONObject().put("role", "system").put("content", couper(texte, p.systeme)))
        }
        (garde + tour).forEachIndexed { i, m ->
            // Seuls les champs acceptés par Groq (pas de reasoning_content, metadata…).
            val c = JSONObject()
            for (k in listOf("role", "content", "tool_calls", "tool_call_id", "name")) if (m.has(k) && !m.isNull(k)) c.put(k, m.get(k))
            if (!c.has("content")) c.put("content", if (c.has("tool_calls")) JSONObject.NULL else "")
            val role = c.optString("role")
            val dansTour = i >= garde.size
            val limite = when { role == "tool" -> p.resultat; dansTour && role == "user" -> 6000; else -> p.texte }
            val t = contenu(c)
            if (c.opt("content") is String || c.opt("content") is JSONArray) c.put("content", if (t.length > limite) t.take(limite) + "…" else t)
            out.put(c)
        }
        return out
    }

    private fun contenu(m: JSONObject): String = when (val c = m.opt("content")) {
        is String -> c
        is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }.filter { it.optString("type") in setOf("text", "input_text") }.joinToString("\n") { it.optString("text") }
        else -> ""
    }

    private fun mots(s: String) = Regex("[\\p{L}\\p{N}]{4,}").findAll(s.lowercase()).map { it.value }.toSet()

    /** Familles d'outils Jarvis concernées par la demande (préfixes), même si ses mots ne figurent pas dans leurs descriptions. */
    internal fun domaines(demande: String): Set<String> {
        val t = demande.lowercase()
        val out = mutableSetOf<String>()
        if (Regex("serveur|\\bssh\\b|debian|docker|conteneur|systemctl|\\bservices?\\b|\\bbot\\b|plex|ollama|discord|home ?assistant|\\blogs?\\b|red[ée]marr|\\bapt\\b|mise à jour du serveur").containsMatchIn(t))
            out += listOf("ssh_", "serveur_")
        if (Regex("home ?assistant|domotique|\\bha\\b|lumi[èe]re|volet|chauffage|thermostat|po[êe]le|capteur|entit[ée]|automatisation|sc[èe]ne|prise").containsMatchIn(t)) out += "ha_"
        if (Regex("plex|films?\\b|s[ée]ries? t[ée]l[ée]|biblioth[èe]que (de )?films").containsMatchIn(t)) out += "plex"
        if (Regex("discord|\\bbot\\b|salon|serveur discord").containsMatchIn(t)) out += "discord"
        if (Regex("freebox|\\bbox\\b").containsMatchIn(t)) out += listOf("freebox", "box_")
        if (Regex("r[ée]seau|wi-?fi|\\bip\\b|imprimante|appareils? connect").containsMatchIn(t)) out += listOf("reseau_", "appareil", "ip_publique", "imprim")
        return out
    }

    /** Garde les outils pertinents pour la demande en cours (et ceux déjà appelés), descriptions abrégées. */
    private fun reduireOutils(outils: JSONArray?, messages: JSONArray, p: Palier): JSONArray? {
        if (outils == null || outils.length() == 0) return null
        val src = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
        val dernierUser = src.indexOfLast { it.optString("role") == "user" }
        fun appels(l: List<JSONObject>) = l.flatMap { m -> m.optJSONArray("tool_calls")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("function")?.optString("name") } }.orEmpty() }.toSet()
        // Outils appelés pendant le tour EN COURS (indispensables) ; ceux des tours précédents gardés seulement en bonus.
        val tourEnCours = appels(if (dernierUser >= 0) src.subList(dernierUser, src.size) else src)
        val avant = appels(if (dernierUser > 0) src.subList(0, dernierUser).takeLast(p.historique) else emptyList())
        val demande = mots(if (dernierUser >= 0) contenu(src[dernierUser]) else "")
        val domaines = domaines(if (dernierUser >= 0) contenu(src[dernierUser]) else "")
        val contexte = mots(src.filter { it.optString("role") in setOf("user", "tool") }.takeLast(3).joinToString(" ") { contenu(it).take(1500) })
        val liste = (0 until outils.length()).mapNotNull { outils.optJSONObject(it) }
        val classes = liste.sortedByDescending { t ->
            val f = t.optJSONObject("function") ?: JSONObject()
            val nom = f.optString("name")
            val m = mots(nom.replace('_', ' ') + " " + f.optString("description").take(400))
            val canon = nom.removePrefix("mcp_jarvis_")
            val score = 3 * m.count { it in demande } + m.count { it in contexte } +
                (if (domaines.any { canon.startsWith(it) || canon == it }) 40 else 0)
            when {
                nom in tourEnCours -> 1000 + score
                // Grand contexte : les outils propres d'Hermès (terminal, web, mémoire, compétences, automatismes,
                // sous-agents…) et l'agent téléphone Jarvis restent toujours disponibles, quelle que soit la formulation.
                p === GRAND && (!nom.startsWith("mcp_") || nom in setOf("mcp_jarvis_agent", "mcp_jarvis_notifier")) -> 300 + score
                // Outils de découverte d'Hermès : gardent l'accès au reste du catalogue.
                canon in setOf("tool_search", "tool_describe", "tool_call", "jarvis_rechercher_outils", "jarvis_appeler_outil") -> 500 + score
                nom in avant -> 20 + score
                else -> score
            }
        }.take(p.outils)
        val out = JSONArray()
        classes.forEach { t ->
            val c = JSONObject(t.toString())
            val f = c.optJSONObject("function") ?: return@forEach
            f.put("description", f.optString("description").take(p.desc))
            f.optJSONObject("parameters")?.optJSONObject("properties")?.let { props ->
                props.keys().forEach { k ->
                    props.optJSONObject(k)?.let { o -> if (p.descParam == 0) o.remove("description") else o.put("description", o.optString("description").take(p.descParam)) }
                }
            }
            out.put(c)
        }
        return out
    }
}
