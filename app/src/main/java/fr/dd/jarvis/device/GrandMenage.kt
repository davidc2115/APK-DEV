package fr.dd.jarvis.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.MailTri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Grand ménage de toute la boîte Gmail : chaque mail rejoint SON libellé (organisation de l'utilisateur, en couleur),
 * est marqué lu + « Traité » et sort de la boîte de réception ; les anciens libellés de Jarvis sont vidés puis
 * supprimés. Règles par expéditeur appliquées par lots de 1000 (rapide), l'IA ne voit que les mails inconnus.
 * Travail découpé en étapes de quelques minutes (WorkManager), qui s'enchaînent jusqu'au bout ; reprise possible.
 */
object GrandMenage {
    private const val CANAL = "menage_mails"
    private const val NOTIF = 7760
    private const val PAR_ETAPE = 400

    fun lancer(ctx: Context, compte: String) {
        val app = ctx.applicationContext as JarvisApp
        app.store.menageCompte.set(compte)
        app.store.menageDemarre.set("")
        app.store.menageBilan.set("{}")
        app.store.menageErreurs.set("0")
        notifier(ctx, "🧹 Grand ménage des mails lancé", "Je range tous les mails de $compte dans tes libellés…", enCours = true)
        planifier(ctx, 0, ExistingWorkPolicy.REPLACE)
    }

    /** Étape suivante (ajoutée APRÈS l'étape en cours, sans l'interrompre). */
    private fun planifier(ctx: Context, delaiS: Long, politique: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("grand_menage_mails", politique,
            OneTimeWorkRequestBuilder<MenageWorker>().setInitialDelay(delaiS, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("compte" to (ctx.applicationContext as JarvisApp).store.menageCompte.value)).build())
    }

    fun etat(app: JarvisApp): String {
        val b = runCatching { JSONObject(app.store.menageBilan.value) }.getOrDefault(JSONObject())
        if (b.length() == 0) return "Aucun grand ménage en cours ou terminé récemment."
        return presenter(b)
    }

    private fun presenter(b: JSONObject): String {
        val parLibelle = b.optJSONObject("libelles") ?: JSONObject()
        val total = parLibelle.keys().asSequence().sumOf { parLibelle.optInt(it) }
        return "📬 ${"%,d".format(total).replace(',', ' ')} mail(s) rangé(s)" + (if (b.optBoolean("fini")) " — ✅ terminé" else " — ⏳ en cours") + "\n" +
            parLibelle.keys().asSequence().map { it to parLibelle.optInt(it) }.sortedByDescending { it.second }.joinToString("\n") { "• ${it.first} : ${it.second}" } +
            (b.optString("supprimes").takeIf { it.isNotBlank() }?.let { "\n🗑️ Anciens libellés supprimés : $it" } ?: "") +
            (b.optString("erreur").takeIf { it.isNotBlank() }?.let { "\n⚠️ $it" } ?: "")
    }

    /** Une étape (quelques minutes au plus) ; vrai s'il reste du travail. */
    suspend fun etape(app: JarvisApp, compte: String): Boolean = withContext(Dispatchers.IO) {
        val debut = System.currentTimeMillis()
        val budget = 7 * 60_000L
        val token = GmailApi.jeton(app, compte)
        val prefix = app.store.mailLabelPrefix.value
        val bilan = runCatching { JSONObject(app.store.menageBilan.value) }.getOrDefault(JSONObject())
        val parLibelle = bilan.optJSONObject("libelles") ?: JSONObject().also { bilan.put("libelles", it) }
        fun compter(l: String, n: Int) { parLibelle.put(l, parLibelle.optInt(l) + n); app.store.menageBilan.set(bilan.toString()) }

        // 1. Libellés de l'organisation (créés et colorés), libellé « Traité ».
        val existants = GmailApi.tousLesLibelles(token)
        val ids = ReglesMail.LIBELLES.associate { it.nom to GmailApi.libelleEnCouleur(token, existants, prefix + it.nom) }
        val traite = GmailApi.libelleEnCouleur(token, existants, prefix + ReglesMail.TRAITE.nom)
        val premiere = app.store.menageDemarre.value != compte
        if (premiere) {
            ReglesMail.LIBELLES.forEach { l -> ids[l.nom]?.let { GmailApi.colorer(token, it, l.fond, l.texte) } }
            GmailApi.colorer(token, traite, ReglesMail.TRAITE.fond, ReglesMail.TRAITE.texte)
            // Tout est retrié : on retire d'abord l'ancien « Traité » (posé avant cette organisation).
            GmailApi.modifierLot(token, GmailApi.idsRecherche(token, "label:\"${prefix}${ReglesMail.TRAITE.nom}\""), emptyList(), listOf(traite))
            app.store.menageDemarre.set(compte)
        }
        val obsoletes = existants.filter { (nom, _) -> nom.startsWith(prefix) && ReglesMail.estObsolete(nom.removePrefix(prefix)) }
        val aRetirer = ids.values + obsoletes.values + listOf("UNREAD", "INBOX")
        val base = "-in:spam -in:trash -in:drafts -in:sent -label:\"${prefix}${ReglesMail.TRAITE.nom}\""
        fun ranger(liste: List<String>, libelle: String) {
            if (liste.isEmpty()) return
            GmailApi.modifierLot(token, liste, listOf(ids.getValue(libelle), traite), aRetirer)
            compter(libelle, liste.size)
        }

        // 2. Règles par expéditeur (recherches Gmail) et clients connus → Travail.
        for (r in ReglesMail.REGLES) {
            if (System.currentTimeMillis() - debut > budget) return@withContext true
            ranger(GmailApi.idsRecherche(token, "${ReglesMail.requete(r)} $base"), r.libelle)
        }
        MailTri.adressesClients(app).chunked(15).forEach { lot ->
            ranger(GmailApi.idsRecherche(token, lot.joinToString(" OR ", "from:(", ") ") { "\"$it\"" } + base), "Travail")
        }

        // 3. Le reste : en-têtes, règles en mémoire, puis l'IA (par étapes de 400).
        val reste = GmailApi.idsRecherche(token, base, PAR_ETAPE)
        if (reste.isNotEmpty()) {
            val sem = Semaphore(6)
            val mails = coroutineScope {
                reste.map { id ->
                    async { sem.withPermit { runCatching { GmailApi.entetes(token, id) }.getOrNull()?.let { (de, sujet, labels) ->
                        MailItem(from = de, subject = sujet.ifBlank { "(sans objet)" }, date = null, snippet = "", unread = "UNREAD" in labels, account = compte, id = id, fromAddr = de, labels = labels)
                    } } }
                }.awaitAll().filterNotNull()
            }
            val classes = MailTri.classer(app, mails)
            classes.groupBy { it.cat }.forEach { (cat, l) -> if (cat in ids) ranger(l.map { it.mail.id }, cat) }
        }
        if (reste.size >= PAR_ETAPE) return@withContext true

        // 4. Fin : anciens libellés supprimés (tous leurs mails ont été rangés ailleurs).
        val supprimes = obsoletes.filter { (_, id) -> GmailApi.supprimerLibelle(token, id) }.keys
        bilan.put("supprimes", supprimes.joinToString(", ")).put("fini", true)
        app.store.menageBilan.set(bilan.toString())
        false
    }

    internal fun notifier(ctx: Context, titre: String, texte: String, enCours: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, "Grand ménage des mails", NotificationManager.IMPORTANCE_LOW))
        runCatching {
            NotificationManagerCompat.from(ctx).notify(NOTIF, NotificationCompat.Builder(ctx, CANAL).setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
                .setContentTitle(titre).setContentText(texte.lines().first()).setStyle(NotificationCompat.BigTextStyle().bigText(texte))
                .setOngoing(enCours).setOnlyAlertOnce(true).setAutoCancel(!enCours).apply { if (enCours) setProgress(0, 0, true) }.build())
        }
    }

    class MenageWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as JarvisApp
            val compte = inputData.getString("compte").orEmpty().ifBlank { app.store.menageCompte.value }
            if (compte.isBlank()) return Result.success()
            return try {
                val encore = etape(app, compte)
                app.store.menageErreurs.set("0")
                val b = runCatching { JSONObject(app.store.menageBilan.value) }.getOrDefault(JSONObject())
                if (encore) {
                    notifier(app, "🧹 Grand ménage des mails en cours…", presenter(b), enCours = true)
                    planifier(app, 15)
                } else notifier(app, "✅ Grand ménage des mails terminé", presenter(b), enCours = false)
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                val b = runCatching { JSONObject(app.store.menageBilan.value) }.getOrDefault(JSONObject()).put("erreur", e.message ?: e.javaClass.simpleName)
                app.store.menageBilan.set(b.toString())
                val n = (app.store.menageErreurs.value.toIntOrNull() ?: 0) + 1
                app.store.menageErreurs.set(n.toString())
                if (n < 4) { planifier(app, 120); Result.success() }
                else { notifier(app, "⚠️ Grand ménage interrompu", presenter(b) + "\nRelance-le depuis Mails.", enCours = false); Result.success() }
            }
        }
    }
}
