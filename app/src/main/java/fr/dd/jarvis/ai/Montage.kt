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
        val sousTitres: Boolean = true,
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
    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "oga", "wav", "flac", "opus")

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
        val sansIa = !ImageToVideoGen.disponible(app) && app.store.huggingFaceToken.value.isBlank() && !VideoProviderGen.disponible(app)
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
        val (largeur, hauteur) = if (vertical) 720 to 1280 else 1280 to 720
        val travail = File(app.cacheDir, "montage_${System.currentTimeMillis()}").apply { mkdirs() }

        // 1. Scénario : plans (visuel + texte à l'écran), voix off, musique, paroles, légende.
        progres("✍️ Écriture du scénario…")
        val existants = resoudreSources(app, d.sources, travail)
        // TikTok : un plan toutes les ~3 s (rythme de la plateforme) ; ailleurs toutes les ~5 s.
        val nbPlans = if (d.tiktok) ceil(d.dureeS / 3.0).toInt().coerceIn(3, 12) else max(2, ceil(d.dureeS / 5.0).toInt())
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
            d.style.isNotBlank() && sb.signature.isNotBlank() && app.store.cfAccounts.any { it.contains(':') } -> {
                progres("🎨 Image de signature (${d.style.take(40)})…")
                runCatching { listOf(ImageGen.generate(app, sb.signature + ", epic, highly detailed, vertical portrait 9:16 composition, no text, no letters").first to true) }
                    .getOrElse { echecs += "image de signature : ${it.message?.take(120)}"; emptyList() }
            }
            d.style.isNotBlank() && sb.signature.isNotBlank() -> { echecs += "image de signature : ajoute un compte Cloudflare (Réglages › IA › Images) ou tes images dans Réglages › TikTok"; emptyList() }
            else -> emptyList()
        }
        // 2a. Un média par plan du scénario, gratuit : image générée (Cloudflare) pour un symbole ou une allégorie (« phénix
        // tricolore au-dessus de Paris »), plan réel libre de droits (Pixabay / Pexels) pour une scène filmable.
        val modePlans = d.plans.trim().lowercase()
        val credits = mutableListOf<Stock.Clip>()
        val cfOk = app.store.cfAccounts.any { it.contains(':') }
        fun veutImage(p: Plan) = cfOk && when (modePlans) {
            "images" -> true
            "stock", "ia" -> false
            else -> p.source == "image" || !Stock.disponible(app)
        }
        val medias = mutableListOf<Media>()
        val pris = mutableSetOf<String>()
        val imagesParPlan = HashMap<Int, File>()
        suspend fun image(rang: Int, p: Plan): File? = imagesParPlan[rang] ?: runCatching {
            ImageGen.generate(app, p.visuel + ", vertical portrait 9:16 composition, cinematic lighting, highly detailed, no text, no letters, no watermark").first
        }.onFailure { echecs += "image ${rang + 1} : ${it.message?.take(110)}" }.getOrNull()?.also { imagesParPlan[rang] = it }
        suspend fun reel(i: Int, p: Plan): File? {
            val requetes = listOf(p.recherche, Stock.motsCles(p.visuel), Stock.motsCles(p.visuel, 2)).filter { it.isNotBlank() }.distinct()
            val clip = requetes.firstNotNullOfOrNull { q -> Stock.chercher(app, q, vertical, pris) }
                // Rien en anglais : mots du sujet en français (Pixabay et Pexels cherchent aussi en français).
                ?: Stock.motsClesFr(d.sujet).let { mots -> listOf(mots.joinToString(" "), mots.firstOrNull().orEmpty()) }
                    .filter { it.isNotBlank() }.distinct().firstNotNullOfOrNull { q -> Stock.chercher(app, q, vertical, pris, francais = true) }
            val f = clip?.let { Stock.telecharger(it, File(travail, "stock_$i.mp4")) }
            if (clip != null && f != null) credits += clip
            return f
        }
        if (manqueTotal > 0 && modePlans != "ia" && (Stock.disponible(app) || cfOk)) {
            val nb = if (d.tiktok) ceil(manqueTotal / 3000.0).toInt().coerceIn(1, 12) else max(1, ceil(manqueTotal / 4000.0).toInt())
            for (i in 0 until nb) {
                verifierAnnulation()
                val rang = (existants.size + i) % sb.plans.size
                val p = sb.plans[rang]
                val img = veutImage(p)
                progres(if (img) "🎨 Image ${i + 1}/$nb (Cloudflare)…" else "🎞️ Plan ${i + 1}/$nb libre de droits (Pixabay / Pexels)…")
                // Source préférée, puis l'autre si elle ne donne rien (jamais de trou dans la vidéo).
                val m = if (img) image(rang, p)?.let { Media(i, it, true, p.texte) }
                    ?: (if (Stock.disponible(app) && modePlans != "images") reel(i, p)?.let { Media(i, it, false, p.texte) } else null)
                else (if (Stock.disponible(app)) reel(i, p)?.let { Media(i, it, false, p.texte) } else null)
                    ?: (if (cfOk && modePlans != "stock") image(rang, p)?.let { Media(i, it, true, p.texte) } else null)
                if (m != null) medias += m
            }
            if (medias.isEmpty()) echecs += "aucune image ni aucun plan libre de droits obtenu"
        }
        // 2b. Animation IA de la première image (Hugging Face, quota gratuit du jour) ; sinon elle sera animée au montage.
        val premiere = medias.firstOrNull { it.image }
        if (premiere != null && d.tiktok && app.store.huggingFaceToken.value.isNotBlank() && modePlans != "stock") {
            progres("🎥 Animation de la première image (Hugging Face, quota gratuit)…")
            val prompt = sb.plans.getOrNull((existants.size + premiere.i) % sb.plans.size)?.visuel.orEmpty() +
                ". Animate this exact image: flames, feathers and smoke move, slow epic camera push-in, keep composition, no text."
            val clip = withTimeoutOrNull(5 * 60_000L) {
                runCatching { withContext(Dispatchers.IO) { VideoGen.genereClip(app, prompt, 4, vertical, image = premiere.fichier) } }
                    .onFailure { echecs += "animation IA de l'image : ${it.message?.take(110)} (animée au montage à la place)" }.getOrNull()
            }
            if (clip != null && clip.size > 10_000) {
                val f = File(travail, "anime_${premiere.i}.mp4").apply { writeBytes(clip) }
                medias[medias.indexOf(premiere)] = premiere.copy(fichier = f, image = false)
            }
        }
        // Plans trouvés (images ou réels) : ils couvrent toute la durée, sans compléter par une IA vidéo payante
        // (fal.ai) ou sans quota gratuit (Gemini). L'IA vidéo ne sert qu'en l'absence de tout plan.
        val clipsStock = medias
        val manque = if (modePlans == "stock" || modePlans == "images" || (modePlans == "auto" && medias.isNotEmpty())) 0L else manqueTotal
        val iaPossible = ImageToVideoGen.disponible(app) || app.store.huggingFaceToken.value.isNotBlank() || VideoProviderGen.disponible(app)
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
        if (briques.isEmpty()) return "❌ Aucun plan n'a pu être créé" + (if (echecs.isNotEmpty()) " :\n- " + echecs.joinToString("\n- ") else ".")
        val segments = mutableListOf<Segment>()
        // Signature : ouverture (et fin dès 15 s), 2,5 s chacune ; le milieu est rempli par les plans.
        val ouverture = signature.firstOrNull()
        val fin = signature.lastOrNull()?.takeIf { ms >= 15_000 }
        val dureeSignature = 2_500L
        val milieu = ms - (if (ouverture != null) dureeSignature else 0) - (if (fin != null) dureeSignature else 0)
        if (ouverture != null) segments += Segment(ouverture.first, ouverture.second, dureeSignature, if (d.textes) sb.plans.firstOrNull()?.texte.orEmpty() else "")
        var total = 0L; var i = 0
        // TikTok : coupes de 3,5 s au plus ; un plan réutilisé reprend là où il s'était arrêté (pas deux fois la même image).
        val maxParMorceau = if (d.tiktok) 3_500L else if (briques.size >= 3) max(4000L, milieu / briques.size * 2) else milieu
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
        val avecParoles = d.paroles.isNotBlank()
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
        val voix: List<File> = voixNaturelle?.let { listOf(it.first) } ?: voixPhrases.map { it.first }.ifEmpty {
            if (texteVoix.isNotBlank()) listOfNotNull(synthese(app, texteVoix, File(travail, "voix.wav"))) else emptyList()
        }
        if (texteVoix.isNotBlank() && voix.isEmpty())
            echecs += "voix off : la synthèse vocale du téléphone n'a rien produit (vérifie Paramètres Android › Synthèse vocale : moteur Google et voix française installée)"
        val reperes = when {
            !d.sousTitres || phrases.isEmpty() -> emptyList()
            voixNaturelle != null -> SousTitres.reperes(phrases, SousTitres.repartir(phrases, voixNaturelle.second))
            voixPhrases.size == phrases.size -> SousTitres.reperes(phrases, voixPhrases.map { it.second })
            else -> emptyList()
        }
        val musique: File? = when (d.musique.trim().lowercase()) {
            "aucune", "non", "false", "sans" -> null
            else -> {
                val existant = if (d.musique.isNotBlank() && d.musique.lowercase() != "auto") resoudreSources(app, d.musique, travail).firstOrNull()?.first else null
                existant ?: if (!ImageToVideoGen.disponible(app)) musiqueLocale(app, travail).also {
                    if (it == null) echecs += "musique : aucune (dépose des musiques libres de droits dans le dossier « Music » du téléphone ou « JARVIS/Musiques » du coffre, ou ajoute le son tendance dans TikTok)"
                } else if (ImageToVideoGen.disponible(app)) {
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
                exporter(app, segments, if (avecSon) voix else emptyList(), if (avecSon) musique else null, avecTexte, d.garderSon && avecSon, largeur, hauteur, avecParoles, sortie,
                    if (avecTexte && avecSon) reperes else emptyList(), tiktok = d.tiktok, bandeau = if (avecTexte && d.tiktok) sb.bandeau else "")
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
                    (if (sb.legende.isNotBlank()) "\n\n## Légende\n${sb.legende}" else "") +
                    (if (credits.isNotEmpty()) "\n\n## Crédits des plans libres de droits\n" + credits.joinToString("\n") { "- ${it.source} · ${it.auteur.ifBlank { "auteur inconnu" }} · ${it.page}" } else ""))
        }
        // Brouillon TikTok : la vidéo part dans la boîte de réception TikTok (son tendance et publication dans l'appli).
        val envoi = if (d.tiktok && d.brouillon) runCatching { TikTok.envoyerBrouillon(app, sortie) }.getOrElse { "❌ Envoi en brouillon TikTok impossible : ${it.message}" } else ""
        return buildString {
            append("🎬 « ${sb.titre} » — ${d.dureeS} s, ${segments.size} plans")
            val pistes = listOfNotNull(if (voix.isNotEmpty() && abandons.isBlank()) "voix off" else null,
                if (reperes.isNotEmpty() && abandons.isBlank()) "sous-titres" else null,
                if (musique != null && abandons != "sans texte ni son") (if (avecParoles) "chanson avec paroles" else "musique") else null,
                if (d.textes && abandons.isBlank()) "texte à l'écran" else null)
            if (pistes.isNotEmpty()) append(" · " + pistes.joinToString(", "))
            append("\n\n[▶ Lire la vidéo](jarvis-video-ia:$nom)")
            if (loc.isNotBlank()) append("\n📁 $loc")
            if (sb.analyse.isNotBlank()) append("\n✍️ Scénario et prompts des plans enregistrés dans la note « ${nom.removeSuffix(".mp4")} » du coffre.")
            if (sb.legende.isNotBlank()) append("\n\n📝 Légende : ${sb.legende}")
            if (credits.isNotEmpty()) append("\n🎞️ Plans libres de droits : " + credits.map { it.source }.distinct().joinToString(" et ") + " (auteurs crédités dans la note)")
            if (envoi.isNotBlank()) append("\n\n$envoi")
            if (abandons.isNotBlank()) append("\n⚠️ Montage fait $abandons (effet refusé par le téléphone).")
            if (echecs.isNotEmpty()) append("\n⚠️ " + echecs.joinToString("\n⚠️ "))
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

    /** Une musique déposée par l'utilisateur : dossier « Music » du téléphone ou « JARVIS/Musiques » du coffre (au hasard). */
    private fun musiqueLocale(app: JarvisApp, travail: File): File? {
        val telephone = runCatching { File(Stockage.racine(), "Music").walkTopDown().maxDepth(2).filter { it.isFile && it.extension.lowercase() in AUDIO && it.length() > 50_000 }.toList() }
            .getOrDefault(emptyList())
        if (telephone.isNotEmpty()) return telephone.random()
        val o = app.obsidian
        val coffre = runCatching { o.chercherFichiers("JARVIS/Musiques/*", limite = 100).filter { it.substringAfterLast('.', "").lowercase() in AUDIO } }.getOrDefault(emptyList())
        return coffre.randomOrNull()?.let { o.versFichierLocal(it, File(travail, "musique")) }
    }

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
        tiktok: Boolean = false, bandeau: String = ""
    ) {
        val presentation = Presentation.createForWidthAndHeight(largeur, hauteur, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        var debutMs = 0L
        val imageBandeau = if (bandeau.isNotBlank()) bandeau(bandeau.uppercase(), largeur, taille = 0.038f) else null
        val video = segments.mapIndexed { rang, s ->
            val debutUs = debutMs * 1000
            debutMs += s.dureeMs
            val effets = mutableListOf<Effect>(presentation)
            if (s.image) {
                // Image animée au montage, mouvement différent d'un plan à l'autre : zoom avant, zoom arrière,
                // travelling horizontal, travelling vertical (compté depuis le début de CE plan).
                val dureeUs = s.dureeMs * 1000f
                val horloge = Horloge(debutUs)
                val mouvement = rang % 4
                val ampleur = if (tiktok) 0.2f else 0.08f
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
                overlays += BitmapOverlay.createStaticBitmapOverlay(if (tiktok) bandeau(s.texte.uppercase(), largeur, taille = 0.085f) else bandeau(s.texte, largeur), reglages)
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
