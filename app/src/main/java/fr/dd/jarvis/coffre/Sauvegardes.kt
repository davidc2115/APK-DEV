package fr.dd.jarvis.coffre

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Serveur
import fr.dd.jarvis.societe.core.SocieteData
import fr.dd.jarvis.societe.core.jsonSociete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sauvegardes chiffrées et DATÉES des données de la société sur le serveur SSH (« ~/jarvis-sauvegardes ») : chaque nuit
 * une archive complète (base + photos, signatures, bulletins…), chiffrée sur le téléphone (AES-256-GCM) avant l'envoi.
 * On garde les 30 derniers jours et une sauvegarde par mois pendant un an. Complément du coffre-fort (synchronisation en
 * continu) : une sauvegarde datée permet de revenir à l'état exact d'un jour donné, même après une erreur synchronisée
 * partout. La clé est celle du coffre-fort s'il est installé (même code de récupération), sinon une clé propre aux
 * sauvegardes dont le code de récupération est affiché dans Réglages › Société › Sauvegardes.
 */
object Sauvegardes {
    private val verrou = Mutex()
    private const val AAD = "jarvis-sauvegarde-societe"
    private const val EXT = ".jsauv"

    fun configure(app: JarvisApp) = Serveur.sshConfigure(app)
    fun actif(app: JarvisApp) = app.store.sauvegardeAuto.value && configure(app)
    private fun dossier(app: JarvisApp) = app.store.sauvegardeDossier.value.trim().trim('/').ifBlank { "jarvis-sauvegardes" }.replace(Regex("[^A-Za-z0-9._/-]"), "_")

    // ------------------------------------------------------------------ clé

    /** Clé maîtresse : celle du coffre-fort si ce téléphone l'a, sinon celle des sauvegardes (créée au premier usage). */
    fun maitre(app: JarvisApp, creer: Boolean = true): ByteArray? {
        app.store.coffreMaitre.value.takeIf { it.isNotBlank() }?.let { return CoffreCrypto.deB64(it) }
        app.store.sauvegardeMaitre.value.takeIf { it.isNotBlank() }?.let { return CoffreCrypto.deB64(it) }
        if (!creer) return null
        val m = CoffreCrypto.nouvelleCleMaitre()
        app.store.sauvegardeMaitre.set(CoffreCrypto.b64(m))
        return m
    }

    /** Toutes les clés possibles pour déchiffrer (coffre et sauvegardes). */
    private fun cles(app: JarvisApp): List<ByteArray> = listOf(app.store.coffreMaitre.value, app.store.sauvegardeMaitre.value)
        .filter { it.isNotBlank() }.distinct().map { CoffreCrypto.cleCategorie(CoffreCrypto.deB64(it), "sauvegarde") }

    fun codeRecuperation(app: JarvisApp): String? = maitre(app, creer = false)?.let { CoffreCrypto.codeRecuperation(it) }
    fun cleDuCoffre(app: JarvisApp) = app.store.coffreMaitre.value.isNotBlank()

    /** Nouveau téléphone : saisie du code de récupération des sauvegardes (52 caractères). */
    fun importerCode(app: JarvisApp, code: String): Boolean {
        val m = CoffreCrypto.depuisCode(code) ?: return false
        app.store.sauvegardeMaitre.set(CoffreCrypto.b64(m)); return true
    }

    // ------------------------------------------------------------------ archive

    private val EXCLUS = Regex("^(data\\.json\\.(tmp|restore\\.tmp)|data\\.corrompu\\..*|.*\\.tmp)$")

    /** Archive ZIP du dossier Société (+ logo) et son empreinte (contenu seul, indépendante des dates). */
    internal fun archive(app: JarvisApp): Pair<ByteArray, String> {
        app.societe.repo.refresh()
        val racine = File(app.filesDir, "societe")
        val fichiers = racine.walkTopDown().filter { it.isFile && !EXCLUS.matches(it.name) && !it.relativeTo(racine).invariantSeparatorsPath.startsWith("coffre/") }
            .map { "societe/" + it.relativeTo(racine).invariantSeparatorsPath to it }.toMutableList()
        File(app.filesDir, "societe_logo.png").takeIf { it.isFile }?.let { fichiers += "societe_logo.png" to it }
        fichiers.sortBy { it.first }
        val empreinte = java.security.MessageDigest.getInstance("SHA-256")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((nom, f) in fichiers) {
                val octets = f.readBytes()
                empreinte.update(nom.toByteArray()); empreinte.update(CoffreCrypto.sha256(octets).toByteArray())
                z.putNextEntry(ZipEntry(nom).apply { time = f.lastModified() }); z.write(octets); z.closeEntry()
            }
        }
        return out.toByteArray() to empreinte.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ rotation (testable)

