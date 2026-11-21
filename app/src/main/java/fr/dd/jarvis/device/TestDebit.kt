package fr.dd.jarvis.device

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import fr.dd.jarvis.JarvisApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Test de débit RÉEL du réseau mobile : latence, débit descendant et montant, par le réseau mobile même quand le
 * téléphone est en Wi-Fi (Android ouvre la connexion mobile le temps de la mesure). Volume plafonné (≈ 10 Mo en
 * réception, 3 Mo en envoi, 6 s chacun) : un test ne coûte presque rien sur le forfait.
 */
object TestDebit {
    private const val HOTE = "https://speed.cloudflare.com"
    private const val MAX_DESCENTE = 10_000_000L
    private const val MAX_MONTEE = 3_000_000
    private const val DUREE_MS = 6_000L

    data class Resultat(val latenceMs: Long?, val descenteMbps: Double?, val monteeMbps: Double?, val octets: Long, val viaMobile: Boolean)

    /** Ce que permet ce débit, en clair. */
    internal fun verdict(r: Resultat): String {
        val d = r.descenteMbps ?: return "❌ Pas de connexion utilisable."
        val usage = when {
            d >= 25 -> "excellent : vidéo 4K, visio, gros téléchargements"
            d >= 8 -> "bon : vidéo HD, visio, musique sans coupure"
            d >= 3 -> "correct : musique, navigation, visio en qualité réduite"
            d >= 1 -> "lent : messages et navigation légère seulement"
            else -> "très lent : à peine les messages"
        }
        val lat = r.latenceMs?.let { if (it > 150) " · latence élevée (${it} ms) : appels et visio peuvent hacher" else "" }.orEmpty()
        return "Débit $usage$lat."
    }

    private fun mbps(octets: Long, ms: Long) = if (ms <= 0) null else octets * 8.0 / 1_000_000.0 / (ms / 1000.0)

    /** Connexion mobile ouverte à la demande (même en Wi-Fi), ou null (données coupées, pas de réseau). */
    private fun reseauMobile(app: JarvisApp): Network? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.activeNetwork?.let { n -> if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) return n }
        val fini = java.util.concurrent.CountDownLatch(1)
        var trouve: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trouve = network; fini.countDown() }
            override fun onUnavailable() { fini.countDown() }
        }
        return runCatching {
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb, 8_000)
            fini.await(9, TimeUnit.SECONDS)
            trouve
        }.getOrNull().also { if (it == null) runCatching { cm.unregisterNetworkCallback(cb) } else enCours = cm to cb }
    }

    @Volatile private var enCours: Pair<ConnectivityManager, ConnectivityManager.NetworkCallback>? = null

    /** Mesure (à appeler hors du fil principal). [mobile] : par le réseau mobile ; sinon par la connexion actuelle. */
    fun mesurer(app: JarvisApp, mobile: Boolean = true): Resultat {
        val reseau = if (mobile) reseauMobile(app) else null
        if (mobile && reseau == null) return Resultat(null, null, null, 0, true)
        try {
            val b = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
            if (reseau != null) b.socketFactory(reseau.socketFactory).dns(object : okhttp3.Dns { override fun lookup(hostname: String) = reseau.getAllByName(hostname).toList() })
            val client = b.build()
            // Latence : meilleure de 4 petites requêtes (la première ouvre la connexion).
            val latences = (1..4).mapNotNull {
                val t0 = System.nanoTime()
                runCatching { client.newCall(Request.Builder().url("$HOTE/__down?bytes=0").build()).execute().use { it.body?.bytes() } }.getOrNull()
                    ?.let { (System.nanoTime() - t0) / 1_000_000 }
            }.drop(1)
            var recu = 0L
            val t1 = System.currentTimeMillis()
            runCatching {
                client.newCall(Request.Builder().url("$HOTE/__down?bytes=$MAX_DESCENTE").build()).execute().use { r ->
                    val flux = r.body?.byteStream() ?: return@use
                    val tampon = ByteArray(64 * 1024)
                    while (System.currentTimeMillis() - t1 < DUREE_MS) { val n = flux.read(tampon); if (n < 0) break; recu += n }
                }
            }
            val dureeDescente = System.currentTimeMillis() - t1
            val t2 = System.currentTimeMillis()
            val envoye = runCatching {
                client.newBuilder().callTimeout(DUREE_MS + 2_000, TimeUnit.MILLISECONDS).build()
                    .newCall(Request.Builder().url("$HOTE/__up").post(ByteArray(MAX_MONTEE).toRequestBody("application/octet-stream".toMediaType())).build())
                    .execute().use { if (it.isSuccessful) MAX_MONTEE.toLong() else 0L }
            }.getOrDefault(0L)
            val dureeMontee = System.currentTimeMillis() - t2
            client.connectionPool.evictAll()
            return Resultat(latences.minOrNull(), if (recu > 0) mbps(recu, dureeDescente) else null, if (envoye > 0) mbps(envoye, dureeMontee) else null,
                recu + envoye, reseau != null || mobile)
        } finally {
            enCours?.let { (cm, cb) -> runCatching { cm.unregisterNetworkCallback(cb) } }; enCours = null
        }
    }

    fun texte(app: JarvisApp, mobile: Boolean = true): String {
        val r = mesurer(app, mobile)
        if (mobile && r.descenteMbps == null && r.octets == 0L) return "🚀 Test de débit mobile impossible : données mobiles coupées ou pas de réseau. (« améliore mon réseau » pour le diagnostic.)"
        fun f(x: Double?) = x?.let { if (it >= 10) "%.0f".format(java.util.Locale.FRANCE, it) else "%.1f".format(java.util.Locale.FRANCE, it) }?.plus(" Mbit/s") ?: "échec"
        return "🚀 Test de débit ${if (mobile) "du réseau mobile" else "de la connexion actuelle"} :\n" +
            "⬇️ ${f(r.descenteMbps)} · ⬆️ ${f(r.monteeMbps)} · ⏱️ ${r.latenceMs?.let { "$it ms" } ?: "?"}\n" +
            verdict(r) + "\n" + ReseauMobile.ligneSignalActuelle(app) + "\n(données utilisées : ${"%.1f".format(java.util.Locale.FRANCE, r.octets / 1e6)} Mo)"
    }
}
