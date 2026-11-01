package fr.dd.jarvis.ai

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Génération de courtes vidéos IA (plans individuels) via Pollinations.ai — un service communautaire GRATUIT, sans
 * carte bancaire, mais SANS garantie de service (pas de SLA, quota gratuit limité, peut ralentir ou refuser) —
 * puis montage de ces plans en une seule vidéo avec androidx.media3 Transformer (bout à bout, aucun service payant).
 */
object VideoGen {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).callTimeout(200, TimeUnit.SECONDS).build()

    fun dossier(app: JarvisApp) = File(app.filesDir, "videos_ia").apply { mkdirs() }

    /** Génère un plan (clip) ; retourne les octets mp4. Erreur explicite si le quota gratuit est atteint ou le service indisponible. */
    private fun genereClip(prompt: String, dureeDemandee: Int, ratio: String, audio: Boolean, modele: String): ByteArray {
        val duree = if (modele == "seedance") dureeDemandee.coerceIn(2, 10) else listOf(4, 6, 8).minBy { kotlin.math.abs(it - dureeDemandee) }
        val url = "https://gen.pollinations.ai/image/" + java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
            "?model=$modele&duration=$duree&aspectRatio=$ratio" + (if (modele == "veo") "&audio=$audio" else "")
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val ct = r.header("Content-Type").orEmpty()
            if (!r.isSuccessful || !ct.startsWith("video")) {
                val brut = String(bytes.copyOf(minOf(bytes.size, 300)))
                val msg = when {
                    r.code == 429 -> "quota gratuit Pollinations atteint pour l'instant (service communautaire sans garantie)"
                    r.code >= 500 || r.code == 0 -> "service de génération vidéo gratuit temporairement indisponible"
                    else -> "génération refusée (${r.code}) : $brut"
                }
                throw ApiException(r.code, msg)
            }
            return bytes
        }
    }

    /** Découpe un script/sujet en 3 à 6 plans filmables (en anglais, pour le modèle vidéo) via l'agent IA. */
    private suspend fun plans(app: JarvisApp, sujet: String, script: String): List<String> {
        val base = script.ifBlank { sujet }
        val reponse = app.llm.agent(Prompts.TIKTOK_PLANS, base)
        return reponse.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotBlank() }.take(6)
    }

    /**
     * Génère une vidéo TikTok complète : découpe en plans, génère chaque plan (service gratuit, sans garantie), puis
     * monte les plans réussis en une seule vidéo. Un plan en échec (quota, service indisponible) n'empêche pas les
     * autres : le montage final reprend ceux qui ont marché, et le message final liste ce qui a échoué.
     */
    suspend fun genererVideo(
        app: JarvisApp, sujet: String, script: String, dureePlan: Int, vertical: Boolean, audio: Boolean, modele: String
    ): Pair<File?, String> = withContext(Dispatchers.IO) {
        val listePlans = plans(app, sujet, script)
        if (listePlans.isEmpty()) return@withContext null to "❌ Impossible de découper le sujet en plans filmables."
        val ratio = if (vertical) "9:16" else "16:9"
        val clips = mutableListOf<File>()
        val echecs = mutableListOf<String>()
        listePlans.forEachIndexed { i, plan ->
            try {
                val bytes = genereClip(plan, dureePlan, ratio, audio, modele)
                val f = File(dossier(app), "clip_${System.currentTimeMillis()}_$i.mp4")
                f.writeBytes(bytes)
                clips.add(f)
            } catch (e: Exception) {
                echecs.add("Plan ${i + 1} (« ${plan.take(50)} ») : ${e.message}")
            }
        }
        if (clips.isEmpty()) return@withContext null to "❌ Aucun plan n'a pu être généré :\n" + echecs.joinToString("\n")
        val assemble = if (clips.size == 1) clips[0] else try { monter(app, clips) } catch (e: Exception) {
            return@withContext clips[0] to "⚠️ Montage impossible (${e.message}), voici le premier plan seul : ${clips[0].name}"
        }
        val nomFinal = "tiktok_${System.currentTimeMillis()}.mp4"
        val fFinal = if (assemble.name == nomFinal) assemble else File(dossier(app), nomFinal).also { assemble.copyTo(it, overwrite = true) }
        val loc = runCatching { app.obsidian.saveBytes("JARVIS/Vidéos TikTok", nomFinal, fFinal.readBytes(), "video/mp4") }.getOrDefault("")
        val msg = buildString {
            append("🎬 Vidéo générée (${clips.size}/${listePlans.size} plans réussis) : ${fFinal.name}")
            if (loc.isNotBlank()) append("\n📁 $loc")
            if (echecs.isNotEmpty()) { append("\n⚠️ Plans manquants :\n"); append(echecs.joinToString("\n")) }
        }
        fFinal to msg
    }

    /** Retrouve une vidéo IA déjà générée (jarvis-video-ia:... ou « derniere »). */
    fun fichier(app: JarvisApp, nom: String): File? {
        val d = dossier(app)
        val n = nom.trim().removePrefix("jarvis-video-ia:").trim()
        if (n.isBlank() || n.lowercase() in setOf("derniere", "dernière", "dernier", "last", "recente", "récente")) {
            return d.listFiles()?.filter { it.isFile && it.name.startsWith("tiktok_") }?.maxByOrNull { it.lastModified() }
        }
        return File(d, n).takeIf { it.exists() }
    }

    /** Assemble plusieurs clips bout à bout en une seule vidéo (androidx.media3 Transformer, aucun service externe). */
    @OptIn(UnstableApi::class)
    private suspend fun monter(app: JarvisApp, clips: List<File>): File = withContext(Dispatchers.Main) {
        val out = File(dossier(app), "montage_${System.currentTimeMillis()}.mp4")
        out.parentFile?.mkdirs()
        suspendCancellableCoroutine { cont ->
            val items = clips.map { f -> EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f))).build() }
            val sequence = EditedMediaItemSequence(items)
            val composition = Composition.Builder(sequence).build()
            val transformer = Transformer.Builder(app)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        if (cont.isActive) cont.resume(out)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                })
                .build()
            transformer.start(composition, out.absolutePath)
        }
    }
}
