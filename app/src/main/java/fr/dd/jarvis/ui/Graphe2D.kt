package fr.dd.jarvis.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.ObsidianWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Filtres de l'image de référence : chacun masque/affiche une couche réelle du graphe. */
enum class FiltreGraphe(val libelle: String, val icone: Icone) {
    DOSSIERS("Dossiers", Icone.DOSSIER), TAGS("Tags", Icone.ETIQUETTE), PIECES("Pièces jointes", Icone.TROMBONE),
    ENTRANTS("Liens entrants", Icone.NOTE), SORTANTS("Liens sortants", Icone.NOTE)
}

/** Charge le coffre réel et construit la constellation (partagé par la vue 2D et l'Orbe 3D). */
class ChargementConstellation {
    var constellation by mutableStateOf<Constellation?>(null)
    var erreur by mutableStateOf<String?>(null)
    var version by mutableStateOf(0)
}

@Composable
fun rememberConstellation(app: JarvisApp): ChargementConstellation {
    val etat = remember { ChargementConstellation() }
    LaunchedEffect(etat.version) {
        etat.erreur = null
        val r = withContext(Dispatchers.IO) { runCatching { ConstellationLayout.construire(app.obsidian.graph()) } }
        r.onSuccess { etat.constellation = it }.onFailure { etat.erreur = it.message ?: it.javaClass.simpleName }
    }
    return etat
}

@Composable
fun ChargementEnCours(erreur: String?, enTete: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF05020B))) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            if (erreur == null) CircularProgressIndicator(color = Neon.violet)
            Text(erreur?.let { "Lecture du coffre impossible : $it" } ?: "Lecture du coffre…", color = Neon.sousTitre, fontSize = 12.sp, fontFamily = Neon.serif, modifier = Modifier.padding(top = 10.dp))
        }
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { enTete() }
    }
}

