package fr.dd.jarvis.work

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Bilan de santé quotidien (vers 7 h 30) : clés IA (sans consommer de quota : simple liste des modèles), Home Assistant,
 * Plex, Freebox, Discord, serveur (disque, mémoire, conteneurs Docker arrêtés), coffre-fort, copie Freebox, sauvegardes.
 * Une notification seulement s'il y a un problème ; le rapport complet est dans Réglages › Diagnostic › Bilan quotidien
 * et à la demande (« fais le bilan de santé »).
 */
object BilanSante {
    enum class Niveau(val icone: String) { OK("✅"), ATTENTION("⚠️"), PANNE("❌"), ABSENT("➖") }
    data class Point(val nom: String, val niveau: Niveau, val detail: String) {
        override fun toString() = "${niveau.icone} **$nom** — $detail"
    }

    private fun code(req: Request): Pair<Int, String> = runCatching {
        Http.fast.newCall(req).execute().use { it.code to (it.body?.string().orEmpty().take(300)) }
    }.getOrElse { -1 to (it.message ?: it.javaClass.simpleName) }

    private fun masque(k: String) = k.take(4) + "…" + k.takeLast(4)
    private fun ilYA(ms: Long): String {
        val h = (System.currentTimeMillis() - ms) / 3_600_000L
        return if (h < 1) "il y a moins d'une heure" else if (h < 48) "il y a $h h" else "il y a ${h / 24} jours"
    }

    private fun gemini(app: JarvisApp): Point {
        val cles = app.store.geminiKeys.filterNot(fr.dd.jarvis.ai.KeyRing::estCleOpenAi)
        if (cles.isEmpty()) return Point("Clés Gemini", Niveau.ABSENT, "aucune")
        val r = cles.map { k ->
            val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=5").header("x-goog-api-key", k).build()
            k to runCatching { fr.dd.jarvis.ai.GeminiPasserelle.executer(Http.fast, req) }.getOrElse { -1 to (it.message ?: "") }
        }
        val ko = r.filter { it.second.first !in 200..299 }
        return when {
            ko.isEmpty() -> Point("Clés Gemini", Niveau.OK, "${cles.size}/${cles.size} acceptées par Google")
            ko.size == cles.size -> Point("Clés Gemini", Niveau.PANNE, ko.joinToString(" ; ") { "${masque(it.first)} : ${fr.dd.jarvis.ai.KeyRing.motif(it.second.first, it.second.second)}" })
            else -> Point("Clés Gemini", Niveau.ATTENTION, "${cles.size - ko.size}/${cles.size} OK — " + ko.joinToString(" ; ") { "${masque(it.first)} : ${fr.dd.jarvis.ai.KeyRing.motif(it.second.first, it.second.second)}" })
        }
    }

    private fun groq(app: JarvisApp): Point {
        val cles = app.store.groqKeys.toList()
        if (cles.isEmpty()) return Point("Clés Groq", Niveau.ABSENT, "aucune")
        val ko = cles.map { k -> k to code(Request.Builder().url("https://api.groq.com/openai/v1/models").header("Authorization", "Bearer $k").build()) }
            .filter { it.second.first !in 200..299 }
        return when {
            ko.isEmpty() -> Point("Clés Groq", Niveau.OK, "${cles.size}/${cles.size} acceptées")
            else -> Point("Clés Groq", if (ko.size == cles.size) Niveau.PANNE else Niveau.ATTENTION,
                "${cles.size - ko.size}/${cles.size} OK — " + ko.joinToString(" ; ") { "${masque(it.first)} : ${fr.dd.jarvis.ai.KeyRing.motif(it.second.first, it.second.second)}" })
        }
    }

