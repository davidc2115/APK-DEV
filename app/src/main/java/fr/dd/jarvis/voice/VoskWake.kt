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

class VoskWake(private val ctx: Context, private val onWake: () -> Unit) {
    private var model: Model? = null
    private var service: SpeechService? = null
    @Volatile private var fired = false

    fun start(): Boolean {
        if (!VoskModel.ready(ctx)) return false
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            if (model == null) model = Model(VoskModel.dir(ctx).absolutePath)
            val rec = Recognizer(model, 16000f, "[\"jarvis\", \"[unk]\"]")
            fired = false
            service = SpeechService(rec, 16000f).also { s ->
                s.startListening(object : RecognitionListener {
                    private fun check(j: String?) {
                        if (!fired && j != null && j.contains("jarvis")) { fired = true; onWake() }
                    }
                    override fun onPartialResult(hypothesis: String?) = check(hypothesis)
                    override fun onResult(hypothesis: String?) = check(hypothesis)
                    override fun onFinalResult(hypothesis: String?) = check(hypothesis)
                    override fun onError(exception: Exception?) {}
                    override fun onTimeout() {}
                })
            }
            true
        } catch (e: Throwable) { false }
    }

    fun stop() {
        try { service?.stop(); service?.shutdown() } catch (_: Throwable) {}
        service = null
    }

    fun release() { stop(); try { model?.close() } catch (_: Throwable) {}; model = null }
}
