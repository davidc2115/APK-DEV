package fr.dd.jarvis.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import fr.dd.jarvis.societe.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Position du téléphone (fix GPS frais si possible) et adresse précise (n°, rue, code postal, ville). */
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

    /**
     * Position la plus précise possible : demande un fix FRAIS (GPS, puis réseau) pendant au plus [delaiMs] ; à défaut
     * la dernière position connue. Une vieille position (plusieurs minutes) n'est jamais présentée comme « exacte ».
     */
    @Suppress("MissingPermission")
    suspend fun precise(ctx: Context, delaiMs: Long = 9000): Location? {
        if (!granted(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val derniere = last(ctx)
        if (derniere != null && System.currentTimeMillis() - derniere.time < 20_000 && derniere.accuracy <= 25f) return derniere
        if (Build.VERSION.SDK_INT >= 30) {
            val fournisseurs = listOf(LocationManager.GPS_PROVIDER, "fused", LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { lm.isProviderEnabled(it) || it == "fused" && lm.allProviders.contains("fused") }.getOrDefault(false) }
            for (f in fournisseurs) {
                val l = withTimeoutOrNull(if (f == LocationManager.GPS_PROVIDER) delaiMs else 5000L) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val annulation = android.os.CancellationSignal()
                        cont.invokeOnCancellation { annulation.cancel() }
                        runCatching { lm.getCurrentLocation(f, annulation, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) } }
                            .onFailure { if (cont.isActive) cont.resume(null) }
                    }
                }
                if (l != null) return l
            }
        }
        return derniere
    }

    /** « Ma position » : coordonnées au format Google Maps, précision, adresse précise et lien. */
    suspend fun describe(ctx: Context): String = withContext(Dispatchers.IO) {
        if (!granted(ctx)) return@withContext "Permission de localisation non accordée (Réglages › Général › Autorisations)."
        val l = precise(ctx) ?: return@withContext "Position indisponible : active la localisation du téléphone."
        val p = Geo.Point(l.latitude, l.longitude)
        val age = (System.currentTimeMillis() - l.time) / 1000
        val adresse = Geo.adresseDe(p)
        buildString {
            append("📍 Position : ${Geo.texteGps(p)} (± ${l.accuracy.toInt()} m")
            if (age > 120) append(", relevée il y a ${age / 60} min")
            append(")\n")
            if (adresse != null) {
                append("🏠 Adresse : ${adresse.libelle}")
                adresse.distance?.let { if (it > 25) append(if (adresse.numero.isBlank()) " (voie la plus proche, à ~$it m)" else " (adresse la plus proche, à ~$it m)") }
                append("\n")
            } else append("🏠 Adresse : service d'adresses injoignable pour l'instant (réseau ?) — les coordonnées ci-dessus restent exactes.\n")
            append("🗺️ ${Geo.lienGoogleMaps(p)}")
        }
    }

    fun point(ctx: Context): Geo.Point? = last(ctx)?.let { Geo.Point(it.latitude, it.longitude) }

    /** Point FRAIS (pour enregistrer « ma position » sur une fiche). */
    suspend fun pointPrecis(ctx: Context): Pair<Geo.Point, Float>? = precise(ctx)?.let { Geo.Point(it.latitude, it.longitude) to it.accuracy }
}
