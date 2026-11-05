package fr.dd.jarvis.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Montages vidéo (TikTok ou autre) : un ou plusieurs films d'une DURÉE précise, faits de plans IA (fal.ai) et/ou de
 * vidéos et images existantes (coffre, vidéos déjà générées), avec texte à l'écran, voix off (synthèse vocale du
 * téléphone), musique (fichier existant, ou générée : instrumentale, ou chanson avec paroles chantées).
 * Tout le montage est fait sur le téléphone (androidx.media3 Transformer). Long : lancé en arrière-plan, le
 * résultat arrive dans le chat et en notification.
 */
object Montage {
    data class Demande(
        val sujet: String,
        val script: String = "",
        val dureeS: Int = 30,
        val format: String = "vertical",
        val nombre: Int = 1,
        /** auto | aucune | description de style | fichier (coffre, jarvis-…) */
        val musique: String = "auto",
        /** Paroles chantées : vide (instrumental), « auto » (écrites par l'IA) ou le texte. */
        val paroles: String = "",
        /** auto | aucune | texte à dire */
        val voixOff: String = "auto",
        val textes: Boolean = true,
        /** Vidéos / images existantes à monter (séparées par « ; »). */
        val sources: String = "",
        val garderSon: Boolean = false,
        val tiktok: Boolean = true
    )

