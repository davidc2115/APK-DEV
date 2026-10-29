package fr.dd.jarvis.societe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Géolocalisation (Nominatim / OpenStreetMap) et itinéraire routier (OSRM). Gratuit, sans clé. */
object Geo {
    data class Point(val lat: Double, val lon: Double)
    data class Route(val km: Double, val minutes: Double, val estime: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private const val UA = "JarvisHUD/1.0 (assistant personnel Android)"

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    suspend fun geocode(adresse: String): Point? = withContext(Dispatchers.IO) {
        if (adresse.isBlank()) return@withContext null
        val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json").addQueryParameter("limit", "1").addQueryParameter("q", adresse).build()
        val body = get(url.toString()) ?: return@withContext null
        try {
            val arr = JSONArray(body)
            if (arr.length() == 0) null
            else arr.getJSONObject(0).let { Point(it.getString("lat").toDouble(), it.getString("lon").toDouble()) }
        } catch (e: Exception) {
            null
        }
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
        // Repli : à vol d'oiseau × 1,3 (marqué comme estimation)
        val km = haversine(a, b) * 1.3
        Route(km, km / 60.0 * 60.0 / 1.0, true)
    }

    fun haversine(a: Point, b: Point): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
