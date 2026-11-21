package fr.dd.jarvis.device

import android.content.Context
import fr.dd.jarvis.ai.CommandesDirectes
import fr.dd.jarvis.societe.Geo
import kotlinx.coroutines.launch

/**
 * Où est garée la voiture : « je suis garé ici (niveau -2, place 45) », « où est ma voiture ? », « ramène-moi à ma
 * voiture ». L'endroit est aussi retenu tout seul quand JARVIS quitte Android Auto (fin du trajet). Position GPS précise,
 * adresse, heure, distance et guidage à pied — sans IA.
 */
object Stationnement {
    sealed class Intention {
        data class Noter(val precision: String) : Intention()
        object OuEst : Intention()
        object Guider : Intention()
        object Oublier : Intention()
    }

    data class Place(val lat: Double, val lon: Double, val precisionM: Int, val ts: Long, val adresse: String, val note: String, val auto: Boolean)

    private const val POLI = "(?:(?:jarvis|ok|alors|bon|dis moi|tu peux|peux tu|est ce que tu peux|stp|s il te plait) )*"
    private const val VOITURE = "(?:ma|la|notre|mon) (?:voiture|auto|bagnole|caisse|vehicule|camion|camionnette|utilitaire|fourgon)"
    private const val GARE = "gare(?:e|s|es)?"
    private fun esp(m: String) = m.replace(" ?", "\\s*").replace(" ", "\\s+")
    private fun m(corps: String) = Regex(esp("^\\s*$POLI$corps(?: (?:stp|s il te plait|merci|jarvis))?\\s*$"))

    private val NOTER = m("(?:(?:je (?:suis|me suis)|on (?:est|s est)|je viens de me|on vient de se) $GARE|je me gare|(?:je suis|on est) stationne(?:e|s)?|" +
        "(?:retiens|note|memorise|enregistre|garde|sauvegarde|souviens toi de)(?: moi)? (?:ou (?:je suis|je me suis|on est|on s est) $GARE|(?:ma|la|notre) place(?: de parking| de stationnement)?|" +
        "(?:l )?(?:endroit|emplacement) (?:ou|de) (?:je suis $GARE|je me suis $GARE|$VOITURE)|ou est $VOITURE|(?:la )?position de $VOITURE|ou je (?:me )?gare))" +
        "(?: (?:ici|la|ici meme))?(?:[ ,:]+(.{2,80}?))?")
    private val OU_EST = m("(?:ou (?:est|se trouve|j ai laisse|ai je laisse|ai je gare|j ai gare)(?: (?:garee?|stationnee?))? $VOITURE|$VOITURE est ou|ou (?:est ce que )?(?:je (?:me )?suis|on (?:s )?est) $GARE|" +
        "ou (?:j ai|ai je) (?:gare|garee|stationne|laisse) (?:la|ma) voiture|tu (?:te )?(?:souviens|sais) ou (?:je (?:me )?suis|on (?:s )?est) $GARE|retrouve $VOITURE|ou est garee $VOITURE)")
    private val GUIDER = m("(?:ramene|emmene|guide|conduis|amene|reconduis)(?: moi| nous)? (?:a|jusqu a|vers) $VOITURE|(?:lance la )?navigation (?:vers|jusqu a) $VOITURE|itineraire (?:vers|jusqu a) $VOITURE")
    private val OUBLIER = m("(?:oublie|efface|supprime) (?:ma place(?: de parking)?|ou je (?:me )?suis $GARE|(?:la )?position de $VOITURE)")

    fun intention(texte: String): Intention? {
        val o = texte.trim()
        if (o.isBlank() || o.length > 200) return null
        val p = CommandesDirectes.plat(o)
        OUBLIER.matchEntire(p)?.let { return Intention.Oublier }
        GUIDER.matchEntire(p)?.let { return Intention.Guider }
        OU_EST.matchEntire(p)?.let { return Intention.OuEst }
        NOTER.matchEntire(p)?.let { r -> return Intention.Noter(r.groups[1]?.range?.let { o.substring(it.first, it.last + 1).trim().trim(',', '.', ':', ' ') }.orEmpty()) }
        return null
    }

