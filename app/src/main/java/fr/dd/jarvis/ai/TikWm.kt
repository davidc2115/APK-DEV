package fr.dd.jarvis.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * tikwm.com : service gratuit NON OFFICIEL qui lit les pages publiques de TikTok (comptes, vidéos, recherche,
 * hashtags, tendances par pays). Sans compte ni clé, limité à environ 1 demande par seconde : les appels sont espacés,
 * gardés 30 min en mémoire et réessayés quand le service répond « limite atteinte ». Il peut cesser de fonctionner à
 * tout moment : chaque erreur le dit clairement, rien n'est inventé.
 */
object TikWm {
    data class Video(
        val id: String, val titre: String, val auteur: String, val vues: Long, val likes: Long, val commentaires: Long,
        val partages: Long, val favoris: Long, val dureeS: Int, val creeLe: Long, val musique: String, val musiqueOriginale: Boolean,
        val lecture: String, val couverture: String
    ) {
        val engagement: Double get() = if (vues <= 0) 0.0 else (likes + commentaires + partages + favoris).toDouble() / vues
        val lien: String get() = if (auteur.isNotBlank() && id.isNotBlank()) "https://www.tiktok.com/@$auteur/video/$id" else ""
    }

    data class Compte(val pseudo: String, val nom: String, val abonnes: Long, val likes: Long, val videos: Long, val bio: String, val verifie: Boolean)
    data class Hashtag(val nom: String, val vues: Long, val videos: Long)

    internal var base = "https://www.tikwm.com/api/"
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    private val verrou = Mutex()
    @Volatile private var dernierAppel = 0L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    class Indisponible(message: String) : Exception(message)

