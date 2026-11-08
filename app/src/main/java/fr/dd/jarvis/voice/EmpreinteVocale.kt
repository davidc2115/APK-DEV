package fr.dd.jarvis.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

/**
 * Empreinte vocale du propriétaire, 100 % hors ligne (modèle « locuteur » de Vosk, ~13 Mo) : JARVIS apprend ta voix
 * avec 3 phrases, puis peut vérifier que c'est bien toi qui parles avant une action sensible demandée à la voix
 * (téléphone verrouillé). Chaque phrase donne un « x-vecteur » ; la comparaison est un cosinus avec ta moyenne, et
 * le seuil est calé sur la régularité de ta propre voix pendant l'enregistrement.
 */
object EmpreinteVocale {
    private const val URL_SPK = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    private const val TAUX = 16000

    val PHRASES = listOf(
        "Jarvis, c'est bien moi, je confirme cette action sur mon téléphone.",
        "Aujourd'hui je vérifie les onduleurs et la production des panneaux solaires.",
        "Mon assistant reconnaît ma voix, même quand je parle un peu plus vite.",
    )
    const val PHRASE_VERIF = "Jarvis, c'est bien moi"

    fun dossierModele(ctx: Context) = File(ctx.filesDir, "vosk-spk")
    fun modelePret(ctx: Context) = File(dossierModele(ctx), "final.ext.raw").exists() || (dossierModele(ctx).listFiles()?.any { it.name.endsWith(".raw") } == true)
    private fun fichierProfil(ctx: Context) = File(ctx.filesDir, "empreinte_voix.json")
    fun enregistree(ctx: Context) = fichierProfil(ctx).exists()
    fun prete(app: JarvisApp) = app.store.empreinteVocale.value && enregistree(app) && modelePret(app) && VoskModel.ready(app)
    fun effacer(ctx: Context) { fichierProfil(ctx).delete() }

    // ------------------------------------------------------------------ calculs (testables)

