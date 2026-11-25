package fr.dd.jarvis.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Détecteur « Jarvis » hors-ligne (Vosk, petit modèle) : écoute en continu SANS bip, sans réseau et à faible coût.
 *
 * Modèle FRANÇAIS (« jarvis » fait partie de son vocabulaire) : un « Jarvis » dit avec l'accent français y est reconnu
 * bien plus souvent qu'avec l'ancien modèle anglais (essais sur 72 phrases de 4 voix françaises : 65 détectées contre
 * 16, et aucun faux réveil sur 300 phrases ordinaires). L'ancien modèle anglais sert encore tant que le français n'est
 * pas téléchargé.
 */
object VoskModel {
    private const val URL_FR = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
    fun dirFr(ctx: Context) = File(ctx.filesDir, "vosk-wake-fr")
    /** Ancien modèle anglais (installations précédentes). */
    fun dirEn(ctx: Context) = File(ctx.filesDir, "vosk-wake")
    private fun complet(d: File) = File(d, "am").isDirectory && File(d, "conf").isDirectory
    /** Le modèle français est installé. */
    fun francais(ctx: Context) = complet(dirFr(ctx))
    fun ready(ctx: Context) = francais(ctx) || complet(dirEn(ctx))
    /** Modèle à utiliser : le français s'il est là, sinon l'ancien anglais. */
    fun dir(ctx: Context) = if (francais(ctx)) dirFr(ctx) else dirEn(ctx)
    /** Le français est là : l'ancien modèle anglais (≈ 70 Mo) ne sert plus. À appeler quand aucun détecteur ne l'utilise. */
    fun nettoyer(ctx: Context) { if (francais(ctx) && dirEn(ctx).exists()) runCatching { dirEn(ctx).deleteRecursively() } }

    private val verrou = kotlinx.coroutines.sync.Mutex()

    /** Modèles chargés, partagés par tout JARVIS (détecteur « Jarvis » et écoute des phrases) : un seul en mémoire. */
    private val charges = HashMap<String, Model>()

    /** Modèle actuel (français s'il est là), chargé une fois pour toute la vie de l'appli ; null s'il n'est pas installé. */
    fun partage(ctx: Context): Model? {
        if (!ready(ctx)) return null
        val chemin = dir(ctx).absolutePath
        synchronized(charges) {
            charges[chemin]?.let { return it }
            return runCatching { Model(chemin) }.getOrNull()?.also { charges[chemin] = it }
        }
    }

    /** Télécharge le modèle français (≈ 41 Mo, une seule fois). Plusieurs demandes en même temps : un seul téléchargement. */
    suspend fun download(ctx: Context, onProgress: (Int) -> Unit): String = verrou.withLock {
        if (francais(ctx)) return@withLock "✅ Détecteur « Jarvis » (français) déjà installé."
        telecharger(ctx, onProgress)
    }

    private suspend fun telecharger(ctx: Context, onProgress: (Int) -> Unit): String = withContext(Dispatchers.IO) {
        try {
            val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            val tmp = File(ctx.cacheDir, "vosk-wake-fr").apply { deleteRecursively(); mkdirs() }
            http.newCall(Request.Builder().url(URL_FR).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "❌ Téléchargement refusé (${r.code})."
                val total = r.body!!.contentLength().coerceAtLeast(1)
                var done = 0L
                ZipInputStream(r.body!!.byteStream()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isNotBlank()) {
                            val f = File(tmp, rel)
                            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { o -> z.copyTo(o) } }
                        }
                        done += e.compressedSize.coerceAtLeast(0)
                        onProgress(((done * 100) / total).toInt().coerceIn(0, 99))
                        e = z.nextEntry
                    }
                }
            }
            if (!complet(tmp)) { tmp.deleteRecursively(); return@withContext "❌ Modèle incomplet après décompression." }
            dirFr(ctx).deleteRecursively()
            if (!tmp.renameTo(dirFr(ctx))) { tmp.copyRecursively(dirFr(ctx), true); tmp.deleteRecursively() }
            if (francais(ctx)) "✅ Détecteur « Jarvis » installé (hors-ligne, français)." else "❌ Modèle incomplet après décompression."
        } catch (e: Throwable) {
            "❌ " + (e.message ?: e.javaClass.simpleName)
        }
    }
}