/** Fiche d'une note (aperçu + liens entrants/sortants réels) et liste complète d'un dossier. */
@Composable
fun DialoguesConstellation(
    app: JarvisApp, c: Constellation?,
    noteOuverte: String?, onNote: (String?) -> Unit,
    hubListe: String?, onHubListe: (String?) -> Unit
) {
    var apercu by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(noteOuverte) {
        apercu = null
        val id = noteOuverte ?: return@LaunchedEffect
        apercu = withContext(Dispatchers.IO) { runCatching { app.obsidian.readNote(id) }.getOrNull()?.take(3000) ?: "" }
    }
    noteOuverte?.let { id ->
        val noeud = c?.notesParHub?.values?.flatten()?.firstOrNull { it.id == id }
        val entrants = c?.tousLiens?.filter { it.vers == id }?.map { it.de.substringAfterLast('/') }.orEmpty()
        val sortants = c?.tousLiens?.filter { it.de == id }?.map { it.vers.substringAfterLast('/') }.orEmpty()
        val tags = c?.tagsParNote?.get(id).orEmpty()
        AlertDialog(
            onDismissRequest = { onNote(null) },
            containerColor = Color(0xFF0E0819), titleContentColor = Neon.lavande, textContentColor = Neon.blancViolet,
            title = { Text(noeud?.label ?: id.substringAfterLast('/'), fontFamily = Neon.serif) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(noeud?.folder?.ifBlank { "Racine du coffre" } ?: "", color = Neon.violet, fontSize = 11.sp, fontFamily = Neon.serif)
                    if (tags.isNotEmpty()) Text(tags.joinToString("  ") { "#$it" }, color = Neon.sousTitre, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("→ Liens entrants (${entrants.size}) : ${entrants.joinToString().ifBlank { "aucun" }}", color = Neon.sousTitre, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    Text("← Liens sortants (${sortants.size}) : ${sortants.joinToString().ifBlank { "aucun" }}", color = Neon.sousTitre, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                    if (apercu == null) CircularProgressIndicator(color = Neon.violet, modifier = Modifier.size(22.dp))
                    else Text(apercu!!.ifBlank { "(note vide ou illisible)" }, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton({ onNote(null) }) { Text("Fermer", color = Neon.violet) } }
        )
    }
    hubListe?.let { cle ->
        val notes = c?.notesParHub?.get(cle).orEmpty().sortedBy { it.label.lowercase() }
        AlertDialog(
            onDismissRequest = { onHubListe(null) },
            containerColor = Color(0xFF0E0819), titleContentColor = Neon.lavande, textContentColor = Neon.blancViolet,
            title = { Text("${if (cle == "Racine") "Coffre" else cle} · ${notes.size} note(s)", fontFamily = Neon.serif) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    notes.forEach { n ->
                        Text("▸ ${n.label}", fontSize = 13.sp, fontFamily = Neon.serif,
                            modifier = Modifier.fillMaxWidth().clickable { onHubListe(null); onNote(n.id) }.padding(vertical = 6.dp))
                    }
                }
            },
            confirmButton = { TextButton({ onHubListe(null) }) { Text("Fermer", color = Neon.violet) } }
        )
    }
}

/** Écran 2D « Cerveau — Graphe Obsidian » : charge le coffre réel, puis affiche [ConstellationVue]. */
@Composable
fun GrapheCerveau2D(app: JarvisApp, enTete: @Composable () -> Unit = {}) {
    val etat = rememberConstellation(app)
    var noteOuverte by remember { mutableStateOf<String?>(null) }
    var hubListe by remember { mutableStateOf<String?>(null) }
    val c = etat.constellation
    if (c == null) ChargementEnCours(etat.erreur, enTete)
    else ConstellationVue(
        c = c, enTete = enTete,
        onNote = { noteOuverte = it },
        onHub = { cle -> if (c.hub(cle)?.type == TypeHub.DOSSIER) hubListe = cle },
        onRecharger = { etat.version++ }
    )
    DialoguesConstellation(app, c, noteOuverte, { noteOuverte = it }, hubListe, { hubListe = it })
}

/**
 * Vue de la constellation (sans accès aux fichiers : uniquement les données reçues), dessinée comme l'image de
 * référence. Gestes : glisser pour déplacer, pincer pour zoomer, toucher un hub/une étiquette.
 */
@Composable
fun ConstellationVue(
    c: Constellation,
    modifier: Modifier = Modifier,
    enTete: @Composable () -> Unit = {},
    onNote: (String) -> Unit = {},
    onHub: (String) -> Unit = {},
    onRecharger: () -> Unit = {},
    animer: Boolean = true
) {
    val mesureur = rememberTextMeasurer(cacheSize = 160)
    val decor = remember { DecorCosmique() }
    val fontScale = LocalDensity.current.fontScale
    var recherche by remember { mutableStateOf("") }
    var filtres by remember { mutableStateOf(FiltreGraphe.values().toSet()) }
    var hubActif by remember { mutableStateOf<String?>(null) }
    var tagActif by remember { mutableStateOf<String?>(null) }
    var noteActive by remember { mutableStateOf<String?>(null) }
    var filtresOuverts by remember { mutableStateOf(true) }
    var mentionsOuvert by remember { mutableStateOf(false) }
    var orphelinsOuvert by remember { mutableStateOf(false) }
    var legendeOuverte by remember { mutableStateOf(false) }
    // Zoom et déplacement de l'utilisateur, relatifs au cadrage automatique (qui fait tenir tout le graphe).
    var zoomUser by remember(c) { mutableStateOf(1f) }
    var panUser by remember(c) { mutableStateOf(Offset.Zero) }

    val pulse = if (animer) {
        val t by rememberInfiniteTransition(label = "cerveau").animateFloat(
            0.78f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "pulse"
        )
        t
    } else 1f

    val requete = recherche.trim().lowercase()
    val toutesNotes = remember(c) { c.notesParHub.values.flatten() }
    val correspondances = remember(c, requete) {
        if (requete.isBlank()) emptyList() else toutesNotes.filter { it.label.lowercase().contains(requete) || it.folder.lowercase().contains(requete) }
    }
    val idsCorrespondants = remember(correspondances) { correspondances.map { it.id }.toHashSet() }
    val notesTag = remember(c, tagActif) { tagActif?.let { t -> c.tagsParNote.filterValues { t in it }.keys } ?: emptySet() }
    val voisinsActifs = remember(c, noteActive) {
        noteActive?.let { n -> c.tousLiens.filter { it.de == n || it.vers == n }.flatMap { listOf(it.de, it.vers) }.toSet() + n } ?: emptySet()
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color(0xFF05020B))) {
        val dens = LocalDensity.current.density
        val largeurPx = constraints.maxWidth.toFloat()
        val hauteurPx = constraints.maxHeight.toFloat()
        // Cadrage automatique : toute la structure tient dans la zone libre entre les panneaux du haut et du bas.
        val (echelleAuto, decalageAuto) = remember(c, largeurPx, hauteurPx, dens) {
            val hautReserve = 112f * dens
            val basReserve = 52f * dens
            val dispoH = (hauteurPx - hautReserve - basReserve).coerceAtLeast(hauteurPx * 0.5f)
            // Les étiquettes ne descendent jamais sous ~8,5 dp de texte : quand l'échelle est petite, elles sont
            // plus larges que prévu par la mise en page. On en tient compte (2 passes) pour qu'aucune ne soit
            // coupée par le bord de l'écran.
            var minX = c.minX; var maxX = c.maxX
            var e = 1f
            repeat(3) {
                val bw = (maxX - minX).coerceAtLeast(100f) + 20f
                val bh = (c.maxY - c.minY).coerceAtLeast(100f) + 16f
                e = if (largeurPx > 0f && hauteurPx > 0f) min(largeurPx / (bw * dens), dispoH / (bh * dens)).coerceIn(0.42f, 1.6f) else 1f
                val gonfle = max(1f, 8.5f / (11f * e)) * 1.08f
                minX = c.minX; maxX = c.maxX
                c.etiquettes.forEach { et ->
                    minX = min(minX, et.pos.x - et.w * gonfle / 2f); maxX = max(maxX, et.pos.x + et.w * gonfle / 2f)
                }
            }
            val cxMonde = (minX + maxX) / 2f; val cyMonde = (c.minY + c.maxY) / 2f
            val cibleY = hautReserve + dispoH / 2f
            e to Offset(-cxMonde * e * dens, cibleY - hauteurPx / 2f - cyMonde * e * dens)
        }
        val k = echelleAuto * zoomUser * dens
        val decalage = decalageAuto * zoomUser + panUser
        val origine = Offset(largeurPx / 2f + decalage.x, hauteurPx / 2f + decalage.y)
        fun ecran(p: Pt) = Offset(origine.x + p.x * k, origine.y + p.y * k)
        // Géométrie courante lue par le gestionnaire de toucher (créé une seule fois, il ne doit pas garder
        // l'origine/l'échelle du premier affichage).
        val geometrie by rememberUpdatedState(origine to k)

        // Décor (serpent-dragon, veines, arbre, ruines, cercle runique), rendu une fois en tâche de fond.
        val fond = rememberFondDragon(largeurPx.toInt(), hauteurPx.toInt(), synchrone = !animer)

        // Rectangles des étiquettes dessinées (pour le toucher), remplis pendant le dessin.
        val zonesEtiquettes = remember { HashMap<String, Rect>() }

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(c) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Zoom autour du centre de l'écran : le déplacement suit le même facteur.
                        val z = (zoomUser * zoom).coerceIn(0.6f, 5f) / zoomUser
                        zoomUser *= z
                        panUser = panUser * z + pan
                    }
                }
                .pointerInput(c, filtres) {
                    detectTapGestures { tap ->
                        val (o, kk) = geometrie
                        fun ecranT(p: Pt) = Offset(o.x + p.x * kk, o.y + p.y * kk)
                        // Étiquettes d'abord (dessinées au-dessus), puis hubs, puis cerveau.
                        val e = zonesEtiquettes.entries.firstOrNull { it.value.inflate(6f * dens).contains(tap) }?.key
                            ?.let { id -> c.etiquettes.firstOrNull { it.id == id } }
                        when {
                            e != null && e.type == TypeEtiquette.NOTE -> { noteActive = e.noteId; onNote(e.noteId!!) }
                            e != null && e.type == TypeEtiquette.TAG -> tagActif = if (tagActif == e.tag) null else e.tag
                            e != null && e.type == TypeEtiquette.PLUS -> onHub(e.hub)
                            e != null -> hubActif = if (hubActif == e.hub) null else e.hub
                            else -> {
                                val h = c.hubs.firstOrNull { hb -> val q = ecranT(hb.pos); hypot(tap.x - q.x, tap.y - q.y) < max(hb.rayon, 30f) * kk + 8f * dens }
                                if (h != null) {
                                    if (h.type == TypeHub.TAGS) tagActif = null
                                    else if (hubActif == h.cle) onHub(h.cle) else hubActif = h.cle
                                } else if (hypot(tap.x - o.x, tap.y - o.y) < c.rayonCerveau * kk) {
                                    hubActif = null; tagActif = null; noteActive = null; recherche = ""
                                }
                            }
                        }
                    }
                }
        ) {
            if (fond != null) drawImage(fond) else dessinerFondCosmique(decor, pulse)
            val centre = origine
            val rCentre = c.rayonCerveau * k
            // Grand rayonnement violet autour du cerveau, comme le cœur lumineux de l'image de référence.
            drawCircle(Brush.radialGradient(listOf(Neon.violetVif.copy(alpha = 0.2f * pulse), Neon.violetProfond.copy(alpha = 0.08f), Color.Transparent), centre, rCentre * 4.2f), rCentre * 4.2f, centre)
            val voirDossiers = FiltreGraphe.DOSSIERS in filtres
            val voirPieces = FiltreGraphe.PIECES in filtres
            val voirTags = FiltreGraphe.TAGS in filtres
            fun hubVisible(h: Hub) = when (h.type) { TypeHub.DOSSIER -> voirDossiers; TypeHub.PIECES -> voirPieces; TypeHub.TAGS -> voirTags }
            val hubsAnneau = c.hubs.filter { it.type != TypeHub.TAGS && hubVisible(it) }

            fun alphaEtiquette(e: Etiquette): Float {
                val filtreActif = requete.isNotBlank() || tagActif != null || hubActif != null || noteActive != null
                if (!filtreActif) return 1f
                val ok = when {
                    requete.isNotBlank() -> (e.noteId != null && e.noteId in idsCorrespondants) || e.texte.lowercase().contains(requete)
                    tagActif != null -> (e.noteId != null && e.noteId in notesTag) || e.tag == tagActif
                    noteActive != null -> e.noteId != null && e.noteId in voisinsActifs
                    else -> e.hub == hubActif
                }
                return if (ok) 1f else 0.22f
            }
            fun alphaHub(h: Hub): Float = when {
                hubActif != null -> if (h.cle == hubActif) 1f else 0.45f
                else -> 1f
            }

            // 1. Toile d'énergie cerveau → hubs.
            dessinerToile(centre, rCentre, hubsAnneau.map { ecran(it.pos) to it.rayon * k }, k, pulse)

            // 2. Flèches hub → étiquettes (structure : dossier → notes, pièces jointes → types).
            for (e in c.etiquettes) {
                if (e.type == TypeEtiquette.TAG) continue
                val h = c.hub(e.hub) ?: continue
                if (!hubVisible(h)) continue
                val depart = ConstellationLayout.bordCercle(h.pos, h.rayon * 1.13f, e.pos)
                val arrivee = ConstellationLayout.bordEtiquette(e.copy(w = e.w + 8f, h = e.h + 8f), h.pos)
                fleche(ecran(depart), ecran(arrivee), Neon.violet, 1.4f * k, alphaEtiquette(e) * 0.95f)
            }
            // 3. Tags : faisceau cerveau → hub Tags, et traits fins vers chaque pastille.
            val hubTags = c.hub("__tags")
            if (hubTags != null && voirTags) {
                val bas = ecran(Pt(0f, c.rayonCerveau))
                val pTags = ecran(hubTags.pos)
                drawLine(Neon.violetVif.copy(alpha = 0.18f * pulse), bas, pTags, 7f * k)
                drawLine(Neon.violet.copy(alpha = 0.6f), bas, pTags, 1.4f * k)
                for (e in c.etiquettes.filter { it.type == TypeEtiquette.TAG }) {
                    drawLine(Neon.violet.copy(alpha = 0.45f * alphaEtiquette(e)), pTags, ecran(e.pos), 1f * k)
                }
            }
            // 4. Vrais liens [[wikilinks]] entre notes affichées (orientés, flèche vers la note citée).
            val voirEntrants = FiltreGraphe.ENTRANTS in filtres
            val voirSortants = FiltreGraphe.SORTANTS in filtres
            if (voirDossiers && (voirEntrants || voirSortants)) {
                val parNote = c.etiquettes.filter { it.noteId != null }.associateBy { it.noteId!! }
                for (l in c.liens) {
                    val a = parNote[l.de] ?: continue
                    val b = parNote[l.vers] ?: continue
                    val n = noteActive
                    val visible = when {
                        n == null -> true
                        l.vers == n -> voirEntrants
                        l.de == n -> voirSortants
                        else -> false
                    }
                    if (!visible) continue
                    val pa = ConstellationLayout.bordEtiquette(a.copy(w = a.w + 6f, h = a.h + 6f), b.pos)
                    val pb = ConstellationLayout.bordEtiquette(b.copy(w = b.w + 10f, h = b.h + 10f), a.pos)
                    fleche(ecran(pa), ecran(pb), Neon.lavande, 1.5f * k, if (n == null) 0.75f else 1f, courbe = courbeVersCentre(ecran(pa), ecran(pb), origine))
                }
            }
            // 5. Noyau, puis hubs, puis étiquettes (le texte toujours au-dessus des traits).
            dessinerNoyau(centre, rCentre, pulse, mesureur, fontScale)
            for (h in c.hubs) {
                if (!hubVisible(h)) continue
                if (h.type == TypeHub.TAGS) dessinerHubTags(h, ecran(h.pos), k, mesureur, fontScale)
                else dessinerHub(h, ecran(h.pos), h.rayon * k, mesureur, fontScale, alphaHub(h), actif = h.cle == hubActif, pulse = pulse)
            }
            zonesEtiquettes.clear()
            for (e in c.etiquettes) {
                val h = c.hub(e.hub)
                if (h != null && !hubVisible(h)) continue
                if (e.type == TypeEtiquette.TAG && !voirTags) continue
                val a = alphaEtiquette(e)
                val sur = (e.noteId != null && (e.noteId == noteActive || (requete.isNotBlank() && e.noteId in idsCorrespondants))) || (e.tag != null && e.tag == tagActif)
                zonesEtiquettes[e.id] = dessinerEtiquette(e, ecran(e.pos), k, mesureur, fontScale, a, sur, taillePxMin = 8.5f * dens)
            }
        }

        // ------------------------------------------------------------------ panneaux (style de l'image)
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Panneau(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconeCompose(Icone.RECHERCHE, 16.dp)
                    Text("Recherche de fichiers", color = Neon.blancViolet, fontSize = 13.sp, fontFamily = Neon.serif, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp).weight(1f).clickable { filtresOuverts = !filtresOuverts })
                    Text(if (filtresOuverts) "▾" else "▸", color = Neon.sousTitre, fontSize = 12.sp, modifier = Modifier.clickable { filtresOuverts = !filtresOuverts }.padding(horizontal = 6.dp))
                    enTete()
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 5.dp)
                        .border(1.dp, Neon.violet.copy(alpha = 0.75f), RoundedCornerShape(50))
                        .background(Color(0xFF07040E), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconeCompose(Icone.RECHERCHE, 13.dp, Neon.sousTitre)
                    BasicTextField(
                        value = recherche, onValueChange = { recherche = it; noteActive = null },
                        modifier = Modifier.weight(1f).padding(start = 8.dp), singleLine = true,
                        textStyle = TextStyle(color = Neon.blancViolet, fontSize = 13.sp, fontFamily = Neon.serif),
                        decorationBox = { inner ->
                            if (recherche.isBlank()) Text("Rechercher…", color = Neon.sousTitre.copy(alpha = 0.7f), fontSize = 13.sp, fontFamily = Neon.serif)
                            inner()
                        }
                    )
                    if (recherche.isNotBlank()) Text("✕", color = Neon.sousTitre, fontSize = 12.sp, modifier = Modifier.clickable { recherche = "" }.padding(start = 6.dp))
                }
                if (filtresOuverts) Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Filtres :", color = Neon.sousTitre, fontSize = 11.sp, fontFamily = Neon.serif)
                    FiltreGraphe.values().forEach { f ->
                        val on = f in filtres
                        Row(
                            Modifier.border(1.dp, (if (on) Neon.violet else Neon.violet.copy(alpha = 0.3f)), RoundedCornerShape(50))
                                .background(if (on) Neon.violetProfond.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(50))
                                .clickable { filtres = if (on) filtres - f else filtres + f }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (f) {
                                FiltreGraphe.ENTRANTS -> Text("→", color = Neon.violet, fontSize = 12.sp)
                                FiltreGraphe.SORTANTS -> Text("←", color = Neon.violet, fontSize = 12.sp)
                                else -> IconeCompose(f.icone, 12.dp, alpha = if (on) 1f else 0.4f)
                            }
                            Text(f.libelle, color = if (on) Neon.blancViolet else Neon.sousTitre.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = Neon.serif, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                if (requete.isNotBlank()) {
                    Text("${correspondances.size} résultat(s)", color = Neon.sousTitre, fontSize = 10.sp, fontFamily = Neon.serif, modifier = Modifier.padding(top = 5.dp))
                    correspondances.take(5).forEach { n ->
                        Text("▸ ${n.label}  ·  ${n.folder.ifBlank { "racine" }}", color = Neon.blancViolet, fontSize = 12.sp, fontFamily = Neon.serif,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { noteActive = n.id; onNote(n.id) }.padding(vertical = 3.dp))
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PanneauListe("Fichiers orphelins", "Sans lien depuis le graphe", Icone.FICHIER, Color(0xFFFF6B5C), c.orphelins, orphelinsOuvert) { orphelinsOuvert = !orphelinsOuvert }
                PanneauListe("Mentions non liées", "Notes sans lien entrant", Icone.ETOILE, Color(0xFFFFC14D), c.sansLienEntrant, mentionsOuvert) { mentionsOuvert = !mentionsOuvert }
            }
            Panneau(Modifier.widthIn(max = 150.dp), onClick = { legendeOuverte = !legendeOuverte }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Légende", color = Neon.blancViolet, fontSize = 12.sp, fontFamily = Neon.serif, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                    Text(if (legendeOuverte) "  ▾" else "  ▸", color = Neon.sousTitre, fontSize = 11.sp)
                }
                if (legendeOuverte) {
                    LigneLegende(Icone.DOSSIER, "Dossier")
                    LigneLegende(Icone.NOTE, "Note")
                    LigneLegende(Icone.ETIQUETTE, "Tag")
                    LigneLegende(Icone.TROMBONE, "Pièce jointe")
                    LigneLegende(null, "Lien entrant", "→")
                    LigneLegende(null, "Lien sortant", "←")
                }
            }
        }

        // Compteurs discrets sous le panneau de recherche + bouton de rechargement.
        Text(
            "${c.totalNotes} notes · ${c.totalLiens} liens · ${c.totalTags} tags · ${c.totalPieces} pièces  ⟲",
            color = Neon.sousTitre.copy(alpha = 0.75f), fontSize = 9.sp, fontFamily = Neon.serif,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = if (filtresOuverts) 118.dp else 86.dp, end = 12.dp).clickable { onRecharger() }
        )
        if (c.vide) Text(
            "Aucune note trouvée : choisis ton coffre Obsidian dans Réglages.",
            color = Neon.sousTitre, fontSize = 13.sp, fontFamily = Neon.serif,
            modifier = Modifier.align(Alignment.Center).padding(32.dp)
        )
    }
}

