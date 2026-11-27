package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Outils TikTok (compte personnel) : connexion, profil, vidéos + statistiques, conseils d'amélioration. */
class TikTokTools(private val app: JarvisApp, private val confirm: suspend (String, Boolean) -> Boolean) {

    companion object {
        // « vid[ée]o » seul (pas seulement avec « tiktok ») : generer_image mis à part, c'est le seul outil capable de
        // VRAIMENT générer une vidéo (tiktok_video) — sans ce mot-clé large, une demande du type « crée-moi une vidéo
        // sur X » (sans dire « tiktok ») ne proposait pas l'outil au modèle, qui répondait alors en inventant un faux
        // succès au lieu d'appeler quoi que ce soit.
        val KEYWORDS = Regex("tiktok|tik tok|vid[ée]o|montage|clip|reels?|shorts?|niches?|concurren\\w*|hashtags?|tendances?|brouillons? tiktok|id[ée]es? de (vid[ée]os?|contenus?)", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = KEYWORDS.containsMatchIn(t)
        val NAMES = setOf("tiktok_connexion", "tiktok_deconnexion", "tiktok_profil", "tiktok_mes_videos", "tiktok_analyse", "tiktok_script", "tiktok_video", "video_montage",
            "tiktok_tendances", "tiktok_niche", "tiktok_concurrents", "tiktok_propositions", "tiktok_brouillon")
    }

    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun tool(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), req: List<String> = emptyList()): JSONObject {
        val p = JSONObject(); props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(req))))
    }

    val schema: List<JSONObject> = listOf(
        tool("tiktok_connexion", "TikTok : ouvre dans le navigateur la page de connexion du compte TikTok personnel (une seule fois, ou si la connexion a expiré/été révoquée). Nécessite que la Clé client et le Secret client TikTok soient renseignés dans Réglages › Comptes › TikTok."),
        tool("tiktok_deconnexion", "TikTok : déconnecte le compte TikTok (efface les jetons enregistrés)."),
        tool("tiktok_profil", "TikTok : informations du compte connecté (nom, abonnés, nombre de vidéos, total de likes)."),
        tool("tiktok_mes_videos", "TikTok : liste les dernières vidéos du compte connecté avec leurs statistiques (vues, likes, commentaires, partages).",
            mapOf("nombre" to prop("Nombre de vidéos à récupérer, 1 à 20 (optionnel, défaut 15)."))),
        tool("tiktok_analyse", "TikTok : analyse les vidéos du compte (statistiques + bonnes pratiques générales du réseau social) pour dire ce qui marche, ce qui ne marche pas, et comment améliorer les vues/likes. Si l'utilisateur demande d'analyser UNE vidéo précise (par son titre ou son sujet, ex. « analyse ma vidéo sur les pompiers »), passe ce titre/sujet dans le paramètre « video » pour une analyse ciblée sur cette seule vidéo — n'utilise JAMAIS tiktok_mes_videos pour ça (qui ne fait que lister, sans analyser). Sans « video », analyse l'ensemble des dernières vidéos. Ne compare PAS à des vidéos concurrentes précises (données non disponibles publiquement) : s'appuie sur les statistiques propres au compte et les tendances générales connues de TikTok.",
            mapOf(
                "nombre" to prop("Nombre de vidéos à analyser, 1 à 60 (optionnel, défaut 40 ; ignoré si « video » est donné)."),
                "video" to prop("Titre ou mot-clé identifiant UNE vidéo précise du compte à analyser seule, plutôt que l'ensemble (optionnel).")
            )),
        tool("tiktok_script", "TikTok : écrit un script complet et prêt à tourner pour une nouvelle vidéo à partir d'un sujet (accroche, découpage de plans minuté, textes à l'écran, appel à l'action, légende + hashtags). S'appuie sur les statistiques des vidéos précédentes du compte si connecté, pour repérer ce qui marche déjà. Enregistre le script dans le coffre Obsidian (dossier « Scripts TikTok »).",
            mapOf(
                "sujet" to prop("Le sujet ou l'idée de la vidéo."),
                "duree" to prop("Durée visée en secondes (optionnel, ex. 30)."),
                "objectif" to prop("Ce que la vidéo doit privilégier : vues, abonnés, ventes, notoriété… (optionnel).")
            ), listOf("sujet")),
        tool("tiktok_video", "TikTok : APPELLE-LE DIRECTEMENT, sans jamais demander « voulez-vous que je lance… ? » (le montage se lance en arrière-plan et peut être annulé). « En lien avec ma niche » : le sujet est pris dans la niche enregistrée. « Avec Pixabay / Pexels / plans libres de droits » : plans = stock. Crée VRAIMENT une ou plusieurs vidéos TikTok montées (pas juste un script), d'une durée précise : plans générés par IA (fal.ai) et/ou vidéos et images existantes, texte incrusté, voix off, musique ou chanson avec paroles. Le montage tourne en arrière-plan (quelques minutes) et le résultat arrive dans le chat. Ne demande pas de précisions inutiles : valeurs par défaut raisonnables.",
            mapOf(
                "sujet" to prop("Le sujet / ce que doit raconter la vidéo."),
                "script" to prop("Script déjà écrit à suivre (optionnel, ex. sortie de tiktok_script)."),
                "duree" to prop("Durée TOTALE de chaque vidéo en secondes (5 à 180, défaut 30). Le montage enchaîne autant de plans que nécessaire pour l'atteindre exactement."),
                "nombre" to prop("Nombre de vidéos différentes à créer (1 à 5, défaut 1) — chacune avec un angle différent."),
                "format" to prop("vertical (9:16, défaut) ou horizontal (16:9)."),
                "musique" to prop("« auto » (défaut : musique générée selon le sujet), « aucune », un style (ex. « lo-fi calme », « rock énergique »), ou un fichier existant (chemin du coffre, Music/…)."),
                "paroles" to prop("Pour une CHANSON avec paroles chantées : « auto » (paroles écrites par l'IA) ou le texte des paroles. Vide = musique instrumentale."),
                "voix_off" to prop("« auto » (défaut : narration écrite par l'IA et lue par la synthèse vocale), « aucune », ou le texte exact à dire."),
                "textes" to prop("« true » (défaut) pour incruster du texte à l'écran sur chaque plan, « false » sinon."),
                "sources" to prop("Vidéos/images EXISTANTES à monter, séparées par « ; » : chemins ou dossiers du coffre (ex. « JARVIS/Images/Coloriage pompier »), fichiers du téléphone (« DCIM/Camera/xxx.mp4 »), « dernieres_videos:5 », « dernieres_images:8 », jarvis-video-ia:… Des plans IA complètent si la durée n'est pas atteinte."),
                "garder_son" to prop("« true » pour garder le son d'origine des vidéos existantes (mixé sous la musique/voix).")
            )),
        tool("video_montage", "Crée une ou plusieurs vidéos (montage) de durée précise, pour n'importe quel usage (souvenir, présentation, publicité, réseaux…) : enchaîne des plans IA et/ou des vidéos/images existantes jusqu'à la durée voulue, avec texte à l'écran, voix off, musique (générée ou fichier) ou chanson avec paroles chantées. Tourne en arrière-plan ; le résultat arrive dans le chat. Utilise-le pour toute demande de vidéo montée, de clip musical, ou de « montage de plusieurs vidéos ».",
            mapOf(
                "sujet" to prop("Le sujet / ce que doit raconter la vidéo."),
                "script" to prop("Script déjà écrit à suivre (optionnel, ex. sortie de tiktok_script)."),
                "duree" to prop("Durée TOTALE de chaque vidéo en secondes (5 à 180, défaut 30). Le montage enchaîne autant de plans que nécessaire pour l'atteindre exactement."),
                "nombre" to prop("Nombre de vidéos différentes à créer (1 à 5, défaut 1) — chacune avec un angle différent."),
                "format" to prop("vertical (9:16, défaut) ou horizontal (16:9)."),
                "musique" to prop("« auto » (défaut : musique générée selon le sujet), « aucune », un style (ex. « lo-fi calme », « rock énergique »), ou un fichier existant (chemin du coffre, Music/…)."),
                "paroles" to prop("Pour une CHANSON avec paroles chantées : « auto » (paroles écrites par l'IA) ou le texte des paroles. Vide = musique instrumentale."),
                "voix_off" to prop("« auto » (défaut : narration écrite par l'IA et lue par la synthèse vocale), « aucune », ou le texte exact à dire."),
                "textes" to prop("« true » (défaut) pour incruster du texte à l'écran sur chaque plan, « false » sinon."),
                "sources" to prop("Vidéos/images EXISTANTES à monter, séparées par « ; » : chemins ou dossiers du coffre (ex. « JARVIS/Images/Coloriage pompier »), fichiers du téléphone (« DCIM/Camera/xxx.mp4 »), « dernieres_videos:5 », « dernieres_images:8 », jarvis-video-ia:… Des plans IA complètent si la durée n'est pas atteinte."),
                "garder_son" to prop("« true » pour garder le son d'origine des vidéos existantes (mixé sous la musique/voix)."),
                "plans" to prop("« auto » (défaut : images générées pour les symboles comme le phénix, plans réels Pixabay/Pexels pour les scènes filmables), « images » (uniquement des images générées puis animées), « stock » (uniquement des plans réels libres de droits) ou « ia » (clips vidéo générés)."),
                "sous_titres" to prop("« true » (défaut) : sous-titres jaunes synchronisés avec la voix off ; « false » sinon."),
                "style" to prop("Signature et ton de la chaîne quand l'utilisateur les décrit (ex. « phénix bleu blanc rouge comme mes anciennes vidéos, ton engagé ») : retenu pour ses prochaines vidéos. Vide = signature enregistrée.")
            )),
        tool("tiktok_tendances", "TikTok : ce qui marche EN CE MOMENT en France (vidéos tendance réelles, hashtags, sons repris, durées, accroches), via tikwm (gratuit, non officiel), puis comment s'en servir dans la niche de l'utilisateur. Avec « theme », cherche les vidéos qui marchent sur ce thème.",
            mapOf("theme" to prop("Thème précis à chercher (optionnel, ex. « panneaux solaires »). Vide = tendances générales du pays."),
                "pays" to prop("Code pays des tendances (optionnel, défaut FR)."))),
        tool("tiktok_niche", "TikTok : analyse une NICHE avec des données réelles (tikwm) : hashtags et leur volume, créateurs qui dominent, vidéos qui marchent, formats ; puis taille, saturation, sous-niches et angles sous-exploités. Sans mots-clés, utilise la niche enregistrée.",
            mapOf("mots_cles" to prop("Mots-clés de la niche (ex. « maintenance photovoltaïque »). Optionnel si une niche est enregistrée."),
                "memoriser" to prop("« true » pour enregistrer ces mots-clés comme niche de l'utilisateur (propositions hebdomadaires)."))),
        tool("tiktok_concurrents", "TikTok : analyse des comptes CONCURRENTS (publics) avec leurs vrais chiffres (tikwm) : abonnés, vues médianes, engagement, rythme, durées, hashtags, accroches de leurs meilleures vidéos, comparés au compte de l'utilisateur. Sans comptes, utilise ceux enregistrés.",
            mapOf("comptes" to prop("Pseudos ou liens des comptes, séparés par des virgules (ex. « @solaire.pro, @elec_martin »). Optionnel si des concurrents sont enregistrés."),
                "nombre" to prop("Vidéos analysées par compte, 5 à 30 (défaut 20)."),
                "memoriser" to prop("« true » pour enregistrer ces comptes comme concurrents suivis."))),
        tool("tiktok_propositions", "TikTok : propose des idées de vidéos à poster (avec accroche, script minuté, texte à l'écran, son, légende, hashtags et moment de publication), fondées sur les statistiques du compte, les tendances France, la niche et les concurrents. Enregistrées dans le coffre (TikTok/Propositions).",
            mapOf("nombre" to prop("Nombre d'idées, 1 à 6 (défaut 3)."), "theme" to prop("Thème imposé (optionnel ; sinon la niche enregistrée)."))),
        tool("tiktok_brouillon", "TikTok : envoie une vidéo dans les BROUILLONS (boîte de réception) du compte TikTok connecté ; l'utilisateur ajoute le son et publie depuis l'appli TikTok. Par défaut, la dernière vidéo TikTok montée par JARVIS.",
            mapOf("fichier" to prop("Vidéo à envoyer (optionnel) : « derniere » (défaut), jarvis-video-ia:nom.mp4, ou chemin du téléphone (DCIM/Camera/xxx.mp4).")))
    )

    suspend fun execute(name: String, a: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try { run(name, a) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ToolResult("❌ ${e.message ?: e.javaClass.simpleName}") }
    }

    /** Dernière phrase de l'utilisateur (appli ou voiture, 15 min). */
    private fun derniereDemande(): String = runCatching {
        val limite = System.currentTimeMillis() - 15 * 60_000L
        (app.chat.toList() + fr.dd.jarvis.service.CarBridge.contexte()).filter { it.fromUser && it.ts >= limite }.maxByOrNull { it.ts }?.text
    }.getOrNull().orEmpty()

    private fun vraiArg(a: JSONObject, k: String) = a.optString(k).trim().lowercase() in setOf("true", "oui", "1", "yes")

    /** Vidéo à envoyer : la dernière montée par JARVIS (TikTok d'abord), un fichier jarvis-video-ia:…, ou un chemin du téléphone. */
    private fun fichierVideo(s: String): java.io.File? {
        if (s.isBlank() || s.lowercase().startsWith("derni")) {
            val l = VideoGen.dossier(app).listFiles()?.filter { it.extension.lowercase() == "mp4" && it.length() > 0 }.orEmpty()
            return (l.filter { it.name.startsWith("tiktok_") }.ifEmpty { l }).maxByOrNull { it.lastModified() }
        }
        if (s.startsWith("jarvis-video-ia:")) return VideoGen.fichier(app, s)
        return runCatching { java.io.File(fr.dd.jarvis.ai.Stockage.racine(), s.trim('/')).takeIf { it.isFile && it.canRead() } }.getOrNull()
            ?: VideoGen.fichier(app, "jarvis-video-ia:" + s.substringAfterLast('/'))
    }

    private fun titre(o: JSONObject): String = o.optString("title").ifBlank { o.optString("video_description").take(50).ifBlank { "(sans titre)" } }

    private fun video(o: JSONObject): String {
        val date = runCatching { java.time.Instant.ofEpochSecond(o.optLong("create_time")).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }.getOrDefault("?")
        return "- « ${titre(o)} » ($date, ${o.optInt("duration")}s) — 👁️ ${o.optLong("view_count")} · ❤️ ${o.optLong("like_count")} · 💬 ${o.optLong("comment_count")} · 🔁 ${o.optLong("share_count")}"
    }

    private suspend fun run(name: String, a: JSONObject): ToolResult {
        fun configure() { if (!TikTok.configure(app)) throw IllegalStateException("Renseigne d'abord la Clé client et le Secret client TikTok dans Réglages › Comptes › TikTok (depuis ton app TikTok for Developers).") }
        return when (name) {
            "tiktok_connexion" -> {
                configure()
                if (!confirm("Ouvrir le navigateur pour connecter ton compte TikTok ?", true)) return ToolResult("Refusé par l'utilisateur.")
                val state = java.util.UUID.randomUUID().toString()
                app.store.tiktokOAuthState.set(state)
                val err = app.phone.start(android.content.Intent.ACTION_VIEW, TikTok.urlAutorisation(app, state), null)
                if (err.isNotBlank()) ToolResult("❌ Impossible d'ouvrir le navigateur : $err")
                else ToolResult("🔗 Page de connexion TikTok ouverte dans le navigateur — connecte-toi et autorise JARVIS.", direct = true)
            }
            "tiktok_deconnexion" -> { TikTok.deconnecter(app); ToolResult("✅ Compte TikTok déconnecté.") }
            "tiktok_profil" -> {
                if (!TikTok.connecte(app)) return ToolResult("Aucun compte TikTok connecté : utilise tiktok_connexion.")
                val u = TikTok.monProfil(app)
                if (u.optBoolean("sans_stats")) return ToolResult("# 🎵 ${u.optString("display_name")}\n- Vidéos : ${u.optInt("videos_listees")}\n- Likes sur ces vidéos : ${u.optLong("likes_videos")}\n" +
                    "- Abonnés : non disponibles — ajoute le scope user.info.stats à Jarvis21 (developers.tiktok.com), active « Abonnés et likes du profil » dans Réglages › TikTok, puis reconnecte le compte.", direct = true)
                ToolResult("# 🎵 ${u.optString("display_name")}\n- Abonnés : ${u.optLong("follower_count")}\n- Abonnements : ${u.optLong("following_count")}\n- Likes cumulés : ${u.optLong("likes_count")}\n- Vidéos : ${u.optLong("video_count")}", direct = true)
            }
            "tiktok_mes_videos" -> {
                if (!TikTok.connecte(app)) return ToolResult("Aucun compte TikTok connecté : utilise tiktok_connexion.")
                val n = (a.optString("nombre").toIntOrNull() ?: 15).coerceIn(1, 20)
                val vids = TikTok.mesVideos(app, n)
                if (vids.length() == 0) return ToolResult("Aucune vidéo trouvée sur ce compte.")
                ToolResult("# 🎬 Tes ${vids.length()} dernières vidéos\n" + (0 until vids.length()).joinToString("\n") { video(vids.getJSONObject(it)) }, direct = true)
            }
            "tiktok_analyse" -> {
                if (!TikTok.connecte(app)) return ToolResult("Aucun compte TikTok connecté : utilise tiktok_connexion.")
                val filtre = a.optString("video").trim()
                if (filtre.isNotBlank()) {
                    val vids = TikTok.mesVideos(app, 50)
                    if (vids.length() == 0) return ToolResult("Aucune vidéo trouvée sur ce compte : rien à analyser.")
                    val tous = (0 until vids.length()).map { vids.getJSONObject(it) }
                    val mot = filtre.lowercase()
                    val trouvees = tous.filter { titre(it).lowercase().contains(mot) }
                    when {
                        trouvees.isEmpty() -> return ToolResult("Aucune vidéo ne correspond à « $filtre ». Vidéos disponibles :\n" + tous.joinToString("\n") { video(it) }, direct = true)
                        trouvees.size > 1 -> return ToolResult("Plusieurs vidéos correspondent à « $filtre », précise laquelle :\n" + trouvees.joinToString("\n") { video(it) }, direct = true)
                    }
                    val v = trouvees[0]
                    return ToolResult("Statistiques de CETTE vidéo précise du compte TikTok de l'utilisateur (titre : « ${titre(v)} ») :\n${video(v)}\n\n" +
                        "Analyse UNIQUEMENT cette vidéo (ne parle d'AUCUNE autre vidéo du compte) : ce qui explique probablement ses résultats (durée, sujet, moment de publication), ce qui a bien fonctionné et ce qui aurait pu être amélioré, puis 2-3 suggestions concrètes pour une prochaine vidéo similaire. Précise que tu n'as pas accès aux vidéos concurrentes ni aux tendances en temps réel du réseau.")
                }
                val n = (a.optString("nombre").toIntOrNull() ?: 40).coerceIn(1, 60)
                val vids = TikTok.mesVideos(app, n)
                if (vids.length() == 0) return ToolResult("Aucune vidéo trouvée sur ce compte : rien à analyser.")
                // Chiffres calculés par JARVIS (médianes, engagement, jours, heures, durées, hashtags) + la liste brute.
                val tableau = TikTokAnalyse.bilan((0 until vids.length()).map { TikTokAnalyse.depuisApi(vids.getJSONObject(it)) }) +
                    "\n\nDétail :\n" + (0 until vids.length()).joinToString("\n") { video(vids.getJSONObject(it)) }
                // Les données renvoyées ici (titres + stats) nourrissent l'analyse que fera le cerveau (second passage, pas de « direct »)
                // car il doit raisonner et donner des conseils — le texte seul serait juste une liste brute.
                ToolResult("Statistiques des vidéos du compte TikTok de l'utilisateur :\n$tableau\n\n" +
                    "Analyse ces statistiques (quelles vidéos marchent le mieux et pourquoi probablement — durée, sujet, moment), et donne des conseils concrets pour améliorer les vues/likes en t'appuyant sur les bonnes pratiques générales connues de TikTok (accroche dans les 1ers secondes, durée optimale, fréquence de publication, usage des sons tendance, sous-titres, appel à l'interaction, heures de publication). Précise clairement que tu n'as pas accès aux vidéos concurrentes ni aux tendances en temps réel du réseau, seulement à des bonnes pratiques générales et aux statistiques propres au compte.")
            }
            "tiktok_script" -> {
                val sujet = a.optString("sujet").trim()
                if (sujet.isBlank()) return ToolResult("Précise le sujet de la vidéo.")
                val stats = if (TikTok.connecte(app)) runCatching {
                    val vids = TikTok.mesVideos(app, 15)
                    if (vids.length() == 0) "" else "\n\nStatistiques des vidéos précédentes de l'utilisateur (repère ce qui a déjà marché) :\n" +
                        (0 until vids.length()).joinToString("\n") { video(vids.getJSONObject(it)) }
                }.getOrDefault("") else ""
                val user = "Sujet : $sujet" +
                    (a.optString("duree").trim().takeIf { it.isNotBlank() }?.let { "\nDurée visée : ${it}s" } ?: "") +
                    (a.optString("objectif").trim().takeIf { it.isNotBlank() }?.let { "\nObjectif principal : $it" } ?: "") + stats
                val script = app.llm.agent(Prompts.TIKTOK_SCRIPT, user)
                val emplacement = runCatching { app.obsidian.saveIn("Scripts TikTok", sujet.take(60), script) }.getOrNull()
                ToolResult(script + (emplacement?.let { "\n\n📁 $it" } ?: ""), direct = true)
            }
            "tiktok_video", "video_montage" -> {
                // « En lien avec ma niche » (ou aucun sujet) : la niche enregistrée donne le thème.
                val niche = app.store.tiktokNiche.value.trim()
                val brut = a.optString("sujet").trim()
                val sujet = when {
                    niche.isNotBlank() && (brut.isBlank() || Regex("(?i)\\bniche\\b").containsMatchIn(brut)) -> (if (brut.isBlank()) "" else "$brut — ") + "niche du compte : $niche"
                    else -> brut
                }
                val script = a.optString("script").trim()
                if (sujet.isBlank() && script.isBlank() && a.optString("sources").isBlank()) return ToolResult("Précise le sujet de la vidéo (ou enregistre ta niche dans Réglages › TikTok).")
                fun vrai(k: String, def: Boolean) = a.optString(k).trim().lowercase().let { if (it.isBlank()) def else it in setOf("true", "oui", "1", "yes") }
                val d = Montage.Demande(
                    sujet = sujet.ifBlank { script.take(80) }, script = script,
                    dureeS = a.optString("duree").filter { it.isDigit() }.toIntOrNull() ?: 30,
                    format = if (name == "tiktok_video") "vertical" else a.optString("format").ifBlank { "vertical" },
                    nombre = a.optString("nombre").filter { it.isDigit() }.toIntOrNull() ?: 1,
                    musique = a.optString("musique").ifBlank { "auto" },
                    paroles = a.optString("paroles").trim(),
                    voixOff = a.optString("voix_off").ifBlank { "auto" },
                    textes = vrai("textes", true),
                    sources = a.optString("sources").trim(),
                    garderSon = vrai("garder_son", false),
                    tiktok = name == "tiktok_video",
                    // « Avec Pixabay / Pexels / libres de droits » dans la demande : uniquement des plans libres de droits.
                    // « Avec Pixabay / Pexels / libres de droits » dans TA phrase (pas seulement dans le sujet rédigé par l'IA) :
                    // uniquement des plans libres de droits.
                    plans = (brut + " " + script + " " + derniereDemande()).let { demande ->
                        val images = Regex("(?i)images? (ia|g[ée]n[ée]r[ée]e?s?)|(à partir|depuis|avec) (d['e]s? |de |ces |mes )?images|illustrations?|comme (mon|l'|cet) ?exemple").containsMatchIn(demande)
                        val stock = Regex("(?i)pixabay|pexels|libres? de droits").containsMatchIn(demande)
                        when {
                            images && stock -> "auto"     // les deux demandés : images pour les symboles, plans réels pour le reste
                            images -> "images"
                            stock -> "stock"
                            else -> a.optString("plans").ifBlank { "auto" }
                        }
                    },
                    sousTitres = vrai("sous_titres", true),
                    // Brouillons TikTok : automatique dès que la publication est activée et autorisée sur le compte.
                    brouillon = name == "tiktok_video" && app.store.tiktokPublication.value && TikTok.envoiAutorise(app),
                    // Signature de la chaîne (TikTok) : celle donnée maintenant est retenue pour les prochaines vidéos.
                    style = if (name == "tiktok_video") a.optString("style").trim().also { if (it.isNotBlank()) app.store.tiktokStyle.set(it) }.ifBlank { app.store.tiktokStyle.value.trim() } else "",
                    signature = if (name == "tiktok_video") app.store.tiktokSignature.value.trim() else ""
                )
                ToolResult(Montage.lancer(app, d), direct = true)
            }
            "tiktok_tendances" -> {
                val theme = a.optString("theme").trim()
                val pays = a.optString("pays").trim().uppercase().ifBlank { "FR" }
                val donnees = if (theme.isBlank()) TikTokAnalyse.tendances(TikWm.tendances(pays, 30), "Tendances du moment ($pays)")
                    else TikTokAnalyse.niche(theme, runCatching { TikWm.hashtags(theme, 12) }.getOrDefault(emptyList()), TikWm.recherche(theme, 30))
                val niche = app.store.tiktokNiche.value
                ToolResult("Données RÉELLES lues sur TikTok (tikwm) :\n$donnees\n\n" +
                    "Explique ce qui marche en ce moment (formats, durées, sons, hashtags, accroches) en t'appuyant UNIQUEMENT sur ces chiffres, puis donne 3 à 5 façons concrètes de s'en servir" +
                    (if (niche.isNotBlank()) " dans la niche de l'utilisateur (« $niche »)." else " pour le compte de l'utilisateur.") + " Cite les vidéos par leur lien quand c'est utile.")
            }
            "tiktok_niche" -> {
                val mots = a.optString("mots_cles").trim().ifBlank { app.store.tiktokNiche.value.trim() }
                if (mots.isBlank()) return ToolResult("Quelle est ta niche ? Donne 2 ou 3 mots-clés (ex. « maintenance photovoltaïque »).")
                if (vraiArg(a, "memoriser")) app.store.tiktokNiche.set(mots)
                val donnees = TikTokAnalyse.niche(mots, runCatching { TikWm.hashtags(mots, 15) }.getOrDefault(emptyList()), TikWm.recherche(mots, 40))
                ToolResult("Données RÉELLES de la niche (tikwm) :\n$donnees\n\n" +
                    "Analyse cette niche avec ces chiffres uniquement : taille (vues des hashtags), saturation (créateurs qui dominent), formats et durées qui marchent, " +
                    "2 ou 3 sous-niches ou angles sous-exploités, et les comptes à suivre comme concurrents (propose de les enregistrer)." +
                    (if (vraiArg(a, "memoriser")) " Indique que la niche « $mots » est enregistrée pour les propositions du lundi." else ""))
            }
            "tiktok_concurrents" -> {
                val liste = TikTokPropositions.comptes(a.optString("comptes").ifBlank { app.store.tiktokConcurrents.value })
                if (liste.isEmpty()) return ToolResult("Quels comptes concurrents analyser ? Donne leurs pseudos (ex. « @solaire.pro, @elec_martin »).")
                if (vraiArg(a, "memoriser")) app.store.tiktokConcurrents.set(liste.joinToString(", ") { "@$it" })
                val n = (a.optString("nombre").toIntOrNull() ?: 20).coerceIn(5, 30)
                val fiches = liste.take(5).map { c ->
                    runCatching { TikTokAnalyse.concurrent(TikWm.compte(c), TikWm.videosDe(c, n)) }.getOrElse { "## @$c\n(indisponible : ${it.message?.take(140)})" }
                }
                val moi = if (TikTok.connecte(app)) runCatching {
                    val v = TikTok.mesVideos(app, 40)
                    "## 🙋 Ton compte\n" + TikTokAnalyse.bilan((0 until v.length()).map { TikTokAnalyse.depuisApi(v.getJSONObject(it)) })
                }.getOrDefault("") else ""
                ToolResult("Données RÉELLES des concurrents (tikwm)" + (if (moi.isNotBlank()) " et du compte de l'utilisateur (API officielle)" else "") + " :\n\n" +
                    (fiches + listOfNotNull(moi.ifBlank { null })).joinToString("\n\n") + "\n\n" +
                    "Compare avec ces chiffres uniquement : ce que chaque concurrent fait mieux (rythme, durées, accroches, hashtags, sons, moments de publication), " +
                    "ce que l'utilisateur fait mieux, puis 5 actions concrètes à appliquer dès cette semaine." +
                    (if (vraiArg(a, "memoriser")) " Indique que ces comptes sont enregistrés comme concurrents suivis." else ""))
            }
            "tiktok_propositions" -> {
                val n = (a.optString("nombre").toIntOrNull() ?: 3).coerceIn(1, 6)
                ToolResult(TikTokPropositions.generer(app, n, a.optString("theme").trim()), direct = true)
            }
            "tiktok_brouillon" -> {
                if (!TikTok.connecte(app)) return ToolResult("Aucun compte TikTok connecté : utilise tiktok_connexion.")
                val f = fichierVideo(a.optString("fichier").trim()) ?: return ToolResult("Aucune vidéo trouvée à envoyer (donne le nom de la vidéo ou monte-en une d'abord).")
                ToolResult(TikTok.envoyerBrouillon(app, f), direct = true)
            }
            else -> ToolResult("Outil TikTok inconnu.")
        }
    }
}
