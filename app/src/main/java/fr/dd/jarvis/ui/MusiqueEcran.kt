package fr.dd.jarvis.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.MusiqueBibliotheque
import fr.dd.jarvis.device.YtMusique
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val vignettes = LruCache<String, Bitmap>(120)

/** Image d'un résultat (pochette), chargée en arrière-plan et gardée en mémoire. */
@Composable
private fun Pochette(url: String?, taille: Dp, bitmap: Bitmap? = null) {
    val image by produceState<Bitmap?>(bitmap ?: url?.let { vignettes.get(it) }, url, bitmap) {
        if (bitmap != null || url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                fr.dd.jarvis.ai.Http.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r -> r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            }.getOrNull()?.also { vignettes.put(url, it) }
        }
    }
    Box(Modifier.size(taille).clip(HudShapeSmall).background(LocalAccent.current.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.size(taille), contentScale = ContentScale.Crop) } ?: Text("🎵", fontSize = (taille.value * 0.4f).sp)
    }
}

/**
 * Écran Musique (Morphe / YouTube Music) : lecteur en cours avec ses commandes (lecture, suivant, j'aime, aléatoire,
 * répéter, radio), recherche dans le catalogue avec filtres (titres, albums, artistes, playlists, vidéos) et choix du
 * résultat, bibliothèque personnelle (J'aime, playlists, titres, albums, artistes, téléchargements) et file d'attente.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MusiqueEcran(app: JarvisApp, onFermer: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val paquet = remember { YtMusique.paquet(app, app.store.appPrefs["musique"]) }
    val nomAppli = if (paquet == fr.dd.jarvis.device.Musique.MORPHE_PACKAGE) "Morphe" else "YouTube Music"
    var enCours by remember { mutableStateOf<MusiqueBibliotheque.EnCours?>(null) }
    var message by remember { mutableStateOf("") }
    var requete by remember { mutableStateOf("") }
    var filtre by remember { mutableStateOf("tout") }
    var resultats by remember { mutableStateOf<List<YtMusique.Resultat>>(emptyList()) }
    var recherche by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf<String?>(null) }
    var biblio by remember { mutableStateOf<List<MusiqueBibliotheque.Element>>(emptyList()) }
    var voirFile by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            enCours = withContext(Dispatchers.IO) { runCatching { MusiqueBibliotheque.enCours(app) }.getOrNull() }
            delay(1500)
        }
    }
    fun revenir() = runCatching {
        ctx.startActivity(Intent(ctx, fr.dd.jarvis.MainActivity::class.java).putExtra("terrain", "musique")
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun lancer(r: YtMusique.Resultat, radio: Boolean = false) {
        val p = paquet ?: run { message = "Morphe / YouTube Music n'est pas installée."; return }
        message = "⏳ Lancement de « ${r.titre} »…"
        scope.launch {
            message = withContext(Dispatchers.IO) { app.phone.jouerYtm(p, r.titre, nomAppli, r, radio) ?: "❌ Impossible d'ouvrir ce résultat." }.substringBefore("\nAutres résultats")
            revenir()
        }
    }
    fun chercher() {
        if (requete.isBlank()) return
        recherche = true; message = ""
        scope.launch {
            resultats = withContext(Dispatchers.IO) { runCatching { YtMusique.rechercher(requete, filtre) }.getOrElse { message = "❌ Recherche impossible : ${it.message}"; emptyList() } }
                .filter { it.type != YtMusique.Type.AUTRE }
            YtMusique.retenir(resultats)
            if (resultats.isEmpty() && message.isBlank()) message = "Aucun résultat."
            recherche = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🎵 MUSIQUE · $nomAppli", color = LocalAccent.current, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            HudButton("Fermer", onClick = onFermer)
        }
        if (paquet == null) { Text("Installe Morphe (ou YouTube Music) pour utiliser cet écran.", color = HudText); return@Column }

        // ---- Lecteur en cours
        HudPanel(Modifier.fillMaxWidth(), title = "En cours") {
            val e = enCours
            if (e == null || e.titre.isBlank()) Text("Rien ne joue pour l'instant.", color = HudDim, fontSize = 13.sp)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Pochette(null, 64.dp, e.image)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.titre, color = HudText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(e.artiste, e.album).filter { it.isNotBlank() }.joinToString(" · "), color = HudDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.app + if (e.lecture) " · en lecture" else " · en pause", color = HudDim, fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudButton("⏮", Modifier.weight(1f)) { message = fr.dd.jarvis.device.Musique.commander(app, "precedent") }
                HudButton(if (e?.lecture == true) "⏸" else "▶️", Modifier.weight(1f)) { message = fr.dd.jarvis.device.Musique.commander(app, if (e?.lecture == true) "pause" else "lecture") }
                HudButton("⏭", Modifier.weight(1f)) { message = fr.dd.jarvis.device.Musique.commander(app, "suivant") }
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HudChip("👍 J'aime") { message = MusiqueBibliotheque.action(app, "jaime") }
                HudChip("👎") { message = MusiqueBibliotheque.action(app, "jaimepas") }
                HudChip("🔀 Aléatoire") { message = MusiqueBibliotheque.action(app, "aleatoire") }
                HudChip("🔁 Répéter") { message = MusiqueBibliotheque.action(app, "repeter") }
                HudChip("📻 Radio") {
                    val x = enCours ?: return@HudChip
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            YtMusique.rechercher("${x.titre} ${x.artiste}", "titres").firstOrNull()?.let { r -> app.phone.jouerYtm(paquet, r.titre, nomAppli, r, radio = true) } ?: "Radio introuvable."
                        }.substringBefore("\nAutres résultats")
                        revenir()
                    }
                }
                if (e != null && e.file.isNotEmpty()) HudChip(if (voirFile) "📜 Masquer la file" else "📜 File (${e.file.size})", voirFile) { voirFile = !voirFile }
            }
            if (voirFile) e?.file?.forEachIndexed { i, (id, t) ->
                Text("${i + 1}. $t", color = HudText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { MusiqueBibliotheque.allerA(app, id) }.padding(vertical = 5.dp))
            }
        }
        if (message.isNotBlank()) Text(message, color = HudText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))

        // ---- Recherche dans le catalogue
        Spacer(Modifier.height(10.dp))
        HudPanel(Modifier.fillMaxWidth(), title = "Rechercher") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudTextField(requete, { requete = it }, Modifier.weight(1f), placeholder = "Titre, artiste, album…", singleLine = true)
                HudButton(if (recherche) "…" else "🔎", enabled = !recherche) { chercher() }
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("tout" to "Tout", "titres" to "Titres", "albums" to "Albums", "artistes" to "Artistes", "playlists" to "Playlists", "videos" to "Vidéos").forEach { (id, nom) ->
                    HudChip(nom, filtre == id) { filtre = id; if (requete.isNotBlank()) chercher() }
                }
            }
            resultats.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { lancer(r) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pochette(r.image, 48.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.titre, color = HudText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${r.type.icone} ${r.type.libelle}" + (if (r.sousTitre.isNotBlank()) " · ${r.sousTitre}" else ""), color = HudDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (r.type == YtMusique.Type.TITRE || r.type == YtMusique.Type.VIDEO) HudChip("📻") { lancer(r, radio = true) }
                    HudChip("▶️") { lancer(r) }
                }
            }
        }

        // ---- Bibliothèque personnelle
        Spacer(Modifier.height(10.dp))
        HudPanel(Modifier.fillMaxWidth(), title = "Ma bibliothèque") {
            Text("Lue dans $nomAppli (ton compte) : l'appli s'ouvre un instant puis JARVIS revient.", color = HudDim, fontSize = 11.sp)
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HudChip("❤️ J'aime") {
                    scope.launch {
                        message = if (withContext(Dispatchers.IO) { YtMusique.ouvrirLien(app, paquet, "https://music.youtube.com/watch?list=LM") }) "❤️ Lecture de tes titres J'aime." else "❌ Impossible d'ouvrir tes J'aime."
                        delay(2500); revenir()
                    }
                }
                listOf("playlists" to "📃 Playlists", "titres" to "🎵 Titres", "albums" to "💿 Albums", "artistes" to "🎤 Artistes", "telechargements" to "⬇️ Téléchargés").forEach { (id, nom) ->
                    HudChip(nom, section == id) {
                        section = id; biblio = emptyList(); message = "⏳ Lecture de ta bibliothèque ($nom)…"
                        scope.launch {
                            val (l, err) = withContext(Dispatchers.IO) { MusiqueBibliotheque.lister(app, paquet, id) }
                            biblio = l; message = err ?: "${l.size} élément(s)."
                            revenir()
                        }
                    }
                }
            }
            biblio.forEach { el ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable {
                        val s = section ?: return@clickable
                        message = "⏳ Lancement de « ${el.titre} »…"
                        scope.launch { message = withContext(Dispatchers.IO) { MusiqueBibliotheque.jouer(app, paquet, s, el.titre) }; delay(1500); revenir() }
                    }) {
                        Text(el.titre, color = HudText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (el.sousTitre.isNotBlank()) Text(el.sousTitre, color = HudDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (section != "titres") HudChip("🔀") {
                        val s = section ?: return@HudChip
                        scope.launch { message = withContext(Dispatchers.IO) { MusiqueBibliotheque.jouer(app, paquet, s, el.titre, aleatoire = true) }; delay(1500); revenir() }
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}
