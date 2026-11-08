package fr.dd.jarvis.coffre

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
import fr.dd.jarvis.societe.core.SocieteData
import fr.dd.jarvis.societe.core.jsonSociete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Coffre-fort de la société : synchronisation chiffrée entre ce téléphone et le service « coffre-jarvis » du serveur,
 * partagé par toutes les installations de JARVIS (ton téléphone, une tablette, les salariés selon leurs droits).
 *
 * Ce qui est synchronisé : la base Société (4 parties : base, clients, documents, paie) et les fichiers du dossier
 * Société (photos, signatures et rapports d'intervention…). Tout est chiffré ici avant l'envoi. Chaque tour :
 * 1) on récupère ce qui a changé sur le coffre (fusion à 3 voies pour la base, fichiers remplacés ou mis de côté en cas
 * de conflit) ; 2) on envoie ce qui a changé ici ; 3) on propage les suppressions de fichiers (corbeille du coffre).
 */
object Coffre {
    private val verrou = Mutex()
    private val compact = Json { encodeDefaults = true }
    val CATEGORIES = listOf("base", "clients", "documents", "paie", "interventions", "fichiers")
    val ROLES = linkedMapOf("admin" to "Moi (accès complet)", "technicien" to "Technicien (clients, interventions — sans devis, factures ni paie)")

    fun configure(app: JarvisApp) = app.store.coffreJeton.value.isNotBlank() && app.store.coffreEmpreinte.value.isNotBlank()
    fun categories(app: JarvisApp) = app.store.coffreCategories.value.split(',').map { it.trim() }.filter { it.isNotBlank() }
    fun estAdmin(app: JarvisApp) = app.store.coffreRole.value == "admin"