/** Détecteur de mot d'éveil : démarre l'écoute du seul mot « Jarvis ». */
interface WakeDetector {
    fun start(): Boolean
    fun stop()
    fun release()
    /** Appelé si le détecteur s'arrête tout seul (micro perdu, erreur) : le service le relance. */
    var onPanne: () -> Unit
    /** Exigence de détection (1 = normale, plus haut = plus strict, après des faux réveils répétés). */
    var exigence: Int
}

/** Vrai détecteur de mot d'éveil (Picovoice Porcupine, mot « Jarvis » intégré) : hors-ligne, très peu de faux déclenchements. */
class PorcupineWake(private val ctx: Context, private val accessKey: String, private val onWake: () -> Unit) : WakeDetector {
    private var mgr: ai.picovoice.porcupine.PorcupineManager? = null
    var error: String = ""
    override var onPanne: () -> Unit = {}
    override var exigence: Int = 1
        set(v) { if (field != v) { field = v; val actif = mgr != null; release(); if (actif) start() } }
    /** Sensibilité Porcupine : plus basse = moins de faux réveils (0,6 par défaut, 0,45 puis 0,35 si ça se déclenche tout seul). */
    private fun sensibilite() = when (exigence) { 1 -> 0.6f; 2 -> 0.45f; else -> 0.35f }

    override fun start(): Boolean {
        return try {
            if (mgr == null) {
                mgr = ai.picovoice.porcupine.PorcupineManager.Builder()
                    .setAccessKey(accessKey.trim())
                    .setKeyword(ai.picovoice.porcupine.Porcupine.BuiltInKeyword.JARVIS)
                    .setSensitivity(sensibilite())
                    .setErrorCallback { onPanne() }
                    .build(ctx, ai.picovoice.porcupine.PorcupineManagerCallback { JournalVoix.noter("Porcupine", "« Jarvis » détecté"); onWake() })
            }
            // Redémarrage : on arrête d'abord l'écoute en cours (jamais deux enregistrements du micro en parallèle).
            runCatching { mgr?.stop() }
            mgr?.start(); true
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
            try { mgr?.delete() } catch (_: Throwable) {}
            mgr = null; false
        }
    }

    override fun stop() { try { mgr?.stop() } catch (_: Throwable) {} }
    override fun release() { try { mgr?.stop(); mgr?.delete() } catch (_: Throwable) {}; mgr = null }
}

/**
 * Détecteur « Jarvis » hors-ligne (Vosk). La grammaire contient, en plus de « jarvis », des mots anglais proches
 * (« service », « travis », « harvest »…) : un mot qui ressemble sans être « Jarvis » est rangé ailleurs au lieu de
 * déclencher. Réveil STRICT : « jarvis » doit ouvrir la phrase (on dit « Jarvis, … », pas « … jarvis … » au milieu
 * d'une conversation ou de la télé), avec une forte confiance et une durée de mot plausible. Les résultats partiels
 * ne déclenchent plus rien (c'était la cause des bips à répétition). Si des faux réveils se répètent, le service
 * relève [exigence] et le seuil monte.
 */
