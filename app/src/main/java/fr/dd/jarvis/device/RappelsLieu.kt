package fr.dd.jarvis.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.CommandesDirectes
import fr.dd.jarvis.societe.Geo
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Rappels À L'ARRIVÉE (ou au départ) d'un lieu : « rappelle-moi d'acheter du pain quand j'arrive à la boulangerie de
 * Gray », « quand j'arrive chez Dupont, rappelle-moi de vérifier l'onduleur », « en partant du chantier, rappelle-moi de
 * fermer le coffret », « à chaque fois que j'arrive à la maison… ». Lieux : maison / travail (mémoire), « ici », clients
 * de la Société, ou une adresse. Surveillance par Android (alerte de proximité, économe) doublée d'une vérification
 * toutes les 15 min par la veille de JARVIS.
 */
object RappelsLieu {
    data class Rappel(val id: Int, val texte: String, val lieu: String, val lat: Double, val lon: Double, val rayon: Int = 150,
                      val depart: Boolean = false, val chaqueFois: Boolean = false, val cree: Long = System.currentTimeMillis(),
                      val dedans: Boolean? = null, val dernier: Long = 0L, val fait: Boolean = false)

    data class Demande(val texte: String, val lieu: String, val depart: Boolean, val chaqueFois: Boolean)

    // ------------------------------------------------------------------ compréhension

    private const val RAPPEL = "(?:rappelle(?: moi| nous)?|fais moi penser|pense a me rappeler|n oublie pas de me rappeler|tu me rappelleras|tu me diras|dis moi|previens moi)"
    private const val QUAND = "(?:quand|lorsque|des que|une fois que|(?:a )?chaque fois que|a chaque fois qu|chaque fois qu)"
    private const val ARRIVE = "(?:j arrive|je suis|je serai|j arriverai|on arrive|on sera|je passe|je rentre|je serai arrive|je reviens|je pars|je quitte|je sors|je m en vais|on part|on quitte)"
    private const val EN = "(?:en (?:arrivant|passant|rentrant|revenant|partant|quittant|sortant))"
    private const val PREP = "(?:a l |a la |au |aux |a |chez |en |dans |sur |de la |de l |du |des |de |d )?"
    private fun esp(m: String) = m.replace(" ?", "\\s*").replace(" ", "\\s+")
    private fun r(c: String) = Regex(esp("^\\s*(?:(?:jarvis|ok|alors|stp) )*$c(?: (?:stp|s il te plait|merci))?\\s*$"))

    private val A = r("$RAPPEL (?:de |d |qu |que )?(.+?) ($QUAND $ARRIVE|$EN) $PREP(.+?)")
    private val B = r("($QUAND $ARRIVE|$EN) $PREP(.+?),? $RAPPEL (?:de |d |qu |que )?(.+)")

    /** Demande de rappel à l'arrivée / au départ d'un lieu (texte brut), ou null. */
    fun intention(texte: String): Demande? {
        val o = texte.trim()
        if (o.isBlank() || o.length > 260) return null
        val p = CommandesDirectes.plat(o)
        fun brut(m: MatchResult, i: Int) = m.groups[i]?.range?.let { o.substring(it.first, it.last + 1).trim().trim(',', '.', ' ') }.orEmpty()
        val (quoi, quand, ou) = A.matchEntire(p)?.let { Triple(brut(it, 1), it.groupValues[2], brut(it, 3)) }
            ?: B.matchEntire(p)?.let { Triple(brut(it, 3), it.groupValues[1], brut(it, 2)) } ?: return null
        if (quoi.isBlank() || ou.isBlank() || ou.split(Regex("\\s+")).size > 10) return null
        // « dans 10 minutes », « demain » : un rappel à une HEURE, pas à un lieu.
        if (Regex("^(\\d|dans \\d|demain|ce soir|ce matin|cet apres midi|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)").containsMatchIn(normaliser(ou))) return null
        val depart = Regex("pars|quitte|sors|m en vais|partant|quittant|sortant|part\\b").containsMatchIn(quand)
        val lieu = ou.replace(Regex("^(?:à |a |au |aux |chez |en |dans |sur |de |du |des |d'|l'|la |le )+", RegexOption.IGNORE_CASE), "").trim()
        return Demande(quoi.replaceFirstChar { it.uppercase() }, lieu.ifBlank { ou }, depart, Regex("chaque fois").containsMatchIn(quand))
    }

    // ------------------------------------------------------------------ lieux

    data class Lieu(val nom: String, val lat: Double, val lon: Double, val rayon: Int)

    private val GENERIQUE = Regex("^(?:la |le |l |une |un )?(boulangerie|pharmacie|supermarche|magasin|poste|banque|garage|station|station service|pompe|marche|tabac|boucherie|bricolage|magasin de bricolage|epicerie|superette|hypermarche|restaurant|cafe|bar|parking|gare|ecole|mairie)$")

