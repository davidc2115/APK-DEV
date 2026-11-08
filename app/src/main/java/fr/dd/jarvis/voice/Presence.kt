package fr.dd.jarvis.voice

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sin

/**
 * État « vivant » de JARVIS pour l'avatar : écoute (niveau du micro, texte entendu), réflexion, parole (niveau réel
 * de la voix, phrase en cours). Alimenté par la voix et le cerveau, lu à chaque image par le dragon animé.
 */
object Presence {
    enum class Etat { REPOS, ECOUTE, REFLEXION, PAROLE }

    @Volatile var ecoute = false
    @Volatile var parle = false
    private val reflexions = AtomicInteger(0)
    /** Niveau du micro pendant l'écoute (0..1). */
    @Volatile var micro = 0f
    /** Texte entendu pendant l'écoute (résultat partiel). */
    @Volatile var entendu = ""
    /** Phrase que JARVIS est en train de dire (sous-titres du mode présence). */
    @Volatile var sousTitre = ""
    @Volatile private var source: (() -> Float)? = null

    val etat: Etat get() = when {
        parle -> Etat.PAROLE
        ecoute -> Etat.ECOUTE
        reflexions.get() > 0 -> Etat.REFLEXION
        else -> Etat.REPOS
    }

    fun debutReflexion() { reflexions.incrementAndGet() }
    fun finReflexion() { if (reflexions.decrementAndGet() < 0) reflexions.set(0) }

    /** Niveau de la voix en ce moment (0..1) : enveloppe réelle de l'audio joué, ou rythme simulé (voix Android). */
    fun niveau(): Float = if (!parle) 0f else runCatching { source?.invoke() }.getOrNull()?.coerceIn(0f, 1f) ?: 0f

    fun brancher(f: (() -> Float)?) { source = f }

    fun ecouteChange(actif: Boolean) {
        ecoute = actif
        if (!actif) micro = 0f else entendu = ""
    }

    /** Niveau du micro d'Android (rmsdB d'environ -2 à 10) ramené entre 0 et 1. */
    fun microDb(rmsdB: Float) { micro = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }

    fun parole(actif: Boolean) {
        parle = actif
        if (!actif) { source = null }
    }

    /** Rythme syllabique simulé (voix Android, dont l'audio n'est pas accessible). */
    internal fun simule(t: Double): Float = (0.25 + 0.75 * abs(sin(t * 11.0)) * (0.55 + 0.45 * abs(sin(t * 2.7)))).toFloat()

    /** Enveloppe d'un PCM 16 bits petit-boutiste : un niveau (0..1) par fenêtre de [fenetre] trames. */
    internal fun enveloppe(pcm: ByteArray, canaux: Int, fenetre: Int): FloatArray {
        val pas = 2 * canaux.coerceAtLeast(1)
        val trames = pcm.size / pas
        if (fenetre <= 0 || trames == 0) return FloatArray(0)
        val n = (trames + fenetre - 1) / fenetre
        val out = FloatArray(n)
        for (w in 0 until n) {
            var s = 0.0; var k = 0
            var f = w * fenetre
            val fin = minOf(trames, f + fenetre)
            while (f < fin) {
                val i = f * pas
                val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                s += v.toDouble() * v; k++; f++
            }
            val rms = if (k == 0) 0.0 else kotlin.math.sqrt(s / k) / 32768.0
            out[w] = (rms / 0.14).toFloat().coerceIn(0f, 1f)
        }
        return out
    }
}
