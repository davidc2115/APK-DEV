package fr.dd.jarvis.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import fr.dd.jarvis.car.Pcm
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
        class Phrase(val pcm: ByteArray, val texteLocal: String, val dureeMs: Long, val voixMs: Int, val voskActif: Boolean = false,
                     /** Mots reconnus avec assurance (voix proche) ; -1 si inconnu. Les voix lointaines n'en donnent presque pas. */
                     val motsSurs: Int = -1) : Fin()
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
        /** Micro qui a écouté la dernière demande (affiché dans Réglages › Écoute). */
        @Volatile var dernier: String? = null
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

    /** Micro réellement utilisé par la dernière écoute (pour le journal). */
    @Volatile var source = "micro du téléphone"
        private set

    /**
     * Ouvre le micro. [attenteMs] : délai pour commencer à parler ; [pauseMs] : silence qui termine la phrase.
     * [entree] : micro à préférer (casque / voiture Bluetooth dont la liaison mains-libres est ouverte).
     * [voiture] : Android Auto → micro de la VOITURE (repli sur le téléphone si l'autoradio ne le donne pas).
     * Faux si le micro ne peut pas s'ouvrir (occupé par un appel, refusé…).
     */
    fun demarrer(attenteMs: Long, pauseMs: Int, r: Rappels, ignorerMs: Long = 0,
                 entree: AudioDeviceInfo? = null, voiture: CarContext? = null): Boolean {
        if (voiture != null) {
            // Ouverture et calibrage du micro de la voiture dans le fil du micro (quelques centaines de ms).
            chargerModele()
            Thread({
                val e = runCatching { ouvrirVoiture(voiture) }.getOrNull() ?: ouvrirTelephone(null)
                if (e == null) r.fin(Fin.Erreur("aucun micro disponible"))
                else {
                    source = e.nom; dernier = e.nom
                    // Honnête : l'autoradio n'a rien donné → c'est bien le téléphone qui écoute cette demande.
                    if (e !is EntreeVoiture) JournalVoix.noter("micro", "micro de la voiture muet ou refusé : demande écoutée par le ${e.nom}")
                    boucle(e, attenteMs, pauseMs, r, ignorerMs)
                }
            }, "jarvis-micro").apply { isDaemon = true; start() }
            return true
        }
        val e = ouvrirTelephone(entree) ?: return false
        source = e.nom; dernier = e.nom
        chargerModele()
        Thread({ boucle(e, attenteMs, pauseMs, r, ignorerMs) }, "jarvis-micro").apply { isDaemon = true; start() }
        return true
    }

    /** Modèle français chargé à côté (1 à 2 s la toute première fois) : l'enregistrement n'attend pas. */
    private fun chargerModele() {
        if (VoskModel.francais(ctx)) Thread({ modele = runCatching { VoskModel.partage(ctx) }.getOrNull() }, "jarvis-modele").apply { isDaemon = true; start() }
    }

    /** Source du son : le micro du téléphone (ou du casque Bluetooth), ou celui de la voiture. */
    private interface Entree {
        val nom: String
        /** Remplit [buf] (PCM 16 bits) ; nombre d'échantillons lus, négatif si le flux est perdu. */
        fun lire(buf: ShortArray): Int
        fun fermer()
    }

    private class EntreeTelephone(private val ar: AudioRecord, override val nom: String) : Entree {
        override fun lire(buf: ShortArray) = ar.read(buf, 0, buf.size)
        override fun fermer() { runCatching { ar.stop() }; runCatching { ar.release() } }
    }

    @SuppressLint("MissingPermission")
    private fun ouvrirTelephone(prefere: AudioDeviceInfo?): Entree? {
        val min = AudioRecord.getMinBufferSize(TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, TRAME * 2 * 50))
        } catch (_: Throwable) { return null }
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); return null }
        // Casque / voiture Bluetooth : leur micro plutôt que celui du téléphone (posé loin, dans la poche…).
        val bt = prefere != null && runCatching { ar.setPreferredDevice(prefere) }.getOrDefault(false)
        try { ar.startRecording() } catch (_: Throwable) { ar.release(); return null }
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) { runCatching { ar.stop() }; ar.release(); return null }
        val route = runCatching { ar.routedDevice }.getOrNull()
        val nom = if (bt) "micro Bluetooth (${prefere?.productName?.toString()?.trim().orEmpty().ifBlank { "casque" }})" else "micro du téléphone"
        JournalVoix.noter("micro", "entrée audio : demandé=${prefere?.productName ?: "téléphone"} ; accepté=$bt ; actif=${route?.productName ?: "non publié"}/${route?.type ?: -1}")
        return EntreeTelephone(ar, nom)
    }

    /**
     * Micro de la voiture (Android Auto). L'ordre des octets du flux n'est pas garanti : il est choisi sur les 300
     * premières ms (le bon est celui où le bruit de fond est faible). Null si l'autoradio ne donne rien (micro refusé,
     * silence numérique parfait) : l'appelant se rabat sur le téléphone.
     */
    private fun ouvrirVoiture(c: CarContext): Entree? {
        val rec = CarAudioRecord.create(c)
        rec.startRecording()
        val e = EntreeVoiture(rec)
        val fini = java.util.concurrent.atomic.AtomicBoolean(false)
        // Garde : un autoradio qui n'envoie rien laisserait la lecture bloquée pour toujours.
        val garde = Thread({ try { Thread.sleep(1500); if (!fini.get()) runCatching { rec.stopRecording() } } catch (_: InterruptedException) { } }, "jarvis-micro-garde")
        garde.isDaemon = true; garde.start()
        val ok = runCatching { e.calibrer() }.getOrDefault(false)
        fini.set(true); garde.interrupt()
        if (!ok) { e.fermer(); return null }
        return e
    }

    private class EntreeVoiture(private val rec: CarAudioRecord) : Entree {
        override val nom = "micro de la voiture"
        private val bloc = ByteArray(max(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE, TRAME * 2))
        private var file = ByteArray(TRAME * 2 * 32)
        private var dispo = 0
        private var grosBoutiste = false

        private fun remplir(): Int {
            val n = rec.read(bloc, 0, bloc.size)
            if (n > 0) {
                if (dispo + n > file.size) file = file.copyOf(maxOf(file.size * 2, dispo + n))
                System.arraycopy(bloc, 0, file, dispo, n); dispo += n
            }
            return n
        }

        fun calibrer(): Boolean {
            val besoin = TRAME * 2 * 15
            while (dispo < besoin) if (remplir() < 0) return false
            var le = 0.0; var be = 0.0
            var i = 0
            while (i + TRAME * 2 <= besoin) { le += Pcm.rms(file, i, TRAME * 2, false); be += Pcm.rms(file, i, TRAME * 2, true); i += TRAME * 2 }
            if (le == 0.0 && be == 0.0) return false
            grosBoutiste = be < le
            return true
        }

        override fun lire(buf: ShortArray): Int {
            while (dispo < 2) { val n = remplir(); if (n < 0) return -1 }
            val k = minOf(buf.size, dispo / 2)
            for (j in 0 until k) {
                val a = file[2 * j].toInt(); val b = file[2 * j + 1].toInt()
                buf[j] = (if (grosBoutiste) (a shl 8) or (b and 0xFF) else (b shl 8) or (a and 0xFF)).toShort()
            }
            val pris = k * 2
            System.arraycopy(file, pris, file, 0, dispo - pris); dispo -= pris
            return k
        }

        override fun fermer() { runCatching { rec.stopRecording() } }
    }

    private fun boucle(ar: Entree, attenteMs: Long, pauseMs: Int, r: Rappels, ignorerMs: Long) {
        val tout = ByteArrayOutputStream()
        val buf = ShortArray(TRAME)
        val octets = ByteArray(TRAME * 2)
        val lot = ByteArrayOutputStream()      // 100 ms envoyées d'un coup au modèle
        val porte = PorteParole(silenceFin = (pauseMs / 20).coerceIn(40, 200), minimum = 90.0)
        // Voix basse ou lointaine (téléphone sur son support en voiture) : remontée automatiquement (×4 au plus, sans
        // écrêter une voix forte) avant le modèle et Whisper.
        val gain = GainAuto(cible = 2500.0, max = 4.0)
        // Fin de phrase plus rapide quand ce qui est dit est déjà une commande complète (« pause », « mets mes titres
        // J'aime », « lance la navigation vers Dijon ») : 0,8 s de silence au lieu de la pause choisie (2 s par défaut).
        var texteJuge = ""
        var commande = false
        // Le texte du modèle arrive ~0,3 s après la voix : la pause comptée sur les mots est raccourcie d'autant.
        val mots = EcouteParMots(pauseTrames = ((pauseMs - 300) / 20).coerceIn(35, 200))
        val avecModele = VoskModel.francais(ctx)
        val debut = System.currentTimeMillis()
        var debutParole = 0L
        var trames = 0
        var rec: org.vosk.Recognizer? = null
        var envoye = 0                          // octets de [tout] déjà passés au modèle
        val texteFini = StringBuilder()         // morceaux déjà conclus par Vosk
        val horodatage = ArrayList<MotDate>()   // mots conclus avec leur position dans l'enregistrement
        var partiel = ""
        var affiche = ""
        var resultat: Fin = Fin.Silence

        fun texte() = listOf(texteFini.toString(), partiel).filter { it.isNotBlank() }.joinToString(" ").trim()
        fun noterMots(json: String): String {
            val o = org.json.JSONObject(json)
            o.optJSONArray("result")?.let { l -> for (i in 0 until l.length()) l.optJSONObject(i)?.let { w ->
                horodatage += MotDate(w.optString("word"), w.optDouble("start"), w.optDouble("end"), w.optDouble("conf", 1.0)) } }
            return o.optString("text").trim()
        }
        fun nourrir(b: ByteArray, len: Int) {
            val v = rec ?: return
            runCatching {
                if (v.acceptWaveForm(b, len)) {
                    val t = noterMots(v.result)
                    if (t.isNotBlank()) { if (texteFini.isNotEmpty()) texteFini.append(' '); texteFini.append(t) }
                    partiel = ""
                } else partiel = org.json.JSONObject(v.partialResult).optString("partial").trim()
            }
            val t = texte()
            if (t != affiche) { affiche = t; if (t.isNotBlank()) r.partiel(t) }
        }
        // Micro de la voiture ou Bluetooth (son « téléphonique » filtré par l'autoradio) : le modèle hors-ligne y reconnaît
        // mal les mots. Ta voix repérée au NIVEAU sonore compte alors aussi — avant, la phrase finissait en « personne
        // n'a parlé » alors que tu parlais, ou ne se terminait qu'au bout du délai.
        val lointain = ar is EntreeVoiture || ar.nom.startsWith("micro Bluetooth")
        fun parle() = if (rec != null) (mots.parle || (lointain && porte.parle)) else porte.parle
        fun phrase(): Fin.Phrase {
            val toutes = tout.toByteArray()
            var final = ""
            rec?.let { v ->
                if (lot.size() > 0) { val b = lot.toByteArray(); lot.reset(); runCatching { v.acceptWaveForm(b, b.size) } }
                final = runCatching { noterMots(v.finalResult) }.getOrDefault("")
            }
            val local = listOf(texteFini.toString(), final).filter { it.isNotBlank() }.joinToString(" ").trim().ifBlank { texte() }
            // Seulement de ~0,4 s avant ton premier mot à ~0,5 s après le dernier (horodatage du modèle) : la télé avant
            // et après ta phrase n'est plus envoyée à Whisper. Sans horodatage : à partir du début repéré.
            val (de, a) = EcouteParMots.decoupe(horodatage, toutes.size / 2, TAUX)
                ?: (if (rec != null && mots.trameDebut > 0) maxOf(0, mots.trameDebut - 60) * TRAME else porte.debut * TRAME) to toutes.size / 2
            val debutOctet = (de * 2).coerceIn(0, toutes.size); val finOctet = (a * 2).coerceIn(debutOctet, toutes.size)
            val voixMs = if (horodatage.isNotEmpty()) horodatage.filter { EcouteParMots.reel(it.mot) }.sumOf { ((it.fin - it.debut) * 1000).toInt() } else porte.tramesVoix * 20
            val surs = if (rec != null) horodatage.count { EcouteParMots.reel(it.mot) && it.conf >= EcouteParMots.CONF_SURE } else -1
            return Fin.Phrase(toutes.copyOfRange(debutOctet, finOctet), local,
                if (debutParole > 0) System.currentTimeMillis() - debutParole else 0, voixMs, rec != null, surs)
        }

        try {
            while (true) {
                if (!actif) { resultat = if (garder && parle()) phrase() else Fin.Annulee; break }
                val n = ar.lire(buf)
                if (n < 0) { resultat = if (parle()) phrase() else Fin.Erreur("micro perdu ($n)"); break }
                if (n == 0) continue
                // Fin de la voix de JARVIS encore dans le haut-parleur / l'autoradio : ces premières trames sont ignorées.
                if (System.currentTimeMillis() - debut < ignorerMs) continue
                gain.appliquer(buf, n).let { g -> if (g !== buf) System.arraycopy(g, 0, buf, 0, n) }
                for (i in 0 until n) { val v = buf[i].toInt(); octets[2 * i] = (v and 0xFF).toByte(); octets[2 * i + 1] = (v shr 8).toByte() }
                tout.write(octets, 0, n * 2)
                val rms = PorteVocale.rms(buf, n)
                trames++
                if (trames % 5 == 0) r.niveau(db(rms))

                // Modèle prêt : reconnaissance en direct (et rattrapage de ce qui a été enregistré avant).
                if (rec == null) modele?.let { m ->
                    rec = runCatching { org.vosk.Recognizer(m, TAUX.toFloat()).also { it.setWords(true) } }.getOrNull()
                    val deja = tout.toByteArray()
                    var i = 0
                    while (i < deja.size) {
                        val k = minOf(3200, deja.size - i); nourrir(deja.copyOfRange(i, i + k), k); i += k
                        mots.trame(affiche, (i / (TRAME * 2)).coerceAtLeast(1))
                    }
                    envoye = deja.size
                }
                if (rec != null && envoye < tout.size()) {
                    lot.write(octets, 0, n * 2); envoye += n * 2
                    if (lot.size() >= 3200) { val b = lot.toByteArray(); lot.reset(); nourrir(b, b.size) }
                }

                val termine: Boolean
                if (rec != null) {
                    // DÉCISION PAR LES MOTS : la phrase commence au premier vrai mot reconnu et finit quand plus aucun
                    // mot n'arrive pendant la pause. Le modèle hors-ligne n'entend bien que la voix proche du téléphone :
                    // la télé ou les gens autour ne la déclenchent pas et ne la prolongent pas (avant : écoute sans fin).
                    // …et dès que TA voix s'est tue pendant la pause choisie (niveau sonore jugé par rapport à ta voix),
                    // sans attendre que le modèle ait fini de réviser son texte : c'était ~1 à 2 s de trop.
                    val finVoix = porte.trame(rms)
                    if (affiche != texteJuge) { texteJuge = affiche; commande = VoiceManager.commandeComplete(affiche) }
                    termine = mots.trame(affiche, trames) ||
                        (mots.parle && porte.parle && finVoix && trames - mots.derniereActivite >= 25 && !VoiceManager.suiteProbable(affiche)) ||
                        (commande && mots.parle && porte.silenceTrames >= 40 && trames - mots.derniereActivite >= 40) ||
                        // Micro lointain sans mots reconnus : la fin de TA voix (niveau sonore) termine la phrase.
                        (lointain && !mots.parle && porte.parle && finVoix)
                } else if (avecModele && System.currentTimeMillis() - debut < 4000) {
                    // Modèle encore en chargement (première écoute) : on enregistre, la décision viendra des mots.
                    porte.trame(rms); termine = false
                } else termine = porte.trame(rms).let { fin ->
                    // Sans modèle : décision au niveau sonore ; phrase inachevée → on attend sa suite.
                    if (fin && porte.prolongations < 2 && VoiceManager.suiteProbable(affiche)) { porte.prolonger(60); false } else fin
                }
                if (parle() && debutParole == 0L) { debutParole = System.currentTimeMillis(); r.debutParole() }
                if (termine) { resultat = phrase(); break }
                val ecoule = System.currentTimeMillis() - debut
                if (!parle() && ecoule > attenteMs) { resultat = Fin.Silence; break }
                // Filet : 12 s au plus d'une même phrase.
                if (debutParole > 0 && System.currentTimeMillis() - debutParole > 12_000) { resultat = phrase(); break }
            }
        } catch (e: Throwable) {
            resultat = if (parle()) runCatching { phrase() }.getOrDefault(Fin.Erreur(e.message ?: "erreur micro")) else Fin.Erreur(e.message ?: e.javaClass.simpleName)
        } finally {
            ar.fermer()
            runCatching { rec?.close() }
        }
        r.fin(resultat)
    }
}