    /** Lieu → coordonnées ; ou un message expliquant ce qu'il faut préciser. */
    suspend fun resoudre(app: JarvisApp, lieu: String): Pair<Lieu?, String?> {
        val n = normaliser(lieu)
        if (Regex("^(ici|la ou je suis|cet endroit|ma position)$").matches(n)) {
            val (p, acc) = Locator.pointPrecis(app) ?: return null to "Position indisponible : active la localisation."
            val adr = runCatching { Geo.adresseDe(p)?.libelle }.getOrNull()
            return Lieu(adr ?: "ici", p.lat, p.lon, maxOf(120, acc.toInt() + 60)) to null
        }
        val canon = Faits.nomLieu(lieu)
        if (canon == "domicile" || canon == "travail") {
            val adr = Faits.lieux(app)[canon]
            val hq = app.societe.data.societe
            if (adr.isNullOrBlank()) {
                if (canon == "travail" && hq.lat != null && hq.lon != null) return Lieu("le travail (${hq.adresse})", hq.lat, hq.lon, 150) to null
                return null to "Je ne connais pas encore l'adresse de ${if (canon == "domicile") "ta maison" else "ton travail"} : dis « mon adresse ${if (canon == "domicile") "du domicile" else "du travail"} est … »."
            }
            val p = Geo.geocode(adr) ?: return null to "Adresse introuvable : $adr"
            return Lieu(if (canon == "domicile") "la maison" else "le travail", p.lat, p.lon, 150) to null
        }
        // Client de la Société (« chez Dupont »).
        val clients = runCatching { app.societe.data.chercherClients(lieu) }.getOrDefault(emptyList())
        if (clients.size == 1) {
            val c = clients.single()
            val site = c.sites.firstOrNull { it.lat != null && it.lon != null }
            if (site != null) return Lieu("chez ${c.nom}", site.lat!!, site.lon!!, 200) to null
            val adr = c.adresse.ifBlank { c.sites.firstOrNull { it.adresse.isNotBlank() }?.adresse.orEmpty() }
            Geo.geocode(adr)?.let { return Lieu("chez ${c.nom}", it.lat, it.lon, 200) to null }
        }
        if (GENERIQUE.matches(n)) return null to "Quelle ${lieu.removePrefix("la ").removePrefix("le ")} ? Donne son nom et sa ville, son adresse, ou dis « quand j'arrive ici » une fois sur place."
        val p = Geo.geocode(lieu) ?: return null to "Je ne trouve pas « $lieu ». Donne une adresse (rue, ville) ou dis « quand j'arrive ici » une fois sur place."
        return Lieu(lieu, p.lat, p.lon, 150) to null
    }

    // ------------------------------------------------------------------ stockage

    private fun fichier(c: Context) = File(c.filesDir, "rappels-lieu.json")

