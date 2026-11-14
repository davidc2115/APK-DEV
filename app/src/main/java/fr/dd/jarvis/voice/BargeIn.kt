package fr.dd.jarvis.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Détection de prise de parole trame par trame (20 ms), indépendante d'Android pour pouvoir être testée.
 * Le seuil s'adapte au bruit de fond ET à l'écho résiduel de la voix de JARVIS (ce que le micro entend encore du
 * haut-parleur après l'annulation d'écho) : seule une voix nettement au-dessus de cet écho, pendant ~200 ms sur 300 ms,
 * déclenche — pas un éclat isolé, ni la voix de JARVIS elle-même.
 */
class DetecteurParole(
    private val echauffement: Int = 20,          // 400 ms pour mesurer l'écho de JARVIS et le bruit de fond
    private val minimum: Double = 420.0,         // niveau absolu minimal d'une voix proche (PCM 16 bits)
    private val ratioBruit: Double = 3.5,
    private val ratioEcho: Double = 1.8,
    private val fenetre: Int = 15,               // 300 ms
    private val requis: Int = 10                 // dont ≈ 200 ms de voix
) {
    private var n = 0
    private val chauffe = ArrayList<Double>()
    private var plancher = 0.0
    private var echo = 0.0
    private val voix = BooleanArray(fenetre)

    /** Ajoute une trame (niveau RMS, taux de passage par zéro) ; vrai quand l'utilisateur prend la parole. */
    fun trame(rms: Double, zcr: Double): Boolean {
        n++
        if (n <= echauffement) {
            chauffe += rms
            if (n == echauffement) {
                val tri = chauffe.sorted()
                plancher = max(tri.first(), 30.0)
                echo = tri[((tri.size - 1) * 0.85).toInt()]
            }
            return false
        }
        val seuil = max(minimum, max(plancher * ratioBruit, echo * ratioEcho))
        val estVoix = rms > seuil && zcr in 0.01..0.4
        if (!estVoix) {
            // Enveloppe haute de l'écho (monte vite, redescend lentement) et bruit de fond (lent).
            echo = if (rms > echo) 0.8 * echo + 0.2 * rms else 0.99 * echo + 0.01 * rms
            plancher = if (rms < plancher) 0.7 * plancher + 0.3 * rms else 0.995 * plancher + 0.005 * rms
        }
        voix[n % fenetre] = estVoix
        return voix.count { it } >= requis
    }

    companion object {
        /** Niveau RMS et taux de passage par zéro d'une trame PCM 16 bits. */
        fun mesurer(buf: ShortArray, len: Int): Pair<Double, Double> {
            if (len <= 0) return 0.0 to 0.0
            var s = 0.0; var z = 0
            for (i in 0 until len) {
                val v = buf[i].toDouble(); s += v * v
                if (i > 0 && (buf[i] >= 0) != (buf[i - 1] >= 0)) z++
            }
            return sqrt(s / len) to z.toDouble() / len
        }
    }
}

/**
 * Détecte que l'utilisateur se remet à parler pendant que JARVIS parle (« couper la parole »).
 * Micro en mode communication (annulation d'écho et réduction de bruit du téléphone) ; à démarrer quand la voix de
 * JARVIS commence réellement (les 400 premières ms servent à mesurer son écho).
 */
class BargeIn(private val onVoix: () -> Unit) {
    @Volatile private var run = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (run) return
        run = true
        thread = Thread {
            var rec: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            try {
                val rate = 16000
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
                if (rec.state != AudioRecord.STATE_INITIALIZED) return@Thread
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
                rec.startRecording()
                val buf = ShortArray(rate / 50)            // trames de 20 ms
                val det = DetecteurParole()
                while (run) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val (rms, zcr) = DetecteurParole.mesurer(buf, n)
                    if (det.trame(rms, zcr)) { run = false; onVoix(); break }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { rec?.stop() }; runCatching { aec?.release() }; runCatching { ns?.release() }; runCatching { rec?.release() }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() { run = false }

    /** Arrête et attend que le micro soit vraiment rendu (AudioRecord libéré), au plus [ms] millisecondes. */
    fun stopEtAttendre(ms: Long) {
        run = false
        val t = thread ?: return
        if (t !== Thread.currentThread()) runCatching { t.join(ms) }
    }
}