    private data class Plan(val visuel: String, val texte: String)
    private data class Storyboard(val titre: String, val analyse: String, val plans: List<Plan>, val voixOff: String, val musique: String, val paroles: String, val legende: String)
    /** Un morceau de la timeline : vidéo (rognée à [dureeMs]) ou image affichée [dureeMs]. */
    private data class Segment(val fichier: File, val image: Boolean, val dureeMs: Long, val texte: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val NOTIF = 7310
    private val VIDEO = setOf("mp4", "mov", "webm", "mkv", "3gp", "m4v")
    private val IMAGE = setOf("png", "jpg", "jpeg", "webp", "bmp", "heic")

    fun dossier(app: JarvisApp) = VideoGen.dossier(app)

    /** Lance le(s) montage(s) en arrière-plan ; le résultat arrive dans le chat. Renvoie le message immédiat. */
    fun lancer(app: JarvisApp, d: Demande): String {
        val sansIa = !ImageToVideoGen.disponible(app) && app.store.huggingFaceToken.value.isBlank()
        if (sansIa && d.sources.isBlank())
            return "❌ Pour créer des plans vidéo, ajoute ta clé fal.ai dans Réglages › Vidéo IA — ou donne-moi des vidéos/images existantes à monter (paramètre sources)."
        val duree = d.dureeS.coerceIn(5, 180)
        val n = d.nombre.coerceIn(1, 5)
        scope.launch {
            for (k in 1..n) {
                val prefixe = if (n > 1) "Vidéo $k/$n : " else ""
                val msg = try {
                    creer(app, d.copy(dureeS = duree), k, n) { etape -> notifier(app, prefixe + etape, enCours = true) }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                    "❌ ${prefixe}montage impossible : ${e.message ?: e.javaClass.simpleName}"
                }
                withContext(Dispatchers.Main) { app.chatAjouter(ChatMsg(false, msg)) }
                notifier(app, prefixe + msg.lineSequence().first().take(120), enCours = false)
            }
        }
        val plans = ceil(duree / 5.0).toInt()
        return "🎬 Montage lancé : ${if (n > 1) "$n vidéos" else "1 vidéo"} de $duree s" +
            (if (!sansIa) " (≈ $plans plans IA par vidéo)" else "") +
            ". Ça prend quelques minutes : je t'envoie le résultat ici dès qu'il est prêt (suivi dans la notification)."
    }

    private fun notifier(app: JarvisApp, texte: String, enCours: Boolean) = runCatching {
        NotificationManagerCompat.from(app).notify(NOTIF, NotificationCompat.Builder(app, JarvisApp.CHANNEL_AGENTS)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(if (enCours) "Montage vidéo en cours" else "Montage vidéo")
            .setContentText(texte).setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setOngoing(enCours).setOnlyAlertOnce(enCours).setProgress(0, 0, enCours).setAutoCancel(!enCours).build())
    }

    // ------------------------------------------------------------------ un montage complet

    private suspend fun creer(app: JarvisApp, d: Demande, k: Int, n: Int, progres: (String) -> Unit): String {
        val ms = d.dureeS * 1000L
        // TikTok/Reels/Shorts sont toujours rendus en 9:16, même si le modèle transmet par erreur « horizontal ».
        val vertical = d.tiktok || !d.format.trim().lowercase().let { it.startsWith("hori") || it == "16:9" || it.startsWith("pays") }
        val (largeur, hauteur) = if (vertical) 720 to 1280 else 1280 to 720
        val travail = File(app.cacheDir, "montage_${System.currentTimeMillis()}").apply { mkdirs() }

        // 1. Scénario : plans (visuel + texte à l'écran), voix off, musique, paroles, légende.
        progres("✍️ Écriture du scénario…")
        val existants = resoudreSources(app, d.sources, travail)
        val nbPlans = max(2, ceil(d.dureeS / 5.0).toInt())
        val sb = storyboard(app, d, nbPlans, k, n)
        // L'analyse éditoriale préalable est une étape obligatoire : ne dépense aucun crédit
        // et ne génère aucun plan si l'agent d'analyse n'a pas produit de résultat exploitable.
        if (sb.analyse.isBlank()) {
            travail.deleteRecursively()
            return "❌ Analyse préalable impossible : aucun plan vidéo n'a été généré. Réessaie lorsque l'agent d'analyse sera disponible."
        }

        // 2. Plans IA pour compléter la durée (en parallèle, 3 à la fois).
        val dureeExistants = existants.sumOf { (f, image) -> if (image) 3500L else dureeMs(f) ?: 0L }
        val manque = (ms - dureeExistants).coerceAtLeast(0)
        val iaPossible = ImageToVideoGen.disponible(app) || app.store.huggingFaceToken.value.isNotBlank()
        val echecs = mutableListOf<String>()
        val clipsIa = if (manque > 0 && iaPossible) {
            val nb = max(1, ceil(manque / 5000.0).toInt())
            val dureeClip = (manque / 1000.0 / nb).coerceIn(2.0, 5.0)
            // Les plans IA prennent la suite des médias existants dans le scénario (texte et visuel).
            val decalage = existants.size
            val plans = List(nb) { i -> sb.plans[(decalage + i) % sb.plans.size].let { p -> if (decalage + i >= sb.plans.size) p.copy(visuel = p.visuel + ", different camera angle") else p } }
            var faits = 0
            val sem = Semaphore(3)
            coroutineScopeAsync(plans.mapIndexed { i, p ->
                suspend {
                    sem.withPermit {
                        val r = runCatching {
                            val bytes = if (ImageToVideoGen.disponible(app)) ImageToVideoGen.clipTexte(app, p.visuel, dureeClip, if (vertical) "9:16" else "16:9")
                            else VideoGen.genereClip(app, p.visuel, dureeClip.toInt(), vertical)
                            File(travail, "plan_$i.mp4").apply { writeBytes(bytes) }
                        }
                        synchronized(this@Montage) { faits++; progres("🎥 Plans IA : $faits/$nb") }
                        r.onFailure { echecs += "plan ${i + 1} : ${it.message?.take(120)}" }
                        r.getOrNull()?.let { Triple(i, it, p.texte) }
                    }
                }
            }).filterNotNull().sortedBy { it.first }
        } else emptyList()

        // 3. Timeline exacte : existants puis plans IA, en boucle si besoin, dernier morceau rogné pour tomber juste.
        val briques = existants.mapIndexed { i, (f, image) -> Triple(f, image, sb.plans.getOrNull(i)?.texte.orEmpty()) } +
            clipsIa.map { (_, f, t) -> Triple(f, false, t) }
        if (briques.isEmpty()) return "❌ Aucun plan n'a pu être créé" + (if (echecs.isNotEmpty()) " :\n- " + echecs.joinToString("\n- ") else ".")
        val segments = mutableListOf<Segment>()
        var total = 0L; var i = 0
        val maxParMorceau = if (briques.size >= 3) max(4000L, ms / briques.size * 2) else ms
        while (total < ms && i < 400) {
            val (f, image, texte) = briques[i % briques.size]
            val naturel = if (image) min(4000L, ms) else (dureeMs(f) ?: 5000L)
            val duree = min(min(naturel, maxParMorceau), ms - total)
            if (duree >= 300) segments += Segment(f, image, duree, if (d.textes) texte else "")
            total += duree; i++
        }

        // 4. Voix off (synthèse vocale du téléphone) et musique.
        val avecParoles = d.paroles.isNotBlank()
        val texteVoix = when (d.voixOff.trim().lowercase()) {
            "aucune", "non", "false", "sans", "" -> ""
            "auto", "oui", "true" -> if (avecParoles) "" else sb.voixOff
            else -> d.voixOff
        }
        val voix = if (texteVoix.isNotBlank()) { progres("🗣️ Voix off…"); synthese(app, texteVoix, File(travail, "voix.wav")) } else null
        val musique: File? = when (d.musique.trim().lowercase()) {
            "aucune", "non", "false", "sans" -> null
            else -> {
                val existant = if (d.musique.isNotBlank() && d.musique.lowercase() != "auto") resoudreSources(app, d.musique, travail).firstOrNull()?.first else null
                existant ?: if (ImageToVideoGen.disponible(app)) {
                    progres(if (avecParoles) "🎤 Chanson avec paroles…" else "🎵 Musique…")
                    runCatching {
                        val desc = if (d.musique.isBlank() || d.musique.lowercase() == "auto") sb.musique else d.musique
                        val paroles = if (avecParoles) sb.paroles.takeIf { it.isNotBlank() && it.lowercase() != "auto" }.orEmpty() else ""
                        val (octets, ext) = ImageToVideoGen.musique(app, desc, d.dureeS + 2.0, paroles)
                        File(travail, "musique.$ext").apply { writeBytes(octets) }
                    }.onFailure { echecs += "musique : ${it.message?.take(120)}" }.getOrNull()
                } else null
            }
        }

        // 5. Montage (avec repli progressif si le téléphone refuse un effet).
        progres("🎞️ Montage…")
        val nom = (if (d.tiktok) "tiktok_" else "video_") + System.currentTimeMillis() + ".mp4"
        val sortie = File(dossier(app), nom)
        val essais = listOf(
            Triple(true, true, "complet"),
            Triple(false, true, "sans texte à l'écran"),
            Triple(false, false, "sans texte ni son")
        )
        var abandons = ""
        var ok = false
        for ((avecTexte, avecSon, libelle) in essais) {
            val r = runCatching {
                exporter(app, segments, if (avecSon) voix else null, if (avecSon) musique else null, avecTexte, d.garderSon && avecSon, largeur, hauteur, avecParoles, sortie)
            }
            if (r.isSuccess) { ok = true; if (libelle != "complet") abandons = libelle; break }
            echecs += "montage $libelle : ${r.exceptionOrNull()?.message?.take(140)}"
        }
        travail.deleteRecursively()
        if (!ok) return "❌ Le montage a échoué sur ce téléphone :\n- " + echecs.joinToString("\n- ")

        val dossierCoffre = if (d.tiktok) "JARVIS/Vidéos TikTok" else "JARVIS/Vidéos"
        val loc = runCatching { app.obsidian.saveBytes(dossierCoffre, nom, sortie.readBytes(), "video/mp4") }.getOrDefault("")
        if (sb.analyse.isNotBlank() || sb.legende.isNotBlank() || sb.voixOff.isNotBlank()) runCatching {
            app.obsidian.saveIn(if (d.tiktok) "Scripts TikTok" else "JARVIS/Vidéos", nom.removeSuffix(".mp4"),
                "# ${sb.titre}\n\n**Durée** : ${d.dureeS} s\n\n## Analyse préalable\n${sb.analyse.ifBlank { "Analyse non disponible ; storyboard construit à partir du brief original." }}\n\n## Plans\n" + sb.plans.joinToString("\n") { "- ${it.texte.ifBlank { "—" }} _(${it.visuel})_" } +
                    (if (sb.voixOff.isNotBlank()) "\n\n## Voix off\n${sb.voixOff}" else "") +
                    (if (sb.paroles.isNotBlank()) "\n\n## Paroles\n${sb.paroles}" else "") +
                    (if (sb.legende.isNotBlank()) "\n\n## Légende\n${sb.legende}" else ""))
        }
        return buildString {
            append("🎬 « ${sb.titre} » — ${d.dureeS} s, ${segments.size} plans")
            val pistes = listOfNotNull(if (voix != null && abandons.isBlank()) "voix off" else null,
                if (musique != null && abandons != "sans texte ni son") (if (avecParoles) "chanson avec paroles" else "musique") else null,
                if (d.textes && abandons.isBlank()) "texte à l'écran" else null)
            if (pistes.isNotEmpty()) append(" · " + pistes.joinToString(", "))
            append("\n\n[▶ Lire la vidéo](jarvis-video-ia:$nom)")
            if (loc.isNotBlank()) append("\n📁 $loc")
            if (sb.legende.isNotBlank()) append("\n\n📝 Légende : ${sb.legende}")
            if (abandons.isNotBlank()) append("\n⚠️ Montage fait $abandons (effet refusé par le téléphone).")
            if (echecs.isNotEmpty()) append("\n⚠️ " + echecs.joinToString("\n⚠️ "))
        }
    }

    private suspend fun <T> coroutineScopeAsync(blocs: List<suspend () -> T>): List<T> = kotlinx.coroutines.coroutineScope {
        blocs.map { b -> async(Dispatchers.IO) { b() } }.awaitAll()
    }

    // ------------------------------------------------------------------ scénario

    private suspend fun storyboard(app: JarvisApp, d: Demande, nbPlans: Int, k: Int, n: Int): Storyboard {
        val user = buildString {
            append("Sujet : ${d.sujet.ifBlank { "(voir script)" }}\n")
            if (d.script.isNotBlank()) append("Script fourni (prioritaire) :\n${d.script}\n")
            append("Durée totale : ${d.dureeS} secondes\nNombre de plans : $nbPlans\n")
            append("Format : ${if (d.tiktok) "TikTok vertical 9:16" else d.format}\n")
            if (d.paroles.isNotBlank() && d.paroles.lowercase() != "auto") append("Paroles imposées par l'utilisateur :\n${d.paroles}\n")
            else if (d.paroles.lowercase() == "auto") append("L'utilisateur veut une CHANSON avec paroles chantées : écris les paroles.\n")
            if (d.musique.isNotBlank() && d.musique.lowercase() !in setOf("auto", "aucune")) append("Musique souhaitée : ${d.musique}\n")
            if (d.voixOff.isNotBlank() && d.voixOff.lowercase() !in setOf("auto", "aucune", "oui", "non")) append("Voix off imposée : ${d.voixOff}\n")
            if (n > 1) append("C'est la variante $k sur $n : choisis un angle, une accroche et des visuels DIFFÉRENTS des autres variantes.\n")
        }

        // Passe 1 : comprendre le brief, les contraintes, le public et l'intention avant de rédiger les prompts.
        val analyseBrute = runCatching { app.llm.agent(Prompts.MONTAGE_ANALYSE, user) }.getOrDefault("")
        val analyseJson = runCatching {
            JSONObject(analyseBrute.substring(analyseBrute.indexOf('{'), analyseBrute.lastIndexOf('}') + 1))
        }.getOrNull()
        val analyse = analyseJson?.let { j ->
            listOf("intention", "public", "idee_centrale", "angle_narratif", "ton", "structure", "accroche",
                "continuite", "direction_artistique", "brief_generation").mapNotNull { key ->
                j.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { "$key : $it" }
            }.plus(listOf("contraintes", "hypotheses").flatMap { key ->
                val a = j.optJSONArray(key) ?: return@flatMap emptyList()
                (0 until a.length()).map { index -> "$key : ${a.optString(index)}" }
            }).joinToString("\n")
        }.orEmpty().ifBlank { analyseBrute.take(5000) }

        // Ne lance aucune génération si la première étape d’analyse n’a fourni aucun contenu exploitable.

        // Passe 2 : transformer l'analyse et le brief original en storyboard + prompts par plan.
        val contexte = buildString {
            append(user)
            if (analyse.isNotBlank()) append("\nANALYSE PRÉALABLE À RESPECTER :\n$analyse\n")
        }
        val brut = runCatching { app.llm.agent(Prompts.MONTAGE, contexte) }.getOrDefault("")
        val j = runCatching { JSONObject(brut.substring(brut.indexOf('{'), brut.lastIndexOf('}') + 1)) }.getOrNull()
        val plans = j?.optJSONArray("plans")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                Plan(it.optString("visuel").ifBlank { d.sujet }, it.optString("texte").trim())
            }
        }.orEmpty().ifEmpty {
            runCatching { app.llm.agent(Prompts.TIKTOK_PLANS, contexte) }.getOrDefault(d.sujet)
                .lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotBlank() }.map { Plan(it, "") }
        }.ifEmpty { listOf(Plan(d.sujet, "")) }
        return Storyboard(
            titre = j?.optString("titre").orEmpty().ifBlank { d.sujet.take(60) },
            analyse = analyse,
            plans = plans,
            voixOff = j?.optString("voix_off").orEmpty(),
            musique = j?.optString("musique").orEmpty().ifBlank { "upbeat modern instrumental background music" },
            paroles = if (d.paroles.isNotBlank() && d.paroles.lowercase() != "auto") d.paroles else j?.optString("paroles").orEmpty(),
            legende = j?.optString("legende").orEmpty()
        )
    }

    // ------------------------------------------------------------------ médias existants

    /** Vidéos/images désignées par l'utilisateur : jarvis-video-ia:…, dernieres_videos:N, jarvis-img:…, coffre, stockage du téléphone. */
    private fun resoudreSources(app: JarvisApp, sources: String, travail: File): List<Pair<File, Boolean>> {
        if (sources.isBlank()) return emptyList()
        val out = mutableListOf<File>()
        sources.split(';', '\n').map { it.trim().trim('"', '\'') }.filter { it.isNotBlank() }.forEach { s ->
            val m = Regex("(?i)^derni[eè]res?_?(videos?|vid[ée]os?|images?):?(\\d*)$").find(s)
            when {
                m != null -> {
                    val nb = m.groupValues[2].toIntOrNull() ?: 5
                    if (m.groupValues[1].startsWith("im", true)) {
                        File(app.filesDir, "images").listFiles()?.filter { it.extension.lowercase() in IMAGE }?.sortedByDescending { it.lastModified() }?.take(nb)?.let { out += it }
                    } else dossier(app).listFiles()?.filter { it.extension.lowercase() in VIDEO }?.sortedByDescending { it.lastModified() }?.take(nb)?.let { out += it }
                }
                s.startsWith("jarvis-video-ia:") -> VideoGen.fichier(app, s)?.let { out += it }
                s.startsWith("jarvis-img:") -> ImageGen.fichier(app, s.removePrefix("jarvis-img:"))?.let { out += it }
                else -> {
                    val telephone = runCatching { File(Stockage.racine(), s.trim('/')).takeIf { it.isFile && it.canRead() } }.getOrNull()
                    if (telephone != null) out += telephone
                    else {
                        val o = app.obsidian
                        val chemins = o.resoudre(s)?.let { r -> if (r.substringAfterLast('/').contains('.')) listOf(r) else o.chercherFichiers("$r/*", limite = 200) }
                            ?: o.chercherFichiers(s, limite = 200)
                        chemins.filter { it.substringAfterLast('.', "").lowercase() in VIDEO + IMAGE }
                            .forEach { c -> o.versFichierLocal(c, File(travail, "sources"))?.let { out += it } }
                    }
                }
            }
        }
        return out.distinctBy { it.absolutePath }.map { it to (it.extension.lowercase() in IMAGE) }
    }

    private fun dureeMs(f: File): Long? = runCatching {
        MediaMetadataRetriever().run {
            try { setDataSource(f.absolutePath); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() }
        }
    }.getOrNull()

    // ------------------------------------------------------------------ voix off

    /** Synthèse vocale française du téléphone vers un fichier WAV (gratuit, hors ligne selon le moteur). */
    private suspend fun synthese(app: JarvisApp, texte: String, sortie: File): File? = withTimeoutOrNull(90_000) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<File?> { cont ->
                var tts: TextToSpeech? = null
                tts = TextToSpeech(app) { st ->
                    val t = tts
                    if (st != TextToSpeech.SUCCESS || t == null) { if (cont.isActive) cont.resume(null); return@TextToSpeech }
                    t.language = Locale.FRANCE
                    t.setSpeechRate(1.05f)
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { t.shutdown(); if (cont.isActive) cont.resume(sortie.takeIf { it.length() > 1000 }) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { t.shutdown(); if (cont.isActive) cont.resume(null) }
                    })
                    if (t.synthesizeToFile(texte.take(3900), Bundle(), sortie, "voix_off") != TextToSpeech.SUCCESS) { t.shutdown(); if (cont.isActive) cont.resume(null) }
                }
                cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
            }
        }
    }

    // ------------------------------------------------------------------ rendu

    /** Bandeau de texte (blanc, contour noir, centré, sur plusieurs lignes) pour l'incrustation. */
    private fun bandeau(texte: String, largeur: Int): Bitmap {
        val taille = largeur * 0.07f
        val fond = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = taille; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            style = Paint.Style.STROKE; strokeWidth = taille * 0.16f; color = Color.BLACK; strokeJoin = Paint.Join.ROUND
        }
        val plein = TextPaint(fond).apply { style = Paint.Style.FILL; color = Color.WHITE }
        val utile = (largeur * 0.88f).toInt()
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(texte, 0, texte.length, p, utile).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).build()
        val lf = layout(fond); val lp = layout(plein)
        val marge = (taille * 0.4f).toInt()
        val bmp = Bitmap.createBitmap(largeur, lf.height + 2 * marge, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            translate((largeur - utile) / 2f, marge.toFloat())
            lf.draw(this); lp.draw(this)
        }
        return bmp
    }

    /** Volume d'une piste audio (mono ou stéréo, sortie stéréo). */
    @OptIn(UnstableApi::class)
    private fun volume(v: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2).scaleBy(v))
        putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(v))
    }

    @OptIn(UnstableApi::class)
    private suspend fun exporter(
        app: JarvisApp, segments: List<Segment>, voix: File?, musique: File?, avecTexte: Boolean, garderSon: Boolean,
        largeur: Int, hauteur: Int, chanson: Boolean, sortie: File
    ) {
        val presentation = Presentation.createForWidthAndHeight(largeur, hauteur, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val video = segments.map { s ->
            val effets = mutableListOf<Effect>(presentation)
            if (s.image) {
                // Léger zoom avant sur les images fixes (effet « Ken Burns »).
                val dureeUs = s.dureeMs * 1000f
                effets += MatrixTransformation { t -> Matrix().apply { val z = 1f + 0.08f * (t / dureeUs).coerceIn(0f, 1f); setScale(z, z) } }
            }
            if (avecTexte && s.texte.isNotBlank()) {
                val reglages = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.58f).build()
                effets += OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(bandeau(s.texte, largeur), reglages)))
            }
            val item = if (s.image) MediaItem.Builder().setUri(Uri.fromFile(s.fichier)).setImageDurationMs(s.dureeMs).build()
            else MediaItem.Builder().setUri(Uri.fromFile(s.fichier))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(s.dureeMs).build()).build()
            EditedMediaItem.Builder(item).apply {
                if (s.image) { setDurationUs(s.dureeMs * 1000); setFrameRate(30) }
                setRemoveAudio(s.image || !garderSon)
                setEffects(Effects(if (garderSon && !s.image) listOf(volume(if (musique != null || voix != null) 0.5f else 1f)) else emptyList(), effets))
            }.build()
        }
        val totalMs = segments.sumOf { it.dureeMs }
        val sequences = mutableListOf(EditedMediaItemSequence(video))
        if (musique != null) {
            val niveau = if (voix != null) 0.28f else if (chanson) 1f else 0.8f
            sequences += EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(musique)))
                .setRemoveVideo(true).setEffects(Effects(listOf(volume(niveau)), emptyList())).build()), true)
        }
        if (voix != null) {
            val item = MediaItem.Builder().setUri(Uri.fromFile(voix))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(totalMs).build()).build()
            sequences += EditedMediaItemSequence(listOf(EditedMediaItem.Builder(item).setRemoveVideo(true)
                .setEffects(Effects(listOf(volume(1f)), emptyList())).build()))
        }
        val composition = Composition.Builder(sequences).experimentalSetForceAudioTrack(true).build()
        if (sortie.exists()) sortie.delete()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val transformer = Transformer.Builder(app)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(c: Composition, r: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                        override fun onError(c: Composition, r: ExportResult, e: ExportException) { if (cont.isActive) cont.resumeWithException(e) }
                    }).build()
                cont.invokeOnCancellation { runCatching { transformer.cancel() } }
                transformer.start(composition, sortie.absolutePath)
            }
        }
    }
}