    @Synchronized
    fun liste(c: Context): List<Rappel> = runCatching {
        val a = JSONArray(fichier(c).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Rappel(o.optInt("id"), o.optString("texte"), o.optString("lieu"), o.optDouble("lat"), o.optDouble("lon"), o.optInt("rayon", 150),
                o.optBoolean("depart"), o.optBoolean("chaqueFois"), o.optLong("cree"), if (o.has("dedans")) o.optBoolean("dedans") else null,
                o.optLong("dernier"), o.optBoolean("fait"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun sauver(c: Context, l: List<Rappel>) {
        val a = JSONArray()
        l.forEach { r -> a.put(JSONObject().put("id", r.id).put("texte", r.texte).put("lieu", r.lieu).put("lat", r.lat).put("lon", r.lon).put("rayon", r.rayon)
            .put("depart", r.depart).put("chaqueFois", r.chaqueFois).put("cree", r.cree).apply { r.dedans?.let { put("dedans", it) } }
            .put("dernier", r.dernier).put("fait", r.fait)) }
        fr.dd.jarvis.data.Ecritures.ecrire(fichier(c)) { a.toString() }
    }

    fun actifs(c: Context) = liste(c).filter { !it.fait }

    private fun permission(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
    fun arrierePlanAutorise(c: Context) = Build.VERSION.SDK_INT < 29 || permission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Crée le rappel et arme la surveillance ; texte de confirmation. */
    suspend fun creer(app: JarvisApp, d: Demande): String {
        if (!permission(app, Manifest.permission.ACCESS_FINE_LOCATION)) return "Autorise la localisation précise de JARVIS pour les rappels à l'arrivée."
        val (lieu, erreur) = resoudre(app, d.lieu)
        if (lieu == null) return "📍 ${erreur ?: "Lieu introuvable."}"
        val id = 8800 + (System.currentTimeMillis() % 1000).toInt() + liste(app).size
        // État actuel : déjà sur place → pas de déclenchement immédiat pour une arrivée (seulement en revenant).
        val ici = Locator.point(app)
        val dedans = ici?.let { distance(it.lat, it.lon, lieu.lat, lieu.lon) <= lieu.rayon }
        val r = Rappel(id, d.texte, lieu.nom, lieu.lat, lieu.lon, lieu.rayon, d.depart, d.chaqueFois, dedans = dedans)
        sauver(app, liste(app) + r)
        armer(app, r)
        fr.dd.jarvis.ai.Annulation.noterRappelLieu(id, d.texte)
        return "📍 Rappel ${if (d.depart) "en quittant" else "à l'arrivée à"} ${lieu.nom}${if (d.chaqueFois) " (à chaque fois)" else ""} : « ${d.texte} »." +
            (if (!arrierePlanAutorise(app)) "\n⚠️ Pour être prévenu même écran éteint, autorise la position « Toujours » : Réglages › Applications › JARVIS › Autorisations › Position › Toujours autoriser." else "")
    }

    fun supprimerParTexte(c: Context, cle: String): List<Rappel> {
        val n = normaliser(cle)
        val cibles = actifs(c).filter { n.isNotBlank() && (normaliser(it.texte).contains(n) || normaliser(it.lieu).contains(n)) }
        cibles.forEach { desarmer(c, it.id) }
        sauver(c, liste(c).filter { r -> cibles.none { it.id == r.id } })
        return cibles
    }

    fun supprimer(c: Context, id: Int): Boolean {
        val l = liste(c)
        if (l.none { it.id == id }) return false
        desarmer(c, id); sauver(c, l.filter { it.id != id }); return true
    }

    fun texte(c: Context): String = actifs(c).joinToString("\n") { "- 📍 ${if (it.depart) "En quittant" else "En arrivant à"} ${it.lieu}${if (it.chaqueFois) " (à chaque fois)" else ""} : ${it.texte}" }

    // ------------------------------------------------------------------ surveillance

    private fun intention(c: Context, id: Int) = PendingIntent.getBroadcast(c, id,
        Intent(c, RappelLieuReceiver::class.java).setAction("fr.dd.jarvis.RAPPEL_LIEU").putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))

    @android.annotation.SuppressLint("MissingPermission")
    private fun armer(c: Context, r: Rappel) {
        if (!permission(c, Manifest.permission.ACCESS_FINE_LOCATION)) return
        runCatching { c.getSystemService(LocationManager::class.java).addProximityAlert(r.lat, r.lon, r.rayon.toFloat(), -1, intention(c, r.id)) }
    }

    private fun desarmer(c: Context, id: Int) {
        runCatching { c.getSystemService(LocationManager::class.java).removeProximityAlert(intention(c, id)) }
    }

    /** Après un redémarrage : toutes les surveillances réarmées. */
    fun restaurer(c: Context) = actifs(c).forEach { armer(c, it) }

    internal fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val rT = 6_371_000.0
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val x = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * rT * Math.asin(Math.sqrt(x))
    }

    /** Transition détectée (entrée ou sortie du lieu) : rappel donné s'il correspond, une seule fois par passage. */
    @Synchronized
    fun transition(c: Context, id: Int, entre: Boolean) {
        val l = liste(c).toMutableList()
        val i = l.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val r = l[i]
        if (r.fait) return
        val pertinent = entre != r.depart && r.dedans != entre && System.currentTimeMillis() - r.dernier > 10 * 60_000L
        l[i] = r.copy(dedans = entre)
        if (pertinent) {
            notifier(c, r)
            l[i] = l[i].copy(dernier = System.currentTimeMillis(), fait = !r.chaqueFois)
            if (!r.chaqueFois) desarmer(c, r.id)
        }
        sauver(c, l)
    }

    /** Veille (toutes les 15 min) : position récente comparée aux lieux, au cas où l'alerte d'Android ne serait pas passée. */
    fun verifier(c: Context) {
        val ici = Locator.last(c) ?: return
        if (System.currentTimeMillis() - ici.time > 20 * 60_000L) return
        for (r in actifs(c)) {
            val dedans = distance(ici.latitude, ici.longitude, r.lat, r.lon) <= r.rayon + minOf(ici.accuracy, 150f)
            if (r.dedans == null) { sauver(c, liste(c).map { if (it.id == r.id) it.copy(dedans = dedans) else it }); continue }
            if (dedans != r.dedans) transition(c, r.id, dedans)
        }
    }

    private const val CANAL = "rappels_lieu_jarvis"

    private fun notifier(c: Context, r: Rappel) {
        if (Build.VERSION.SDK_INT >= 26) c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, "Rappels à l'arrivée / au départ", NotificationManager.IMPORTANCE_HIGH))
        val titre = "📍 ${if (r.depart) "En quittant" else "Arrivé à"} ${r.lieu}"
        val n = NotificationCompat.Builder(c, CANAL).setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle(titre).setContentText(r.texte)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.texte)).setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(c, r.id, Intent(c, fr.dd.jarvis.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        runCatching { NotificationManagerCompat.from(c).notify(r.id, n) }
        // En voiture : dit aussi sur l'écran / la voix d'Android Auto.
        runCatching { (c.applicationContext as? JarvisApp)?.let { if (it.enVoiture) fr.dd.jarvis.service.CarBridge.post(it, "$titre : ${r.texte}") } }
    }
}

/** Alerte de proximité d'Android (entrée / sortie d'un lieu de rappel). */
class RappelLieuReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        if (id == 0 || !intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        RappelsLieu.transition(context, id, intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false))
    }
}