// ---------------------------------------------------------------------------------------------- éléments de panneau

@Composable
private fun Panneau(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, contenu: @Composable () -> Unit) {
    Column(
        modifier
            .drawBehind {
                drawRoundRect(Neon.violetVif.copy(alpha = 0.22f), Offset(-3.dp.toPx(), -3.dp.toPx()), Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()), CornerRadius(14.dp.toPx()))
            }
            .background(Neon.fondPanneau, RoundedCornerShape(12.dp))
            .border(1.dp, Neon.violet.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) { contenu() }
}

@Composable
private fun PanneauListe(titre: String, sousTitre: String, icone: Icone, puce: Color, items: List<String>, ouvert: Boolean, basculer: () -> Unit) {
    Panneau(Modifier.fillMaxWidth(), onClick = basculer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconeCompose(icone, 14.dp, if (icone == Icone.ETOILE) Color(0xFFFFC14D) else Color(0xFFFF6B5C))
            Column(Modifier.padding(start = 6.dp).weight(1f)) {
                Text("$titre · ${items.size}", color = Neon.blancViolet, fontSize = 12.sp, fontFamily = Neon.serif, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ouvert) Text(sousTitre, color = Neon.sousTitre, fontSize = 9.sp, fontFamily = Neon.serif)
            }
            Text(if (ouvert) "▾" else "▸", color = Neon.sousTitre, fontSize = 11.sp)
        }
        if (ouvert) {
            if (items.isEmpty()) Text("Aucun", color = Neon.sousTitre, fontSize = 11.sp, fontFamily = Neon.serif, modifier = Modifier.padding(top = 3.dp))
            items.take(4).forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    Box(Modifier.size(6.dp).background(puce, CircleShape))
                    Text(s, color = Neon.blancViolet, fontSize = 11.sp, fontFamily = Neon.serif, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (items.size > 4) Text("+${items.size - 4} autres", color = Neon.sousTitre, fontSize = 10.sp, fontFamily = Neon.serif, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun LigneLegende(icone: Icone?, texte: String, symbole: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        if (icone != null) IconeCompose(icone, 14.dp) else Text(symbole, color = Neon.violet, fontSize = 13.sp, modifier = Modifier.width(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(texte, color = Neon.blancViolet, fontSize = 11.sp, fontFamily = Neon.serif)
    }
}

@Composable
fun IconeCompose(icone: Icone, taille: Dp, couleur: Color = Neon.violet, alpha: Float = 1f) {
    Canvas(Modifier.size(taille)) { dessinerIcone(icone, Offset(size.width / 2f, size.height / 2f), size.minDimension * 0.92f, couleur, alpha) }
}
