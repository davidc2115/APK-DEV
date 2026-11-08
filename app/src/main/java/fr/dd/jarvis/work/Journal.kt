package fr.dd.jarvis.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * Journal de bord automatique : chaque soir (21 h par défaut), une note Obsidian « Journal/AAAA-MM-JJ » avec les
 * rendez-vous, les interventions, l'activité de la société (devis, factures, paiements), les kilomètres estimés et ce
 * que tu as demandé à JARVIS, plus un court résumé. Le dimanche, un bilan de la semaine (« Journal/Semaine … »).
 */
object Journal {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val jourLong = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
    private fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)
    private fun euros(v: Double) = String.format(Locale.FRENCH, "%,.2f €", v)

    /** Appelée par la veille : écrit le journal du jour une fois l'heure passée (et le bilan le dimanche). */
    suspend fun siLHeure(app: JarvisApp) {
        if (!app.store.journalAuto.value) return
        val heure = app.store.journalHeure.value.toIntOrNull()?.coerceIn(0, 23) ?: 21
        if (java.time.LocalTime.now().hour < heure) return
        val jour = LocalDate.now()
        if (app.store.journalFait.value == jour.toString()) return
        app.store.journalFait.set(jour.toString())
        val (chemin, _) = ecrire(app, jour)
        var texte = "Ta journée est notée dans Obsidian : $chemin"
        if (jour.dayOfWeek == DayOfWeek.SUNDAY) texte += "\nBilan de la semaine : " + bilanSemaine(app, jour).first
        notifier(app, "📓 Journal de bord du jour", texte)
    }

    /** Statistiques d'un jour, sans IA (testables séparément). */
    data class Stats(val rdv: List<String>, val interventions: List<String>, val devis: Int, val devisHt: Double, val factures: Int,
                     val facturesTtc: Double, val paiements: Int, val encaisse: Double, val km: Double, val minutes: Double, val demandes: List<String>)

    suspend fun stats(app: JarvisApp, jour: LocalDate): Stats = withContext(Dispatchers.IO) {
        val evts = runCatching { app.calendar.events(jour, 1) }.getOrDefault(emptyList()).sortedBy { it.begin }
        val rdv = evts.map { e -> (if (e.allDay) "Journée" else "${hhmm(e.begin)}–${hhmm(e.end)}") + " · ${e.title.ifBlank { "(sans titre)" }}" + (if (e.location.isNotBlank()) " — 📍 ${e.location.take(70)}" else "") }
        val data = app.societe.data
        val j = jour.toString()
        val interventions = data.clients.flatMap { c -> c.sites.flatMap { s -> s.interventions.filter { it.date.startsWith(j) }.map { i ->
            "${c.nom}" + (if (c.sites.size > 1) " · ${s.nom}" else "") + " — ${i.categorie} : ${i.description.take(90)}" + (if (i.statut != "terminee") " (${i.statut})" else "")
        } } }
        val docs = data.docs
        val devis = docs.filter { it.type == "devis" && it.date == j }
        val factures = docs.filter { it.type == "facture" && it.date == j }
        val payees = docs.filter { it.type == "facture" && it.payeLe.startsWith(j) }
        // Kilomètres estimés : siège → rendez-vous (dans l'ordre) → siège.
        val hq = data.societe
        var km = 0.0; var min = 0.0
        val points = evts.filter { !it.allDay && it.location.isNotBlank() }.take(8).mapNotNull { runCatching { Geo.geocode(it.location) }.getOrNull() }
        if (points.isNotEmpty() && hq.lat != null && hq.lon != null) {
            val etapes = listOf(Geo.Point(hq.lat, hq.lon)) + points + Geo.Point(hq.lat, hq.lon)
            for (k in 0 until etapes.size - 1) runCatching { Geo.route(etapes[k], etapes[k + 1]) }.getOrNull()?.let { km += it.km; min += it.minutes }
        }
        val debut = jour.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); val fin = debut + 86_400_000L
        app.chatCharger()
        val demandes = app.chat.toList().filter { it.fromUser && it.ts in debut until fin }.map { it.text.replace(Regex("\\s+"), " ").take(140) }
        Stats(rdv, interventions, devis.size, devis.sumOf { data.totaux(it).ht }, factures.size, factures.sumOf { data.totaux(it).ttc },
            payees.size, payees.sumOf { data.totaux(it).ttc }, km, min, demandes)
    }

    /** Partie fixe de la note (sans IA). */
    internal fun markdown(jour: LocalDate, s: Stats): String = buildString {
        append("# 📓 Journal du ${jour.format(jourLong)}\n\n")
        append("## 📅 Rendez-vous (${s.rdv.size})\n")
        append(if (s.rdv.isEmpty()) "- Aucun\n" else s.rdv.joinToString("\n") { "- $it" } + "\n")
        append("\n## 👷 Interventions (${s.interventions.size})\n")
        append(if (s.interventions.isEmpty()) "- Aucune enregistrée\n" else s.interventions.joinToString("\n") { "- $it" } + "\n")
        append("\n## 💶 Société\n")
        append("- Devis créés : ${s.devis}" + (if (s.devis > 0) " (${euros(s.devisHt)} HT)" else "") + "\n")
        append("- Factures émises : ${s.factures}" + (if (s.factures > 0) " (${euros(s.facturesTtc)} TTC)" else "") + "\n")
        append("- Paiements reçus : ${s.paiements}" + (if (s.paiements > 0) " (${euros(s.encaisse)})" else "") + "\n")
        append("\n## 🚗 Route\n")
        append(if (s.km > 0) "- ≈ ${Math.round(s.km)} km, ≈ ${Math.round(s.minutes)} min de conduite (estimation siège → rendez-vous → siège)\n" else "- Pas de déplacement prévu à l'agenda\n")
        append("\n## 💬 Avec JARVIS\n- ${s.demandes.size} demande(s)\n")
    }

    /** Écrit la note du jour ; renvoie (chemin, contenu). */
    suspend fun ecrire(app: JarvisApp, jour: LocalDate): Pair<String, String> {
        val s = stats(app, jour)
        var md = markdown(jour, s)
        // Résumé par l'IA (facultatif : en cas d'échec, la note reste complète sans lui).
        val resume = runCatching {
            app.llm.agent(
                "Tu es JARVIS. À partir du journal de la journée et des demandes faites à JARVIS, écris en français : " +
                    "« ## 🗒️ Ce que tu m'as demandé » (3 puces au plus, sujets regroupés) puis « ## 📝 En bref » (2 phrases : bilan et point à retenir pour demain). Rien d'autre, n'invente rien.",
                md + "\nDemandes du jour :\n" + s.demandes.takeLast(30).joinToString("\n") { "- $it" })
        }.getOrNull()?.trim().orEmpty()
        if (resume.isNotBlank()) md += "\n" + resume + "\n"
        val chemin = "Journal/$jour.md"
        withContext(Dispatchers.IO) { app.obsidian.writeNote(chemin, md, false) }
        return chemin to md
    }

    /** Bilan de la semaine (lundi → [fin]) ; renvoie (chemin, contenu). */
    suspend fun bilanSemaine(app: JarvisApp, fin: LocalDate): Pair<String, String> {
        val debut = fin.with(DayOfWeek.MONDAY)
        val jours = generateSequence(debut) { it.plusDays(1) }.takeWhile { !it.isAfter(fin) }.toList()
        val stats = jours.map { it to stats(app, it) }
        val semaine = fin.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val notes = withContext(Dispatchers.IO) { jours.mapNotNull { j -> runCatching { app.obsidian.readNote("Journal/$j.md") }.getOrNull()?.take(1500) } }
        val prochaine = runCatching { app.calendar.events(fin.plusDays(1), 7) }.getOrDefault(emptyList()).sortedBy { it.begin }
        var md = buildString {
            append("# 📊 Bilan de la semaine $semaine (${debut.format(DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH))} → ${fin.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRENCH))})\n\n")
            append("- 📅 Rendez-vous : ${stats.sumOf { it.second.rdv.size }}\n")
            append("- 👷 Interventions : ${stats.sumOf { it.second.interventions.size }}\n")
            append("- 💶 Devis : ${stats.sumOf { it.second.devis }} (${euros(stats.sumOf { it.second.devisHt })} HT) · Factures : ${stats.sumOf { it.second.factures }} (${euros(stats.sumOf { it.second.facturesTtc })} TTC) · Encaissé : ${euros(stats.sumOf { it.second.encaisse })}\n")
            append("- 🚗 ≈ ${Math.round(stats.sumOf { it.second.km })} km estimés\n")
            append("- 💬 ${stats.sumOf { it.second.demandes.size }} demandes à JARVIS\n")
            append("\n## 🔭 Semaine prochaine (${prochaine.size} rendez-vous)\n")
            append(prochaine.take(10).joinToString("\n") { "- ${Instant.ofEpochMilli(it.begin).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d HH:mm", Locale.FRENCH))} · ${it.title}" }.ifBlank { "- Rien de prévu" } + "\n")
        }
        val resume = runCatching {
            app.llm.agent("Tu es JARVIS. Écris en français « ## 📝 L'essentiel de la semaine » (4 puces : réalisations, points d'attention, chiffres marquants, conseil pour la semaine prochaine). N'invente rien.",
                md + "\n\nJournaux de la semaine :\n" + notes.joinToString("\n---\n"))
        }.getOrNull()?.trim().orEmpty()
        if (resume.isNotBlank()) md += "\n" + resume + "\n"
        val chemin = "Journal/Semaine $semaine (${fin.year}).md"
        withContext(Dispatchers.IO) { app.obsidian.writeNote(chemin, md, false) }
        return chemin to md
    }

    private fun notifier(app: JarvisApp, titre: String, texte: String) {
        val canal = "journal_jarvis"
        if (Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(canal, "Journal de bord", NotificationManager.IMPORTANCE_LOW))
        runCatching {
            NotificationManagerCompat.from(app).notify(7750, NotificationCompat.Builder(app, canal).setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle(titre).setContentText(texte.lines().first()).setStyle(NotificationCompat.BigTextStyle().bigText(texte)).setAutoCancel(true).build())
        }
    }
}
