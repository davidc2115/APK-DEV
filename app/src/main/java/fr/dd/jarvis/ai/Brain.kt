package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject

data class ChatMsg(val fromUser: Boolean, val text: String, val ts: Long = System.currentTimeMillis())

/**
 * Cerveau principal (Groq) : reçoit la conversation, choisit les outils, les exécute (les agents Gemini
 * travaillent à l'intérieur des outils) puis répond.
 */
class Brain(
    private val llm: Llm,
    private val tools: ToolBox,
    private val app: fr.dd.jarvis.JarvisApp,
    private val userName: () -> String
) {
    suspend fun reply(history: List<ChatMsg>, onStatus: (String) -> Unit): String {
        val recent = history.takeLast(4).filter { it.fromUser }.joinToString(" ") { it.text }
        val societeOn = fr.dd.jarvis.societe.SocieteTools.concerned(recent)
        val planningClientsSync = Regex("(planning|agenda).{0,100}(fiche|client)|(fiche|client).{0,100}(planning|agenda)", RegexOption.IGNORE_CASE).containsMatchIn(recent) &&
            Regex("analys|ajout|compl[eé]t|rendez|enregistr|synchron|mettre à jour", RegexOption.IGNORE_CASE).containsMatchIn(recent)
        // Recherche d'imprimante / scan du réseau : toujours par l'outil, jamais par le modèle (qui inventait des appareils).
        val dernier = history.lastOrNull { it.fromUser }?.text.orEmpty().lowercase()
        val outilDirect = when {
            Regex("imprimante").containsMatchIn(dernier) && Regex("cherch|trouv|d[ée]tect|recherch|list|scan|quelle|o[uù] est|y a.t.il").containsMatchIn(dernier)
                && !Regex("imprime[rz]? (ce|le|la|mon|ma|un|une|cette)|lance l.impression").containsMatchIn(dernier) -> "imprimantes_trouver"
            Regex("(scan|analys|d[ée]tect|list|cherch|affich).{0,25}(r[ée]seau|wi-?fi|appareils)|appareils connect").containsMatchIn(dernier) -> "reseau_scanner"
            else -> null
        }
        positionDirecte(dernier, history, onStatus)?.let { return it }
        if (outilDirect != null) {
            onStatus("⚙️ $outilDirect…")
            val args = JSONObject(); if (dernier.contains("serveur")) args.put("via", "serveur")
            return try { tools.execute(outilDirect, args).text } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "❌ ${e.message}" }
        }
        // Hermès (Termux) est le cerveau principal quand il est installé : commandes rapides → Groq, le reste → Hermès.
        val choix = fr.dd.jarvis.hermes.Routeur.choisir(app, history.lastOrNull { it.fromUser }?.text.orEmpty())
        if (choix.cible == fr.dd.jarvis.hermes.Routeur.Cible.HERMES) {
            val r = fr.dd.jarvis.hermes.Hermes.repondre(app, history, tools, userName(), onStatus)
            if (r != null) return r
            onStatus("Hermès indisponible : Groq prend le relais…")
        }
        onStatus("🧠 Analyse de la demande…")
        val all = tools.schemaAll()
        val plan = try { Analyseur.analyser(app, history, all) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        // Pour une consigne de chauffage, le planificateur ne doit jamais retirer les outils nécessaires :
        // il faut pouvoir retrouver les correspondances dans Obsidian, vérifier les entités HA puis agir.
        val pilotageChauffage = Regex("po[eê]les?|poils?|pellets?|granul[eé]s?|chauffage|thermostats?|climate\\.|temp[eé]rature|consigne|degr[eé]s?", RegexOption.IGNORE_CASE).containsMatchIn(recent) &&
            Regex("r[eé]gl|mettre|passer|fixer|monter|baisser|augment|dimin|r[eé]gler|allum|[eé]teind|commande|sur \\d", RegexOption.IGNORE_CASE).containsMatchIn(recent)
        val schema: JSONArray
        var societeNeeded = societeOn || planningClientsSync
        if (plan != null && plan.outils.isNotEmpty()) {
            val indispensables = if (pilotageChauffage) listOf("obsidian_chercher", "obsidian_lire", "ha_entites", "ha_etat", "ha_service") else emptyList()
            val reglageDirect = Regex("r[eé]glage|param[eè]tre|mode conversation|[eé]coute continue|annonce vocale|r[eé]ponse vocale|confirmation|agent autonome|bips|analyse gemini|gemini|agents? autonomes?|notifications banni[eè]re", RegexOption.IGNORE_CASE).containsMatchIn(recent)
            val keep = (plan.outils + indispensables + (if (reglageDirect) listOf("reglage_jarvis") else emptyList()) + (if (planningClientsSync) listOf("analyser_planning_clients") else emptyList()) + listOf("memoire_ajouter") +
                (if (fr.dd.jarvis.hermes.Hermes.enLigneConnu()) listOf("hermes_deleguer") else emptyList()) +
                (if (Regex("herm[eè]s|termux", RegexOption.IGNORE_CASE).containsMatchIn(recent)) listOf("hermes") else emptyList())).toSet()
            schema = JSONArray()
            for (i in 0 until all.length()) if (all.getJSONObject(i).getJSONObject("function").getString("name") in keep) schema.put(all.get(i))
            if (plan.outils.any { it in fr.dd.jarvis.societe.SocieteTools.NAMES }) societeNeeded = true
        } else schema = tools.schemaFor(recent)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content",
            Prompts.brain(userName()) + app.memo.toutes() + (if (societeNeeded) "\n\n" + fr.dd.jarvis.societe.SocieteTools.PROMPT else "") +
                (if (plan != null && plan.note.isNotBlank()) "\n\nAnalyse préalable de la demande (Gemini) — suis ce plan, avec les outils fournis : ${plan.note}" else "")))
        // Réduction de tokens sans perte de précision : seuls les derniers échanges (où un détail précis — liste de fichiers,
        // de SMS, de permissions… — a des chances d'être encore repris par la demande en cours) gardent le plafond large de
        // 12000 caractères ; les tours plus anciens (qui ne servent plus qu'à garder le fil de la conversation) sont ramenés
        // à 4000 caractères — largement assez pour une trentaine de lignes (noms de fichiers, SMS, etc.) sans repayer en
        // entier, à chaque nouveau tour, le texte complet d'une réponse déjà ancienne et déjà traitée.
        val recentHistory = history.takeLast(16)
        recentHistory.forEachIndexed { idx, m ->
            val cap = if (recentHistory.size - 1 - idx < 3) 12000 else 4000
            messages.put(JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", m.text.take(cap)))
        }

        repeat(6) {
            val msg = try {
                llm.groqChat(messages, schema)
            } catch (e: ApiException) {
                // Llama/GPT-OSS génère parfois un appel d'outil mal formé : on réessaie en interdisant explicitement un
                // nouvel appel d'outil ce tour-ci (tool_choice=none), mais SANS retirer les définitions d'outils du
                // contexte. Ça réduit le risque mais ne l'élimine pas : le modèle peut encore insister pour appeler un
                // outil malgré la consigne, et Groq refuse alors la requête avec la MÊME erreur une seconde fois. On ne
                // retente donc pas une 3e fois (ça ne ferait que répéter l'échec) : on répond proprement à la place,
                // plutôt que de laisser cette erreur brute remonter jusqu'à l'utilisateur.
                if (e.code == 400 && (e.message ?: "").contains("tool_use_failed")) {
                    try {
                        val m2 = JSONArray(); for (k in 0 until messages.length()) m2.put(messages.get(k))
                        m2.put(JSONObject().put("role", "system").put("content", "L'appel d'outil a échoué pour ce tour. N'invente AUCUNE donnée (appareils, imprimantes, mails, événements, fichiers) : dis simplement que l'action n'a pas pu être lancée et demande de réessayer."))
                        llm.groqChat(m2, schema, forceText = true)
                    } catch (e2: kotlinx.coroutines.CancellationException) { throw e2 } catch (e2: Exception) {
                        return "Désolé, j'ai buté sur cette demande (le modèle n'a pas réussi à formuler l'appel d'outil correctement). Peux-tu reformuler ou réessayer ?"
                    }
                } else throw e
            }
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return msg.optString("content", "").trim().ifBlank { "…" }
            }

            messages.put(
                JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls)
            )
            val direct = StringBuilder()
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val fn = c.getJSONObject("function")
                val name = fn.getString("name")
                val args = try {
                    JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" })
                } catch (e: Exception) {
                    JSONObject()
                }
                onStatus("⚙️ $name…")
                val res = try {
                    tools.execute(name, args)
                } catch (e: ApiException) {
                    ToolResult("Erreur du service IA : ${e.message}")
                } catch (e: Exception) {
                    ToolResult("Erreur : ${e.message}")
                }
                messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", res.text.take(12000))
                )
                if (res.direct) direct.append(res.text).append("\n\n")
            }
            if (direct.isNotEmpty()) return direct.toString().trim()
            onStatus("Réflexion…")
        }
        return "Je n'ai pas réussi à terminer cette opération."
    }

    /**
     * « Où suis-je ? / ma localisation exacte » → l'outil ma_position, toujours (adresse précise + GPS) ;
     * « mets à jour la fiche X avec ma localisation » → client_ecrire avec la position et l'adresse du téléphone,
     * sans jamais redemander l'adresse à l'utilisateur.
     */
    private suspend fun positionDirecte(dernier: String, history: List<ChatMsg>, onStatus: (String) -> Unit): String? {
        val n = fr.dd.jarvis.societe.core.normaliser(dernier)
        val parlePosition = Regex("\\b(ma|mon) (localisation|position|geolocalisation|emplacement|point gps|adresse actuelle)|\\bou (je suis|suis je|est ce que je suis|je me trouve)\\b|\\b(ici meme|ou on est)\\b").containsMatchIn(n)
        val enAttente = attentePosition?.takeIf { System.currentTimeMillis() - it.second < 5 * 60_000 }
        val majFiche = Regex("\\b(a jour|mets?|met|mettre|maj|actualise|enregistre|ajoute|renseigne|complete|modifie|corrige)\\b").containsMatchIn(n) &&
            Regex("\\b(fiche|client|installation|site|chantier)").containsMatchIn(n)
        val clients = app.societe.data.clients
        if (enAttente != null && !parlePosition) {
            // Réponse à « laquelle de ses installations ? »
            val c = clients.firstOrNull { it.id == enAttente.first } ?: return null.also { attentePosition = null }
            val site = siteNomme(c, n) ?: if (Regex("\\b(toutes?|tous|les deux|les \\d+|chacune)\\b").containsMatchIn(n)) "toutes" else return null
            attentePosition = null
            return majPosition(c.nom, site, onStatus)
        }
        if (!parlePosition) return null
        if (majFiche) {
            val c = clients.filter { it.nom.isNotBlank() && Regex("\\b" + Regex.escape(fr.dd.jarvis.societe.core.normaliser(it.nom)) + "\\b").containsMatchIn(n) }
                .maxByOrNull { it.nom.length }
                ?: clients.filter { c -> fr.dd.jarvis.societe.core.normaliser(c.nom).split(" ").any { m -> m.length >= 4 && Regex("\\b$m\\b").containsMatchIn(n) } }.singleOrNull()
                ?: return null // client non reconnu : le cerveau s'en charge (avec les mêmes consignes)
            val site = siteNomme(c, n) ?: when {
                Regex("\\b(toutes?|tous|deux|les \\d+|ses installations|chaque installation|chacune)\\b").containsMatchIn(n) -> "toutes"
                c.sites.size <= 1 -> ""
                else -> {
                    attentePosition = c.id to System.currentTimeMillis()
                    return "📍 ${c.nom} a ${c.sites.size} installations : ${c.sites.joinToString { "« ${it.nom} »" }}. Laquelle se trouve ici ? Réponds par son nom, ou « toutes »."
                }
            }
            return majPosition(c.nom, site, onStatus)
        }
        // « envoie ma position à… », « trajet depuis ma position », météo… : le cerveau garde la main.
        if (Regex("\\b(envoie|envoyer|partage|sms|message|mail|agenda|rendez vous|itineraire|trajet|route|distance|meteo|temps qu il fait|emmene|guide)").containsMatchIn(n)) return null
        onStatus("📍 Localisation…")
        return tools.execute("ma_position", JSONObject()).text
    }

    private fun siteNomme(c: fr.dd.jarvis.societe.core.Client, n: String): String? =
        c.sites.filter { it.nom.isNotBlank() && n.contains(fr.dd.jarvis.societe.core.normaliser(it.nom)) }.maxByOrNull { it.nom.length }?.nom

    private suspend fun majPosition(client: String, site: String, onStatus: (String) -> Unit): String {
        onStatus("📍 Relevé de la position et de l'adresse…")
        val args = JSONObject().put("nom", client).put("site_gps", "ma position").put("site_adresse", "ma position")
        if (site.isNotBlank()) args.put("site_nom", site)
        return tools.execute("client_ecrire", args).text
    }

    companion object {
        /** Client dont on attend l'installation concernée par « ma position » (id, horodatage). */
        @Volatile private var attentePosition: Pair<String, Long>? = null
    }
}
