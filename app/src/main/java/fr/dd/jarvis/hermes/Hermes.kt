package fr.dd.jarvis.hermes

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.ai.ToolBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Client de la passerelle Hermès (API compatible OpenAI sur http://127.0.0.1:8642, jeton Bearer).
 * Hermès raisonne avec Gemini et agit sur le téléphone grâce aux outils de Jarvis exposés par [PontMcp].
 */
object Hermes {
    private val JSON = "application/json".toMediaType()
    private val rapide = OkHttpClient.Builder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(2500, TimeUnit.MILLISECONDS).build()
    private val lent = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(330, TimeUnit.SECONDS).build()

    @Volatile private var enLigneCache = false
    @Volatile private var verifieA = 0L
    @Volatile private var relanceA = 0L

    fun marquerEnLigne(v: Boolean) { enLigneCache = v; verifieA = System.currentTimeMillis() }

    /** Dernier état connu, sans réseau (pour décider vite d'exposer l'outil de délégation). */
    fun enLigneConnu() = enLigneCache

    suspend fun enLigne(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - verifieA < 15_000) return@withContext enLigneCache
        val ok = runCatching {
            rapide.newCall(Request.Builder().url("http://127.0.0.1:${HermesTermux.PORT_API}/health").build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        marquerEnLigne(ok); ok
    }

    /** Hermès est hors ligne alors qu'il est installé : on relance sa passerelle (au plus une fois par minute). */
    fun relancerSiBesoin(app: JarvisApp) {
        if (!app.store.hermesInstalle.value || app.store.hermesMode.value == "off") return
        if (System.currentTimeMillis() - relanceA < 60_000) return
        relanceA = System.currentTimeMillis()
        HermesTermux.demarrer(app)
    }

    private fun systeme(app: JarvisApp, userName: String): String {
        val maintenant = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.FRENCH))
        return """
Tu es Hermès, le cerveau principal de JARVIS, l'assistant personnel de ${userName.ifBlank { "l'utilisateur" }} sur son smartphone Android. Nous sommes le $maintenant.
Réponds toujours en français, de façon naturelle et concise : ta réponse est souvent lue à voix haute (évite les tableaux et le Markdown chargé).
Tu es le chef d'orchestre central de JARVIS : tu ne te limites pas à répondre, tu répartis le travail entre les modèles et agents réellement disponibles, puis tu contrôles et synthétises leurs résultats.
Pour toute mission complexe, multi-étapes ou nécessitant des avis indépendants, décompose le travail et utilise delegate_task pour lancer plusieurs sous-agents en parallèle. Répartis les missions selon leurs compétences et, si le paramètre « option » est proposé, utilise les routes jarvis-gemini, jarvis-groq, jarvis-cloudflare-* ou les routes configurées disponibles. Si ces options ne sont pas proposées, utilise la délégation native sans paramètre de route.
Tu peux confier un sous-problème à un sous-agent orchestrateur (role="orchestrator") lorsque plusieurs niveaux de travail sont réellement nécessaires. Limite les délégations inutiles : une commande simple ou urgente doit rester directe. Ne lance jamais plusieurs agents pour répéter exactement la même tâche ; donne à chacun un objectif et un livrable distincts.
À la fin, compare les résultats, vérifie les contradictions et les faits importants, puis rédige une réponse unique. Les agents ne doivent jamais contourner les confirmations ou les protections de JARVIS pour les actions sensibles.
Ressources IA de JARVIS : Gemini (analyse et raisonnement, clés en rotation), Groq (agent rapide et secours, clés en rotation), Cloudflare Workers AI (secours conversationnel et modèles configurés), ainsi que tout autre fournisseur/modèle déjà configuré dans Hermes. Le tool jarvis_ai_agent permet de consulter directement le moteur IA interne de JARVIS (Gemini puis Groq) comme deuxième avis. Pour les images, utilise l'outil JARVIS generer_image afin de conserver son moteur, ses réglages et son archivage Obsidian.
RÈGLES STRICTES POUR LES VIDÉOS — À APPLIQUER AVANT TOUT APPEL D'OUTIL :
- Interprète d'abord la demande dans sa langue d'origine. Ne la traduis en anglais que pour les champs de description visuelle des modèles vidéo ; conserve exactement le sens, les détails et les contraintes.
- Choisis l'outil selon le résultat demandé, pas seulement selon le mot « vidéo » :
  1) Un clip unique court à partir d'un texte, sans montage narratif multi-plans : generer_video.
  2) Animer une image déjà créée ou fournie : animer_image (ou generer_video avec le paramètre image si l'outil l'exige). Ne remplace jamais l'image source par une description textuelle.
  3) Une histoire racontée en plusieurs plans, un TikTok/Reel/Short, un clip musical, une publicité, un souvenir, ou une vidéo avec durée totale, voix off, musique ou textes : tiktok_video (ou video_montage pour un montage non destiné aux réseaux).
- Pour un montage multi-plans, appelle directement tiktok_video/video_montage avec le brief original complet en « sujet » et, si utile, le script en « script ». Ne fais pas toi-même un résumé qui supprimerait les détails importants. Laisse le moteur storyboard spécialisé de JARVIS préparer les plans ; utilise jarvis_ai_agent uniquement si un second avis est réellement utile ou si la demande est ambiguë.
- Ne demande pas une description en anglais à l'utilisateur. Rédige toi-même les descriptions de plans en anglais uniquement après avoir compris le brief. Chaque plan doit représenter une action visible concrète, pas une suite de mots-clés.
- Préserve les éléments explicitement demandés : nombre et identité des sujets, apparence, vêtements, objets, décor, époque, lieu, action, ordre des événements, style, cadrage et mouvements de caméra. Maintiens la continuité visuelle entre les plans.
- N'ajoute pas de personnages, animaux, accessoires, lieux, événements, textes, logos, rebondissements ou effets qui changeraient le sens. Si un détail manque, choisis la solution la plus neutre ; si l'ambiguïté change fortement le résultat, pose une seule question ciblée avant de lancer la génération.
- Vérifie que l'outil choisi correspond réellement au livrable et à la durée demandée. Un clip Wan 2.2 est court (2–5 s) ; ne le présente jamais comme un montage long. Un montage multi-plans doit utiliser la durée totale demandée.
- Après l'appel, contrôle le résultat de l'outil et signale honnêtement les limites ou échecs. Ne prétends jamais qu'une vidéo est créée avant le résultat réel.
Tu travailles en équipe :
- toi (Hermès) : raisonnement, tâches en plusieurs étapes, recherche web, rédaction, code et scripts dans Termux, mémoire long terme, compétences et tâches planifiées ;
- les outils de JARVIS, via le serveur MCP « jarvis » (outils mcp__jarvis__*) : téléphone (appels, SMS, applis, réglages), agenda, mails, contacts, musique, réveils, domotique, coffre Obsidian (obsidian_fichiers, obsidian_lire…), société (clients, devis, factures, paie, KML), localisation précise (ma_position), génération d'images/vidéos. Pour TOUTE action sur le téléphone ou les données de l'utilisateur, utilise ces outils plutôt que des commandes shell ; n'invente jamais leur contenu.
Les textes renvoyés par les outils de JARVIS sont déjà mis en forme : résume-les au lieu de les recopier en entier.
""".trim() + app.memo.toutes().let { if (it.isBlank()) "" else "\n\nConsignes mémorisées par JARVIS :$it" }
    }

    /**
     * Confie la conversation à Hermès. Renvoie sa réponse, ou null s'il est indisponible (l'appelant bascule alors
     * sur le cerveau Groq). Pendant l'appel, les outils exposés à Hermès sont ceux de [boite] (mêmes confirmations).
     */
    suspend fun repondre(app: JarvisApp, history: List<ChatMsg>, boite: ToolBox?, userName: String, onStatus: (String) -> Unit): String? {
        if (!enLigne()) { relancerSiBesoin(app); return null }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systeme(app, userName)))
        val recents = history.takeLast(12)
        recents.forEachIndexed { i, m ->
            val cap = if (recents.size - 1 - i < 3) 8000 else 2500
            messages.put(JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", m.text.take(cap)))
        }
        return appeler(app, messages, boite, onStatus)
    }

    /** Délégation d'une tâche précise (outil hermes_deleguer du cerveau Groq). */
    suspend fun deleguer(app: JarvisApp, tache: String, boite: ToolBox?, userName: String): String? {
        if (!enLigne(force = true)) { relancerSiBesoin(app); return null }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systeme(app, userName)))
            .put(JSONObject().put("role", "user").put("content", tache))
        return appeler(app, messages, boite) {}
    }

    private suspend fun appeler(app: JarvisApp, messages: JSONArray, boite: ToolBox?, onStatus: (String) -> Unit): String? {
        PontMcp.demarrer(app)
        val precedente = PontMcp.boiteActive
        if (boite != null) PontMcp.boiteActive = boite
        onStatus("🪽 Hermès réfléchit…")
        return try {
            withContext(Dispatchers.IO) {
                val corps = JSONObject().put("model", "hermes-agent").put("messages", messages).put("stream", false)
                val req = Request.Builder().url("http://127.0.0.1:${HermesTermux.PORT_API}/v1/chat/completions")
                    .header("Authorization", "Bearer ${app.store.hermesCle.value}")
                    .header("X-Hermes-Session-Key", "jarvis")
                    .post(corps.toString().toRequestBody(JSON)).build()
                lent.newCall(req).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        if (r.code == 401) HermesTermux.message = "⚠️ Hermès refuse la clé de Jarvis : touche « Configurer Hermès »."
                        return@withContext null
                    }
                    JSONObject(txt).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                        ?.optString("content").orEmpty().trim().ifBlank { null }
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            marquerEnLigne(false); relancerSiBesoin(app); null
        } finally {
            PontMcp.boiteActive = precedente
        }
    }
}

