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

    private val DEMANDE = Regex("^(jarvis )?(deverrouille|debloque|deverouille|ouvre)( (le|mon) (telephone|portable|smartphone|ecran))?( stp| s il te plait)?$")

    /** « déverrouille », « débloque le téléphone »… (phrase courte, sans autre demande). */
    fun estDemande(texte: String) = DEMANDE.matches(fr.dd.jarvis.societe.core.normaliser(texte))

    fun verrouille(ctx: Context) = runCatching { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked }.getOrDefault(false)

    private fun shellPossible(ctx: Context) = PhoneShell.ready(ctx) || AdbShell.hasKey(ctx)
    private fun sh(ctx: Context, cmd: String) = runCatching { PhoneShell.exec(ctx, cmd) }.getOrElse { "Erreur : ${it.message}" }.trim()

    /** Code de l'écran de verrouillage accepté (chiffres ou lettres/chiffres, sans espace). */
    fun codeValide(code: String) = Regex("^[A-Za-z0-9]{4,32}$").matches(code)

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
        sh(app, "input text '$code'")
        delay(300)
        sh(app, "input keyevent 66")
        delay(1500)
        if (!verrouille(app)) "🔓 Téléphone déverrouillé." else {
            afficherDeverrouillage(app)
            "⚠️ Le code n'a pas été accepté (ou l'écran n'a pas répondu) : termine avec ton empreinte ou ton visage, et vérifie le code dans Réglages › Sécurité › Systèmes."
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
