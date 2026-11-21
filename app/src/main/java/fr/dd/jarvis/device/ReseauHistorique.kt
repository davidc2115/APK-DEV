package fr.dd.jarvis.device

import android.os.Build
import android.telephony.TelephonyManager
import fr.dd.jarvis.JarvisApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Historique de la réception mobile et alerte de perte du réseau.
 *
 * Android prévient JARVIS à chaque changement de signal (aucune mesure active, aucune batterie en plus) : un point est
 * gardé au plus toutes les 3 minutes, ou dès que la qualité change, avec la position déjà connue du téléphone (aucune
 * recherche GPS). D'où « où est-ce que je capte mal ? », « à quelle heure ? » — utile chez les clients, sur la route.
 *
 * Plus de réseau mobile pendant 5 minutes (hors mode avion et hors mode nuit) : une notification propose les appels
 * Wi-Fi ou la relance de l'antenne, pour rester joignable.
 */
object ReseauHistorique {
    data class Point(val t: Long, val techno: String, val dbm: Int?, val q: Int, val lat: Double? = null, val lon: Double? = null)

    private const val MAX = 3000
    internal const val PAS_MS = 3 * 60_000L
    private const val ALERTE_MS = 5 * 60_000L
    private const val CANAL = "reseau_mobile"

    // ------------------------------------------------------------------ partie pure

    internal fun doitNoter(dernier: Point?, nouveau: Point): Boolean = dernier == null || nouveau.t - dernier.t >= PAS_MS || nouveau.q != dernier.q

    internal fun serialiser(l: List<Point>): String = JSONArray().also { a ->
        l.forEach { p -> a.put(JSONObject().put("t", p.t).put("n", p.techno).put("q", p.q).apply { p.dbm?.let { put("d", it) }; p.lat?.let { put("la", it) }; p.lon?.let { put("lo", it) } }) }
    }.toString()

