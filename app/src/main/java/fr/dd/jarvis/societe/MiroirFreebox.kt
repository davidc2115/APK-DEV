package fr.dd.jarvis.societe

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.FreeboxOs
import fr.dd.jarvis.societe.core.Miroir
import fr.dd.jarvis.societe.core.SocieteData
import fr.dd.jarvis.societe.core.jsonSociete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Copie lisible et tenue à jour des données de la société dans un dossier du disque de la Freebox (« JARVIS Société »),
 * pour les consulter depuis n'importe quel ordinateur de la maison : PDF des devis, factures et bulletins, photos et
 * signatures des interventions, tableaux CSV (Excel), base complète en JSON.
 *
 * Le téléphone reste la référence (il marche hors ligne) ; la sauvegarde chiffrée sur le serveur (coffre-fort et
 * sauvegardes datées) est inchangée. Chaque modification part sur la box ~30 s après ; seuls les fichiers modifiés sont
 * renvoyés. JARVIS ne supprime dans ce dossier que les fichiers qu'il y a déposés lui-même.
 */
object MiroirFreebox {
    private val verrou = Mutex()
    private val joli = Json { prettyPrint = true; encodeDefaults = true }

    fun disponible(app: JarvisApp) = app.store.freeboxAppToken.value.isNotBlank()
    fun actif(app: JarvisApp) = app.store.miroirFreebox.value && disponible(app)
    fun dossier(app: JarvisApp) = app.store.miroirFreeboxDossier.value.trim().trim('/').ifBlank { "JARVIS Société" }

    // ------------------------------------------------------------------ état : ce qui est déjà sur la box

    private fun fichierEtat(app: JarvisApp) = File(File(app.filesDir, "miroir-freebox").apply { mkdirs() }, "etat.json")

    private class Etat(var dossier: String, val fichiers: MutableMap<String, String>)

    private fun lire(app: JarvisApp): Etat = runCatching {
        val o = JSONObject(fichierEtat(app).readText())
        val f = o.optJSONObject("fichiers") ?: JSONObject()
        Etat(o.optString("dossier"), f.keys().asSequence().associateWith { f.getString(it) }.toMutableMap())
    }.getOrDefault(Etat("", mutableMapOf()))

    private fun ecrire(app: JarvisApp, e: Etat) {
        val o = JSONObject().put("dossier", e.dossier).put("fichiers", JSONObject(e.fichiers as Map<*, *>))
        val tmp = File(fichierEtat(app).parentFile, "etat.json.tmp"); tmp.writeText(o.toString()); tmp.renameTo(fichierEtat(app))
    }

    /** Oublie ce qui a été envoyé : le prochain passage renverra tout (fichiers remplacés, rien n'est perdu). */
    fun reinitialiser(app: JarvisApp) { fichierEtat(app).delete() }

    // ------------------------------------------------------------------ plan

    private fun fichiersTelephone(app: JarvisApp): Map<String, String> {
        val racine = File(app.filesDir, "societe")
        val m = sortedMapOf<String, String>()
        racine.walkTopDown().filter { it.isFile }.forEach { f -> m["societe/" + f.relativeTo(racine).invariantSeparatorsPath] = "${f.length()}:${f.lastModified()}" }
        File(app.filesDir, "societe_logo.png").takeIf { it.isFile }?.let { m["societe_logo.png"] = "${it.length()}:${it.lastModified()}" }
        return m
    }

    private fun identite(app: JarvisApp, d: SocieteData) =
        fr.dd.jarvis.societe.core.Rendu.identiteLegale(d) + "|" + File(app.filesDir, "societe_logo.png").let { if (it.isFile) "${it.length()}:${it.lastModified()}" else "" }

