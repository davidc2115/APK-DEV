package fr.dd.jarvis.societe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Géolocalisation des adresses clients et itinéraires.
 *
 * Ordre de recherche, le plus précis l'emporte :
 *  1. coordonnées GPS saisies : format Google Maps « 47.541558, 4.459845 », degrés-minutes-secondes de Google Earth
 *     (47°32'29.6"N 4°27'35.4"E) ou lien Google Maps — utilisées telles quelles ;
 *  2. Base Adresse Nationale (géocodage de la Géoplateforme IGN) : position au numéro de rue, rues et lieux-dits ;
 *  3. OpenStreetMap (Nominatim, puis Photon) : bâtiments, fermes et hameaux nommés ;
 *  4. à défaut, le centre de la commune, signalé comme approximatif.
 * Chaque résultat est contrôlé (même département, nom de rue ou de lieu-dit réellement présent dans l'adresse) pour ne
 * jamais placer un client sur une rue voisine au nom ressemblant (« Grande Rue » pour « Ferme de la Grange »…).
 */
object Geo {
    data class Point(val lat: Double, val lon: Double)
    data class Route(val km: Double, val minutes: Double, val estime: Boolean)

    /** Précision d'une position, de la plus sûre à la plus vague. */
    enum class Precision(val libelle: String, val rang: Int) {
        MANUELLE("coordonnées GPS saisies", 6),
        NUMERO("adresse exacte", 5),
        RUE("rue (numéro non trouvé)", 4),
        LIEU_DIT("lieu-dit / hameau", 3),
        COMMUNE("centre de la commune", 1),
        INCONNUE("non vérifiée", 0);

        /** Position fiable au bâtiment près. */
        val exacte get() = rang >= 5
    }

    /** Position trouvée, sa précision et ce qui a été reconnu (adresse officielle, lieu…). */
    data class Localisation(val point: Point, val precision: Precision, val trouve: String = "", val source: String = "")

    fun precisionDe(nom: String?): Precision? = Precision.values().firstOrNull { it.name == nom }

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private const val UA = "JarvisHUD/1.2 (Android client KML geocoding)"
    private const val BAN = "https://data.geopf.fr/geocodage/search"
    private val cache = ConcurrentHashMap<String, Localisation>()

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    // ================================================================================== formats Google Maps / Earth

    private fun f6(v: Double) = String.format(Locale.US, "%.6f", v)

    /** « 47.541558, 4.459845 » : latitude, longitude en degrés décimaux, comme Google Maps (à coller dans sa recherche). */
    fun texteGps(p: Point) = "${f6(p.lat)}, ${f6(p.lon)}"

    /** « 47°32'29.6"N 4°27'35.4"E » : degrés, minutes, secondes, comme Google Maps et Google Earth. */
    fun texteDms(p: Point) = dms(p.lat, 'N', 'S') + " " + dms(p.lon, 'E', 'W')

    private fun dms(v: Double, pos: Char, neg: Char): String {
        val dixiemes = (abs(v) * 36000.0).roundToLong()   // en dixièmes de seconde, arrondi une seule fois
        val d = dixiemes / 36000; val m = (dixiemes / 600) % 60; val s = (dixiemes % 600) / 10.0
        return String.format(Locale.US, "%d°%02d'%04.1f\"%c", d, m, s, if (v >= 0) pos else neg)
    }

    /** Coordonnées d'un repère KML : longitude,latitude,altitude (ordre imposé par la norme KML). */
    fun texteKml(p: Point) = "${f6(p.lon)},${f6(p.lat)},0"

    fun lienGoogleMaps(p: Point) = "https://www.google.com/maps/search/?api=1&query=${f6(p.lat)},${f6(p.lon)}"
    fun lienItineraire(p: Point) = "https://www.google.com/maps/dir/?api=1&destination=${f6(p.lat)},${f6(p.lon)}"

    // ================================================================================== lecture de coordonnées

    private val reLieu = Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)""")
    private val reDecimal = Regex("""(?<![\d.,])(-?\d{1,2}[.,]\d{3,})\s*[,;\s]\s*(-?\d{1,3}[.,]\d{3,})(?![\d])""")
    private val reDms = Regex(
        """(\d{1,3})\s*°\s*(?:(\d{1,2}(?:[.,]\d+)?)\s*['′’]\s*)?(?:(\d{1,2}(?:[.,]\d+)?)\s*(?:"|″|”|''|′′)\s*)?([NSEWO])(?![a-zA-Z])""",
        RegexOption.IGNORE_CASE
    )

    private val reDmsAvant = Regex(
        """(?<![a-zA-Z])([NSEWO])\s*(\d{1,3})\s*°\s*(?:(\d{1,2}(?:[.,]\d+)?)\s*['′’]\s*)?(?:(\d{1,2}(?:[.,]\d+)?)\s*(?:"|″|”|''|′′)?)?""",
        RegexOption.IGNORE_CASE
    )

    private fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
    private fun valide(lat: Double?, lon: Double?): Point? =
        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) Point(lat, lon) else null

    /**
     * Lit des coordonnées GPS telles que Google Maps ou Google Earth les affichent : « 47.541558, 4.459845 »,
     * « 47°32'29.6"N 4°27'35.4"E », ou un lien Google Maps complet (position exacte du lieu si présente). Null sinon.
     */
    fun lireCoordonnees(texte: String): Point? {
        var s = texte.trim()
        if (s.isBlank()) return null
        repeat(2) { if (s.contains('%')) s = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s) }
        reLieu.find(s)?.let { m -> valide(num(m.groupValues[1]), num(m.groupValues[2]))?.let { return it } }
        // Degrés-minutes-secondes, hémisphère après (47°32'29.6"N) ou avant (N 47°32'29.6").
        val dmsTrouves = reDms.findAll(s).map { m -> m.groupValues.drop(1) }.toList().takeIf { it.size >= 2 }
            ?: reDmsAvant.findAll(s).map { m -> listOf(m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[1]) }.toList()
        if (dmsTrouves.size >= 2) {
            var lat: Double? = null; var lon: Double? = null
            for (g in dmsTrouves) {
                val v = (num(g[0]) ?: continue) + (num(g[1]) ?: 0.0) / 60.0 + (num(g[2]) ?: 0.0) / 3600.0
                when (g[3].uppercase()) {
                    "N" -> lat = lat ?: v
                    "S" -> lat = lat ?: -v
                    "E" -> lon = lon ?: v
                    else -> lon = lon ?: -v      // W, ou O (ouest)
                }
            }
            valide(lat, lon)?.let { return it }
        }
        reDecimal.find(s)?.let { m -> valide(num(m.groupValues[1]), num(m.groupValues[2]))?.let { return it } }
        return null
    }

    private val reLienCourt = Regex("""https?://(?:maps\.app\.goo\.gl|goo\.gl/maps|g\.co/kgs)/\S+""")

    /** Suit un lien court Google Maps (maps.app.goo.gl/…) et lit les coordonnées dans l'adresse de destination. */
    private fun resoudreLienCourt(texte: String): Point? {
        val url = reLienCourt.find(texte)?.value ?: return null
        return try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14)").build()).execute().use { rep ->
                var r: okhttp3.Response? = rep
                while (r != null) {
                    lireCoordonnees(r.request.url.toString())?.let { return it }
                    r.header("Location")?.let { loc -> lireCoordonnees(loc)?.let { return it } }
                    r = r.priorResponse
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ================================================================================== analyse de l'adresse

    private val MOTS_VIDES = setOf(
        "rue", "r", "route", "rte", "avenue", "av", "ave", "chemin", "ch", "che", "chem", "impasse", "imp", "allee", "all", "place", "pl",
        "boulevard", "bd", "bld", "blvd", "quai", "cours", "lotissement", "lot", "residence", "res", "square", "sq", "sentier", "voie", "ruelle",
        "passage", "pas", "hameau", "ham", "lieu", "dit", "lieudit", "ld", "la", "le", "les", "l", "de", "du", "des", "d", "en", "sur", "sous",
        "aux", "au", "a", "et", "ferme", "domaine", "gaec", "earl", "scea", "sci", "sarl", "sas", "sasu", "eurl", "chez", "france", "cedex",
        "bis", "ter", "quater", "batiment", "bat", "appartement", "appt", "apt", "etage", "porte", "cidex", "bp"
    )
    private val ABREVIATIONS = mapOf(
        "st" to "saint", "ste" to "sainte", "sts" to "saints", "gal" to "general", "gl" to "general", "mal" to "marechal", "pdt" to "president",
        "dr" to "docteur", "pr" to "professeur", "cdt" to "commandant", "lt" to "lieutenant", "fg" to "faubourg", "fbg" to "faubourg", "mt" to "mont"
    )

    internal fun sansAccents(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun jetons(s: String): List<String> =
        sansAccents(s.lowercase(Locale.FRENCH)).replace(Regex("[^a-z0-9]+"), " ").trim().split(' ')
            .filter { it.isNotBlank() }.map { ABREVIATIONS[it] ?: it }

    /** Mots qui identifient une rue, un lieu-dit ou une commune (sans « rue », « de », numéros…). */
    internal fun significatifs(s: String): List<String> = jetons(s).filter { it !in MOTS_VIDES && it.length >= 2 && !it.all(Char::isDigit) }

    private fun proche(a: String, b: String) =
        a == b || a == b + "s" || b == a + "s" || a == b + "x" || b == a + "x" ||
            (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)))

    /**
     * Vrai si le nom [nom] (rue, lieu-dit, commune) est réellement présent dans l'adresse ([mots]). Les mots de la commune
     * du résultat ([ville]) sont retirés d'abord : « Rue Sainte-Reine » ne doit pas être reconnue grâce à la commune
     * « Alise-Sainte-Reine ».
     */
    internal fun correspond(nom: String?, mots: List<String>, ville: String? = null): Boolean {
        val cible = significatifs(nom ?: return false)
        if (cible.isEmpty()) return false
        val dispo = mots.toMutableList()
        if (!ville.isNullOrBlank()) significatifs(ville).forEach { dispo.remove(it) }
        var trouves = 0; var long = false
        for (m in cible) {
            val i = dispo.indexOfFirst { proche(it, m) }
            if (i >= 0) { trouves++; if (m.length >= 4) long = true; dispo.removeAt(i) }
        }
        val requis = if (cible.size <= 2) cible.size else ceil(cible.size * 0.66).toInt()
        return trouves >= requis && (long || trouves == cible.size)
    }

    private val GENERIQUES = setOf("saint", "sainte", "saints", "saintes", "sur", "sous", "mont", "val", "bois", "grand", "grande", "petit", "vieux", "neuf", "pont", "port")

    /**
     * La commune du résultat est-elle bien celle de l'adresse ? Son nom (ou au moins sa partie distinctive :
     * « Venarey » pour « Venarey-les-Laumes ») doit figurer dans l'adresse ; une adresse sans nom de commune se
     * contente du code postal. Plusieurs communes partagent souvent le même code postal (21150 : Venarey, Alise, Grignon…).
     */
    private fun communeOk(villes: List<String?>, pc: String?, r: Requete): Boolean {
        for (v in villes) {
            if (v.isNullOrBlank()) continue
            if (correspond(v, r.mots)) return true
            val distinctif = significatifs(v).firstOrNull { it !in GENERIQUES && it.length >= 3 }
            if (distinctif != null && r.mots.any { proche(it, distinctif) }) return true
        }
        return r.cp != null && pc == r.cp && r.sansCommune
    }

    private fun departement(cp: String) = if (cp.startsWith("97") || cp.startsWith("98")) cp.take(3) else cp.take(2)
    private fun memeDepartement(cp: String?, autre: String?) =
        cp == null || autre.isNullOrBlank() || !autre.trim().matches(Regex("\\d{5}")) || departement(cp) == departement(autre.trim())

    /** Adresse découpée : texte nettoyé, code postal, numéro, morceaux, mots significatifs, commune probable. */
    internal class Requete(brut: String) {
        val texte: String = brut.replace(Regex("(?i)\\bfrance\\b"), " ").replace(Regex("\\s+"), " ")
            .replace(Regex("\\s*,\\s*(,\\s*)*"), ", ").trim(' ', ',', ';')
        val cp: String? = Regex("""(?<!\d)\d{5}(?!\d)""").findAll(texte).lastOrNull()?.value
        val numero: String? = Regex("""(?:^|[\s,])(\d{1,4})(?:\s*(?:bis|ter|quater|[a-d])\b)?(?=[\s,]+[^\d\s,])""", RegexOption.IGNORE_CASE)
            .find(texte)?.groupValues?.get(1)
        val parties: List<String> = texte.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
        val mots: List<String> = significatifs(texte)
        /** Premier morceau sans chiffre (nom de ferme, de société ou lieu-dit) quand l'adresse en compte plusieurs. */
        val nomEnTete: String? = parties.firstOrNull()?.takeIf { parties.size >= 2 && it.none(Char::isDigit) && significatifs(it).isNotEmpty() }
        val commune: String? = run {
            if (cp != null) {
                val p = parties.lastOrNull { it.contains(cp) } ?: texte
                val apres = p.substringAfter(cp).trim(' ', ',', '-')
                val avant = p.substringBefore(cp).trim(' ', ',', '-')
                apres.ifBlank { if (parties.size >= 2 || significatifs(avant).size <= 3) avant else "" }.ifBlank { null }
            } else parties.takeIf { it.size >= 2 }?.lastOrNull()
        }
        /** Vrai si l'adresse ne nomme aucune commune (seulement un code postal, ou rien). */
        val sansCommune: Boolean = commune.isNullOrBlank() || significatifs(commune).isEmpty()
    }

    private class Candidat(val loc: Localisation, val score: Double)
    private fun meilleur(a: Candidat?, b: Candidat?): Candidat? = when {
        a == null -> b
        b == null -> a
        b.loc.precision.rang > a.loc.precision.rang -> b
        b.loc.precision.rang == a.loc.precision.rang && b.score > a.score -> b
        else -> a
    }

    private fun memeNumero(a: String?, b: String?) = !a.isNullOrBlank() && !b.isNullOrBlank() && a.filter(Char::isDigit) == b.filter(Char::isDigit)

    // ================================================================================== Base Adresse Nationale (IGN)

    private fun banFeatures(q: String, cp: String?, type: String? = null, limit: Int = 5): JSONArray? {
        val texte = q.trimStart { !it.isLetterOrDigit() }.take(200)
        if (texte.length < 3) return null
        val u = BAN.toHttpUrl().newBuilder().addQueryParameter("q", texte).addQueryParameter("limit", limit.toString())
            .addQueryParameter("index", "address")
        if (cp != null) u.addQueryParameter("postcode", cp)
        if (type != null) u.addQueryParameter("type", type)
        return runCatching { JSONObject(get(u.build().toString()) ?: return null).getJSONArray("features") }.getOrNull()
    }

    private fun candidatBan(f: JSONObject, r: Requete): Candidat? {
        val p = f.optJSONObject("properties") ?: return null
        val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val point = valide(c.optDouble(1), c.optDouble(0)) ?: return null
        val score = p.optDouble("score", 0.0)
        val pc = p.optString("postcode"); val ville = p.optString("city")
        if (score < 0.3 || !memeDepartement(r.cp, pc)) return null
        if (!communeOk(listOf(ville, p.optString("oldcity"), if (p.optString("type") == "municipality") p.optString("name") else null), pc, r)) return null
        var prec = when (p.optString("type")) {
            "housenumber" -> Precision.NUMERO
            "street" -> Precision.RUE
            "locality" -> Precision.LIEU_DIT
            "municipality" -> Precision.COMMUNE
            else -> return null
        }
        val reconnu = when (prec) {
            Precision.NUMERO, Precision.RUE -> correspond(p.optString("street").ifBlank { p.optString("name") }, r.mots, ville)
            Precision.LIEU_DIT -> correspond(p.optString("name"), r.mots, ville)
            else -> true   // commune déjà contrôlée
        }
        if (!reconnu) return null
        // Un numéro différent de celui demandé (ou ajouté alors qu'il n'y en avait pas) ne vaut qu'une position « rue ».
        if (prec == Precision.NUMERO && !memeNumero(p.optString("housenumber"), r.numero)) prec = Precision.RUE
        return Candidat(Localisation(point, prec, p.optString("label"), "Base Adresse Nationale (IGN)"), score)
    }

    private fun parBan(r: Requete): Candidat? {
        var best: Candidat? = null
        val essais = buildList<Pair<String, String?>> {
            add(r.texte to null)
            r.nomEnTete?.let { nom ->
                add(r.parties.drop(1).joinToString(", ") to null)                       // sans le nom de ferme / société
                add(listOfNotNull(nom, r.cp, r.commune).joinToString(" ") to "locality")  // le nom comme lieu-dit
            }
        }.distinct()
        for ((q, type) in essais) {
            for (avecCp in listOf(true, false)) {
                if (avecCp && r.cp == null) continue
                val fs = banFeatures(q, if (avecCp) r.cp else null, type) ?: continue
                var trouve = false
                for (i in 0 until fs.length()) {
                    val c = candidatBan(fs.optJSONObject(i) ?: continue, r) ?: continue
                    best = meilleur(best, c); trouve = true
                }
                if (trouve) break   // inutile de relâcher le filtre du code postal
            }
            if (best?.loc?.precision == Precision.NUMERO) break
        }
        return best
    }

    private fun communeBan(r: Requete): Candidat? {
        val q = listOfNotNull(r.cp, r.commune).joinToString(" ").ifBlank { r.texte }
        val fs = banFeatures(q, r.cp, "municipality", 3) ?: return null
        var best: Candidat? = null
        for (i in 0 until fs.length()) best = meilleur(best, candidatBan(fs.optJSONObject(i) ?: continue, r))
        return best
    }

    // ================================================================================== OpenStreetMap

    private val verrouOsm = Mutex()
    @Volatile private var dernierOsm = 0L

    /** Nominatim impose au plus une requête par seconde : les appels sont espacés, même lors d'un export de 50 clients. */
    private suspend fun getOsm(url: String): String? = verrouOsm.withLock {
        val attente = 1100 - (System.currentTimeMillis() - dernierOsm)
        if (attente > 0) delay(attente)
        try { get(url) } finally { dernierOsm = System.currentTimeMillis() }
    }

    private fun villeOsm(a: JSONObject?) = a?.let {
        it.optString("village").ifBlank { it.optString("town") }.ifBlank { it.optString("city") }.ifBlank { it.optString("municipality") }
    }.orEmpty()

    private suspend fun parNominatim(r: Requete): Candidat? {
        var best: Candidat? = null
        val essais = listOfNotNull(r.texte, r.nomEnTete?.let { n -> listOfNotNull(n, r.cp, r.commune).joinToString(" ") }).distinct()
        for (q in essais) {
            val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
                .addQueryParameter("format", "jsonv2").addQueryParameter("addressdetails", "1")
                .addQueryParameter("limit", "3").addQueryParameter("countrycodes", "fr").addQueryParameter("q", q).build()
            val arr = runCatching { JSONArray(getOsm(url.toString()) ?: "[]") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val point = valide(o.optString("lat").toDoubleOrNull(), o.optString("lon").toDoubleOrNull()) ?: continue
                val a = o.optJSONObject("address")
                if (!memeDepartement(r.cp, a?.optString("postcode"))) continue
                val ville = villeOsm(a)
                // Commune (et ancienne commune d'une commune nouvelle) ; jamais le hameau, qui peut porter le même nom qu'un lieu-dit cherché.
                val villes = listOf(ville, a?.optString("village"), a?.optString("town"), a?.optString("city"), a?.optString("municipality"))
                if (!communeOk(villes, a?.optString("postcode"), r)) continue
                val rang = o.optInt("place_rank", 0)
                val nom = o.optString("name"); val route = a?.optString("road").orEmpty(); val num = a?.optString("house_number").orEmpty()
                var prec = when { rang >= 28 -> Precision.NUMERO; rang >= 26 -> Precision.RUE; rang >= 18 -> Precision.LIEU_DIT; else -> Precision.COMMUNE }
                val reconnu = when (prec) {
                    Precision.NUMERO -> if (num.isNotBlank()) correspond(route, r.mots, ville) else correspond(nom, r.mots, ville)
                    Precision.RUE -> correspond(nom.ifBlank { route }, r.mots, ville)
                    Precision.LIEU_DIT -> correspond(nom, r.mots, ville)
                    else -> true
                }
                if (!reconnu) continue
                if (prec == Precision.NUMERO && num.isNotBlank() && !memeNumero(num, r.numero)) prec = Precision.RUE
                best = meilleur(best, Candidat(Localisation(point, prec, o.optString("display_name"), "OpenStreetMap"), o.optDouble("importance", 0.0)))
            }
            if (best != null && best.loc.precision.rang >= Precision.LIEU_DIT.rang) break
        }
        return best
    }

    private val LIEUX_OSM = setOf("hamlet", "isolated_dwelling", "locality", "farm", "farmyard", "neighbourhood", "quarter", "suburb", "allotments")

    private suspend fun parPhoton(r: Requete): Candidat? {
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", r.texte).addQueryParameter("limit", "5").addQueryParameter("lang", "fr").build()
        val fs = runCatching { JSONObject(getOsm(url.toString()) ?: return null).getJSONArray("features") }.getOrNull() ?: return null
        var best: Candidat? = null
        for (i in 0 until fs.length()) {
            val f = fs.optJSONObject(i) ?: continue
            val p = f.optJSONObject("properties") ?: continue
            if (p.optString("countrycode").let { it.isNotBlank() && !it.equals("FR", true) }) continue
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val point = valide(c.optDouble(1), c.optDouble(0)) ?: continue
            val pc = p.optString("postcode"); val ville = p.optString("city")
            if (!memeDepartement(r.cp, pc)) continue
            if (!communeOk(listOf(ville, if (p.optString("type") == "city") p.optString("name") else null), pc, r)) continue
            val nom = p.optString("name"); val rue = p.optString("street"); val num = p.optString("housenumber")
            val type = p.optString("type"); val valeur = p.optString("osm_value")
            var prec = when {
                num.isNotBlank() -> Precision.NUMERO
                type == "street" -> Precision.RUE
                valeur in LIEUX_OSM || type == "locality" || type == "district" -> Precision.LIEU_DIT
                type == "house" && nom.isNotBlank() -> Precision.NUMERO
                type == "city" || valeur in setOf("village", "town", "city") -> Precision.COMMUNE
                else -> continue
            }
            val reconnu = when (prec) {
                Precision.NUMERO -> if (num.isNotBlank()) correspond(rue, r.mots, ville) else correspond(nom, r.mots, ville)
                Precision.RUE, Precision.LIEU_DIT -> correspond(nom, r.mots, ville)
                else -> true
            }
            if (!reconnu) continue
            if (prec == Precision.NUMERO && num.isNotBlank() && !memeNumero(num, r.numero)) prec = Precision.RUE
            val libelle = listOf(listOf(num, rue).filter { it.isNotBlank() }.joinToString(" ").ifBlank { nom }, pc, ville).filter { it.isNotBlank() }.joinToString(" ")
            best = meilleur(best, Candidat(Localisation(point, prec, libelle, "OpenStreetMap (Photon)"), 0.0))
        }
        return best
    }

    // ================================================================================== point d'entrée

    /**
     * Localise une adresse (ou des coordonnées / un lien Google Maps) avec la meilleure précision disponible.
     * Retourne null si rien de fiable n'est trouvé — jamais une rue ou une commune qui ne correspond pas.
     */
    suspend fun localiser(adresse: String): Localisation? = withContext(Dispatchers.IO) {
        val brut = adresse.trim().replace(Regex("\\s+"), " ")
        if (brut.isBlank()) return@withContext null
        lireCoordonnees(brut)?.let { return@withContext Localisation(it, Precision.MANUELLE, "coordonnées GPS saisies", "saisie") }
        if (reLienCourt.containsMatchIn(brut)) {
            return@withContext resoudreLienCourt(brut)?.let { Localisation(it, Precision.MANUELLE, "lien Google Maps", "Google Maps") }
        }
        val cle = sansAccents(brut.lowercase(Locale.FRENCH))
        cache[cle]?.let { return@withContext it }

        val r = Requete(brut)
        var best = parBan(r)
        val suffisant = { b: Candidat? -> b != null && (b.loc.precision == Precision.NUMERO || (b.loc.precision == Precision.RUE && r.numero == null)) }
        if (!suffisant(best)) {
            best = meilleur(best, parNominatim(r))
            if ((best?.loc?.precision?.rang ?: 0) < Precision.LIEU_DIT.rang) best = meilleur(best, parPhoton(r))
        }
        if (best == null) best = communeBan(r)
        best?.loc?.also { cache[cle] = it }
    }

    /** Adresse postale retrouvée à partir d'un point GPS ([distance] = écart en mètres avec le point d'adresse). */
    data class Adresse(val libelle: String, val numero: String, val rue: String, val codePostal: String, val ville: String, val distance: Int?, val source: String)

    /**
     * Adresse précise d'un point GPS (n°, rue, code postal, ville) : Base Adresse Nationale (IGN) d'abord, au numéro
     * près en France ; OpenStreetMap ensuite. Null si rien n'est trouvé.
     */
    suspend fun adresseDe(p: Point): Adresse? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://data.geopf.fr/geocodage/reverse".toHttpUrl().newBuilder()
                .addQueryParameter("lon", f6(p.lon)).addQueryParameter("lat", f6(p.lat))
                .addQueryParameter("index", "address").addQueryParameter("limit", "1").build()
            val f = JSONObject(get(url.toString()) ?: "{}").optJSONArray("features")?.optJSONObject(0)
            val pr = f?.optJSONObject("properties")
            if (pr != null && pr.optString("label").isNotBlank()) {
                val d = pr.optDouble("distance", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                // Au-delà de ~250 m, le point d'adresse le plus proche n'est probablement pas le bon bâtiment.
                if (d == null || d <= 250) return@withContext Adresse(pr.optString("label"), pr.optString("housenumber"),
                    pr.optString("street").ifBlank { pr.optString("name") }, pr.optString("postcode"), pr.optString("city"), d, "Base Adresse Nationale (IGN)")
            }
        }
        runCatching {
            val url = "https://nominatim.openstreetmap.org/reverse".toHttpUrl().newBuilder()
                .addQueryParameter("format", "jsonv2").addQueryParameter("addressdetails", "1").addQueryParameter("zoom", "18")
                .addQueryParameter("lat", f6(p.lat)).addQueryParameter("lon", f6(p.lon)).build()
            val o = JSONObject(getOsm(url.toString()) ?: "{}")
            val a = o.optJSONObject("address") ?: return@runCatching null
            val num = a.optString("house_number"); val rue = a.optString("road").ifBlank { a.optString("hamlet") }
            val cp = a.optString("postcode"); val ville = villeOsm(a)
            val libelle = listOf(listOf(num, rue).filter { it.isNotBlank() }.joinToString(" "), listOf(cp, ville).filter { it.isNotBlank() }.joinToString(" "))
                .filter { it.isNotBlank() }.joinToString(", ").ifBlank { o.optString("display_name") }
            if (libelle.isBlank()) null else Adresse(libelle, num, rue, cp, ville, null, "OpenStreetMap")
        }.getOrNull()
    }

    /** Raccourci : seulement le point (itinéraires, trajets). */
    suspend fun geocode(adresse: String): Point? = localiser(adresse)?.point

    // ================================================================================== itinéraires

    suspend fun route(a: Point, b: Point): Route? = withContext(Dispatchers.IO) {
        val url = "https://router.project-osrm.org/route/v1/driving/${f6(a.lon)},${f6(a.lat)};${f6(b.lon)},${f6(b.lat)}?overview=false"
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