    internal fun lire(s: String): List<Point> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            Point(o.getLong("t"), o.optString("n"), if (o.has("d")) o.getInt("d") else null, o.optInt("q"),
                if (o.has("la")) o.getDouble("la") else null, if (o.has("lo")) o.getDouble("lo") else null)
        } }
    }.getOrDefault(emptyList())

    private val MAUVAIS = ReseauMobile.Qualite.FAIBLE.ordinal

    /** Durée « représentée » par chaque point : jusqu'au suivant, au plus 30 minutes (téléphone éteint, trou de mesures). */
    private fun durees(l: List<Point>, maintenant: Long): List<Long> = l.indices.map { i -> ((if (i + 1 < l.size) l[i + 1].t else maintenant) - l[i].t).coerceIn(0, 30 * 60_000L) }

    /** Bilan lisible sur [jours] jours ; [lieu] nomme une position (adresse approximative) ou null. */
    internal fun synthese(tous: List<Point>, maintenant: Long, jours: Int = 7, lieu: (Double, Double) -> String? = { _, _ -> null }): String {
        val l = tous.filter { it.t >= maintenant - jours * 86_400_000L }.sortedBy { it.t }
        if (l.size < 3) return "📈 Pas encore assez de mesures : l'historique se remplit tout seul au fil de la journée (un point toutes les 3 minutes au plus)."
        val d = durees(l, maintenant)
        val total = d.sum().coerceAtLeast(1)
        fun part(f: (Point) -> Boolean) = (l.indices.filter { f(l[it]) }.sumOf { d[it] } * 100 / total).toInt()
        val out = ArrayList<String>()
        out += "📈 Réception mobile — ${if (jours == 1) "dernières 24 h" else "$jours derniers jours"} (${l.size} mesures)"
        val bon = part { it.q <= ReseauMobile.Qualite.BONNE.ordinal }; val moyen = part { it.q == ReseauMobile.Qualite.MOYENNE.ordinal }
        val faible = part { it.q == ReseauMobile.Qualite.FAIBLE.ordinal || it.q == ReseauMobile.Qualite.TRES_FAIBLE.ordinal }; val aucun = part { it.q == ReseauMobile.Qualite.AUCUNE.ordinal }
        out += "🟢 bon $bon % · 🟡 moyen $moyen % · 🟠 faible $faible % · ⚫ sans réseau $aucun %"
        val technos = l.indices.groupBy { l[it].techno.ifBlank { "?" } }.mapValues { (_, ix) -> ix.sumOf { d[it] } * 100 / total }.filterValues { it >= 3 }
            .entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value} %" }
        if (technos.isNotBlank()) out += "📡 $technos"
        // Zones de mauvaise réception (grille d'environ 1 km).
        val zones = l.filter { it.lat != null && it.lon != null }.groupBy { Math.round(it.lat!! * 100) to Math.round(it.lon!! * 100) }
            .filterValues { it.size >= 3 }
            .map { (_, pts) -> Triple(pts, pts.count { it.q >= MAUVAIS } * 100 / pts.size, pts.mapNotNull { it.dbm }.average().takeIf { !it.isNaN() }) }
            .filter { it.second >= 40 }.sortedWith(compareByDescending<Triple<List<Point>, Int, Double?>> { it.second }.thenBy { it.third ?: 0.0 }).take(3)
        if (zones.isNotEmpty()) {
            out += "\n📍 Où ça capte mal :"
            zones.forEach { (pts, mauvais, moyenne) ->
                val la = pts.mapNotNull { it.lat }.average(); val lo = pts.mapNotNull { it.lon }.average()
                out += "- ${lieu(la, lo) ?: "vers %.3f, %.3f".format(java.util.Locale.ROOT, la, lo)} : mauvais $mauvais % du temps" + (moyenne?.let { " (moyenne ${it.toInt()} dBm)" }.orEmpty())
            }
        }
        // Heures où la réception est la plus mauvaise.
        val heures = l.groupBy { java.time.Instant.ofEpochMilli(it.t).atZone(java.time.ZoneId.systemDefault()).hour }.filterValues { it.size >= 3 }
            .mapValues { (_, pts) -> pts.count { it.q >= MAUVAIS } * 100 / pts.size }.filterValues { it >= 50 }.entries.sortedByDescending { it.value }.take(2)
        if (heures.isNotEmpty()) out += "\n🕒 Moins bonne réception vers " + heures.joinToString(" et ") { "${it.key} h (${it.value} %)" } + "."
        if (faible + aucun >= 25) out += "\n💡 Réception souvent faible : active les appels Wi-Fi là où tu as du Wi-Fi, et pense à « passe en 4G » si la 5G décroche."
        return out.joinToString("\n")
    }

    // ------------------------------------------------------------------ téléphone

    private fun fichier(app: JarvisApp) = File(app.filesDir, "reseau_historique.json")
    private val points = ArrayList<Point>()
    @Volatile private var charge = false
    @Volatile private var actif: Any? = null
    @Volatile private var sansReseauDepuis = 0L
    @Volatile private var alerteEnvoyee = false
    private val principal by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var verification: Runnable? = null

    @Synchronized private fun charger(app: JarvisApp) {
        if (charge) return
        points.clear(); points += lire(runCatching { fichier(app).readText() }.getOrDefault("[]")).takeLast(MAX); charge = true
    }

    @Synchronized fun tous(app: JarvisApp): List<Point> { charger(app); return points.toList() }

    fun synthese(app: JarvisApp, jours: Int = 7): String {
        val geo = runCatching { android.location.Geocoder(app, java.util.Locale.FRANCE) }.getOrNull()
        return synthese(tous(app), System.currentTimeMillis(), jours) { la, lo ->
            @Suppress("DEPRECATION")
            runCatching { geo?.getFromLocation(la, lo, 1)?.firstOrNull()?.let { a -> listOfNotNull(a.thoroughfare, a.locality).joinToString(", ").ifBlank { null } } }.getOrNull()
        }
    }

    /** Réception des dernières 24 h en une ligne (pour le diagnostic), ou null. */
    fun resume24h(app: JarvisApp): String? {
        val maintenant = System.currentTimeMillis()
        val l = tous(app).filter { it.t >= maintenant - 86_400_000L }
        if (l.size < 5) return null
        val mauvais = l.count { it.q >= MAUVAIS } * 100 / l.size
        return "📈 Dernières 24 h : réception faible ou absente $mauvais % des mesures" + (if (mauvais >= 25) " (« où est-ce que je capte mal ? »)" else "")
    }

    @Synchronized private fun noter(app: JarvisApp, p: Point) {
        charger(app)
        if (!doitNoter(points.lastOrNull(), p)) return
        points += p
        if (points.size > MAX) points.subList(0, points.size - MAX).clear()
        val copie = points.toList()
        fr.dd.jarvis.data.Ecritures.ecrire(fichier(app)) { serialiser(copie) }
    }

    private fun position(app: JarvisApp): Pair<Double, Double>? = runCatching {
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val lm = app.getSystemService(android.location.LocationManager::class.java)
        @Suppress("MissingPermission")
        val l = lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER) ?: return null
        // Position récente seulement (moins de 15 minutes) : une vieille position placerait la mesure au mauvais endroit.
        if (System.currentTimeMillis() - l.time > 15 * 60_000L) null else (Math.round(l.latitude * 1000) / 1000.0) to (Math.round(l.longitude * 1000) / 1000.0)
    }.getOrNull()

    private fun surSignal(app: JarvisApp, ss: android.telephony.SignalStrength?) {
        val avion = android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        if (avion) { sansReseauDepuis = 0; return }
        val s = ss?.let { ReseauMobile.depuis(it) }
        val q = ReseauMobile.qualite(s)
        val pos = position(app)
        noter(app, Point(System.currentTimeMillis(), s?.let { if (it.nsa) "5G NSA" else it.techno }.orEmpty(), s?.dbm, q.ordinal, pos?.first, pos?.second))
        if (q == ReseauMobile.Qualite.AUCUNE) {
            if (sansReseauDepuis == 0L) {
                sansReseauDepuis = System.currentTimeMillis()
                val r = Runnable { if (sansReseauDepuis != 0L && !alerteEnvoyee) alerter(app) }
                verification?.let { principal.removeCallbacks(it) }
                verification = r
                principal.postDelayed(r, ALERTE_MS)
            }
        } else {
            sansReseauDepuis = 0L; alerteEnvoyee = false
            verification?.let { principal.removeCallbacks(it) }; verification = null
        }
    }

    private fun alerter(app: JarvisApp) {
        if (runCatching { Contexte.actuel(app).mode == Contexte.Mode.NUIT }.getOrDefault(false)) return
        alerteEnvoyee = true
        val wifi = runCatching {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
        val texte = if (wifi) "Tu es en Wi-Fi : active les appels Wi-Fi pour rester joignable (« Jarvis, ouvre les réglages des appels Wi-Fi »)."
            else "Dis « Jarvis, relance le réseau » pour forcer la recherche d'antenne, ou déplace-toi vers une fenêtre / dehors."
        runCatching {
            val nm = app.getSystemService(android.app.NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel(CANAL, "Réseau mobile", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Perte prolongée du réseau mobile" })
            val pi = android.app.PendingIntent.getActivity(app, 7710, android.content.Intent(app, fr.dd.jarvis.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            androidx.core.app.NotificationManagerCompat.from(app).notify(7710, androidx.core.app.NotificationCompat.Builder(app, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("📵 Plus de réseau mobile depuis 5 minutes")
                .setContentText(texte).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(texte))
                .setContentIntent(pi).setAutoCancel(true).build())
        }
        fr.dd.jarvis.voice.JournalVoix.noter("reseau", "alerte : plus de réseau mobile depuis 5 min")
    }

    /** Commence l'écoute du signal (service permanent de JARVIS). Sans effet si déjà démarrée ou Android < 12. */
    @Synchronized fun demarrer(app: JarvisApp) {
        if (actif != null || Build.VERSION.SDK_INT < 31) return
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return
        val cb = object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(ss: android.telephony.SignalStrength) { runCatching { surSignal(app, ss) } }
        }
        runCatching { tm.registerTelephonyCallback(java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-reseau").apply { isDaemon = true } }, cb); actif = cb }
    }

    @Synchronized fun arreter(app: JarvisApp) {
        if (Build.VERSION.SDK_INT < 31) return
        (actif as? android.telephony.TelephonyCallback)?.let { cb -> runCatching { app.getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(cb) } }
        actif = null
        verification?.let { principal.removeCallbacks(it) }; verification = null
    }
}
