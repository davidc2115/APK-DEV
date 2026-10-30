package fr.dd.jarvis.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.math.sqrt

/**
 * Détecte que l'utilisateur se remet à parler pendant que JARVIS parle (« couper la parole »).
 * Micro en mode communication (annulation d'écho du téléphone), niveau mesuré pendant le début de la phrase de JARVIS,
 * déclenchement quand la voix dépasse nettement ce niveau de façon continue.
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
                var t = 0; var base = 0.0; var fort = 0
                while (run) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var s = 0.0
                    for (i in 0 until n) s += buf[i] * buf[i].toDouble()
                    val rms = sqrt(s / n)
                    t++
                    if (t <= 25) { base = maxOf(base, rms); continue }   // 500 ms : niveau résiduel de la voix de JARVIS
                    val seuil = maxOf(base * 2.6, 1700.0)
                    fort = if (rms > seuil) fort + 1 else 0
                    if (fort >= 6) { run = false; onVoix(); break }       // ≈ 120 ms de voix continue
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { rec?.stop() }; runCatching { aec?.release() }; runCatching { ns?.release() }; runCatching { rec?.release() }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() { run = false }
}
