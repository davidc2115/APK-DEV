package fr.dd.jarvis.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
 * Le modèle anglais est utilisé car « jarvis » fait partie de son vocabulaire ; la grammaire se limite à ce seul mot.
 */
object VoskModel {
    private const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    fun dir(ctx: Context) = File(ctx.filesDir, "vosk-wake")
    fun ready(ctx: Context) = File(dir(ctx), "am").isDirectory && File(dir(ctx), "conf").isDirectory

    suspend fun download(ctx: Context, onProgress: (Int) -> Unit): String = withContext(Dispatchers.IO) {
        try {
            val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            val tmp = File(ctx.cacheDir, "vosk-wake").apply { deleteRecursively(); mkdirs() }
            http.newCall(Request.Builder().url(URL).build()).execute().use { r ->
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
            dir(ctx).deleteRecursively()
            if (!tmp.renameTo(dir(ctx))) { tmp.copyRecursively(dir(ctx), true); tmp.deleteRecursively() }
            if (ready(ctx)) "✅ Détecteur « Jarvis » installé (hors-ligne)." else "❌ Modèle incomplet après décompression."
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
                    .build(ctx, ai.picovoice.porcupine.PorcupineManagerCallback { onWake() })
            }
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
class VoskWake(private val ctx: Context, private val onWake: () -> Unit) : WakeDetector {
    private var model: Model? = null
    private var service: SpeechService? = null
    @Volatile private var fired = false
    override var onPanne: () -> Unit = {}
    @Volatile override var exigence: Int = 1
    private fun seuil() = when (exigence) { 1 -> 0.88; 2 -> 0.93; else -> 0.97 }

    companion object {
        /** Mots du modèle anglais qui servent de leurres autour de « jarvis » (tous présents dans son vocabulaire). */
        private val GRAMMAIRE = listOf(
            "jarvis", "service", "travis", "harvest", "charles", "nervous", "marvel", "garage", "jars", "office",
            "yes", "no", "okay", "hey", "alexa", "google", "[unk]"
        )
        internal fun contientJarvis(texte: String) = Regex("\\bjarvis\\b").containsMatchIn(texte)

        /**
         * Vrai si la phrase reconnue commence par « jarvis » (1er mot, ou 2e après un bruit / un « hey »), avec une
         * confiance ≥ [seuil] et une durée de mot plausible (0,22 à 1,3 s).
         */
        internal fun estReveil(mots: org.json.JSONArray?, seuil: Double): Boolean {
            if (mots == null) return false
            for (i in 0 until minOf(mots.length(), 2)) {
                val w = mots.optJSONObject(i) ?: continue
                val mot = w.optString("word")
                val duree = w.optDouble("end", 0.0) - w.optDouble("start", 0.0)
                if (mot == "jarvis") return w.optDouble("conf", 0.0) >= seuil && duree in 0.22..1.3
                if (i == 0 && mot != "[unk]" && mot != "hey" && mot != "okay") return false
            }
            return false
        }
    }

    override fun start(): Boolean {
        if (!VoskModel.ready(ctx)) return false
        stop()   // jamais deux écoutes en parallèle sur le micro
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            if (model == null) model = Model(VoskModel.dir(ctx).absolutePath)
            val rec = Recognizer(model, 16000f, GRAMMAIRE.joinToString(", ", "[", "]") { "\"$it\"" })
            rec.setWords(true)
            fired = false
            service = SpeechService(rec, 16000f).also { s ->
                s.startListening(object : RecognitionListener {
                    private fun check(j: String?) {
                        if (fired || j == null) return
                        try {
                            if (estReveil(org.json.JSONObject(j).optJSONArray("result"), seuil())) { fired = true; onWake() }
                        } catch (_: Throwable) {}
                    }
                    override fun onPartialResult(hypothesis: String?) {}
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
    }

    override fun release() { stop(); try { model?.close() } catch (_: Throwable) {}; model = null }
}
