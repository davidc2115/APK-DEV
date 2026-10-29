package fr.dd.jarvis.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import fr.dd.jarvis.societe.Geo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Position du téléphone (dernière position connue) et adresse approximative (OpenStreetMap). */
object Locator {
    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    fun last(ctx: Context): Location? {
        if (!granted(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    fun describe(ctx: Context): String {
        if (!granted(ctx)) return "Permission de localisation non accordée (Réglages › Localisation)."
        val l = last(ctx) ?: return "Position indisponible : active la localisation du téléphone."
        val adresse = runCatching {
            val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
            val req = Request.Builder().header("User-Agent", "JarvisHUD/1.0")
                .url("https://nominatim.openstreetmap.org/reverse?format=json&lat=${l.latitude}&lon=${l.longitude}").build()
            http.newCall(req).execute().use { JSONObject(it.body?.string() ?: "{}").optString("display_name") }
        }.getOrDefault("")
        return "Position : ${"%.5f".format(l.latitude)}, ${"%.5f".format(l.longitude)} (±${l.accuracy.toInt()} m)" +
            (if (adresse.isNotBlank()) "\nAdresse approximative : $adresse" else "") +
            "\nhttps://www.google.com/maps/search/?api=1&query=${l.latitude},${l.longitude}"
    }

    fun point(ctx: Context): Geo.Point? = last(ctx)?.let { Geo.Point(it.latitude, it.longitude) }
}
