package fr.dd.jarvis.device

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.CommandesDirectes
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Mode rendez-vous : le téléphone passe en Ne pas déranger au début de chaque rendez-vous de l'agenda (ceux d'un client —
 * titre avec un client de la Société, « intervention », « chantier », « RDV »… — ou tous, au choix) et le son revient à la
 * fin. En option, un SMS « je te rappelle après » part aux CONTACTS dont l'appel est manqué pendant le rendez-vous (jamais
 * aux numéros inconnus, une seule fois par personne). À la fin : la liste des appels manqués.
 */
object SilenceRdv {
    const val ACT_DEBUT = "fr.dd.jarvis.SILENCE_RDV_DEBUT"
    const val ACT_FIN = "fr.dd.jarvis.SILENCE_RDV_FIN"
    const val ACT_ARRET = "fr.dd.jarvis.SILENCE_RDV_ARRET"
    private const val CANAL = "rdv_silence_jarvis"
    private const val NOTIF = 8400

    private val MOTS_RDV = Regex("\\b(rdv|rendez vous|clients?|intervention|chantier|visite|devis|depannage|installation|mise en service|maintenance|entretien|reunion|expertise|diagnostic|sav|rencontre|signature|releve|metrage|audit)\\b")
    private val PAS_RDV = Regex("\\b(anniversaire|anniv|conges?|vacances|ferie|fete|perso|sport|repas|dejeuner|pause|trajet|route)\\b")

    /** Ce rendez-vous justifie-t-il le silence ? Tous les événements à heure fixe (option « tous »), sinon ceux d'un client. */
    internal fun eligible(titre: String, lieu: String, description: String, journee: Boolean, debut: Long, fin: Long, clients: List<String>, tous: Boolean): Boolean {
        if (journee || fin <= debut || fin - debut > 10 * 3_600_000L) return false
        val t = normaliser("$titre $lieu")
        if (PAS_RDV.containsMatchIn(t)) return false
        if (tous || MOTS_RDV.containsMatchIn(t)) return true
        val tout = normaliser("$titre $lieu $description")
        return clients.any { c -> val n = normaliser(c); n.length >= 3 && Regex("\\b${Regex.escape(n)}\\b").containsMatchIn(tout) }
    }

    fun rdvs(app: JarvisApp, depuis: Long, jusqua: Long): List<EventInfo> {
        if (!app.calendar.hasPermission()) return emptyList()
        val clients = runCatching { app.societe.data.clients.map { it.nom } }.getOrDefault(emptyList())
        val jour = Instant.ofEpochMilli(depuis).atZone(ZoneId.systemDefault()).toLocalDate()
        val tous = app.store.silenceRdvTous.value
        return runCatching { app.calendar.events(jour, 3) }.getOrDefault(emptyList())
            .filter { it.end > depuis && it.begin < jusqua && eligible(it.title, it.location, it.description, it.allDay, it.begin, it.end, clients, tous) }
            .sortedBy { it.begin }
    }

    // ------------------------------------------------------------------ programmation

    private fun intent(app: Context, action: String, e: EventInfo? = null) = Intent(app, SilenceRdvReceiver::class.java).setAction(action).apply {
        if (e != null) putExtra("id", e.id).putExtra("titre", e.title).putExtra("fin", e.end)
    }

    private fun code(id: Long, fin: Boolean) = 31_000_000 + ((id and 0x3FFFFF).toInt() shl 1) + if (fin) 1 else 0

