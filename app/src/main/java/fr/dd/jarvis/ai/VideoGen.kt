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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Génération de courtes vidéos IA (plans individuels) via Hugging Face — un Space officiel « ZeroGPU »
 * (Space ZeroGPU Wan 2.2 image → vidéo), utilisable GRATUITEMENT avec un simple compte Hugging Face (sans carte bancaire), mais avec
 * un vrai quota journalier de calcul GPU limité (≈5 min/jour pour un compte gratuit) : peut être lent (file
 * d'attente partagée) ou refuser une fois le quota du jour épuisé — SANS garantie de service (c'est un service
 * communautaire, pas une offre payante avec SLA). Pollinations.ai, utilisé avant, exige désormais un abonnement
 * payant même pour la vidéo et n'est donc plus utilisable gratuitement.
 * Les plans générés sont ensuite montés bout à bout avec androidx.media3 Transformer (aucun service externe).
 */
object VideoGen {
    /**
     * Space ZeroGPU « zerogpu-aoti/wan2-2-fp8da-aoti-faster » (Wan 2.2 14B image → vidéo, Lightning LoRA, maintenu par
     * l'équipe ZeroGPU de Hugging Face) : GRATUIT avec un compte Hugging Face (5 min de GPU par jour, sans carte), environ
     * 35 s de GPU par clip de 3,5 s. L'ancien Space officiel « Wan-AI/Wan-2.2-5B » a été supprimé (erreur 404).
     */
    private const val HF_SPACE = "https://zerogpu-aoti-wan2-2-fp8da-aoti-faster.hf.space"
    /** Prompt négatif par défaut du Space (modèle Wan, rédigé en chinois). */
    private const val NEGATIF = "色调艳丽, 过曝, 静态, 细节模糊不清, 字幕, 风格, 作品, 画作, 画面, 静止, 整体发灰, 最差质量, 低质量, JPEG压缩残留, 丑陋的, 残缺的, 多余的手指, " +
        "画得不好的手部, 画得不好的脸部, 畸形的, 毁容的, 形态畸形的肢体, 手指融合, 静止不动的画面, 杂乱的背景, 三条腿, 背景人很多, 倒着走"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(320, TimeUnit.SECONDS).callTimeout(360, TimeUnit.SECONDS).build()

    fun dossier(app: JarvisApp) = File(app.filesDir, "videos_ia").apply { mkdirs() }

    /**
     * Analyse le brief avant un clip unique ou l'animation d'une image, puis rédige un prompt vidéo fidèle.
     * En cas d'échec de l'analyse, aucun appel payant au fournisseur vidéo n'est lancé.
     */
    suspend fun preparerPrompt(
        app: JarvisApp,
        brief: String,
        duree: Double,
        format: String = "vertical",
        animerImage: Boolean = false
    ): String {
        if (brief.isBlank()) throw IllegalStateException("Décris précisément ce que la vidéo doit montrer.")
        val ratio = if (animerImage) "auto (ratio de l'image source à préserver)" else ImageToVideoGen.rapport(format)
        val contexte = buildString {
            append("Brief original de l'utilisateur :\n")
            append(brief.trim())
            append("\nDurée du clip : ${duree.coerceIn(0.5, 5.0)} secondes.")
            append("\nFormat demandé : $ratio.")
            append(if (ratio == "9:16") " Portrait vertical 9:16, sujet centré dans la zone sûre." else " Respect strict du ratio demandé.")
            if (animerImage) append("\nMode image-vers-vidéo : l'image source existe déjà. Préserve exactement son sujet, son identité, ses vêtements, ses objets, son décor, sa composition et son style ; n'invente pas de nouvel élément.")
        }
        val analyseBrute = app.llm.agent(Prompts.MONTAGE_ANALYSE, contexte)
        val analyse = runCatching {
            val debut = analyseBrute.indexOf('{')
            val fin = analyseBrute.lastIndexOf('}')
            if (debut < 0 || fin <= debut) "" else JSONObject(analyseBrute.substring(debut, fin + 1)).optString("brief_generation")
        }.getOrDefault("")
        if (analyse.isBlank()) throw IllegalStateException("L'analyse préalable n'a pas produit de brief réalisateur exploitable. Aucune vidéo n'a été envoyée au fournisseur.")
        val contextePrompt = contexte + "\n\nAnalyse préalable :\n" + analyse +
            if (animerImage) "\n\nAnime uniquement les mouvements demandés dans l'image source." else ""
        val prompt = app.llm.agent(Prompts.VIDEO_SINGLE, contextePrompt).trim()
        if (prompt.length < 20) throw IllegalStateException("Le prompt vidéo préparé est trop court ; aucune génération n'a été lancée.")
        return prompt.removePrefix("```").removeSuffix("```").trim()
    }

