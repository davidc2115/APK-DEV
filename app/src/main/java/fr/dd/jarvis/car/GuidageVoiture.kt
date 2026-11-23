package fr.dd.jarvis.car

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.core.app.NotificationCompat
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Guidage demandé en voiture, VÉRIFIÉ : JARVIS ne dit plus « navigation lancée » sur la seule foi d'un lancement que
 * Android ou Android Auto peut ignorer sans erreur. Le guidage est considéré démarré quand Waze ou Google Maps publie
 * (ou met à jour) sa notification de navigation après la demande.
 *
 * Ordre : 1) Android Auto lui-même (JARVIS ouvert dans Android Auto) ; 2) l'appli de navigation du téléphone, dont le
 * guidage s'affiche dans la voiture ; 3) sinon, une notification « Itinéraire » à toucher, et une réponse honnête.
 */
object GuidageVoiture {
    val PAQUETS = setOf("com.waze", "com.google.android.apps.maps")
    private val dernieres = ConcurrentHashMap<String, Long>()
    private val fenetres = ConcurrentHashMap<String, Long>()
    private const val NOTIF = 7731

    /** Notification d'une appli de navigation (appelé par l'écouteur de notifications). */
    fun noter(pkg: String, quand: Long, categorie: String?, permanente: Boolean) {
        if (pkg !in PAQUETS) return
        if (categorie == Notification.CATEGORY_NAVIGATION || permanente) dernieres.merge(pkg, quand) { a, b -> maxOf(a, b) }
    }

    /** Fenêtre de Waze / Maps ouverte sur le téléphone (appelé par le service d'accessibilité). */
    fun noterFenetre(pkg: String) { if (pkg in PAQUETS) fenetres[pkg] = System.currentTimeMillis() }

    /** Waze / Maps affiché il y a moins de [ms]. */
    fun fenetreRecente(ms: Long = 120_000): Boolean = fenetres.values.any { System.currentTimeMillis() - it < ms }

    /** Bouton de départ du guidage (« Y aller », « Démarrer »…) : appuyer dessus est sans risque, aucune confirmation. */
    private val BOUTON_DEPART = Regex("(?i)^\\s*(y aller|go|démarrer|demarrer|partir|c'est parti|lancer|lancer le guidage|commencer|start|démarrer la navigation|demarrer la navigation|aller)\\s*[!.]?\\s*$")
    fun boutonDepart(texte: String): Boolean = BOUTON_DEPART.matches(texte)

    /** Voix de guidage en cours (Waze / Maps annonce le départ) : lecture audio « navigation » active. */
    fun guidageVocal(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(AudioManager::class.java).activePlaybackConfigurations
            .any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
    }.getOrDefault(false)

    /**
     * Guidage démarré depuis [depuis] ? Indices : notification de navigation publiée ou mise à jour, fenêtre de Waze / Maps
     * ouverte, voix de guidage. null = impossible à savoir (ni notifications ni accessibilité).
     */
    fun demarreDepuis(depuis: Long, ctx: Context? = null): Boolean? {
        if (dernieres.values.any { it >= depuis } || fenetres.values.any { it >= depuis }) return true
        if (ctx != null && guidageVocal(ctx)) return true
        val notif = fr.dd.jarvis.service.JarvisNotificationListener.navigationActive(PAQUETS, depuis)
        if (notif == null && fr.dd.jarvis.service.JarvisAccessibilityService.instance != null) return false
        return notif
    }

    /** Attend jusqu'à [ms] que le guidage apparaisse. */
    suspend fun attendre(depuis: Long, ms: Long = 6000, ctx: Context? = null): Boolean? {
        val fin = System.currentTimeMillis() + ms
        var r = demarreDepuis(depuis, ctx)
        while (r == false && System.currentTimeMillis() < fin) { delay(400); r = demarreDepuis(depuis, ctx) }
        return r
    }