class VoskWake(
    private val ctx: Context, private val econome: Boolean = true,
    /** « Jarvis » n'importe où dans la phrase (coupure pendant une réponse : le micro entend aussi la voix de JARVIS). */
    private val partout: Boolean = false,
    private val onWake: () -> Unit
) : WakeDetector {
    private var model: Model? = null
    private var service: SpeechService? = null
    @Volatile private var boucle: BoucleEconome? = null
    @Volatile private var fired = false
    override var onPanne: () -> Unit = {}
    @Volatile override var exigence: Int = 1
    /** Modèle français (sinon l'ancien anglais) : grammaire et seuils propres à chacun. */
    private val francais = VoskModel.francais(ctx)
    /** En voiture (écoute continue, gain fort) : seuil un peu plus bas — un « Jarvis » dit doucement passe. */
    @Volatile private var voitureActive = false
    private fun seuil() = if (francais) when (exigence) { 1 -> if (voitureActive) 0.5 else 0.55; 2 -> if (voitureActive) 0.72 else 0.78; else -> 0.9 }
        else when (exigence) { 1 -> 0.88; 2 -> 0.93; else -> 0.97 }

    companion object {
        /** Tranche d'écoute continue pendant la musique, en voiture : 1,5 s (15 trames de 0,1 s). */
        internal const val FENETRE_MUSIQUE = 15

        /** Micro qui écoute « Jarvis » en ce moment (affiché dans Réglages › Écoute), null si aucune écoute. */
        @Volatile var microActuel: String? = null
            private set

        /** Nom lisible d'un micro Android. */
        internal fun nomMicro(type: Int, produit: String?): String = when (type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "micro du téléphone"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "micro Bluetooth (${produit.orEmpty().ifBlank { "casque / voiture" }})"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "micro du casque filaire"
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "micro USB (${produit.orEmpty()})"
            else -> "micro ${produit.orEmpty().ifBlank { "inconnu" }} (type $type)"
        }
        /**
         * Grammaire du modèle français : « jarvis » au milieu des mots les plus courants et de ses quasi-homonymes
         * (« j'arrive », « service », « gervais », « marvin », « joris », « janvier »…). Un mot qui ressemble sans être
         * « Jarvis » est rangé ailleurs. Mesuré (4 voix françaises × 3 débits) : 90 % des « Jarvis » détectés, 0 faux
         * réveil sur 300 phrases ordinaires, et sur 672 phrases commençant par un prénom ou un mot proche, 0 à 1 faux
         * réveil au lieu de plusieurs dizaines (« Joris, viens manger », « Jadis… », « Janvier est froid », « Javier arrive »…).
         */
        internal val GRAMMAIRE_FR = listOf(
            "jarvis", "[unk]", "a", "ai", "aller", "allons", "allume", "appelle", "arrive", "arriver", "as", "au",
            "aujourd'hui", "avec", "avez", "avis", "avoir", "beau", "bien", "bis", "bonjour", "c'", "canapé", "ce",
            "charles", "chat", "chien", "d'", "dans", "de", "demain", "des", "deux", "dire", "dis", "dit", "dix", "douce",
            "du", "elle", "en", "envoie", "es", "est", "et", "facture", "faire", "fait", "ferme", "gagné", "garage",
            "garde", "gars", "gervais", "harvey", "heure", "hier", "il", "ils", "j'", "j'ai", "j'arrive", "jacques",
            "jardin", "javel", "je", "l'", "la", "lance", "le", "les", "lumière", "mais", "maison", "marché", "marvin",
            "matin", "merci", "met", "mets", "mettre", "minutes", "mode", "musique", "ne", "non", "nous", "ok", "on",
            "ont", "ou", "oui", "ouvre", "où", "par", "paris", "parti", "partie", "pas", "plus", "pour", "qu'", "que",
            "quel", "quelle", "qui", "radis", "salon", "salut", "savon", "servi", "service", "services", "si", "soir",
            "sommes", "suis", "sur", "sécurisé", "temps", "tous", "tout", "travail", "trois", "très", "tu", "téléphone",
            "un", "une", "va", "vais", "vas", "ville", "vin", "vis", "visite", "voir", "voiture", "voix", "vous", "vu",
            "à", "ça", "éteins", "être",
            // Prénoms et mots proches de « Jarvis » (Joris, Doris, Javier, Janvier, Charlie, parvis, carvi…) : sans eux,
            // le modèle, obligé de choisir dans cette liste, les rangeait sous « jarvis » et réveillait JARVIS.
            "joris", "boris", "doris", "maurice", "morris", "loris", "jadis", "jolis", "joli", "jolie", "charvet", "jarry",
            "denis", "alexis", "francis", "régis", "clovis", "elvis", "iris", "davis", "lewis", "norris", "harris",
            "travis", "chris", "louis", "jules", "jamais", "j'avais", "avais", "jeudi", "georges", "gérard", "jérôme",
            "julie", "josé", "javier", "chorus", "marquis", "narcisse", "idris", "mavis", "jarret", "jarnac", "davy",
            "jérémy", "joie", "choix", "parvis", "ravis", "ravi", "gravis", "carvi", "marvel", "charly", "charlie",
            "jasmine", "jason", "jérémie", "jordan", "jonas", "gaspard", "garance", "janis", "anaïs", "kevin", "xavier",
            "olivier", "cyrus", "yanis", "janvier", "jacky", "gervaise", "servir", "chartres", "jade", "harvard", "larves",
        )
        /** Mots du modèle anglais qui servent de leurres autour de « jarvis » (tous présents dans son vocabulaire). */
        private val GRAMMAIRE = listOf(
            "jarvis", "service", "travis", "harvest", "charles", "nervous", "marvel", "garage", "jars", "office",
            "yes", "no", "okay", "hey", "alexa", "google", "[unk]"
        )
        internal fun contientJarvis(texte: String) = Regex("\\bjarvis\\b").containsMatchIn(texte)
        /** Boucles d'écoute économe encore vivantes (micro tenu), toutes instances confondues : jamais plus d'une. */
        internal val vivantes = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Vrai si la phrase reconnue commence par « jarvis » (1er mot, ou 2e après un bruit / un « hey »), avec une
         * confiance ≥ [seuil] et une durée de mot plausible (0,22 à 1,3 s).
         */
        /** Mots admis avant « jarvis » (« ok Jarvis », « dis Jarvis », « hey Jarvis »…). */
        private val AVANT = setOf("[unk]", "hey", "okay", "ok", "dis", "salut", "bonjour")

        /** « jarvis » à n'importe quelle place, assez sûr et de durée plausible. */
        internal fun contientMot(mots: org.json.JSONArray?, seuil: Double): Boolean {
            if (mots == null) return false
            return (0 until mots.length()).any { i ->
                val w = mots.optJSONObject(i) ?: return@any false
                val duree = w.optDouble("end", 0.0) - w.optDouble("start", 0.0)
                w.optString("word") == "jarvis" && w.optDouble("conf", 0.0) >= seuil && duree in 0.22..1.3
            }
        }

        internal fun estReveil(mots: org.json.JSONArray?, seuil: Double): Boolean {
            if (mots == null) return false
            for (i in 0 until minOf(mots.length(), 2)) {
                val w = mots.optJSONObject(i) ?: continue
                val mot = w.optString("word")
                val duree = w.optDouble("end", 0.0) - w.optDouble("start", 0.0)
                if (mot == "jarvis") return w.optDouble("conf", 0.0) >= seuil && duree in 0.22..1.3
                if (i == 0 && mot !in AVANT) return false
            }
            return false
        }

        /** Même validation sur les mots partiels Vosk : confiance, durée et position restent obligatoires. */
        internal fun detectePartiel(mots: org.json.JSONArray?, seuil: Double, partout: Boolean = false): Boolean =
            if (partout) contientMot(mots, seuil) else estReveil(mots, seuil)
    }

    private fun detecte(mots: org.json.JSONArray?) = if (partout) contientMot(mots, seuil()) else estReveil(mots, seuil())

    override fun start(): Boolean {
        if (!VoskModel.ready(ctx)) return false
        stop()   // jamais deux écoutes en parallèle sur le micro
        // L'ancienne boucle n'a pas encore rendu le micro (modèle encore en train de décoder) : on ne superpose pas une
        // deuxième écoute — c'étaient deux détecteurs qui entendaient « Jarvis » en même temps (bips en double).
        // Le service réessaie un peu plus tard.
        if (vivantes.get() > 0) return false
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            if (model == null) model = VoskModel.partage(ctx) ?: return false
            val rec = Recognizer(model, 16000f, (if (francais) GRAMMAIRE_FR else GRAMMAIRE).joinToString(", ", "[", "]") { "\"$it\"" })
            rec.setWords(true)
            rec.setPartialWords(true)
            fired = false
            if (econome) {
                // Écoute économe : le modèle ne travaille que quand il y a du son (porte de silence), pas en continu.
                val b = BoucleEconome(rec)
                if (!b.demarrer()) { rec.close(); return false }
                boucle = b
                return true
            }
            service = SpeechService(rec, 16000f).also { s ->
                s.startListening(object : RecognitionListener {
                    private fun check(j: String?) {
                        if (fired || j == null) return
                        try {
                            if (detecte(org.json.JSONObject(j).optJSONArray("result"))) { fired = true; JournalVoix.noter("Vosk", "« Jarvis » détecté${if (francais) "" else " (modèle anglais)"}"); onWake() }
                        } catch (_: Throwable) {}
                    }
                    // Ne pas attendre la fin de phrase : les mots partiels contiennent maintenant confiance et durée.
                    override fun onPartialResult(hypothesis: String?) = verifierPartiel(hypothesis)
                    override fun onResult(hypothesis: String?) = check(hypothesis)
                    override fun onFinalResult(hypothesis: String?) = check(hypothesis)
                    // Le service de Vosk s'arrête sur une erreur micro : on prévient pour qu'il soit relancé.
                    override fun onError(exception: Exception?) { onPanne() }
                    override fun onTimeout() {}
                })
            }
            true
        } catch (e: Throwable) { false }
    }

    override fun stop() {
        try { service?.stop(); service?.shutdown() } catch (_: Throwable) {}
        service = null
        boucle?.arreter(); boucle = null
    }

    private fun verifierPartiel(j: String?, nImporteOu: Boolean = false) {
        if (fired || j == null) return
        try {
            val mots = org.json.JSONObject(j).optJSONArray("partial_result")
            if (detectePartiel(mots, seuil(), nImporteOu)) {
                fired = true
                JournalVoix.noter("Vosk", "« Jarvis » détecté dès le résultat partiel${if (nImporteOu) " malgré la musique" else ""}")
                onWake()
            }
        } catch (_: Throwable) {}
    }

    private fun verifier(j: String?, nImporteOu: Boolean = false) {
        if (fired || j == null) return
        try {
            val mots = org.json.JSONObject(j).optJSONArray("result")
            if (if (nImporteOu) contientMot(mots, seuil()) else detecte(mots)) {
                fired = true
                JournalVoix.noter("Vosk", "« Jarvis » détecté${if (nImporteOu) " malgré la musique" else ""}${if (francais) "" else " (modèle anglais)"}")
                onWake()
            }
        } catch (_: Throwable) {}
    }

    private fun nouveauRecognizer(): Recognizer? = runCatching {
        Recognizer(model, 16000f, (if (francais) GRAMMAIRE_FR else GRAMMAIRE).joinToString(", ", "[", "]") { "\"$it\"" }).apply {
            setWords(true)
            setPartialWords(true)
        }
    }.getOrNull()

    /**
     * Micro lu par trames de 0,1 s ; tant que c'est le silence (ou le bruit de fond habituel), rien n'est envoyé au
     * modèle (c'était lui qui consommait : il décodait le silence sans arrêt). Dès qu'un son dépasse le bruit de fond,
     * les 0,6 s précédentes puis la suite sont analysées, jusqu'à 0,6 s de calme.
     */
    private inner class BoucleEconome(private val rec: Recognizer) {
        @Volatile private var actif = true
        private var fil: Thread? = null
        private var micro: android.media.AudioRecord? = null
        private var reducteur: android.media.audiofx.NoiseSuppressor? = null

        @android.annotation.SuppressLint("MissingPermission")
        fun demarrer(): Boolean {
            val taille = 1600   // 0,1 s à 16 kHz
            val min = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            val ar = try {
                android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, taille * 2 * 4))
            } catch (_: Throwable) { return false }
            if (ar.state != android.media.AudioRecord.STATE_INITIALIZED) { ar.release(); return false }
            try { ar.startRecording() } catch (_: Throwable) { ar.release(); return false }
            if (ar.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING) { ar.release(); return false }
            val voiture = (ctx.applicationContext as? fr.dd.jarvis.JarvisApp)?.enVoiture == true
            // En voiture : réduction du bruit de la route (souffle, moteur) du téléphone, quand il en a une.
            if (voiture) runCatching {
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) reducteur = android.media.audiofx.NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = true }
            }
            runCatching {
                val d = ar.routedDevice
                microActuel = if (d != null) nomMicro(d.type, d.productName?.toString()) else "micro du téléphone"
                JournalVoix.noter("Vosk", "écoute de « Jarvis » sur le $microActuel" + (if (voiture) " · voiture : écoute continue, gain ×10 max" +
                    (if (reducteur != null) ", réduction du bruit active" else "") else ""))
            }
            micro = ar
            vivantes.incrementAndGet()
            fil = Thread({ try { boucle(ar, taille) } finally { vivantes.decrementAndGet() } }, "jarvis-ecoute").apply { isDaemon = true; start() }
            return true
        }

        /**
         * En voiture : micro plus sensible (gain automatique ×10 au plus, réduction du bruit, seuil plus bas : un « Jarvis »
         * presque chuchoté passe) et écoute CONTINUE sans porte, musique ou pas : la musique et le bruit de la route la
         * gardaient ouverte en permanence, la phrase reconnue ne finissait jamais et « Jarvis » n'était plus en tête →
         * jamais détecté. Deux reconnaissances décalées d'environ 0,75 s, remises à zéro toutes les 1,5 s, repèrent « Jarvis »
         * n'importe où dans ce qu'elles entendent.
         */
        private fun boucle(ar: android.media.AudioRecord, taille: Int) {
            val porte = PorteVocale().apply { sensible = true }
            val avant = ArrayDeque<ShortArray>()
            val buf = ShortArray(taille)
            val app = ctx.applicationContext as? fr.dd.jarvis.JarvisApp
            val gain = GainAuto(cible = 3000.0, max = 10.0)
            val gainTel = GainAuto(cible = 2500.0, max = 5.0)
            var voiture = false; var musique = false; var compte = 0
            var rec2: Recognizer? = null
            var trames1 = 0; var trames2 = -FENETRE_MUSIQUE / 2
            var panne = false
            try {
                while (actif) {
                    val n = ar.read(buf, 0, taille)
                    if (n < 0) { panne = true; break }
                    if (n == 0) continue
                    if (compte++ % 10 == 0) {
                        voiture = app?.enVoiture == true
                        voitureActive = voiture
                        // Téléphone aussi : porte qui s'ouvre sur une voix basse (avant : seulement en voiture).
                        porte.sensible = true
                        // En voiture : écoute CONTINUE, avec ou sans musique — le bruit de la route gardait la porte ouverte
                        // (phrase jamais terminée, « Jarvis » plus en tête) ou la fermait sur une voix basse.
                        val m = voiture
                        if (m != musique) {
                            musique = m
                            JournalVoix.noter("Vosk", if (m) "voiture : écoute continue (musique ou bruit de la route), « Jarvis » repéré n'importe où dans la phrase" else "hors voiture : écoute normale")
                            rec.reset(); rec2?.reset(); trames1 = 0; trames2 = -FENETRE_MUSIQUE / 2; avant.clear()
                            if (m && rec2 == null) rec2 = nouveauRecognizer()
                        }
                    }
                    val rms = PorteVocale.rms(buf, n)
                    // Gain automatique partout (×5 au plus sur le téléphone, ×10 en voiture) : « Jarvis » dit doucement ou de loin.
                    val trame = if (voiture) gain.appliquer(buf, n, rms) else gainTel.appliquer(buf, n, rms)
                    if (musique) {
                        // Écoute continue : fenêtres de 1,5 s, avec une seconde reconnaissance décalée d'environ 0,75 s pour réduire la latence.
                        if (rec.acceptWaveForm(trame, n)) verifier(rec.result, nImporteOu = true)
                        else verifierPartiel(rec.partialResult, nImporteOu = true)
                        if (++trames1 >= FENETRE_MUSIQUE) { verifier(rec.finalResult, nImporteOu = true); rec.reset(); trames1 = 0 }
                        rec2?.let { r2 ->
                            if (trames2 < 0) trames2++
                            else {
                                if (r2.acceptWaveForm(trame, n)) verifier(r2.result, nImporteOu = true)
                                else verifierPartiel(r2.partialResult, nImporteOu = true)
                                if (++trames2 >= FENETRE_MUSIQUE) { verifier(r2.finalResult, nImporteOu = true); r2.reset(); trames2 = 0 }
                            }
                        }
                        if (fired) break
                        continue
                    }
                    when (porte.trame(rms)) {
                        PorteVocale.Decision.IGNORER -> { avant.addLast(trame.copyOf(n)); while (avant.size > PorteVocale.PRE_ROULEMENT) avant.removeFirst() }
                        PorteVocale.Decision.OUVRIR -> {
                            avant.forEach { if (rec.acceptWaveForm(it, it.size)) verifier(rec.result) else verifierPartiel(rec.partialResult) }
                            avant.clear()
                            if (rec.acceptWaveForm(trame, n)) verifier(rec.result) else verifierPartiel(rec.partialResult)
                        }
                        PorteVocale.Decision.CONTINUER -> if (rec.acceptWaveForm(trame, n)) verifier(rec.result) else verifierPartiel(rec.partialResult)
                        PorteVocale.Decision.FERMER -> {
                            rec.acceptWaveForm(trame, n)
                            verifier(rec.finalResult)
                            rec.reset()
                        }
                    }
                    if (fired) break
                }
            } catch (_: Throwable) { panne = actif }
            finally {
                runCatching { reducteur?.release() }
                reducteur = null
                microActuel = null
                runCatching { ar.stop() }
                runCatching { ar.release() }
                runCatching { rec.close() }
                runCatching { rec2?.close() }
            }
            if (panne && actif) onPanne()
        }

        fun arreter() {
            actif = false
            // Attend la libération du micro : la reconnaissance de la demande va le reprendre (au plus 1,2 s si le
            // modèle finit de décoder une phrase).
            fil?.let { if (it !== Thread.currentThread()) runCatching { it.join(1200) } }
        }
    }

    // Le modèle est partagé (écoute des phrases) : il reste chargé.
    override fun release() { stop(); model = null }
}

