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
        val KEYWORDS = Regex("tiktok|tik tok|vid[ée]o|montage|clip|reels?|shorts?", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = KEYWORDS.containsMatchIn(t)
        val NAMES = setOf("tiktok_connexion", "tiktok_deconnexion", "tiktok_profil", "tiktok_mes_videos", "tiktok_analyse", "tiktok_script", "tiktok_video", "video_montage")
    }

    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun tool(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), req: List<String> = emptyList()): JSONObject {
        val p = JSONObject(); props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(req))))
    }

    val schema: List<JSONObject> = listOf(
        tool("tiktok_connexion", "TikTok : ouvre dans le navigateur la page de connexion du compte TikTok personnel (une seule fois, ou si la connexion a expiré/été révoquée). Nécessite que la Clé client et le Secret client TikTok soient renseignés dans Réglages › TikTok."),
        tool("tiktok_deconnexion", "TikTok : déconnecte le compte TikTok (efface les jetons enregistrés)."),
        tool("tiktok_profil", "TikTok : informations du compte connecté (nom, abonnés, nombre de vidéos, total de likes)."),
        tool("tiktok_mes_videos", "TikTok : liste les dernières vidéos du compte connecté avec leurs statistiques (vues, likes, commentaires, partages).",
            mapOf("nombre" to prop("Nombre de vidéos à récupérer, 1 à 20 (optionnel, défaut 15)."))),
        tool("tiktok_analyse", "TikTok : analyse les vidéos du compte (statistiques + bonnes pratiques générales du réseau social) pour dire ce qui marche, ce qui ne marche pas, et comment améliorer les vues/likes. Si l'utilisateur demande d'analyser UNE vidéo précise (par son titre ou son sujet, ex. « analyse ma vidéo sur les pompiers »), passe ce titre/sujet dans le paramètre « video » pour une analyse ciblée sur cette seule vidéo — n'utilise JAMAIS tiktok_mes_videos pour ça (qui ne fait que lister, sans analyser). Sans « video », analyse l'ensemble des dernières vidéos. Ne compare PAS à des vidéos concurrentes précises (données non disponibles publiquement) : s'appuie sur les statistiques propres au compte et les tendances générales connues de TikTok.",
            mapOf(
                "nombre" to prop("Nombre de vidéos à analyser, 1 à 20 (optionnel, défaut 15 ; ignoré si « video » est donné)."),
                "video" to prop("Titre ou mot-clé identifiant UNE vidéo précise du compte à analyser seule, plutôt que l'ensemble (optionnel).")
            )),
        tool("tiktok_script", "TikTok : écrit un script complet et prêt à tourner pour une nouvelle vidéo à partir d'un sujet (accroche, découpage de plans minuté, textes à l'écran, appel à l'action, légende + hashtags). S'appuie sur les statistiques des vidéos précédentes du compte si connecté, pour repérer ce qui marche déjà. Enregistre le script dans le coffre Obsidian (dossier « Scripts TikTok »).",
            mapOf(
                "sujet" to prop("Le sujet ou l'idée de la vidéo."),
                "duree" to prop("Durée visée en secondes (optionnel, ex. 30)."),
                "objectif" to prop("Ce que la vidéo doit privilégier : vues, abonnés, ventes, notoriété… (optionnel).")
            ), listOf("sujet")),
        tool("tiktok_video", "TikTok : crée VRAIMENT une ou plusieurs vidéos TikTok montées (pas juste un script), d'une durée précise : plans générés par IA (fal.ai) et/ou vidéos et images existantes, texte incrusté, voix off, musique ou chanson avec paroles. Le montage tourne en arrière-plan (quelques minutes) et le résultat arrive dans le chat. Ne demande pas de précisions inutiles : valeurs par défaut raisonnables.",
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
                "garder_son" to prop("« true » pour garder le son d'origine des vidéos existantes (mixé sous la musique/voix).")
            ))
    )

    suspend fun execute(name: String, a: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try { run(name, a) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ToolResult("❌ ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun titre(o: JSONObject): String = o.optString("title").ifBlank { o.optString("video_description").take(50).ifBlank { "(sans titre)" } }

    private fun video(o: JSONObject): String {
        val date = runCatching { java.time.Instant.ofEpochSecond(o.optLong("create_time")).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }.getOrDefault("?")
        return "- « ${titre(o)} » ($date, ${o.optInt("duration")}s) — 👁️ ${o.optLong("view_count")} · ❤️ ${o.optLong("like_count")} · 💬 ${o.optLong("comment_count")} · 🔁 ${o.optLong("share_count")}"
    }

    private suspend fun run(name: String, a: JSONObject): ToolResult {
        fun configure() { if (!TikTok.configure(app)) throw IllegalStateException("Renseigne d'abord la Clé client et le Secret client TikTok dans Réglages › TikTok (depuis ton app TikTok for Developers).") }
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
                val n = (a.optString("nombre").toIntOrNull() ?: 15).coerceIn(1, 20)
                val vids = TikTok.mesVideos(app, n)
                if (vids.length() == 0) return ToolResult("Aucune vidéo trouvée sur ce compte : rien à analyser.")
                val tableau = (0 until vids.length()).joinToString("\n") { video(vids.getJSONObject(it)) }
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
                val sujet = a.optString("sujet").trim()
                val script = a.optString("script").trim()
                if (sujet.isBlank() && script.isBlank() && a.optString("sources").isBlank()) return ToolResult("Précise le sujet de la vidéo (ou les vidéos/images à monter).")
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
                    tiktok = name == "tiktok_video"
                )
                ToolResult(Montage.lancer(app, d), direct = true)
            }
            else -> ToolResult("Outil TikTok inconnu.")
        }
    }
}