/** Mot reconnu par le modèle hors-ligne, avec sa position (secondes depuis le début de l'enregistrement). */
internal data class MotDate(val mot: String, val debut: Double, val fin: Double, val conf: Double)

/**
 * Début et fin de ta phrase décidés par les MOTS reconnus en direct (modèle Vosk français), trame par trame (20 ms) :
 * la phrase commence au premier vrai mot et se termine après [pauseTrames] sans nouveau mot. Une phrase visiblement
 * inachevée (« envoie un message à… ») attend encore sa suite (2 fois au plus).
 */
internal class EcouteParMots(private val pauseTrames: Int) {
    var parle = false
        private set
    /** Trame où le premier vrai mot est apparu. */
    var trameDebut = 0
        private set
    /** Trame du dernier changement du texte reconnu. */
    var derniereActivite = 0
        private set
    private var texte = ""
    private var prolongations = 0
    private var n = 0

    /** [trame] : numéro de la trame courante ; vrai quand la phrase est terminée. */
    fun trame(texteDirect: String, trame: Int): Boolean {
        n = trame
        val t = texteDirect.trim()
        if (t != texte) {
            texte = t
            if (motsReels(t) > 0) {
                derniereActivite = n
                if (!parle) { parle = true; trameDebut = n }
            }
        }
        if (!parle || n - derniereActivite < pauseTrames) return false
        if (prolongations < 2 && VoiceManager.suiteProbable(texte)) { prolongations++; derniereActivite = n - pauseTrames + 60; return false }
        return true
    }

