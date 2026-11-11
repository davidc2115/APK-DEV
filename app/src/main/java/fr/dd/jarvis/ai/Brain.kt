package fr.dd.jarvis.ai

import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject

data class ChatMsg(val fromUser: Boolean, val text: String, val ts: Long = System.currentTimeMillis())

/**
 * Point d'entrée conversationnel de Jarvis. Le routeur choisit entre Hermès (cerveau : tâches complexes, automatismes,
 * sous-agents Groq/Gemini) et Jarvis (exécution rapide sur le téléphone). Quand Hermès confie une action à Jarvis
 * (outil MCP jarvis_agent), [reply] tourne avec pourHermes = true : pas de renvoi vers Hermès, pas de boucle.
 */
class Brain(
    private val llm: Llm,
    private val tools: ToolBox,
    private val app: fr.dd.jarvis.JarvisApp,
    private val userName: () -> String
) {
    /** Chemin suivi par une réponse (pour décider si Hermès doit reprendre la main). */
    private class Suivi { var cerveauJarvis = false; var hermesEssaye = false; var hermesIndisponible = false }

    /**
     * Jarvis d'abord : réflexes, puis Hermès seulement pour ce qui le demande clairement, sinon le cerveau rapide de
     * Jarvis. Si Jarvis n'aboutit pas (pas d'outil, rien trouvé, information manquante, erreur), Hermès reprend
     * AUTOMATIQUEMENT la demande — avec sa mémoire, ses notes, skills et outils — sans que l'utilisateur ait à le nommer.
     */
    suspend fun reply(history: List<ChatMsg>, pourHermes: Boolean = false, onTexte: ((String) -> Unit)? = null, onStatus: (String) -> Unit): String {
        // L'avatar montre que JARVIS réfléchit tant que la réponse se prépare.
        fr.dd.jarvis.voice.Presence.debutReflexion()
        try { return replyInterne(history, pourHermes, onTexte, onStatus) } finally { fr.dd.jarvis.voice.Presence.finReflexion() }
    }

    private suspend fun replyInterne(history: List<ChatMsg>, pourHermes: Boolean, onTexte: ((String) -> Unit)?, onStatus: (String) -> Unit): String {
        // Anti-vol déclenché : JARVIS ne fait plus rien tant que l'alerte n'est pas arrêtée avec le code.
        if (app.store.antivolAlerte.value) return "🔒 Mode anti-vol actif : JARVIS est verrouillé. Arrête l'alerte avec ton code (écran d'alerte, Réglages › Sécurité › Anti-vol, ou SMS « JARVIS tonCode STOP »)."
        // Carnet de notes (« prends note… », « relis mes notes sur le poêle ») : enregistré tout de suite, sans IA, sans
        // Home Assistant ni autre application — quel que soit le sujet de la note.
        if (!pourHermes) history.lastOrNull()?.takeIf { it.fromUser }?.let { m ->
            val precedent = history.dropLast(1).lastOrNull { !it.fromUser }?.text
            (runCatching { fr.dd.jarvis.device.Carnet.reflexe(app, m.text, fr.dd.jarvis.hermes.Routeur.multiEtapes(m.text), precedent) }.getOrNull()
                ?: runCatching { fr.dd.jarvis.device.Echeancier.reflexe(app, m.text) }.getOrNull())?.let { r ->
                fr.dd.jarvis.hermes.Routeur.derniere = fr.dd.jarvis.hermes.Routeur.Cible.JARVIS
                onStatus(if (r.startsWith("# ⏰") || r.startsWith("Rien de programmé")) "⏰ Rappels et automatisations" else "📝 Carnet de notes")
                return r
            }
        }
        // Routine déclenchée par une phrase (« Jarvis, je pars ») : toutes ses actions d'un coup.
        if (!pourHermes) history.lastOrNull()?.takeIf { it.fromUser }?.let { m ->
            fr.dd.jarvis.device.Routines.parPhrase(app, m.text)?.let { r ->
                onStatus("⚙️ Routine « ${r.nom} »…")
                return fr.dd.jarvis.device.Routines.executer(app, r, tools = tools)
            }
        }
        val suivi = Suivi()
        val r = try { repondre(history, pourHermes, onStatus, suivi, onTexte) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            if (!suivi.cerveauJarvis || !escaladePossible(pourHermes, suivi)) throw e
            "❌ ${e.message ?: e.javaClass.simpleName}"
        }
        // Hermès devait répondre mais n'a pas pu : on le dit, pour ne jamais faire passer Jarvis pour Hermès.
        if (suivi.hermesIndisponible) return r + "\n\n⚠️ " + when {
            fr.dd.jarvis.hermes.Hermes.degrade() -> "Hermès est saturé (quota de l'IA) pour quelques minutes"
            !fr.dd.jarvis.hermes.Hermes.enLigneConnu() -> "Hermès est hors ligne (il redémarre peut-être)"
            else -> "Hermès n'a pas répondu"
        } + " : réponse directe de Jarvis."
        if (!suivi.cerveauJarvis || !escaladePossible(pourHermes, suivi) || !fr.dd.jarvis.hermes.Routeur.jarvisABesoinDHermes(r)) return r
        onStatus("🪽 Hermès prend le relais…")
        val h = fr.dd.jarvis.hermes.Hermes.repondre(app, history, tools, userName(), onStatus, tentativeJarvis = r, onTexte = onTexte) ?: return r
        fr.dd.jarvis.hermes.Routeur.derniere = fr.dd.jarvis.hermes.Routeur.Cible.HERMES
        return h
    }

    /** Réponse de conversation : prompt court, 10 derniers messages, aucun outil (Groq → Gemini → Cloudflare). */
    private suspend fun conversationRapide(history: List<ChatMsg>, onStatus: (String) -> Unit): String? {
        onStatus("💬 …")
        val ctx = runCatching { fr.dd.jarvis.device.Contexte.actuel(app) }.getOrNull()
        val maintenant = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH'h'mm", java.util.Locale.FRENCH))
        val sys = "Tu es JARVIS, l'assistant personnel de ${userName().ifBlank { "l'utilisateur" }}, qui te tutoie. C'est un moment de conversation détendue : " +
            "réponds naturellement en français, chaleureux et vivant, avec une pointe d'humour quand ça s'y prête, en une à trois phrases courtes, " +
            "comme un ami attentionné ; relance parfois par une question. Pas de liste, pas de titre, pas d'emoji en excès. " +
            "Tu n'as consulté ni son agenda, ni ses mails, ni ses appareils : n'invente aucun fait sur sa journée ; s'il veut une info précise, propose de vérifier. " +
            "Nous sommes le $maintenant." + (ctx?.let { " Contexte : ${it.mode.libelle}." } ?: "")
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        history.takeLast(10).filter { it.text.isNotBlank() }.forEach { m ->
            msgs.put(JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", m.text.take(1200)))
        }
        // Gemini Nano/AICore est un accélérateur facultatif réservé au bavardage.
        // S'il n'est pas déjà prêt ou ne répond pas très vite, on conserve exactement le chemin cloud actuel.
        val promptNano = buildString {
            append(sys)
            append("\n\nHistorique récent :\n")
            history.takeLast(6).filter { it.text.isNotBlank() }.forEach { m ->
                append(if (m.fromUser) "Utilisateur : " else "Jarvis : ")
                append(m.text.take(700))
                append('\n')
            }
            append("\nRéponds au dernier message de l'utilisateur.")
        }.take(6500)
        GeminiNanoLocal.repondre(promptNano)?.let { return it }
        return try {
            app.llm.groqChat(msgs, null, forceText = true).optString("content").trim().takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
    }

    private fun escaladePossible(pourHermes: Boolean, suivi: Suivi) = !pourHermes && !suivi.hermesEssaye &&
        app.store.hermesMode.value in setOf("auto", "tout") && !fr.dd.jarvis.hermes.Hermes.degrade()

    private suspend fun repondre(history: List<ChatMsg>, pourHermes: Boolean, onStatus: (String) -> Unit, suivi: Suivi, onTexte: ((String) -> Unit)? = null): String {
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
        val texteUser = history.lastOrNull { it.fromUser }?.text.orEmpty()
        // RÉFLEXES : demandes simples et sans ambiguïté traitées instantanément et de façon fiable, avant tout modèle
        // (Hermès les voit ensuite dans l'historique). Jamais pour une demande à orchestrer, en plusieurs étapes, ou
        // adressée nommément à Hermès.
        val multi = fr.dd.jarvis.hermes.Routeur.multiEtapes(texteUser)
        val reflexes = pourHermes || !fr.dd.jarvis.hermes.Routeur.pourHermesSeul(texteUser)
        fun reflexe(r: String) = r.also { if (!pourHermes) fr.dd.jarvis.hermes.Routeur.derniere = fr.dd.jarvis.hermes.Routeur.Cible.JARVIS }
        // Une procédure qu'Hermès a apprise (skill ajouté) passe avant les raccourcis de Jarvis sur le même sujet.
        val competence = if (pourHermes || app.store.hermesMode.value == "off") null
            else runCatching { fr.dd.jarvis.hermes.Routeur.competence(texteUser, app.memo.skillsHermes()) }.getOrNull()
        // Mode « Hermès pour tout » : seules les commandes instantanées du téléphone (sans IA) passent avant Hermès ;
        // tout le reste (fichiers, Freebox, vidéos, position, installations…) est traité par Hermès avec les outils de Jarvis.
        val toutHermes = !pourHermes && app.store.hermesMode.value == "tout" && !app.enVoiture && !fr.dd.jarvis.hermes.Hermes.degrade() &&
            !(app.store.hermesRapide.value && fr.dd.jarvis.hermes.Routeur.commandeSimple(texteUser))
        if (reflexes) {
            if (!multi) commandeImmediate(texteUser, onStatus)?.let { return reflexe(it) }
        }
        if (reflexes && !toutHermes) {
            positionDirecte(dernier, history, onStatus)?.let { return reflexe(it) }
            if (competence == null) {
                rangementDirect(history, onStatus)?.let { return reflexe(it) }
                if (!multi) rechercheDirecte(texteUser, onStatus)?.let { return reflexe(it) }
                if (!multi) freeboxDirect(texteUser, onStatus)?.let { return reflexe(it) }
                if (!multi) videoDirecte(history, onStatus)?.let { return reflexe(it) }
                if (!multi) installationDirecte(texteUser, onStatus)?.let { return reflexe(it) }
            }
        }
        // Conversation simple (« salut », « comment ça va ? », « merci ») : réponse immédiate, un seul appel d'IA, sans outils ni Hermès.
        if (!pourHermes && !multi && fr.dd.jarvis.hermes.Routeur.bavardage(texteUser)) {
            conversationRapide(history, onStatus)?.let { return reflexe(it) }
        }
        // Hermès, cerveau principal (mode « tout » : toutes les autres demandes ; mode « auto » : les tâches complexes).
        // S'il est indisponible, Jarvis reprend la main avec ses outils.
        if (!pourHermes) {
            val precedent = history.dropLastWhile { it.fromUser }.lastOrNull()?.text
            val choix = fr.dd.jarvis.hermes.Routeur.choisir(app, texteUser, precedent)
            if (choix.cible == fr.dd.jarvis.hermes.Routeur.Cible.JARVIS && app.store.hermesMode.value == "tout" && fr.dd.jarvis.hermes.Hermes.degrade()) suivi.hermesIndisponible = true
            if (choix.cible == fr.dd.jarvis.hermes.Routeur.Cible.HERMES) {
                suivi.hermesEssaye = true
                val r = fr.dd.jarvis.hermes.Hermes.repondre(app, history, tools, userName(), onStatus, onTexte = onTexte)
                if (r != null) return r
                fr.dd.jarvis.hermes.Routeur.derniere = fr.dd.jarvis.hermes.Routeur.Cible.JARVIS
                suivi.hermesIndisponible = true
                onStatus("⚠️ Hermès indisponible : Jarvis répond…")
            }
        }
        if (outilDirect != null) {
            onStatus("⚙️ $outilDirect…")
            val args = JSONObject(); if (dernier.contains("serveur")) args.put("via", "serveur")
            return try { tools.execute(outilDirect, args).text } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "❌ ${e.message}" }
        }
        suivi.cerveauJarvis = true
        onStatus("🧠 Analyse de la demande…")
        val all = tools.schemaAll()
        val sansAnalyse = pourHermes || fr.dd.jarvis.hermes.Routeur.commandeSimple(texteUser)
        // Notes pertinentes et analyse de la demande EN MÊME TEMPS (avant : l'une après l'autre, jusqu'à 2,5 s de perdues).
        val (notesUtiles, plan) = kotlinx.coroutines.coroutineScope {
            val notes = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { fr.dd.jarvis.device.NotesPertinentes.pour(app, texteUser) }.getOrDefault("") }
            val analyse = async {
                // L'analyse Gemini n'est utile que pour une vraie orchestration multiétape.
                // Pour une demande simple, schemaFor() sélectionne localement les outils : un appel IA complet économisé.
                if (sansAnalyse || !multi) null else try { Analyseur.analyser(app, history, all) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            }
            notes.await() to analyse.await()
        }
        // Pour une consigne de chauffage, le planificateur ne doit jamais retirer les outils nécessaires :
        // il faut pouvoir retrouver les correspondances dans Obsidian, vérifier les entités HA puis agir.
        val pilotageChauffage = Regex("po[eê]les?|poils?|pellets?|granul[eé]s?|chauffage|thermostats?|climate\\.|temp[eé]rature|consigne|degr[eé]s?", RegexOption.IGNORE_CASE).containsMatchIn(recent) &&
            Regex("r[eé]gl|mettre|passer|fixer|monter|baisser|augment|dimin|r[eé]gler|allum|[eé]teind|commande|sur \\d", RegexOption.IGNORE_CASE).containsMatchIn(recent)
        var schema: JSONArray
        var societeNeeded = societeOn || planningClientsSync
        // Domotique (chauffage, télé, lumières, volets…) : toujours de quoi relire les notes, trouver l'entité ET agir.
        val domotique = pilotageChauffage || (Regex("t[ée]l[ée]|\\btv\\b|t[ée]l[ée]vis|volume|lumi[èe]re|lampe|volet|store|prise|home ?assistant|domotique|enceinte", RegexOption.IGNORE_CASE).containsMatchIn(recent) &&
            ACTION_DOMOTIQUE.containsMatchIn(recent))
        val indispensables = if (domotique) listOf("notes_lire", "obsidian_chercher", "obsidian_lire", "ha_entites", "ha_etat", "ha_service") else emptyList()
        if (plan != null && plan.outils.isNotEmpty()) {
            val reglageDirect = Regex("r[eé]glage|param[eè]tre|mode conversation|[eé]coute continue|annonce vocale|r[eé]ponse vocale|confirmation|agent autonome|bips|analyse gemini|gemini|agents? autonomes?|notifications banni[eè]re", RegexOption.IGNORE_CASE).containsMatchIn(recent)
            val keep = (plan.outils + indispensables + (if (reglageDirect) listOf("reglage_jarvis") else emptyList()) + (if (planningClientsSync) listOf("analyser_planning_clients") else emptyList()) + listOf("memoire_ajouter", "memoire_apprendre", "memoire_apprentissages", "memoire_lire", "memoire_oublier", "note_prendre", "notes_lire", "rappel_creer", "rappels_liste") + (if (societeOn || planningClientsSync) fr.dd.jarvis.societe.SocieteTools.NAMES else emptySet<String>()) +
                (if (fr.dd.jarvis.hermes.Hermes.enLigneConnu()) listOf("hermes_deleguer") else emptyList()) +
                (if (Regex("herm[eè]s|termux", RegexOption.IGNORE_CASE).containsMatchIn(recent)) listOf("hermes") else emptyList()) +
                (if (Regex("freebox|\\bbox\\b|vpn|wireguard|openvpn|redirection|ouvr\\w* (le|un|les) ports?|\\bdmz\\b|bail dhcp", RegexOption.IGNORE_CASE).containsMatchIn(recent)) listOf("freebox") else emptyList()) +
                (if (TikTokTools.concerned(recent)) listOf("video_montage", "tiktok_video", "generer_video") else emptyList()) +
                (if (ServeurTools.concerned(recent)) ServeurTools.NAMES.toList() else emptyList()) +
                (if (Regex("[ée]cran|appli|clique|appuie|tape sur|pilote|contr[ôo]le|v[ée]rifie|ouvre|lance|musique|joue|navigue|remplis|coche", RegexOption.IGNORE_CASE).containsMatchIn(recent))
                    listOf("voir_ecran", "piloter_telephone", "phone_action") else emptyList())).toSet()
            schema = JSONArray()
            for (i in 0 until all.length()) if (all.getJSONObject(i).getJSONObject("function").getString("name") in keep) schema.put(all.get(i))
            if (plan.outils.any { it in fr.dd.jarvis.societe.SocieteTools.NAMES }) societeNeeded = true
        } else {
            schema = tools.schemaFor(recent)
            val presents = (0 until schema.length()).mapNotNull { schema.optJSONObject(it)?.optJSONObject("function")?.optString("name") }.toSet()
            val manquants = indispensables.filter { it !in presents }.toSet()
            if (manquants.isNotEmpty()) schema = JSONArray(schema.toString()).also { s ->
                for (i in 0 until all.length()) if (all.getJSONObject(i).getJSONObject("function").getString("name") in manquants) s.put(all.get(i))
            }
        }
        // Outils retirés : ceux qui renverraient vers Hermès (quand c'est Hermès qui demande), et le SSH pour une demande
        // Home Assistant ordinaire — elle passe par l'API (ha_*) ; si l'API échoue, l'approfondissement prend le relais.
        val retires = (if (pourHermes) setOf("hermes", "hermes_deleguer", "tache_arriere_plan") else emptySet()) +
            (if (fr.dd.jarvis.hermes.Routeur.homeAssistantApi(texteUser)) setOf("ssh_commande", "ssh_fichier", "ssh_script", "serveur_diagnostic") else emptySet())
        val schemaFinal = if (retires.isEmpty()) schema else JSONArray().also { f ->
            for (i in 0 until schema.length()) {
                val nom = schema.optJSONObject(i)?.optJSONObject("function")?.optString("name")
                if (nom !in retires) f.put(schema.get(i))
            }
        }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content",
            (if (pourHermes) "Tu es l'agent téléphone de JARVIS, missionné par Hermès (le cerveau) : exécute la consigne avec tes outils, puis rends un compte rendu factuel et complet (résultats réels, chemins, identifiants, erreurs exactes), sans poser de question sauf si une information indispensable manque.\n\n" else "") +
            // Partie stable d'abord (mise en cache par le fournisseur), puis le contexte propre à la demande.
            Prompts.brain(userName(), avecDate = false) + (if (societeNeeded) "\n\n" + fr.dd.jarvis.societe.SocieteTools.PROMPT else "") +
                app.memo.pourPrompt() + app.memo.hermesPourPrompt() + app.memo.capacitesPourPrompt() + notesUtiles +
                (if (plan != null && plan.note.isNotBlank()) "\n\nAnalyse préalable de la demande (Gemini) — suis ce plan, avec les outils fournis : ${plan.note}" else "") +
                runCatching { fr.dd.jarvis.device.Contexte.consigne(fr.dd.jarvis.device.Contexte.actuel(app)) }.getOrDefault("") +
                "\n\nDate et heure actuelles : ${Prompts.now()}."))
        // Réduction de tokens sans perte de précision : seuls les derniers échanges (où un détail précis — liste de fichiers,
        // de SMS, de permissions… — a des chances d'être encore repris par la demande en cours) gardent le plafond large de
        // 12000 caractères ; les tours plus anciens (qui ne servent plus qu'à garder le fil de la conversation) sont ramenés
        // à 4000 caractères — largement assez pour une trentaine de lignes (noms de fichiers, SMS, etc.) sans repayer en
        // entier, à chaque nouveau tour, le texte complet d'une réponse déjà ancienne et déjà traitée.
        // Les 3 derniers messages restent complets pour préserver les détails opérationnels.
        // Les 7 précédents servent uniquement à garder le fil et sont plafonnés à 1600 caractères.
        val recentHistory = history.takeLast(10)
        recentHistory.forEachIndexed { idx, m ->
            val cap = if (recentHistory.size - 1 - idx < 3) 12000 else 1600
            messages.put(JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", m.text.take(cap)))
        }

        repeat(6) {
            val msg = try {
                llm.groqChat(messages, schemaFinal)
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
                        llm.groqChat(m2, schemaFinal, forceText = true)
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
                    if (name in retires) ToolResult(if (name.startsWith("ssh") || name == "serveur_diagnostic") "Pour Home Assistant, utilise l'API (ha_entites, ha_etat, ha_service, ha_config, ha_dashboard, ha_admin), pas le SSH." else "Outil indisponible ici.")
                    else tools.execute(name, args)
                } catch (e: ApiException) {
                    ToolResult("Erreur du service IA : ${e.message}")
                } catch (e: Exception) {
                    ToolResult("Erreur : ${e.message}")
                }
                messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", res.text.take(12000))
                )
                // Un résultat « direct » (liste d'entités…) n'est rendu tel quel que si l'utilisateur voulait cette liste : s'il
                // demandait une action (« baisse le son de la télé »), le modèle s'en sert pour agir ; s'il est vide, pour réessayer.
                if (res.direct && !(name == "ha_entites" && (res.text.startsWith("Aucune") || ACTION_DOMOTIQUE.containsMatchIn(texteUser))))
                    direct.append(res.text).append("\n\n")
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

    /**
     * « Range / déplace tous les fichiers (images…) dont le nom contient X dans le (sous-)dossier Y » : la demande est
     * comprise par un agent (motif, type, destination, y compris sur plusieurs messages) puis exécutée d'un coup par
     * obsidian_fichiers/trier — sans jamais exiger les noms exacts des fichiers.
     */
    private suspend fun rangementDirect(history: List<ChatMsg>, onStatus: (String) -> Unit): String? {
        val users = history.filter { it.fromUser }.takeLast(3).map { fr.dd.jarvis.societe.core.normaliser(it.text) }
        val n = users.lastOrNull() ?: return null
        val verbe = Regex("\\b(deplace[rz]?|range[rz]?|mets|mettre|met|place[rz]?|trie[rz]?|classe[rz]?|bouge[rz]?|transfere[rz]?|regroupe[rz]?|organise[rz]?)\\b")
        val objet = Regex("\\b(fichiers?|images?|photos?|documents?|pdf|notes?|videos?|coloriages?)\\b")
        val critere = Regex("\\b(nomme|nommes|nommee|nommees|appele|appeles|appelee|appelees|comport|contien|contenant|comportant|dont le nom|leur nom|le nom|avec le mot|qui ont)")
        val direct = verbe.containsMatchIn(n) && objet.containsMatchIn(n) && (critere.containsMatchIn(n) || n.contains("dossier"))
        val suite = !direct && users.size >= 2 && users.dropLast(1).any { verbe.containsMatchIn(it) && objet.containsMatchIn(it) } &&
            (objet.containsMatchIn(n) || critere.containsMatchIn(n) || n.contains("dossier"))
        if (!direct && !suite) return null
        // Rangement du stockage du téléphone (Téléchargements, DCIM…) : ce n'est pas le coffre.
        if (Regex("telechargement|download|dcim|galerie|camera|whatsapp").containsMatchIn(n) && !Regex("coffre|obsidian|jarvis").containsMatchIn(n)) return null
        onStatus("🗂️ Analyse du rangement…")
        val contexte = history.takeLast(6).joinToString("\n") { (if (it.fromUser) "Utilisateur : " else "Jarvis : ") + it.text.take(400) }
        val sys = """Tu analyses une demande de RANGEMENT de fichiers dans le coffre Obsidian. Réponds UNIQUEMENT par un objet JSON :
{"ranger": true ou false, "motif": "mots que le NOM des fichiers doit contenir, sans article ni « dans leur nom »", "types": "images|pdf|notes|videos|audio|tous", "source": "dossier où chercher, ou vide", "destination": "dossier de destination tel que dit par l'utilisateur, ou vide", "sous_dossier": true ou false}
- ranger=false si la DERNIÈRE demande n'est pas de déplacer/ranger/trier des fichiers d'après leur nom.
- Tiens compte des messages précédents : une réponse courte peut compléter une demande antérieure (motif ou destination).
- sous_dossier=true si l'utilisateur parle d'un « sous-dossier » ou ne donne qu'un simple nom de dossier."""
        val brut = try { app.llm.agent(sys, contexte) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return null }
        val j = runCatching { JSONObject(brut.substring(brut.indexOf('{'), brut.lastIndexOf('}') + 1)) }.getOrNull() ?: return null
        if (!j.optBoolean("ranger")) return null
        val motif = j.optString("motif").trim()
        val dest = j.optString("destination").trim()
        if (motif.isBlank()) return null
        if (dest.isBlank()) return "🗂️ Dans quel dossier du coffre dois-je ranger les fichiers « $motif » ? (par exemple « un sous-dossier $motif »)"
        onStatus("🗂️ Rangement des fichiers « $motif »…")
        return tools.execute("obsidian_fichiers", JSONObject().put("action", "trier").put("motif", motif).put("destination", dest)
            .put("types", j.optString("types").ifBlank { "tous" }).put("chemin", j.optString("source"))
            .put("sous_dossier", j.optBoolean("sous_dossier", true).toString())).text
    }

    /**
     * « Trouve-moi le dossier coloriage pompier », « où est le fichier facture Martin ? », « cherche les images de
     * dragon » : recherche RÉELLE dans le coffre Obsidian ET le stockage du téléphone (fichiers et dossiers), sans
     * passer par un modèle qui pourrait conclure trop vite que rien n'existe. Rien trouvé → le cerveau reprend la main.
     */
    private suspend fun rechercheDirecte(texte: String, onStatus: (String) -> Unit): String? {
        val n = fr.dd.jarvis.societe.core.normaliser(texte)
        val verbe = Regex("\\b(trouve[rsz]?|retrouve[rsz]?|cherche[rsz]?|recherche[rsz]?|localise[rsz]?|ou est|ou sont|ou se trouvent?|existe t il|il existe|as tu|tu as|montre[sz]?|affiche[sz]?|liste[sz]?|contenu|qu y a t il|que contient)\\b")
        val objet = Regex("\\b(dossiers?|sous dossiers?|fichiers?|images?|photos?|documents?|pdf|videos?|notes?)\\b")
        if (!verbe.containsMatchIn(n) || !objet.containsMatchIn(n)) return null
        // Ce n'est pas une recherche de fichier : imprimante, réseau, internet, client, agenda…
        if (Regex("\\b(imprimante|reseau|internet|web|google|client|agenda|rendez vous|mail|sms|contact)\\b").containsMatchIn(n)) return null
        val m = objet.find(n) ?: return null
        var q = n.substring(m.range.last + 1)
        q = q.replace(Regex("^\\s*(nomme|nommes|nommee|nommees|appele|appeles|appelee|appelees|intitule|qui s appelle|qui sappelle|qui contient|qui contiennent|qui comporte|qui comportent|contenant|comportant|avec|de|des|du|sur|la|le|les|l)\\b"), "")
        q = q.replace(Regex("\\b(dans|sur|de) (mon|le|ma|mes|mon) (telephone|smartphone|coffre( obsidian)?|obsidian|stockage|portable)\\b.*$"), "")
            .replace(Regex("\\b(stp|s il te plait|merci|svp|dans leur nom|dans le nom|en ce moment)\\b"), "").trim()
        if (q.length < 2 || q.split(' ').size > 8) return null
        onStatus("🔎 Recherche de « $q » dans le coffre et le téléphone…")
        val r = runCatching { tools.execute("stockage_telephone", JSONObject().put("action", "chercher").put("chemin", q)).text }.getOrNull() ?: return null
        return if (r.startsWith("Aucun résultat")) null else r
    }

    /**
     * « Crée une vidéo de 40 s sur… », « fais 3 TikToks… », « refais-la en 1 minute » : toute création de vidéo passe par
     * le montage (Montage) — scénario et prompts précis écrits d'abord par l'agent Gemini, puis plans générés et montés à
     * la durée exacte. Aucun modèle ne peut répondre « seulement 5 secondes ».
     */
    private suspend fun videoDirecte(history: List<ChatMsg>, onStatus: (String) -> Unit): String? {
        val users = history.filter { it.fromUser }.takeLast(3)
        val dernier = users.lastOrNull()?.text ?: return null
        val n = fr.dd.jarvis.societe.core.normaliser(dernier)
        if (Regex("\\b(annule[rsz]?|arrete[rsz]?|stoppe[rsz]?|interromp\\w*)\\b.*\\b(videos?|montages?|tiktoks?|clips?)\\b").containsMatchIn(n)) return Montage.annuler(app)
        val mot = Regex("\\b(videos?|clips?|montages?|tiktoks?|tik tok|reels?|shorts?|court metrage|film anime)\\b")
        val verbe = Regex("\\b(cree[rsz]?|creation|fais|faire|fait|genere[rsz]?|generation|realise[rsz]?|monte[rsz]?|produi[st]|produire|prepare[rsz]?|refais|refaire|recommence|transforme|rallonge)\\b")
        val duree = Regex("\\b\\d+ ?(s|sec|secondes?|min|minutes?)\\b")
        val direct = mot.containsMatchIn(n) && verbe.containsMatchIn(n)
        val suite = !direct && users.dropLast(1).any { mot.containsMatchIn(fr.dd.jarvis.societe.core.normaliser(it.text)) } &&
            (duree.containsMatchIn(n) || Regex("\\b(refais|refaire|recommence|rallonge|plus longue?|une autre)\\b").containsMatchIn(n))
        if (!direct && !suite) return null
        // Ni analyse, ni lecture, ni recherche, ni simple script : le cerveau garde la main.
        if (Regex("\\b(analyse|regarde|lis|lire|cherche|trouve|statistiques?|stats|vues|abonnes|script seul|juste le script)\\b").containsMatchIn(n)) return null
        onStatus("🎬 Préparation de la vidéo…")
        val contexte = history.takeLast(6).joinToString("\n") { (if (it.fromUser) "Utilisateur : " else "Jarvis : ") + it.text.take(600) }
        val sys = """Tu analyses une demande de CRÉATION DE VIDÉO pour le moteur de montage de JARVIS. Réponds UNIQUEMENT par un objet JSON :
{"creer": true ou false, "sujet": "ce que la vidéo doit montrer/raconter, fidèle et complet (personnages, lieu, actions, ambiance, style)", "script": "script ou texte fourni par l'utilisateur, sinon vide", "duree": secondes (entier), "nombre": nombre de vidéos (entier), "format": "vertical|horizontal|carre", "tiktok": true ou false, "musique": "auto|aucune|style demandé", "paroles": "vide, auto (chanson demandée) ou paroles fournies", "voix_off": "auto|aucune|texte exact à dire", "textes": true ou false, "sources": "médias existants à utiliser séparés par ; (liens jarvis-img:/jarvis-video-ia:, chemins ou dossiers du coffre cités), sinon vide", "garder_son": true ou false}
- creer=false si la DERNIÈRE demande n'est pas de produire maintenant une vidéo (question, analyse, publication, lecture d'un film, script seul…).
- Tiens compte des messages précédents : « refais-la en 40 secondes » reprend le sujet de la vidéo précédente avec la nouvelle durée.
- duree : la durée demandée (1 min 30 = 90). Si aucune durée n'est donnée : 30 pour TikTok/Reels/Shorts, 20 sinon.
- tiktok=true pour TikTok/Reels/Shorts (format vertical). format horizontal seulement si demandé (paysage, 16:9, YouTube…).
- musique « aucune », voix_off « aucune », textes false seulement si l'utilisateur le demande (« sans musique », « sans voix », « sans texte »)."""
        val brut = try { app.llm.agent(sys, contexte) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return null }
        val j = runCatching { JSONObject(brut.substring(brut.indexOf('{'), brut.lastIndexOf('}') + 1)) }.getOrNull() ?: return null
        if (!j.optBoolean("creer")) return null
        val sujet = j.optString("sujet").trim().ifBlank { dernier }
        val format = j.optString("format").trim().lowercase().ifBlank { "vertical" }
        val d = Montage.Demande(
            sujet = sujet, script = j.optString("script").trim(),
            dureeS = j.optInt("duree", 0).takeIf { it > 0 } ?: 20,
            format = format, nombre = j.optInt("nombre", 1).coerceAtLeast(1),
            musique = j.optString("musique").trim().ifBlank { "auto" },
            paroles = j.optString("paroles").trim(),
            voixOff = j.optString("voix_off").trim().ifBlank { "auto" },
            textes = j.optBoolean("textes", true),
            sources = j.optString("sources").trim(),
            garderSon = j.optBoolean("garder_son", false),
            tiktok = j.optBoolean("tiktok", false) && !format.startsWith("hori")
        )
        return Montage.lancer(app, d)
    }

    /**
     * Commandes du téléphone les plus courantes, exécutées immédiatement SANS modèle (une fraction de seconde) :
     * lampe torche, volume et luminosité chiffrés, Wi-Fi, Bluetooth, lecture/pause/suivant/précédent, verrouillage,
     * capture d'écran. Les confirmations habituelles (Wi-Fi coupé, verrouillage…) restent demandées par l'outil.
     */
    private suspend fun commandeImmediate(texte: String, onStatus: (String) -> Unit): String? {
        val (outil, action, valeur) = actionImmediate(texte) ?: return null
        val args = if (outil == "controle_media") JSONObject().put("action", action) else JSONObject().put("action", action).put("value", valeur)
        val r = tools.execute(outil, args).text
        onStatus("⚡ Commande exécutée")
        return r
    }

    /** Demandes Freebox courantes exécutées directement par l'outil (pas de modèle qui répondrait « impossible »). */
    private suspend fun freeboxDirect(texte: String, onStatus: (String) -> Unit): String? {
        val (action, args) = intentionFreebox(texte) ?: return null
        onStatus("📶 Freebox : $action…")
        val r = runCatching { tools.execute("freebox", args).text }.getOrElse { "❌ Freebox : ${it.message}" }
        // Disque : si l'API Freebox refuse (droit « explorer » non accordé…), essai par le partage réseau (SMB/FTP).
        if (action == "fichiers" && r.startsWith("❌")) {
            val partage = runCatching { tools.execute("box_disque", JSONObject().put("action", if (args.optString("chemin").isBlank()) "partages" else "lister").put("chemin", args.optString("chemin"))).text }.getOrNull()
            if (partage != null && partage.startsWith("📂") && Regex("^\\s*(📁|📄|\\S+/|d |- )", RegexOption.MULTILINE).containsMatchIn(partage.substringAfter('\n', "")))
                return "$r\n\nℹ️ Contenu lu par le partage réseau de la box (Réglages › Maison › Box Internet) :\n$partage"
        }
        return r
    }

    /** « Supprime l'installation Clairotte 3 (de Martin) » : outil dédié, jamais la suppression de toute la fiche client. */
    private suspend fun installationDirecte(texte: String, onStatus: (String) -> Unit): String? {
        val n = fr.dd.jarvis.societe.core.normaliser(texte)
        if (!Regex("\\b(supprim\\w*|effac\\w*|retir\\w*|enleve\\w*|enlever)\\b").containsMatchIn(n)) return null
        if (!Regex("\\b(installations?|sites?|centrales?|toitures?)\\b").containsMatchIn(n)) return null
        if (Regex("\\binterventions?\\b").containsMatchIn(n)) return null
        val clients = app.societe.data.clients
        val (client, site) = cibleInstallation(n, clients.map { c -> c.nom to c.sites.map { it.nom } }) ?: return null
        onStatus("🗑️ Installation « $site »…")
        return tools.execute("installation_supprimer", JSONObject().put("client", client).put("installation", site)).text
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
        /** Demande d'ACTION domotique (et non de simple liste) : le modèle doit agir après avoir trouvé l'entité. */
        internal val ACTION_DOMOTIQUE = Regex(
            "\\b(baisse|monte|augmente|diminue|coupe|r[ée]gle|mets?|allume|[ée]teins|ouvre|ferme|lance|arr[êe]te|active|d[ée]sactive|passe|change|mute|pause|reprends)\\b",
            RegexOption.IGNORE_CASE)
        /** Demande Freebox reconnue sans modèle : (action, arguments) pour l'outil freebox, ou null. */
        internal fun intentionFreebox(texte: String): Pair<String, JSONObject>? {
            val n = fr.dd.jarvis.societe.core.normaliser(texte)
            val box = Regex("\\b(freebox|box)\\b").containsMatchIn(n)
            val vpn = Regex("\\b(vpn|wireguard|openvpn)\\b").containsMatchIn(n)
            if (!box && !vpn && !Regex("redirections? de ports?|ouvr\\w* (le|un|les) ports?").containsMatchIn(n)) return null
            val active = Regex("\\b(active|activer|activez|allume|demarre|lance|mets en route|ouvre)\\b").containsMatchIn(n)
            val coupe = Regex("\\b(desactive|desactiver|coupe|couper|arrete|eteins|stoppe)\\b").containsMatchIn(n)
            val serveur = when { n.contains("openvpn") -> "openvpn_routed"; n.contains("ipsec") -> "ipsec"; n.contains("pptp") -> "pptp"; else -> "" }
            val args = JSONObject()
            // Disque dur de la box : « se connecter au disque », « dossier téléchargements », « mes films sur la box »…
            // Une MODIFICATION de fichiers (renommer, déplacer, supprimer, copier, envoyer, créer…) n'est jamais un simple
            // listage : elle part au cerveau, qui utilise les actions de fichiers de l'outil freebox.
            if (box && Regex("\\b(renomm\\w*|deplac\\w*|bouge\\w*|supprim\\w*|efface\\w*|copi\\w*|envoi\\w*|envoy\\w*|transfer\\w*|ajout\\w*|cree\\w*|creer|range\\w*|classe\\w*|mets?|recupere\\w*|telecharge (le|la|ce|cette|mon|ma)|organis\\w*)\\b").containsMatchIn(n)) return null
            val disque = box && Regex("\\b(disques?|dd|hdd|nas|stockage|fichiers?|dossiers?|telechargements?|films?|series?|videos?|musiques?|photos?)\\b").containsMatchIn(n)
            val action = when {
                Regex("redemarre|reboot").containsMatchIn(n) && box -> "redemarrer"
                box && Regex("telechargements? en cours|en cours de telechargement|gestionnaire de telechargement|file de telechargement|torrents?").containsMatchIn(n) -> "telechargements"
                disque -> {
                    val dossier = Regex("\\bdossiers? (?:nommes? |appeles? |de |des |du |d )?([a-z0-9][a-z0-9 _-]{1,40}?)(?= et | sur | de la | dans | du disque|$)").find(n)?.groupValues?.get(1)?.trim()
                    val chemin = when {
                        dossier != null && dossier !in setOf("la box", "ma box", "la freebox", "ma freebox") -> dossier
                        n.contains("telechargement") -> "Téléchargements"
                        Regex("\\bfilms?\\b").containsMatchIn(n) -> "Vidéos"
                        else -> ""
                    }
                    if (chemin.isNotBlank()) args.put("chemin", chemin)
                    "fichiers"
                }
                Regex("redirections?|ports? ouverts?|nat\\b").containsMatchIn(n) && Regex("liste|lister|quelles?|affiche|montre|voir|donne|y a t il|ai je").containsMatchIn(n) -> "redirections"
                vpn && Regex("utilisateurs?|comptes?|acces").containsMatchIn(n) && Regex("liste|quels?|affiche|montre|voir").containsMatchIn(n) -> "vpn_utilisateurs"
                vpn && coupe -> "desactiver_vpn"
                vpn && active -> "activer_vpn"
                vpn -> "vpn"
                Regex("\\b(appareils?|connectes|peripheriques?)\\b|qui est connecte|sont connectes").containsMatchIn(n) && box -> "appareils"
                Regex("\\bwi ?fi\\b").containsMatchIn(n) && box && coupe -> "wifi_desactiver"
                Regex("\\bwi ?fi\\b").containsMatchIn(n) && box && active -> "wifi_activer"
                Regex("\\bwi ?fi\\b").containsMatchIn(n) && box -> "wifi"
                Regex("journal|appels? (recus|manques|emis)|qui (m a|a) appele").containsMatchIn(n) && box -> "appels"
                Regex("\\bdmz\\b").containsMatchIn(n) -> return null // paramètres à préciser : le cerveau s'en charge avec l'outil
                box && Regex("etat|statut|debit|connexion|ip publique|fonctionne|marche").containsMatchIn(n) -> "etat"
                box && Regex("droits?|autorisations?|permissions?").containsMatchIn(n) -> "droits"
                else -> return null // ajout de redirection, création d'utilisateur VPN… : paramètres → cerveau + outil freebox
            }
            if (serveur.isNotBlank()) args.put("serveur", serveur) else if (n.contains("wireguard")) args.put("serveur", "wireguard")
            args.put("action", action)
            return action to args
        }

        /**
         * (client, installation) désignés dans [n] (texte normalisé) parmi [clients] (nom → noms d'installations) :
         * l'installation doit être nommée ; le client aussi s'il y a plusieurs installations du même nom. Null si ambigu.
         */
        internal fun cibleInstallation(n: String, clients: List<Pair<String, List<String>>>): Pair<String, String>? {
            fun dans(nom: String) = nom.isNotBlank() && Regex("\\b" + Regex.escape(fr.dd.jarvis.societe.core.normaliser(nom)) + "\\b").containsMatchIn(n)
            val candidats = clients.flatMap { (c, sites) -> sites.filter { dans(it) }.map { c to it } }
                // « Clairotte 3 » nommé : ne pas garder aussi « Clairotte » (nom contenu dans un autre nommé)
                .let { l -> l.filter { (_, s) -> l.none { (_, autre) -> autre != s && autre.length > s.length && fr.dd.jarvis.societe.core.normaliser(autre).contains(fr.dd.jarvis.societe.core.normaliser(s)) } } }
            if (candidats.size == 1) return candidats.first()
            val avecClient = candidats.filter { (c, _) -> dans(c) }
            return avecClient.singleOrNull()
        }

        /** Commande immédiate reconnue sans modèle : (outil, action, valeur), ou null. */
        internal fun actionImmediate(texte: String): Triple<String, String, String>? {
            val n = fr.dd.jarvis.societe.core.normaliser(texte)
            if (n.isBlank() || n.split(' ').size > 12 || Regex("\\b(et|puis|ensuite|si|quand|tous les|chaque)\\b").containsMatchIn(n)) return null
            // Le son, la lecture ou la lumière d'un AUTRE appareil (« baisse le son de la télé ») : jamais le téléphone.
            if (Regex("\\b(tele|tv|television|televiseur|enceintes?|sonos|chaine|ampli|barre de son|home ?assistant|chromecast|player|ordinateur|pc|autoradio|salon|chambre|cuisine|bureau|garage)\\b").containsMatchIn(n)) return null
            val allume = Regex("\\b(allume[rsz]?|active[rsz]?|mets?|ouvre|demarre|lance)\\b")
            val eteins = Regex("\\b(eteins|eteint|eteindre|coupe[rsz]?|desactive[rsz]?|ferme[rsz]?|arrete[rsz]?|stoppe[rsz]?)\\b")
            val nombre = Regex("\\b(\\d{1,3}) ?(%|pour ?cent|pourcent)?").find(n)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
            fun phone(action: String, valeur: String = "") = Triple("phone_action", action, valeur)
            fun media(action: String) = Triple("controle_media", action, "")
            val torche = Regex("\\b(torche|lampe de poche|lampe du telephone|flash)\\b").containsMatchIn(n)
            val wifi = Regex("\\bwi ?fi\\b").containsMatchIn(n) && !Regex("freebox|box|reseau|mot de passe|connecte").containsMatchIn(n)
            val bt = Regex("\\bbluetooth\\b").containsMatchIn(n) && !Regex("apparei|connecte|associe").containsMatchIn(n)
            return when {
                torche && eteins.containsMatchIn(n) -> phone("torch_off")
                torche && allume.containsMatchIn(n) -> phone("torch_on")
                Regex("\\b(volume|son)\\b").containsMatchIn(n) && nombre != null && !Regex("\\b(alarme|reveil|sonnerie)\\b").containsMatchIn(n) -> phone("volume", nombre.toString())
                Regex("\\bcoupe (le son|le volume)\\b|\\bmode muet\\b").containsMatchIn(n) -> phone("volume", "0")
                Regex("\\bluminosite\\b").containsMatchIn(n) && nombre != null -> phone("brightness", nombre.toString())
                wifi && eteins.containsMatchIn(n) -> phone("wifi_off")
                wifi && allume.containsMatchIn(n) -> phone("wifi_on")
                bt && eteins.containsMatchIn(n) -> phone("bluetooth_off")
                bt && allume.containsMatchIn(n) -> phone("bluetooth_on")
                Regex("^(mets? (la musique |la lecture )?en pause|pause( la musique| la lecture)?|stop(pe)? la musique|arrete la musique|coupe la musique)$").containsMatchIn(n) -> media("pause")
                Regex("^(reprends|relance|remets) (la musique|la lecture|le son)$|^(lecture|play)$").containsMatchIn(n) -> media("play")
                Regex("^((chanson|morceau|titre|piste) suivante?|suivant|passe (a la suivante|au suivant|la chanson))$").containsMatchIn(n) -> media("next")
                Regex("^((chanson|morceau|titre|piste) precedente?|precedent|reviens (a la precedente|au precedent))$").containsMatchIn(n) -> media("previous")
                Regex("^verrouille (le telephone|l ecran|mon telephone)$").containsMatchIn(n) -> phone("lock")
                Regex("^(fais|prends) une capture d ecran$").containsMatchIn(n) -> phone("screenshot")
                else -> null
            }
        }

        /** Client dont on attend l'installation concernée par « ma position » (id, horodatage). */
        @Volatile private var attentePosition: Pair<String, Long>? = null
    }
}
