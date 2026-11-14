package fr.dd.jarvis.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.max

/**
 * Écoute « JARVIS » : JARVIS tient lui-même le micro et décide seul de la fin de ta phrase, au lieu de la confier au
 * reconnaisseur de Google (qui, sur ce téléphone, coupait au milieu des phrases, ne rendait qu'un mot ou restait muet).
 *
 *  - le micro s'ouvre tout de suite (pas d'attente de création du reconnaisseur : les premiers mots ne sont plus perdus) ;
 *  - la phrase ne se termine qu'après la pause choisie dans les réglages, JAMAIS pendant que tu parles : niveau sonore
 *    ET mots reconnus en direct (une voix basse ou lointaine compte aussi) ;
 *  - une phrase visiblement inachevée (« envoie un message à… ») attend sa suite ;
 *  - le texte en direct vient du modèle Vosk français (hors-ligne) ; la phrase finale est ensuite transcrite par
 *    Whisper (Groq) par [VoiceManager], le texte Vosk servant de secours sans réseau.
 */
class MicroJarvis(private val ctx: Context) {

    sealed class Fin {
        /**
         * Phrase enregistrée (PCM 16 bits 16 kHz petit-boutiste), son texte hors-ligne (peut être vide) et la durée de
         * voix réellement entendue (hors silences) : sous ~¼ s, c'est un bruit, pas une phrase.
         */
        class Phrase(val pcm: ByteArray, val texteLocal: String, val dureeMs: Long, val voixMs: Int, val voskActif: Boolean = false) : Fin()
        object Silence : Fin()
        object Annulee : Fin()
        class Erreur(val message: String) : Fin()
    }

    interface Rappels {
        fun niveau(db: Float) {}
        fun debutParole() {}
        fun partiel(texte: String) {}
        fun fin(f: Fin)
    }

    companion object {
        const val TAUX = 16000
        private const val TRAME = 320            // 20 ms
        /** Niveau RMS (PCM 16 bits) → échelle « dB » des reconnaisseurs Android (≈ -2 à 10) pour l'animation. */
        internal fun db(rms: Double): Float = ((20 * log10(max(rms, 1.0)) - 30) / 4).toFloat()
    }

    @Volatile private var actif = true
    @Volatile private var garder = false
    @Volatile private var modele: org.vosk.Model? = null

    /** Arrête l'écoute : [garderPhrase] = ce qui a déjà été dit est rendu (bouton micro retouché), sinon annulée. */
    fun arreter(garderPhrase: Boolean) { garder = garderPhrase; actif = false }

    /**
     * Ouvre le micro. [attenteMs] : délai pour commencer à parler ; [pauseMs] : silence qui termine la phrase.
     * Faux si le micro ne peut pas s'ouvrir (occupé par un appel, refusé…).
     */
    @SuppressLint("MissingPermission")
    fun demarrer(attenteMs: Long, pauseMs: Int, r: Rappels, ignorerMs: Long = 0): Boolean {
        val min = AudioRecord.getMinBufferSize(TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, TRAME * 2 * 50))
        } catch (_: Throwable) { return false }
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); return false }
        try { ar.startRecording() } catch (_: Throwable) { ar.release(); return false }
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) { runCatching { ar.stop() }; ar.release(); return false }
        // Modèle français chargé à côté (1 à 2 s la toute première fois) : l'enregistrement n'attend pas.
        if (VoskModel.francais(ctx)) Thread({ modele = runCatching { VoskModel.partage(ctx) }.getOrNull() }, "jarvis-modele").apply { isDaemon = true; start() }
        Thread({ boucle(ar, attenteMs, pauseMs, r, ignorerMs) }, "jarvis-micro").apply { isDaemon = true; start() }
        return true
    }

    private fun boucle(ar: AudioRecord, attenteMs: Long, pauseMs: Int, r: Rappels, ignorerMs: Long) {
        val tout = ByteArrayOutputStream()
        val buf = ShortArray(TRAME)
        val octets = ByteArray(TRAME * 2)
        val lot = ByteArrayOutputStream()      // 100 ms envoyées d'un coup au modèle
        val porte = PorteParole(silenceFin = (pauseMs / 20).coerceIn(40, 200))
        val debut = System.currentTimeMillis()
        var debutParole = 0L
        var trames = 0
        var rec: org.vosk.Recognizer? = null
        var envoye = 0                          // octets de [tout] déjà passés au modèle
        val texteFini = StringBuilder()         // morceaux déjà conclus par Vosk
        var partiel = ""
        var affiche = ""
        var prolongations = 0
        var resultat: Fin = Fin.Silence

        fun texte() = listOf(texteFini.toString(), partiel).filter { it.isNotBlank() }.joinToString(" ").trim()
        fun nourrir(b: ByteArray, len: Int) {
            val v = rec ?: return
            runCatching {
                if (v.acceptWaveForm(b, len)) {
                    val t = org.json.JSONObject(v.result).optString("text").trim()
                    if (t.isNotBlank()) { if (texteFini.isNotEmpty()) texteFini.append(' '); texteFini.append(t) }
                    partiel = ""
                } else partiel = org.json.JSONObject(v.partialResult).optString("partial").trim()
            }
            val t = texte()
            if (t != affiche) { affiche = t; if (t.isNotBlank()) r.partiel(t) }
        }
        fun phrase(): Fin.Phrase {
            val f = porte
            val toutes = tout.toByteArray()
            // Le son d'AVANT ta parole (télé, bruit) n'est pas envoyé : seulement à partir de ~0,4 s avant ton premier mot.
            val depuis = (f.debut * TRAME * 2).coerceIn(0, toutes.size)
            val final = rec?.let { v -> runCatching { org.json.JSONObject(v.finalResult).optString("text").trim() }.getOrDefault("") }.orEmpty()
            val local = listOf(texteFini.toString(), final).filter { it.isNotBlank() }.joinToString(" ").trim().ifBlank { texte() }
            return Fin.Phrase(toutes.copyOfRange(depuis - depuis % 2, toutes.size), local,
                if (debutParole > 0) System.currentTimeMillis() - debutParole else 0, f.tramesVoix * 20, rec != null)
        }

        try {
            while (true) {
                if (!actif) { resultat = if (garder && porte.parle) phrase() else Fin.Annulee; break }
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) { resultat = if (porte.parle) phrase() else Fin.Erreur("micro perdu ($n)"); break }
                if (n == 0) continue
                // Fin de la voix de JARVIS encore dans le haut-parleur / l'autoradio : ces premières trames sont ignorées.
                if (System.currentTimeMillis() - debut < ignorerMs) continue
                for (i in 0 until n) { val v = buf[i].toInt(); octets[2 * i] = (v and 0xFF).toByte(); octets[2 * i + 1] = (v shr 8).toByte() }
                tout.write(octets, 0, n * 2)
                val rms = PorteVocale.rms(buf, n)
                trames++
                if (trames % 5 == 0) r.niveau(db(rms))

                // Modèle prêt : reconnaissance en direct (et rattrapage de ce qui a été enregistré avant).
                if (rec == null) modele?.let { m ->
                    rec = runCatching { org.vosk.Recognizer(m, TAUX.toFloat()) }.getOrNull()
                    val deja = tout.toByteArray()
                    var i = 0
                    while (i < deja.size) { val k = minOf(6400, deja.size - i); nourrir(deja.copyOfRange(i, i + k), k); i += k }
                    envoye = deja.size
                }
                if (rec != null && envoye < tout.size()) {
                    lot.write(octets, 0, n * 2); envoye += n * 2
                    if (lot.size() >= 3200) { val b = lot.toByteArray(); lot.reset(); nourrir(b, b.size) }
                }

                val f = porte
                var termine = f.trame(rms)
                if (f.parle && debutParole == 0L) { debutParole = System.currentTimeMillis(); r.debutParole() }
                // Phrase visiblement inachevée (« envoie un message à… ») : on attend encore un peu sa suite (2 fois au plus).
                if (termine && prolongations < 2 && VoiceManager.suiteProbable(affiche)) {
                    prolongations++
                    f.prolonger(60)
                    termine = false
                }
                if (termine) { resultat = phrase(); break }
                val ecoule = System.currentTimeMillis() - debut
                if (!f.parle && ecoule > attenteMs) { resultat = Fin.Silence; break }
                // Filet : une phrase de plus de 15 s (télé, radio qui ne s'arrêtent jamais) est conclue.
                if (debutParole > 0 && System.currentTimeMillis() - debutParole > 15_000) { resultat = phrase(); break }
            }
        } catch (e: Throwable) {
            resultat = if (porte.parle) runCatching { phrase() }.getOrDefault(Fin.Erreur(e.message ?: "erreur micro")) else Fin.Erreur(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { ar.stop() }
            runCatching { ar.release() }
            runCatching { rec?.close() }
        }
        r.fin(resultat)
    }
}

