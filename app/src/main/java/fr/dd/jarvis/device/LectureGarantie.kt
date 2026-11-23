package fr.dd.jarvis.device

import android.content.Context
import android.os.SystemClock

/**
 * Musique lancée par JARVIS : elle doit JOUER une fois la conversation terminée.
 *
 * Pendant l'échange, la voix de JARVIS et son micro prennent le focus audio : le lecteur qui vient de s'ouvrir (YT Music
 * Morphe…) se met en pause, ou n'arrive pas à démarrer, et ne repart pas toujours seul. JARVIS note donc la lecture
 * demandée ; sa voix ne fait alors que BAISSER la musique (au lieu de l'arrêter), il ne rouvre pas le micro derrière, et
 * à la fin de la conversation il vérifie que la musique joue — sinon il relance la lecture.
 */
object LectureGarantie {
    @Volatile private var paquet: String? = null
    @Volatile private var depuis = 0L
    /** Horloge (remplaçable dans les tests). */
    internal var horloge: () -> Long = { SystemClock.elapsedRealtime() }

    /** Lecture de [pkg] demandée à l'instant. */
    fun noter(pkg: String) { paquet = pkg; depuis = horloge() }

    /** « Pause », « stop » : la musique ne doit plus être relancée. */
    fun oublier() { paquet = null }

    /** Musique lancée par JARVIS il y a moins de [ms] (sa voix doit la baisser, pas l'arrêter). */
    fun recente(ms: Long = 45_000): Boolean = paquet != null && horloge() - depuis < ms

    /** Paquet à vérifier (lancement de moins de 2 minutes), consommé : une seule vérification par lancement. */
    @Synchronized internal fun aVerifier(maintenant: Long = horloge()): String? {
        val p = paquet ?: return null
        paquet = null
        return p.takeIf { maintenant - depuis < 120_000 }
    }

    /**
     * La musique n'a pas démarré pendant la demande : vérifiée de nouveau dès que plus aucune voix de JARVIS ne parle ni
     * n'écoute (demande faite depuis l'écran Android Auto, sans conversation vocale qui se termine). Si la conversation se
     * termine avant, c'est elle qui vérifie (une seule vérification par lancement).
     */
    fun surveiller(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            runCatching {
                Thread.sleep(3000)
                var n = 0
                while (n < 20 && fr.dd.jarvis.voice.VoiceManager.occupeQuelquePart()) { Thread.sleep(2000); n++ }
                Thread.sleep(800)
                if (!fr.dd.jarvis.voice.VoiceManager.occupeQuelquePart()) verifier(app)
            }
        }.apply { isDaemon = true; name = "jarvis-lecture-surveillance" }.start()
    }

    /** Fin de conversation : la musique demandée doit jouer ; sinon JARVIS relance la lecture (hors du fil principal). */
    fun verifier(ctx: Context) {
        val p = aVerifier() ?: return
        val app = ctx.applicationContext as? fr.dd.jarvis.JarvisApp ?: return
        Thread {
            runCatching { app.phone.forcerLecture(p, "fin de la conversation") }
                .onFailure { fr.dd.jarvis.voice.JournalVoix.noter("musique", "relance après la conversation impossible : ${it.message}") }
        }.apply { isDaemon = true; name = "jarvis-lecture" }.start()
    }
}
