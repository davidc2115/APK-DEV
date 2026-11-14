package fr.dd.jarvis.securite

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.AdbShell
import fr.dd.jarvis.device.PhoneShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Mode « téléphone sécurisé » : mise en veille + verrouillage de l'écran ; déverrouillage À LA VOIX, seulement après
 * vérification de l'empreinte vocale du propriétaire (voir JarvisService). Android n'offre aucune API de déverrouillage
 * aux applications : Jarvis saisit le code de l'écran de verrouillage par l'ADB intégré (ou Shizuku). Le code est
 * conservé dans le stockage chiffré du téléphone, jamais dans la conversation.
 */
object TelephoneSecurise {
    const val ACTION_DEVERROUILLER = "fr.dd.jarvis.DEVERROUILLER"

    private val VERBE = Regex("\\b(de ?v[ea]?r+ou+il+\\w*|debloqu\\w*|unlock\\w*)\\b")
    private val AUTRE_CHOSE = Regex("\\b(porte|portail|voiture|serrure|garage|ordinateur|pc|session|fichier|coffre|pdf|compte|carte|sim|appli|application|site|maison)\\b")

    /**
     * « déverrouille », « Jarvis, déverrouiller le téléphone », « déverrouille-toi », « débloque mon portable stp »…
     * Phrase courte qui ne vise pas autre chose (porte, voiture, fichier…).
     */
    fun estDemande(texte: String): Boolean {
        val t = fr.dd.jarvis.societe.core.normaliser(texte).removePrefix("jarvis ").trim()
        return t.split(' ').size <= 8 && VERBE.containsMatchIn(t) && !AUTRE_CHOSE.containsMatchIn(t)
    }

    /** Schéma enregistré (« schema:15963 » = points 1 à 9 comme un clavier de téléphone), sinon null. */
    fun schema(code: String): List<Int>? = code.takeIf { it.startsWith("schema:") }?.removePrefix("schema:")?.map { it - '0' }?.takeIf { l -> l.size >= 4 && l.all { it in 1..9 } && l.distinct().size == l.size }

    fun verrouille(ctx: Context) = runCatching { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked }.getOrDefault(false)

    private fun shellPossible(ctx: Context) = PhoneShell.ready(ctx) || AdbShell.hasKey(ctx)
    private fun sh(ctx: Context, cmd: String) = runCatching { PhoneShell.exec(ctx, cmd) }.getOrElse { "Erreur : ${it.message}" }.trim()

    /** Code de l'écran de verrouillage accepté (chiffres ou lettres/chiffres, sans espace), ou schéma « schema:… ». */
    fun codeValide(code: String) = Regex("^[A-Za-z0-9]{4,32}$").matches(code) || schema(code) != null

    /**
     * Grille du schéma à l'écran (gauche, haut, droite, bas) : relevée dans l'interface de l'écran de verrouillage
     * (uiautomator), sinon position habituelle (carré centré dans le bas de l'écran).
     */
    private fun grille(ctx: Context, l: Int, h: Int): IntArray? = grilleUiautomator(ctx)?.first

    /** Grille lue par uiautomator (ADB / Shizuku) : voit toute la hiérarchie de l'écran de verrouillage. */
    private fun grilleUiautomator(ctx: Context): Pair<IntArray, String>? {
        repeat(2) {
            val xml = sh(ctx, "uiautomator dump /sdcard/jarvis_ui.xml >/dev/null 2>&1; cat /sdcard/jarvis_ui.xml; rm -f /sdcard/jarvis_ui.xml")
            val noeuds = noeudsUiautomator(xml)
            choisirGrille(noeuds)?.let { return it.first to (it.second + " — uiautomator") }
            if (noeuds.isNotEmpty()) dernierDump = resumeNoeuds(noeuds)
        }
        return null
    }

    @Volatile private var dernierDump = ""

    /** Centre d'un point (1 à 9, 1 = en haut à gauche) dans la grille [g] (gauche, haut, droite, bas). */
    internal fun centre(d: Int, g: IntArray): Pair<Int, Int> {
        val r = (d - 1) / 3; val c = (d - 1) % 3
        return (g[0] + (c * 2 + 1) * (g[2] - g[0]) / 6) to (g[1] + (r * 2 + 1) * (g[3] - g[1]) / 6)
    }

