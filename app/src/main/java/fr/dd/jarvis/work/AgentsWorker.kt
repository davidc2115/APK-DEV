package fr.dd.jarvis.work

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.MailTri
import fr.dd.jarvis.data.Store
import fr.dd.jarvis.device.Locator
import fr.dd.jarvis.device.PhoneHealth
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
        // Le centre de surveillance conserve son historique même lorsque les agents IA sont suspendus.
        val mode = store.modeEnergie.value
        val healthMinutes = when (mode) { "performance" -> 15L; "eco" -> 90L; else -> 30L }
        val healthReq = PeriodicWorkRequestBuilder<PhoneHealthWorker>(healthMinutes, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork("phone_health", ExistingPeriodicWorkPolicy.UPDATE, healthReq)
        if (!store.agentsOn.value) { wm.cancelUniqueWork(NAME); return }
        val agentMinutes = when (mode) {
            "performance" -> 15L
            "eco" -> maxOf(60L, store.agentMinutes.value.toLong())
            else -> store.agentMinutes.value.coerceAtLeast(15).toLong()
        }
        val req = PeriodicWorkRequestBuilder<AgentsWorker>(agentMinutes, TimeUnit.MINUTES).build()
        // Les contrôles locaux (batterie, stockage, tâches, factures) doivent aussi tourner hors connexion.
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

/** Collecte indépendante des agents IA : le graphique du centre de surveillance continue même si les agents sont suspendus. */
class PhoneHealthWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = runCatching {
        PhoneHealth.recordSample(ctx.applicationContext as JarvisApp)
        Result.success()
    }.getOrElse { Result.retry() }
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
    private fun reseauDisponible(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun readSet(n: String): MutableSet<String> = runCatching { seenFile(n).readLines().toMutableSet() }.getOrDefault(mutableSetOf())
    private fun writeSet(n: String, s: Set<String>) = runCatching { seenFile(n).writeText(s.toList().takeLast(600).joinToString("\n")) }

    private fun alert(id: Int, titre: String, texte: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            journal.append("- ⚠️ Alerte « $titre » non publiée : autorisation de notifications Android absente.\n")
            return false
        }
        val pi = PendingIntent.getActivity(ctx, id, Intent(ctx, fr.dd.jarvis.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, JarvisApp.CHANNEL_AGENTS).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(titre).setContentText(texte.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte.take(3500))).setContentIntent(pi).setAutoCancel(true).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        return true
    }

    override suspend fun doWork(): Result {
        val only = inputData.getString("only")
        val tri = false // plus de tri déclenché par les bannières Jarvis (supprimées) : seuls les agents activés tournent
        if (!store.agentsOn.value) return Result.success()
        val enLigne = reseauDisponible()
        // Le centre de surveillance collecte ses propres mesures : ce n’est plus un agent spécialisé.
        PhoneHealth.recordSample(app)
        val steps = listOf<Pair<String, suspend () -> Unit>>(
            "Mails" to {
                if (tri || (store.agentMails.value && (only == null || only == "mails"))) {
                    if (enLigne) mails() else journal.append("- ⏸️ Mails : reportés, connexion Internet indisponible.\n")
                }
            },
            "Factures" to { if (store.agentFactures.value && only == null) factures() },
            "Planning" to { if (store.agentPlanning.value && only == null) planning() },
            "Tâches personnelles" to { if (store.agentPlanning.value && only == null) taches() },
            "Notifications" to {
                if (store.agentNotifs.value && (only == null || only == "notifs")) {
                    if (enLigne) notifs() else journal.append("- ⏸️ Analyse des notifications : reportée, connexion Internet indisponible.\n")
                }
            },
        )
        var failures = 0
        val outcomes = mutableListOf<String>()
        for ((nom, f) in steps) {
            val journalStart = journal.length
            val active = when (nom) {
                "Mails" -> tri || (store.agentMails.value && (only == null || only == "mails"))
                "Factures" -> store.agentFactures.value && only == null
                "Planning", "Tâches personnelles" -> store.agentPlanning.value && only == null
                "Notifications" -> store.agentNotifs.value && (only == null || only == "notifs")
                else -> false
            }
            val prerequisite = when (nom) {
                "Mails" -> if (active && !store.mailConfigured()) "configuration mail manquante" else null
                "Planning" -> if (active && !app.calendar.hasPermission()) "autorisation agenda manquante" else null
                "Notifications" -> if (active && !PhoneHealth.dashboard(app).notificationListener) "accès aux notifications du téléphone non autorisé" else null
                else -> null
            }
            try {
                when {
                    !active -> Unit
                    prerequisite != null -> journal.append("- ⏸️ $nom : bloqué — $prerequisite.\n")
                    else -> f()
                }
                outcomes += "$nom : " + when {
                    !active -> "non exécuté (agent désactivé ou hors de ce cycle)"
                    prerequisite != null -> "bloqué — $prerequisite"
                    journal.length > journalStart -> "activité / report"
                    else -> "RAS"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures++
                outcomes += "$nom : erreur"
                journal.append("- ⚠️ $nom : ${e.message?.take(120)}\n")
            }
        }
        val finishedAt = System.currentTimeMillis()
        val status = if (failures == 0) "Cycle terminé sans erreur" else "Cycle partiel : $failures étape(s) en erreur"
        store.agentLastRunAt.set(finishedAt.toString())
        store.agentLastRunStatus.set(status)
        val horodatage = java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(finishedAt), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        val historyLine = "$horodatage · $status · ${outcomes.joinToString(" | ")}"
        val history = (store.agentRunHistory.value.lines().filter { it.isNotBlank() } + historyLine).takeLast(20)
        store.agentRunHistory.set(history.joinToString("\n"))
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
        var peutMarquerCommeTraite = true
        if (urgents.isNotEmpty()) {
            val txt = urgents.joinToString("\n") { "• ${it.mail.from} — ${it.mail.subject}" + (if (it.action.isNotBlank()) " → ${it.action}" else "") }
            if (!alert(2001, "📧 ${urgents.size} mail(s) urgent(s)", txt)) peutMarquerCommeTraite = false
            journal.append(urgents.joinToString("") { "  - 🔴 ${it.mail.from} — ${it.mail.subject}\n" })
        }
        if (store.agentRanger.value && store.gmailAccounts.isNotEmpty()) {
            runCatching { MailTri.ranger(app, classes, store.agentArchiver.value) }.onSuccess {
                journal.append("  - 🏷️ $it mail(s) rangés (libellés Urgent, Clients…)\n")
                // (pas de notification Jarvis par mail rangé : le détail reste dans le journal)
            }.onFailure { throw it } // Remonter l’échec : les mails restent non traités et seront réessayés au prochain cycle.
        }
        if (peutMarquerCommeTraite) writeSet("agent_seen_mails.txt", seen + nouveaux.map { key(it) })
        else journal.append("  - ⏳ Mails conservés pour une nouvelle tentative : alerte urgente non délivrée.\n")
    }

    // ---------------------------------------------------------------- factures et devis
    private fun factures() {
        val today = LocalDate.now().toString()
        if (store.agentLastFact.value == today && !inputData.getBoolean("force", false)) return
        // Relire la base persistée : les créations/modifications réalisées depuis un autre écran
        // ou par Hermès peuvent être plus récentes que l'instantané déjà chargé en mémoire.
        val d = app.societe.repo.refresh()
        val retard = d.docs.filter { it.type == "facture" && it.statut == "a_payer" && it.echeance.isNotBlank() && it.echeance < today }
        val bientotEchues = d.docs.filter { it.type == "facture" && it.statut == "a_payer" && it.echeance.isNotBlank() && it.echeance >= today && it.echeance <= LocalDate.now().plusDays(3).toString() }
        val devis = d.docs.filter { it.type == "devis" && it.statut == "envoye" && it.date <= LocalDate.now().minusDays(10).toString() }
        val maintenance = d.clients.flatMap { client ->
            client.sites.flatMap { site ->
                site.interventions.mapNotNull { intervention ->
                    val date = runCatching { LocalDate.parse(intervention.prochaineMaintenance) }.getOrNull()
                    if (date != null && !date.isAfter(LocalDate.now().plusDays(14))) Triple(client, site, intervention) else null
                }
            }
        }.sortedBy { it.third.prochaineMaintenance }
        if (retard.isEmpty() && bientotEchues.isEmpty() && devis.isEmpty() && maintenance.isEmpty()) {
            store.agentLastFact.set(today)
            return
        }
        val sb = StringBuilder()
        if (retard.isNotEmpty()) {
            sb.append("Factures en retard (${euro(retard.sumOf { d.totaux(it).ttc })}) :\n")
            retard.forEach { sb.append("• ${it.numero} · ${d.client(it.clientId)?.nom.orEmpty()} · ${euro(d.totaux(it).ttc)} · échue le ${dateFr(it.echeance)}\n") }
        }
        if (bientotEchues.isNotEmpty()) {
            sb.append("Factures à échéance dans les 3 jours :\n")
            bientotEchues.forEach { sb.append("• ${it.numero} · ${d.client(it.clientId)?.nom.orEmpty()} · ${euro(d.totaux(it).ttc)} · échéance le ${dateFr(it.echeance)}\n") }
        }
        if (devis.isNotEmpty()) {
            sb.append("Devis sans réponse depuis plus de 10 jours :\n")
            devis.forEach { sb.append("• " + it.numero + " · " + (d.client(it.clientId)?.nom.orEmpty()) + " · " + euro(d.totaux(it).ttc) + "\n") }
        }
        if (maintenance.isNotEmpty()) {
            sb.append("Maintenances photovoltaïques à prévoir (retard ou dans les 14 jours) :\n")
            maintenance.forEach { (client, site, intervention) ->
                val date = intervention.prochaineMaintenance
                val retardTxt = if (date < today) " — EN RETARD" else ""
                sb.append("• " + client.nom + " · " + site.nom + " — " + dateFr(date) + retardTxt + "\n")
            }
        }
        if (alert(2002, "💶 Suivi entreprise et maintenances", sb.toString())) store.agentLastFact.set(today)
        journal.append("- 💶 " + retard.size + " facture(s) en retard, " + bientotEchues.size + " échéance(s) proche(s), " + devis.size + " devis à relancer, " + maintenance.size + " maintenance(s) à prévoir\n")
    }

    // ---------------------------------------------------------------- planning : heure de départ
    private suspend fun planning() {
        if (!app.calendar.hasPermission()) return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val semaine = app.calendar.events(LocalDate.now(), 7).filter { !it.allDay }.sortedBy { it.begin }

        // Détecte les chevauchements réels sur les sept prochains jours, sans modifier le calendrier.
        val conflits = buildList {
            for (i in semaine.indices) {
                val a = semaine[i]
                for (j in i + 1 until semaine.size) {
                    val b = semaine[j]
                    if (b.begin >= a.end) break
                    if (a.id != b.id && b.begin < a.end && a.begin < b.end) add(a to b)
                }
            }
        }
        val conflictKeys = conflits.map { (a, b) -> listOf("${a.id}_${a.begin}", "${b.id}_${b.begin}").sorted().joinToString("__") }
        val seenConflicts = readSet("agent_seen_conflicts.txt")
        val nouveauxConflits = conflits.filterIndexed { index, _ -> conflictKeys[index] !in seenConflicts }
        if (nouveauxConflits.isNotEmpty()) {
            val details = nouveauxConflits.take(8).joinToString("\n") { (a, b) ->
                val startA = Instant.ofEpochMilli(a.begin).atZone(zone).format(fmt)
                val startB = Instant.ofEpochMilli(b.begin).atZone(zone).format(fmt)
                "• ${a.title} ($startA) chevauche ${b.title} ($startB)"
            }
            if (alert(3010, "⚠️ Conflit dans ton planning", details)) {
                writeSet("agent_seen_conflicts.txt", seenConflicts + conflictKeys)
                journal.append("- ⚠️ ${nouveauxConflits.size} conflit(s) d'agenda détecté(s) sur les 7 prochains jours\n")
            } else journal.append("- ⏳ Conflits conservés pour une nouvelle alerte : notification non délivrée.\n")
        } else writeSet("agent_seen_conflicts.txt", seenConflicts + conflictKeys)

        val prochain = semaine.firstOrNull { it.end >= now }
        val synthese = when {
            conflits.isNotEmpty() -> "⚠️ ${conflits.size} conflit(s) d'agenda à vérifier sur les 7 prochains jours."
            prochain != null -> "Prochain rendez-vous : ${prochain.title} à ${Instant.ofEpochMilli(prochain.begin).atZone(zone).format(fmt)}."
            else -> "Aucun rendez-vous à venir dans les 7 prochains jours."
        }
        store.proactiveSummary.set(synthese)

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
            if (alert(3000 + (e.id % 900).toInt(), "🚗 Partir à $h", "${e.title}\n📍 $lieu\n${Math.round(r.km)} km · ${Math.round(r.minutes)} min de route (marge 10 min)")) {
                journal.append("- 🚗 Départ ${h} pour « ${e.title} » (${Math.round(r.minutes)} min)\n")
                writeSet("agent_notified_events.txt", notified + "${e.id}_${e.begin}")
            }
        }
    }

    // ---------------------------------------------------------------- tâches personnelles
    private fun taches() {
        val due = app.tasks.pendingDue(LocalDate.now().plusDays(1))
        if (due.isEmpty()) return
        val seen = readSet("agent_seen_tasks.txt")
        val fresh = due.filter { (it.id + "_" + it.dueDate) !in seen }
        if (fresh.isEmpty()) return
        val today = LocalDate.now().toString()
        val details = fresh.joinToString("\n") { task ->
            val late = task.dueDate < today
            (if (late) "🔴 EN RETARD — " else if (task.dueDate == today) "🟠 AUJOURD'HUI — " else "🟡 DEMAIN — ") +
                task.title + (if (task.dueDate.isNotBlank()) " (" + dateFr(task.dueDate) + ")" else "") +
                (if (task.priority == "urgente" || task.priority == "haute") " · priorité " + task.priority else "")
        }
        if (alert(3012, "📝 Tâches à ne pas oublier", details)) {
            journal.append("- 📝 " + fresh.size + " tâche(s) personnelle(s) en retard ou à échéance sous 24 h\n")
            writeSet("agent_seen_tasks.txt", seen + fresh.map { it.id + "_" + it.dueDate })
        } else journal.append("- ⏳ Tâches conservées pour une nouvelle alerte : notification non délivrée.\n")
    }

    // ---------------------------------------------------------------- notifications du téléphone
    private suspend fun notifs() {
        JarvisNotificationListener.recharger(ctx)
        val last = store.agentLastNotif.value.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (last == 0L) {
            store.agentLastNotif.set(now.toString()) // premier passage : établir le point de départ sans retraiter l'ancien historique
            return
        }
        val l = JarvisNotificationListener.snapshot().filter { it.time > last }.take(30)
        if (l.isEmpty()) {
            store.agentLastNotif.set(now.toString())
            return
        }
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRENCH)
        val rep = app.llm.agent(
            "Tu es l'agent Notifications de JARVIS (artisan, maintenance photovoltaïque). On te donne des notifications du téléphone. Garde uniquement celles qui demandent une attention rapide : clients, devis, factures, paiements, chantiers, rendez-vous, banque, proches. Ignore pubs, réseaux sociaux, jeux. Réponds une ligne par notification retenue au format « application — résumé — action suggérée », ou uniquement RIEN.",
            l.joinToString("\n") { "${fmt.format(java.util.Date(it.time))} | ${it.app} | ${it.titre} | ${it.texte.take(200)}" }
        ).trim()
        // N'avancer que jusqu'au dernier élément effectivement analysé : si plus de 30
        // notifications attendent, les suivantes seront traitées au cycle suivant.
        val analyzedThrough = l.maxOf { it.time }
        if (rep.isBlank() || rep.uppercase().startsWith("RIEN")) {
            store.agentLastNotif.set(analyzedThrough.toString())
            return
        }
        // Si Android bloque la publication, conserver le curseur : l'alerte sera retentée
        // au prochain cycle au lieu d'être silencieusement considérée comme livrée.
        if (!alert(2003, "🔔 Notifications importantes", rep)) {
            journal.append("- ⏸️ Notifications importantes analysées mais non publiées ; elles seront retentées.\n")
            return
        }
        store.agentLastNotif.set(analyzedThrough.toString())
        journal.append("- 🔔 Notifications importantes :\n" + rep.lines().joinToString("") { "  - $it\n" })
    }
}