    private fun homeAssistant(app: JarvisApp): Point {
        val jeton = app.store.haToken.value.trim()
        val urls = listOf(app.store.haUrl.value, app.store.haUrlDistant.value).map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }
        if (jeton.isBlank() || urls.isEmpty()) return Point("Home Assistant", Niveau.ABSENT, "non configuré")
        val r = urls.map { u -> u to code(Request.Builder().url("$u/api/").header("Authorization", "Bearer $jeton").build()) }
        r.firstOrNull { it.second.first in 200..299 }?.let { return Point("Home Assistant", Niveau.OK, "en ligne (${it.first.substringAfter("://").substringBefore('/')})") }
        return Point("Home Assistant", Niveau.PANNE, r.joinToString(" ; ") { (u, c) ->
            u.substringAfter("://").substringBefore('/') + " : " + when (c.first) { -1 -> "injoignable (${c.second.take(60)})"; 401 -> "jeton refusé"; else -> "HTTP ${c.first}" }
        } + " — dis « répare Home Assistant » pour un diagnostic.")
    }

    private fun plex(app: JarvisApp): Point {
        if (app.store.plexToken.value.isBlank()) return Point("Plex", Niveau.ABSENT, "non configuré")
        return runCatching { fr.dd.jarvis.ai.Plex.serveur(app); Point("Plex", Niveau.OK, "serveur joignable") }
            .getOrElse { Point("Plex", Niveau.PANNE, it.message?.take(120) ?: "injoignable") }
    }

    private fun freebox(app: JarvisApp): Point {
        if (app.store.freeboxAppToken.value.isBlank()) return Point("Freebox", Niveau.ABSENT, "non associée")
        return runCatching {
            val (enLigne, libre, total) = fr.dd.jarvis.ai.FreeboxOs.etat(app) ?: return Point("Freebox", Niveau.ABSENT, "non associée")
            val pct = if (total > 0) libre * 100 / total else -1
            when {
                !enLigne -> Point("Freebox", Niveau.PANNE, "connexion Internet coupée")
                pct in 0..4 -> Point("Freebox", Niveau.ATTENTION, "disque presque plein ($pct % libres)")
                else -> Point("Freebox", Niveau.OK, "en ligne" + (if (pct >= 0) ", disque $pct % libres" else ""))
            }
        }.getOrElse { Point("Freebox", Niveau.PANNE, it.message?.take(120) ?: "injoignable") }
    }

    private fun discord(app: JarvisApp): Point {
        if (app.store.discordToken.value.isBlank()) return Point("Discord", Niveau.ABSENT, "non configuré")
        return runCatching { val m = fr.dd.jarvis.ai.Discord.moi(app); Point("Discord", Niveau.OK, "bot « ${m.optString("username")} » connecté") }
            .getOrElse { Point("Discord", Niveau.PANNE, it.message?.take(120) ?: "refusé") }
    }

    private fun serveur(app: JarvisApp): List<Point> {
        if (!fr.dd.jarvis.ai.Serveur.sshConfigure(app)) return listOf(Point("Serveur", Niveau.ABSENT, "SSH non configuré"))
        val (c, out) = runCatching { fr.dd.jarvis.ai.Serveur.exec(app,
            "df -P / | awk 'NR==2{print \"DISQUE \" \$5}'; free -m | awk 'NR==2{print \"RAM \" \$2 \" \" \$7}'; " +
            "(docker ps -a --format '{{.Names}}|{{.Status}}' 2>/dev/null || sudo -n docker ps -a --format '{{.Names}}|{{.Status}}' 2>/dev/null) | sed 's/^/CT /'", 40) }
            .getOrElse { return listOf(Point("Serveur", Niveau.PANNE, "SSH : ${it.message?.take(120)}")) }
        val l = mutableListOf<Point>()
        val disque = Regex("DISQUE (\\d+)%").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val ram = Regex("RAM (\\d+) (\\d+)").find(out)?.groupValues
        l += Point("Serveur", when { disque != null && disque >= 95 -> Niveau.PANNE; disque != null && disque >= 85 -> Niveau.ATTENTION; else -> Niveau.OK },
            listOfNotNull(disque?.let { "disque utilisé à $it %" }, ram?.let { "mémoire libre ${it[2]} Mo / ${it[1]} Mo" }).joinToString(", ").ifBlank { "joignable (code $c)" })
        val conteneurs = out.lines().filter { it.startsWith("CT ") }.map { it.removePrefix("CT ").split('|', limit = 2) }.filter { it.size == 2 }
        val arretes = conteneurs.filter { (_, st) -> !st.startsWith("Up") || st.contains("Restarting") || st.contains("unhealthy") }
        if (conteneurs.isNotEmpty()) l += if (arretes.isEmpty()) Point("Conteneurs Docker", Niveau.OK, "${conteneurs.size} en marche")
            else Point("Conteneurs Docker", Niveau.ATTENTION, arretes.joinToString(", ") { "${it[0]} (${it[1].take(40)})" })
        return l
    }

    private fun sauvegardes(app: JarvisApp): List<Point> {
        val s = app.store
        val l = mutableListOf<Point>()
        val jour = 24 * 3_600_000L
        if (fr.dd.jarvis.coffre.Coffre.configure(app)) {
            val d = s.coffreDerniere.value.toLongOrNull() ?: 0L
            l += Point("Coffre-fort", when { s.coffreBilan.value.contains("❌") -> Niveau.ATTENTION; d == 0L || System.currentTimeMillis() - d > 2 * jour -> Niveau.ATTENTION; else -> Niveau.OK },
                (if (d > 0) "synchro ${ilYA(d)}" else "jamais synchronisé") + (s.coffreBilan.value.takeIf { it.contains("❌") }?.let { " — ${it.substringAfter("❌").trim().take(100)}" } ?: ""))
        }
        if (fr.dd.jarvis.societe.MiroirFreebox.actif(app)) {
            val d = s.miroirFreeboxDerniere.value.toLongOrNull() ?: 0L
            l += Point("Copie Freebox (société)", if (s.miroirFreeboxBilan.value.contains("❌") || (d > 0 && System.currentTimeMillis() - d > 2 * jour)) Niveau.ATTENTION else Niveau.OK,
                (if (d > 0) "dernière copie ${ilYA(d)}" else "pas encore faite") + (s.miroirFreeboxBilan.value.takeIf { it.contains("❌") }?.let { " — ${it.substringAfter("❌").trim().take(100)}" } ?: ""))
        }
        if (fr.dd.jarvis.coffre.Sauvegardes.actif(app)) {
            val d = s.sauvegardeDerniere.value.toLongOrNull() ?: 0L
            l += Point("Sauvegardes chiffrées", if (s.sauvegardeBilan.value.startsWith("❌") || d == 0L || System.currentTimeMillis() - d > 2 * jour) Niveau.ATTENTION else Niveau.OK,
                (if (d > 0) "dernière ${ilYA(d)}" else "aucune encore") + (s.sauvegardeBilan.value.takeIf { it.startsWith("❌") }?.let { " — ${it.take(120)}" } ?: ""))
        }
        return l
    }

    /** Lance toutes les vérifications en parallèle (20 s au plus chacune). */
    suspend fun bilan(app: JarvisApp): List<Point> = withContext(Dispatchers.IO) {
        coroutineScope {
            val taches: List<Pair<String, suspend () -> List<Point>>> = listOf(
                "Clés Gemini" to { listOf(gemini(app)) }, "Clés Groq" to { listOf(groq(app)) }, "Home Assistant" to { listOf(homeAssistant(app)) },
                "Plex" to { listOf(plex(app)) }, "Freebox" to { listOf(freebox(app)) }, "Discord" to { listOf(discord(app)) },
                "Serveur" to { serveur(app) }, "Sauvegardes" to { sauvegardes(app) })
            taches.map { (nom, f) -> async { withTimeoutOrNull(25_000) { runCatching { f() }.getOrElse { listOf(Point(nom, Niveau.PANNE, it.message ?: "erreur")) } }
                ?: listOf(Point(nom, Niveau.PANNE, "pas de réponse en 25 s")) } }.awaitAll().flatten()
        }
    }

    fun texte(points: List<Point>): String {
        val problemes = points.count { it.niveau == Niveau.PANNE || it.niveau == Niveau.ATTENTION }
        return "🩺 **Bilan de santé** — " + (if (problemes == 0) "tout va bien" else "$problemes point(s) à regarder") + "\n\n" +
            points.sortedBy { when (it.niveau) { Niveau.PANNE -> 0; Niveau.ATTENTION -> 1; Niveau.OK -> 2; Niveau.ABSENT -> 3 } }.joinToString("\n")
    }

    suspend fun executer(app: JarvisApp, notifier: Boolean): String {
        val p = bilan(app)
        val t = texte(p)
        app.store.santeDernier.set(t); app.store.santeQuand.set(System.currentTimeMillis().toString())
        val problemes = p.filter { it.niveau == Niveau.PANNE || it.niveau == Niveau.ATTENTION }
        if (notifier && problemes.isNotEmpty()) runCatching {
            val resume = problemes.joinToString("\n") { "${it.niveau.icone} ${it.nom} : ${it.detail.take(90)}" }
            val ouvrir = android.app.PendingIntent.getActivity(app, 7301, (app.packageManager.getLaunchIntentForPackage(app.packageName) ?: android.content.Intent())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            NotificationManagerCompat.from(app).notify(7301, NotificationCompat.Builder(app, JarvisApp.CHANNEL_AGENTS)
                .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("JARVIS — ${problemes.size} point(s) à regarder")
                .setContentText(problemes.first().let { "${it.nom} : ${it.detail.take(80)}" }).setStyle(NotificationCompat.BigTextStyle().bigText(resume))
                .setContentIntent(ouvrir).setAutoCancel(true).build())
        }
        return t
    }

    fun planifier(ctx: Context) {
        val app = ctx.applicationContext as? JarvisApp ?: return
        val wm = WorkManager.getInstance(ctx)
        if (!app.store.santeAuto.value) { wm.cancelUniqueWork("bilan_sante"); return }
        val maintenant = java.time.LocalDateTime.now()
        var cible = maintenant.toLocalDate().atTime(LocalTime.of(7, 30))
        if (!cible.isAfter(maintenant)) cible = cible.plusDays(1)
        wm.enqueueUniquePeriodicWork("bilan_sante", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BilanWorker>(24, TimeUnit.HOURS).setInitialDelay(java.time.Duration.between(maintenant, cible).toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    class BilanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as JarvisApp
            if (app.store.santeAuto.value) executer(app, notifier = true)
            return Result.success()
        }
    }
}