    /** Génère un plan (clip) via le Space Hugging Face ; retourne les octets mp4. Erreur explicite si le token
     *  manque/est refusé, si le quota GPU gratuit du jour est épuisé, ou si le service est indisponible. */
    internal suspend fun genereClip(app: JarvisApp, prompt: String, dureeDemandee: Int, vertical: Boolean, image: File? = null): ByteArray {
        // Génération vidéo gratuite via Hugging Face ZeroGPU. Aucun appel fal.ai n'est effectué.
        val token = app.store.huggingFaceToken.value.trim()
        if (token.isBlank()) throw ApiException(401,
            "Token Hugging Face gratuit manquant : crée-en un sur huggingface.co/settings/tokens puis ajoute-le dans Réglages › IA › Images & vidéo. fal.ai n'est plus utilisé pour la génération vidéo.")
        // Le service anime une IMAGE : sans image fournie, JARVIS en crée une d'abord (Cloudflare ou Hugging Face, gratuits), au bon format.
        val depart = image ?: runCatching {
            ImageGen.generate(app, "$prompt, ${if (vertical) "vertical portrait 9:16" else "horizontal 16:9"} composition, cinematic, highly detailed, no text",
                "flux-2-klein-4b", if (vertical) 768 else 1344, if (vertical) 1344 else 768).first
        }.getOrElse { throw ApiException(0, "pas d'image de départ à animer (génération d'image impossible : ${it.message?.take(160)})") }
        return withContext(Dispatchers.IO) { animer(token, depart, prompt, dureeDemandee) }
    }

    /** Anime [image] sur le Space ZeroGPU (API Gradio : envoi de l'image, appel, suivi en flux) ; octets mp4. */
    private fun animer(token: String, image: File, prompt: String, dureeDemandee: Int): ByteArray {
        val duree = dureeDemandee.coerceIn(2, 5).toDouble()   // 0,5 à 5 s possibles (8 à 80 images à 16 i/s)
        val fichier = JSONObject().put("path", envoyerFichier(token, image)).put("orig_name", image.name)
            .put("meta", JSONObject().put("_type", "gradio.FileData"))
        // Paramètres de generate_video : image, prompt, étapes (4 : rapide, ménage le quota gratuit), prompt négatif,
        // durée, guidances (1 et 1 avec la LoRA Lightning), graine, graine aléatoire.
        val data = JSONArray().put(fichier).put(prompt.take(1500)).put(4).put(NEGATIF).put(duree).put(1.0).put(1.0).put(42).put(true)
        val corps = JSONObject().put("data", data).toString().toRequestBody("application/json".toMediaType())
        val soumission = Request.Builder()
            .url("$HF_SPACE/gradio_api/call/generate_video")
            .header("Authorization", "Bearer $token")
            .post(corps)
            .build()
        val eventId: String
        http.newCall(soumission).execute().use { r ->
            val brut = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, erreurSoumission(r.code, brut))
            eventId = runCatching { JSONObject(brut).getString("event_id") }.getOrNull()
                ?: throw ApiException(0, "réponse inattendue de Hugging Face : ${brut.take(200)}")
        }