    private fun prefs(c: Context) = c.getSharedPreferences("stationnement", Context.MODE_PRIVATE)

    fun place(c: Context): Place? {
        val p = prefs(c)
        if (!p.contains("latB")) return null
        return Place(java.lang.Double.longBitsToDouble(p.getLong("latB", 0L)), java.lang.Double.longBitsToDouble(p.getLong("lonB", 0L)), p.getInt("acc", 0),
            p.getLong("ts", 0L), p.getString("adresse", "").orEmpty(), p.getString("note", "").orEmpty(), p.getBoolean("auto", false))
    }

    private fun sauver(c: Context, pl: Place) {
        prefs(c).edit().putLong("latB", pl.lat.toRawBits()).putLong("lonB", pl.lon.toRawBits()).putInt("acc", pl.precisionM)
            .putLong("ts", pl.ts).putString("adresse", pl.adresse).putString("note", pl.note).putBoolean("auto", pl.auto).apply()
    }

    /** Fin d'Android Auto : la place est retenue toute seule (position récente, sans attendre le GPS). */
    fun noterAuto(c: Context) {
        val app = c.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch { runCatching { noter(app, "", auto = true) } }
    }

    /** Retient la position actuelle comme place de stationnement. */
    suspend fun noter(c: Context, note: String, auto: Boolean = false): String {
        if (!Locator.granted(c)) return "Autorise la localisation pour que je retienne où tu es garé."
        val (pt, acc) = (if (auto) Locator.point(c)?.let { it to 50f } else Locator.pointPrecis(c)) ?: return "Position indisponible : active la localisation."
        val adresse = runCatching { Geo.adresseDe(pt)?.libelle }.getOrNull().orEmpty()
        sauver(c, Place(pt.lat, pt.lon, acc.toInt(), System.currentTimeMillis(), adresse, note, auto))
        fr.dd.jarvis.voice.JournalVoix.noter("voiture", "place de stationnement retenue${if (auto) " (fin d'Android Auto)" else ""}")
        return "🅿️ C'est noté : tu es garé ${if (adresse.isNotBlank()) adresse else Geo.texteGps(pt)} (± ${acc.toInt()} m)" +
            (if (note.isNotBlank()) " — $note" else "") + ". Demande « où est ma voiture ? » pour la retrouver."
    }

    private fun depuis(ts: Long): String {
        val min = (System.currentTimeMillis() - ts) / 60_000
        val h = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
        val jour = h.toLocalDate()
        val heure = "${h.hour} h ${"%02d".format(h.minute)}"
        return when {
            jour == java.time.LocalDate.now() -> "depuis $heure" + if (min < 120) " (il y a $min min)" else " (il y a ${min / 60} h)"
            jour == java.time.LocalDate.now().minusDays(1) -> "depuis hier $heure"
            else -> "depuis le ${fr.dd.jarvis.ai.DatesFr.libelle(jour, java.time.LocalDate.now())} à $heure"
        }
    }

    private fun distance(a: Geo.Point, b: Geo.Point): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val x = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(x))
    }

    /** « Où est ma voiture ? » ; null si aucune place retenue (la demande suit son cours : traceur Home Assistant…). */
    fun ouEst(c: Context): String? {
        val pl = place(c) ?: return null
        val pt = Geo.Point(pl.lat, pl.lon)
        val ici = Locator.point(c)
        val loin = ici?.let { distance(it, pt) }?.let { d -> if (d < 40) " Tu es juste à côté." else " Tu es à ${if (d < 1000) "${(d / 10).toInt() * 10} m" else "%.1f km".format(java.util.Locale.FRANCE, d / 1000)}." }.orEmpty()
        return "🅿️ Ta voiture est garée ${if (pl.adresse.isNotBlank()) pl.adresse else Geo.texteGps(pt)}" + (if (pl.note.isNotBlank()) " — ${pl.note}" else "") +
            ", ${depuis(pl.ts)}${if (pl.auto) " (retenu à la fin d'Android Auto)" else ""}.$loin\n🗺️ ${Geo.lienGoogleMaps(pt)}"
    }

    fun oublier(c: Context): String { prefs(c).edit().clear().apply(); return "🅿️ Place de stationnement oubliée." }
}
