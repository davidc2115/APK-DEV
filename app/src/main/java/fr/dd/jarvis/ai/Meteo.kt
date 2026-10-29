package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.Locator
import fr.dd.jarvis.societe.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Météo précise (Open-Meteo, sans clé) pour la position du téléphone ou un lieu donné. */
object Meteo {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "JarvisHUD/1.0").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) { null }

    private fun wmo(c: Int): Pair<String, String> = when (c) {
        0 -> "☀️" to "Ciel dégagé"; 1 -> "🌤️" to "Plutôt dégagé"; 2 -> "⛅" to "Partiellement nuageux"; 3 -> "☁️" to "Couvert"
        45, 48 -> "🌫️" to "Brouillard"; 51, 53, 55 -> "🌦️" to "Bruine"; 56, 57 -> "🌧️" to "Bruine verglaçante"
        61 -> "🌧️" to "Pluie faible"; 63 -> "🌧️" to "Pluie"; 65 -> "🌧️" to "Pluie forte"; 66, 67 -> "🌧️" to "Pluie verglaçante"
        71, 73, 75, 77 -> "🌨️" to "Neige"; 80, 81 -> "🌦️" to "Averses"; 82 -> "⛈️" to "Fortes averses"; 85, 86 -> "🌨️" to "Averses de neige"
        95 -> "⛈️" to "Orage"; 96, 99 -> "⛈️" to "Orage avec grêle"; else -> "🌡️" to "—"
    }

    suspend fun get(app: JarvisApp, lieu: String, jours: Int): String = withContext(Dispatchers.IO) {
        var nom = lieu.trim()
        val p: Geo.Point? = when {
            nom.isNotBlank() -> Geo.geocode(nom)
            else -> Locator.point(app)
        }
        var source = ""
        val pt = p ?: run {
            val hq = app.societe.data.societe
            if (nom.isBlank() && hq.lat != null && hq.lon != null) { source = "\n_(position du téléphone indisponible : météo du siège — autorise la localisation dans Réglages)_"; nom = "le siège"; Geo.Point(hq.lat, hq.lon) }
            else return@withContext if (nom.isBlank()) "Position indisponible : autorise la localisation (Réglages › Contrôle du téléphone) ou donne une ville." else "Lieu introuvable : $nom"
        }
        if (nom.isBlank()) {
            nom = get("https://nominatim.openstreetmap.org/reverse?format=json&zoom=12&lat=${pt.lat}&lon=${pt.lon}")?.let {
                runCatching { JSONObject(it).getJSONObject("address").let { a -> listOf("village", "town", "city", "municipality", "suburb").firstNotNullOfOrNull { k -> a.optString(k).ifBlank { null } } } }.getOrNull()
            } ?: "%.3f, %.3f".format(pt.lat, pt.lon)
            if (source.isBlank()) source = "\n_(d'après ta position)_"
        }
        val n = jours.coerceIn(1, 7)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${pt.lat}&longitude=${pt.lon}&timezone=auto&forecast_days=$n" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_gusts_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,sunrise,sunset,uv_index_max"
        val body = get(url) ?: return@withContext "Service météo indisponible (réseau)."
        val j = JSONObject(body)
        val cur = j.getJSONObject("current")
        val (ic, txt) = wmo(cur.getInt("weather_code"))
        val d = j.getJSONObject("daily")
        val sb = StringBuilder("# $ic Météo — $nom\n")
        sb.append(source).append("\n\n")
        sb.append("**Maintenant** : $txt, **${Math.round(cur.getDouble("temperature_2m"))}°C** (ressenti ${Math.round(cur.getDouble("apparent_temperature"))}°C)\n")
        sb.append("- 💧 Humidité ${cur.getInt("relative_humidity_2m")} % · 🌧️ ${cur.getDouble("precipitation")} mm\n")
        sb.append("- 💨 Vent ${Math.round(cur.getDouble("wind_speed_10m"))} km/h (rafales ${Math.round(cur.getDouble("wind_gusts_10m"))} km/h)\n")
        // Prochaines heures (aujourd'hui)
        val h = j.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val nowIso = cur.getString("time").take(13)
        var start = 0
        for (i in 0 until times.length()) if (times.getString(i).take(13) >= nowIso) { start = i; break }
        sb.append("\n## 🕗 Prochaines heures\n")
        var k = start
        while (k < minOf(times.length(), start + 12)) {
            val (hi, _) = wmo(h.getJSONArray("weather_code").getInt(k))
            sb.append("- ${times.getString(k).takeLast(5)} $hi ${Math.round(h.getJSONArray("temperature_2m").getDouble(k))}°C · pluie ${h.getJSONArray("precipitation_probability").optInt(k)} %\n")
            k += 2
        }
        sb.append("\n## 📅 Prévisions\n")
        val fmt = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.FRENCH)
        val dt = d.getJSONArray("time")
        for (i in 0 until dt.length()) {
            val (di, dtxt) = wmo(d.getJSONArray("weather_code").getInt(i))
            sb.append("- **${LocalDate.parse(dt.getString(i)).format(fmt)}** $di $dtxt · ${Math.round(d.getJSONArray("temperature_2m_min").getDouble(i))}° / ${Math.round(d.getJSONArray("temperature_2m_max").getDouble(i))}°C · " +
                "pluie ${d.getJSONArray("precipitation_probability_max").optInt(i)} % (${d.getJSONArray("precipitation_sum").optDouble(i)} mm) · vent ${Math.round(d.getJSONArray("wind_speed_10m_max").getDouble(i))} km/h · UV ${Math.round(d.getJSONArray("uv_index_max").optDouble(i))}\n")
        }
        sb.append("\n🌅 Lever ${d.getJSONArray("sunrise").getString(0).takeLast(5)} · 🌇 Coucher ${d.getJSONArray("sunset").getString(0).takeLast(5)}")
        sb.toString()
    }
}
