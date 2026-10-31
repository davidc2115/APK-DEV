package fr.dd.jarvis.work

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.MailTri
import fr.dd.jarvis.data.Store
import fr.dd.jarvis.device.Locator
import fr.dd.jarvis.service.JarvisNotificationListener
import fr.dd.jarvis.societe.Geo
import fr.dd.jarvis.societe.core.euro
import fr.dd.jarvis.societe.core.dateFr
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object AgentsScheduler {
    private const val NAME = "agents"
    fun apply(ctx: Context, store: Store) {
        val wm = WorkManager.getInstance(ctx)
        if (!store.agentsOn.value) { wm.cancelUniqueWork(NAME); return }
        val req = PeriodicWorkRequestBuilder<AgentsWorker>(store.agentMinutes.value.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
    private var dernier = HashMap<String, Long>()

    /** Passage immédiat d'un agent (« mails » ou « notifs ») à l'arrivée d'une notification, au plus une fois par minute et par agent. */
    @Synchronized fun declencher(ctx: Context, quoi: String) {
        val now = System.currentTimeMillis()
        if (now - (dernier[quoi] ?: 0L) < 25_000L) return
        dernier[quoi] = now
        val req = OneTimeWorkRequestBuilder<AgentsWorker>().setInputData(workDataOf("only" to quoi))
            .setInitialDelay(if (quoi == "mails") 20 else 3, TimeUnit.SECONDS)   // laisse Gmail synchroniser avant de lire la boîte
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("agents_live_$quoi", ExistingWorkPolicy.KEEP, req)
    }

    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<AgentsWorker>().setInputData(workDataOf("force" to true)).build())
    }
}

/**
 * Agents autonomes (en arrière-plan, sans action de ta part) : mails, factures/devis, trajet avant rendez-vous, notifications.
 * Chaque passage écrit un journal dans Obsidian (JARVIS/Journal) et envoie une alerte quand c'est utile.
 */
class AgentsWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as JarvisApp
    private val store get() = app.store
    private val journal = StringBuilder()

    private fun seenFile(n: String) = File(ctx.filesDir, n)
    private fun readSet(n: String): MutableSet<String> = runCatching { seenFile(n).readLines().toMutableSet() }.getOrDefault(mutableSetOf())
    private fun writeSet(n: String, s: Set<String>) = runCatching { seenFile(n).writeText(s.toList().takeLast(600).joinToString("\n")) }

    private fun alert(id: Int, titre: String, texte: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(ctx, id, Intent(ctx, fr.dd.jarvis.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, JarvisApp.CHANNEL_AGENTS).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(titre).setContentText(texte.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte.take(3500))).setContentIntent(pi).setAutoCancel(true).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }

    override suspend fun doWork(): Result {
        val only = inputData.getString("only")
        val tri = only == "mails" && store.notifLive.value
        if (!store.agentsOn.value && !tri) return Result.success()
        val steps = listOf<Pair<String, suspend () -> Unit>>(
            "Mails" to { if (tri || (store.agentMails.value && (only == null || only == "mails"))) mails() },
            "Factures" to { if (store.agentFactures.value && only == null) factures() },
            "Planning" to { if (store.agentPlanning.value && only == null) planning() },
            "Notifications" to { if (store.agentNotifs.value && (only == null || only == "notifs")) notifs() }
        )
        for ((nom, f) in steps) {
            try { f() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { journal.append("- ⚠️ $nom : ${e.message?.take(120)}\n") }
        }
        if (journal.isNotBlank()) {
            runCatching {
                val now = java.time.LocalDateTime.now()
                app.obsidian.writeNote("JARVIS/Journal/${LocalDate.now()}.md", "## ${now.format(DateTimeFormatter.ofPattern("HH:mm"))}\n$journal", true)
            }
        }
        return Result.success()
    }

    // ---------------------------------------------------------------- mails
    private suspend fun mails() {
        if (!store.mailConfigured()) return
        val unread = app.mail.fetchRecent(40, true, null, full = true)
        val seen = readSet("agent_seen_mails.txt")
        fun key(m: fr.dd.jarvis.device.MailItem) = if (m.id.isNotBlank()) m.account + m.id else (m.from + m.subject).hashCode().toString()
        val nouveaux = unread.filter { key(it) !in seen }
        if (nouveaux.isEmpty()) return
        val classes = MailTri.classer(app, nouveaux)
        val urgents = classes.filter { it.cat == "Urgent" || it.prio == 1 }
        journal.append("- 📧 ${nouveaux.size} nouveau(x) mail(s) : " + classes.groupBy { it.cat }.entries.joinToString(", ") { "${it.value.size} ${it.key}" } + "\n")
        if (urgents.isNotEmpty()) {
            val txt = urgents.joinToString("\n") { "• ${it.mail.from} — ${it.mail.subject}" + (if (it.action.isNotBlank()) " → ${it.action}" else "") }
            alert(2001, "📧 ${urgents.size} mail(s) urgent(s)", txt)
            journal.append(urgents.joinToString("") { "  - 🔴 ${it.mail.from} — ${it.mail.subject}\n" })
        }
        if (store.agentRanger.value && store.gmailAccounts.isNotEmpty()) {
            runCatching { MailTri.ranger(app, classes, store.agentArchiver.value) }.onSuccess {
                journal.append("  - 🏷️ $it mail(s) rangés (libellés Urgent, Clients…)\n")
                if (store.notifLive.value) alert(2004, "🏷️ ${classes.size} mail(s) rangé(s) dans Gmail",
                    classes.joinToString("\n") { "• ${it.label} — ${it.mail.from} : ${it.mail.subject}" })
            }.onFailure { journal.append("  - ⚠️ Rangement Gmail : ${it.message?.take(100)}\n") }
        }
        writeSet("agent_seen_mails.txt", seen + nouveaux.map { key(it) })
    }

    // ---------------------------------------------------------------- factures et devis
    private fun factures() {
        val today = LocalDate.now().toString()
        if (store.agentLastFact.value == today && !inputData.getBoolean("force", false)) return
        val d = app.societe.data
        val retard = d.docs.filter { it.type == "facture" && it.statut == "a_payer" && it.echeance.isNotBlank() && it.echeance < today }
        val devis = d.docs.filter { it.type == "devis" && it.statut == "envoye" && it.date <= LocalDate.now().minusDays(10).toString() }
        store.agentLastFact.set(today)
        if (retard.isEmpty() && devis.isEmpty()) return
        val sb = StringBuilder()
        if (retard.isNotEmpty()) {
            sb.append("Factures en retard (${euro(retard.sumOf { d.totaux(it).ttc })}) :\n")
            retard.forEach { sb.append("• ${it.numero} · ${d.client(it.clientId)?.nom.orEmpty()} · ${euro(d.totaux(it).ttc)} · échue le ${dateFr(it.echeance)}\n") }
        }
        if (devis.isNotEmpty()) {
            sb.append("Devis sans réponse depuis plus de 10 jours :\n")
            devis.forEach { sb.append("• ${it.numero} · ${d.client(it.clientId)?.nom.orEmpty()} · ${euro(d.totaux(it).ttc)}\n") }
        }
        alert(2002, "💶 Relances à faire", sb.toString())
        journal.append("- 💶 ${retard.size} facture(s) en retard, ${devis.size} devis à relancer\n")
    }

    // ---------------------------------------------------------------- planning : heure de départ
    private suspend fun planning() {
        if (!app.calendar.hasPermission()) return
        val now = System.currentTimeMillis()
        val horizon = now + 150 * 60_000L
        val notified = readSet("agent_notified_events.txt")
        val evs = app.calendar.events(LocalDate.now(), 1).filter { !it.allDay && it.location.isNotBlank() && it.begin in now..horizon && "${it.id}_${it.begin}" !in notified }
        if (evs.isEmpty()) return
        val origine = Locator.point(app) ?: app.societe.data.societe.let { s -> if (s.lat != null && s.lon != null) Geo.Point(s.lat, s.lon) else null } ?: return
        for (e in evs) {
            val lieu = e.location.lines().first().trim()
            val p = Geo.geocode(lieu) ?: continue
            val r = Geo.route(origine, p) ?: continue
            val partir = e.begin - (Math.round(r.minutes) + 10) * 60_000L
            if (partir - now > (store.agentMinutes.value + 10) * 60_000L) continue // pas encore l'heure de prévenir
            val h = Instant.ofEpochMilli(partir).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            alert(3000 + (e.id % 900).toInt(), "🚗 Partir à $h", "${e.title}\n📍 $lieu\n${Math.round(r.km)} km · ${Math.round(r.minutes)} min de route (marge 10 min)")
            journal.append("- 🚗 Départ ${h} pour « ${e.title} » (${Math.round(r.minutes)} min)\n")
            writeSet("agent_notified_events.txt", notified + "${e.id}_${e.begin}")
        }
    }

    // ---------------------------------------------------------------- notifications du téléphone
    private suspend fun notifs() {
        JarvisNotificationListener.recharger(ctx)
        val last = store.agentLastNotif.value.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        store.agentLastNotif.set(now.toString())
        if (last == 0L) return
        val l = JarvisNotificationListener.snapshot().filter { it.time > last }.take(30)
        if (l.isEmpty()) return
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRENCH)
        val rep = app.llm.agent(
            "Tu es l'agent Notifications de JARVIS (artisan, maintenance photovoltaïque). On te donne des notifications du téléphone. Garde uniquement celles qui demandent une attention rapide : clients, devis, factures, paiements, chantiers, rendez-vous, banque, proches. Ignore pubs, réseaux sociaux, jeux. Réponds une ligne par notification retenue au format « application — résumé — action suggérée », ou uniquement RIEN.",
            l.joinToString("\n") { "${fmt.format(java.util.Date(it.time))} | ${it.app} | ${it.titre} | ${it.texte.take(200)}" }
        ).trim()
        if (rep.isBlank() || rep.uppercase().startsWith("RIEN")) return
        alert(2003, "🔔 Notifications importantes", rep)
        journal.append("- 🔔 Notifications importantes :\n" + rep.lines().joinToString("") { "  - $it\n" })
    }
}