    internal fun cosinus(a: FloatArray, b: FloatArray): Float {
        var p = 0.0; var na = 0.0; var nb = 0.0
        for (i in 0 until minOf(a.size, b.size)) { p += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0f else (p / (sqrt(na) * sqrt(nb))).toFloat()
    }

    internal fun moyenne(v: List<FloatArray>): FloatArray {
        val n = v.first().size
        return FloatArray(n) { i -> v.sumOf { (it.getOrElse(i) { 0f }).toDouble() }.toFloat() / v.size }
    }

    /** Seuil personnel : régularité de ta voix (chaque phrase comparée aux autres) moins une marge, borné. */
    internal fun seuilDe(v: List<FloatArray>): Float {
        if (v.size < 2) return 0.5f
        val intra = v.indices.map { i -> cosinus(v[i], moyenne(v.filterIndexed { j, _ -> j != i })) }
        return (intra.minOrNull()!! - 0.15f).coerceIn(0.30f, 0.75f)
    }

    // ------------------------------------------------------------------ modèles

    /** Télécharge ce qui manque : modèle locuteur (13 Mo) et petit modèle de reconnaissance de Vosk (40 Mo). */
    suspend fun installer(ctx: Context, onProgression: (String) -> Unit): String = withContext(Dispatchers.IO) {
        if (!VoskModel.ready(ctx)) {
            onProgression("Téléchargement du modèle de voix (40 Mo)…")
            val r = VoskModel.download(ctx) { p -> onProgression("Modèle de voix : $p %") }
            if (!VoskModel.ready(ctx)) return@withContext r
        }
        if (modelePret(ctx)) return@withContext "✅ Modèles prêts."
        onProgression("Téléchargement du modèle « locuteur » (13 Mo)…")
        try {
            val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            val tmp = File(ctx.cacheDir, "vosk-spk").apply { deleteRecursively(); mkdirs() }
            http.newCall(Request.Builder().url(URL_SPK).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "❌ Téléchargement refusé (${r.code})."
                ZipInputStream(r.body!!.byteStream()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isNotBlank()) {
                            val f = File(tmp, rel)
                            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { o -> z.copyTo(o) } }
                        }
                        e = z.nextEntry
                    }
                }
            }
            dossierModele(ctx).deleteRecursively()
            if (!tmp.renameTo(dossierModele(ctx))) { tmp.copyRecursively(dossierModele(ctx), true); tmp.deleteRecursively() }
            if (modelePret(ctx)) "✅ Modèles prêts." else "❌ Modèle « locuteur » incomplet."
        } catch (e: Throwable) { "❌ " + (e.message ?: e.javaClass.simpleName) }
    }

    // ------------------------------------------------------------------ audio

    /**
     * Enregistre une phrase au micro (16 kHz) : jusqu'à [maxMs], arrêt après ~0,9 s de silence une fois la parole
     * commencée. Renvoie le PCM 16 bits, ou null si personne n'a parlé.
     */
    @SuppressLint("MissingPermission")
    fun enregistrer(maxMs: Long = 6000, attenteMs: Long = 4000): ByteArray? {
        val min = AudioRecord.getMinBufferSize(TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3200)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(640) // 20 ms
        var parle = false; var silence = 0; var voix = 0
        var plancher = 300.0
        val debut = System.currentTimeMillis()
        try {
            rec.startRecording()
            while (System.currentTimeMillis() - debut < maxMs) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                out.write(buf, 0, n)
                val rms = fr.dd.jarvis.car.Pcm.rms(buf, 0, n, false)
                if (!parle) plancher = 0.95 * plancher + 0.05 * rms
                val fort = rms > maxOf(500.0, plancher * 3)
                if (fort) { voix++; silence = 0; if (voix >= 5) parle = true } else silence++
                if (parle && silence > 45) break
                if (!parle && System.currentTimeMillis() - debut > attenteMs) break
            }
        } finally { runCatching { rec.stop() }; rec.release() }
        return if (parle) out.toByteArray() else null
    }

    /** X-vecteur d'un enregistrement (modèle locuteur de Vosk). */
    fun vecteur(ctx: Context, pcm: ByteArray): FloatArray? {
        val model = org.vosk.Model(VoskModel.dir(ctx).absolutePath)
        val spk = org.vosk.SpeakerModel(dossierModele(ctx).absolutePath)
        try {
            val rec = org.vosk.Recognizer(model, TAUX.toFloat(), spk)
            try {
                var i = 0
                while (i < pcm.size) { val n = minOf(8000, pcm.size - i); rec.acceptWaveForm(pcm.copyOfRange(i, i + n), n); i += n }
                val a = JSONObject(rec.finalResult).optJSONArray("spk") ?: return null
                return FloatArray(a.length()) { a.optDouble(it).toFloat() }
            } finally { rec.close() }
        } finally { spk.close(); model.close() }
    }

    // ------------------------------------------------------------------ profil

    fun sauver(ctx: Context, vecteurs: List<FloatArray>): Float {
        val seuil = seuilDe(vecteurs)
        val o = JSONObject().put("seuil", seuil.toDouble())
            .put("moyenne", JSONArray(moyenne(vecteurs).map { it.toDouble() }))
            .put("vecteurs", JSONArray(vecteurs.map { v -> JSONArray(v.map { it.toDouble() }) }))
        fichierProfil(ctx).writeText(o.toString())
        return seuil
    }

    private fun profil(ctx: Context): Pair<FloatArray, Float>? = runCatching {
        val o = JSONObject(fichierProfil(ctx).readText())
        val m = o.getJSONArray("moyenne")
        FloatArray(m.length()) { m.optDouble(it).toFloat() } to o.optDouble("seuil", 0.5).toFloat()
    }.getOrNull()

    data class Resultat(val ok: Boolean, val score: Float, val seuil: Float, val message: String)

    /** Écoute une phrase et la compare à ton empreinte. */
    suspend fun verifier(app: JarvisApp): Resultat = withContext(Dispatchers.IO) {
        val (moy, seuilProfil) = profil(app) ?: return@withContext Resultat(false, 0f, 0f, "aucune empreinte enregistrée")
        val seuil = app.store.empreinteSeuil.value.toFloatOrNull() ?: seuilProfil
        val pcm = enregistrer() ?: return@withContext Resultat(false, 0f, seuil, "je n'ai rien entendu")
        val v = runCatching { vecteur(app, pcm) }.getOrNull() ?: return@withContext Resultat(false, 0f, seuil, "phrase trop courte")
        val score = cosinus(v, moy)
        Resultat(score >= seuil, score, seuil, if (score >= seuil) "voix reconnue" else "voix non reconnue")
    }
}