    fun lisezMoi(app: JarvisApp, d: SocieteData) = """
        |${d.societe.nom.ifBlank { "Ma société" }} — dossier tenu à jour automatiquement par JARVIS
        |=====================================================================
        |
        |Ce dossier est une COPIE de consultation des données de la société, mise à jour par JARVIS
        |environ 30 secondes après chaque modification faite dans l'application.
        |
        |  Factures/ et Devis/       un PDF par document, rangé par année
        |  Paie/                     les bulletins de paie (PDF), par année
        |  Interventions/            photos, signatures et rapports, un dossier par intervention
        |  Tableaux/                 listes à ouvrir avec Excel ou LibreOffice (clients, factures, devis…)
        |  Base/societe.json         la base complète (format technique, pour une restauration)
        |  Factures électroniques/   factures au format Factur-X (seulement si l'option est activée)
        |
        |Important
        |- Les modifications se font dans JARVIS : un fichier modifié ici sera remplacé au prochain changement.
        |- Les fichiers que tu ajoutes toi-même dans ce dossier ne sont jamais supprimés par JARVIS.
        |- Ce dossier n'est PAS chiffré : protège le partage de la Freebox par un mot de passe
        |  (Freebox OS › Paramètres › Partages Windows).
        |- La sauvegarde chiffrée (coffre-fort et sauvegardes datées sur le serveur) continue en parallèle.
        |
        |Accès depuis un ordinateur de la maison
        |- Windows : Explorateur de fichiers › Réseau, ou taper  \\mafreebox.freebox.fr  dans la barre d'adresse.
        |- Mac : Finder › Aller › Se connecter au serveur ›  smb://mafreebox.freebox.fr
        |- Partout : https://mafreebox.freebox.fr › Explorateur de fichiers.
        |""".trimMargin()

    // ------------------------------------------------------------------ synchronisation

    data class Bilan(var envoyes: Int = 0, var supprimes: Int = 0, var restants: Int = 0, val erreurs: MutableList<String> = mutableListOf()) {
        override fun toString() = (if (envoyes + supprimes == 0 && restants == 0) "À jour" else listOfNotNull(
            envoyes.takeIf { it > 0 }?.let { "⬆️ $it fichier(s) envoyé(s)" }, supprimes.takeIf { it > 0 }?.let { "🗑️ $it retiré(s)" },
            restants.takeIf { it > 0 }?.let { "⏳ $it restant(s), la suite part dans une minute" }).joinToString(" · ")) +
            (if (erreurs.isNotEmpty()) "\n❌ " + erreurs.take(3).joinToString("\n❌ ") + (if (erreurs.size > 3) "\n… et ${erreurs.size - 3} autre(s)" else "") else "")
    }