    /** Adresses dans le bon ordre : la locale d'abord si le téléphone est sur le réseau de la maison. */
    fun adresses(app: JarvisApp): List<String> {
        val local = app.store.coffreUrlLocal.value.trim()
        val distant = app.store.coffreUrlDistant.value.trim()
        val hote = runCatching { java.net.URI(local).host }.getOrNull().orEmpty()
        val prefixe = hote.substringBeforeLast('.', "") + "."
        val surLeReseau = prefixe.length > 1 && runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().any { it.hostAddress.orEmpty().startsWith(prefixe) }
        }.getOrDefault(false)
        return (if (surLeReseau || distant.isBlank()) listOf(local, distant) else listOf(distant, local)).filter { it.isNotBlank() }
    }

    /** Tunnel SSH vers le coffre (si ce téléphone a l'accès SSH au serveur) : marche sans aucun port ouvert sur la box. */
    fun tunnel(app: JarvisApp): (() -> String)? =
        if (fr.dd.jarvis.ai.Serveur.sshConfigure(app)) { { "https://127.0.0.1:" + fr.dd.jarvis.ai.Serveur.tunnel(app, CoffreInstallation.PORT) } } else null

    fun client(app: JarvisApp) = CoffreClient(adresses(app), app.store.coffreEmpreinte.value, tunnel(app)) { app.store.coffreJeton.value }

    /** Clé d'une catégorie : dérivée de la clé maîtresse (administrateur) ou reçue à l'invitation (salarié). */
    fun cle(app: JarvisApp, categorie: String): ByteArray? {
        app.store.coffreMaitre.value.takeIf { it.isNotBlank() }?.let { return CoffreCrypto.cleCategorie(CoffreCrypto.deB64(it), categorie) }
        return runCatching { JSONObject(app.store.coffreCles.value).optString(categorie).takeIf { it.isNotBlank() }?.let { CoffreCrypto.deB64(it) } }.getOrNull()
    }

    /** Enregistre l'accès de ce téléphone (après installation, invitation ou récupération). */
    fun enregistrer(app: JarvisApp, local: String, distant: String, empreinte: String, reponse: JSONObject, maitre: ByteArray?, cles: JSONObject?) {
        val s = app.store
        s.coffreUrlLocal.set(local.trim().trimEnd('/')); s.coffreUrlDistant.set(distant.trim().trimEnd('/')); s.coffreEmpreinte.set(empreinte.trim().lowercase())
        s.coffreJeton.set(reponse.getString("jeton")); s.coffreAppareil.set(reponse.optString("appareil")); s.coffreRole.set(reponse.optString("role"))
        val cats = reponse.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: CATEGORIES
        s.coffreCategories.set(cats.joinToString(","))
        if (maitre != null) s.coffreMaitre.set(CoffreCrypto.b64(maitre)) else s.coffreMaitre.clear()
        s.coffreCles.set(cles?.toString() ?: "")
        s.coffreSeq.set("0")
        File(app.filesDir, "coffre").deleteRecursively()
        planifier(app)
    }

    fun oublier(app: JarvisApp) {
        val s = app.store
        listOf(s.coffreJeton, s.coffreCles, s.coffreMaitre).forEach { it.clear() }
        listOf(s.coffreUrlLocal, s.coffreUrlDistant, s.coffreEmpreinte, s.coffreAppareil, s.coffreRole, s.coffreCategories, s.coffreBilan).forEach { it.set("") }
        s.coffreSeq.set("0")
        File(app.filesDir, "coffre").deleteRecursively()
        WorkManager.getInstance(app).cancelUniqueWork("coffre_periodique")
    }

    // ------------------------------------------------------------------ état local de la synchronisation

    private class Suivi(val v: Int, val sha: String, val taille: Long = -1, val modif: Long = -1)

    private fun dossier(app: JarvisApp) = File(app.filesDir, "coffre").apply { mkdirs() }
    private fun fichierEtat(app: JarvisApp) = File(dossier(app), "etat.json")

    private fun lireEtat(app: JarvisApp): MutableMap<String, Suivi> = runCatching {
        val o = JSONObject(fichierEtat(app).readText())
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Suivi(it.getInt("v"), it.getString("sha"), it.optLong("t", -1), it.optLong("m", -1)) } }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun ecrireEtat(app: JarvisApp, e: Map<String, Suivi>) {
        val o = JSONObject(); e.forEach { (k, s) -> o.put(k, JSONObject().put("v", s.v).put("sha", s.sha).put("t", s.taille).put("m", s.modif)) }
        val tmp = File(dossier(app), "etat.json.tmp"); tmp.writeText(o.toString()); tmp.renameTo(fichierEtat(app))
    }

    private fun fichierBase(app: JarvisApp, partie: String) = File(dossier(app), "base-$partie.json")

    /** Dernière version synchronisée d'une partie ; à défaut, les valeurs par défaut (un téléphone neuf ne remplace rien). */
    private fun base(app: JarvisApp, partie: String): JsonObject = runCatching { Json.parseToJsonElement(fichierBase(app, partie).readText()).jsonObject }
        .getOrElse { Fusion.extraire(jsonSociete.encodeToJsonElement(SocieteData.serializer(), SocieteData()).jsonObject, partie) }

    // ------------------------------------------------------------------ synchronisation

    private fun racine(app: JarvisApp) = File(app.filesDir, "societe")
    private val EXCLUS = Regex("^(data\\.json(\\..*)?|data\\.corrompu\\..*|.*\\.tmp)$")
    internal fun categorieFichier(rel: String) = if (rel.startsWith("interventions/")) "interventions" else "fichiers"

    private fun tout(app: JarvisApp): JsonObject = jsonSociete.encodeToJsonElement(SocieteData.serializer(), app.societe.repo.refresh()).jsonObject
    private fun texte(o: JsonObject) = compact.encodeToString(JsonObject.serializer(), o)

    /** Écrit une partie fusionnée dans la base Société du téléphone (relue sous verrou, sans écraser une écriture concurrente). */
    private fun appliquer(app: JarvisApp, partie: String, fusion: JsonObject) {
        app.societe.repo.update { d ->
            val j = jsonSociete.encodeToJsonElement(SocieteData.serializer(), d).jsonObject
            jsonSociete.decodeFromJsonElement(SocieteData.serializer(), Fusion.remplacer(j, partie, fusion))
        }
    }

    data class Bilan(var recus: Int = 0, var envoyes: Int = 0, var supprimes: Int = 0, var conflits: Int = 0, val erreurs: MutableList<String> = mutableListOf()) {
        override fun toString() = (if (recus + envoyes + supprimes == 0) "À jour" else listOfNotNull(
            recus.takeIf { it > 0 }?.let { "⬇️ $it reçu(s)" }, envoyes.takeIf { it > 0 }?.let { "⬆️ $it envoyé(s)" },
            supprimes.takeIf { it > 0 }?.let { "🗑️ $it supprimé(s)" }).joinToString(" · ")) +
            (if (conflits > 0) " · ⚠️ $conflits conflit(s) fusionné(s)" else "") + (if (erreurs.isNotEmpty()) "\n❌ " + erreurs.take(3).joinToString("\n❌ ") else "")
    }

    suspend fun synchroniser(app: JarvisApp): String = withContext(Dispatchers.IO) {
        if (!configure(app)) return@withContext "Coffre-fort non configuré."
        verrou.withLock {
            val b = Bilan()
            try {
                tour(app, b)
            } catch (e: CoffreException) {
                b.erreurs += if (e.code == 401) "Accès refusé : cet appareil a été révoqué ou le coffre a été réinstallé." else e.message.orEmpty()
            } catch (e: Exception) {
                b.erreurs += e.message ?: e.javaClass.simpleName
            }
            app.store.coffreDerniere.set(System.currentTimeMillis().toString())
            app.store.coffreBilan.set(b.toString())
            b.toString()
        }
    }

    private fun tour(app: JarvisApp, b: Bilan) {
        val c = client(app)
        val cats = categories(app).toSet()
        val etat = lireEtat(app)
        try {
            // 1. Ce qui a changé sur le coffre.
            var seq = app.store.coffreSeq.value.toLongOrNull() ?: 0L
            do {
                val r = c.changements(seq)
                val l = r.getJSONArray("changements")
                for (i in 0 until l.length()) {
                    val ch = l.getJSONObject(i)
                    runCatching { recevoir(app, c, ch, etat, b) }.onFailure { b.erreurs += "${ch.optString("chemin")} : ${it.message}" }
                }
                seq = r.optLong("seq", seq)
                app.store.coffreSeq.set(seq.toString())
            } while (r.optBoolean("suite"))

            // 2. La base Société : chaque partie autorisée qui a changé ici.
            for (partie in Fusion.PARTIES.keys.filter { it in cats }) {
                val chemin = "donnees/$partie.json"
                repeat(3) { essai ->
                    val local = Fusion.extraire(tout(app), partie)
                    val t = texte(local)
                    val sha = CoffreCrypto.sha256(t.toByteArray())
                    val st = etat[chemin]
                    if (st?.sha == sha) return@repeat
                    val cle = cle(app, partie) ?: return@repeat
                    try {
                        val v = c.ecrire(chemin, partie, st?.v ?: 0, CoffreCrypto.chiffrer(cle, chemin, t.toByteArray()))
                        etat[chemin] = Suivi(v, sha); fichierBase(app, partie).writeText(t); b.envoyes++
                        return@repeat
                    } catch (e: CoffreException) {
                        if (e.code != 409 || essai == 2) throw e
                        // Un autre téléphone a écrit entre-temps : on récupère sa version, on fusionne, on renvoie.
                        recevoirPartie(app, c, partie, e.version, etat, b)
                    }
                }
            }

            // 3. Les fichiers du dossier Société.
            val racine = racine(app)
            val presents = HashSet<String>()
            racine.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(racine).invariantSeparatorsPath
                if (EXCLUS.matches(f.name) || rel.startsWith("coffre/")) return@forEach
                val cat = categorieFichier(rel)
                if (cat !in cats) return@forEach
                val chemin = "fichiers/$rel"
                presents += chemin
                val st = etat[chemin]
                if (st != null && st.taille == f.length() && st.modif == f.lastModified()) return@forEach
                val octets = f.readBytes()
                val sha = CoffreCrypto.sha256(octets)
                if (st?.sha == sha) { etat[chemin] = Suivi(st.v, sha, f.length(), f.lastModified()); return@forEach }
                val cle = cle(app, cat) ?: return@forEach
                try {
                    val v = c.ecrire(chemin, cat, st?.v ?: 0, CoffreCrypto.chiffrer(cle, chemin, octets))
                    etat[chemin] = Suivi(v, sha, f.length(), f.lastModified()); b.envoyes++
                } catch (e: CoffreException) {
                    if (e.code != 409) { b.erreurs += "$rel : ${e.message}"; return@forEach }
                    // Fichier modifié des deux côtés : notre version est gardée à côté, celle du coffre reprend sa place.
                    val copie = File(f.parentFile, f.nameWithoutExtension + " (conflit ${java.text.SimpleDateFormat("dd-MM HH'h'mm", java.util.Locale.FRANCE).format(java.util.Date())})" +
                        (if (f.extension.isNotBlank()) "." + f.extension else ""))
                    f.copyTo(copie, overwrite = true)
                    runCatching { f.writeBytes(CoffreCrypto.dechiffrer(cle, chemin, c.lire(chemin))) }
                    etat.remove(chemin); b.conflits++
                }
            }
            // Fichiers supprimés ici depuis la dernière synchro → corbeille du coffre (récupérables 30 jours).
            etat.keys.filter { it.startsWith("fichiers/") && it !in presents && categorieFichier(it.removePrefix("fichiers/")) in cats }.forEach { chemin ->
                val st = etat[chemin] ?: return@forEach
                runCatching { c.supprimer(chemin, st.v) }.onSuccess { etat.remove(chemin); b.supprimes++ }
                    .onFailure { e -> if ((e as? CoffreException)?.code == 409) etat.remove(chemin) }
            }
        } finally {
            ecrireEtat(app, etat)
        }
    }

    private fun recevoir(app: JarvisApp, c: CoffreClient, ch: JSONObject, etat: MutableMap<String, Suivi>, b: Bilan) {
        val chemin = ch.getString("chemin")
        val v = ch.getInt("version")
        if ((etat[chemin]?.v ?: 0) >= v) return
        if (chemin.startsWith("donnees/")) {
            if (!ch.optBoolean("supprime")) recevoirPartie(app, c, chemin.removePrefix("donnees/").removeSuffix(".json"), v, etat, b)
            return
        }
        val rel = chemin.removePrefix("fichiers/")
        if (rel.split('/').any { it == ".." || it.isBlank() }) return
        val f = File(racine(app), rel)
        val st = etat[chemin]
        if (ch.optBoolean("supprime")) {
            // Supprimé sur un autre téléphone : supprimé ici aussi, sauf si on l'a modifié entre-temps.
            if (f.exists() && (st == null || CoffreCrypto.sha256(f.readBytes()) == st.sha)) { f.delete(); b.supprimes++ }
            etat.remove(chemin)
            return
        }
        val cle = cle(app, ch.optString("categorie")) ?: return
        val clair = CoffreCrypto.dechiffrer(cle, chemin, c.lire(chemin))
        val sha = CoffreCrypto.sha256(clair)
        if (f.exists()) {
            val actuel = CoffreCrypto.sha256(f.readBytes())
            if (actuel != sha && st != null && actuel != st.sha) {
                // Modifié ici ET ailleurs : notre version est gardée à côté.
                f.copyTo(File(f.parentFile, f.nameWithoutExtension + " (conflit)" + (if (f.extension.isNotBlank()) "." + f.extension else "")), overwrite = true)
                b.conflits++
            }
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".coffre.tmp"); tmp.writeBytes(clair); tmp.renameTo(f)
        etat[chemin] = Suivi(v, sha, f.length(), f.lastModified())
        b.recus++
    }

    private fun recevoirPartie(app: JarvisApp, c: CoffreClient, partie: String, v: Int, etat: MutableMap<String, Suivi>, b: Bilan) {
        if (partie !in Fusion.PARTIES) return
        val chemin = "donnees/$partie.json"
        val cle = cle(app, partie) ?: return
        val texteDistant = String(CoffreCrypto.dechiffrer(cle, chemin, c.lire(chemin)))
        val distant = Json.parseToJsonElement(texteDistant).jsonObject
        val local = Fusion.extraire(tout(app), partie)
        val fusion = Fusion.fusionner(base(app, partie), local, distant) as? JsonObject ?: distant
        if (fusion != local) appliquer(app, partie, fusion)
        if (fusion != local && fusion != distant) b.conflits++
        fichierBase(app, partie).writeText(texte(distant))
        etat[chemin] = Suivi(v, CoffreCrypto.sha256(texte(distant).toByteArray()))
        b.recus++
    }

    // ------------------------------------------------------------------ planification

    fun planifier(ctx: Context) {
        val app = ctx.applicationContext as? JarvisApp ?: return
        if (!configure(app) || !app.store.coffreAuto.value) return
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("coffre_periodique", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CoffreWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    /** Une modification de la base Société : synchro dans 20 s (les modifications rapprochées sont regroupées). */
    fun signalerChangement(ctx: Context) {
        val app = ctx.applicationContext as? JarvisApp ?: return
        if (!configure(app) || !app.store.coffreAuto.value) return
        WorkManager.getInstance(ctx).enqueueUniqueWork("coffre_maintenant", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CoffreWorker>().setInitialDelay(20, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    class CoffreWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as JarvisApp
            synchroniser(app)
            return Result.success()
        }
    }
}
