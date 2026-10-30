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
}

/** Vrai détecteur de mot d'éveil (Picovoice Porcupine, mot « Jarvis » intégré) : hors-ligne, très peu de faux déclenchements. */
class PorcupineWake(private val ctx: Context, private val accessKey: String, private val onWake: () -> Unit) : WakeDetector {
    private var mgr: ai.picovoice.porcupine.PorcupineManager? = null
    var error: String = ""

    override fun start(): Boolean {
        return try {
            if (mgr == null) {
                mgr = ai.picovoice.porcupine.PorcupineManager.Builder()
                    .setAccessKey(accessKey.trim())
                    .setKeyword(ai.picovoice.porcupine.Porcupine.BuiltInKeyword.JARVIS)
                    .setSensitivity(0.65f)
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

class VoskWake(private val ctx: Context, private val onWake: () -> Unit) : WakeDetector {
    private var model: Model? = null
    private var service: SpeechService? = null
    @Volatile private var fired = false

    override fun start(): Boolean {
        if (!VoskModel.ready(ctx)) return false
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            if (model == null) model = Model(VoskModel.dir(ctx).absolutePath)
            val rec = Recognizer(model, 16000f, "[\"jarvis\", \"[unk]\"]")
            rec.setWords(true)
            fired = false
            service = SpeechService(rec, 16000f).also { s ->
                s.startListening(object : RecognitionListener {
                    // Seuls les résultats définitifs comptent (plus de déclenchement sur les résultats partiels / bruits),
                    // et le mot « jarvis » doit être reconnu avec une bonne confiance.
                    private fun check(j: String?) {
                        if (fired || j == null) return
                        try {
                            val arr = org.json.JSONObject(j).optJSONArray("result") ?: return
                            for (i in 0 until arr.length()) {
                                val w = arr.getJSONObject(i)
                                if (w.optString("word") == "jarvis" && w.optDouble("conf", 0.0) >= 0.9 && w.optDouble("end", 0.0) - w.optDouble("start", 0.0) >= 0.25) {
                                    fired = true; onWake(); return
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    override fun onPartialResult(hypothesis: String?) {}
                    override fun onResult(hypothesis: String?) = check(hypothesis)
                    override fun onFinalResult(hypothesis: String?) = check(hypothesis)
                    override fun onError(exception: Exception?) {}
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
