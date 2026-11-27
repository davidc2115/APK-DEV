package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Plans vidéo libres de droits et GRATUITS pour les montages : Pexels (200 recherches/heure, 20 000/mois) puis Pixabay
 * (100 recherches/minute). Clés gratuites à créer sur pexels.com/api et pixabay.com/api/docs. Les auteurs sont crédités
 * dans la note du montage, comme le demandent les deux services.
 */
object Stock {
    data class Clip(val id: String, val url: String, val source: String, val auteur: String, val page: String, val dureeS: Int)

    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun disponible(app: JarvisApp) = app.store.pexelsKey.value.isNotBlank() || app.store.pixabayKey.value.isNotBlank()

    private val VIDES = setOf("a", "an", "the", "of", "in", "on", "with", "and", "at", "to", "for", "by", "from", "shot", "camera", "close", "up",
        "cinematic", "realistic", "lighting", "light", "style", "scene", "view", "angle", "different", "slow", "motion", "vertical", "frame")

    /** Mots-clés de recherche tirés d'un prompt de plan (en anglais) quand le scénario n'en donne pas. */
    fun motsCles(visuel: String, max: Int = 4): String = visuel.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ')
        .filter { it.length > 2 && it !in VIDES }.distinct().take(max).joinToString(" ")

    /** Fichier Pexels le plus adapté : mp4, bonne orientation, hauteur proche de 1280 (vertical) ou de 720 (horizontal). */
    internal fun choisirPexels(v: JSONObject, vertical: Boolean): String? {
        val fichiers = v.optJSONArray("video_files") ?: return null
        val cible = if (vertical) 1280 else 720
        return (0 until fichiers.length()).mapNotNull { fichiers.optJSONObject(it) }
            .filter { it.optString("file_type") == "video/mp4" && it.optInt("width") > 0 && it.optString("link").startsWith("https://") }
            .filter { f -> if (vertical) f.optInt("height") >= f.optInt("width") else f.optInt("width") >= f.optInt("height") }
            .filter { f -> minOf(f.optInt("width"), f.optInt("height")) in 480..1440 }
            .minByOrNull { f -> kotlin.math.abs(maxOf(f.optInt("width"), f.optInt("height")) - cible) }?.optString("link")
    }

    /** Fichier Pixabay : « medium » (≈ 1280 px), sinon « small », sinon « large ». */
    internal fun choisirPixabay(h: JSONObject): String? {
        val v = h.optJSONObject("videos") ?: return null
        return listOf("medium", "small", "large").firstNotNullOfOrNull { k -> v.optJSONObject(k)?.optString("url")?.takeIf { it.startsWith("https://") } }
    }

    private fun get(url: String, entete: Pair<String, String>? = null): JSONObject? = runCatching {
        val b = Request.Builder().url(url)
        if (entete != null) b.header(entete.first, entete.second)
        http.newCall(b.build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null }
    }.getOrNull()

    /** Premier plan libre de droits pour [requete] (anglais), pas encore utilisé dans ce montage ([pris]). */
    suspend fun chercher(app: JarvisApp, requete: String, vertical: Boolean, pris: MutableSet<String>): Clip? = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(requete.trim().ifBlank { return@withContext null }, "UTF-8")
        val pexels = app.store.pexelsKey.value.trim()
        if (pexels.isNotBlank()) {
            val j = get("https://api.pexels.com/videos/search?query=$q&orientation=${if (vertical) "portrait" else "landscape"}&size=medium&per_page=15", "Authorization" to pexels)
            val vids = j?.optJSONArray("videos")
            if (vids != null) for (i in 0 until vids.length()) {
                val v = vids.optJSONObject(i) ?: continue
                val id = "pexels:" + v.optLong("id")
                if (id in pris || v.optInt("duration") < 3) continue
                val lien = choisirPexels(v, vertical) ?: continue
                pris += id
                return@withContext Clip(id, lien, "Pexels", v.optJSONObject("user")?.optString("name").orEmpty(), v.optString("url"), v.optInt("duration"))
            }
        }
        val pixabay = app.store.pixabayKey.value.trim()
        if (pixabay.isNotBlank()) {
            val j = get("https://pixabay.com/api/videos/?key=${java.net.URLEncoder.encode(pixabay, "UTF-8")}&q=$q&per_page=15&safesearch=true")
            val hits = j?.optJSONArray("hits")
            if (hits != null) for (i in 0 until hits.length()) {
                val h = hits.optJSONObject(i) ?: continue
                val id = "pixabay:" + h.optLong("id")
                if (id in pris || h.optInt("duration") < 3) continue
                val lien = choisirPixabay(h) ?: continue
                pris += id
                return@withContext Clip(id, lien, "Pixabay", h.optString("user"), h.optString("pageURL"), h.optInt("duration"))
            }
        }
        null
    }

    /** Télécharge le plan (40 Mo au plus) ; null en cas d'échec. */
    suspend fun telecharger(clip: Clip, dest: File): File? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(clip.url).build()).execute().use { r ->
                val corps = r.body ?: return@use null
                if (!r.isSuccessful || corps.contentLength() > 40L * 1024 * 1024) return@use null
                dest.outputStream().use { out -> corps.byteStream().copyTo(out) }
                dest.takeIf { it.length() > 10_000 }
            }
        }.getOrNull()
    }
}