    suspend fun synchroniser(app: JarvisApp, toutRenvoyer: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!disponible(app)) return@withContext "JARVIS n'est pas associé à la Freebox (Réglages › Maison › Box Internet)."
        verrou.withLock {
            val b = Bilan()
            var chemin = ""
            try { chemin = tour(app, b, toutRenvoyer) } catch (e: Exception) { b.erreurs += e.message ?: e.javaClass.simpleName }
            app.store.miroirFreeboxDerniere.set(System.currentTimeMillis().toString())
            app.store.miroirFreeboxBilan.set(b.toString())
            if (b.restants > 0) signalerChangement(app, delaiS = 60)
            (if (chemin.isNotBlank()) "📁 $chemin\n" else "") + b
        }
    }

    private fun tour(app: JarvisApp, b: Bilan, toutRenvoyer: Boolean): String {
        val d = app.societe.repo.refresh()
        val dossier = dossier(app)
        val base = joli.encodeToString(SocieteData.serializer(), d)
        val plan = Miroir.plan(d, fichiersTelephone(app), identite(app, d), lisezMoi(app, d), base, facturX = FactureElectronique.active(app))
        val etat = lire(app)
        if (etat.dossier != dossier || toutRenvoyer) { etat.dossier = dossier; etat.fichiers.clear() }
        val aEnvoyer = plan.filter { etat.fichiers[it.chemin] != it.empreinte }
        val prevus = plan.map { it.chemin }.toSet()
        val aRetirer = etat.fichiers.keys.filter { it !in prevus }
        if (aEnvoyer.isEmpty() && aRetirer.isEmpty()) return ""
        val tmp = File(app.cacheDir, "miroir").apply { deleteRecursively(); mkdirs() }
        val limite = System.currentTimeMillis() + 8 * 60_000L   // un passage WorkManager dure 10 min au plus
        return try {
            FreeboxOs.depot(app, dossier) { dep ->
                for ((i, e) in aEnvoyer.withIndex()) {
                    if (System.currentTimeMillis() > limite) { b.restants = aEnvoyer.size - i; break }
                    runCatching {
                        val f = produire(app, d, e, tmp) ?: return@runCatching
                        dep.envoyer(e.chemin, f)
                        etat.fichiers[e.chemin] = e.empreinte; b.envoyes++
                        if (f.parentFile == tmp) f.delete()
                        if (b.envoyes % 10 == 0) ecrire(app, etat)
                    }.onFailure { b.erreurs += "${e.chemin} : ${it.message ?: it.javaClass.simpleName}" }
                }
                for (rel in aRetirer) {
                    if (System.currentTimeMillis() > limite) { b.restants++; continue }
                    runCatching { if (dep.supprimer(rel)) { etat.fichiers.remove(rel); b.supprimes++ } }
                        .onFailure { b.erreurs += "$rel : ${it.message}" }
                }
                dep.lisible
            }
        } finally {
            ecrire(app, etat)
            tmp.deleteRecursively()
        }
    }

    private fun produire(app: JarvisApp, d: SocieteData, e: Miroir.Entree, tmp: File): File? {
        fun ecrireTmp(octets: ByteArray) = File(tmp, "f" + System.nanoTime() + "." + e.chemin.substringAfterLast('.', "bin")).apply { writeBytes(octets) }
        return when (val s = e.source) {
            is Miroir.Source.Texte -> ecrireTmp(s.contenu.toByteArray(Charsets.UTF_8))
            is Miroir.Source.PdfDoc -> d.doc(s.numero)?.let { ecrireTmp(app.societe.pdfBytes(it)) }
            is Miroir.Source.PdfBulletin -> d.bulletins.firstOrNull { it.id == s.id }?.let { app.societe.fichierBulletin(it) }
            is Miroir.Source.Fichier -> File(app.filesDir, s.rel).takeIf { it.isFile }
            is Miroir.Source.FacturX -> d.doc(s.numero)?.let { ecrireTmp(FactureElectronique.pdfFacturX(app, d, it)) }
            is Miroir.Source.FacturXml -> d.doc(s.numero)?.let { ecrireTmp(fr.dd.jarvis.societe.core.FacturX.xml(d, it).toByteArray(Charsets.UTF_8)) }
        }
    }

    // ------------------------------------------------------------------ planification

    /** Une modification de la société : copie sur la box dans ~30 s (les modifications rapprochées sont regroupées). */
    fun signalerChangement(ctx: Context, delaiS: Long = 30) {
        val app = ctx.applicationContext as? JarvisApp ?: return
        if (!actif(app)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork("miroir_freebox", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MiroirWorker>().setInitialDelay(delaiS, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    /** Rattrapage toutes les 6 h (fichiers ajoutés hors de la base, échec réseau précédent…). */
    fun planifier(ctx: Context) {
        val app = ctx.applicationContext as? JarvisApp ?: return
        val wm = WorkManager.getInstance(ctx)
        if (!actif(app)) { wm.cancelUniqueWork("miroir_freebox_periodique"); return }
        wm.enqueueUniquePeriodicWork("miroir_freebox_periodique", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MiroirWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build())
    }

    class MiroirWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as JarvisApp
            if (actif(app)) synchroniser(app)
            return Result.success()
        }
    }
}