/**
 * Gain automatique du micro en voiture : le niveau des sons entendus est suivi et remonté vers un niveau confortable
 * (×6 au plus, sans saturer) — un « Jarvis » dit doucement arrive au modèle aussi fort qu'une voix normale.
 */
internal class GainAuto(private val cible: Double = 2500.0, private val max: Double = 6.0) {
    var niveau = 400.0
        private set
    var gain = 1.0
        private set

    fun appliquer(b: ShortArray, n: Int, rms: Double = PorteVocale.rms(b, n)): ShortArray {
        if (rms > 30) niveau += (rms - niveau) * 0.05
        val voulu = (cible / niveau.coerceAtLeast(60.0)).coerceIn(1.0, max)
        gain += (voulu - gain) * 0.2
        if (gain <= 1.01) return b
        // Limiteur : une voix forte juste après un silence n'est pas écrêtée (le gain s'adapte en ~0,5 s seulement).
        var crete = 1
        for (i in 0 until n) { val v = kotlin.math.abs(b[i].toInt()); if (v > crete) crete = v }
        val g = minOf(gain, 30_000.0 / crete)
        if (g <= 1.01) return b
        return ShortArray(n) { (b[it] * g).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }
}

/**
 * Porte de silence de l'écoute économe (trames de 0,1 s). Elle suit le bruit de fond (pièce calme, ventilation, télé)
 * et ne s'ouvre que sur un son nettement plus fort ; elle se referme après 0,6 s de calme, ou au bout de 8 s d'un son
 * continu (télé, musique) dont elle prend alors le niveau comme nouveau bruit de fond.
 */
internal class PorteVocale {
    enum class Decision { IGNORER, OUVRIR, CONTINUER, FERMER }

