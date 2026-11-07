package fr.dd.jarvis.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Vision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

// ======================================================================================= outils communs (photo, signature)

/** Fichier temporaire (partagé avec l'appareil photo par le FileProvider de l'appli). */
private fun nouvellePhoto(ctx: Context, prefixe: String): Pair<File, Uri> {
    val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "${prefixe}_${System.currentTimeMillis()}.jpg")
    return f to androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
}

/** Photo réduite (1600 px max, JPEG 85 %) : envoi plus rapide, lecture des petites inscriptions préservée. */
internal fun jpegReduit(ctx: Context, uri: Uri, max: Int = 1600): ByteArray? = runCatching {
    val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bornes) }
    var ech = 1
    while (bornes.outWidth / (ech * 2) >= max || bornes.outHeight / (ech * 2) >= max) ech *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ech }) } ?: return null
    val r = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
    val fin = if (r < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt(), (bmp.height * r).toInt(), true) else bmp
    java.io.ByteArrayOutputStream().use { o -> fin.compress(Bitmap.CompressFormat.JPEG, 85, o); o.toByteArray() }
}.getOrNull()

/** Lanceur d'appareil photo : appelle [surPhoto] avec l'adresse de la photo prise. */
@Composable
private fun appareilPhoto(prefixe: String, surPhoto: (Uri) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var attendu by remember { mutableStateOf<Uri?>(null) }
    val lanceur = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> attendu?.let { if (ok) surPhoto(it) }; attendu = null }
    return {
        val (_, uri) = nouvellePhoto(ctx, prefixe)
        attendu = uri
        runCatching { lanceur.launch(uri) }
    }
}

@Composable
private fun EnteteEcran(titre: String, onFermer: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(titre, color = LocalAccent.current, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
        HudButton("Fermer", onClick = onFermer)
    }
}

@Composable
private fun Vignettes(photos: List<Uri>) {
    val ctx = LocalContext.current
    if (photos.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        photos.forEach { u ->
            val bmp = remember(u) { runCatching { ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 }) } }.getOrNull() }
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        }
    }
}

/** Choix d'une installation : les plus proches de la position actuelle d'abord, filtre par nom. */
@Composable
private fun ChoixInstallation(app: JarvisApp, choisi: Vision.SiteProche?, onChoix: (Vision.SiteProche) -> Unit) {
    var filtre by remember { mutableStateOf("") }
    val tous = remember { Vision.sitesProches(app) }
    val liste = tous.filter { filtre.isBlank() || fr.dd.jarvis.societe.core.normaliser(it.client.nom + " " + it.site.nom).contains(fr.dd.jarvis.societe.core.normaliser(filtre)) }.take(8)
    if (tous.isEmpty()) { Text("Aucune installation enregistrée (onglet Société).", color = HudDim, fontSize = 12.sp); return }
    HudTextField(filtre, { filtre = it }, Modifier.fillMaxWidth(), label = "Client ou installation")
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        liste.forEach { s ->
            val libelle = s.client.nom + (if (s.client.sites.size > 1) " · ${s.site.nom}" else "") +
                (s.km?.let { if (it < 1) " · ${(it * 1000).toInt()} m" else " · ${"%.0f".format(it)} km" } ?: "")
            HudChip((if (s.km != null && s.km < 0.5) "📍 " else "") + libelle, choisi?.site?.id == s.site.id) { onChoix(s) }
        }
    }
}

// ======================================================================================= 3. Regarder (caméra)

/**
 * Mode caméra : photo d'une plaque, d'un code erreur, d'un document, ou question libre ; Jarvis lit et propose la
 * suite (enregistrer sur l'installation, expliquer la panne, classer le document).
 */