/**
 * Début et fin de TA phrase, trame par trame (20 ms), même avec la télé ou la radio allumée :
 *  - début : un son nettement plus fort (×2,5) que le fond sonore des 1,5 s précédentes (télé comprise) ;
 *  - fin : [silenceFin] trames sans voix, la « voix » étant jugée par rapport à TON niveau (crête de ta phrase) et
 *    non plus au calme du début : la télé, bien plus faible que ta voix près du téléphone, compte comme du silence.
 *    Avant, la télé passait pour de la parole et la phrase ne finissait jamais (25 à 30 s enregistrées).
 */
internal class PorteParole(private val silenceFin: Int, private val minimum: Double = 150.0) {
    private val fond = ArrayDeque<Double>()
    private val recent = BooleanArray(10)
    private var n = 0
    private var silence = 0
    private var crete = 0.0
    private var fondFige = 0.0
    var parle = false
        private set
    /** Trame à partir de laquelle la phrase est gardée (~0,4 s avant le premier mot). */
    var debut = 0
        private set
    var tramesVoix = 0
        private set

    /** Niveau sous lequel se trouvent 20 % des trames récentes : le fond sonore (pauses de la télé comprises). */
    private fun niveauFond(): Double {
        if (fond.isEmpty()) return 0.0
        val tri = fond.sorted()
        return tri[(tri.size * 0.2).toInt().coerceAtMost(tri.size - 1)]
    }

    /** Ajoute une trame ; vrai quand la phrase est terminée. */
    fun trame(rms: Double): Boolean {
        n++
        if (!parle) {
            val v = fond.size >= 8 && rms > maxOf(minimum, niveauFond() * 2.5)
            recent[n % recent.size] = v
            if (recent.count { it } >= 5) {
                parle = true
                debut = maxOf(0, n - 20)
                fondFige = niveauFond()
                crete = rms
                tramesVoix = 5
                silence = 0
                return false
            }
            fond.addLast(rms)
            while (fond.size > 75) fond.removeFirst()
            return false
        }
        crete = maxOf(rms, crete * 0.998)
        // Voix = au moins ~-13 dB sous la crête de TA phrase : les voix plus lointaines (télé, gens autour) n'en sont pas.
        val voix = rms > maxOf(minimum, fondFige * 2.0, crete * 0.22)
        if (voix) { tramesVoix++; silence = 0 } else silence++
        return silence >= silenceFin
    }

    /** La phrase semble inachevée : on attend encore [trames] trames de silence avant de conclure. */
    fun prolonger(trames: Int) { silence = (silenceFin - trames).coerceAtMost(silence) }
}