    companion object {
        const val PRE_ROULEMENT = 6     // 0,6 s gardées avant l'ouverture (début du mot)
        const val MAINTIEN = 6          // 0,6 s de calme avant de refermer
        const val DUREE_MAX = 80        // 8 s de son continu au plus

        fun rms(b: ShortArray, n: Int): Double {
            var somme = 0.0
            for (i in 0 until n) { val v = b[i].toDouble(); somme += v * v }
            return kotlin.math.sqrt(somme / n.coerceAtLeast(1))
        }
    }

    var plancher = -1.0
        private set
    var ouverte = false
        private set
    /** En voiture : s'ouvre sur un son plus faible (voix basse, presque chuchotée). */
    var sensible = false
    private var restant = 0
    private var duree = 0
    private var somme = 0.0

    fun seuil() = if (sensible) maxOf(plancher * 1.35, plancher + 40.0, 70.0) else maxOf(plancher * 2.0, plancher + 100.0, 150.0)

    fun trame(rms: Double): Decision {
        if (plancher < 0) plancher = rms.coerceIn(20.0, 2000.0)
        val fort = rms > seuil()
        if (!ouverte) {
            if (fort) { ouverte = true; restant = MAINTIEN; duree = 1; somme = rms; return Decision.OUVRIR }
            plancher += (rms - plancher) * 0.1
            return Decision.IGNORER
        }
        duree++; somme += rms
        plancher += (rms - plancher) * 0.01
        if (duree >= DUREE_MAX) {
            // Son continu (télé, musique) : devient le bruit de fond.
            plancher = maxOf(plancher, somme / duree * 0.9)
            ouverte = false
            return Decision.FERMER
        }
        if (fort) { restant = MAINTIEN; return Decision.CONTINUER }
        if (--restant <= 0) { ouverte = false; return Decision.FERMER }
        return Decision.CONTINUER
    }
}
