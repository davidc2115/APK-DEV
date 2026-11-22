package fr.dd.jarvis.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Traitement audio des messages vocaux (PCM 16 bits mono), sans dépendance Android :
 * conversion, rééchantillonnage, effets de personnage (voix plus aiguë ou plus grave, écho magique, robot),
 * clochettes de Noël, normalisation et écriture WAV.
 */
object AudioFx {
    /** Fréquence commune des messages (celle de la synthèse Gemini). */
    const val FREQ = 24_000

    fun versShorts(pcm: ByteArray): ShortArray =
        ShortArray(pcm.size / 2) { i -> ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort() }

    fun versOctets(s: ShortArray): ByteArray = ByteArray(s.size * 2).also { b ->
        s.forEachIndexed { i, v -> b[2 * i] = (v.toInt() and 0xFF).toByte(); b[2 * i + 1] = (v.toInt() shr 8).toByte() }
    }

    /** Stéréo entrelacée → mono (moyenne des canaux). */
    fun mono(s: ShortArray, canaux: Int): ShortArray =
        if (canaux <= 1) s else ShortArray(s.size / canaux) { i -> (0 until canaux).sumOf { s[i * canaux + it].toInt() }.div(canaux).toShort() }

    private fun lire(s: ShortArray, pos: Double): Double {
        val i = pos.toInt()
        if (i >= s.size - 1) return s.lastOrNull()?.toDouble() ?: 0.0
        val f = pos - i
        return s[i] * (1 - f) + s[i + 1] * f
    }

    private fun borne(v: Double): Short = v.coerceIn(-32768.0, 32767.0).toInt().toShort()

    /** Changement de fréquence d'échantillonnage (interpolation linéaire). */
    fun reechantillonner(s: ShortArray, de: Int, vers: Int): ShortArray {
        if (de == vers || s.isEmpty()) return s
        val pas = de.toDouble() / vers
        return ShortArray((s.size / pas).toInt()) { i -> borne(lire(s, i * pas)) }
    }

    /**
     * Voix plus aiguë et plus rapide ([facteur] > 1, petit lutin) ou plus grave et plus lente (< 1, Père Noël, géant) :
     * lecture accélérée ou ralentie, comme une bande magnétique.
     */
    fun vitesse(s: ShortArray, facteur: Double): ShortArray {
        if (abs(facteur - 1.0) < 1e-3 || s.isEmpty()) return s
        return ShortArray((s.size / facteur).toInt()) { i -> borne(lire(s, i * facteur)) }
    }

    /** Réverbération (Schroeder : 4 filtres en peigne amortis + 2 passe-tout), avec la traîne. [dosage] 0..1. */
    fun reverb(s: ShortArray, dosage: Double, freq: Int = FREQ): ShortArray {
        if (dosage <= 0.0 || s.isEmpty()) return s
        val traine = (freq * (0.4 + dosage)).toInt()
        val n = s.size + traine
        val x = DoubleArray(n) { if (it < s.size) s[it] / 32768.0 else 0.0 }
        val humide = DoubleArray(n)
        for (ms in doubleArrayOf(29.7, 37.1, 41.1, 43.7)) {
            val d = (ms * freq / 1000).toInt(); val buf = DoubleArray(d); var idx = 0; var filtre = 0.0
            val retour = 0.70 + 0.12 * dosage
            for (i in 0 until n) {
                val sortie = buf[idx]
                filtre = sortie * 0.6 + filtre * 0.4
                buf[idx] = x[i] + filtre * retour
                idx = (idx + 1) % d
                humide[i] += sortie / 4
            }
        }
        for (ms in doubleArrayOf(5.0, 1.7)) {
            val d = (ms * freq / 1000).toInt().coerceAtLeast(1); val buf = DoubleArray(d); var idx = 0
            for (i in 0 until n) {
                val b = buf[idx]; val entree = humide[i]
                val sortie = -0.7 * entree + b
                buf[idx] = entree + 0.7 * sortie
                idx = (idx + 1) % d
                humide[i] = sortie
            }
        }
        val sec = 1.0 - 0.35 * dosage
        return ShortArray(n) { i -> borne((x[i] * sec + humide[i] * dosage * 1.4) * 32767) }
    }

