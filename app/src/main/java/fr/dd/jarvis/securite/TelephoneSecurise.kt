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
    private fun grille(ctx: Context, l: Int, h: Int): IntArray {
        repeat(2) {
            val xml = sh(ctx, "uiautomator dump /sdcard/jarvis_ui.xml >/dev/null 2>&1; cat /sdcard/jarvis_ui.xml; rm -f /sdcard/jarvis_ui.xml")
            Regex("<node[^>]*resource-id=\"[^\"]*(lockPatternView|lock_pattern|pattern)[^\"]*\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"", RegexOption.IGNORE_CASE)
                .find(xml)?.let { m -> return intArrayOf(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt()) }
        }
        val cote = (l * 0.75).toInt()
        val cy = (h * 0.66).toInt()
        return intArrayOf((l - cote) / 2, cy - cote / 2, (l + cote) / 2, cy + cote / 2)
    }

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

    /** Grille relevée (« g,h,d,b ») valide, ou null. */
    internal fun grilleEnregistree(s: String): IntArray? = s.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 4 }
        ?.toIntArray()?.takeIf { it[2] - it[0] > 200 && it[3] - it[1] > 200 }

    /**
     * Trace le schéma par l'ACCESSIBILITÉ : un seul geste continu, doigt posé du premier au dernier point, sur la grille
     * réellement affichée (lue à l'écran, sinon relevée lors d'un précédent affichage de l'écran de verrouillage).
     * Faux si l'accessibilité de JARVIS n'est pas active ou si le geste n'a pas été exécuté.
     */
    private suspend fun tracerAccessibilite(app: JarvisApp, points: List<Int>): Boolean {
        val a = fr.dd.jarvis.service.JarvisAccessibilityService.instance ?: return false
        val g = a.grilleSchema()?.also { app.store.schemaGrille.set(it.joinToString(",")) } ?: grilleEnregistree(app.store.schemaGrille.value) ?: return false
        val pts = points.map { centre(it, g).let { (x, y) -> x.toFloat() to y.toFloat() } }
        val fait = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val lance = withContext(Dispatchers.Main) { a.tracer(pts) { ok -> fait.complete(ok) } }
        return lance && (kotlinx.coroutines.withTimeoutOrNull(8000) { fait.await() } ?: false)
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
        if (pointsSchema != null && !shellPossible(app) && fr.dd.jarvis.service.JarvisAccessibilityService.instance != null) {
            afficherDeverrouillage(app)
            delay(1300)
            val ok = tracerAccessibilite(app, pointsSchema)
            delay(1200)
            return@withContext if (!verrouille(app)) "🔓 Téléphone déverrouillé." else if (!ok)
                "⚠️ Je n'ai pas pu tracer le schéma : affiche une fois l'écran de verrouillage avec la grille du schéma (JARVIS relève alors sa position), puis redemande."
            else "⚠️ Le schéma tracé n'a pas été accepté : retrace-le dans Réglages › Sécurité › Systèmes (exactement comme sur l'écran de verrouillage)."
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
            if (!tracerAccessibilite(app, points)) sh(app, traceSchema(points, grilleEnregistree(app.store.schemaGrille.value) ?: grille(app, l, h)))
        }
        else {
            sh(app, "input text '$code'")
            delay(300)
            sh(app, "input keyevent 66")
        }
        delay(1500)
        if (!verrouille(app)) "🔓 Téléphone déverrouillé." else {
            afficherDeverrouillage(app)
            "⚠️ Le ${if (points != null) "schéma" else "code"} n'a pas été accepté (ou l'écran n'a pas répondu) : termine avec ton empreinte ou ton visage, et vérifie-le dans Réglages › Sécurité › Systèmes."
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