    /**
     * Raison probable d'un lancement bloqué en arrière-plan, ou null si rien ne l'empêche (autorisations en place) :
     * l'itinéraire a alors très probablement démarré même si aucun indice ne l'a confirmé.
     */
    fun blocageProbable(app: JarvisApp): String? {
        if (fr.dd.jarvis.device.Xiaomi.est() && fr.dd.jarvis.device.Xiaomi.arrierePlan(app) == false)
            return "HyperOS bloque l'ouverture d'applis en arrière-plan"
        val superposition = runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)
        if (Build.VERSION.SDK_INT >= 29 && !superposition && fr.dd.jarvis.service.JarvisAccessibilityService.instance == null && !app.phone.adbOk())
            return "Android bloque les applis lancées en arrière-plan"
        return null
    }

    /** Adresse au format attendu par Android Auto : mots séparés par « + » (« 12+rue+de+la+Paix,+Dijon »). */
    fun requete(destination: String): String = destination.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("+") { mot ->
        buildString {
            for (b in mot.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in ",.-") append(c) else append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
    }

    fun coord(v: Double) = String.format(Locale.US, "%.6f", v)

    private fun etat(b: Boolean?) = when (b) { true -> "oui"; false -> "NON"; null -> "inconnu" }

    /** URI geo: pour Android Auto : coordonnées si connues (guidage direct), sinon l'adresse. */
    fun geo(destination: String, point: Pair<Double, Double>?): String =
        if (point != null) "geo:${coord(point.first)},${coord(point.second)}" else "geo:0,0?q=" + requete(destination)

    /**
     * Lance le guidage et ne l'annonce que s'il a vraiment démarré. [appli] : Waze, Google Maps… (vide = Waze s'il est
     * installé). Renvoie la phrase à dire.
     */
    suspend fun lancer(app: JarvisApp, destination: String, point: Pair<Double, Double>?, appli: String = ""): String {
        val journal = fr.dd.jarvis.voice.JournalVoix
        val nom = destination.ifBlank { point?.let { "${coord(it.first)}, ${coord(it.second)}" }.orEmpty() }
        val echecs = mutableListOf<String>()
        journal.noter("voiture", "itinéraire demandé vers « $nom »" + (point?.let { " (${coord(it.first)}, ${coord(it.second)})" } ?: " (adresse non géolocalisée)") +
            " · JARVIS ouvert dans Android Auto : ${if (NavigationVoiture.contexte != null) "oui" else "non"}" +
            " · lecture des notifications : ${if (fr.dd.jarvis.service.JarvisNotificationListener.active) "oui" else "NON"}" +
            (if (fr.dd.jarvis.device.Xiaomi.est()) " · Xiaomi pop-up en arrière-plan : ${etat(fr.dd.jarvis.device.Xiaomi.arrierePlan(app))}" +
                ", écran verrouillé : ${etat(fr.dd.jarvis.device.Xiaomi.ecranVerrouille(app))}" else ""))

        // 1. Android Auto lance lui-même son GPS (JARVIS ouvert dans Android Auto pendant le trajet).
        if (NavigationVoiture.contexte != null) {
            val t0 = System.currentTimeMillis()
            val accepte = NavigationVoiture.lancer(destination, point)
            journal.noter("voiture", "Android Auto ${if (accepte) "a accepté" else "a refusé"} ${geo(destination, point)}")
            if (accepte) {
                when (attendre(t0, ctx = app)) {
                    true -> { journal.noter("voiture", "guidage démarré par Android Auto vers $nom"); return "🧭 Guidage lancé dans Android Auto vers $nom." }
                    // Pas de lecture des notifications : impossible de vérifier, on ne relance pas une 2e fois par le téléphone.
                    null -> return "🧭 Guidage demandé à Android Auto vers $nom. Je ne peux pas vérifier qu'il a démarré : autorise l'accès aux notifications à JARVIS pour que je le confirme."
                    false -> echecs += "Android Auto n'a pas démarré le guidage"
                }
            } else echecs += "Android Auto a refusé la demande"
        } else echecs += "JARVIS n'est pas ouvert dans Android Auto"

        // 2. L'appli de navigation du téléphone (Waze de préférence) : son guidage s'affiche dans la voiture.
        val dispo = app.phone.navApps()
        val choix = appli.trim().ifBlank { app.store.appPrefs["navigation"].orEmpty() }.ifBlank { dispo.keys.firstOrNull { it.contains("Waze") } ?: dispo.keys.firstOrNull().orEmpty() }
        val t1 = System.currentTimeMillis()
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.phone.navigate(destination, "d", choix, point) }
        journal.noter("voiture", "téléphone : ${choix.ifBlank { "Google Maps" }} → ${r.take(120)}")
        if (r.startsWith("🧭")) {
            // Waze démarré à froid met plusieurs secondes à calculer l'itinéraire.
            val nomAppli = choix.ifBlank { "Google Maps" }
            when (attendre(t1, 10_000, app)) {
                true -> { journal.noter("voiture", "guidage démarré par $nomAppli vers $nom"); return "🧭 Itinéraire vers $nom lancé dans $nomAppli." }
                null -> return "🧭 Itinéraire vers $nom envoyé à $nomAppli."
                false -> {
                    val blocage = blocageProbable(app)
                    // Rien ne l'empêche (autorisations en place) : Waze ne signale simplement pas toujours son départ.
                    // Avant, JARVIS annonçait un échec alors que la navigation démarrait.
                    if (blocage == null) {
                        journal.noter("voiture", "$nomAppli : départ non confirmé, mais aucune autorisation ne manque → annoncé comme envoyé")
                        return "🧭 Itinéraire vers $nom envoyé à $nomAppli."
                    }
                    echecs += "$nomAppli ne s'est pas ouvert ($blocage)"
                }
            }
        } else echecs += r.removePrefix("❌ ").take(120)

        // 3. Rien de vérifié : itinéraire prêt dans une notification à toucher, et réponse honnête.
        notifier(app, destination, point, choix)
        journal.noter("voiture", "guidage NON confirmé vers $nom : ${echecs.joinToString(" ; ")}")
        val xiaomi = fr.dd.jarvis.device.Xiaomi.conseilLancement(app)
        return "⚠️ Je n'ai pas réussi à démarrer le guidage vers $nom : " + echecs.joinToString(" ; ") + ". " +
            "L'itinéraire est prêt dans une notification sur le téléphone, ou ouvre ${choix.ifBlank { "ton GPS" }} dans Android Auto. " +
            xiaomi.ifBlank {
                "Pour que je le lance tout seul : autorise « Afficher par-dessus les autres applis » pour JARVIS" +
                    (if (fr.dd.jarvis.service.JarvisAccessibilityService.instance == null) ", ou active son service d'accessibilité" else "") + "."
            }
    }

    /** Notification « Itinéraire vers … » : un toucher ouvre le guidage (lancement autorisé par Android). */
    private fun notifier(app: JarvisApp, destination: String, point: Pair<Double, Double>?, appli: String) {
        runCatching {
            val a = appli.lowercase()
            val uri = when {
                a.contains("waze") -> if (point != null) "waze://?ll=${coord(point.first)},${coord(point.second)}&navigate=yes" else "waze://?q=" + Uri.encode(destination) + "&navigate=yes"
                else -> "google.navigation:q=" + (point?.let { "${coord(it.first)},${coord(it.second)}" } ?: Uri.encode(destination))
            }
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(app, 31, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, JarvisApp.CHANNEL_AGENTS).setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("🧭 Itinéraire vers ${destination.ifBlank { "la destination" }.take(60)}")
                .setContentText("Touche pour démarrer le guidage").setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).setAutoCancel(true).build()
            app.getSystemService(NotificationManager::class.java).notify(NOTIF, n)
        }
    }
}