@Composable
fun RegarderEcran(app: JarvisApp, onFermer: () -> Unit, onDemander: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Vision.Mode.EQUIPEMENT) }
    var question by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var octets by remember { mutableStateOf<ByteArray?>(null) }
    var resultat by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    var installation by remember { mutableStateOf<Vision.SiteProche?>(null) }

    fun analyser() {
        val b = octets ?: return
        occupe = true; resultat = null; message = "🔎 Jarvis regarde…"
        scope.launch {
            runCatching { Vision.analyser(app, mode, b, question) }
                .onSuccess { resultat = it; message = "" }
                .onFailure { message = "❌ Lecture impossible : ${it.message}" }
            occupe = false
        }
    }
    val prendre = appareilPhoto("regarder") { u ->
        photo = u
        scope.launch { octets = withContext(Dispatchers.IO) { jpegReduit(ctx, u) }; analyser() }
    }
    val galerie = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { photo = u; scope.launch { octets = withContext(Dispatchers.IO) { jpegReduit(ctx, u) }; analyser() } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        EnteteEcran("📷 REGARDER", onFermer)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Vision.Mode.values().forEach { m -> HudChip("${m.icone} ${m.libelle}", mode == m) { mode = m; resultat = null; if (octets != null && m != Vision.Mode.QUESTION) analyser() } }
        }
        Text(when (mode) {
            Vision.Mode.EQUIPEMENT -> "Photographie la plaque signalétique (onduleur, panneau, compteur, batterie…) : je relève marque, modèle, n° de série et caractéristiques."
            Vision.Mode.CODE -> "Photographie l'écran ou les voyants de l'appareil en défaut : je lis le code, puis je t'explique la panne et la procédure."
            Vision.Mode.DOCUMENT -> "Photographie une facture, un contrat ou un courrier : je le lis, je le classe dans ton coffre et j'ajoute les factures fournisseurs à tes achats."
            Vision.Mode.QUESTION -> "Pose ta question, puis prends la photo."
        }, color = HudDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        if (mode == Vision.Mode.QUESTION) HudTextField(question, { question = it }, Modifier.fillMaxWidth(), label = "Ta question sur la photo")
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("📷 Photo", filled = true, enabled = !occupe) { prendre() }
            HudButton("🖼️ Galerie", enabled = !occupe) { galerie.launch("image/*") }
            if (octets != null) HudButton("↻ Relire", enabled = !occupe) { analyser() }
        }
        photo?.let { Vignettes(listOf(it)) }
        if (message.isNotBlank()) Text(message, color = HudText, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))

        val r = resultat
        if (r != null) {
            Spacer(Modifier.height(10.dp))
            when (mode) {
                Vision.Mode.EQUIPEMENT -> {
                    HudPanel(Modifier.fillMaxWidth(), title = "Équipement relevé") {
                        Text(Vision.texteEquipement(r), color = HudText, fontSize = 14.sp)
                        if (r.optString("resume").isNotBlank()) Text(r.optString("resume"), color = HudDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Enregistrer sur l'installation :", color = HudDim, fontSize = 12.sp)
                    ChoixInstallation(app, installation) { installation = it }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudButton("💾 Enregistrer", filled = true, enabled = installation != null && !occupe) {
                            val s = installation ?: return@HudButton
                            occupe = true
                            scope.launch {
                                message = withContext(Dispatchers.IO) { runCatching { Vision.enregistrerEquipement(app, s.client.id, s.site.id, r, octets ?: ByteArray(0)) }.getOrElse { "❌ ${it.message}" } }
                                occupe = false
                            }
                        }
                        HudButton("🪽 Demander à Jarvis") { onDemander("J'ai relevé cette plaque : ${Vision.texteEquipement(r).replace("\n", " · ")}. Que peux-tu m'en dire (caractéristiques, points de vigilance, compatibilité) ?") }
                    }
                }
                Vision.Mode.CODE -> {
                    HudPanel(Modifier.fillMaxWidth(), title = "Code relevé") { Text(Vision.texteCode(r), color = HudText, fontSize = 14.sp) }
                    Spacer(Modifier.height(8.dp))
                    HudButton("🔧 Explication et procédure", Modifier.fillMaxWidth(), filled = true) {
                        onDemander("Panne relevée sur le terrain : ${Vision.texteCode(r)}. Explique la signification de ce code, les causes probables et la procédure de dépannage pas à pas, en commençant par les consignes de sécurité (consignation DC/AC). Cherche la documentation du fabricant si besoin.")
                    }
                }
                Vision.Mode.DOCUMENT -> {
                    HudPanel(Modifier.fillMaxWidth(), title = "Document lu") {
                        Text(Vision.texteDocument(r), color = HudText, fontSize = 14.sp)
                        if (r.optString("resume").isNotBlank()) Text(r.optString("resume"), color = HudDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudButton("🗂️ Classer dans le coffre", filled = true, enabled = !occupe) {
                            occupe = true
                            scope.launch {
                                message = withContext(Dispatchers.IO) { runCatching { Vision.classerDocument(app, r, octets ?: ByteArray(0)) }.getOrElse { "❌ ${it.message}" } }
                                occupe = false
                            }
                        }
                        HudButton("🪽 Demander à Jarvis") { onDemander("Voici un document que je viens de scanner : ${Vision.texteDocument(r).replace("\n", " · ")}. Résumé : ${r.optString("resume")}. Qu'est-ce que je dois en faire (échéance, paiement, action) ?") }
                    }
                }
                Vision.Mode.QUESTION -> HudPanel(Modifier.fillMaxWidth(), title = "Réponse") { Text(r.optString("reponse"), color = HudText, fontSize = 14.sp) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ======================================================================================= 4. Compte-rendu d'intervention

/** Trait de signature (points successifs). */
private class Trait(val points: MutableList<Offset> = mutableListOf())

/** Zone de signature du client. */
@Composable
private fun ZoneSignature(traits: MutableList<Trait>, version: Int, onTaille: (androidx.compose.ui.unit.IntSize) -> Unit, onChange: () -> Unit) {
    val accent = LocalAccent.current
    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF4F1FA)).border(1.dp, accent, RoundedCornerShape(10.dp))
        .onSizeChanged(onTaille)
        .pointerInput(Unit) {
            detectDragGestures(onDragStart = { traits.add(Trait(mutableListOf(it))); onChange() }) { change, _ ->
                traits.lastOrNull()?.points?.add(change.position); onChange()
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            version.let { }
            traits.forEach { t -> for (i in 1 until t.points.size) drawLine(Color(0xFF1A1030), t.points[i - 1], t.points[i], strokeWidth = 4f, cap = StrokeCap.Round) }
        }
        if (traits.isEmpty()) Text("Signature du client", color = Color(0xFF8A80A0), fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
    }
}

/** Signature dessinée → PNG (fond blanc). */
private fun signaturePng(traits: List<Trait>, largeur: Int, hauteur: Int): ByteArray? {
    if (traits.none { it.points.size > 1 }) return null
    val bmp = Bitmap.createBitmap(largeur.coerceAtLeast(200), hauteur.coerceAtLeast(100), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp); c.drawColor(android.graphics.Color.WHITE)
    val p = android.graphics.Paint().apply { color = android.graphics.Color.rgb(26, 16, 48); strokeWidth = 5f; isAntiAlias = true; strokeCap = android.graphics.Paint.Cap.ROUND }
    traits.forEach { t -> for (i in 1 until t.points.size) c.drawLine(t.points[i - 1].x, t.points[i - 1].y, t.points[i].x, t.points[i].y, p) }
    return java.io.ByteArrayOutputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o); o.toByteArray() }
}

/**
 * Compte-rendu dicté sur place : installation (la plus proche d'abord), ce qui a été fait (dictée ou texte), photos
 * avant/après, signature du client. Jarvis structure le rapport, l'enregistre dans l'historique de l'installation (et
 * dans Obsidian), joint photos et signature, puis propose de créer la facture et de l'envoyer.
 */
@Composable
fun CompteRenduEcran(app: JarvisApp, onFermer: () -> Unit, onDemander: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var installation by remember { mutableStateOf<Vision.SiteProche?>(null) }
    var categorie by remember { mutableStateOf("maintenance") }
    var dictee by remember { mutableStateOf("") }
    val avant = remember { mutableStateListOf<Uri>() }
    val apres = remember { mutableStateListOf<Uri>() }
    val traits = remember { mutableStateListOf<Trait>() }
    var version by remember { mutableStateOf(0) }
    var tailleSig by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(900, 300)) }
    var message by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    var fini by remember { mutableStateOf<Pair<String, String>?>(null) } // (date, description)
    // Ouvert sur place (mode chantier) : l'installation la plus proche est déjà choisie.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (installation == null) installation = withContext(Dispatchers.IO) { runCatching { Vision.sitesProches(app).firstOrNull { (it.km ?: 99.0) < 0.25 } }.getOrNull() }
    }

    val photoAvant = appareilPhoto("avant") { avant.add(it) }
    val photoApres = appareilPhoto("apres") { apres.add(it) }
    val micro = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (t.isNotBlank()) dictee = (dictee.trimEnd() + (if (dictee.isBlank()) "" else " ") + t.trim()).trim()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        EnteteEcran("📝 COMPTE-RENDU", onFermer)
        Text("Installation", color = HudDim, fontSize = 12.sp)
        ChoixInstallation(app, installation) { installation = it }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("maintenance", "panne", "diagnostic", "nettoyage", "remplacement", "contrôle").forEach { c -> HudChip(c.replaceFirstChar { it.uppercase() }, categorie == c) { categorie = c } }
        }
        Spacer(Modifier.height(10.dp))
        HudTextField(dictee, { dictee = it }, Modifier.fillMaxWidth(), label = "Ce que tu as constaté et fait (mesures, pièces, suite à donner)", singleLine = false, maxLines = 10)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("🎙️ Dicter", filled = true) {
                runCatching {
                    micro.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Décris l'intervention"))
                }
            }
            if (dictee.isNotBlank()) HudButton("Effacer") { dictee = "" }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("📷 Avant (${avant.size})") { photoAvant() }
            HudButton("📷 Après (${apres.size})") { photoApres() }
        }
        Vignettes(avant + apres)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Signature du client (optionnelle)", color = HudDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (traits.isNotEmpty()) HudChip("Effacer") { traits.clear(); version++ }
        }
        ZoneSignature(traits, version, { tailleSig = it }) { version++ }
        Spacer(Modifier.height(12.dp))
        HudButton(if (occupe) "Création…" else "✅ Créer le rapport", Modifier.fillMaxWidth(), filled = true, enabled = !occupe && installation != null && dictee.isNotBlank()) {
            val s = installation ?: return@HudButton
            occupe = true; message = "🪽 Je rédige le rapport…"
            val signature = signaturePng(traits.toList(), tailleSig.width, tailleSig.height)
            scope.launch {
                val r = runCatching {
                    fr.dd.jarvis.ai.CompteRendu.creer(app, s.client.id, s.site.id, categorie, dictee, avant.toList(), apres.toList(), signature)
                }.getOrElse { fr.dd.jarvis.ai.CompteRendu.Resultat("❌ ${it.message}", null, null) }
                message = r.texte
                if (r.date != null && r.description != null) fini = r.date to r.description
                occupe = false
            }
        }
        if (message.isNotBlank()) Text(message, color = HudText, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        val f = fini
        val s = installation
        if (f != null && s != null) {
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("📄 Créer la facture", filled = true) {
                    onDemander("Crée la facture de l'intervention du ${f.first} chez ${s.client.nom} (installation ${s.site.nom}) : ${f.second}. Applique mes tarifs habituels et les frais de déplacement, puis montre-la-moi avant envoi.")
                }
                HudButton("✉️ Envoyer le rapport") {
                    onDemander("Envoie par mail à ${s.client.nom} le rapport de l'intervention du ${f.first} sur l'installation ${s.site.nom} (PDF), avec un message court et professionnel.")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ======================================================================================= 6. Santé de Jarvis

/** Tableau de santé : chaque système, son état, et quoi faire avant que ça casse. */
@Composable
fun SanteEcran(app: JarvisApp, onFermer: () -> Unit, onDemander: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var liste by remember { mutableStateOf<List<fr.dd.jarvis.device.Sante.Indicateur>?>(null) }
    var tour by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(tour) { liste = null; liste = fr.dd.jarvis.device.Sante.mesurer(app) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        EnteteEcran("🩺 SANTÉ", onFermer)
        val l = liste
        if (l == null) Text("Vérification en cours…", color = HudDim, fontSize = 13.sp)
        else {
            Text(fr.dd.jarvis.device.Sante.resume(l), color = HudText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            l.sortedBy { when (it.niveau) { fr.dd.jarvis.device.Sante.Niveau.PANNE -> 0; fr.dd.jarvis.device.Sante.Niveau.ATTENTION -> 1; fr.dd.jarvis.device.Sante.Niveau.OK -> 2; else -> 3 } }.forEach { i ->
                val couleur = when (i.niveau) {
                    fr.dd.jarvis.device.Sante.Niveau.OK -> Color(0xFF4CE0A0); fr.dd.jarvis.device.Sante.Niveau.ATTENTION -> Color(0xFFFFB74D)
                    fr.dd.jarvis.device.Sante.Niveau.PANNE -> Color(0xFFFF5370); else -> HudDim
                }
                HudPanel(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(i.icone, fontSize = 16.sp)
                        Text(i.nom, color = HudText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(couleur))
                    }
                    Text(i.detail, color = HudDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    if (i.conseil.isNotBlank()) Text("→ ${i.conseil}", color = HudText, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    if (i.cle == "hermes" && fr.dd.jarvis.hermes.Hermes.degrade()) HudChip("Réessayer Hermès maintenant") { fr.dd.jarvis.hermes.Hermes.reprendre(); tour++ }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("↻ Actualiser") { tour++ }
                HudButton("🪽 Demander à Jarvis") {
                    onDemander("Voici l'état de mes systèmes : " + l.filter { it.niveau != fr.dd.jarvis.device.Sante.Niveau.INACTIF }.joinToString(" ; ") { "${it.nom} : ${it.detail}" } +
                        ". Que faut-il corriger en priorité ?")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