    private val DATE = Regex("societe-(\\d{4}-\\d{2}-\\d{2})_\\d{4}")
    internal fun dateDe(nom: String): LocalDate? = DATE.find(nom)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Sauvegardes à garder : les 30 derniers jours, la plus récente de chaque mois sur 12 mois, et toujours les 3 dernières. */
    internal fun aGarder(noms: List<String>, aujourdhui: LocalDate): Set<String> {
        val dates = noms.mapNotNull { n -> dateDe(n)?.let { n to it } }.sortedByDescending { it.first }
        val garder = mutableSetOf<String>()
        garder += dates.take(3).map { it.first }
        garder += dates.filter { !it.second.isBefore(aujourdhui.minusDays(30)) }.map { it.first }
        dates.filter { !it.second.isBefore(aujourdhui.minusMonths(12)) }.groupBy { it.second.year * 100 + it.second.monthValue }
            .values.forEach { l -> garder += l.maxByOrNull { it.first }!!.first }
        return garder + noms.filter { dateDe(it) == null }
    }

    // ------------------------------------------------------------------ envoi

    private fun q(t: String) = "'" + t.replace("'", "'\\''") + "'"

    suspend fun sauvegarder(app: JarvisApp, forcer: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!configure(app)) return@withContext "Serveur SSH non configuré (Réglages › Maison › Serveur)."
        verrou.withLock {
            val r = try {
                val (zip, empreinte) = archive(app)
                if (!forcer && empreinte == app.store.sauvegardeEmpreinte.value) "✅ Rien n'a changé depuis la dernière sauvegarde."
                else {
                    val cle = CoffreCrypto.cleCategorie(maitre(app)!!, "sauvegarde")
                    val chiffre = CoffreCrypto.chiffrer(cle, AAD, zip)
                    val nom = "societe-" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date()) + EXT
                    val dos = dossier(app)
                    val mk = Serveur.exec(app, "mkdir -p ${q(dos)} && chmod 700 ${q(dos)}", 30)
                    if (mk.first != 0) throw IllegalStateException("dossier ~/$dos impossible à créer : ${mk.second.trim().take(150)}")
                    Serveur.envoyerOctets(app, "$dos/$nom.part", chiffre)
                    val v = Serveur.exec(app, "cd ${q(dos)} && test \"\$(stat -c %s ${q("$nom.part")})\" = ${chiffre.size} && mv ${q("$nom.part")} ${q(nom)} && chmod 600 ${q(nom)} && echo OK", 30)
                    if (!v.second.contains("OK")) throw IllegalStateException("vérification de la sauvegarde échouée sur le serveur (${v.second.trim().take(120)}).")
                    app.store.sauvegardeEmpreinte.set(empreinte)
                    val retires = runCatching { rotation(app) }.getOrDefault(0)
                    "✅ Sauvegarde chiffrée envoyée : ~/$dos/$nom (${taille(chiffre.size.toLong())})" + (if (retires > 0) " · $retires ancienne(s) retirée(s)" else "")
                }
            } catch (e: Exception) { "❌ Sauvegarde impossible : ${e.message ?: e.javaClass.simpleName}" }
            app.store.sauvegardeDerniere.set(System.currentTimeMillis().toString())
            app.store.sauvegardeBilan.set(r)
            r
        }
    }

    private fun taille(o: Long) = when { o >= 1L shl 20 -> "%.1f Mo".format(o / 1048576.0); o >= 1024 -> "${o / 1024} Ko"; else -> "$o o" }

    data class Archive(val nom: String, val taille: Long)

    fun lister(app: JarvisApp): List<Archive> {
        val r = Serveur.exec(app, "cd ${q(dossier(app))} 2>/dev/null && for f in societe-*$EXT; do [ -f \"\$f\" ] && echo \"\$(stat -c %s \"\$f\") \$f\"; done", 30)
        return r.second.lines().mapNotNull { l -> l.trim().split(' ', limit = 2).takeIf { it.size == 2 && it[0].toLongOrNull() != null }?.let { Archive(it[1], it[0].toLong()) } }
            .sortedByDescending { it.nom }
    }

    private fun rotation(app: JarvisApp): Int {
        val noms = lister(app).map { it.nom }
        val retirer = noms - aGarder(noms, LocalDate.now())
        if (retirer.isEmpty()) return 0
        Serveur.exec(app, "cd ${q(dossier(app))} && rm -f " + retirer.joinToString(" ") { q(it) }, 30)
        return retirer.size
    }

    fun texteListe(app: JarvisApp): String {
        val l = lister(app)
        if (l.isEmpty()) return "Aucune sauvegarde sur le serveur (dossier ~/${dossier(app)})."
        return "🗄️ Sauvegardes chiffrées sur le serveur (~/${dossier(app)}) :\n" + l.take(40).joinToString("\n") { a ->
            val d = dateDe(a.nom); val h = Regex("_(\\d{2})(\\d{2})").find(a.nom)?.let { "${it.groupValues[1]}h${it.groupValues[2]}" }.orEmpty()
            "• ${d?.let { fr.dd.jarvis.societe.core.dateFr(it.toString()) } ?: a.nom} $h — ${taille(a.taille)}  (${a.nom})"
        }
    }

    // ------------------------------------------------------------------ restauration

    /**
     * Restaure une sauvegarde : l'état actuel est d'abord archivé sur le téléphone (« societe-avant-restauration-….zip »),
     * puis la base et les fichiers de la sauvegarde remplacent les actuels (les fichiers absents de la sauvegarde sont
     * conservés). La restauration se propage ensuite au coffre-fort et à la copie Freebox comme une modification normale.
     */
    suspend fun restaurer(app: JarvisApp, nom: String): String = withContext(Dispatchers.IO) {
        verrou.withLock {
            val choix = lister(app).firstOrNull { it.nom == nom.trim() } ?: lister(app).firstOrNull { it.nom.contains(nom.trim()) }
                ?: return@withLock "Sauvegarde « $nom » introuvable sur le serveur."
            val chiffre = Serveur.lireOctets(app, "${dossier(app)}/${choix.nom}", 500_000_000L)
            val zip = cles(app).firstNotNullOfOrNull { k -> runCatching { CoffreCrypto.dechiffrer(k, AAD, chiffre) }.getOrNull() }
                ?: return@withLock "❌ Impossible de déchiffrer ${choix.nom} : aucune clé de ce téléphone ne correspond (saisis le code de récupération dans Réglages › Société › Sauvegardes)."
            val entrees = LinkedHashMap<String, ByteArray>()
            ZipInputStream(zip.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val n = e.name
                    if (!e.isDirectory && n.split('/').none { it == ".." || it.isBlank() } && (n.startsWith("societe/") || n == "societe_logo.png")) entrees[n] = z.readBytes()
                    e = z.nextEntry
                }
            }
            val base = entrees["societe/data.json"] ?: return@withLock "❌ ${choix.nom} ne contient pas de base Société."
            val donnees = runCatching { jsonSociete.decodeFromString(SocieteData.serializer(), String(base, Charsets.UTF_8)) }
                .getOrElse { return@withLock "❌ Base illisible dans ${choix.nom} : ${it.message}" }
            // Filet de sécurité : l'état actuel, gardé sur le téléphone (3 derniers).
            val secours = File(app.filesDir, "restaurations").apply { mkdirs() }
            File(secours, "societe-avant-restauration-" + System.currentTimeMillis() + ".zip").writeBytes(archive(app).first)
            secours.listFiles()?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
            var n = 0
            for ((rel, octets) in entrees) {
                if (rel == "societe/data.json" || rel.startsWith("societe/data.json.")) continue
                val f = File(app.filesDir, rel); f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".restore.tmp"); tmp.writeBytes(octets); if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
                n++
            }
            app.societe.repo.update { donnees }
            "✅ Sauvegarde du ${dateDe(choix.nom)?.let { fr.dd.jarvis.societe.core.dateFr(it.toString()) } ?: choix.nom} restaurée : ${donnees.clients.size} client(s), ${donnees.docs.size} devis/facture(s), $n fichier(s).\n" +
                "↩️ L'état d'avant est gardé sur le téléphone (Réglages › Société › Sauvegardes)."
        }
    }

    // ------------------------------------------------------------------ planification (chaque nuit vers 3 h)

    fun planifier(ctx: Context) {
        val app = ctx.applicationContext as? JarvisApp ?: return
        val wm = WorkManager.getInstance(ctx)
        if (!actif(app)) { wm.cancelUniqueWork("sauvegarde_nuit"); return }
        val maintenant = java.time.LocalDateTime.now()
        var cible = maintenant.toLocalDate().atTime(LocalTime.of(3, 0))
        if (!cible.isAfter(maintenant)) cible = cible.plusDays(1)
        val delai = java.time.Duration.between(maintenant, cible).toMinutes()
        wm.enqueueUniquePeriodicWork("sauvegarde_nuit", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SauvegardeWorker>(24, TimeUnit.HOURS).setInitialDelay(delai, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build())
    }

    class SauvegardeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as JarvisApp
            if (!actif(app)) return Result.success()
            val r = sauvegarder(app)
            return if (r.startsWith("❌") && runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}