    /** (Re)programme les débuts et fins des rendez-vous des 36 prochaines heures ; silence tout de suite si un rendez-vous est en cours. */
    fun planifier(app: JarvisApp) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val prefs = app.getSharedPreferences("jarvis_silence_rdv", Context.MODE_PRIVATE)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // Anciennes alarmes (rendez-vous déplacé ou supprimé) : retirées avant de reprogrammer.
        runCatching { JSONArray(prefs.getString("prog", "[]")) }.getOrDefault(JSONArray()).let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                runCatching { am.cancel(PendingIntent.getBroadcast(app, o.optInt("c"), intent(app, o.optString("a")), flags)) }
            }
        }
        val prog = JSONArray()
        if (!app.store.silenceRdvOn.value) {
            prefs.edit().putString("prog", "[]").apply()
            if (etat(app) != null) fin(app, null, manuel = true)
            return
        }
        val now = System.currentTimeMillis()
        val l = rdvs(app, now, now + 36 * 3_600_000L)
        fun prog(action: String, e: EventInfo, quand: Long, fin: Boolean) {
            val c = code(e.id, fin)
            val pi = PendingIntent.getBroadcast(app, c, intent(app, action, e), flags)
            runCatching {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, pi)
                prog.put(JSONObject().put("c", c).put("a", action))
            }
        }
        for (e in l) {
            if (e.begin > now) prog(ACT_DEBUT, e, e.begin, false)
            prog(ACT_FIN, e, e.end, true)
        }
        prefs.edit().putString("prog", prog.toString()).apply()
        // Rendez-vous déjà en cours (téléphone redémarré, mode activé en plein rendez-vous) — sauf si le son a été rétabli à la main.
        val ignore = prefs.getLong("ignore", -1L)
        l.firstOrNull { it.begin <= now + 60_000 && it.end > now + 60_000 && it.id != ignore }?.let { debut(app, it.id, it.title, it.end) }
    }

    // ------------------------------------------------------------------ début / fin

    fun etat(app: JarvisApp): JSONObject? = runCatching { JSONObject(app.store.silenceRdvEtat.value) }.getOrNull()?.takeIf { it.optBoolean("actif") }
    private fun sauver(app: JarvisApp, o: JSONObject?) = app.store.silenceRdvEtat.set(o?.toString().orEmpty())

    private fun npdActif(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    fun accesNpd(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted || PhoneShell.ready(ctx)

    private fun heure(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let { "${it.hour} h${if (it.minute > 0) "%02d".format(it.minute) else ""}" }

    fun debut(app: JarvisApp, id: Long, titre: String, fin: Long) {
        if (!app.store.silenceRdvOn.value || fin <= System.currentTimeMillis()) return
        etat(app)?.let { e ->
            // Rendez-vous enchaînés : le silence continue jusqu'à la fin du dernier.
            if (fin > e.optLong("fin")) { e.put("fin", fin).put("id", id).put("titre", titre); sauver(app, e) }
            return
        }
        val deja = npdActif(app)
        if (!deja) {
            if (!accesNpd(app)) { notifier(app, "🔕 Mode rendez-vous : accès manquant", "Autorise « Ne pas déranger » pour JARVIS (Réglages Android › Notifications › Ne pas déranger › Accès) pour qu'il coupe le son pendant tes rendez-vous.", false); return }
            if (!ModeSonore.appliquer(app, ModeSonore.NPD).startsWith("🔕")) return
        }
        sauver(app, JSONObject().put("actif", true).put("id", id).put("titre", titre).put("fin", fin).put("avant", if (deja) "npd" else "")
            .put("manques", JSONArray()).put("sms", JSONArray()))
        notifier(app, "🔕 En rendez-vous jusqu'à ${heure(fin)}", "« $titre » : Ne pas déranger activé, le son revient à la fin." +
            (if (app.store.silenceRdvSms.value) " Les contacts qui appellent reçoivent un SMS « je te rappelle »." else ""), true)
    }

    fun fin(app: JarvisApp, id: Long?, manuel: Boolean = false) {
        val e = etat(app) ?: return
        val now = System.currentTimeMillis()
        if (!manuel) {
            // Fin d'un rendez-vous déjà prolongé par un autre : on attend la fin du dernier.
            if (id != null && e.optLong("id") != id && e.optLong("fin") > now + 60_000) return
            rdvs(app, now, now + 120_000).firstOrNull { it.begin <= now + 60_000 && it.end > now + 120_000 }?.let { s ->
                e.put("id", s.id).put("titre", s.title).put("fin", s.end); sauver(app, e); return
            }
        }
        if (manuel) app.getSharedPreferences("jarvis_silence_rdv", Context.MODE_PRIVATE).edit().putLong("ignore", e.optLong("id")).apply()
        if (e.optString("avant") != "npd") runCatching { ModeSonore.appliquer(app, ModeSonore.NPD_FIN) }
        sauver(app, null)
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIF) }
        val manques = e.optJSONArray("manques")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        notifier(app, "🔔 Fin du rendez-vous : son rétabli", if (manques.isEmpty()) "« ${e.optString("titre")} » terminé, aucun appel manqué."
            else "Appels manqués pendant « ${e.optString("titre")} » : ${manques.joinToString(", ")}.", false, NOTIF + 1)
    }

    // ------------------------------------------------------------------ appels manqués

    /** Qui a appelé, d'après la notification d'appel manqué (nom ou numéro) ; null si plusieurs appelants ou rien d'exploitable. */
    internal fun appelant(titre: String, texte: String): String? {
        Regex("\\+?\\d[\\d .()/-]{6,}\\d").find("$titre $texte")?.let { return it.value.trim() }
        val bruit = Regex("(?i)^(?:\\d+\\s+)?(?:appels?\\s+manqu[ée]s?|missed\\s+calls?|appels?\\s+en\\s+absence)(?:\\s+(?:de|from))?\\s*:?\\s*")
        val fin = Regex("(?i)\\s*[·•,-]?\\s*(?:appel\\s+manqu[ée]|missed\\s+call|mobile|domicile|travail|portable|\\d{1,2}[:h]\\d{2})\\s*$")
        return listOf(titre, texte).map { it.trim().replace(bruit, "").replace(fin, "").trim() }
            .firstOrNull { it.isNotBlank() && !Regex("(?i)appels?\\s+manqu|missed|en absence|^\\d+$|,| et ").containsMatchIn(it) }
    }

    /** Notification d'appel manqué (catégorie Android, ou texte d'une appli Téléphone qui ne la renseigne pas). */
    fun estAppelManque(pkg: String, categorie: String?, titre: String, texte: String): Boolean =
        categorie == android.app.Notification.CATEGORY_MISSED_CALL ||
            (Regex("dialer|incallui|telecom|phone|contacts", RegexOption.IGNORE_CASE).containsMatchIn(pkg) && Regex("(?i)manqu|missed|en absence").containsMatchIn("$titre $texte"))

    fun appelManque(app: JarvisApp, titre: String, texte: String) {
        val e = etat(app) ?: return
        val qui = appelant(titre, texte) ?: return
        val manques = e.optJSONArray("manques") ?: JSONArray().also { e.put("manques", it) }
        val sms = e.optJSONArray("sms") ?: JSONArray().also { e.put("sms", it) }
        var libelle = qui
        if (app.store.silenceRdvSms.value) runCatching {
            val (nom, numero) = Telephony.resolve(app, qui) ?: return@runCatching
            // Seulement les contacts et clients connus : jamais de SMS à un numéro inconnu (démarchage).
            val connu = if (qui.any(Char::isLetter)) nom else Telephony.identifierAppel(app, numero)
            if (connu.isNullOrBlank() || numero.filter(Char::isDigit).length < 8) return@runCatching
            val cle = numero.filter(Char::isDigit).takeLast(9)
            libelle = connu
            if ((0 until sms.length()).any { sms.optString(it) == cle }) return@runCatching
            val msg = app.store.silenceRdvMessage.value.ifBlank { "Je suis en rendez-vous, je te rappelle dès que possible." }.replace("{fin}", heure(e.optLong("fin")))
            if (Telephony.envoyer(app, numero, msg).startsWith("✅")) { sms.put(cle); libelle = "$connu (SMS envoyé)" }
        }
        if ((0 until manques.length()).none { manques.optString(it).substringBefore(" (") == libelle.substringBefore(" (") }) manques.put(libelle)
        else if (libelle.endsWith("(SMS envoyé)")) for (i in 0 until manques.length()) if (manques.optString(i).substringBefore(" (") == libelle.substringBefore(" (")) manques.put(i, libelle)
        sauver(app, e)
    }

    // ------------------------------------------------------------------ notifications

    private fun notifier(app: JarvisApp, titre: String, texte: String, enCours: Boolean, id: Int = NOTIF) {
        if (Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, "Mode rendez-vous", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val b = NotificationCompat.Builder(app, CANAL).setSmallIcon(android.R.drawable.ic_lock_silent_mode).setContentTitle(titre).setContentText(texte)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte)).setOngoing(enCours).setAutoCancel(!enCours)
            .setContentIntent(PendingIntent.getActivity(app, id, Intent(app, fr.dd.jarvis.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
        if (enCours) b.addAction(0, "🔔 Rétablir le son", PendingIntent.getBroadcast(app, id + 10, intent(app, ACT_ARRET), flags))
        runCatching { NotificationManagerCompat.from(app).notify(id, b.build()) }
    }

    // ------------------------------------------------------------------ à la voix (sans IA)

    sealed interface Intention {
        data class Activer(val tous: Boolean?) : Intention
        object Desactiver : Intention
        data class Sms(val actif: Boolean) : Intention
        data class Message(val texte: String) : Intention
        object Etat : Intention
    }

    private val SUJET = Regex("\\b(mode (?:rendez vous|rdv)|pendant (?:mes |les |un |mes prochains |chaque )?(?:rendez vous|rdv)|reponse automatique|repondeur (?:par )?sms|sms automatiques?)\\b")
    private val FIN_V = Regex("\\b(desactive[rsz]?|coupe[rsz]?|arrete[rsz]?|stoppe|stop|enleve[rsz]?|retire[rsz]?|supprime[rsz]?|plus de|ne (?:\\w+ )?plus|n (?:\\w+ )?plus|pas de)\\b")
    private val ETAT_R = Regex("^(?:est ce que |c est quoi |quel est |ou en est )?(?:le |l )?(?:mode (?:rendez vous|rdv)|reponse automatique)(?: est)? (?:actif|active|activee|en marche|allume|en cours)|^(?:etat|statut) (?:du |de la )?(?:mode (?:rendez vous|rdv)|reponse automatique)")

    fun intention(texte: String): Intention? {
        val o = texte.trim().trimEnd('?', '!', '.', ' ')
        if (o.isBlank() || o.length > 300) return null
        val p = CommandesDirectes.plat(o).replace(Regex("\\s+"), " ").trim().replace(Regex("^(?:jarvis |ok |alors |est ce que tu peux |tu peux |peux tu |s il te plait |stp )+"), "")
        if (!SUJET.containsMatchIn(p)) return null
        Regex("\\b(?:message|texte|sms) (?:de (?:la )?reponse automatique|du sms automatique|du mode (?:rendez vous|rdv)|automatique)\\s*(?::|par|=|sera|devient|est|en)?\\s+").find(CommandesDirectes.plat(o))?.let { m ->
            val t = o.substring(m.range.last + 1).trim().trim('«', '»', '"', ' ')
            if (t.length >= 5) return Intention.Message(t)
        }
        if (ETAT_R.containsMatchIn(p)) return Intention.Etat
        val fin = FIN_V.containsMatchIn(p)
        if (Regex("\\b(reponse automatique|repondeur|sms automatiques?|repond(?:s|re)? (?:par sms|automatiquement)|envoie (?:un )?sms)\\b").containsMatchIn(p)) return Intention.Sms(!fin)
        if (fin) return Intention.Desactiver
        if (!Regex("\\b(active[rsz]?|mets?|met|passe[rsz]?|allume[rsz]?|enclenche[rsz]?|demarre[rsz]?|je veux|automatique(?:ment)?|silencieux|silence|ne pas deranger|coupe le son|mode (?:rendez vous|rdv))\\b").containsMatchIn(p)) return null
        val tous = when {
            Regex("\\b(tous (?:mes|les) (?:rendez vous|rdv|evenements)|chaque (?:rendez vous|rdv|evenement)|n importe quel)\\b").containsMatchIn(p) -> true
            Regex("\\b(clients?|seulement|uniquement|professionnels?|pro)\\b").containsMatchIn(p) -> false
            else -> null
        }
        return Intention.Activer(tous)
    }

    fun texte(app: JarvisApp): String {
        val st = app.store
        if (!st.silenceRdvOn.value) return "Mode rendez-vous désactivé. Dis « active le mode rendez-vous » pour que je coupe le son pendant tes rendez-vous clients."
        val now = System.currentTimeMillis()
        val e = etat(app)
        val prochain = rdvs(app, now, now + 7 * 86_400_000L).firstOrNull { it.begin > now }
        return "🔕 Mode rendez-vous actif (${if (st.silenceRdvTous.value) "tous les rendez-vous" else "rendez-vous clients"})." +
            (e?.let { " En ce moment : « ${it.optString("titre")} » jusqu'à ${heure(it.optLong("fin"))}." }.orEmpty()) +
            (prochain?.let { " Prochain : « ${it.title} » ${fr.dd.jarvis.ai.DatesFr.libelle(Instant.ofEpochMilli(it.begin).atZone(ZoneId.systemDefault()).toLocalDate(), java.time.LocalDate.now())} à ${heure(it.begin)}." } ?: " Aucun rendez-vous concerné dans les 7 jours.") +
            (if (st.silenceRdvSms.value) " Réponse automatique par SMS : « ${st.silenceRdvMessage.value} »." else " Réponse automatique par SMS : désactivée.") +
            (if (!accesNpd(app)) " ⚠️ Il manque l'accès « Ne pas déranger » pour JARVIS." else "")
    }

    fun executer(app: JarvisApp, i: Intention): String {
        val st = app.store
        val r = when (i) {
            is Intention.Activer -> {
                st.silenceRdvOn.set(true); i.tous?.let { st.silenceRdvTous.set(it) }
                planifier(app)
                texte(app) + (if (!st.silenceRdvSms.value) " Je peux aussi répondre par SMS aux contacts qui t'appellent : dis « active la réponse automatique »." else "")
            }
            Intention.Desactiver -> { st.silenceRdvOn.set(false); planifier(app); "🔔 Mode rendez-vous désactivé : je ne touche plus au son pendant tes rendez-vous." }
            is Intention.Sms -> {
                st.silenceRdvSms.set(i.actif)
                if (i.actif) { st.silenceRdvOn.set(true); planifier(app) }
                if (i.actif) "✉️ Réponse automatique activée : pendant un rendez-vous, les contacts qui t'appellent reçoivent « ${st.silenceRdvMessage.value.replace("{fin}", "15 h 30")} » (une seule fois, jamais aux numéros inconnus)."
                else "Réponse automatique par SMS désactivée."
            }
            is Intention.Message -> { st.silenceRdvMessage.set(i.texte); "✅ Message de réponse automatique : « ${i.texte} »." + (if (!st.silenceRdvSms.value) " (Dis « active la réponse automatique » pour l'envoyer.)" else "") }
            Intention.Etat -> texte(app)
        }
        if (st.silenceRdvOn.value && !accesNpd(app)) runCatching {
            app.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return r
    }
}

class SilenceRdvReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? JarvisApp ?: return
        runCatching {
            when (intent.action) {
                SilenceRdv.ACT_DEBUT -> SilenceRdv.debut(app, intent.getLongExtra("id", 0), intent.getStringExtra("titre").orEmpty(), intent.getLongExtra("fin", 0))
                SilenceRdv.ACT_FIN -> SilenceRdv.fin(app, intent.getLongExtra("id", 0))
                SilenceRdv.ACT_ARRET -> SilenceRdv.fin(app, null, manuel = true)
            }
        }
    }
}
