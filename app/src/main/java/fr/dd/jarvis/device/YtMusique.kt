package fr.dd.jarvis.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import fr.dd.jarvis.ai.Http
import fr.dd.jarvis.societe.core.normaliser
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catalogue de YT Music Morphe (le même que celui de l'appli, sans compte) : JARVIS cherche lui-même les titres,
 * albums, artistes et playlists, choisit le bon résultat, puis demande à YT Music Morphe — et à elle seule — de lire CE
 * résultat précis par son lien (music.youtube.com/watch?v=…). Plus de pilotage d'écran pour taper dans la recherche :
 * c'est rapide et exact. Les résultats servent aussi à l'écran Musique (choisir parmi les résultats).
 */
object YtMusique {
    enum class Type(val libelle: String, val icone: String) {
        TITRE("Titre", "🎵"), VIDEO("Vidéo", "🎬"), ALBUM("Album", "💿"), ARTISTE("Artiste", "🎤"), PLAYLIST("Playlist", "📃"), AUTRE("Autre", "•")
    }

    data class Resultat(
        val type: Type, val titre: String, val sousTitre: String,
        val videoId: String? = null, val playlistId: String? = null, val browseId: String? = null,
        val image: String? = null, val enTete: Boolean = false,
    ) {
        /** Lien qui fait LIRE le résultat dans l'appli (titre, album/playlist entière, radio de l'artiste). */
        fun lien(radio: Boolean = false): String? = when (type) {
            Type.TITRE, Type.VIDEO -> videoId?.let { if (radio) "https://music.youtube.com/watch?v=$it&list=RDAMVM$it" else "https://music.youtube.com/watch?v=$it" }
            Type.ALBUM, Type.PLAYLIST, Type.ARTISTE -> playlistId?.let { "https://music.youtube.com/watch?list=$it" }
                ?: browseId?.let { b -> if (b.startsWith("VL")) "https://music.youtube.com/watch?list=${b.removePrefix("VL")}" else if (b.startsWith("UC")) "https://music.youtube.com/channel/$b" else "https://music.youtube.com/browse/$b" }
            Type.AUTRE -> videoId?.let { "https://music.youtube.com/watch?v=$it" }
        }
        fun ligne() = "${type.icone} $titre" + (if (sousTitre.isNotBlank()) " — $sousTitre" else "")
        fun cle() = videoId ?: playlistId ?: browseId ?: titre
    }

    /** Filtres de recherche du moteur YouTube Music. */
    val FILTRES = linkedMapOf(
        "tout" to "",
        "titres" to "EgWKAQIIAWoMEA4QChADEAQQCRAF",
        "albums" to "EgWKAQIYAWoMEA4QChADEAQQCRAF",
        "artistes" to "EgWKAQIgAWoMEA4QChADEAQQCRAF",
        "playlists" to "EgeKAQQoADgBagwQDhAKEAMQBBAJEAU=",
        "videos" to "EgWKAQIQAWoMEA4QChADEAQQCRAF",
    )

    /** Derniers résultats montrés (pour « mets le 2 » / musique_choisir), dans l'ordre affiché. */
    @Volatile var derniers: List<Resultat> = emptyList()
        private set

    fun retenir(l: List<Resultat>) { derniers = l }

    private fun contexte(): JSONObject {
        val loc = java.util.Locale.getDefault()
        return JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX")
            .put("clientVersion", "1." + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(System.currentTimeMillis() - 86_400_000L)) + ".01.00")
            .put("hl", loc.language.ifBlank { "fr" }).put("gl", loc.country.ifBlank { "FR" }))
    }

    private fun appel(point: String, corps: JSONObject): String {
        val req = Request.Builder().url("https://music.youtube.com/youtubei/v1/$point?prettyPrint=false")
            .header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            .post(corps.put("context", contexte()).toString().toRequestBody(Http.JSON)).build()
        Http.client.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("YouTube Music ${r.code}")
            return t
        }
    }

    /** Recherche (filtre : tout, titres, albums, artistes, playlists, videos). Réseau : à appeler hors du fil principal. */
    fun rechercher(requete: String, filtre: String = "tout"): List<Resultat> {
        val corps = JSONObject().put("query", requete)
        FILTRES[filtre]?.takeIf { it.isNotBlank() }?.let { corps.put("params", it) }
        val l = analyser(appel("search", corps))
        // Filtre « titres » vide (catalogue restreint dans certains cas) : recherche générale, titres et vidéos gardés.
        val res = if (l.isEmpty() && filtre == "titres") analyser(appel("search", JSONObject().put("query", requete))).filter { it.type == Type.TITRE || it.type == Type.VIDEO } else l
        derniers = res
        return res
    }

    // ------------------------------------------------------------------ lecture de la réponse (pur, testable)

    private fun runs(o: JSONObject?): JSONArray? = o?.optJSONArray("runs")
    private fun texte(o: JSONObject?): String = runs(o)?.let { r -> (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()

    /** Toutes les valeurs d'une clé, en profondeur. */
    private fun valeurs(o: Any?, cle: String, out: MutableList<String> = mutableListOf()): MutableList<String> {
        when (o) {
            is JSONObject -> o.keys().forEach { k -> val v = o.opt(k); if (k == cle && v is String) out += v else valeurs(v, cle, out) }
            is JSONArray -> for (i in 0 until o.length()) valeurs(o.opt(i), cle, out)
        }
        return out
    }

    private fun image(o: JSONObject?): String? {
        val t = o?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return null
        return t.optJSONObject(t.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
    }

    private fun typePage(p: String?): Type? = when (p) {
        "MUSIC_PAGE_TYPE_ALBUM" -> Type.ALBUM
        "MUSIC_PAGE_TYPE_ARTIST" -> Type.ARTISTE
        "MUSIC_PAGE_TYPE_PLAYLIST" -> Type.PLAYLIST
        null -> null
        else -> Type.AUTRE
    }

    private fun typeVideo(v: String?): Type = when (v) {
        "MUSIC_VIDEO_TYPE_ATV" -> Type.TITRE
        "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" -> Type.AUTRE
        else -> Type.VIDEO
    }

    private val PREFIXES = mapOf("titre" to Type.TITRE, "song" to Type.TITRE, "video" to Type.VIDEO, "album" to Type.ALBUM, "ep" to Type.ALBUM,
        "single" to Type.ALBUM, "artiste" to Type.ARTISTE, "artist" to Type.ARTISTE, "playlist" to Type.PLAYLIST,
        "episode" to Type.AUTRE, "podcast" to Type.AUTRE, "profil" to Type.AUTRE, "profile" to Type.AUTRE)

    private fun playlistPour(type: Type, o: JSONObject, browseId: String?, videoId: String?): String? {
        val ids = valeurs(o, "playlistId")
        return when (type) {
            Type.ALBUM -> ids.firstOrNull { it.startsWith("OLAK5uy_") } ?: ids.firstOrNull()
            Type.PLAYLIST -> browseId?.takeIf { it.startsWith("VL") }?.removePrefix("VL") ?: ids.firstOrNull()
            Type.ARTISTE -> ids.firstOrNull { it.startsWith("RDAO") } ?: ids.firstOrNull { it.startsWith("RDEM") } ?: ids.firstOrNull()
            else -> videoId?.let { "RDAMVM$it" }
        }
    }

    private fun element(it: JSONObject, enTete: Boolean = false): Resultat? {
        val colonnes = it.optJSONArray("flexColumns") ?: return null
        fun col(i: Int) = colonnes.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        val titre = texte(col(0)).trim().ifBlank { return null }
        val sous = (1 until colonnes.length()).map { texte(col(it)).trim() }.filter { it.isNotBlank() }.joinToString(" • ")
        val nav = it.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        val browseId = nav?.optString("browseId")?.takeIf { b -> b.isNotBlank() }
        val page = nav?.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
        val watch = runs(col(0))?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
        val videoId = watch?.optString("videoId")?.takeIf { v -> v.isNotBlank() } ?: it.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { v -> v.isNotBlank() }
        val typeVid = watch?.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")?.optString("musicVideoType")
            ?: valeurs(it.optJSONObject("overlay"), "musicVideoType").firstOrNull()
        val prefixe = normaliser(sous.substringBefore(" • ")).trim()
        var type = typePage(page) ?: if (videoId != null) typeVideo(typeVid) else PREFIXES[prefixe] ?: Type.AUTRE
        if (prefixe == "titre" || prefixe == "song") type = Type.TITRE
        val sousTitre = if (PREFIXES.containsKey(prefixe)) sous.substringAfter(" • ", "") else sous
        return Resultat(type, titre, sousTitre, videoId, playlistPour(type, it, browseId, videoId), browseId, image(it.optJSONObject("thumbnail")), enTete)
    }

    private fun carte(c: JSONObject): Resultat? {
        val premier = runs(c.optJSONObject("title"))?.optJSONObject(0) ?: return null
        val titre = premier.optString("text").trim().ifBlank { return null }
        val nav = premier.optJSONObject("navigationEndpoint")
        val watch = nav?.optJSONObject("watchEndpoint")
        val browse = nav?.optJSONObject("browseEndpoint")
        val sous = texte(c.optJSONObject("subtitle")).trim()
        val prefixe = normaliser(sous.substringBefore(" • ")).trim()
        val videoId = watch?.optString("videoId")?.takeIf { it.isNotBlank() }
        val page = browse?.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
        var type = typePage(page) ?: if (videoId != null) typeVideo(watch.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")?.optString("musicVideoType")) else PREFIXES[prefixe] ?: Type.AUTRE
        if (prefixe == "titre" || prefixe == "song") type = Type.TITRE
        val browseId = browse?.optString("browseId")?.takeIf { it.isNotBlank() }
        val sousTitre = if (PREFIXES.containsKey(prefixe)) sous.substringAfter(" • ", "") else sous
        return Resultat(type, titre, sousTitre, videoId, playlistPour(type, c, browseId, videoId), browseId, image(c.optJSONObject("thumbnail")), enTete = true)
    }

    /** Résultats d'une réponse de recherche (meilleur résultat en tête, puis les listes), sans doublons. */
    internal fun analyser(json: String): List<Resultat> {
        val out = mutableListOf<Resultat>()
        fun parcourir(o: Any?) {
            when (o) {
                is JSONObject -> {
                    o.optJSONObject("musicCardShelfRenderer")?.let { c -> carte(c)?.let { out += it }; parcourir(c.optJSONArray("contents")); return }
                    o.optJSONObject("musicResponsiveListItemRenderer")?.let { element(it)?.let { r -> out += r }; return }
                    o.keys().forEach { k -> parcourir(o.opt(k)) }
                }
                is JSONArray -> for (i in 0 until o.length()) parcourir(o.opt(i))
            }
        }
        parcourir(runCatching { JSONObject(json) }.getOrNull())
        return out.filter { it.type != Type.AUTRE || it.videoId != null }.distinctBy { it.cle() }
    }

    // ------------------------------------------------------------------ choix du bon résultat

    private val PARASITES = listOf("live", "cover", "remix", "slowed", "reverb", "karaoke", "instrumental", "lyrics", "paroles", "8d", "sped up", "nightcore", "extended", "reaction")

    /** Type demandé d'après la phrase (« l'album … », « la playlist … », « du Stromae »…). */
    fun typeVoulu(requete: String): Type? {
        val n = " " + normaliser(requete) + " "
        return when {
            Regex(" (l )?album ").containsMatchIn(n) -> Type.ALBUM
            Regex(" playlist ").containsMatchIn(n) -> Type.PLAYLIST
            Regex(" (de l artiste|artiste) ").containsMatchIn(n) -> Type.ARTISTE
            else -> null
        }
    }

    internal fun score(requete: String, r: Resultat, voulu: Type?): Double {
        val q = normaliser(requete).split(' ').filter { it.length > 1 && it !in setOf("album", "playlist", "artiste", "titre", "chanson", "morceau", "le", "la", "les", "de", "du", "des") }
        val cible = normaliser(r.titre + " " + r.sousTitre)
        val mots = cible.split(' ').toSet()
        var s = q.count { it in mots } * 2.0 + q.count { m -> m !in mots && cible.contains(m) } * 0.8
        if (q.isNotEmpty() && q.all { cible.contains(it) }) s += 3
        s += when (r.type) { Type.TITRE -> 2.5; Type.VIDEO -> 1.0; Type.ALBUM, Type.PLAYLIST, Type.ARTISTE -> 0.5; Type.AUTRE -> -6.0 }
        if (voulu != null) s += if (r.type == voulu) 6 else -2
        // Seulement le nom d'un artiste : sa radio (ses titres) plutôt qu'un seul morceau.
        if (voulu == null && r.type == Type.ARTISTE && normaliser(r.titre) == normaliser(requete)) s += 5
        if (r.enTete) s += 1.5
        PARASITES.forEach { p -> if (cible.contains(p) && !normaliser(requete).contains(p)) s -= 2.5 }
        return s
    }

    fun meilleur(requete: String, l: List<Resultat>): Resultat? {
        val voulu = typeVoulu(requete)
        return l.filter { it.lien() != null }.maxByOrNull { score(requete, it, voulu) }
    }

    // ------------------------------------------------------------------ lecture dans l'appli

    /** Complète un album / un artiste sans liste de lecture (page de l'album ou de l'artiste lue). */
    fun completer(r: Resultat): Resultat {
        if (r.playlistId != null || r.browseId == null || r.type !in setOf(Type.ALBUM, Type.ARTISTE)) return r
        val page = runCatching { appel("browse", JSONObject().put("browseId", r.browseId)) }.getOrNull() ?: return r
        val id = when (r.type) {
            Type.ALBUM -> Regex("OLAK5uy_[A-Za-z0-9_-]+").find(page)?.value
            else -> Regex("\"playlistId\":\"(RDAO[A-Za-z0-9_-]+|RDEM[A-Za-z0-9_-]+)\"").find(page)?.groupValues?.get(1)
        }
        return if (id != null) r.copy(playlistId = id) else r
    }

    /** Ouvre le résultat DANS YT Music Morphe ([paquet]) : la lecture démarre sur ce résultat précis. */
    fun ouvrir(ctx: Context, paquet: String, r: Resultat, radio: Boolean = false): Boolean {
        val lien = r.lien(radio) ?: return false
        return runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lien)).setPackage(paquet).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            true
        }.getOrDefault(false)
    }

    fun ouvrirLien(ctx: Context, paquet: String, lien: String): Boolean = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lien)).setPackage(paquet).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)); true
    }.getOrDefault(false)

    /** YT Music Morphe si elle est installée (JAMAIS l'appli YouTube Music officielle). */
    @Suppress("UNUSED_PARAMETER")
    fun paquet(ctx: Context, prefere: String? = null): String? =
        Musique.MORPHE_PACKAGE.takeIf { runCatching { ctx.packageManager.getPackageInfo(it, 0); true }.getOrDefault(false) }

    const val YTM_OFFICIEL = "com.google.android.apps.youtube.music"
    fun estYtm(paquet: String?) = paquet == Musique.MORPHE_PACKAGE
    const val NOM = "YT Music Morphe"

    /** Liste numérotée des résultats (réponse de JARVIS, pour en choisir un autre). */
    fun liste(l: List<Resultat>, max: Int = 6): String = l.take(max).mapIndexed { i, r -> "${i + 1}. ${r.ligne()}" }.joinToString("\n")
}