    /** Voix de robot : modulation en anneau (son métallique) + petit écho court. */
    fun robot(s: ShortArray, freq: Int = FREQ): ShortArray {
        if (s.isEmpty()) return s
        val d = (0.006 * freq).toInt()
        val out = DoubleArray(s.size)
        for (i in s.indices) {
            val x = s[i].toDouble()
            val anneau = x * sin(2 * PI * 55.0 * i / freq)
            out[i] = 0.55 * anneau + 0.45 * x + if (i >= d) 0.45 * out[i - d] else 0.0
        }
        return ShortArray(s.size) { borne(out[it] * 0.8) }
    }

    /** Crête ramenée à [crete] (pleine échelle = 1) ; un message trop faible est remonté (×4 au plus). */
    fun normaliser(s: ShortArray, crete: Double = 0.89): ShortArray {
        val max = s.maxOfOrNull { abs(it.toInt()) } ?: 0
        if (max == 0) return s
        val g = (crete * 32767 / max).coerceAtMost(4.0)
        return ShortArray(s.size) { borne(s[it] * g) }
    }

    /** Fondu d'entrée et de sortie (évite les clics entre les répliques). */
    fun fondu(s: ShortArray, ms: Int = 12, freq: Int = FREQ): ShortArray {
        val n = (ms * freq / 1000).coerceAtMost(s.size / 2)
        if (n <= 0) return s
        val out = s.copyOf()
        for (i in 0 until n) {
            val g = i.toDouble() / n
            out[i] = (out[i] * g).toInt().toShort()
            out[s.size - 1 - i] = (out[s.size - 1 - i] * g).toInt().toShort()
        }
        return out
    }

    fun silence(ms: Int, freq: Int = FREQ) = ShortArray((ms.toLong() * freq / 1000).toInt())

    /**
     * Clochettes de traîneau, générées (aucun fichier son) : tintements métalliques (partiels inharmoniques de cloche)
     * et léger grelot, qui s'éteignent sur la fin. Toujours les mêmes pour une même [graine].
     */
    fun clochettes(ms: Int = 1400, freq: Int = FREQ, graine: Int = 7): ShortArray {
        val n = (ms.toLong() * freq / 1000).toInt()
        val out = DoubleArray(n)
        val r = Random(graine)
        val notes = doubleArrayOf(2093.0, 2349.3, 2637.0, 2793.8, 3136.0)
        var t = 0
        while (t < n - freq / 10) {
            val f0 = notes[r.nextInt(notes.size)] * (0.99 + r.nextDouble() * 0.02)
            val force = 0.5 + r.nextDouble() * 0.5
            val duree = (freq * 0.35).toInt()
            for (k in 0 until duree) {
                val i = t + k; if (i >= n) break
                val tt = k.toDouble() / freq
                val env = exp(-tt / 0.09) * force
                val son = sin(2 * PI * f0 * tt) + 0.5 * sin(2 * PI * f0 * 2.76 * tt) + 0.22 * sin(2 * PI * f0 * 5.4 * tt)
                val grelot = if (k < freq / 60) (r.nextDouble() * 2 - 1) * 0.6 else 0.0
                out[i] += (son * 0.55 + grelot) * env
            }
            t += (freq * (0.06 + r.nextDouble() * 0.07)).toInt()
        }
        // Extinction progressive sur le dernier tiers.
        val debutFin = n * 2 / 3
        return ShortArray(n) { i ->
            val g = if (i < debutFin) 1.0 else 1.0 - (i - debutFin).toDouble() / (n - debutFin)
            borne(out[i] * g * 0.16 * 32767)
        }
    }

    fun concatener(parts: List<ShortArray>): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var p = 0
        parts.forEach { it.copyInto(out, p); p += it.size }
        return out
    }

    fun dureeMs(s: ShortArray, freq: Int = FREQ): Long = s.size * 1000L / freq

    /** Fichier WAV (PCM 16 bits mono). */
    fun wav(s: ShortArray, freq: Int = FREQ): ByteArray {
        val donnees = versOctets(s)
        val b = java.nio.ByteBuffer.allocate(44 + donnees.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + donnees.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(freq).putInt(freq * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(donnees.size).put(donnees)
        return b.array()
    }
}
