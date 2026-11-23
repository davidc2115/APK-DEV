package fr.dd.jarvis.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Brain
import fr.dd.jarvis.ai.CanalDemande
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.ai.ToolBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Une routine : plusieurs actions lancées d'un coup, par une phrase, un lieu, une heure ou la voiture. */
data class Routine(
    val id: String,
    val nom: String,
    val declencheur: String = "phrase",
    val phrases: List<String> = emptyList(),
    val heure: String = "",
    val jours: String = "tous",
    val actions: List<String> = emptyList(),
    val active: Boolean = true,
)

/**
 * Routines de JARVIS (« Jarvis, je pars » → éteindre la maison, itinéraire du premier client, musique, prévenir
 * l'équipe…). Déclencheurs : phrase dite ou écrite, arrivée / départ de la maison ou d'un chantier (détectés par la
 * veille, toutes les 15 min), connexion à la voiture, heure (± 15 min). Les actions sont des demandes en français,
 * exécutées dans l'ordre par JARVIS avec ses outils (domotique, musique, SMS, agenda, navigation…).
 */
object Routines {
    val DECLENCHEURS = linkedMapOf(
        "phrase" to "🗣️ Phrase", "depart_maison" to "🏠➡️ En quittant la maison", "arrivee_maison" to "➡️🏠 En arrivant à la maison",
        "arrivee_chantier" to "👷 En arrivant sur un chantier", "depart_chantier" to "👷➡️ En quittant un chantier",
        "voiture" to "🚗 En montant en voiture", "heure" to "⏰ À une heure",
    )
    val JOURS = linkedMapOf("tous" to "Tous les jours", "semaine" to "Lun → ven", "weekend" to "Week-end")

    private val DEFAUT = listOf(
        Routine("je_pars", "Je pars", "phrase", listOf("je pars", "je m'en vais"), actions = listOf(
            "Éteins les lumières de la maison",
            "Donne-moi l'itinéraire vers mon prochain rendez-vous d'aujourd'hui",
            "Dis-moi la météo des prochaines heures en une phrase",
        )),
        Routine("bonne_nuit", "Bonne nuit", "phrase", listOf("bonne nuit", "je vais me coucher"), actions = listOf(
            "Éteins toutes les lumières de la maison",
            "Dis-moi mon premier rendez-vous de demain, l'heure à laquelle partir et la météo prévue",
        )),
    )

    private fun fichier(app: JarvisApp) = File(app.filesDir, "routines.json")

    @Synchronized fun liste(app: JarvisApp): List<Routine> {
        val f = fichier(app)
        if (!f.exists()) return DEFAUT
        return runCatching { depuisJson(JSONArray(f.readText())) }.getOrDefault(DEFAUT)
    }

    @Synchronized fun enregistrer(app: JarvisApp, l: List<Routine>) { fichier(app).writeText(versJson(l).toString(2)) }

    fun ajouterOuModifier(app: JarvisApp, r: Routine) {
        val l = liste(app).toMutableList()
        val i = l.indexOfFirst { it.id == r.id }
        if (i >= 0) l[i] = r else l += r
        enregistrer(app, l)
    }

    fun supprimer(app: JarvisApp, id: String) = enregistrer(app, liste(app).filter { it.id != id })

    internal fun versJson(l: List<Routine>) = JSONArray().also { a ->
        l.forEach { r ->
            a.put(JSONObject().put("id", r.id).put("nom", r.nom).put("declencheur", r.declencheur).put("phrases", JSONArray(r.phrases))
                .put("heure", r.heure).put("jours", r.jours).put("actions", JSONArray(r.actions)).put("active", r.active))
        }
    }

    internal fun depuisJson(a: JSONArray): List<Routine> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
        fun l(k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        Routine(o.optString("id"), o.optString("nom"), o.optString("declencheur", "phrase"), l("phrases"), o.optString("heure"),
            o.optString("jours", "tous"), l("actions"), o.optBoolean("active", true))
    }

    fun nouvelId(nom: String) = norm(nom).replace(' ', '_').take(30).ifBlank { "routine" } + "_" + (System.currentTimeMillis() % 100000)