    companion object {
        private val REMPLISSAGE = setOf("euh", "heu", "hum", "hm", "mh", "ah", "oh", "eh", "ben", "bah", "hein", "pff", "bon")
        fun reel(m: String) = m.length >= 2 && m.lowercase() !in REMPLISSAGE
        fun motsReels(t: String) = t.split(' ').count { reel(it) }

        /**
         * Morceau à envoyer à Whisper (échantillons [de, a[) : ~0,4 s avant le premier vrai mot, ~0,5 s après le
         * dernier ; null sans horodatage utilisable.
         */
        /** Assurance du modèle au-dessus de laquelle un mot vient d'une voix proche (mesuré : toi ≥ 0,8 à 95 %, télé 30 %). */
        const val CONF_SURE = 0.7

        fun decoupe(mots: List<MotDate>, total: Int, taux: Int): Pair<Int, Int>? {
            val tous = mots.filter { reel(it.mot) && it.fin > it.debut }
            // Les mots sûrs (ta voix) bornent le morceau ; les mots douteux de la télé avant / après sont laissés.
            val vrais = tous.filter { it.conf >= CONF_SURE }.ifEmpty { tous }
            if (vrais.isEmpty()) return null
            val de = ((vrais.minOf { it.debut } - 0.4) * taux).toInt().coerceIn(0, total)
            val a = ((vrais.maxOf { it.fin } + 0.5) * taux).toInt().coerceIn(de, total)
            return if (a - de >= taux / 4) de to a else null
        }
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
    /** Trames de silence depuis la dernière trame de voix. */
    val silenceTrames get() = silence
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

    var prolongations = 0
        private set
    /** La phrase semble inachevée : on attend encore [trames] trames de silence avant de conclure. */
    fun prolonger(trames: Int) { prolongations++; silence = (silenceFin - trames).coerceAtMost(silence) }
}