/**
 * Répartition des demandes (mode « auto ») :
 *  - Hermès, orchestrateur principal : reçoit toutes les demandes en mode auto, décompose les missions et appelle
 *    les outils MCP de Jarvis pour les actions téléphone, agenda, mails, domotique, société et fichiers ;
 *  - Jarvis/Groq : réponses très brèves et mode Android Auto, puis secours automatique si Hermès est indisponible ;
 *  - Gemini, Groq et Cloudflare : modèles spécialisés et routes de sous-agents, avec les autres fournisseurs Hermes configurés.
 */
object Routeur {
    enum class Cible { HERMES, JARVIS }
    data class Choix(val cible: Cible, val raison: String)

    private val NOMME = Regex("\\bherm[eè]s\\b", RegexOption.IGNORE_CASE)
    private val COMMANDE = Regex(
        "\\b(appel(le|er)?|t[ée]l[ée]phone (à|a)|rappelle|sms|texto|envoie (un |le )?(message|mail|sms)|r[ée]pond(s|re) (à|au)|" +
            "musique|chanson|playlist|joue|mets (du|de la|la|le|un)|lecture|pause|suivant|volume|" +
            "alarme|r[ée]veil|minuteur|chrono|rappel|agenda|calendrier|planning|rendez.vous|rdv|[ée]v[ée]nement|" +
            "allume|[ée]teins|lumi[èe]re|lampe|torche|chauffage|po[êe]le|thermostat|volet|prise|wi-?fi|bluetooth|luminosit[ée]|" +
            "itin[ée]raire|navigation|emm[èe]ne|guide.moi|waze|trajet|" +
            "ouvre|lance (l.app|l.application)|ferme|capture|photo|" +
            "devis|factur|client|fiche|paie|bulletin|tarif|kml|google earth|" +
            "obsidian|coffre|dossier|fichier|note|" +
            "image|dessin|coloriage|vid[ée]o|tiktok|discord|imprim|scann|" +
            "m[ée]t[ée]o|quelle heure|batterie|ma position|ma localisation|o[uù] suis.je|" +
            "mails?|courriels?|bo[iî]te de r[ée]ception|contacts?|r[ée]glage)",
        RegexOption.IGNORE_CASE
    )
    private val COMPLEXE = Regex(
        "recherch|sur internet|sur le web|compar|analys|expliqu|pourquoi|comment (faire|fonctionne|marche)|r[ée]dige|[ée]cris (un|une|moi)|" +
            "r[ée]sum|synth[èe]se|rapport|article|code|script|programme|python|bash|automatis|tous les (jours|matins|soirs|lundis|mois)|" +
            "chaque (jour|semaine|mois|matin)|surveille|planifie|plan d.action|strat[ée]gie|[ée]tapes|projet|apprends|comp[ée]tence|" +
            "conseil|id[ée]es?|propose|optimise|calcul|traduis|https?://",
        RegexOption.IGNORE_CASE
    )
    private val BREF = Regex("^(merci|ok|okay|d.accord|super|parfait|salut|bonjour|bonsoir|coucou|oui|non|stop|annule|c.est bon)[ !.,]*\\S{0,12}$", RegexOption.IGNORE_CASE)

    fun choisir(app: JarvisApp, texte: String): Choix {
        val mode = app.store.hermesMode.value
        if (mode == "off" || !app.store.hermesInstalle.value) return Choix(Cible.JARVIS, "Hermès désactivé")
        val t = texte.trim()
        if (NOMME.containsMatchIn(t)) return Choix(Cible.HERMES, "Hermès demandé")
        if (mode != "auto") return Choix(Cible.JARVIS, "Hermès seulement sur demande")
        if (app.enVoiture) return Choix(Cible.JARVIS, "Android Auto : réponse rapide")
        if (BREF.matches(t)) return Choix(Cible.JARVIS, "réponse courte")
        val complexe = COMPLEXE.containsMatchIn(t)
        return Choix(Cible.HERMES, when {
            COMMANDE.containsMatchIn(t) -> "action confiée à Hermès via les outils MCP de Jarvis"
            complexe -> "tâche complexe"
            else -> "orchestrateur principal"
        })
    }
}