    internal fun norm(s: String) = fr.dd.jarvis.societe.core.normaliser(s).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Texte réduit à l'essentiel (« Jarvis, je pars s'il te plaît ! » → « je pars »). */
    internal fun essentiel(texte: String): String = norm(texte)
        .replace(Regex("^(ok |hey |dis |bon )?jarvis "), "")
        .replace(Regex(" (s il te plait|s il vous plait|stp|merci|jarvis)$"), "")
        .trim()

    /** Verbe d'ordre et mot « mode / routine » retirés : « active le mode coucher », « passe en mode coucher » → « coucher ». */
    private val ORDRE = Regex("^(?:(?:active|activer|lance|lancer|demarre|demarrer|execute|executer|enclenche|declenche|mets|met|mettre|passe|passer|bascule|on passe|fais|je veux|je passe)\\s+)?" +
        "(?:en\\s+|sur\\s+)?(?:le |la |ma |mon |l |les |mes )?(?:mode |routine |scenario |scene |ambiance )?")

    internal fun cle(s: String): String = norm(s).replace(Regex("^(ok |hey |dis |bon )?jarvis "), "").replace(Regex(" (s il te plait|s il vous plait|stp|merci|jarvis)$"), "")
        .replace(ORDRE, "").removePrefix("mode ").removePrefix("routine ").trim()

    /**
     * La phrase dite déclenche-t-elle cette routine ? Phrase exacte, ou nom / phrase de la routine précédés d'un ordre
     * (« mode coucher », « active le mode coucher », « lance la routine coucher », « passe en mode coucher »).
     */
    internal fun correspond(r: Routine, texte: String): Boolean {
        if (!r.active) return false
        val e = essentiel(texte)
        if (e.isBlank()) return false
        if (r.declencheur == "phrase" && r.phrases.any { norm(it) == e }) return true
        val c = cle(texte)
        if (c.length < 3) return false
        return cle(r.nom) == c || (r.declencheur == "phrase" && r.phrases.any { cle(it) == c })
    }

    fun parPhrase(app: JarvisApp, texte: String): Routine? = if (texte.length > 80) null else liste(app).firstOrNull { correspond(it, texte) }

    /** Routine désignée par son nom (« coucher », « mode coucher ») : repli de l'activation d'un mode. */
    fun parNom(app: JarvisApp, nom: String): Routine? = cle(nom).takeIf { it.length >= 3 }?.let { c ->
        liste(app).firstOrNull { it.active && (cle(it.nom) == c || it.phrases.any { p -> cle(p) == c }) }
    }

    internal fun joursOk(jours: String, jour: DayOfWeek): Boolean = when (jours) {
        "semaine" -> jour.value <= 5
        "weekend" -> jour.value >= 6
        else -> true
    }

    /** Consigne donnée au cerveau : toutes les actions d'un coup, compte rendu court. */
    internal fun consigne(r: Routine, contexte: String = ""): String =
        "Routine « ${r.nom} »" + (if (contexte.isNotBlank()) " ($contexte)" else "") +
            " : exécute ces actions dans l'ordre avec tes outils, sans redemander de confirmation (sauf action sensible), " +
            "sans jamais administrer, recharger ni redémarrer Home Assistant ou le serveur, " +
            "puis réponds par un compte rendu très court, une ligne par action commençant par ✅ ou ❌ :\n" +
            r.actions.mapIndexed { i, a -> "${i + 1}. $a" }.joinToString("\n")

    /** Routine en cours jusqu'à cette heure (0 = aucune) : les outils d'administration sont refusés à tous. */
    @Volatile private var enCoursJusqua = 0L
    private val INTERDITS = setOf("ha_admin", "ha_config", "ha_sauvegarde", "ha_dashboard", "serveur_service", "serveur_tache", "serveur_diagnostic",
        "serveur_fichiers", "serveur_scan_ports", "ssh_commande", "ssh_fichier", "ssh_script", "pilote_ecran", "piloter_telephone")

    /** Refus d'un outil pendant une routine (null : autorisé). */
    fun refus(outil: String, a: JSONObject): String? {
        if (System.currentTimeMillis() > enCoursJusqua) return null
        val domaine = a.optString("domaine").trim().lowercase()
        if (outil !in INTERDITS && !(outil == "ha_service" && domaine in setOf("homeassistant", "hassio", "recorder", "system_log", "logger"))) return null
        fr.dd.jarvis.voice.JournalVoix.noter("routine", "outil « $outil » refusé pendant la routine (administration / redémarrage)")
        return "⛔ Interdit pendant une routine : pas d'administration de Home Assistant ni du serveur (recharger, redémarrer, configurer). " +
            "Fais seulement l'action demandée avec ha_service sur l'appareil, sinon réponds ❌ pour cette action."
    }

    /**
     * Exécute la routine action par action, dans l'ordre. Chaque action qu'une commande directe sait faire (musique,
     * poêle, lumière, appli, navigation, mode du téléphone…) part TOUT DE SUITE, sans IA ; les autres sont traitées
     * EXACTEMENT comme si tu les disais toi-même (mêmes notes, même cerveau, Hermès compris) — avant, elles partaient
     * dans une consigne « routine » à part, qui échouait là où la même phrase dite hors routine marchait. Pendant la
     * routine, rien ne peut administrer ni redémarrer Home Assistant ou le serveur.
     */
    suspend fun executer(app: JarvisApp, r: Routine, contexte: String = "", tools: ToolBox? = null): String {
        marquer(app, r)
        if (r.actions.isEmpty()) return "La routine « ${r.nom} » n'a aucune action : ajoute-en dans Réglages › Agents › Routines."
        val brain = Brain(app.llm, tools ?: ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }
        val debut = System.currentTimeMillis()
        val lignes = mutableListOf<String>()
        val actions = r.actions.map { it.trim() }.filter { it.isNotBlank() }
        enCoursJusqua = System.currentTimeMillis() + 10 * 60_000L
        try {
            var poeleAvant = false
            for (a in actions) {
                enCoursJusqua = System.currentTimeMillis() + 5 * 60_000L
                // Deux poêles du même compte cloud (Agua IoT, MCZ…) commandés coup sur coup : le second ordre arrivait
                // pendant le premier et le cloud l'abandonnait (« jobAnswerStatus … terminated »). Un temps de battement.
                val poele = Regex("\\b(poeles?|poiles?|poils?|granules?|pellets?)\\b").containsMatchIn(fr.dd.jarvis.societe.core.normaliser(a))
                if (poele && poeleAvant) kotlinx.coroutines.delay(4000)
                poeleAvant = poele
                val t0 = System.currentTimeMillis()
                val direct = runCatching { brain.reflexeSeul(a) }.getOrNull()
                val rep = direct ?: runCatching {
                    brain.reply(listOf(ChatMsg(true, a)), apprendre = false, vocal = CanalDemande.vocal) { }
                }.getOrElse { "❌ ${it.message ?: it.javaClass.simpleName}" }
                fr.dd.jarvis.voice.JournalVoix.noter("routine", "« ${a.take(60)} » ${if (direct != null) "sans IA" else "comme une demande normale"} en ${System.currentTimeMillis() - t0} ms : ${rep.lineSequence().firstOrNull().orEmpty().take(80)}")
                lignes += ligne(a, rep)
            }
        } finally { enCoursJusqua = 0L }
        fr.dd.jarvis.voice.JournalVoix.noter("routine", "« ${r.nom} » terminée en ${System.currentTimeMillis() - debut} ms")
        return "⚙️ Routine « ${r.nom} »\n" + lignes.joinToString("\n")
    }

    /** Compte rendu d'une action en une ligne : ✅ / ❌ puis le résultat court. */
    internal fun ligne(action: String, resultat: String): String {
        val premiere = resultat.lineSequence().map { it.replace(Regex("[#*_`>|]+"), "").trim() }.firstOrNull { it.isNotBlank() }.orEmpty().take(140)
        val echec = Regex("^(❌|⚠️)|(^|[^\\p{L}])([ÉéEe]chec|[Ii]mpossible|[Ii]ntrouvable|[Ee]rreur)").containsMatchIn(premiere)
        val texte = premiere.replace(Regex("^(✅|❌|⚠️)\\s*"), "").ifBlank { action }
        return (if (echec) "❌ " else "✅ ") + texte
    }

    private fun marquer(app: JarvisApp, r: Routine) {
        val o = runCatching { JSONObject(app.store.routinesDernier.value) }.getOrDefault(JSONObject())
        app.store.routinesDernier.set(o.put(r.id, System.currentTimeMillis()).toString())
    }

    private fun derniere(app: JarvisApp, r: Routine): Long = runCatching { JSONObject(app.store.routinesDernier.value).optLong(r.id) }.getOrDefault(0L)

    /** Un évènement (lieu, voiture) s'est produit : lance les routines concernées et prévient par notification. */
    suspend fun declencher(app: JarvisApp, evenement: String, contexte: String = "") {
        val l = liste(app).filter { it.active && it.declencheur == evenement && System.currentTimeMillis() - derniere(app, it) > 10 * 60_000L }
        for (r in l) {
            val rep = executer(app, r, contexte)
            notifier(app, r, rep)
            if (app.enVoiture) runCatching { fr.dd.jarvis.service.CarBridge.post(app, rep.replace(Regex("[#*_`>|]+"), "")) }
        }
    }

    /** Routines à heure fixe (appelée par la veille, toutes les 15 min) : une fois par jour, dans les 20 min qui suivent l'heure. */
    suspend fun horaires(app: JarvisApp) {
        val maintenant = LocalTime.now()
        val aujourdhui = LocalDate.now()
        for (r in liste(app).filter { it.active && it.declencheur == "heure" }) {
            val h = runCatching { LocalTime.parse(r.heure.trim().padStart(5, '0').replace('h', ':')) }.getOrNull() ?: continue
            if (!joursOk(r.jours, aujourdhui.dayOfWeek)) continue
            val ecart = java.time.Duration.between(h, maintenant).toMinutes()
            if (ecart !in 0..20) continue
            val deja = java.time.Instant.ofEpochMilli(derniere(app, r)).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == aujourdhui
            if (deja) continue
            notifier(app, r, executer(app, r, "heure prévue ${r.heure}"))
        }
    }

    /** Lieu actuel → évènements d'arrivée / de départ (appelée par la veille). */
    suspend fun surveillerLieu(app: JarvisApp) {
        val lieu = runCatching { Contexte.lieu(app) }.getOrNull() ?: return
        val avant = app.store.veilleLieu.value
        if (lieu == avant) return
        app.store.veilleLieu.set(lieu)
        if (avant.isBlank()) return
        if (avant == "maison") declencher(app, "depart_maison")
        if (avant.startsWith("chantier:")) declencher(app, "depart_chantier", "chantier quitté : ${avant.substringAfterLast('|')}")
        if (lieu == "maison") declencher(app, "arrivee_maison")
        if (lieu.startsWith("chantier:")) declencher(app, "arrivee_chantier", "arrivée chez ${lieu.substringAfterLast('|')}")
    }

    private fun notifier(app: JarvisApp, r: Routine, texte: String) {
        val canal = "routines_jarvis"
        if (Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(canal, "Routines", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(app, 7740, Intent(app, fr.dd.jarvis.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            NotificationManagerCompat.from(app).notify(7741 + (r.id.hashCode() and 0xFF), NotificationCompat.Builder(app, canal)
                .setSmallIcon(android.R.drawable.ic_menu_rotate).setContentTitle("⚙️ Routine « ${r.nom} »")
                .setContentText(texte.lines().drop(1).firstOrNull().orEmpty()).setStyle(NotificationCompat.BigTextStyle().bigText(texte))
                .setContentIntent(pi).setAutoCancel(true).setSilent(Contexte.notificationsDiscretes(app)).build())
        }
    }

    /** Présentation pour le chat (outil routines_lister). */
    fun presenter(l: List<Routine>): String = if (l.isEmpty()) "Aucune routine." else "# ⚙️ Routines\n" + l.joinToString("\n\n") { r ->
        "**${r.nom}**" + (if (!r.active) " (désactivée)" else "") + " — " + (DECLENCHEURS[r.declencheur] ?: r.declencheur) +
            (if (r.declencheur == "phrase") " : « ${r.phrases.joinToString("», «")} »" else "") +
            (if (r.declencheur == "heure") " ${r.heure} (${JOURS[r.jours] ?: r.jours})" else "") + "\n" +
            r.actions.mapIndexed { i, a -> "${i + 1}. $a" }.joinToString("\n")
    }
}
