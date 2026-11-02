package fr.dd.jarvis.societe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Géolocalisation (Nominatim puis Photon / OpenStreetMap) et itinéraire routier (OSRM). */
object Geo {
    data class Point(val lat: Double, val lon: Double)
    data class Route(val km: Double, val minutes: Double, val estime: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private const val UA = "JarvisHUD/1.1 (Android client KML geocoding)"
    private val geocodeCache = ConcurrentHashMap<String, Point>()

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    private fun pointFromNominatim(body: String?): Point? {
        return try {
            val arr = JSONArray(body ?: return null)
            if (arr.length() == 0) null else arr.getJSONObject(0).let {
                Point(it.getString("lat").toDouble(), it.getString("lon").toDouble())
            }
        } catch (_: Exception) { null }
    }

    private fun pointFromPhoton(body: String?): Point? {
        return try {
            val features = JSONObject(body ?: return null).getJSONArray("features")
            if (features.length() == 0) null else {
                val coords = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
                Point(coords.getDouble(1), coords.getDouble(0))
            }
        } catch (_: Exception) { null }
    }

    /**
     * Essaie l'adresse complète, puis la commune seule si le lieu-dit/la ferme n'est pas référencé.
     * Le repli communal donne un point cartographique approximatif plutôt que de perdre le client.
     */
    suspend fun geocode(adresse: String): Point? = withContext(Dispatchers.IO) {
        val full = adresse.trim().replace(Regex("\\s+"), " ")
        if (full.isBlank()) return@withContext null
        val key = full.lowercase()
        geocodeCache[key]?.let { return@withContext it }

        val parts = full.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val commune = parts.drop(1).joinToString(", ").takeIf { it.isNotBlank() }
        val localite = parts.lastOrNull { !it.contains("France", ignoreCase = true) }
        val queries = listOf(full, commune, localite?.let { if (it.contains("France", ignoreCase = true)) it else "$it, France" })
            .filterNotNull().distinct()

        for (query in queries) {
            val nominatimUrl = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", "1")
                .addQueryParameter("countrycodes", "fr")
                .addQueryParameter("q", query)
                .build()
            val nominatim = pointFromNominatim(get(nominatimUrl.toString()))
            if (nominatim != null) {
                geocodeCache[key] = nominatim
                return@withContext nominatim
            }
            // Nominatim impose une cadence basse ; éviter les rafales lors d'un export multi-clients.
            delay(1100)
            val photonUrl = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("limit", "1")
                .addQueryParameter("lang", "fr")
                .build()
            val photon = pointFromPhoton(get(photonUrl.toString()))
            if (photon != null) {
                geocodeCache[key] = photon
                return@withContext photon
            }
        }
        null
    }

    suspend fun route(a: Point, b: Point): Route? = withContext(Dispatchers.IO) {
        val url = "https://router.project-osrm.org/route/v1/driving/${a.lon},${a.lat};${b.lon},${b.lat}?overview=false"
        val body = get(url)
        if (body != null) {
            try {
                val r = JSONObject(body).getJSONArray("routes").getJSONObject(0)
                return@withContext Route(r.getDouble("distance") / 1000.0, r.getDouble("duration") / 60.0, false)
            } catch (_: Exception) {
            }
        }
        val km = haversine(a, b) * 1.3
        Route(km, km, true)
    }

    fun haversine(a: Point, b: Point): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
