package fr.dd.jarvis.car

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/** Outils audio sans dépendance Android (testables) : niveau, ordre des octets, WAV, détection de fin de phrase. */
object Pcm {
    const val TAUX = 16000
    /** 20 ms de PCM 16 bits mono à 16 kHz. */
    const val TRAME = 640

    fun rms(b: ByteArray, debut: Int, len: Int, grosBoutiste: Boolean): Double {
        var s = 0.0; var n = 0
        var i = debut
        while (i + 1 < debut + len) {
            val v = if (grosBoutiste) ((b[i].toInt() shl 8) or (b[i + 1].toInt() and 0xFF)).toShort().toInt()
            else ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            s += v.toDouble() * v; n++; i += 2
        }
        return if (n == 0) 0.0 else sqrt(s / n)
    }

    fun inverserOctets(b: ByteArray): ByteArray {
        val r = b.copyOf()
        var i = 0
        while (i + 1 < r.size) { val t = r[i]; r[i] = r[i + 1]; r[i + 1] = t; i += 2 }
        return r
    }

    /** En-tête WAV (PCM 16 bits mono) autour de données petit-boutistes. */
    fun wav(pcm: ByteArray, taux: Int = TAUX): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(taux); b.putInt(taux * 2)
        b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
        return b.array()
    }

    /** Phrases que Whisper « entend » dans le silence ou la musique (sous-titres de vidéos) : à ignorer. */
    private val hallucinations = Regex(
        "(?i)(sous-?titr|amara\\.org|merci d'avoir regard|abonnez-vous|n'oubliez pas de vous abonner|♪|\\[musique]|\\(musique\\))"
    )

    fun nettoyerTranscription(t: String): String {
        val s = t.trim()
        if (s.length < 90 && hallucinations.containsMatchIn(s)) return ""
        return s.trim(' ', '.', '…')
    }
}

/**
 * Détection de prise de parole puis de fin de phrase, trame par trame (20 ms) : seuil adapté au bruit de fond de la
 * voiture (route, ventilation), la phrase se termine après ~1,1 s de silence.
 */
class FinDePhrase(plancherInitial: Double, private val minimum: Double = 250.0) {
    private var plancher = max(plancherInitial, 20.0)
    private val voix = BooleanArray(10)
    private var n = 0
    private var silence = 0
    var parle = false
        private set
    /** Numéro de trame où la parole a commencé (pour couper le silence de tête). */
    var debut = 0
        private set

    /** Ajoute une trame ; vrai quand la phrase est terminée. */
    fun trame(rms: Double): Boolean {
        n++
        val v = rms > max(minimum, plancher * 3.0)
        if (!v) plancher = if (rms < plancher) 0.8 * plancher + 0.2 * rms else 0.995 * plancher + 0.005 * rms
        voix[n % voix.size] = v
        if (!parle && voix.count { it } >= 5) { parle = true; debut = max(0, n - 15) }
        if (parle) {
            silence = if (v) 0 else silence + 1
            if (silence >= 55) return true
        }
        return false
    }
}

/** Une phrase dite au micro de la voiture (Android Auto), ou la raison pour laquelle il n'y en a pas. */
sealed class Ecoute {
    class Phrase(val pcm: ByteArray) : Ecoute()
    object Silence : Ecoute()
    object Annulee : Ecoute()
    class Erreur(val message: String) : Ecoute()
}

/**
 * Micro de la VOITURE (Android Auto, API voiture niveau 5+) : enregistre une phrase jusqu'au silence. La musique est
 * mise en pause pendant l'écoute (focus audio « assistant »), comme pour l'Assistant Google.
 */
class MicroVoiture(private val ctx: CarContext) {
    @Volatile private var arret = false
    fun arreter() { arret = true }

    /** Bloquant : à appeler hors du fil principal. [attenteMs] = délai pour commencer à parler. */
    fun ecouter(attenteMs: Long, maxMs: Long = 15_000): Ecoute {
        val am = ctx.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        runCatching { am.requestAudioFocus(focus) }
        val rec = try { CarAudioRecord.create(ctx) } catch (e: Throwable) {
            runCatching { am.abandonAudioFocusRequest(focus) }
            return Ecoute.Erreur("micro de la voiture indisponible (${e.message ?: e.javaClass.simpleName})")
        }
        val tout = ByteArrayOutputStream()
        var grosBoutiste: Boolean? = null
        var fin: FinDePhrase? = null
        val calLE = ArrayList<Double>(); val calBE = ArrayList<Double>()
        val debut = System.currentTimeMillis()
        try {
            rec.startRecording()
            val buf = ByteArray(maxOf(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE, Pcm.TRAME))
            val trame = ByteArray(Pcm.TRAME)
            var rempli = 0
            while (true) {
                if (arret) return Ecoute.Annulee
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) tout.write(buf, 0, n)
                var i = 0
                while (i < n) {
                    val k = minOf(n - i, Pcm.TRAME - rempli)
                    System.arraycopy(buf, i, trame, rempli, k)
                    rempli += k; i += k
                    if (rempli < Pcm.TRAME) break
                    rempli = 0
                    if (grosBoutiste == null) {
                        // L'ordre des octets du flux n'est pas garanti : le bon est celui où le bruit de fond est faible.
                        calLE += Pcm.rms(trame, 0, Pcm.TRAME, false); calBE += Pcm.rms(trame, 0, Pcm.TRAME, true)
                        if (calLE.size >= 15) {
                            val be = calBE.sum() < calLE.sum()
                            grosBoutiste = be
                            fin = FinDePhrase((if (be) calBE else calLE).minOrNull() ?: 100.0)
                        }
                    } else if (fin!!.trame(Pcm.rms(trame, 0, Pcm.TRAME, grosBoutiste == true))) {
                        return Ecoute.Phrase(decouper(tout.toByteArray(), grosBoutiste == true, fin!!.debut + 15))
                    }
                }
                val ecoule = System.currentTimeMillis() - debut
                if (fin?.parle != true && ecoule > attenteMs) return Ecoute.Silence
                if (ecoule > maxMs) break
            }
            val pcm = tout.toByteArray()
            val f = fin
            return if (f != null && f.parle) Ecoute.Phrase(decouper(pcm, grosBoutiste == true, f.debut + 15)) else Ecoute.Silence
        } catch (e: Throwable) {
            return Ecoute.Erreur(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { rec.stopRecording() }
            runCatching { am.abandonAudioFocusRequest(focus) }
        }
    }

    /** PCM petit-boutiste à partir de la trame [trameDebut] (un peu de marge avant la parole). */
    private fun decouper(pcm: ByteArray, grosBoutiste: Boolean, trameDebut: Int): ByteArray {
        val depuis = (trameDebut * Pcm.TRAME).coerceIn(0, pcm.size)
        val utile = pcm.copyOfRange(depuis - depuis % 2, pcm.size)
        return if (grosBoutiste) Pcm.inverserOctets(utile) else utile
    }
}
