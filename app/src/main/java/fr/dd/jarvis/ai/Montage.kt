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
import kotlinx.coroutines.sync.withLock
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
        val tiktok: Boolean = true,
        /** auto (plans libres de droits Pexels/Pixabay d'abord, IA pour le reste) | stock | ia */
        val plans: String = "auto",
        /** Sous-titres synchronisés avec la voix off. */
        val sousTitres: Boolean = false,
        /** Envoyer la vidéo terminée dans les brouillons TikTok du compte. */
        val brouillon: Boolean = false,
        /** Signature et ton de la chaîne (« phénix bleu blanc rouge en ouverture et en fin, ton engagé »). */
        val style: String = "",
        /** Images / vidéos de signature existantes pour l'ouverture et la fin (sinon image générée d'après [style]). */
        val signature: String = ""
    )

    private data class Plan(val visuel: String, val texte: String, val recherche: String = "", val source: String = "")
    /** Un plan prêt : image (animée au montage) ou vidéo ; [i] = rang dans le scénario. */
    private data class Media(val i: Int, val fichier: File, val image: Boolean, val texte: String)
    private data class Storyboard(val titre: String, val analyse: String, val plans: List<Plan>, val voixOff: String, val musique: String, val paroles: String, val legende: String,
                                  val signature: String = "", val bandeau: String = "")
    /** Un morceau de la timeline : vidéo (de [debutMs], rognée à [dureeMs]) ou image affichée [dureeMs]. */
    private data class Segment(val fichier: File, val image: Boolean, val dureeMs: Long, val texte: String, val debutMs: Long = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val NOTIF = 7310
    private val VIDEO = setOf("mp4", "mov", "webm", "mkv", "3gp", "m4v")
    private val IMAGE = setOf("png", "jpg", "jpeg", "webp", "bmp", "heic")

    fun dossier(app: JarvisApp) = VideoGen.dossier(app)

    /** Un seul montage à la fois (fal.ai limite les générations simultanées) ; les suivants attendent leur tour. */
    private val unParUn = kotlinx.coroutines.sync.Mutex()
    private val enAttente = java.util.concurrent.atomic.AtomicInteger(0)
    private val montageEnCours = java.util.concurrent.atomic.AtomicBoolean(false)
    private val travaux = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.Job>()
    /** Demande d'arrêt : vérifiée à chaque étape et pendant le suivi fal.ai. */
    private val annule = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Arrête le montage en cours et ceux en attente (les générations fal.ai en cours sont annulées). */
    fun annuler(app: JarvisApp): String {
        val n = travaux.count { it.isActive }
        if (n == 0) return "Aucun montage vidéo en cours."
        annule.set(true)
        travaux.forEach { it.cancel() }
        travaux.clear(); enAttente.set(0)
        notifier(app, "⏹️ Montage annulé.", enCours = false)
        return "⏹️ Montage vidéo annulé" + (if (n > 1) " ($n montages)" else "") + "."
    }

    private fun verifierAnnulation() { if (annule.get()) throw kotlinx.coroutines.CancellationException("montage annulé") }

    /** Lance le(s) montage(s) en arrière-plan ; le résultat arrive dans le chat. Renvoie le message immédiat. */
    fun lancer(app: JarvisApp, d: Demande): String {
        val sansIa = !ImageToVideoGen.disponible(app) && !HfJetons.configure(app) && !VideoProviderGen.disponible(app)
        if (sansIa && d.sources.isBlank() && !Stock.disponible(app))
            return "❌ Pour créer des plans vidéo, configure Hugging Face, Gemini (si quota vidéo disponible) ou Dreamina dans Réglages › IA › Images & vidéo — ou donne-moi des vidéos/images existantes à monter (paramètre sources)."
        val duree = d.dureeS.coerceIn(5, 180)
        val n = d.nombre.coerceIn(1, 5)
        // Position dans la file : montages en attente + celui en cours.
        val position = enAttente.getAndIncrement() + (if (montageEnCours.get()) 1 else 0) + 1
        if (position == 1) notifier(app, "✍️ Préparation du scénario…", enCours = true)
        val job = scope.launch {
          unParUn.withLock {
           montageEnCours.set(true)
           try {
            annule.set(false)
            enAttente.updateAndGet { (it - 1).coerceAtLeast(0) }
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
           } finally { montageEnCours.set(false) }
          }
        }
        travaux.removeAll { !it.isActive }
        travaux += job
        val plans = ceil(duree / 5.0).toInt()
        val agent = if (app.store.geminiKeys.isNotEmpty()) "l'agent Gemini" else "l'agent IA"
        if (position > 1) return "⏳ Un montage vidéo est déjà en cours : celui-ci (${if (n > 1) "$n vidéos" else "1 vidéo"} de $duree s) démarrera juste après " +
            "(position $position dans la file). Les vidéos sont générées l'une après l'autre pour ne pas saturer fal.ai. Dis « annule la vidéo » pour tout arrêter."
        return "🎬 Montage lancé : ${if (n > 1) "$n vidéos" else "1 vidéo"} de $duree s" +
            (if (Stock.disponible(app) && d.plans.lowercase() != "ia") " (plans libres de droits Pexels / Pixabay)" else if (!sansIa) " (≈ $plans plans IA par vidéo)" else "") + ".\n" +
            "✍️ D'abord $agent analyse ta demande et écrit le scénario, puis un prompt précis pour chaque plan (mêmes personnages, décor et style d'un plan à l'autre) ; " +
            "ensuite les plans sont générés et montés à la durée exacte. Ça prend quelques minutes : je t'envoie le résultat ici (suivi dans la notification)." +
            (fr.dd.jarvis.work.SuiviTaches.bloquees(app)?.let { "\n$it" } ?: "")
    }

    private fun notifier(app: JarvisApp, texte: String, enCours: Boolean) {
        if (enCours) fr.dd.jarvis.work.SuiviTaches.enCours(app, NOTIF, "🎬 Montage vidéo en cours", texte)
        else fr.dd.jarvis.work.SuiviTaches.termine(app, NOTIF, "🎬 Montage vidéo terminé", texte)
    }

    // ------------------------------------------------------------------ un montage complet

    private suspend fun creer(app: JarvisApp, d: Demande, k: Int, n: Int, progres: (String) -> Unit): String {
        val ms = d.dureeS * 1000L
        // TikTok/Reels/Shorts sont toujours rendus en 9:16, même si le modèle transmet par erreur « horizontal ».
        val vertical = d.tiktok || !d.format.trim().lowercase().let { it.startsWith("hori") || it == "16:9" || it.startsWith("pays") }
        // TikTok : 1080 × 1920 (format recommandé par TikTok) ; sinon 720p. Repli en 720p si le téléphone refuse.
        val (largeur, hauteur) = if (d.tiktok) 1080 to 1920 else if (vertical) 720 to 1280 else 1280 to 720
        val travail = File(app.cacheDir, "montage_${System.currentTimeMillis()}").apply { mkdirs() }

        // 0. TikTok : niche, style, bandeau, concurrents et images modèles détectés depuis tes vidéos (chaque semaine).
        var d = d
        if (d.tiktok) {
            if (TikTokAuto.possible(app) && TikTokAuto.aRafraichir(app)) { progres("🔍 Analyse de ta chaîne TikTok…"); TikTokAuto.siBesoin(app) }
            val niche = app.store.tiktokNiche.value.trim()
            d = d.copy(
                style = d.style.ifBlank { app.store.tiktokStyle.value.trim() },
                sujet = if (niche.isNotBlank() && Regex("(?i)\\bniche\\b").containsMatchIn(d.sujet) && !d.sujet.contains(niche)) d.sujet + " — niche du compte : $niche" else d.sujet
            )
        }
        // 1. Scénario : plans (visuel + texte à l'écran), voix off, musique, paroles, légende.
        progres("✍️ Écriture du scénario…")
        val existants = resoudreSources(app, d.sources, travail)
        // TikTok : un plan toutes les ~4 s (rythme de tes vidéos, plans plus longs et plus lisibles) ; ailleurs toutes les ~5 s.
        val nbPlans = if (d.tiktok) ceil(d.dureeS / 4.0).toInt().coerceIn(3, 12) else max(2, ceil(d.dureeS / 5.0).toInt())
        val sb = storyboard(app, d, nbPlans, k, n, progres)
        // L'analyse éditoriale préalable est une étape obligatoire : ne dépense aucun crédit
        // et ne génère aucun plan si l'agent d'analyse n'a pas produit de résultat exploitable.
        if (sb.analyse.isBlank()) {
            travail.deleteRecursively()
            return "❌ Analyse préalable impossible : aucun plan vidéo n'a été généré. Réessaie lorsque l'agent d'analyse sera disponible."
        }

        // 2. Plans IA pour compléter la durée (en parallèle, 3 à la fois).
        val dureeExistants = existants.sumOf { (f, image) -> if (image) 3500L else dureeMs(f) ?: 0L }
        val manqueTotal = (ms - dureeExistants).coerceAtLeast(0)
        val echecs = mutableListOf<String>()
        // 1b. Signature de la chaîne (ouverture et fin) : tes images / vidéos, sinon une image générée (Cloudflare, gratuit).
        val signature: List<Pair<File, Boolean>> = when {
            d.signature.isNotBlank() -> resoudreSources(app, d.signature, travail).take(2)
                .also { if (it.isEmpty()) echecs += "signature : rien trouvé dans « ${d.signature.take(60)} »" }
            d.style.isNotBlank() && sb.signature.isNotBlank() && ImageGen.disponible(app) -> {
                progres("🎨 Image de signature (${d.style.take(40)})…")
                runCatching { listOf(ImageGen.generate(app, sansPersonnagesProteges(sb.signature) + ", epic, highly detailed, ${if (vertical) "vertical portrait 9:16" else "horizontal 16:9"} composition, no text, no letters",
                    "flux-2-klein-4b", if (vertical) 768 else 1344, if (vertical) 1344 else 768, huggingFace = false).first to true) }
                    .recoverCatching { listOfNotNull(ImageGen.banque(app, sb.signature, emptySet()).firstOrNull()?.let { it to true }).ifEmpty { throw it } }
                    .getOrElse { echecs += "image de signature : ${it.message?.take(160)}"; emptyList() }
            }
            d.style.isNotBlank() && sb.signature.isNotBlank() -> { echecs += "image de signature : ajoute un compte Cloudflare ou ton jeton Hugging Face (Réglages › IA › Images & vidéo), ou tes images dans Réglages › TikTok"; emptyList() }
            else -> emptyList()
        }
        // 2a. Un média par plan du scénario, gratuit : image générée (Cloudflare) pour un symbole ou une allégorie (« phénix
        // tricolore au-dessus de Paris »), plan réel libre de droits (Pixabay / Pexels) pour une scène filmable.
        // « mixte » : images ET plans libres de droits demandés par toi (Pixabay accepté, pas de garde-fou hors sujet).
        val mixte = d.plans.trim().lowercase() == "mixte"
        val modePlans = if (mixte) "auto" else d.plans.trim().lowercase()
        val credits = mutableListOf<Stock.Clip>()
        // Images : Cloudflare (quota gratuit du jour), puis tes images IA déjà faites sur le même sujet (banque JARVIS),
        // puis photos libres de droits vraiment pertinentes. Hugging Face n'est pas utilisé ici : son petit quota gratuit
        // sert à animer les images.
        val cfOk = app.store.cfAccounts.any { it.contains(':') }
        fun veutImage(p: Plan) = (cfOk || Stock.disponible(app)) && when (modePlans) {
            "images" -> true
            "stock", "ia" -> false
            else -> p.source == "image" || !Stock.disponible(app)
        }
        val medias = mutableListOf<Media>()
        val pris = mutableSetOf<String>()
        val imagesParPlan = HashMap<Int, File>()
        val banqueUtilisee = mutableSetOf<String>()
        var depuisBanque = 0
        // Plans voulus en image (symbole, allégorie) mais remplis par une photo ou un plan libre de droits : hors sujet probable.
        var imagesVoulues = 0
        var horsSujet = 0
        var raisonImages = ""
        // Après un refus pour quota, inutile de redemander une image IA à chaque plan.
        var imagesIaEpuisees = !cfOk
        // Images modèles : miniatures de tes vidéos les plus vues (même phénix, mêmes couleurs, même ambiance) et la
        // première image de ce montage (le même sujet d'un plan à l'autre).
        val modelesChaine = if (d.tiktok) TikTokAuto.references(app).take(3) else emptyList()
        var modeleMontage: File? = null
        // Sujet récurrent de la chaîne (le phénix tricolore…) imposé aux plans qui n'en montrent aucun symbole.
        val ancre = if (!d.tiktok) "" else app.store.tiktokStyle.value.trim().takeIf { it.isNotBlank() && it.length <= 220 && Regex("(?i)[a-z]").containsMatchIn(it) }
            ?: if (Regex("(?i)ph[ée]nix|phoenix").containsMatchIn(d.sujet + " " + d.style)) ANCRE_PHENIX else ""
        suspend fun image(rang: Int, p: Plan): File? = imagesParPlan[rang] ?: if (imagesIaEpuisees) null else runCatching {
            val modeles = modelesChaine + listOfNotNull(modeleMontage)
            val prompt = (if (modeles.isNotEmpty()) "Keep exactly the same main subject design, color palette, lighting and mood as the reference images, in a new composition: " else "") +
                interdits(ancrer(sansPersonnagesProteges(p.visuel), ancre)) + ", ${if (vertical) "vertical portrait 9:16" else "horizontal 16:9"} composition, cinematic lighting, highly detailed"
            // Image directement au bon format et nette (FLUX.2 klein 4B : ≈ 10 fois moins de quota que le 9B).
            ImageGen.generate(app, prompt, "flux-2-klein-4b", if (vertical) 768 else 1344, if (vertical) 1344 else 768, huggingFace = false, references = modeles).first
                .also { if (modeleMontage == null) modeleMontage = ImageGen.reference(it, File(travail, "modele_montage.jpg")) }
        }.onFailure {
            if (raisonImages.isBlank()) raisonImages = it.message?.take(220).orEmpty()
            if (Regex("(?i)quota|épuisé|allocation|aucun compte|jeton").containsMatchIn(it.message.orEmpty())) imagesIaEpuisees = true
        }.getOrNull()?.also { imagesParPlan[rang] = it }
        // Image IA déjà faite par JARVIS sur le même sujet (jamais deux fois la même dans une vidéo).
        fun banque(rang: Int, p: Plan): File? = imagesParPlan[rang] ?: ImageGen.banque(app, p.visuel, banqueUtilisee).firstOrNull()?.also {
            banqueUtilisee += it.name; imagesParPlan[rang] = it; depuisBanque++
        }
        // Photo ou illustration libre de droits (Pexels / Pixabay), seulement si elle montre vraiment le sujet.
        suspend fun photo(i: Int, p: Plan): File? {
            if (!Stock.disponible(app)) return null
            val requetes = listOf(p.recherche, Stock.motsCles(p.visuel), Stock.motsCles(p.visuel, 2)).filter { it.isNotBlank() }.distinct()
            // TikTok : la photo doit montrer un symbole de la niche (drapeau, monument, phénix…), pas seulement les mots du plan.
            val exige = if (d.tiktok && ancre.isNotBlank()) SYMBOLES else null
            val clip = requetes.firstNotNullOfOrNull { q -> Stock.chercherImage(app, q, vertical, pris, illustration = p.source == "image", exige = exige) }
                ?: Stock.motsClesFr(d.sujet).let { mots -> listOf(mots.joinToString(" "), mots.firstOrNull().orEmpty()) }
                    .filter { it.isNotBlank() }.distinct().firstNotNullOfOrNull { q -> Stock.chercherImage(app, q, vertical, pris, francais = true, illustration = p.source == "image", exige = exige) }
            val f = clip?.let { Stock.telecharger(it, File(travail, "photo_$i.jpg")) }?.takeIf { imageUtilisable(it) }
            if (clip != null && f != null) credits += clip
            return f
        }
        suspend fun reel(i: Int, p: Plan): File? {
            val requetes = listOf(p.recherche, Stock.motsCles(p.visuel), Stock.motsCles(p.visuel, 2)).filter { it.isNotBlank() }.distinct()
            val clip = requetes.firstNotNullOfOrNull { q -> Stock.chercher(app, q, vertical, pris, strict = d.tiktok) }
                // Rien en anglais : mots du sujet en français (Pixabay et Pexels cherchent aussi en français).
                ?: Stock.motsClesFr(d.sujet).let { mots -> listOf(mots.joinToString(" "), mots.firstOrNull().orEmpty()) }
                    .filter { it.isNotBlank() }.distinct().firstNotNullOfOrNull { q -> Stock.chercher(app, q, vertical, pris, francais = true, strict = d.tiktok) }
            val f = clip?.let { Stock.telecharger(it, File(travail, "stock_$i.mp4")) }
            if (clip != null && f != null) credits += clip
            return f
        }
        if (manqueTotal > 0 && modePlans != "ia" && (Stock.disponible(app) || cfOk)) {
            val nb = if (d.tiktok) ceil(manqueTotal / 4000.0).toInt().coerceIn(1, 12) else max(1, ceil(manqueTotal / 4000.0).toInt())
            for (i in 0 until nb) {
                verifierAnnulation()
                val rang = (existants.size + i) % sb.plans.size
                val p = sb.plans[rang]
                val img = veutImage(p)
                progres(if (img) "🎨 Image ${i + 1}/$nb" + (if (imagesIaEpuisees) " (banque JARVIS / photo libre de droits)…" else "…") else "🎞️ Plan ${i + 1}/$nb libre de droits (Pixabay / Pexels)…")
                // Source préférée, puis les autres si elle ne donne rien (jamais de trou dans la vidéo) :
                // image IA → banque JARVIS → photo libre de droits → plan vidéo libre de droits.
                val m = if (img) {
                    imagesVoulues++
                    (image(rang, p) ?: banque(rang, p))?.let { Media(i, it, true, p.texte) }
                        ?: (photo(i, p)?.let { Media(i, it, true, p.texte) }
                            ?: (if (Stock.disponible(app) && modePlans != "images") reel(i, p)?.let { Media(i, it, false, p.texte) } else null))
                            ?.also { horsSujet++ }
                } else (if (Stock.disponible(app)) reel(i, p)?.let { Media(i, it, false, p.texte) } else null)
                    ?: (if (modePlans != "stock") (image(rang, p) ?: banque(rang, p) ?: photo(i, p))?.let { Media(i, it, true, p.texte) } else null)
                // TikTok : rien de pertinent pour ce plan → une image déjà retenue de CE montage (autre mouvement), jamais un plan hors sujet.
                val retenu = m ?: if (d.tiktok && img) medias.filter { it.image }.let { l -> l.getOrNull(i % maxOf(1, l.size)) }?.let { Media(i, it.fichier, true, p.texte) } else null
                if (retenu != null) medias += retenu
            }
            if (raisonImages.isNotBlank()) echecs += "images IA indisponibles (" + raisonImages + ")" +
                (if (depuisBanque > 0) " : $depuisBanque reprise${if (depuisBanque > 1) "s" else ""} de ta banque d'images JARVIS sur le même sujet" else "")
            if (medias.isEmpty()) echecs += "aucune image ni aucun plan libre de droits obtenu"
            // Garde-fou : les images IA étaient prévues mais la plupart ont dû être remplacées par des photos au hasard.
            // Mieux vaut ne pas livrer une vidéo hors sujet (dis « avec Pixabay » pour l'accepter quand même).
            if (cfOk && !mixte && modePlans != "stock" && imagesVoulues >= 2 && horsSujet * 2 > imagesVoulues) {
                travail.deleteRecursively()
                return horsSujetMessage(raisonImages, horsSujet, imagesVoulues, d.sujet)
            }
        }
        // 2b. Vraie animation IA des images (Hugging Face Wan 2.2, quota GPU gratuit du jour : 3 images au plus, 10 min
        // en tout) ; une image non animée l'est au montage (zoom / travelling).
        val aAnimer = medias.filter { it.image }.take(3)
        var animees = 0
        if (aAnimer.isNotEmpty() && d.tiktok && modePlans != "stock") {
            if (!HfJetons.configure(app)) echecs += "animation IA des images : ajoute ton jeton Hugging Face gratuit (Réglages › IA › Images & vidéo) pour de vraies images animées"
            else {
                val limite = System.currentTimeMillis() + 10 * 60_000L
                for ((k, m) in aAnimer.withIndex()) {
                    val reste = limite - System.currentTimeMillis()
                    if (reste < 60_000L) break
                    progres("🎥 Animation IA de l'image ${k + 1}/${aAnimer.size} (Hugging Face, quota gratuit)…")
                    val prompt = sansPersonnagesProteges(sb.plans.getOrNull((existants.size + m.i) % sb.plans.size)?.visuel.orEmpty()) +
                        ". Animate this exact image: flames flicker, feathers and smoke move, embers fly, slow epic camera push-in, keep the composition, no text."
                    val clip = withTimeoutOrNull(reste) {
                        runCatching { withContext(Dispatchers.IO) { VideoGen.genereClip(app, prompt, 4, vertical, image = m.fichier) } }
                            .onFailure { echecs += "animation IA de l'image ${k + 1} : ${it.message?.take(110)}" }.getOrNull()
                    }
                    if (clip == null || clip.size <= 10_000) break   // quota épuisé ou service indisponible : inutile d'insister
                    // Hugging Face rend 16 images/s : sous le minimum de TikTok (23) → converti en 30 images/s constants.
                    val brut = File(travail, "anime_${m.i}_16.mp4").apply { writeBytes(clip) }
                    val f = runCatching { en30ips(app, brut, travail) }.getOrNull() ?: brut
                    medias[medias.indexOf(m)] = m.copy(fichier = f, image = false)
                    animees++
                }
            }
        }
        // Plans trouvés (images ou réels) : ils couvrent toute la durée, sans compléter par une IA vidéo payante
        // (fal.ai) ou sans quota gratuit (Gemini). L'IA vidéo ne sert qu'en l'absence de tout plan.
        val clipsStock = medias
        val manque = if (modePlans == "stock" || modePlans == "images" || (modePlans == "auto" && medias.isNotEmpty())) 0L else manqueTotal
        val iaPossible = ImageToVideoGen.disponible(app) || HfJetons.configure(app) || VideoProviderGen.disponible(app)
        val clipsIa = if (manque > 0 && iaPossible) {
            val nb = max(1, ceil(manque / 5000.0).toInt())
            val dureeClip = (manque / 1000.0 / nb).coerceIn(2.0, 5.0)
            // Les plans IA prennent la suite des médias existants et des plans libres de droits dans le scénario.
            val decalage = existants.size + clipsStock.size
            val plans = List(nb) { i -> sb.plans[(decalage + i) % sb.plans.size].let { p -> if (decalage + i >= sb.plans.size) p.copy(visuel = p.visuel + ", different camera angle") else p } }
            progres("✅ Scénario prêt : « ${sb.titre.take(50)} » — génération de $nb plans…")
            // Suivi par plan (file d'attente / en cours / terminé), résumé dans la notification au plus une fois par seconde.
            val etats = java.util.concurrent.ConcurrentHashMap<Int, String>()
            val debutPlans = System.currentTimeMillis()
            var derniereMaj = 0L
            val fileBloquee = java.util.concurrent.atomic.AtomicBoolean(false)
            var dernierEchec = ""
            fun resume(force: Boolean = false) {
                val t = System.currentTimeMillis()
                if (!force && t - derniereMaj < 1000) return
                derniereMaj = t
                val v = etats.values
                val ecoule = (t - debutPlans) / 1000
                val actuel = etats.entries.firstOrNull { it.value == "en cours" || it.value.startsWith("file") }
                progres("🎥 Plans IA : ${v.count { it == "ok" }}/$nb terminés" +
                    (actuel?.let { " · plan ${it.key + 1} " + (if (it.value == "en cours") "en génération" else "en file d'attente") } ?: "") +
                    v.count { it == "échec" }.let { if (it > 0) " · $it échec(s)" else "" } +
                    " · ${ecoule / 60} min ${"%02d".format(ecoule % 60)}" +
                    (if (dernierEchec.isNotBlank()) "\nDernier échec : ${dernierEchec.take(140)}" else ""))
            }
            // Un plan à la fois : fal.ai limite les générations simultanées par compte, les autres restaient en file.
            val sem = Semaphore(1)
            // Minuteur : la notification avance même quand fal.ai ne répond rien de nouveau.
            val minuteur = scope.launch { while (true) { kotlinx.coroutines.delay(5000); resume(force = true) } }
            coroutineScopeAsync(plans.mapIndexed { i, p ->
                suspend {
                    sem.withPermit {
                        suspend fun essai() = runCatching {
                            verifierAnnulation()
                            if (fileBloquee.get()) throw IllegalStateException("annulé : la file fal.ai ne démarre pas")
                            etats[i] = "file"; resume(force = true)
                            val bytes = if (ImageToVideoGen.disponible(app)) ImageToVideoGen.clipTexte(app, p.visuel, dureeClip, if (vertical) "9:16" else "16:9",
                                suivi = { e -> verifierAnnulation(); etats[i] = if (e.startsWith("file")) "file" else "en cours"; resume() },
                                limiteMs = 6 * 60_000L, limiteFileMs = 10 * 60_000L)
                            else try { VideoGen.genereClip(app, p.visuel, dureeClip.toInt(), vertical) }
                            catch (hf: Exception) { VideoProviderGen.genererClipSecours(app, p.visuel, dureeClip.toInt(), vertical) ?: throw hf }
                            File(travail, "plan_$i.mp4").apply { writeBytes(bytes) }
                        }
                        var r = essai()
                        // Erreur passagère (réseau, 5xx, génération trop longue) : un nouvel essai ; pas pour la file bloquée ou un refus.
                        val passagere = r.exceptionOrNull()?.let { e ->
                            e !is kotlinx.coroutines.CancellationException && !e.message.orEmpty().contains("file d'attente") && !annule.get() &&
                                (e !is ApiException || e.code >= 500 || e.code == 408 || e.code == 0)
                        } == true
                        if (passagere) r = essai()
                        r.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) { minuteur.cancel(); throw it } }
                        r.onFailure {
                            dernierEchec = "plan ${i + 1} : ${it.message?.take(160)}"
                            echecs += dernierEchec
                            // Une demande jamais démarrée : inutile d'empiler les suivantes.
                            if (it.message.orEmpty().contains("file d'attente")) fileBloquee.set(true)
                        }
                        etats[i] = if (r.isSuccess) "ok" else "échec"; resume(force = true)
                        r.getOrNull()?.let { Triple(i, it, p.texte) }
                    }
                }
            }).filterNotNull().sortedBy { it.first }.also { minuteur.cancel() }
        } else emptyList()
        verifierAnnulation()

        // 3. Timeline exacte : existants puis plans IA, en boucle si besoin, dernier morceau rogné pour tomber juste.
        val briques = (existants.mapIndexed { i, (f, image) -> Triple(f, image, sb.plans.getOrNull(i)?.texte.orEmpty()) } +
            clipsStock.sortedBy { it.i }.map { Triple(it.fichier, it.image, it.texte) } + clipsIa.map { (_, f, t) -> Triple(f, false, t) })
            .ifEmpty { signature.map { (f, image) -> Triple(f, image, sb.plans.firstOrNull()?.texte.orEmpty()) } }
        if (briques.isEmpty()) return "❌ Aucun plan n'a pu être créé" + (if (echecs.isNotEmpty()) " :\n- " + regrouper(echecs).joinToString("\n- ") else ".")
        val segments = mutableListOf<Segment>()
        // Signature : ouverture (et fin dès 15 s), 2,5 s chacune ; le milieu est rempli par les plans.
        val ouverture = signature.firstOrNull()
        val fin = signature.lastOrNull()?.takeIf { ms >= 15_000 }
        val dureeSignature = 2_500L
        val milieu = ms - (if (ouverture != null) dureeSignature else 0) - (if (fin != null) dureeSignature else 0)
        if (ouverture != null) segments += Segment(ouverture.first, ouverture.second, dureeSignature, if (d.textes) sb.plans.firstOrNull()?.texte.orEmpty() else "")
        var total = 0L; var i = 0
        // TikTok : coupes de 3,5 s au plus ; un plan réutilisé reprend là où il s'était arrêté (pas deux fois la même image).
        val maxParMorceau = if (d.tiktok) 4_000L else if (briques.size >= 3) max(4000L, milieu / briques.size * 2) else milieu
        val consomme = HashMap<File, Long>()
        while (total < milieu && i < 400) {
            val (f, image, texte) = briques[i % briques.size]
            val naturel = if (image) min(4000L, milieu) else (dureeMs(f) ?: 5000L)
            val debut = if (image) 0L else (consomme[f] ?: 0L).let { if (it + 1_500 > naturel) 0L else it }
            val duree = min(min(naturel - debut, maxParMorceau), milieu - total)
            if (duree >= 300) { segments += Segment(f, image, duree, if (d.textes) texte else "", debut); consomme[f] = debut + duree }
            total += max(duree, 300L); i++
        }
        if (fin != null) segments += Segment(fin.first, fin.second, dureeSignature, if (d.textes) sb.plans.lastOrNull()?.texte.orEmpty() else "")

        // 4. Voix off (synthèse vocale du téléphone) et musique.
        // Une chanson n'est possible qu'avec un générateur de musique (fal.ai) : sinon, voix off (jamais une vidéo muette).
        val avecParoles = d.paroles.isNotBlank() && ImageToVideoGen.disponible(app)
        val texteVoix = when (d.voixOff.trim().lowercase()) {
            "aucune", "non", "false", "sans", "" -> ""
            // Scénario sans voix off : les textes des plans sont dits (jamais une vidéo muette sans raison).
            "auto", "oui", "true" -> if (avecParoles) "" else sb.voixOff.ifBlank {
                sb.plans.map { it.texte.trim().trimEnd('.', '!', '?') }.filter { it.isNotBlank() }.takeIf { it.size >= 2 }?.joinToString(". ", postfix = ".").orEmpty()
            }
            else -> d.voixOff
        }
        val phrases = SousTitres.phrases(texteVoix)
        // 1) Voix naturelle Gemini (narrateur grave et engagé), en une fois : les sous-titres suivent la longueur des phrases.
        val voixNaturelle: Pair<File, Long>? = if (phrases.isNotEmpty() && app.store.geminiKeys.any { it.isNotBlank() && !KeyRing.estCleOpenAi(it) }) {
            progres("🗣️ Voix off (voix naturelle)…")
            runCatching { withContext(Dispatchers.IO) { fr.dd.jarvis.voice.VoixNaturelle.enregistrement(app, phrases.joinToString(" "), VOIX_MONTAGE, styleVoix(d)) } }
                .onFailure { echecs += "voix naturelle Gemini : ${it.message?.take(110)} (voix du téléphone essayée)" }.getOrNull()
                ?.takeIf { it.pcm.size > 4_000 }?.let { a ->
                    val f = File(travail, "voix_naturelle.wav")
                    ecrireWav(a.pcm, a.frequence, a.canaux, f)
                    f to a.pcm.size * 1000L / (a.frequence.toLong() * a.canaux * 2)
                }
        } else null
        // 2) Sinon la synthèse vocale du téléphone, phrase par phrase (durée exacte de chaque phrase).
        val voixPhrases = if (voixNaturelle == null && phrases.isNotEmpty()) { progres("🗣️ Voix off…"); synthesePhrases(app, phrases, travail) } else emptyList()
        // 3) Dernier recours : voix de Google Traduction (gratuite), morceau par morceau.
        val voixGoogle = if (voixNaturelle == null && voixPhrases.isEmpty() && phrases.isNotEmpty()) { progres("🗣️ Voix off (Google)…"); voixGoogle(phrases, travail) } else emptyList()
        val voix: List<File> = voixNaturelle?.let { listOf(it.first) } ?: voixPhrases.map { it.first }.ifEmpty { voixGoogle.map { it.second } }.ifEmpty {
            if (texteVoix.isNotBlank()) listOfNotNull(synthese(app, texteVoix, File(travail, "voix.wav"))) else emptyList()
        }
        if (texteVoix.isNotBlank() && voix.isEmpty())
            echecs += "voix off : la synthèse vocale du téléphone n'a rien produit (vérifie Paramètres Android › Synthèse vocale : moteur Google et voix française installée)"
        val reperes = when {
            !d.sousTitres || phrases.isEmpty() -> emptyList()
            voixNaturelle != null -> SousTitres.reperes(phrases, SousTitres.repartir(phrases, voixNaturelle.second))
            voixPhrases.size == phrases.size -> SousTitres.reperes(phrases, voixPhrases.map { it.second })
            voixGoogle.isNotEmpty() -> SousTitres.reperes(voixGoogle.map { it.first }, voixGoogle.map { it.third })
            else -> emptyList()
        }
        val musique: File? = when (d.musique.trim().lowercase()) {
            "aucune", "non", "false", "sans" -> null
            else -> {
                val existant = if (d.musique.isNotBlank() && d.musique.lowercase() != "auto") resoudreSources(app, d.musique, travail).firstOrNull()?.first else null
                // Pas de musique prise au hasard sur le téléphone : une musique donnée, ou générée (fal.ai), sinon aucune
                // (le son tendance s'ajoute dans TikTok, sous la voix off).
                existant ?: if (ImageToVideoGen.disponible(app)) {
                    progres(if (avecParoles) "🎤 Chanson avec paroles…" else "🎵 Musique…")
                    runCatching {
                        val desc = if (d.musique.isBlank() || d.musique.lowercase() == "auto") sb.musique else d.musique
                        val paroles = if (avecParoles) sb.paroles.takeIf { it.isNotBlank() && it.lowercase() != "auto" }.orEmpty() else ""
                        val etiquette = if (avecParoles) "🎤 Chanson avec paroles" else "🎵 Musique"
                        val (octets, ext) = ImageToVideoGen.musique(app, desc, d.dureeS + 2.0, paroles, suivi = { e -> progres("$etiquette : $e…") })
                        File(travail, "musique.$ext").apply { writeBytes(octets) }
                    }.onFailure { echecs += "musique : ${it.message?.take(120)}" }.getOrNull()
                } else null
            }
        }

        // 5. Montage (avec repli progressif si le téléphone refuse un effet).
        val nom = (if (d.tiktok) "tiktok_" else "video_") + System.currentTimeMillis() + ".mp4"
        val sortie = File(dossier(app), nom)
        // Encodeur LOGICIEL d'Android d'abord : l'encodeur matériel de certains téléphones (Xiaomi / HyperOS notamment) rend
        // des images abîmées (blocs remis à zéro → bandes et images figées sur TikTok). Le réglage (portrait direct ou
        // paysage + rotation) est choisi d'après ce que l'encodeur accepte vraiment, pour garder la pleine résolution.
        // Chaque export est vérifié (images abîmées, résolution) ; sinon il est refait autrement, le meilleur gardé.
        // Cadence : tout plan vidéo sous 23 images/s (TikTok le refuse) est d'abord converti en 30 images/s constants.
        val convertis = HashMap<File, File>()
        for ((k, sg) in segments.withIndex()) {
            if (sg.image || (ipsVideo(sg.fichier) ?: 30.0) >= 23.0) continue
            progres("🎞️ Mise à 30 images/s d'un plan…")
            val c = convertis.getOrPut(sg.fichier) { runCatching { en30ips(app, sg.fichier, travail) }.getOrNull() ?: sg.fichier }
            segments[k] = sg.copy(fichier = c)
        }
        data class Essai(val avecTexte: Boolean, val avecSon: Boolean, val l: Int, val h: Int, val libelle: String, val logiciel: Boolean = true, val portrait: Boolean = true,
                         val texteDansImages: Boolean = false, val hevc: Boolean = false)
        val (l720, h720) = if (vertical) 720 to 1280 else 1280 to 720
        // Encodeur logiciel (fiable) : la plus grande taille qu'il accepte ici ; sur certains téléphones, pas la HD.
        val tailleSw = tailleLogicielle(largeur, hauteur)
        val pleineSw = tailleSw?.takeIf { min(it.first, it.second) >= min(largeur, hauteur) * 9 / 10 }
        val essais = listOfNotNull(
            pleineSw?.let { Essai(true, true, it.first, it.second, "complet", portrait = it.third) },
            Essai(true, true, largeur, hauteur, "encodeur matériel", logiciel = false),
            // Autre bloc d'encodage du téléphone (H.265, accepté par TikTok) quand le H.264 matériel abîme des images.
            Essai(true, true, largeur, hauteur, "encodeur matériel HEVC", logiciel = false, hevc = true),
            tailleSw?.takeIf { pleineSw == null }?.let { Essai(true, true, it.first, it.second, "en ${min(it.first, it.second)}p (encodeur logiciel)", portrait = it.third) },
            Essai(false, true, largeur, hauteur, "avec le texte dessiné dans les images", logiciel = pleineSw != null, portrait = pleineSw?.third ?: true, texteDansImages = d.textes),
            Essai(false, true, l720, h720, "sans texte à l'écran", logiciel = false),
            Essai(false, false, l720, h720, "sans texte ni son", logiciel = false)
        )
        val complets = setOf("complet", "encodeur matériel", "encodeur matériel HEVC", "avec le texte dessiné dans les images")
        var abandons = ""
        var raisonAbandon = ""
        var ok = false
        var meilleur: File? = null
        var meilleurScore = Long.MIN_VALUE
        var meilleurAbimees = 0
        var meilleurLibelle = ""
        var meilleurCote = 0
        var meilleurComplet = false
        for ((avecTexte, avecSon, l, h, libelle, logiciel, portrait, texteDansImages, hevc) in essais) {
            val complet = avecSon && (avecTexte || texteDansImages || !d.textes)
            // Une version complète (texte + son) existe déjà : une version qui retire le texte ou le son ne peut pas être meilleure.
            if (meilleurComplet && !complet) continue
            progres("🎞️ Montage" + (if (libelle != "complet") " ($libelle)" else "") + "…")
            val r = runCatching {
                exporter(app, segments, if (avecSon) voix else emptyList(), if (avecSon) musique else null, avecTexte, d.garderSon && avecSon, l, h, avecParoles, sortie,
                    if (avecTexte && avecSon) reperes else emptyList(), tiktok = d.tiktok,
                    bandeau = if ((avecTexte || texteDansImages) && d.tiktok) app.store.tiktokBandeau.value.trim().ifBlank { sb.bandeau } else "",
                    logiciel = logiciel, portrait = portrait, texteDansImages = texteDansImages, hevc = hevc)
            }
            if (r.isFailure) {
                val raison = r.exceptionOrNull()?.let { e -> (e.message ?: e.javaClass.simpleName) + (e.cause?.message?.let { " ($it)" } ?: "") }.orEmpty()
                if (raisonAbandon.isBlank()) raisonAbandon = raison.take(160)
                echecs += "montage $libelle : ${raison.take(160)}"; continue
            }
            progres("🔎 Vérification de la vidéo…")
            val (abimees, cote) = withContext(Dispatchers.IO) { analyser(sortie) }
            val pleineTaille = cote == 0 || cote >= min(largeur, hauteur) * 9 / 10
            if (abimees == 0 && pleineTaille && complet) { ok = true; meilleur = null; break }
            val defaut = if (abimees > 0) "$abimees images abîmées par l'encodeur du téléphone" else "résolution réduite à ${cote}p par l'encodeur"
            if (raisonAbandon.isBlank()) raisonAbandon = defaut
            echecs += "montage $libelle : $defaut, refait autrement"
            val score = scoreExport(avecSon, avecTexte || texteDansImages || !d.textes, abimees, cote)
            if (score > meilleurScore) {
                meilleur = File(travail, "meilleur.mp4").also { sortie.copyTo(it, overwrite = true) }
                meilleurScore = score; meilleurAbimees = abimees; meilleurLibelle = libelle; meilleurCote = cote; meilleurComplet = complet
            }
        }
        if (!ok && meilleur != null) {
            meilleur.copyTo(sortie, overwrite = true)
            ok = true
            if (meilleurLibelle !in complets && !meilleurLibelle.startsWith("en ")) abandons = meilleurLibelle
            echecs += when {
                meilleurAbimees > 0 -> "vidéo gardée avec $meilleurAbimees image${if (meilleurAbimees > 1) "s" else ""} abîmée${if (meilleurAbimees > 1) "s" else ""} par l'encodeur vidéo du téléphone (bref défaut possible à l'image) : aucun export parfait n'était possible"
                else -> "vidéo en ${meilleurCote}p : seul l'encodeur logiciel du téléphone donne une image sans défaut, et il ne sait pas faire plus grand"
            }
        }
        travail.deleteRecursively()
        if (!ok) return "❌ Le montage a échoué sur ce téléphone :\n- " + regrouper(echecs).joinToString("\n- ")
        // Export réussi : les essais ratés en chemin ne sont plus des échecs à signaler.
        echecs.removeAll { it.startsWith("montage ") }

        val dossierCoffre = if (d.tiktok) "JARVIS/Vidéos TikTok" else "JARVIS/Vidéos"
        val loc = runCatching { app.obsidian.saveBytes(dossierCoffre, nom, sortie.readBytes(), "video/mp4") }.getOrDefault("")
        if (sb.analyse.isNotBlank() || sb.legende.isNotBlank() || sb.voixOff.isNotBlank()) runCatching {
            app.obsidian.saveIn(if (d.tiktok) "Scripts TikTok" else "JARVIS/Vidéos", nom.removeSuffix(".mp4"),
                "# ${sb.titre}\n\n**Durée** : ${d.dureeS} s\n\n## Analyse préalable\n${sb.analyse.ifBlank { "Analyse non disponible ; storyboard construit à partir du brief original." }}\n\n## Plans\n" + sb.plans.joinToString("\n") { "- ${it.texte.ifBlank { "—" }} _(${it.visuel})_" } +
                    (if (sb.voixOff.isNotBlank()) "\n\n## Voix off\n${sb.voixOff}" else "") +
                    (if (sb.paroles.isNotBlank()) "\n\n## Paroles\n${sb.paroles}" else "") +
                    (if (sb.legende.isNotBlank()) "\n\n## Légende\n${sb.legende}" else "") +
                    (if (credits.isNotEmpty()) "\n\n## Crédits des plans libres de droits\n" + credits.joinToString("\n") { "- ${it.source} · ${it.auteur.ifBlank { "auteur inconnu" }} · ${it.page}" } else ""))
        }
        // Brouillon TikTok : la vidéo part dans la boîte de réception TikTok (son tendance et publication dans l'appli).
        // TikTok exige 23 à 60 images/s : vérifié avant l'envoi (sinon refus « frame_rate_check_failed » après coup).
        val cadence = withContext(Dispatchers.IO) { cadenceMoyenne(sortie) }
        val envoi = when {
            !(d.tiktok && d.brouillon) -> ""
            cadence != null && (cadence < 23.0 || cadence > 60.5) -> "⚠️ Pas envoyée dans tes brouillons TikTok : la vidéo est à ${"%.1f".format(cadence)} images/s (TikTok exige 23 à 60)."
            else -> runCatching { TikTok.envoyerBrouillon(app, sortie) }.getOrElse { "❌ Envoi en brouillon TikTok impossible : ${it.message}" }
        }
        return buildString {
            append("🎬 « ${sb.titre} » — ${d.dureeS} s, ${segments.size} plans")
            val pistes = listOfNotNull(if (voix.isNotEmpty() && abandons.isBlank()) "voix off" else null,
                if (reperes.isNotEmpty() && abandons.isBlank()) "sous-titres" else null,
                if (musique != null && abandons != "sans texte ni son") (if (avecParoles) "chanson avec paroles" else "musique") else null,
                if (d.textes && abandons.isBlank()) "texte à l'écran" else null)
            if (animees > 0) append(" · $animees image${if (animees > 1) "s" else ""} animée${if (animees > 1) "s" else ""} par IA")
            if (pistes.isNotEmpty()) append(" · " + pistes.joinToString(", "))
            append("\n\n[▶ Lire la vidéo](jarvis-video-ia:$nom)")
            if (loc.isNotBlank()) append("\n📁 $loc")
            if (sb.analyse.isNotBlank()) append("\n✍️ Scénario et prompts des plans enregistrés dans la note « ${nom.removeSuffix(".mp4")} » du coffre.")
            if (sb.legende.isNotBlank()) append("\n\n📝 Légende : ${sb.legende}")
            if (credits.isNotEmpty()) append("\n🎞️ Plans libres de droits : " + credits.map { it.source }.distinct().joinToString(" et ") + " (auteurs crédités dans la note)")
            if (envoi.isNotBlank()) append("\n\n$envoi")
            if (abandons.isNotBlank()) append("\n⚠️ Montage fait $abandons (refusé par le téléphone" + (if (raisonAbandon.isNotBlank()) " : $raisonAbandon" else "") + ").")
            if (echecs.isNotEmpty()) append("\n⚠️ " + regrouper(echecs).joinToString("\n⚠️ "))
        }
    }

    /**
     * Messages d'échec regroupés : « image 1 : quota… », « image 2 : quota… » → « images 1, 2 : quota… » (le même
     * refus n'est plus répété neuf fois et la vraie cause du problème reste visible).
     */
    internal fun regrouper(echecs: List<String>): List<String> {
        val motif = Regex("^(image|plan|animation IA de l'image) (\\d+) : (.*)$", RegexOption.DOT_MATCHES_ALL)
        val groupes = LinkedHashMap<Pair<String, String>, MutableList<String>>()
        for (e in echecs) {
            val m = motif.find(e)
            if (m == null) { groupes.getOrPut("" to e) { mutableListOf() }; continue }
            val (genre, n, raison) = m.destructured
            groupes.getOrPut(genre to raison) { mutableListOf() } += n
        }
        return groupes.map { (cle, nums) ->
            val (genre, raison) = cle
            when {
                genre.isEmpty() -> raison
                nums.size == 1 -> "$genre ${nums[0]} : $raison"
                else -> when (genre) { "image" -> "images"; "plan" -> "plans"; else -> "animation IA des images" } + " ${nums.joinToString(", ")} : $raison"
            }
        }
    }

    private suspend fun <T> coroutineScopeAsync(blocs: List<suspend () -> T>): List<T> = kotlinx.coroutines.coroutineScope {
        blocs.map { b -> async(Dispatchers.IO) { b() } }.awaitAll()
    }

    // ------------------------------------------------------------------ scénario

    private suspend fun storyboard(app: JarvisApp, d: Demande, nbPlans: Int, k: Int, n: Int, progres: (String) -> Unit = {}): Storyboard {
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
            if (d.style.isNotBlank()) append("Signature et ton de la chaîne (à respecter dans chaque plan, la voix off et l'image « signature ») : ${d.style}\n")
            if (d.tiktok) {
                // Ressembler aux vidéos de la chaîne : même sujet récurrent d'un plan à l'autre, textes courts et forts.
                append("Fidélité à la chaîne : garde LE MÊME sujet principal (même apparence, mêmes couleurs) dans tous les plans, " +
                    "décors et ambiance cohérents ; textes à l'écran de 1 à 4 mots, percutants, dans le ton de la chaîne.\n" +
                    "Chaque plan montre ce sujet principal OU un symbole évident de la niche (pour une niche patriotique française : drapeau tricolore, " +
                    "coq gaulois, Marianne, monuments français, pompiers, agriculteurs, paysages de France). JAMAIS de personnage inventé sans rapport, " +
                    "de masque, de portail magique, de rue ou d'objet quelconque, ni de texte écrit DANS l'image (vêtements, panneaux).\n" +
                    "Personnages protégés par le droit d'auteur (Astérix, Obélix, héros de BD, de films ou de jeux) : jamais ; à la place, des guerriers gaulois " +
                    "ORIGINAUX, sans reprendre leur apparence.\n" +
                    "ACCROCHE ET ENGAGEMENT (obligatoires) : le texte du 1er plan et la 1re phrase de la voix off sont une accroche percutante (question ou affirmation " +
                    "choc, 6 mots au plus) ; le DERNIER plan et la fin de la voix off invitent à réagir : une question qui appelle une réponse en commentaire, " +
                    "puis « like » et « partage » (ex. texte « TU EN PENSES QUOI ? 🇫🇷 », voix : « Dis-moi en commentaire… et partage si tu es fier d'être Français ») ; " +
                    "la légende reprend cette question.\n")
                app.store.tiktokBandeau.value.takeIf { it.isNotBlank() }?.let { append("Bandeau habituel en haut des vidéos : $it\n") }
            }
        }

        // Passe 1 : comprendre le brief, les contraintes, le public et l'intention avant de rédiger les prompts.
        progres("🧠 Analyse de ta demande…")
        val analyseBrute = runCatching { app.llm.agent(Prompts.MONTAGE_ANALYSE, user) }.getOrDefault("")
        val analyseJson = runCatching {
            JSONObject(analyseBrute.substring(analyseBrute.indexOf('{'), analyseBrute.lastIndexOf('}') + 1))
        }.getOrNull()
        val analyse = analyseJson?.takeIf { it.optString("brief_generation").isNotBlank() }?.let { j ->
            listOf("intention", "public", "idee_centrale", "angle_narratif", "ton", "structure", "accroche",
                "continuite", "direction_artistique", "brief_generation").mapNotNull { key ->
                j.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { "$key : $it" }
            }.plus(listOf("contraintes", "hypotheses").flatMap { key ->
                val a = j.optJSONArray(key) ?: return@flatMap emptyList()
                (0 until a.length()).map { index -> "$key : ${a.optString(index)}" }
            }).joinToString("\n")
        }.orEmpty()

        // Ne lance aucune génération si la première étape d’analyse n’a fourni aucun contenu exploitable.

        // Passe 2 : transformer l'analyse et le brief original en storyboard + prompts par plan.
        progres("✍️ Scénario et prompts des $nbPlans plans…")
        val contexte = buildString {
            append(user)
            if (analyse.isNotBlank()) append("\nANALYSE PRÉALABLE À RESPECTER :\n$analyse\n")
        }
        val brut = runCatching { app.llm.agent(Prompts.MONTAGE, contexte) }.getOrDefault("")
        val j = runCatching { JSONObject(brut.substring(brut.indexOf('{'), brut.lastIndexOf('}') + 1)) }.getOrNull()
        val plans = j?.optJSONArray("plans")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                Plan(it.optString("visuel").ifBlank { d.sujet }, it.optString("texte").trim(), it.optString("recherche").trim(), it.optString("source").trim().lowercase())
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
            legende = j?.optString("legende").orEmpty(),
            signature = j?.optString("signature").orEmpty().trim(),
            bandeau = j?.optString("bandeau").orEmpty().trim()
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
                // Fichier joint à la conversation (bouton +) ou importé dans la bibliothèque.
                DocumentStore.find(app, s)?.takeIf { it.extension.lowercase() in VIDEO + IMAGE } != null ->
                    out += DocumentStore.find(app, s)!!
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

    /** Refus d'une vidéo hors sujet : pourquoi, quand ça repartira, et comment forcer. */
    internal fun horsSujetMessage(raison: String, horsSujet: Int, voulues: Int, sujet: String): String =
        "❌ Vidéo non créée : $horsSujet image${if (horsSujet > 1) "s" else ""} sur $voulues n'auraient pas montré « ${sujet.take(60)} » " +
            "(seules des photos libres de droits sans rapport étaient disponibles). Je préfère ne pas te livrer une vidéo hors sujet.\n" +
            "Cause : " + raison.ifBlank { "images IA indisponibles" } + ".\n" +
            "➡️ Relance après la remise à zéro du quota (1 h du matin), ajoute un 2e compte Cloudflare gratuit (Réglages › IA › Images & vidéo : " +
            "chaque compte = ≈ 60 images par jour), ou dis « avec Pixabay » pour accepter des photos libres de droits."

    /** Image téléchargée lisible et pas presque toute noire (une image transparente ou ratée donnait un écran noir). */
    private fun imageUtilisable(f: File): Boolean = runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
        val b = android.graphics.BitmapFactory.decodeFile(f.absolutePath, o) ?: return@runCatching false
        try {
            var somme = 0L; var n = 0
            for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) {
                val c = b.getPixel(x, y)
                somme += (((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff)) * (((c ushr 24) and 0xff)) / 255
                n++
            }
            n > 0 && somme / (3L * n) >= 18
        } finally { b.recycle() }
    }.getOrDefault(true)

    /** Sujet récurrent des vidéos de la chaîne quand elle parle de phénix (repris dans chaque plan qui n'en montre aucun symbole). */
    internal const val ANCRE_PHENIX = "a majestic phoenix with blue, white and red flaming wings in the colours of the French flag"

    internal val SYMBOLES = Regex("(?i)ph[oeé]+ni|flag|drapeau|tricolo|marianne|rooster|\\bcoq|eiffel|triomphe|triumph|notre.?dame|mont.?saint|versailles|" +
        "firefighter|pompier|farmer|agricult|tractor|tracteur|soldier|soldat|\\bfrance|french|paris|chateau|château|cathedral")

    private val PROTEGES = Regex("(?i)\\b(ast[ée]rix|ob[ée]lix|id[ée]fix|dogmatix|panoramix|getafix|abraracourcix|vitalstatistix)\\b(\\s+(et|and|,)\\s+(ast[ée]rix|ob[ée]lix|id[ée]fix|panoramix))*")

    /**
     * Personnages protégés (Astérix, Obélix…) : jamais reproduits (droit d'auteur, retrait possible par TikTok) ; ils
     * deviennent des guerriers gaulois ORIGINAUX, sans reprendre leur apparence.
     */
    internal fun sansPersonnagesProteges(visuel: String): String =
        PROTEGES.replace(visuel, "original ancient Gaulish warriors of our own design")

    /** Plan sans aucun symbole de la chaîne : le sujet récurrent ([ancre]) y est ajouté. */
    internal fun ancrer(visuel: String, ancre: String): String =
        if (ancre.isBlank() || SYMBOLES.containsMatchIn(visuel)) visuel else "$ancre, $visuel"

    /** Interdits de l'image : jamais de texte (« Unité » sur un t-shirt), ni de personnage inventé quand le plan n'en demande pas. */
    internal fun interdits(visuel: String): String {
        val personnes = Regex("(?i)firefighter|pompier|farmer|agricult|soldier|soldat|crowd|foule|people|person|\\bman\\b|woman|child|famil|marianne").containsMatchIn(visuel)
        return visuel + ", no text, no letters, no words, no writing on clothes or signs, no logo, no watermark" +
            if (personnes) ", no masks" else ", no people, no human figures, no masks"
    }

    /** Images de sortie pour chaque image source : [n] images à [source] i/s → [cible] i/s constants (1 ou 2 chacune). */
    internal fun repartitionTrames(n: Int, source: Double, cible: Double = 30.0): List<Int> =
        (0 until n).map { k -> (Math.round((k + 1) * cible / source) - Math.round(k * cible / source)).toInt().coerceAtLeast(1) }

    /** Cadence moyenne d'une vidéo (images / seconde) d'après ses premières secondes ; null si illisible. */
    private fun ipsVideo(f: File): Double? = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            val piste = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return@runCatching null
            ex.selectTrack(piste)
            var n = 0; var premier = -1L; var dernier = 0L
            while (n < 240) {
                val t = ex.sampleTime
                if (t < 0) break
                if (premier < 0) premier = t
                dernier = t; n++
                if (!ex.advance()) break
            }
            if (n < 3 || dernier <= premier) null else (n - 1) * 1_000_000.0 / (dernier - premier)
        } finally { ex.release() }
    }.getOrNull()

    /**
     * Clip à moins de 23 images/s (animation IA à 16 i/s, refusée par TikTok) → copie à 30 images/s CONSTANTES : chaque
     * image du clip est reprise 1 ou 2 fois, la durée et le mouvement restent identiques. null si impossible.
     */
    @OptIn(UnstableApi::class)
    private suspend fun en30ips(app: JarvisApp, clip: File, dossier: File): File? {
        if (android.os.Build.VERSION.SDK_INT < 28) return null
        val ips = ipsVideo(clip) ?: return null
        if (ips >= 23.0) return clip
        val images = withContext(Dispatchers.IO) {
            val mmr = android.media.MediaMetadataRetriever()
            try {
                mmr.setDataSource(clip.absolutePath)
                val n = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: return@withContext emptyList<File>()
                val out = mutableListOf<File>()
                var k = 0
                while (k < n) {
                    val lot = runCatching { mmr.getFramesAtIndex(k, minOf(10, n - k)) }.getOrNull() ?: break
                    lot.forEach { b -> out += File(dossier, "${clip.nameWithoutExtension}_i${out.size}.jpg").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }; b.recycle() } }
                    k += lot.size
                    if (lot.isEmpty()) break
                }
                out
            } finally { runCatching { mmr.release() } }
        }
        if (images.size < 3) return null
        val trames = repartitionTrames(images.size, ips)
        val items = images.mapIndexed { k, f ->
            val ms = trames[k] * 1000L / 30
            EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(f)).setImageDurationMs(ms).build())
                .setDurationUs(ms * 1000).setFrameRate(30).setRemoveAudio(true).build()
        }
        val sortie = File(dossier, clip.nameWithoutExtension.removeSuffix("_16") + "_30.mp4")
        if (sortie.exists()) sortie.delete()
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val selecteur = androidx.media3.transformer.EncoderSelector { mime ->
                        val tous = androidx.media3.transformer.EncoderUtil.getSupportedEncoders(mime)
                        com.google.common.collect.ImmutableList.copyOf(tous.filter { encodeurLogiciel(it.name) }.ifEmpty { tous })
                    }
                    val transformer = Transformer.Builder(app).setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                        .setPortraitEncodingEnabled(true)
                        .setEncoderFactory(androidx.media3.transformer.DefaultEncoderFactory.Builder(app).setVideoEncoderSelector(selecteur).setEnableFallback(true).build())
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(c: Composition, r: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                            override fun onError(c: Composition, r: ExportResult, e: ExportException) { if (cont.isActive) cont.resumeWithException(e) }
                        }).build()
                    cont.invokeOnCancellation { runCatching { transformer.cancel() } }
                    transformer.start(Composition.Builder(listOf(EditedMediaItemSequence(items))).build(), sortie.absolutePath)
                }
            }
        } finally { images.forEach { it.delete() } }
        return sortie.takeIf { it.length() > 10_000 && (ipsVideo(it) ?: 0.0) >= 23.0 }
    }

    /** Image du plan avec son texte (et le bandeau) dessinés DEDANS, au format final : repli quand l'incrustation est refusée. */
    private fun imageAvecTexte(src: File, texte: String, bandeauTexte: String, largeur: Int, hauteur: Int, tiktok: Boolean, dest: File): File? = runCatching {
        val b = android.graphics.BitmapFactory.decodeFile(src.absolutePath) ?: return@runCatching null
        val sortie = Bitmap.createBitmap(largeur, hauteur, Bitmap.Config.ARGB_8888)
        val c = Canvas(sortie)
        val k = maxOf(largeur.toFloat() / b.width, hauteur.toFloat() / b.height)
        c.drawBitmap(b, null, android.graphics.RectF((largeur - b.width * k) / 2, (hauteur - b.height * k) / 2, (largeur + b.width * k) / 2, (hauteur + b.height * k) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG))
        b.recycle()
        fun poser(bmp: Bitmap, ancreY: Float) { c.drawBitmap(bmp, 0f, (1f - (ancreY + 1f) / 2f) * hauteur - bmp.height / 2f, null); bmp.recycle() }
        if (texte.isNotBlank()) poser(if (tiktok) bandeau(texte.uppercase(), largeur, taille = 0.065f) else bandeau(texte, largeur), if (tiktok) -0.12f else -0.58f)
        if (bandeauTexte.isNotBlank()) poser(bandeau(bandeauTexte.uppercase(), largeur, taille = 0.038f), 0.9f)
        dest.outputStream().use { sortie.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        sortie.recycle()
        dest
    }.getOrNull()

    /** Encodeurs logiciels d'Android (Google) : fiables partout, à la différence de certains encodeurs matériels. */
    internal fun encodeurLogiciel(nom: String) = nom.startsWith("c2.android.") || nom.startsWith("OMX.google.")

    /**
     * Image vidéo abîmée : au moins 16 octets nuls d'affilée, impossible dans une image H.264 / H.265 valide (le format
     * interdit plus de deux zéros de suite dans une image) mais typique d'un encodeur matériel défaillant.
     */
    internal fun echantillonAbime(octets: ByteArray, taille: Int = octets.size): Boolean {
        var suite = 0
        for (i in 0 until minOf(taille, octets.size)) {
            if (octets[i].toInt() == 0) { if (++suite >= 16) return true } else suite = 0
        }
        return false
    }

    /**
     * Note d'un export pour choisir le meilleur : le SON compte le plus (voix off), puis le texte (accroche), puis une
     * image sans défaut (1 ou 2 images abîmées valent mieux qu'une image floue en très basse résolution), puis la taille.
     */
    internal fun scoreExport(avecSon: Boolean, avecTexte: Boolean, abimees: Int, cote: Int): Long {
        val qualite = when {
            abimees == 0 -> if (cote == 0 || cote >= 540) 1_000L else 400L
            abimees <= 2 -> 600L - abimees * 50L
            abimees <= 6 -> 300L - abimees * 20L
            else -> -abimees.toLong()
        }
        return (if (avecSon) 4_000L else 0L) + (if (avecTexte) 2_000L else 0L) + qualite + cote / 10
    }

    /** Plus grande taille (même format) que l'encodeur logiciel accepte : (largeur, hauteur, portrait direct ?) ou null. */
    private fun tailleLogicielle(largeur: Int, hauteur: Int): Triple<Int, Int, Boolean>? {
        val vertical = hauteur >= largeur
        val cotes = listOf(1080, 720, 576, 540, 480, 432, 360).filter { it <= min(largeur, hauteur) }
        for (c in cotes) {
            val long = (c * 16 / 9 + 15) / 16 * 16
            val (l, h) = if (vertical) c to long else long to c
            reglageLogiciel(l, h)?.let { return Triple(l, h, it) }
        }
        return null
    }

    /**
     * Encodeur logiciel H.264 capable de [l] × [h] à 30 i/s : true = en portrait direct, false = en paysage + rotation,
     * null = aucun (taille refusée, ou pas d'encodeur logiciel sur ce téléphone).
     */
    private fun reglageLogiciel(l: Int, h: Int): Boolean? = runCatching {
        val encodeurs = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && encodeurLogiciel(it.name) && it.supportedTypes.any { t -> t.equals("video/avc", ignoreCase = true) } }
        var paysage = false
        for (e in encodeurs) {
            val caps = e.getCapabilitiesForType("video/avc").videoCapabilities ?: continue
            if (caps.areSizeAndRateSupported(l, h, 30.0)) return@runCatching true
            if (caps.areSizeAndRateSupported(h, l, 30.0)) paysage = true
        }
        if (paysage) false else null
    }.getOrNull()

    /** Vidéo [f] : (images abîmées, côté court de l'image encodée) ; (0, 0) si la vérification échoue. */
    private fun analyser(f: File): Pair<Int, Int> = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            val piste = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return@runCatching 0 to 0
            val format = ex.getTrackFormat(piste)
            val cote = runCatching { min(format.getInteger(android.media.MediaFormat.KEY_WIDTH), format.getInteger(android.media.MediaFormat.KEY_HEIGHT)) }.getOrDefault(0)
            ex.selectTrack(piste)
            val tampon = java.nio.ByteBuffer.allocate(8 * 1024 * 1024)
            val octets = ByteArray(tampon.capacity())
            var n = 0
            while (true) {
                tampon.clear()
                val t = ex.readSampleData(tampon, 0)
                if (t < 0) break
                tampon.position(0)
                tampon.get(octets, 0, t)
                if (echantillonAbime(octets, t)) n++
                if (!ex.advance()) break
            }
            n to cote
        } finally { ex.release() }
    }.getOrDefault(0 to 0)

    /** Cadence moyenne de toute la vidéo (images / seconde), telle que TikTok la mesure ; null si illisible. */
    private fun cadenceMoyenne(f: File): Double? = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            val piste = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return@runCatching null
            ex.selectTrack(piste)
            var n = 0; var max = 0L; var min = Long.MAX_VALUE
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                n++; if (t > max) max = t; if (t < min) min = t
                if (!ex.advance()) break
            }
            if (n < 3 || max <= min) null else (n - 1) * 1_000_000.0 / (max - min)
        } finally { ex.release() }
    }.getOrNull()

    private fun dureeMs(f: File): Long? = runCatching {
        MediaMetadataRetriever().run {
            try { setDataSource(f.absolutePath); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() }
        }
    }.getOrNull()?.takeIf { it > 0 } ?: if (f.extension.lowercase() == "wav") dureeWav(f) else null

    /** Durée d'un WAV lue dans son en-tête (certains téléphones ne la donnent pas via MediaMetadataRetriever). */
    internal fun dureeWav(f: File): Long? = runCatching {
        val b = f.inputStream().use { ins -> val t = ByteArray(4096); val n = ins.read(t).coerceAtLeast(0); t.copyOf(n) }
        fun le32(o: Int) = (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)
        if (String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return@runCatching null
        var o = 12; var octetsParSeconde = 0L; var donnees = -1L; var debutDonnees = 0
        while (o + 8 <= b.size) {
            val id = String(b, o, 4); val taille = le32(o + 4)
            if (id == "fmt ") octetsParSeconde = le32(o + 16)
            if (id == "data") { donnees = taille; debutDonnees = o + 8; break }
            o += 8 + taille.toInt() + (taille.toInt() and 1)
        }
        // Taille « data » absente ou invalide (écriture en flux) : le reste du fichier.
        if (donnees <= 0 || donnees > f.length()) donnees = f.length() - debutDonnees
        if (octetsParSeconde <= 0) null else donnees * 1000 / octetsParSeconde
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

    /** Morceaux de 190 caractères au plus, coupés entre deux mots (limite de la voix Google Traduction). */
    internal fun morceaux(phrases: List<String>, max: Int = 190): List<String> = phrases.flatMap { p ->
        if (p.length <= max) listOf(p) else p.split(' ').fold(mutableListOf("")) { acc, mot ->
            if ((acc.last() + " " + mot).trim().length > max) acc += mot else acc[acc.size - 1] = (acc.last() + " " + mot).trim()
            acc
        }
    }.filter { it.isNotBlank() }

    /** Voix de Google Traduction (gratuite, non officielle) : (texte, fichier mp3, durée) par morceau, ou liste vide. */
    private suspend fun voixGoogle(phrases: List<String>, dossier: File): List<Triple<String, File, Long>> = withContext(Dispatchers.IO) {
        val http = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
        val out = mutableListOf<Triple<String, File, Long>>()
        for ((i, m) in morceaux(phrases).withIndex()) {
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=fr&q=" + java.net.URLEncoder.encode(m, "UTF-8")
            val f = File(dossier, "voix_google_$i.mp3")
            val ok = runCatching {
                http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 16)").build()).execute().use { r ->
                    val b = r.body?.bytes()
                    if (r.isSuccessful && b != null && b.size > 1_000) { f.writeBytes(b); true } else false
                }
            }.getOrDefault(false)
            val duree = if (ok) dureeMs(f) else null
            if (duree == null || duree <= 0) return@withContext emptyList()
            out += Triple(m, f, duree)
        }
        out
    }

    /** Voix Gemini des montages : narrateur de bande-annonce, grave et intense. */
    private const val VOIX_MONTAGE = "Alnilam"

    private fun styleVoix(d: Demande) = "Voix de narrateur de bande-annonce, profonde, intense et solennelle, ton engagé et habité, rythme soutenu avec de courtes pauses." +
        (if (d.style.isNotBlank()) " Ton de la chaîne : ${d.style.take(160)}." else "") +
        " Parle en français, débit naturel ; ne prononce jamais la ponctuation ni les symboles."

    /** PCM 16 bits → fichier WAV. */
    internal fun ecrireWav(pcm: ByteArray, frequence: Int, canaux: Int, f: File) {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(canaux.toShort()).putInt(frequence).putInt(frequence * canaux * 2)
            .putShort((canaux * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size)
        f.outputStream().use { it.write(b.array()); it.write(pcm) }
    }

    /**
     * Voix off phrase par phrase (un fichier WAV par phrase, même moteur et même voix) : renvoie chaque fichier et sa
     * durée, ou une liste vide si la synthèse échoue (repli : une seule piste, sans sous-titres).
     */
    private suspend fun synthesePhrases(app: JarvisApp, phrases: List<String>, dossier: File): List<Pair<File, Long>> {
        val tts = withTimeoutOrNull(20_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<TextToSpeech?> { cont ->
                    var t: TextToSpeech? = null
                    t = TextToSpeech(app) { st -> if (cont.isActive) cont.resume(if (st == TextToSpeech.SUCCESS) t else null) }
                }
            }
        } ?: return emptyList()
        return try {
            tts.language = Locale.FRANCE
            tts.setSpeechRate(1.05f)
            val out = mutableListOf<Pair<File, Long>>()
            for ((i, p) in phrases.withIndex()) {
                val f = File(dossier, "voix_$i.wav")
                val ok = withTimeoutOrNull(30_000) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}
                            override fun onDone(id: String?) { if (cont.isActive) cont.resume(true) }
                            @Deprecated("Deprecated in Java")
                            override fun onError(id: String?) { if (cont.isActive) cont.resume(false) }
                        })
                        if (tts.synthesizeToFile(p.take(3900), Bundle(), f, "voix_$i") != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
                    }
                } == true
                val duree = if (ok && f.length() > 1000) dureeMs(f) else null
                if (duree == null || duree <= 0) return emptyList()
                out += f to duree
            }
            out
        } finally { runCatching { tts.shutdown() } }
    }

    /**
     * Temps d'une incrustation : selon les versions d'Android/Media3, l'horodatage reçu par un effet est celui de la
     * vidéo finale ou celui du plan. Le premier horodatage reçu tranche ; renvoie le temps dans la vidéo finale (µs).
     */
    private class Horloge(private val debutUs: Long) {
        private var premier: Long? = null
        fun global(t: Long): Long {
            val p = premier ?: t.also { premier = it }
            return if (debutUs > 0 && p >= debutUs - 100_000) t else t + debutUs
        }
        fun local(t: Long): Long = global(t) - debutUs
    }

    /** Sous-titres d'un plan : le morceau de phrase dit à cet instant (aucun entre deux phrases). */
    @OptIn(UnstableApi::class)
    private class OverlaySousTitres(private val reperes: List<SousTitres.Repere>, private val debutUs: Long, private val largeur: Int) : BitmapOverlay() {
        private val horloge = Horloge(debutUs)
        private val vide = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private val cache = HashMap<SousTitres.Repere, Bitmap>()
        private val reglages = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.5f).build()
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val r = SousTitres.a(reperes, horloge.global(presentationTimeUs) / 1000) ?: return vide
            return cache.getOrPut(r) { bandeau(r.texte, largeur, sousTitre = true) }
        }
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = reglages
    }

    // ------------------------------------------------------------------ rendu

    /** Bandeau de texte (blanc, contour noir, centré, sur plusieurs lignes) pour l'incrustation. */
    private fun bandeau(texte: String, largeur: Int, sousTitre: Boolean = false, taille: Float = 0f): Bitmap {
        val taille = largeur * (if (taille > 0f) taille else if (sousTitre) 0.075f else 0.07f)
        val fond = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = taille; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            style = Paint.Style.STROKE; strokeWidth = taille * 0.16f; color = Color.BLACK; strokeJoin = Paint.Join.ROUND
        }
        // Sous-titres en jaune (style TikTok) pour les distinguer du texte du plan.
        val plein = TextPaint(fond).apply { style = Paint.Style.FILL; color = if (sousTitre) Color.rgb(255, 221, 51) else Color.WHITE }
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
        app: JarvisApp, segments: List<Segment>, voix: List<File>, musique: File?, avecTexte: Boolean, garderSon: Boolean,
        largeur: Int, hauteur: Int, chanson: Boolean, sortie: File, reperes: List<SousTitres.Repere> = emptyList(),
        tiktok: Boolean = false, bandeau: String = "", logiciel: Boolean = true, portrait: Boolean = true, texteDansImages: Boolean = false,
        hevc: Boolean = false
    ) {
        // Repli : texte et bandeau dessinés dans les images (sans incrustation Media3), les vidéos restent sans texte.
        val segments = if (!texteDansImages) segments else withContext(Dispatchers.IO) {
            segments.mapIndexed { k, s ->
                if (!s.image) s
                else imageAvecTexte(s.fichier, s.texte, bandeau, largeur, hauteur, tiktok, File(sortie.parentFile, ".texte_${k}_${sortie.nameWithoutExtension}.jpg"))
                    ?.let { s.copy(fichier = it) } ?: s
            }
        }
        val ipsSources = HashMap<File, Double?>()
        val presentation = Presentation.createForWidthAndHeight(largeur, hauteur, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        var debutMs = 0L
        val imageBandeau = if (bandeau.isNotBlank()) bandeau(bandeau.uppercase(), largeur, taille = 0.038f) else null
        val video = segments.mapIndexed { rang, s ->
            val debutUs = debutMs * 1000
            debutMs += s.dureeMs
            val effets = mutableListOf<Effect>(presentation)
            // Plans à 50 / 60 images/s ramenés à 30 : cadence régulière (TikTok mesure la cadence de la vidéo).
            if (!s.image && (ipsSources.getOrPut(s.fichier) { ipsVideo(s.fichier) } ?: 30.0) > 31.0)
                effets.add(0, androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(30f))
            if (s.image) {
                // Image animée au montage, mouvement différent d'un plan à l'autre : zoom avant, zoom arrière,
                // travelling horizontal, travelling vertical (compté depuis le début de CE plan).
                val dureeUs = s.dureeMs * 1000f
                val horloge = Horloge(debutUs)
                val mouvement = rang % 4
                val ampleur = if (tiktok) 0.25f else 0.08f
                effets += MatrixTransformation { t ->
                    val p = (horloge.local(t) / dureeUs).coerceIn(0f, 1f)
                    Matrix().apply {
                        when (mouvement) {
                            0 -> { val z = 1f + ampleur * p; setScale(z, z) }
                            1 -> { val z = 1f + ampleur * (1f - p); setScale(z, z) }
                            2 -> { setScale(1f + ampleur * 0.8f, 1f + ampleur * 0.8f); postTranslate(ampleur * 0.5f * (2f * p - 1f), 0f) }
                            else -> { setScale(1f + ampleur * 0.8f, 1f + ampleur * 0.8f); postTranslate(0f, ampleur * 0.4f * (1f - 2f * p)) }
                        }
                    }
                }
            }
            val overlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
            if (avecTexte && s.texte.isNotBlank()) {
                // TikTok : phrase-choc en MAJUSCULES au centre (sous-titres plus bas) ; ailleurs en bas, ou en haut s'il y a des sous-titres.
                val y = if (tiktok) -0.12f else if (reperes.isNotEmpty()) 0.62f else -0.58f
                val reglages = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).build()
                overlays += BitmapOverlay.createStaticBitmapOverlay(if (tiktok) bandeau(s.texte.uppercase(), largeur, taille = 0.065f) else bandeau(s.texte, largeur), reglages)
            }
            if (imageBandeau != null) overlays += BitmapOverlay.createStaticBitmapOverlay(imageBandeau, OverlaySettings.Builder().setBackgroundFrameAnchor(0f, 0.9f).build())
            if (reperes.isNotEmpty()) overlays += OverlaySousTitres(reperes, debutUs, largeur)
            if (overlays.isNotEmpty()) effets += OverlayEffect(ImmutableList.copyOf(overlays))
            val item = if (s.image) MediaItem.Builder().setUri(Uri.fromFile(s.fichier)).setImageDurationMs(s.dureeMs).build()
            else MediaItem.Builder().setUri(Uri.fromFile(s.fichier))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.debutMs).setEndPositionMs(s.debutMs + s.dureeMs).build()).build()
            EditedMediaItem.Builder(item).apply {
                if (s.image) { setDurationUs(s.dureeMs * 1000); setFrameRate(30) }
                setRemoveAudio(s.image || !garderSon)
                setEffects(Effects(if (garderSon && !s.image) listOf(volume(if (musique != null || voix.isNotEmpty()) 0.5f else 1f)) else emptyList(), effets))
            }.build()
        }
        val totalMs = segments.sumOf { it.dureeMs }
        val sequences = mutableListOf(EditedMediaItemSequence(video))
        if (musique != null) {
            val niveau = if (voix.isNotEmpty()) 0.28f else if (chanson) 1f else 0.8f
            sequences += EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(musique)))
                .setRemoveVideo(true).setEffects(Effects(listOf(volume(niveau)), emptyList())).build()), true)
        }
        if (voix.isNotEmpty()) {
            // Phrases enchaînées ; celle qui dépasserait la fin de la vidéo est coupée.
            var cumul = 0L
            val pistes = voix.mapNotNull { f ->
                val reste = totalMs - cumul
                if (reste < 200) return@mapNotNull null
                val d = dureeMs(f) ?: reste
                cumul += min(d, reste)
                val item = MediaItem.Builder().setUri(Uri.fromFile(f))
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(min(d, reste)).build()).build()
                EditedMediaItem.Builder(item).setRemoveVideo(true).setEffects(Effects(listOf(volume(1f)), emptyList())).build()
            }
            if (pistes.isNotEmpty()) sequences += EditedMediaItemSequence(pistes)
        }
        val composition = Composition.Builder(sequences).experimentalSetForceAudioTrack(true).build()
        if (sortie.exists()) sortie.delete()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                // Vrai portrait (pas une vidéo couchée + rotation, mal lue par TikTok), H.264, débit élevé (qualité TikTok).
                // Encodeur logiciel (Google) ou, en second essai, celui du téléphone.
                val selecteur = androidx.media3.transformer.EncoderSelector { mime ->
                    val tous = androidx.media3.transformer.EncoderUtil.getSupportedEncoders(mime)
                    if (!logiciel) tous
                    else com.google.common.collect.ImmutableList.copyOf(tous.filter { info ->
                        encodeurLogiciel(info.name) || (android.os.Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly)
                    })
                }
                val encodeur = androidx.media3.transformer.DefaultEncoderFactory.Builder(app)
                    .setVideoEncoderSelector(selecteur)
                    // Image clé chaque seconde : un éventuel défaut de l'encodeur ne se propage pas plus d'une seconde.
                    .setRequestedVideoEncoderSettings(androidx.media3.transformer.VideoEncoderSettings.Builder()
                        .setBitrate(if (largeur * hauteur >= 1080 * 1920) 12_000_000 else 8_000_000).setiFrameIntervalSeconds(1f).build())
                    .setEnableFallback(true).build()
                val transformer = Transformer.Builder(app)
                    .setVideoMimeType(if (hevc) androidx.media3.common.MimeTypes.VIDEO_H265 else androidx.media3.common.MimeTypes.VIDEO_H264)
                    // Portrait direct, ou image couchée + rotation (comme les vidéos filmées) si l'encodeur n'accepte que le paysage.
                    .setPortraitEncodingEnabled(portrait)
                    .setEncoderFactory(encodeur)
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
