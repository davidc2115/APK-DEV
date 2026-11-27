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
import fr.dd.jarvis.societe.Geo.Precision as P
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
    fun noter(pkg: String, quand: Long, categorie: String?, @Suppress("UNUSED_PARAMETER") permanente: Boolean) {
        if (pkg !in PAQUETS) return
        // Une notification persistante seule ne prouve pas que le calcul d’itinéraire a démarré.
        if (categorie == Notification.CATEGORY_NAVIGATION) dernieres.merge(pkg, quand) { a, b -> maxOf(a, b) }
    }

    /** Fenêtre de Waze / Maps ouverte sur le téléphone (appelé par le service d'accessibilité). */
    fun noterFenetre(pkg: String) { if (pkg in PAQUETS) fenetres[pkg] = System.currentTimeMillis() }

    /** Réinitialisation déterministe réservée aux tests unitaires. */
    internal fun reinitialiserPourTests() { dernieres.clear(); fenetres.clear() }

    /** Waze / Maps affiché il y a moins de [ms] (diagnostic uniquement, jamais une preuve de guidage). */
    fun fenetreRecente(ms: Long = 120_000): Boolean = fenetres.values.any { System.currentTimeMillis() - it < ms }

    /** Une fenêtre Waze/Maps est-elle apparue après cette demande ? Cela confirme l'ouverture, pas le guidage. */
    fun fenetreDepuis(depuis: Long): Boolean = fenetres.values.any { it >= depuis }

    /** Attend l'ouverture effective de l'application GPS, sans la confondre avec le départ du guidage. */
    suspend fun attendreOuverture(depuis: Long, ms: Long = 6_000): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (fenetreDepuis(depuis)) return true
            delay(300)
        }
        return fenetreDepuis(depuis)
    }



    /** Bouton de départ du guidage (« Y aller », « Démarrer »…) : appuyer dessus est sans risque, aucune confirmation. */
    private val BOUTON_DEPART = Regex("(?i)^\\s*(y aller|go|démarrer|demarrer|partir|c'est parti|lancer|lancer le guidage|commencer|start|démarrer la navigation|demarrer la navigation|aller)\\s*[!.]?\\s*$")
    fun boutonDepart(texte: String): Boolean = BOUTON_DEPART.matches(texte)

    /** Voix de guidage en cours (Waze / Maps annonce le départ) : lecture audio « navigation » active. */
    fun guidageVocal(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(AudioManager::class.java).activePlaybackConfigurations
            .any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
    }.getOrDefault(false)

    /**
     * Guidage démarré depuis [depuis] ? Seules une notification explicitement classée NAVIGATION ou une sortie audio
     * ASSISTANCE_NAVIGATION_GUIDANCE comptent. Une simple ouverture de Waze/Maps ne suffit pas.
     * null = impossible à savoir (ni notifications ni accessibilité).
     */
    fun demarreDepuis(depuis: Long, ctx: Context? = null): Boolean? {
        if (dernieres.values.any { it >= depuis }) return true
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

    /**
     * URI de l'intent NAVIGATE d'Android Auto. Avec l'action NAVIGATE, « geo:lat,lon » et « geo:0,0?q=… » démarrent
     * tous deux la NAVIGATION (une simple épingle, c'est l'action VIEW) — documentation Android « intents des applis de
     * navigation ». Les coordonnées vérifiées passent donc en priorité : avec le texte seul, Waze ou Maps refaisaient
     * une recherche (liste de résultats à toucher, ou mauvaise commune : « rue de Massingy à Vitteaux » →
     * Massingy-lès-Vitteaux) et le guidage ne partait pas.
     */
    fun geo(destination: String, point: Pair<Double, Double>?): String =
        if (point != null) "geo:${coord(point.first)},${coord(point.second)}" else "geo:0,0?q=" + requete(destination.trim())

    private val R_NUMERO = Regex("""(?:^|[\s,])\d{1,4}(?:\s*(?:bis|ter|quater|[a-d])\b)?(?=[\s,]+[^\d\s,])""", RegexOption.IGNORE_CASE)
    private val R_VOIE = Regex("\\b(rue|avenue|av|boulevard|bd|chemin|route|impasse|allee|place|quai|cours|lotissement|residence|faubourg|promenade|square)\\b")

    /**
     * La position trouvée correspond-elle assez à ce qui a été demandé pour guider directement dessus ? Numéro demandé →
     * au numéro près ; rue demandée → la rue ; lieu-dit, établissement → le lieu ; une commune seule → son centre.
     */
    internal fun pointFiable(destination: String, loc: fr.dd.jarvis.societe.Geo.Localisation?): Boolean {
        loc ?: return false
        val n = fr.dd.jarvis.societe.core.normaliser(destination)
        return when {
            loc.precision == P.MANUELLE -> true
            R_NUMERO.containsMatchIn(destination) -> loc.precision == P.NUMERO
            R_VOIE.containsMatchIn(n) -> loc.precision.rang >= P.RUE.rang
            loc.precision.rang >= P.LIEU_DIT.rang -> true
            // Commune seule (« Dijon », « à Vitteaux ») : tous les mots demandés sont dans la commune trouvée.
            loc.precision == P.COMMUNE -> {
                val demandes = n.split(' ').filter { it.length >= 3 && it !in setOf("les", "des", "sur", "sous", "pres", "ville", "centre") }
                val trouve = " " + fr.dd.jarvis.societe.core.normaliser(loc.trouve) + " "
                demandes.isNotEmpty() && demandes.all { trouve.contains(" $it ") }
            }
            else -> false
        }
    }

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
            journal.noter("voiture", "Android Auto ${if (accepte) "a accepté la demande (pas une preuve de guidage)" else "a refusé"} ${geo(destination, point)}")
            if (!accepte) {
                // Le contexte Android Auto existe : ne pas lancer une seconde navigation sur le téléphone en arrière-plan.
                journal.noter("voiture", "Android Auto a refusé la demande ; aucun lancement de secours sur le téléphone")
                return "⚠️ Android Auto a refusé la demande d'itinéraire vers $nom. Le guidage n'a pas été confirmé et je n'ai pas lancé une deuxième application sur le téléphone."
            }
            // Android Auto pilote le GPS de l'écran de voiture. Ses API ne renvoient pas l'état du calcul
            // d'itinéraire à l'application téléphone : les notifications et l'audio du téléphone ne sont que
            // des indices facultatifs et peuvent être absents même quand l'écran voiture a bien démarré.
            // On conserve une courte fenêtre pour une confirmation positive, mais son absence ne doit plus
            // être présentée comme un échec du lancement ni déclencher un second lancement sur le téléphone.
            when (attendre(t0, 5_000, app)) {
                true -> {
                    journal.noter("voiture", "guidage confirmé par un signal disponible après transmission Android Auto vers $nom")
                    return "🧭 Android Auto a reçu l'itinéraire vers $nom et le guidage est confirmé."
                }
                false -> {
                    journal.noter("voiture", "itinéraire transmis à Android Auto vers $nom ; aucun signal de confirmation remonté au téléphone (état écran voiture non exposé)")
                    return "🧭 Itinéraire transmis à Android Auto vers $nom. L'état du guidage sur l'écran de la voiture ne remonte pas à JARVIS sur cet appareil ; vérifie que Waze ou Google Maps affiche bien la destination. Je ne relance pas une deuxième navigation sur le téléphone."
                }
                null -> {
                    journal.noter("voiture", "itinéraire transmis à Android Auto vers $nom ; confirmation non exposée par le système")
                    return "🧭 Itinéraire transmis à Android Auto vers $nom. Android Auto ne communique pas à JARVIS l'état du guidage sur l'écran de la voiture ; vérifie simplement que la destination s'affiche. Je ne lance pas de deuxième navigation sur le téléphone."
                }
            }
        } else echecs += "JARVIS n'a pas de contexte écran Android Auto ; tentative via le téléphone"

        // 2. L'appli de navigation du téléphone (Waze de préférence) : son guidage s'affiche dans la voiture.
        val dispo = app.phone.navApps()
        val choix = appli.trim().ifBlank { app.store.appPrefs["navigation"].orEmpty() }.ifBlank { dispo.keys.firstOrNull { it.contains("Waze") } ?: dispo.keys.firstOrNull().orEmpty() }
        val t1 = System.currentTimeMillis()
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.phone.navigate(destination, "d", choix, point) }
        journal.noter("voiture", "téléphone : ${choix.ifBlank { "Google Maps" }} → ${r.take(120)}")
        if (r.startsWith("🧭")) {
            // Waze démarré à froid met plusieurs secondes à calculer l'itinéraire.
            val nomAppli = choix.ifBlank { "Google Maps" }
            // Premier contrôle court : souvent l'appli s'ouvre sur l'itinéraire calculé et attend « Y aller » / « Démarrer ».
            when (attendre(t1, 6_000, app)) {
                true -> { journal.noter("voiture", "guidage démarré par $nomAppli vers $nom"); return "🧭 Itinéraire vers $nom lancé dans $nomAppli." }
                null -> {
                    val ouverte = attendreOuverture(t1, 6_000)
                    val appui = if (ouverte) appuyerDepart(journal, nomAppli) else null
                    return when {
                        appui != null -> "🧭 Itinéraire vers $nom lancé dans $nomAppli (j'ai appuyé sur « $appui »)."
                        ouverte -> "⚠️ $nomAppli s'est ouvert pour $nom, mais l'accès aux notifications ne permet pas de confirmer le guidage. Vérifie que l'itinéraire a bien démarré."
                        else -> "⚠️ La demande a été transmise à $nomAppli, mais je ne peux pas vérifier que l'application ou le guidage s'est ouvert. Active l'accès aux notifications et l'accessibilité de JARVIS pour un contrôle fiable."
                    }
                }
                false -> {
                    val ouverte = attendreOuverture(t1, 6_000)
                    val blocage = blocageProbable(app)
                    if (!ouverte) {
                        journal.noter("voiture", "$nomAppli : aucune ouverture d'application détectée après l'intent")
                        return "⚠️ Android a accepté la demande pour $nomAppli, mais l'application GPS ne s'est pas ouverte. Le guidage n'a donc pas démarré. ${blocage ?: "Vérifie l'autorisation de lancement en arrière-plan et que l'application est installée."}"
                    }
                    // Ouverte mais pas partie : JARVIS appuie lui-même sur le bouton de départ, puis revérifie.
                    val appui = appuyerDepart(journal, nomAppli)
                    val t2 = System.currentTimeMillis()
                    if (appui != null && attendre(t2 - 500, 7_000, app) == true) {
                        journal.noter("voiture", "guidage démarré par $nomAppli vers $nom après appui sur « $appui »")
                        return "🧭 Itinéraire vers $nom lancé dans $nomAppli."
                    }
                    if (attendre(t1, 4_000, app) == true) return "🧭 Itinéraire vers $nom lancé dans $nomAppli."
                    journal.noter("voiture", "$nomAppli : application ouverte, mais aucun signal de guidage" + (appui?.let { " (même après appui sur « $it »)" } ?: " (bouton de départ non trouvé à l'écran)"))
                    return "⚠️ $nomAppli s'est bien ouvert pour $nom, mais je ne détecte pas le démarrage de l'itinéraire" +
                        (if (appui != null) " (j'ai pourtant appuyé sur « $appui »)" else "") + ". Vérifie le bouton « Démarrer / Y aller » dans l'application."
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

    /** Bouton de départ de Waze / Maps touché par le service d'accessibilité (jusqu'à ~4 s d'attente de son affichage). */
    private suspend fun appuyerDepart(journal: fr.dd.jarvis.voice.JournalVoix, nomAppli: String): String? {
        val acc = fr.dd.jarvis.service.JarvisAccessibilityService.instance ?: return null
        repeat(8) {
            val l = runCatching { acc.cliquerDepartGuidage(PAQUETS) }.getOrNull()
            if (l != null) { journal.noter("voiture", "$nomAppli : bouton « $l » appuyé pour démarrer le guidage"); return l }
            delay(500)
        }
        return null
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