    /** Appel GET espacé d'au moins 1,1 s (limite du service gratuit), mis en cache 30 min. */
    internal suspend fun get(chemin: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val url = base + chemin + "?" + params.entries.joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
        cache[url]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return@withContext it.second }
        var derniereErreur = ""
        repeat(4) { essai ->
            val j = verrou.withLock {
                val attente = 1_100L - (System.currentTimeMillis() - dernierAppel)
                if (attente > 0) delay(attente)
                dernierAppel = System.currentTimeMillis()
                runCatching {
                    http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 16) JARVIS").build()).execute().use { r ->
                        val t = r.body?.string().orEmpty()
                        if (r.code == 429) null else if (!r.isSuccessful) throw Indisponible("tikwm a répondu ${r.code}") else JSONObject(t)
                    }
                }.getOrElse { e -> derniereErreur = e.message ?: e.javaClass.simpleName; null }
            }
            if (j != null) {
                val code = j.optInt("code", 0)
                val msg = j.optString("msg")
                if (code == 0) { cache[url] = System.currentTimeMillis() to j; return@withContext j }
                derniereErreur = msg.ifBlank { "code $code" }
                // « Free Api Limit: 1 request/second » : on attend et on réessaie ; une autre erreur est définitive.
                if (!Regex("(?i)limit|too many|busy|try again").containsMatchIn(msg)) throw Indisponible("tikwm : $derniereErreur")
            }
            delay(1_500L * (essai + 1))
        }
        throw Indisponible("tikwm ne répond pas (${derniereErreur.take(120)}). Ce service gratuit non officiel est peut-être saturé ou bloqué : réessaie plus tard.")
    }

    // ------------------------------------------------------------------ lecture des réponses (tolérante)

    private fun JSONObject.long(vararg cles: String): Long = cles.firstNotNullOfOrNull { k -> if (has(k) && !isNull(k)) optLong(k) else null } ?: 0L
    private fun JSONObject.texte(vararg cles: String): String = cles.firstNotNullOfOrNull { k -> optString(k).takeIf { it.isNotBlank() && it != "null" } }.orEmpty()

    internal fun video(o: JSONObject): Video {
        val auteur = o.optJSONObject("author")
        val musique = o.optJSONObject("music_info")
        return Video(
            id = o.texte("video_id", "id", "aweme_id"),
            titre = o.texte("title", "desc"),
            auteur = auteur?.texte("unique_id", "uniqueId").orEmpty(),
            vues = o.long("play_count", "playCount"),
            likes = o.long("digg_count", "diggCount"),
            commentaires = o.long("comment_count", "commentCount"),
            partages = o.long("share_count", "shareCount"),
            favoris = o.long("collect_count", "collectCount"),
            dureeS = o.long("duration").toInt(),
            creeLe = o.long("create_time", "createTime"),
            musique = musique?.let { m -> listOf(m.texte("title"), m.texte("author")).filter { it.isNotBlank() }.joinToString(" — ") }.orEmpty(),
            musiqueOriginale = musique?.optBoolean("original", false) ?: false,
            lecture = o.texte("play", "wmplay"),
            couverture = o.texte("cover", "origin_cover")
        )
    }

    /** Vidéos d'une réponse : « data » est soit une liste, soit un objet avec « videos ». */
    internal fun videos(reponse: JSONObject): List<Video> {
        val d = reponse.opt("data")
        val a: JSONArray? = when (d) {
            is JSONArray -> d
            is JSONObject -> d.optJSONArray("videos") ?: d.optJSONArray("aweme_list") ?: d.optJSONArray("items")
            else -> null
        }
        return a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(::video) } }.orEmpty().filter { it.id.isNotBlank() }
    }

    internal fun compte(reponse: JSONObject): Compte {
        val d = reponse.optJSONObject("data") ?: JSONObject()
        val u = d.optJSONObject("user") ?: d
        val s = d.optJSONObject("stats") ?: d
        return Compte(
            pseudo = u.texte("uniqueId", "unique_id"), nom = u.texte("nickname"),
            abonnes = s.long("followerCount", "follower_count"), likes = s.long("heartCount", "heart", "total_favorited"),
            videos = s.long("videoCount", "aweme_count"), bio = u.texte("signature"), verifie = u.optBoolean("verified", false)
        )
    }

    internal fun hashtags(reponse: JSONObject): List<Hashtag> {
        val d = reponse.opt("data")
        val a = (d as? JSONObject)?.optJSONArray("challenge_list") ?: (d as? JSONArray)
        return a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) } }.orEmpty().map { c ->
            val info = c.optJSONObject("challenge_info") ?: c
            Hashtag(info.texte("cha_name", "title", "name"), info.long("view_count", "viewCount", "video_count_views"), info.long("user_count", "video_count", "videoCount"))
        }.filter { it.nom.isNotBlank() }
    }

    /** « @Mon.Compte », « https://www.tiktok.com/@mon.compte » → « mon.compte ». */
    fun pseudo(saisie: String): String = saisie.trim().substringAfter("tiktok.com/").substringBefore('?').substringBefore("/video")
        .trim('/', ' ').removePrefix("@").trim()

    // ------------------------------------------------------------------ appels

    suspend fun compte(pseudo: String): Compte = compte(get("user/info", mapOf("unique_id" to pseudo(pseudo))))

    suspend fun videosDe(pseudo: String, nombre: Int = 30): List<Video> {
        val tout = mutableListOf<Video>()
        var curseur = "0"
        while (tout.size < nombre) {
            val r = get("user/posts", mapOf("unique_id" to pseudo(pseudo), "count" to "${minOf(35, nombre - tout.size)}", "cursor" to curseur))
            val lot = videos(r)
            if (lot.isEmpty()) break
            tout += lot
            val d = r.optJSONObject("data")
            if (d?.optBoolean("hasMore", false) != true) break
            curseur = d.optString("cursor")
            if (curseur.isBlank()) break
        }
        return tout.distinctBy { it.id }.take(nombre)
    }

    suspend fun recherche(motsCles: String, nombre: Int = 30): List<Video> {
        val tout = mutableListOf<Video>()
        var curseur = "0"
        while (tout.size < nombre) {
            val r = get("feed/search", mapOf("keywords" to motsCles, "count" to "${minOf(30, nombre - tout.size)}", "cursor" to curseur, "region" to "FR"))
            val lot = videos(r)
            if (lot.isEmpty()) break
            tout += lot
            val d = r.optJSONObject("data")
            if (d?.optBoolean("hasMore", false) != true) break
            curseur = d.optString("cursor")
            if (curseur.isBlank()) break
        }
        return tout.distinctBy { it.id }.take(nombre)
    }

    suspend fun hashtags(motsCles: String, nombre: Int = 15): List<Hashtag> =
        hashtags(get("challenge/search", mapOf("keywords" to motsCles, "count" to "$nombre", "cursor" to "0")))

    /** Vidéos tendance d'un pays (« FR ») : le fil « Pour toi » du moment. */
    suspend fun tendances(region: String = "FR", nombre: Int = 30): List<Video> {
        val tout = mutableListOf<Video>()
        repeat(3) {
            if (tout.size >= nombre) return@repeat
            tout += videos(get("feed/list", mapOf("region" to region, "count" to "${minOf(20, nombre)}", "page" to "${it + 1}")))
        }
        return tout.distinctBy { it.id }.take(nombre)
    }

    /** Adresse de la vidéo sans filigrane (pour l'analyser ou la regarder), ou "". */
    suspend fun fichierVideo(lien: String): String {
        val d = get("", mapOf("url" to lien, "hd" to "1")).optJSONObject("data") ?: return ""
        return d.texte("hdplay", "play").let { if (it.startsWith("/")) "https://www.tikwm.com$it" else it }
    }
}