        val resultat = Request.Builder()
            .url("$HF_SPACE/gradio_api/call/generate_video/$eventId")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(resultat).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "suivi de la génération refusé (${r.code})")
            val lecteur = r.body?.charStream()?.buffered() ?: throw ApiException(0, "flux de génération vide")
            var typeEvenement = "message"
            while (true) {
                val ligne = lecteur.readLine() ?: break
                when {
                    ligne.startsWith("event:") -> typeEvenement = ligne.removePrefix("event:").trim()
                    ligne.startsWith("data:") -> {
                        val donnee = ligne.removePrefix("data:").trim()
                        when (typeEvenement) {
                            "complete" -> return telechargerClip(token, donnee)
                            "error" -> throw ApiException(500, erreurGeneration(token, donnee))
                            else -> { /* "generating" / heartbeat : on continue d'attendre */ }
                        }
                    }
                }
            }
        }
        throw ApiException(0, "flux de génération interrompu sans résultat (service peut-être surchargé)")
    }

    /** Envoie un fichier au Space (API Gradio « upload ») ; renvoie son chemin côté serveur. */
    private fun envoyerFichier(token: String, f: File): String {
        val corps = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("files", f.name, f.readBytes().toRequestBody((if (f.extension.lowercase() == "png") "image/png" else "image/jpeg").toMediaType())).build()
        val req = Request.Builder().url("$HF_SPACE/gradio_api/upload").header("Authorization", "Bearer $token").post(corps).build()
        http.newCall(req).execute().use { r ->
            val brut = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "envoi de l'image à Hugging Face refusé (${r.code}) : ${brut.take(120)}")
            return runCatching { JSONArray(brut).getString(0) }.getOrNull() ?: throw ApiException(0, "réponse d'envoi inattendue : ${brut.take(120)}")
        }
    }

    private fun erreurSoumission(code: Int, brut: String): String = when {
        code == 401 || code == 403 -> "token Hugging Face refusé (vérifie qu'il est bien à jour dans Réglages › IA › Images & vidéo)"
        code == 429 -> "file d'attente du service gratuit pleine pour l'instant (réessaie dans quelques minutes)"
        code >= 500 -> "service de génération vidéo gratuit (Hugging Face) temporairement indisponible"
        else -> "génération refusée ($code) : ${brut.take(200)}"
    }

    private fun erreurGeneration(token: String, donnee: String): String {
        val detail = runCatching { JSONObject(donnee).let { it.optString("error").ifBlank { it.optString("title") } } }.getOrDefault(donnee).trim()
        return when {
            HfImage.quota(detail) -> "quota GPU gratuit du jour épuisé sur Hugging Face (≈ 3 utilisations par jour : il revient 24 h après la première)"
            // Refus sans explication (« null ») : jeton refusé ou quota épuisé, vérifié auprès de Hugging Face.
            detail.isBlank() || detail == "null" -> HfImage.diagnosticRefus(token)
            else -> "génération refusée par Hugging Face : ${detail.take(250)}"
        }
    }

    /** La réponse SSE « complete » est un tableau JSON dont le 1er élément décrit le fichier vidéo généré (objet
     *  avec un « url »/« path », ou simple chemin texte selon la version de Gradio) : on le résout en octets mp4. */
    private fun telechargerClip(token: String, donneeJson: String): ByteArray {
        val valeur = runCatching { JSONArray(donneeJson).opt(0) }.getOrNull()
            ?: throw ApiException(0, "résultat de génération vide")
        val urlVideo = when (val v = (valeur as? JSONObject)?.optJSONObject("video") ?: valeur) {
            is JSONObject -> v.optString("url").ifBlank {
                val chemin = v.optString("path")
                if (chemin.isNotBlank()) "$HF_SPACE/gradio_api/file=$chemin" else ""
            }
            is String -> if (v.startsWith("http")) v else "$HF_SPACE/gradio_api/file=$v"
            else -> ""
        }
        if (urlVideo.isBlank()) throw ApiException(0, "réponse de génération inattendue (pas de fichier vidéo) : ${donneeJson.take(200)}")
        val req = Request.Builder().url(urlVideo).header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "téléchargement du clip généré refusé (${r.code})")
            return r.body?.bytes() ?: ByteArray(0)
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
        app: JarvisApp, sujet: String, script: String, dureePlan: Int, vertical: Boolean
    ): Pair<File?, String> = withContext(Dispatchers.IO) {
        // Même sans token HF, on prépare les plans et on propose les solutions web de secours.
        val listePlans = plans(app, sujet, script)
        if (listePlans.isEmpty()) return@withContext null to "❌ Impossible de découper le sujet en plans filmables."
        val clips = mutableListOf<File>()
        val echecs = mutableListOf<String>()
        listePlans.forEachIndexed { i, plan ->
            try {
                val bytes = try { genereClip(app, plan, dureePlan, vertical) }
                catch (hf: Exception) { VideoProviderGen.genererClipSecours(app, plan, dureePlan, vertical) ?: throw hf }
                val f = File(dossier(app), "clip_" + System.currentTimeMillis() + "_" + i + ".mp4")
                f.writeBytes(bytes)
                clips.add(f)
            } catch (e: Exception) {
                echecs.add("Plan " + (i + 1) + " (« " + plan.take(50) + " ») : " + e.message)
            }
        }
        if (clips.isEmpty()) {
            val secours = buildString {
                append("❌ Hugging Face n'a généré aucun plan. Tu peux poursuivre sans perdre le travail de préparation :")
                append("\n\nConfigure une clé UseAPI Flow ou BytePlus Dreamina dans Réglages › IA › Images & vidéo pour activer les secours automatiques.")
                append("\n\n**Prompts préparés :**")
                listePlans.forEachIndexed { index, prompt ->
                    append("\n\nPlan ${index + 1} :\n")
                    append(prompt)
                }
                if (echecs.isNotEmpty()) append("\n\nDétail HF :\n" + echecs.joinToString("\n"))
            }
            return@withContext null to secours
        }
        val assemble = if (clips.size == 1) clips[0] else try { monter(app, clips) } catch (e: Exception) {
            return@withContext clips[0] to "⚠️ Montage impossible (${e.message}), voici le premier plan seul : ${clips[0].name}"
        }
        val nomFinal = "tiktok_${System.currentTimeMillis()}.mp4"
        val fFinal = if (assemble.name == nomFinal) assemble else File(dossier(app), nomFinal).also { assemble.copyTo(it, overwrite = true) }
        val loc = app.obsidian.saveBytes("JARVIS/Vidéos TikTok", nomFinal, fFinal.readBytes(), "video/mp4")
        val msg = buildString {
            append("🎬 Vidéo générée (${clips.size}/${listePlans.size} plans réussis, Hugging Face / Flow / Dreamina) : ${fFinal.name}")
            append("\n\n[▶ Lire la vidéo](jarvis-video-ia:${fFinal.name})")
            if (loc.isNotBlank()) append("\n📁 $loc")
            if (echecs.isNotEmpty()) {
                append("\n⚠️ Plans manquants :\n")
                append(echecs.joinToString("\n"))
                append("\n\nConfigure Flow ou Dreamina dans Réglages › IA › Images & vidéo pour activer les fournisseurs de secours sur les prochaines générations.")
                echecs.forEach { detail ->
                    val plan = listePlans.firstOrNull { detail.contains(it.take(50)) }
                    if (plan != null) append("\n\nPrompt à reprendre :\n").append(plan)
                }
            }
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
