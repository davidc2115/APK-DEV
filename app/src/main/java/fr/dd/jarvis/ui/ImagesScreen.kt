package fr.dd.jarvis.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.images.Pollinations
import fr.dd.jarvis.images.PromptImage
import fr.dd.jarvis.images.StudioImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** État du Studio conservé quand on change d'onglet : une génération en cours continue et son résultat reste affiché. */
private object StudioEtat {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var job: Job? = null
    var enCours by mutableStateOf(false)
    var progres by mutableStateOf("")
    var erreur by mutableStateOf("")
    var resultat by mutableStateOf<StudioImages.Resultat?>(null)
    var version by mutableIntStateOf(0)
}

/**
 * Onglet « Images » : Studio de génération Pollinations, séparé du reste de JARVIS (le chat et les outils n'y ont
 * pas accès, rien n'est envoyé ailleurs). Contenu adulte après confirmation de majorité, avec garde-fous locaux.
 */
@Composable
fun ImagesScreen(app: JarvisApp) {
    val store = app.store
    val ctx = LocalContext.current
    val activite = ctx as? android.app.Activity

    // Masqué des apps récentes / captures si demandé.
    DisposableEffect(store.imagesSecure.value) {
        if (store.imagesSecure.value) activite?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activite?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // Verrou : redemandé à chaque ouverture de l'onglet et à chaque retour dans l'application.
    var deverrouille by remember { mutableStateOf(!store.imagesVerrou.value) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && store.imagesVerrou.value) deverrouille = false }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    if (!deverrouille) { Verrou(ctx) { deverrouille = true }; return }

    var demande by rememberSaveable { mutableStateOf("") }
    var adulte by rememberSaveable { mutableStateOf(false) }
    var modele by rememberSaveable { mutableStateOf("auto") }
    var format by rememberSaveable { mutableStateOf("auto") }
    var cleSaisie by rememberSaveable { mutableStateOf("") }
    var changerCle by rememberSaveable { mutableStateOf(false) }
    var modeles by remember { mutableStateOf(StudioImages.modelesEnCache(app)) }
    var modelesTexte by remember { mutableStateOf<List<String>>(emptyList()) }
    var edition by remember { mutableStateOf(false) }
    var promptEdit by remember { mutableStateOf("") }
    var negatifEdit by remember { mutableStateOf("") }
    var ouverte by remember { mutableStateOf<StudioImages.Image?>(null) }
    var confirmerTout by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    val reveles = remember { mutableStateListOf<String>() }
    val galerie = remember(StudioEtat.version) { StudioImages.galerie(app) }
    val avecCle = store.pollinationsCle.value.isNotBlank()

    LaunchedEffect(avecCle) {
        runCatching { modeles = withContext(Dispatchers.IO) { StudioImages.rafraichirModeles(app) } }
    }

    fun lancer(d: String, o: StudioImages.Options) {
        if (StudioEtat.enCours) return
        StudioEtat.enCours = true; StudioEtat.erreur = ""; StudioEtat.progres = "Préparation…"
        StudioEtat.job = StudioEtat.scope.launch {
            try {
                val r = StudioImages.generer(app, d, o) { p -> StudioEtat.scope.launch { StudioEtat.progres = p } }
                StudioEtat.resultat = r
                StudioEtat.version++
                StudioEtat.erreur = when {
                    r.images.isEmpty() -> r.erreurs.firstOrNull() ?: "Aucune image reçue."
                    r.erreurs.isNotEmpty() -> "${r.erreurs.size} image(s) en échec : " + r.erreurs.first()
                    else -> ""
                }
            } catch (e: CancellationException) {
                StudioEtat.erreur = "Génération annulée."
            } catch (e: Exception) {
                StudioEtat.erreur = e.message ?: e.javaClass.simpleName
            } finally {
                StudioEtat.enCours = false; StudioEtat.progres = ""
            }
        }
    }

    fun formatChoisi(): PromptImage.Format? = when (format) {
        "portrait" -> PromptImage.Format.PORTRAIT; "carre" -> PromptImage.Format.CARRE; "paysage" -> PromptImage.Format.PAYSAGE; else -> null
    }

    fun formatDe(im: StudioImages.Image): PromptImage.Format = when {
        im.largeur > im.hauteur -> PromptImage.Format.PAYSAGE; im.largeur < im.hauteur -> PromptImage.Format.PORTRAIT; else -> PromptImage.Format.CARRE
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── Présentation + clé ──
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "🎨 Studio d'images") {
                Text("Décris l'image en français : JARVIS vérifie la demande, réécrit un prompt détaillé en anglais, choisit le modèle " +
                    "Pollinations adapté (photo réaliste, anime, illustration, texte dans l'image, rapide…) puis génère. Les images " +
                    "restent privées dans l'application tant que tu ne les enregistres pas dans la galerie. Cet onglet est séparé : " +
                    "le chat et les outils de JARVIS n'y ont pas accès.", color = HudDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                if (avecCle && !changerCle) {
                    val c = store.pollinationsCle.value
                    Text("🔑 Clé Pollinations enregistrée (${c.take(3)}…${c.takeLast(4)}) : tous les modèles, jusqu'à 2048 px.", color = HudText, fontSize = 12.sp)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip("Changer") { changerCle = true; cleSaisie = "" }
                        HudChip("Supprimer") { runCatching { store.pollinationsCle.clear() }; store.imagesModelesCache.set("") }
                    }
                } else {
                    Text(if (avecCle) "Nouvelle clé :" else "Sans clé : modèle flux uniquement, 768 px max, une image toutes les 15-45 s. Une clé (gratuite, " +
                        "enter.pollinations.ai) débloque zimage, turbo, qwen-image… et la réécriture par les modèles de texte Pollinations.",
                        color = HudDim, fontSize = 11.sp)
                    HudTextField(cleSaisie, { cleSaisie = it.trim() }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "Clé Pollinations (sk_… ou pk_…)", password = true)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudButton("Enregistrer", enabled = cleSaisie.length >= 8) {
                            info = runCatching { store.pollinationsCle.set(cleSaisie); cleSaisie = ""; changerCle = false; "✅ Clé enregistrée (chiffrée)." }
                                .getOrElse { "⚠️ " + (it.message ?: "Enregistrement impossible") }
                        }
                        HudButton("Obtenir une clé") {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Pollinations.SITE_CLE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        if (changerCle) HudChip("Annuler") { changerCle = false; cleSaisie = "" }
                    }
                }
                if (info.isNotBlank()) Text(info, color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // ── Contenu adulte, confidentialité ──
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "🔞 Contenu adulte et confidentialité") {
                if (!store.imagesMajeur.value) {
                    Text("Le contenu adulte (nudité, érotisme) est réservé aux personnes majeures. Il reste soumis à des règles fixes, " +
                        "vérifiées sur le téléphone avant tout envoi : personnages adultes et fictifs uniquement, jamais de mineur ni " +
                        "d'apparence juvénile, jamais de personne réelle (célébrité, proche, nom de tes contacts), uniquement des scènes consenties.",
                        color = HudDim, fontSize = 12.sp)
                    HudButton("J'ai 18 ans ou plus — activer", Modifier.padding(top = 8.dp), filled = true) { store.imagesMajeur.set(true) }
                } else {
                    Text("Activé · personnages adultes fictifs et consentants uniquement (règles vérifiées sur le téléphone). " +
                        "Gemini n'est jamais utilisé pour réécrire une demande adulte : ses règles l'interdisent et la clé risquerait d'être bloquée.",
                        color = HudDim, fontSize = 11.sp)
                    Interrupteur("Flouter les aperçus (toucher pour voir)", store.imagesFlou.value) { store.imagesFlou.set(it) }
                    Interrupteur("Verrouiller l'onglet (empreinte / code)", store.imagesVerrou.value) { v ->
                        if (!v) store.imagesVerrou.set(false)
                        else authentifier(ctx, { store.imagesVerrou.set(true) }, { info = "⚠️ $it" })
                    }
                    Interrupteur("Masquer des apps récentes et des captures", store.imagesSecure.value) { store.imagesSecure.set(it) }
                    HudChip("Désactiver le contenu adulte") { store.imagesMajeur.set(false); adulte = false }
                }
            }
        }

        // ── Demande ──
        item {
            HudPanel(Modifier.fillMaxWidth(), title = "✨ Nouvelle image") {
                HudTextField(demande, { demande = it }, Modifier.fillMaxWidth(), label = "Ta demande",
                    placeholder = "ex : portrait photo d'une femme rousse en robe de soirée, lumière de bougies", singleLine = false, maxLines = 6)
                Etiquette("Contenu")
                Rangee {
                    HudChip("Classique", !adulte) { adulte = false }
                    if (store.imagesMajeur.value) HudChip("🔞 Adulte", adulte) { adulte = true }
                    else Text("(🔞 à activer au-dessus)", color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Etiquette("Format")
                Rangee {
                    listOf("auto" to "Auto", "portrait" to "▯ Portrait", "carre" to "◻ Carré", "paysage" to "▭ Paysage")
                        .forEach { (k, v) -> HudChip(v, format == k) { format = k } }
                }
                Etiquette("Nombre d'images")
                Rangee {
                    listOf(1, 2, 4).forEach { n -> HudChip("×$n", store.imagesNombre.value == n) { store.imagesNombre.set(n) } }
                    if (!avecCle) Text("(2 max sans clé)", color = HudDim, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Etiquette("Modèle")
                Rangee {
                    HudChip("🤖 Auto", modele == "auto") { modele = "auto" }
                    (if (avecCle) modeles else modeles.filter { it.nom in setOf("flux", "turbo") }).forEach { m ->
                        HudChip(m.nom + (if (m.payant) " 💰" else "") + (if (PromptImage.modere(m.nom)) " 🛡" else ""), modele == m.nom) { modele = m.nom }
                    }
                }
                Text(when (modele) {
                    "auto" -> "Auto : zimage pour la photo réaliste, flux pour l'illustration / l'anime, qwen-image pour le texte dans l'image, turbo si tu dis « rapide ». En adulte, jamais un modèle 🛡 (filtré)."
                    else -> modeles.firstOrNull { it.nom == modele }?.description.orEmpty().ifBlank { modele } + if (PromptImage.modere(modele)) " · 🛡 filtre la nudité" else ""
                }, color = HudDim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                Etiquette("Réécriture du prompt")
                Rangee {
                    listOf("auto" to "Auto", "gemini" to "Gemini", "pollinations" to "Pollinations", "locale" to "Locale", "aucune" to "Aucune")
                        .forEach { (k, v) -> HudChip(v, store.imagesReecriture.value == k) { store.imagesReecriture.set(k) } }
                }
                Text(when (store.imagesReecriture.value) {
                    "gemini" -> "Gemini pour les demandes classiques ; une demande adulte passe quand même par Pollinations (Gemini l'interdit)."
                    "pollinations" -> "Modèle de texte Pollinations « ${store.imagesModeleTexte.value} », puis enrichissement local s'il refuse."
                    "locale" -> "Sans IA : ta demande + mots de qualité du style + négatif adapté."
                    "aucune" -> "Ta demande est envoyée telle quelle (avec les ajouts de sécurité en mode adulte)."
                    else -> "Classique : Gemini. Adulte : modèle de texte Pollinations « ${store.imagesModeleTexte.value} », puis enrichissement local s'il refuse."
                }, color = HudDim, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                if (store.imagesReecriture.value in setOf("auto", "pollinations", "gemini")) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        HudTextField(store.imagesModeleTexte.value, { store.imagesModeleTexte.set(it.trim()) }, Modifier.weight(1f), label = "Modèle de texte Pollinations")
                        Spacer(Modifier.size(6.dp))
                        HudChip("Lister") {
                            StudioEtat.scope.launch {
                                modelesTexte = runCatching { withContext(Dispatchers.IO) { Pollinations.modelesTexte(store.pollinationsCle.value.trim()) } }
                                    .getOrElse { info = "⚠️ " + (it.message ?: "Liste indisponible"); emptyList() }
                            }
                        }
                    }
                    if (modelesTexte.isNotEmpty()) Rangee {
                        modelesTexte.forEach { m -> HudChip(m, store.imagesModeleTexte.value == m) { store.imagesModeleTexte.set(m) } }
                    }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HudButton(if (StudioEtat.enCours) "En cours…" else "Générer", filled = true, enabled = !StudioEtat.enCours && demande.isNotBlank()) {
                        edition = false
                        lancer(demande, StudioImages.Options(adulte, modele, formatChoisi(), store.imagesReecriture.value, store.imagesNombre.value))
                    }
                    if (StudioEtat.enCours) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = LocalAccent.current, strokeWidth = 2.dp)
                        HudChip("Annuler") { StudioEtat.job?.cancel() }
                    }
                }
                if (StudioEtat.progres.isNotBlank()) Text(StudioEtat.progres, color = HudDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                if (StudioEtat.erreur.isNotBlank()) Text(StudioEtat.erreur, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ── Dernier résultat ──
        StudioEtat.resultat?.let { r ->
            item {
                HudPanel(Modifier.fillMaxWidth(), title = "🖼️ Résultat · ${r.modele}") {
                    r.etapes.forEach { Text(it, color = HudDim, fontSize = 11.sp) }
                    Spacer(Modifier.height(6.dp))
                    r.images.chunked(2).forEach { ligne ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            ligne.forEach { im ->
                                Vignette(im, store.imagesFlou.value && im.adulte && im.fichier.name !in reveles, Modifier.weight(1f)) {
                                    if (store.imagesFlou.value && im.adulte && im.fichier.name !in reveles) reveles += im.fichier.name else ouverte = im
                                }
                            }
                            if (ligne.size == 1 && r.images.size > 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text("Prompt envoyé (${r.prompt.source}) :", color = HudText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    SelectionContainer { Text(r.prompt.positif, color = HudDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                    if (r.prompt.negatif.isNotBlank()) {
                        Text("À éviter :", color = HudText, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        SelectionContainer { Text(r.prompt.negatif, color = HudDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                    }
                    Rangee {
                        HudChip("✏️ Modifier le prompt") { edition = !edition; promptEdit = r.prompt.positif; negatifEdit = r.prompt.negatif }
                        HudChip("🔁 Autres variantes") {
                            lancer(r.images.firstOrNull()?.demande ?: demande, StudioImages.Options(r.explicite, r.modele, r.images.firstOrNull()?.let(::formatDe) ?: formatChoisi(),
                                "aucune", store.imagesNombre.value, r.prompt.positif, r.prompt.negatif))
                        }
                    }
                    if (edition) {
                        HudTextField(promptEdit, { promptEdit = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "Prompt (anglais conseillé)", singleLine = false, maxLines = 8)
                        HudTextField(negatifEdit, { negatifEdit = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "À éviter", singleLine = false, maxLines = 4)
                        HudButton("Régénérer avec ce prompt", Modifier.padding(top = 6.dp), filled = true, enabled = !StudioEtat.enCours && promptEdit.isNotBlank()) {
                            lancer(r.images.firstOrNull()?.demande ?: demande, StudioImages.Options(r.explicite, if (modele == "auto") r.modele else modele,
                                formatChoisi() ?: r.images.firstOrNull()?.let(::formatDe), "aucune", store.imagesNombre.value, promptEdit, negatifEdit))
                        }
                    }
                }
            }
        }

        // ── Galerie privée ──
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🗂️ GALERIE PRIVÉE (${galerie.size})", color = LocalAccent.current, fontSize = 11.sp, letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                if (galerie.isNotEmpty()) HudChip("Tout supprimer") { confirmerTout = true }
            }
            if (galerie.isEmpty()) Text("Aucune image pour l'instant.", color = HudDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        items(galerie.chunked(3), key = { l -> l.first().fichier.name }) { ligne ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ligne.forEach { im ->
                    val flou = store.imagesFlou.value && im.adulte && im.fichier.name !in reveles
                    Vignette(im, flou, Modifier.weight(1f), petite = true) { if (flou) reveles += im.fichier.name else ouverte = im }
                }
                repeat(3 - ligne.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    ouverte?.let { im ->
        Visionneuse(im, onFermer = { ouverte = null },
            onGalerie = { info = if (StudioImages.versGalerie(app, im)) "✅ Copiée dans Images › JARVIS Studio." else "⚠️ Copie impossible."; ouverte = null },
            onPartager = { runCatching { StudioImages.partager(app, im) } },
            onVarier = {
                ouverte = null
                lancer(im.demande, StudioImages.Options(im.adulte, im.modele, formatDe(im), "aucune", store.imagesNombre.value, im.prompt, im.negatif))
            },
            onReprendre = { demande = im.demande; adulte = im.adulte; ouverte = null },
            onSupprimer = {
                StudioImages.supprimer(im); ouverte = null; StudioEtat.version++
                StudioEtat.resultat = StudioEtat.resultat?.let { r -> r.copy(images = r.images.filter { it.fichier != im.fichier }) }
            })
    }
    if (confirmerTout) AlertDialog(onDismissRequest = { confirmerTout = false }, containerColor = Color(0xFF0B1424),
        titleContentColor = LocalAccent.current, textContentColor = HudText,
        title = { Text("Supprimer toute la galerie ?") },
        text = { Text("${galerie.size} image(s) seront effacées de l'application (les copies déjà enregistrées dans la galerie du téléphone restent).") },
        confirmButton = { TextButton({ StudioImages.toutSupprimer(app); StudioEtat.resultat = null; StudioEtat.version++; confirmerTout = false }) { Text("Supprimer", color = Color(0xFFFF8A80)) } },
        dismissButton = { TextButton({ confirmerTout = false }) { Text("Annuler", color = HudDim) } })
}

@Composable
private fun Etiquette(t: String) = Text(t, color = HudText, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Rangee(content: @Composable () -> Unit) =
    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }

@Composable
private fun Interrupteur(texte: String, valeur: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(texte, color = HudText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(valeur, onChange, colors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current))
    }
}

private fun decoder(f: File, maxPx: Int): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

@Composable
private fun ImageFichier(f: File, maxPx: Int, modifier: Modifier, flou: Boolean, echelle: ContentScale) {
    val bmp by produceState<ImageBitmap?>(null, f.path, maxPx) { value = withContext(Dispatchers.IO) { decoder(f, maxPx) } }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b == null) CircularProgressIndicator(Modifier.size(22.dp), color = LocalAccent.current, strokeWidth = 2.dp)
        else Image(b, null, Modifier.fillMaxSize().then(if (flou) Modifier.blur(32.dp) else Modifier), contentScale = echelle)
        if (flou) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (Build.VERSION.SDK_INT >= 31) 0.35f else 0.92f)), contentAlignment = Alignment.Center) {
            Text("👁 Toucher pour voir", color = HudText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Vignette(im: StudioImages.Image, flou: Boolean, modifier: Modifier, petite: Boolean = false, onClick: () -> Unit) {
    val ratio = if (im.largeur > 0 && im.hauteur > 0) im.largeur.toFloat() / im.hauteur else 1f
    ImageFichier(im.fichier, if (petite) 360 else 720,
        modifier.aspectRatio(if (petite) 1f else ratio).clip(HudShapeSmall).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClick),
        flou, ContentScale.Crop)
}

@Composable
private fun Visionneuse(
    im: StudioImages.Image, onFermer: () -> Unit, onGalerie: () -> Unit, onPartager: () -> Unit,
    onVarier: () -> Unit, onReprendre: () -> Unit, onSupprimer: () -> Unit
) {
    Dialog(onDismissRequest = onFermer, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f)).padding(12.dp)) {
            ImageFichier(im.fichier, 2048, Modifier.fillMaxWidth().weight(1f).clickable(onClick = onFermer), false, ContentScale.Fit)
            Column(Modifier.fillMaxWidth().height(170.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                Text("${im.modele} · ${im.largeur}×${im.hauteur} · graine ${im.seed}" + if (im.adulte) " · 🔞" else "", color = HudText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                if (im.demande.isNotBlank()) Text("Demande : " + im.demande, color = HudDim, fontSize = 11.sp)
                SelectionContainer { Text(im.prompt, color = HudDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudChip("💾 Galerie", onClick = onGalerie)
                HudChip("📤 Partager", onClick = onPartager)
                HudChip("🔁 Variantes", onClick = onVarier)
                HudChip("↩️ Reprendre la demande", onClick = onReprendre)
                HudChip("🗑️ Supprimer", onClick = onSupprimer)
                HudChip("✖ Fermer", onClick = onFermer)
            }
        }
    }
}

@Composable
private fun Verrou(ctx: Context, onOk: () -> Unit) {
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { authentifier(ctx, onOk) { message = it } }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🔒", fontSize = 48.sp)
        Text("Onglet Images verrouillé", color = HudText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        if (message.isNotBlank()) Text(message, color = HudDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        HudButton("Déverrouiller", Modifier.padding(top = 16.dp), filled = true) { authentifier(ctx, onOk) { message = it } }
    }
}

/** Empreinte / visage / code du téléphone (BiometricPrompt du système, sans bibliothèque). */
private fun authentifier(ctx: Context, ok: () -> Unit, ko: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < 28) { ok(); return }
    val km = ctx.getSystemService(KeyguardManager::class.java)
    if (km?.isDeviceSecure != true) { ko("Aucun verrouillage d'écran n'est configuré sur le téléphone : impossible de verrouiller l'onglet."); return }
    val b = BiometricPrompt.Builder(ctx).setTitle("Onglet Images").setSubtitle("Déverrouille pour continuer")
    if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
    runCatching {
        b.build().authenticate(CancellationSignal(), ctx.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = ok()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = ko(errString?.toString() ?: "Déverrouillage annulé.")
        })
    }.onFailure { ko(it.message ?: "Déverrouillage indisponible.") }
}