    /** Trace du schéma au doigt simulé (input motionevent), points intermédiaires pour un geste continu (repli sans accessibilité). */
    internal fun traceSchema(points: List<Int>, g: IntArray): String {
        val sb = StringBuilder()
        val depart = centre(points.first(), g)
        sb.append("input motionevent DOWN ${depart.first} ${depart.second}")
        var avant = depart
        for (d in points.drop(1)) {
            val cible = centre(d, g)
            for (k in 1..4) sb.append("; input motionevent MOVE ${avant.first + (cible.first - avant.first) * k / 4} ${avant.second + (cible.second - avant.second) * k / 4}")
            avant = cible
        }
        sb.append("; input motionevent UP ${avant.first} ${avant.second}")
        return sb.toString()
    }

    /** Grille relevée (« g,h,d,b ») valide, ou null : à peu près carrée (une grille de schéma l'est toujours). */
    internal fun grilleEnregistree(s: String): IntArray? = s.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 4 }
        ?.toIntArray()?.takeIf { estGrille(it) }

    private fun estGrille(g: IntArray): Boolean {
        val w = g[2] - g[0]; val h = g[3] - g[1]
        return w > 200 && h > 200 && h.toFloat() / w in 0.75f..1.33f
    }

    /** Élément de l'écran (accessibilité ou uiautomator) : identifiant, classe, description, limites. */
    data class Noeud(val id: String, val cls: String, val desc: String, val l: Int, val t: Int, val r: Int, val b: Int) {
        val w get() = r - l
        val h get() = b - t
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
    }

    private fun motifSchema(n: Noeud): Boolean {
        val id = n.id.lowercase(); val cls = n.cls.lowercase()
        return (id.contains("pattern") && !id.contains("text") && !id.contains("title") && !id.contains("message")) ||
            cls.endsWith("lockpatternview") || cls.contains("patternview")
    }

    /** Valeurs regroupées à [tol] près : centres des groupes. */
    private fun groupes(v: List<Int>, tol: Int): List<Int> {
        val out = mutableListOf<MutableList<Int>>()
        for (x in v.sorted()) { val g = out.lastOrNull(); if (g != null && x - g.last() <= tol) g += x else out += mutableListOf(x) }
        return out.map { it.average().toInt() }
    }

    /**
     * Grille du schéma à partir des éléments de l'écran : (gauche, haut, droite, bas) et la façon dont elle a été trouvée.
     *  1) les 9 points eux-mêmes (petits carrés alignés 3 × 3) → position exacte ;
     *  2) la vue du schéma CARRÉE la plus petite (pas le conteneur plein écran, qui faisait tracer dans le vide) ;
     *  3) sinon null (on ne devine pas : chaque essai raté compte comme une tentative de déverrouillage).
     */
    internal fun choisirGrille(noeuds: List<Noeud>): Pair<IntArray, String>? {
        val zones = noeuds.filter { motifSchema(it) && it.w > 200 && it.h > 200 }
        // 1) Points du schéma (descendants des zones du schéma, sinon n'importe où) : 9 petits carrés de même taille.
        for (zone in zones.sortedBy { it.w.toLong() * it.h } + listOf<Noeud?>(null)) {
            val dedans = noeuds.filter { n ->
                n.w in 30..600 && n.h in 30..600 && kotlin.math.abs(n.w - n.h) <= maxOf(n.w, n.h) / 3 &&
                    (zone == null || (n.l >= zone.l - 5 && n.r <= zone.r + 5 && n.t >= zone.t - 5 && n.b <= zone.b + 5)) && n !== zone
            }
            for ((_, memeTaille) in dedans.groupBy { it.w / 20 }) {
                if (memeTaille.size < 9) continue
                val xs = groupes(memeTaille.map { it.cx }, memeTaille[0].w / 3)
                val ys = groupes(memeTaille.map { it.cy }, memeTaille[0].h / 3)
                if (xs.size == 3 && ys.size == 3) {
                    val pasX = (xs[2] - xs[0]) / 2; val pasY = (ys[2] - ys[0]) / 2
                    if (pasX > 40 && pasY > 40) return intArrayOf(xs[0] - pasX / 2, ys[0] - pasY / 2, xs[2] + pasX / 2, ys[2] + pasY / 2) to "les 9 points du schéma"
                }
            }
        }
        // 2) Vue du schéma à peu près carrée, la plus petite.
        zones.filter { it.h.toFloat() / it.w in 0.75f..1.33f }.minByOrNull { it.w.toLong() * it.h }?.let { return intArrayOf(it.l, it.t, it.r, it.b) to "vue du schéma (${it.cls.substringAfterLast('.')})" }
        return null
    }

    /** Éléments lus dans un fichier uiautomator (dump XML). */
    internal fun noeudsUiautomator(xml: String): List<Noeud> =
        Regex("<node\\b[^>]*>").findAll(xml).mapNotNull { m ->
            val n = m.value
            fun attr(k: String) = Regex("\\b$k=\"([^\"]*)\"").find(n)?.groupValues?.get(1).orEmpty()
            val b = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(attr("bounds"))?.groupValues ?: return@mapNotNull null
            Noeud(attr("resource-id"), attr("class"), attr("content-desc").ifBlank { attr("text") }, b[1].toInt(), b[2].toInt(), b[3].toInt(), b[4].toInt())
        }.toList()

    /** Résumé des éléments de l'écran de verrouillage pour le journal (diagnostic, sans contenu privé). */
    internal fun resumeNoeuds(noeuds: List<Noeud>): String = noeuds.filter { it.w > 0 && it.h > 0 && (it.id.isNotBlank() || it.cls.isNotBlank()) }
        .sortedByDescending { if (motifSchema(it)) 1 else 0 }.take(18)
        .joinToString(" | ") { "${it.cls.substringAfterLast('.')}${it.id.substringAfterLast('/').takeIf { i -> i.isNotBlank() }?.let { i -> "#$i" } ?: ""}[${it.l},${it.t},${it.r},${it.b}]" }

    /**
     * Trace le schéma par l'ACCESSIBILITÉ : un seul geste continu, doigt posé du premier au dernier point, sur la grille
     * réellement affichée (lue à l'écran, sinon relevée lors d'un précédent affichage de l'écran de verrouillage).
     * Faux si l'accessibilité de JARVIS n'est pas active ou si le geste n'a pas été exécuté.
     */
    private suspend fun tracerAccessibilite(app: JarvisApp, points: List<Int>): Boolean {
        val a = fr.dd.jarvis.service.JarvisAccessibilityService.instance ?: return false
        val g = a.grilleSchema()?.also { app.store.schemaGrille.set(it.joinToString(",")) } ?: grilleEnregistree(app.store.schemaGrille.value) ?: return false
        return tracerSur(a, points, g, 160, 0)
    }

    private fun journal(t: String) = runCatching { fr.dd.jarvis.voice.JournalVoix.noter("déverrouillage", t) }

    /** Trace le schéma sur la grille [g] ; vrai si Android a exécuté le geste en entier. */
    private suspend fun tracerSur(a: fr.dd.jarvis.service.JarvisAccessibilityService, points: List<Int>, g: IntArray, msParSegment: Long, maintienMs: Long): Boolean {
        val pts = points.map { centre(it, g).let { (x, y) -> x.toFloat() to y.toFloat() } }
        val fait = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val lance = withContext(Dispatchers.Main) { a.tracer(pts, msParSegment, maintienMs) { ok -> fait.complete(ok) } }
        return lance && (kotlinx.coroutines.withTimeoutOrNull(12_000) { fait.await() } ?: false)
    }

    /** Attend que la grille du schéma soit réellement affichée (lue à l'écran), [ms] au plus. */
    private suspend fun attendreGrille(a: fr.dd.jarvis.service.JarvisAccessibilityService, ms: Long): IntArray? {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            withContext(Dispatchers.Main) { runCatching { a.grilleSchema() }.getOrNull() }?.let { return it }
            delay(250)
        }
        return null
    }

    /**
     * Déverrouillage par le SCHÉMA, par l'accessibilité (avec ou sans ADB) :
     * 1) réveil + demande de déverrouillage ; 2) si la grille n'apparaît pas (écran HyperOS / MIUI « glisser vers le haut »),
     * glissé vers le haut ; 3) attente de la grille RÉELLEMENT affichée ; 4) doigt posé puis tracé continu ; 5) contrôle,
     * puis un 2e essai plus lent sur la grille relue. Chaque étape est notée dans le journal de la voix (Réglages › Voix).
     */
    private suspend fun deverrouillerSchema(app: JarvisApp, points: List<Int>): String? {
        val a = fr.dd.jarvis.service.JarvisAccessibilityService.instance ?: return null
        journal("schéma : réveil et affichage du déverrouillage")
        afficherDeverrouillage(app)
        var g = attendreGrille(a, 2500)
        if (g == null) {
            val m = app.resources.displayMetrics
            journal("schéma : grille pas encore affichée → glissé vers le haut")
            withContext(Dispatchers.Main) { a.swipe(m.widthPixels / 2f, m.heightPixels * 0.90f, m.widthPixels / 2f, m.heightPixels * 0.35f, 320) }
            g = attendreGrille(a, 3000)
        }
        // L'accessibilité ne voit pas la vraie grille (seulement le conteneur plein écran) : uiautomator (ADB) la voit.
        if (g == null && shellPossible(app)) g = grilleUiautomator(app)?.also { journal("schéma : grille trouvée par uiautomator (${it.second})") }?.first
        val lue = g != null
        if (g == null) g = grilleEnregistree(app.store.schemaGrille.value)
        if (g == null) {
            journal("schéma : grille introuvable — écran vu par l'accessibilité : " + withContext(Dispatchers.Main) { runCatching { resumeNoeuds(a.noeudsEcran()) }.getOrDefault("?") }.take(900))
            return "⚠️ Je ne trouve pas la grille du schéma à l'écran. Affiche une fois toi-même l'écran du schéma (JARVIS relève sa position), puis redemande. " +
                "Vérifie aussi que l'accessibilité de JARVIS est bien active (Réglages Android › Accessibilité › JARVIS)."
        }
        app.store.schemaGrille.set(g.joinToString(","))
        journal("schéma : grille ${if (lue) "lue à l'écran" else "enregistrée (non lue)"} [${g.joinToString(",")}], ${points.size} points")
        for ((i, reglage) in listOf(220L to 140L, 420L to 260L).withIndex()) {
            val ok = tracerSur(a, points, g!!, reglage.first, reglage.second)
            journal("schéma : geste ${if (ok) "exécuté" else "refusé ou interrompu par Android"} (essai ${i + 1})")
            delay(1500)
            if (!verrouille(app)) { journal("schéma : téléphone déverrouillé ✓"); return "🔓 Téléphone déverrouillé." }
            // Après un essai refusé, la grille peut s'être déplacée (message « schéma incorrect ») : relue.
            g = attendreGrille(a, 1500) ?: g
            if (i == 0) journal("schéma : écran après l'essai 1 — " + withContext(Dispatchers.Main) { runCatching { resumeNoeuds(a.noeudsEcran()) }.getOrDefault("?") }.take(700))
        }
        journal("schéma : toujours verrouillé après 2 essais")
        return "⚠️ Le schéma a été tracé mais le téléphone est resté verrouillé. Vérifie dans Réglages › Sécurité › Systèmes que le schéma enregistré est " +
            "EXACTEMENT celui de l'écran (mêmes points, même ordre, même point de départ). Le détail de chaque étape est dans le journal de la voix."
    }

    /** Mise en veille + verrouillage. */
    suspend fun verrouiller(app: JarvisApp): String = withContext(Dispatchers.IO) {
        if (AntiVol.verrouiller(app)) return@withContext "📴 Écran verrouillé et téléphone en veille."
        if (shellPossible(app)) { sh(app, "input keyevent 223"); delay(800); if (verrouille(app)) return@withContext "📴 Téléphone mis en veille (écran verrouillé)." }
        "⚠️ Impossible de verrouiller l'écran : active l'accessibilité de JARVIS ou l'administrateur de l'appareil (Réglages › Sécurité › Anti-vol)."
    }

    /**
     * Déverrouille (à appeler UNIQUEMENT après vérification de la voix du propriétaire). Sans code enregistré ou sans
     * ADB, réveille l'écran et affiche la saisie empreinte / visage / code.
     */
    suspend fun deverrouiller(app: JarvisApp): String = withContext(Dispatchers.IO) {
        if (!verrouille(app)) return@withContext "🔓 Le téléphone est déjà déverrouillé."
        val code = runCatching { app.store.codeDeverrouillage.value }.getOrDefault("")
        // Schéma sans ADB : l'accessibilité suffit (affichage de la saisie par JARVIS, puis geste continu sur la grille).
        val pointsSchema = schema(code)?.let { fr.dd.jarvis.ui.SchemaDessinLogique.completer(it) }
        // Schéma : toujours par l'accessibilité d'abord (même avec ADB/Shizuku : sur HyperOS / MIUI, l'injection « input »
        // est refusée sans « Débogage USB (paramètres de sécurité) »).
        if (pointsSchema != null) deverrouillerSchema(app, pointsSchema)?.let { r ->
            if (!verrouille(app) || !shellPossible(app)) return@withContext r
        }
        if (code.isBlank() || !codeValide(code) || !shellPossible(app)) {
            afficherDeverrouillage(app)
            return@withContext if (code.isBlank()) "🔓 Écran réveillé : termine avec ton empreinte ou ton visage. (Pour un déverrouillage complet à la voix, enregistre le code de l'écran dans Réglages › Sécurité › Systèmes.)"
                else "🔓 Écran réveillé : termine avec ton empreinte ou ton visage (l'ADB intégré ou Shizuku n'est pas actif)."
        }
        sh(app, "input keyevent 224")          // réveil
        delay(500)
        val taille = Regex("(\\d{3,5})x(\\d{3,5})").findAll(sh(app, "wm size")).lastOrNull()
        val l = taille?.groupValues?.get(1)?.toIntOrNull() ?: 1080
        val h = taille?.groupValues?.get(2)?.toIntOrNull() ?: 2400
        sh(app, "input swipe ${l / 2} ${(h * 0.85).toInt()} ${l / 2} ${(h * 0.25).toInt()} 250")   // fait apparaître la saisie du code
        delay(700)
        val points = schema(code)?.let { fr.dd.jarvis.ui.SchemaDessinLogique.completer(it) }
        if (points != null) {
            // 1) Geste continu par l'accessibilité sur la vraie grille ; 2) repli : commandes « input » sur la grille lue ou relevée.
            val g = grille(app, l, h) ?: grilleEnregistree(app.store.schemaGrille.value)
            if (g == null) {
                journal("schéma (ADB) : grille introuvable — écran : ${dernierDump.take(900)}")
                afficherDeverrouillage(app)
                return@withContext "⚠️ Je ne trouve pas la grille du schéma à l'écran (le détail est dans le journal de la voix) : termine avec ton empreinte ou ton visage."
            }
            journal("schéma (ADB) : grille [${g.joinToString(",")}] → tracé par « input »")
            sh(app, traceSchema(points, g))
        }
        else {
            sh(app, "input text '$code'")
            delay(300)
            sh(app, "input keyevent 66")
        }
        delay(1500)
        if (!verrouille(app)) "🔓 Téléphone déverrouillé." else {
            afficherDeverrouillage(app)
            "⚠️ Le ${if (points != null) "schéma" else "code"} n'a pas été accepté (ou l'écran n'a pas répondu) : termine avec ton empreinte ou ton visage, et vérifie-le dans Réglages › Sécurité › Systèmes." +
                (if (points != null) " Sur Xiaomi, l'ADB ne peut toucher l'écran que si « Débogage USB (paramètres de sécurité) » est activé dans les options pour les développeurs." else "")
        }
    }

    /** Réveille l'écran et affiche la demande de déverrouillage du système (empreinte, visage ou code). */
    fun afficherDeverrouillage(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(ctx, fr.dd.jarvis.MainActivity::class.java).setAction(ACTION_DEVERROUILLER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}
