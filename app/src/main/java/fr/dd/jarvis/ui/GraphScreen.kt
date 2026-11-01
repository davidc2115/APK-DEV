package fr.dd.jarvis.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.ObsidianWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Vue « graphe » du coffre Obsidian, organisée PAR DOSSIER — un « cerveau » central, un hub coloré avec icône
 *  par dossier racine, et les notes de chaque dossier qui gravitent autour de leur propre hub (plutôt qu'un nuage
 *  de points dispersé au hasard) — avec un fond en nébuleuse violette pour l'ambiance. Disposition calculée une
 *  fois (force-directed par grappes) puis pan/zoom/tap à la main pour explorer. */

/** Combien de notes au plus affichées par dossier (en liste, le long du rayon qui part de son hub) — au-delà, elles
 *  comptent dans le total du dossier mais ne sont pas affichées individuellement (évite un graphe surchargé). */
private const val MAX_NOTES_PAR_DOSSIER = 12
/** Au-delà de ce nombre de dossiers racine, les plus petits sont regroupés dans une catégorie « Autres » : jamais
 *  de notes perdues silencieusement, mais le cercle de hubs reste lisible même sur un très gros coffre. */
private const val MAX_DOSSIERS = 16

private data class PosNoeud(val node: ObsidianWriter.GraphNode, val pos: Offset, val degre: Int, val categorie: String)
private data class CategorieInfo(val nom: String, val pos: Offset, val nbNotes: Int, val nbAffichees: Int)

/** Résultat complet de la disposition : nœuds positionnés + arêtes + hubs de dossiers, renvoyés ENSEMBLE (plus de
 *  variable globale partagée entre threads/écrans pour faire transiter les arêtes — source d'un bug réel : rien ne
 *  garantissait qu'elle ne soit pas réécrite entre le calcul et sa lecture si deux écrans tournaient en même temps). */
private data class Disposition(val noeuds: List<PosNoeud>, val aretes: List<ObsidianWriter.GraphEdge>, val categories: List<CategorieInfo>)

private fun racineDossier(folder: String): String {
    val r = folder.substringBefore('/').trim()
    return r.ifBlank { "Racine" }
}

private fun rayonHubMonde(nb: Int) = 30f + min(nb, 40) * 1.6f
// Écart (en unités « monde ») entre deux notes consécutives du même dossier, et entre le hub et la première note :
// volontairement très généreux (une étiquette fait au plus 22 caractères) pour qu'aucun chevauchement ne soit
// possible même au zoom le plus fort.
private const val ECART_NOTE = 150f

/** Disposition ENTIÈREMENT déterministe (plus de simulation physique pour les notes, source des chevauchements
 *  précédents) : un cercle de hubs de dossier autour du centre, puis — pour chaque dossier — ses notes alignées
 *  en liste le long du RAYON qui part du centre à travers son hub, espacées d'un écart fixe généreux. Deux notes
 *  ne peuvent donc jamais se chevaucher (alignées sur un même segment, à distance fixe), et deux dossiers voisins
 *  ne peuvent pas se chevaucher non plus (leurs rayons divergent en s'éloignant du centre). */
private fun calculerDisposition(data: ObsidianWriter.GraphData): Disposition {
    if (data.nodes.isEmpty()) return Disposition(emptyList(), emptyList(), emptyList())
    val degres = HashMap<String, Int>()
    data.edges.forEach { e -> degres[e.from] = (degres[e.from] ?: 0) + 1; degres[e.to] = (degres[e.to] ?: 0) + 1 }

    val parCategorieBrut = data.nodes.groupBy { racineDossier(it.folder) }
    val trieesParTaille = parCategorieBrut.keys.sortedByDescending { parCategorieBrut[it]!!.size }
    val parCategorie = LinkedHashMap<String, List<ObsidianWriter.GraphNode>>()
    trieesParTaille.take(MAX_DOSSIERS).forEach { parCategorie[it] = parCategorieBrut[it]!! }
    val surplus = trieesParTaille.drop(MAX_DOSSIERS)
    if (surplus.isNotEmpty()) parCategorie["Autres"] = surplus.flatMap { parCategorieBrut[it]!! }

    val categories = parCategorie.keys.toList()
    val nbCat = categories.size.coerceAtLeast(1)
    // Composition radiale compacte, pensée d'abord pour le format portrait du téléphone.
    // Les hubs occupent un anneau autour du noyau, et non un grand cercle qui écrase tout au zoom.
    val rayonCategories = 172f + min(nbCat, 8) * 13f
    val posCategorie = HashMap<String, Offset>()
    categories.forEachIndexed { i, cat ->
        val angle = (-Math.PI / 2.0 + (i.toFloat() / nbCat) * 2.0 * Math.PI).toFloat()
        posCategorie[cat] = Offset(
            cos(angle) * rayonCategories * 0.88f,
            sin(angle) * rayonCategories
        )
    }

    val noeuds = mutableListOf<PosNoeud>()
    val catInfos = mutableListOf<CategorieInfo>()
    for (cat in categories) {
        val hub = posCategorie[cat]!!
        val angleBase = kotlin.math.atan2(hub.y, hub.x)
        val notesCat = parCategorie[cat]!!
        val ordre = notesCat.sortedWith(
            compareByDescending<ObsidianWriter.GraphNode> { degres[it.id] ?: 0 }
                .thenBy { it.folder }.thenBy { it.label }
        )
        val affichees = ordre.take(MAX_NOTES_PAR_DOSSIER)
        // Les notes forment une couronne autour de leur dossier. L'espacement angulaire évite
        // l'empilement des étiquettes observé dans la version précédente.
        val ecartAngle = when {
            affichees.size <= 5 -> 0.95f
            else -> 0.38f
        }
        affichees.forEachIndexed { k, node ->
            val angle = angleBase + (k - (affichees.size - 1) / 2f) * ecartAngle
            val rayon = 112f + min(affichees.size, 8) * 3f
            val p = Offset(
                hub.x + cos(angle) * rayon,
                hub.y + sin(angle) * rayon
            )
            noeuds += PosNoeud(node, p, degres[node.id] ?: 0, cat)
        }
        catInfos += CategorieInfo(cat, hub, notesCat.size, affichees.size)
    }
    val idsAffiches = noeuds.map { it.node.id }.toHashSet()
    val aretes = data.edges.filter { it.from in idsAffiches && it.to in idsAffiches }
    return Disposition(noeuds, aretes, catInfos)
}

/** Couleur stable par dossier (premier niveau du chemin), pour repérer visuellement les groupes de notes
 *  (Contacts, Société/Devis, Journal…) dans le graphe — une teinte dérivée du nom, toujours la même. */
private fun couleurDossier(folder: String, accent: Color): Color = couleurCategorie(racineDossier(folder), accent)

private fun couleurCategorie(categorie: String, accent: Color): Color {
    if (categorie == "Racine") return accent
    val h = (categorie.lowercase().fold(0) { acc, c -> acc * 31 + c.code } and 0xFFFFFF)
    val hue = (h % 360).toFloat()
    return Color.hsv(hue, 0.55f, 1f)
}

/** Dessine un petit glyphe « dossier » simple (sans dépendance à une police d'icônes), centré sur [centre]. */
private fun DrawScope.dessinerIconeDossier(centre: Offset, taille: Float, couleur: Color) {
    val w = taille; val h = taille * 0.76f
    val trait = (taille * 0.1f).coerceAtLeast(1.4f)
    val chemin = Path().apply {
        moveTo(centre.x - w / 2f, centre.y - h / 2f + h * 0.2f)
        lineTo(centre.x - w / 2f + w * 0.22f, centre.y - h / 2f)
        lineTo(centre.x - w / 2f + w * 0.42f, centre.y - h / 2f)
        lineTo(centre.x - w / 2f + w * 0.55f, centre.y - h / 2f + h * 0.2f)
        lineTo(centre.x + w / 2f, centre.y - h / 2f + h * 0.2f)
        lineTo(centre.x + w / 2f, centre.y + h / 2f)
        lineTo(centre.x - w / 2f, centre.y + h / 2f)
        close()
    }
    drawPath(chemin, color = couleur, style = Stroke(width = trait))
}

/** Petit décor de fond façon « nébuleuse » (dégradé violet + anneaux concentriques + poussière d'étoiles), pour
 *  l'ambiance demandée — fixe par rapport à l'écran (ne bouge pas avec le pan/zoom du graphe lui-même, sauf les
 *  anneaux qui entourent la structure et suivent donc le cadrage). */
private fun DrawScope.dessinerNebuleuse(etoiles: List<Triple<Float, Float, Float>>, cx: Float, cy: Float, rayonStructure: Float) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF211034), Color(0xFF0B0813), Color(0xFF030308)),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = max(size.width, size.height) * 0.85f
        ),
        size = size
    )
    for ((fx, fy, a) in etoiles) {
        drawCircle(color = Color.White.copy(alpha = a * 0.5f), radius = (a * 2.2f + 0.6f) * density, center = Offset(fx * size.width, fy * size.height))
    }
    if (rayonStructure > 0f) {
        drawCircle(color = Color(0xFF9B6BFF).copy(alpha = 0.16f), radius = rayonStructure, center = Offset(cx, cy), style = Stroke(width = 1.4f * density))
        drawCircle(color = Color(0xFF9B6BFF).copy(alpha = 0.09f), radius = rayonStructure * 1.16f, center = Offset(cx, cy), style = Stroke(width = 1f * density))
    }
}

/** Version purement visuelle (sans barre, sans tap/zoom/pan, sans fiche de note) du graphe Obsidian, utilisée
 *  comme fond pendant une session de conversation vocale. [active] (écoute/réflexion en cours) l'éclaircit
 *  légèrement, pour garder le repère visuel qu'offrait l'ancienne orbe pulsante. */
@Composable
fun GraphBackdrop(app: JarvisApp, modifier: Modifier = Modifier, active: Boolean = false) {
    val accent = LocalAccent.current
    var noeuds by remember { mutableStateOf<List<PosNoeud>>(emptyList()) }
    var aretes by remember { mutableStateOf<List<ObsidianWriter.GraphEdge>>(emptyList()) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        val resultat = withContext(Dispatchers.Default) {
            calculerDisposition(app.obsidian.graph())
        }
        noeuds = resultat.noeuds
        aretes = resultat.aretes
    }

    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "graphePulse").animateFloat(
        initialValue = 0.72f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse
        ), label = "graphePulse"
    )
    val eclat = if (active) pulse else 0.82f

    BoxWithConstraints(modifier) {
        val localDensity = LocalDensity.current
        LaunchedEffect(noeuds) {
            if (noeuds.isNotEmpty()) {
                val minX = noeuds.minOf { it.pos.x }; val maxX = noeuds.maxOf { it.pos.x }
                val minY = noeuds.minOf { it.pos.y }; val maxY = noeuds.maxOf { it.pos.y }
                val largeur = (maxX - minX).coerceAtLeast(40f); val hauteur = (maxY - minY).coerceAtLeast(40f)
                val canvasW = with(localDensity) { maxWidth.toPx() }; val canvasH = with(localDensity) { maxHeight.toPx() }
                if (canvasW > 0f && canvasH > 0f) {
                    val s = (minOf(canvasW / largeur, canvasH / hauteur) * 0.8f).coerceIn(0.5f, 8f)
                    scale = s
                    offset = Offset(-(minX + maxX) / 2f * s, -(minY + maxY) / 2f * s)
                }
            }
        }
        if (noeuds.isNotEmpty()) {
            val parId = remember(noeuds) { noeuds.associateBy { it.node.id } }
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f + offset.x
                val cy = size.height / 2f + offset.y
                for (a in aretes) {
                    val pa = parId[a.from]?.pos ?: continue
                    val pb = parId[a.to]?.pos ?: continue
                    drawLine(
                        color = Color(0x3390B8FF).copy(alpha = 0.27f * eclat),
                        start = Offset(cx + pa.x * scale, cy + pa.y * scale),
                        end = Offset(cx + pb.x * scale, cy + pb.y * scale),
                        strokeWidth = 1.3f * density * scale.coerceAtLeast(0.4f)
                    )
                }
                for (pn in noeuds) {
                    val sx = cx + pn.pos.x * scale
                    val sy = cy + pn.pos.y * scale
                    val rayon = (7f + min(pn.degre, 10) * 1.8f) * density * scale
                    val estHub = pn.degre >= 5
                    val base = if (pn.node.ghost) Color(0xFF999999) else couleurDossier(pn.node.folder, accent)
                    drawCircle(
                        color = base.copy(alpha = eclat * (if (pn.node.ghost) 0.33f else if (estHub) 0.9f else 0.55f)),
                        radius = rayon, center = Offset(sx, sy)
                    )
                    if (estHub) drawCircle(
                        color = accent.copy(alpha = 0.2f * eclat), radius = rayon * 2f, center = Offset(sx, sy),
                        style = Stroke(width = 1.5f * density * scale.coerceAtLeast(0.3f))
                    )
                }
            }
        }
    }
}

@Composable
fun GraphScreen(app: JarvisApp) {
    var showOrb by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(Color(0xFF030208))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF0D0815)).padding(horizontal = 14.dp, vertical = 5.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showOrb = !showOrb }) {
                Text(if (showOrb) "GRAPHE 2D" else "ORBE 3D", color = Color(0xFFC7A5FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            if (showOrb) ObsidianOrb3D(app) else GraphScreen2DClean(app)
        }
    }
}

@Composable
private fun GraphScreen2D(app: JarvisApp) {
    val accent = LocalAccent.current
    var chargement by remember { mutableStateOf(true) }
    var noeuds by remember { mutableStateOf<List<PosNoeud>>(emptyList()) }
    var aretes by remember { mutableStateOf<List<ObsidianWriter.GraphEdge>>(emptyList()) }
    var categories by remember { mutableStateOf<List<CategorieInfo>>(emptyList()) }
    var totalNotes by remember { mutableStateOf(0) }
    var version by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf<ObsidianWriter.GraphNode?>(null) }
    var apercu by remember { mutableStateOf("") }
    var recherche by remember { mutableStateOf("") }
    var dossierActif by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(version) {
        chargement = true
        val resultat = withContext(Dispatchers.Default) {
            val data = app.obsidian.graph()
            calculerDisposition(data) to data.nodes.size
        }
        noeuds = resultat.first.noeuds
        aretes = resultat.first.aretes
        categories = resultat.first.categories
        totalNotes = resultat.second
        chargement = false
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Pulsation du cerveau central (toujours présent, virtuel — ne correspond plus à une note précise mais au
    // graphe dans son ensemble, comme dans une vraie vue « cerveau de connaissances ») et des hubs de dossier.
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "hubPulse").animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse
        ), label = "hubPulse"
    )
    // API Compose standard pour le texte (TextMeasurer + DrawScope.drawText), à la place de l'ancien
    // android.graphics.Paint via nativeCanvas : ce dernier restait invisible sur certains rendus matériels
    // (constaté même sur des nœuds totalement isolés, sans rien à proximité pour les recouvrir — donc pas
    // un simple souci de chevauchement mais bien un problème du chemin de rendu nativeCanvas lui-même).
    val mesureurTexte = rememberTextMeasurer()
    // Poussière d'étoiles fixe (ambiance nébuleuse), générée une seule fois.
    val etoiles = remember { (0 until 90).map { i -> Triple(Random(i * 7 + 1).nextFloat(), Random(i * 13 + 2).nextFloat(), Random(i * 19 + 3).nextFloat()) } }

    val isolees = remember(noeuds) { noeuds.count { it.degre == 0 && !it.node.ghost } }
    val requete = recherche.trim().lowercase()
    val resultatsRecherche = remember(noeuds, requete) {
        if (requete.isBlank()) emptySet() else noeuds.filter { it.node.label.lowercase().contains(requete) || it.node.folder.lowercase().contains(requete) }.map { it.node.id }.toSet()
    }

    Column(Modifier.fillMaxSize()) {
        // Barre de recherche et filtres : ils mettent en évidence les éléments réels du coffre sans fabriquer de liens.
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xCC10091C), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⌕", color = accent, fontSize = 20.sp)
                BasicTextField(
                    value = recherche, onValueChange = { recherche = it },
                    modifier = Modifier.weight(1f).padding(start = 9.dp),
                    singleLine = true, textStyle = TextStyle(color = HudText, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    decorationBox = { inner ->
                        if (recherche.isBlank()) Text("Rechercher une note ou un dossier…", color = HudDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        inner()
                    }
                )
                if (recherche.isNotBlank()) Text("✕", color = HudDim, modifier = Modifier.clickable { recherche = "" }.padding(start = 8.dp))
            }
            if (requete.isNotBlank()) Text("${resultatsRecherche.size} résultat(s) · les correspondances sont éclairées dans le graphe", color = HudDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 5.dp, start = 4.dp))
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxSize()) {
            val localDensity = LocalDensity.current
            // Cadrage automatique : englobe maintenant aussi les hubs de dossier (pas seulement les notes) et le
            // centre virtuel (0,0), pour que toute la structure — pas juste le nuage de notes — tienne à l'écran.
            LaunchedEffect(noeuds, categories) {
                if (noeuds.isNotEmpty()) {
                    val xs = noeuds.map { it.pos.x } + categories.map { it.pos.x } + listOf(0f)
                    val ys = noeuds.map { it.pos.y } + categories.map { it.pos.y } + listOf(0f)
                    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
                    val largeur = (maxX - minX).coerceAtLeast(40f); val hauteur = (maxY - minY).coerceAtLeast(40f)
                    val canvasW = with(localDensity) { maxWidth.toPx() }; val canvasH = with(localDensity) { maxHeight.toPx() }
                    if (canvasW > 0f && canvasH > 0f) {
                        val s = (minOf(canvasW / largeur, canvasH / hauteur) * 0.85f).coerceIn(0.15f, 4f)
                        scale = s
                        offset = Offset(-(minX + maxX) / 2f * s, -(minY + maxY) / 2f * s)
                    }
                }
            }
            if (chargement && noeuds.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = accent)
                    Text("Lecture du coffre…", color = HudDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 10.dp))
                }
            } else if (noeuds.isEmpty()) {
                Text(
                    "Aucune note trouvée. Choisis ton coffre Obsidian dans Réglages pour voir le graphe.",
                    color = HudDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
            } else {
                val parId = remember(noeuds) { noeuds.associateBy { it.node.id } }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.25f, 4f)
                                offset += pan
                            }
                        }
                        .pointerInput(noeuds) {
                            detectTapGestures { tap ->
                                val cx = size.width / 2f + offset.x
                                val cy = size.height / 2f + offset.y
                                var meilleur: PosNoeud? = null
                                var meilleureDist = Float.MAX_VALUE
                                for (pn in noeuds) {
                                    val sx = cx + pn.pos.x * scale
                                    val sy = cy + pn.pos.y * scale
                                    val d = hypot(tap.x - sx, tap.y - sy)
                                    val rayon = (6f + min(pn.degre, 10) * 1.6f) * scale + 18f
                                    if (d < rayon && d < meilleureDist) { meilleur = pn; meilleureDist = d }
                                }
                                if (meilleur != null) {
                                    selection = meilleur.node
                                    apercu = if (meilleur.node.ghost) "" else app.obsidian.readNote(meilleur.node.id)?.take(2500) ?: ""
                                }
                            }
                        }
                ) {
                    val cx = size.width / 2f + offset.x
                    val cy = size.height / 2f + offset.y
                    val rayonStructureEcran = if (categories.isNotEmpty()) categories.maxOf { hypot(it.pos.x, it.pos.y) } * scale else 0f
                    dessinerNebuleuse(etoiles, cx, cy, rayonStructureEcran)

                    // Rayons plafonnés en pixels écran (pas seulement en unités « monde ») : quel que soit le résultat
                    // du calcul de disposition ou du zoom auto-adapté, un nœud ne peut plus jamais devenir assez gros
                    // pour avaler ses voisins (et donc cacher leurs traits de lien et leur texte dessous).
                    fun rayonEcran(pn: PosNoeud) = ((10f + min(pn.degre, 10) * 2.2f) * density * scale).coerceIn(5f * density, 34f * density)
                    fun rayonHubEcran(cat: CategorieInfo) = ((30f + min(cat.nbNotes, 40) * 1.6f) * density * scale).coerceIn(16f * density, 64f * density)
                    val rayonCerveau = (66f * density * scale).coerceIn(34f * density, 112f * density)

                    // Passe 1 : arêtes structurelles (cerveau→dossier, dossier→note), sous tout le reste.
                    for (cat in categories) {
                        val hcol = couleurCategorie(cat.nom, accent)
                        drawLine(
                            color = hcol.copy(alpha = 0.5f),
                            start = Offset(cx, cy), end = Offset(cx + cat.pos.x * scale, cy + cat.pos.y * scale),
                            strokeWidth = 2.6f * density * scale.coerceAtLeast(0.4f)
                        )
                    }
                    for (pn in noeuds) {
                        val hub = categories.find { it.nom == pn.categorie } ?: continue
                        val hcol = couleurCategorie(pn.categorie, accent)
                        drawLine(
                            color = hcol.copy(alpha = 0.28f),
                            start = Offset(cx + hub.pos.x * scale, cy + hub.pos.y * scale),
                            end = Offset(cx + pn.pos.x * scale, cy + pn.pos.y * scale),
                            strokeWidth = 1.4f * density * scale.coerceAtLeast(0.4f)
                        )
                    }
                    // Passe 2 : vrais liens « [[...]] » entre notes (par-dessus les traits de structure, plus nets).
                    for (a in aretes) {
                        val pa = parId[a.from]?.pos ?: continue
                        val pb = parId[a.to]?.pos ?: continue
                        drawLine(
                            color = Color(0xFFD5BFFF).copy(alpha = 0.82f),
                            start = Offset(cx + pa.x * scale, cy + pa.y * scale),
                            end = Offset(cx + pb.x * scale, cy + pb.y * scale),
                            strokeWidth = 2.2f * density * scale.coerceAtLeast(0.4f)
                        )
                    }
                    // Passe 3 : noyau d'obsidienne — sphère sombre, reflet violet et orbites holographiques.
                    val centre = Offset(cx, cy)
                    val violet = Color(0xFF9B62FF)
                    drawCircle(color = violet.copy(alpha = 0.12f * pulse), radius = rayonCerveau * (2.65f + 0.22f * pulse), center = centre)
                    drawCircle(color = violet.copy(alpha = 0.18f), radius = rayonCerveau * 1.72f, center = centre, style = Stroke(width = 1.6f * density))
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF8A55C9), Color(0xFF29143F), Color(0xFF090610)),
                            center = Offset(cx - rayonCerveau * 0.28f, cy - rayonCerveau * 0.34f),
                            radius = rayonCerveau * 1.65f
                        ),
                        radius = rayonCerveau,
                        center = centre
                    )
                    drawCircle(color = Color(0xFFC9A7FF).copy(alpha = 0.72f), radius = rayonCerveau, center = centre, style = Stroke(width = 1.5f * density))
                    drawCircle(color = Color.White.copy(alpha = 0.12f), radius = rayonCerveau * 0.76f, center = Offset(cx - rayonCerveau * 0.12f, cy - rayonCerveau * 0.18f), style = Stroke(width = 1.3f * density))
                    // Anneaux orbitaux inclinés autour du noyau.
                    drawOval(
                        color = Color(0xFFB58AFF).copy(alpha = 0.68f * pulse),
                        topLeft = Offset(cx - rayonCerveau * 1.55f, cy - rayonCerveau * 0.48f),
                        size = Size(rayonCerveau * 3.1f, rayonCerveau * 0.96f),
                        style = Stroke(width = 1.25f * density)
                    )
                    drawOval(
                        color = Color(0xFF7D4AC4).copy(alpha = 0.52f),
                        topLeft = Offset(cx - rayonCerveau * 1.35f, cy - rayonCerveau * 0.28f),
                        size = Size(rayonCerveau * 2.7f, rayonCerveau * 0.56f),
                        style = Stroke(width = 1f * density)
                    )
                    repeat(10) { i ->
                        val a = (i / 10f) * 2 * Math.PI.toFloat()
                        val r1 = rayonCerveau * 0.72f
                        val r2 = rayonCerveau * (1.12f + 0.08f * pulse)
                        drawLine(
                            color = Color(0xFFDCCBFF).copy(alpha = 0.38f * pulse),
                            start = Offset(cx + cos(a) * r1, cy + sin(a) * r1),
                            end = Offset(cx + cos(a) * r2, cy + sin(a) * r2),
                            strokeWidth = 1.1f * density
                        )
                        drawCircle(color = Color(0xFFDCCBFF).copy(alpha = 0.72f), radius = 1.8f * density, center = Offset(cx + cos(a) * r2, cy + sin(a) * r2))
                    }
                    // Passe 4 : hubs de dossier (cercle coloré + icône + halo).
                    for (cat in categories) {
                        val hcol = couleurCategorie(cat.nom, accent)
                        val sx = cx + cat.pos.x * scale; val sy = cy + cat.pos.y * scale
                        val r = rayonHubEcran(cat)
                        drawCircle(color = hcol.copy(alpha = 0.2f * pulse), radius = r * (1.4f + 0.25f * pulse), center = Offset(sx, sy))
                        drawCircle(color = hcol.copy(alpha = if (dossierActif == null || dossierActif == cat.nom) 0.92f else 0.18f), radius = r, center = Offset(sx, sy))
                        drawCircle(color = Color.White.copy(alpha = 0.35f), radius = r, center = Offset(sx, sy), style = Stroke(width = 1.4f * density))
                        dessinerIconeDossier(Offset(sx, sy), r * 0.78f, Color.White.copy(alpha = 0.92f))
                    }
                    // Passe 5 : cercles des notes, par-dessus les traits.
                    for (pn in noeuds) {
                        val sx = cx + pn.pos.x * scale
                        val sy = cy + pn.pos.y * scale
                        val rayon = rayonEcran(pn)
                        val estHub = pn.degre >= 5
                        val couleur = if (pn.node.ghost) Color(0xFF999999) else couleurDossier(pn.node.folder, accent)
                        drawCircle(
                            color = if (pn.node.ghost) couleur.copy(alpha = 0.4f) else if (requete.isNotBlank() && resultatsRecherche.isNotEmpty()) { if (pn.node.id in resultatsRecherche) Color(0xFFFFD36E) else couleur.copy(alpha = 0.16f) } else if (dossierActif != null && pn.categorie != dossierActif) couleur.copy(alpha = 0.14f) else if (estHub) couleur else couleur.copy(alpha = 0.82f),
                            radius = rayon,
                            center = Offset(sx, sy)
                        )
                        if (estHub) drawCircle(
                            color = couleur.copy(alpha = 0.3f), radius = rayon * 2f, center = Offset(sx, sy),
                            style = Stroke(width = 1.6f * density * scale.coerceAtLeast(0.3f))
                        )
                    }
                    // Passe 6 : TOUS les textes en dernier, par-dessus absolument tout — aucun cercle dessiné après
                    // ne peut donc plus jamais recouvrir un label dessiné avant lui.
                    val styleCerveau = TextStyle(color = Color.White, fontSize = (15f * scale.coerceIn(0.9f, 2.2f)).sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    val yCerveau = cy + rayonCerveau + 12f * density
                    dessinerPlaqueEtTexte(mesureurTexte, "CERVEAU", styleCerveau, Offset(cx, yCerveau), density)
                    val styleSousTitre = TextStyle(color = Color(0xFFE0D0FF), fontSize = (10.5f * scale.coerceIn(0.9f, 2.2f)).sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                    dessinerPlaqueEtTexte(mesureurTexte, "GRAPHE OBSIDIAN", styleSousTitre, Offset(cx, yCerveau + 24f * density), density)

                    for (cat in categories) {
                        val sx = cx + cat.pos.x * scale; val sy = cy + cat.pos.y * scale
                        val r = rayonHubEcran(cat)
                        val styleCat = TextStyle(
                            color = Color.White, fontSize = (14.5f * scale.coerceIn(0.9f, 2.2f)).sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                        )
                        dessinerPlaqueEtTexte(mesureurTexte, "${cat.nom} (${cat.nbNotes})", styleCat, Offset(sx, sy + r + 10f * density), density)
                    }
                    for (pn in noeuds) {
                        val sx = cx + pn.pos.x * scale
                        val sy = cy + pn.pos.y * scale
                        val rayon = rayonEcran(pn)
                        val label = pn.node.label.take(18)
                        val taille = 11.5f * scale.coerceIn(0.9f, 2.2f)
                        val style = TextStyle(
                            color = if (pn.node.ghost) Color.LightGray else Color.White,
                            fontSize = taille.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal, textAlign = TextAlign.Center
                        )
                        dessinerPlaqueEtTexte(mesureurTexte, label, style, Offset(sx, sy + rayon + 8f * density), density)
                    }
                    // « +N autres » au bout du rayon de chaque dossier qui a plus de notes que l'affichage n'en montre.
                    for (cat in categories) {
                        val masquees = cat.nbNotes - cat.nbAffichees
                        if (masquees <= 0) continue
                        val hub = cat.pos
                        val dist = hypot(hub.x, hub.y).coerceAtLeast(0.001f)
                        val u = Offset(hub.x / dist, hub.y / dist)
                        val r = rayonHubMonde(cat.nbNotes) + ECART_NOTE * (cat.nbAffichees + 1)
                        val p = Offset(hub.x + u.x * r, hub.y + u.y * r)
                        val sx = cx + p.x * scale; val sy = cy + p.y * scale
                        val styleN = TextStyle(color = HudDim, fontSize = (11.5f * scale.coerceIn(0.9f, 2.2f)).sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                        dessinerPlaqueEtTexte(mesureurTexte, "+$masquees autres…", styleN, Offset(sx, sy), density)
                    }
                }

                // Légende des dossiers (couleurs) — comme dans une vraie vue de connaissances.
                if (categories.isNotEmpty()) Column(
                    Modifier.align(Alignment.BottomStart).padding(10.dp)
                        .background(Color(0xCC0B0618), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text("DOSSIERS", color = HudDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    categories.take(8).forEach { cat ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                            Box(Modifier.size(9.dp).background(couleurCategorie(cat.nom, accent), CircleShape))
                            Text("  ${cat.nom} · ${cat.nbNotes}", color = HudText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (categories.size > 8) Text("+${categories.size - 8} autres…", color = HudDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 3.dp))
                }
                if (isolees > 0) Text(
                    "⚠ $isolees note(s) isolée(s) (aucun lien)",
                    color = HudDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .background(Color(0xCC0B0618), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }

    selection?.let { node ->
        AlertDialog(
            onDismissRequest = { selection = null },
            containerColor = Color(0xFF100B19), titleContentColor = Color(0xFFB58AFF), textContentColor = HudText,
            title = { Text(node.label, fontFamily = FontFamily.Monospace) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (node.ghost) {
                        Text("Note liée mais introuvable dans le coffre (lien cassé ou pas encore créée).", color = HudDim, fontSize = 12.sp)
                    } else {
                        if (node.folder.isNotBlank()) Text(node.folder, color = accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 6.dp))
                        Text(apercu.ifBlank { "(note vide)" }, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = { TextButton({ selection = null }) { Text("Fermer", color = accent) } }
        )
    }
}

/** Dessine une petite plaque sombre derrière [texte] (centré horizontalement sur [centreHaut].x, qui commence à
 *  [centreHaut].y) puis le texte par-dessus — factorisé car utilisé pour le cerveau, chaque hub et chaque note. */
private fun DrawScope.dessinerPlaqueEtTexte(
    mesureur: androidx.compose.ui.text.TextMeasurer, texte: String, style: TextStyle, centreHaut: Offset, density: Float
) {
    val resultat = mesureur.measure(texte, style)
    val topLeft = Offset(centreHaut.x - resultat.size.width / 2f, centreHaut.y)
    drawRoundRect(
        color = Color(0xB9080614),
        topLeft = Offset(topLeft.x - 6f * density, topLeft.y - 3f * density),
        size = Size(resultat.size.width + 12f * density, resultat.size.height + 6f * density),
        cornerRadius = CornerRadius(6f * density, 6f * density)
    )
    drawText(resultat, topLeft = topLeft)
}


/**
 * Vue 2D épurée : un seul réseau de notes, sans hubs géants ni libellés permanents.
 * Les noms n'apparaissent qu'à la sélection afin de conserver un graphe lisible sur téléphone.
 */
@Composable
private fun GraphScreen2DClean(app: JarvisApp) {
    var nodes by remember { mutableStateOf<List<ObsidianWriter.GraphNode>>(emptyList()) }
    var edges by remember { mutableStateOf<List<ObsidianWriter.GraphEdge>>(emptyList()) }
    var selected by remember { mutableStateOf<ObsidianWriter.GraphNode?>(null) }
    var loading by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        val graph = withContext(Dispatchers.IO) { app.obsidian.graph() }
        nodes = graph.nodes
        edges = graph.edges
        loading = false
    }

    // Disposition de constellation déterministe : les liens réels attirent leurs notes,
    // les notes se repoussent pour éviter un anneau uniforme et les grappes artificielles par dossier.
    val selectedNeighborhood = remember(selected, edges) {
        val current = selected?.id ?: return@remember emptySet<String>()
        edges.filter { it.from == current || it.to == current }
            .flatMap { listOf(it.from, it.to) }.toSet() + current
    }

    val positions = remember(nodes, edges) {
        val count = nodes.size
        if (count == 0) emptyMap() else {
            val index = nodes.mapIndexed { i, node -> node.id to i }.toMap()
            val x = FloatArray(count) { i ->
                val a = i * 2.3999632f
                val r = 0.24f + 0.76f * kotlin.math.sqrt((i + 0.5f) / count.toFloat())
                cos(a) * r
            }
            val y = FloatArray(count) { i ->
                val a = i * 2.3999632f
                val r = 0.24f + 0.76f * kotlin.math.sqrt((i + 0.5f) / count.toFloat())
                sin(a) * r
            }
            val links = edges.mapNotNull { e ->
                val a = index[e.from] ?: return@mapNotNull null
                val b = index[e.to] ?: return@mapNotNull null
                if (a == b) null else a to b
            }
            val iterations = if (count <= 450) 54 else 18
            repeat(iterations) {
                val fx = FloatArray(count) { -x[it] * 0.012f }
                val fy = FloatArray(count) { -y[it] * 0.012f }
                if (count <= 450) {
                    for (i in 0 until count) for (j in i + 1 until count) {
                        val dx = x[i] - x[j]
                        val dy = y[i] - y[j]
                        val d2 = (dx * dx + dy * dy).coerceAtLeast(0.0025f)
                        val force = 0.0018f / d2
                        fx[i] += dx * force; fy[i] += dy * force
                        fx[j] -= dx * force; fy[j] -= dy * force
                    }
                }
                links.forEach { (a, b) ->
                    val dx = x[b] - x[a]
                    val dy = y[b] - y[a]
                    fx[a] += dx * 0.018f; fy[a] += dy * 0.018f
                    fx[b] -= dx * 0.018f; fy[b] -= dy * 0.018f
                }
                val step = if (count <= 450) 0.72f else 0.48f
                for (i in 0 until count) {
                    x[i] += (fx[i] * step).coerceIn(-0.035f, 0.035f)
                    y[i] += (fy[i] * step).coerceIn(-0.035f, 0.035f)
                }
            }
            val extent = maxOf(x.maxOf { kotlin.math.abs(it) }, y.maxOf { kotlin.math.abs(it) }, 0.001f)
            nodes.mapIndexed { i, node -> node.id to Offset(x[i] / extent, y[i] / extent) }.toMap()
        }
    }

    Box(Modifier.fillMaxSize().background(
        Brush.radialGradient(
            listOf(Color(0xFF25113B), Color(0xFF0D0817), Color(0xFF030308)),
            radius = 1100f
        )
    )) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(nodes, positions) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(0.55f, 4.5f)
                        pan += panChange
                    }
                }
                .pointerInput(nodes, positions, zoom, pan) {
                    detectTapGestures { tap ->
                        val radius = minOf(size.width, size.height) * 0.39f * zoom
                        val center = Offset(size.width / 2f + pan.x, size.height / 2f + pan.y)
                        selected = nodes.mapNotNull { node ->
                            val p = positions[node.id] ?: return@mapNotNull null
                            val point = center + Offset(p.x * radius, p.y * radius)
                            val d = (point - tap).getDistance()
                            if (d < 30f) node to d else null
                        }.minByOrNull { it.second }?.first
                    }
                }
        ) {
            val center = Offset(size.width / 2f + pan.x, size.height / 2f + pan.y)
            val radius = size.minDimension * 0.39f * zoom
            val byId = nodes.associateBy { it.id }
            val brainRadius = (size.minDimension * 0.075f).coerceAtLeast(24f)
            drawCircle(Color(0xFF9E62F4).copy(alpha = 0.14f), brainRadius * 2.45f, center)
            // Noyau cérébral bilobé : une silhouette identifiable, pas une grosse bulle de dossier.
            val brain = Path().apply {
                moveTo(center.x, center.y - brainRadius * 0.88f)
                cubicTo(center.x - brainRadius * 0.28f, center.y - brainRadius * 1.12f, center.x - brainRadius * 0.92f, center.y - brainRadius * 0.86f, center.x - brainRadius * 0.88f, center.y - brainRadius * 0.22f)
                cubicTo(center.x - brainRadius * 1.18f, center.y + brainRadius * 0.18f, center.x - brainRadius * 0.72f, center.y + brainRadius * 0.92f, center.x - brainRadius * 0.18f, center.y + brainRadius * 0.78f)
                cubicTo(center.x - brainRadius * 0.06f, center.y + brainRadius * 0.98f, center.x, center.y + brainRadius * 0.82f, center.x, center.y + brainRadius * 0.72f)
                cubicTo(center.x, center.y + brainRadius * 0.82f, center.x + brainRadius * 0.06f, center.y + brainRadius * 0.98f, center.x + brainRadius * 0.18f, center.y + brainRadius * 0.78f)
                cubicTo(center.x + brainRadius * 0.72f, center.y + brainRadius * 0.92f, center.x + brainRadius * 1.18f, center.y + brainRadius * 0.18f, center.x + brainRadius * 0.88f, center.y - brainRadius * 0.22f)
                cubicTo(center.x + brainRadius * 0.92f, center.y - brainRadius * 0.86f, center.x + brainRadius * 0.28f, center.y - brainRadius * 1.12f, center.x, center.y - brainRadius * 0.88f)
                close()
            }
            drawPath(brain, Brush.radialGradient(listOf(Color(0xFFB58AFF), Color(0xFF512A79), Color(0xFF10091B)), center, brainRadius * 1.7f))
            drawPath(brain, Color(0xFFD8B9FF).copy(alpha = 0.72f), style = Stroke(1.4f))
            drawLine(Color(0xFFE2C9FF).copy(alpha = 0.55f), Offset(center.x, center.y - brainRadius * 0.72f), Offset(center.x, center.y + brainRadius * 0.7f), 1.1f)
            // Petits sillons lumineux internes, distincts des relations réelles entre notes.
            repeat(8) { i ->
                val side = if (i % 2 == 0) -1f else 1f
                val y = center.y - brainRadius * 0.48f + (i / 2) * brainRadius * 0.3f
                val x = center.x + side * brainRadius * (0.22f + (i % 3) * 0.12f)
                drawLine(Color(0xFFE3C9FF).copy(alpha = 0.34f), Offset(center.x, y), Offset(x, y + side * brainRadius * 0.12f), 1.15f)
                drawCircle(Color(0xFFE8D5FF).copy(alpha = 0.72f), 1.7f, Offset(x, y + side * brainRadius * 0.12f))
            }
            // Relations explicites uniquement : aucune arête n'est inventée pour remplir la scène.
            edges.forEach { edge ->
                val a = positions[edge.from] ?: return@forEach
                val b = positions[edge.to] ?: return@forEach
                drawLine(
                    Color(0xFFB987FF).copy(alpha = if (selected == null) 0.58f else if (edge.from in selectedNeighborhood && edge.to in selectedNeighborhood) 0.9f else 0.07f),
                    center + Offset(a.x * radius, a.y * radius),
                    center + Offset(b.x * radius, b.y * radius),
                    strokeWidth = if (selected != null && edge.from in selectedNeighborhood && edge.to in selectedNeighborhood) 2.0f else 1.25f
                )
            }
            nodes.forEach { node ->
                val p = positions[node.id] ?: return@forEach
                val point = center + Offset(p.x * radius, p.y * radius)
                val isSelected = selected?.id == node.id
                // Une teinte stable par dossier, sans créer de hubs ni de bulles de catégories.
                val color = if (node.ghost) Color(0xFF8B8494) else couleurDossier(node.folder, Color(0xFFC18AFF))
                val related = node.id in selectedNeighborhood
                val dim = selected != null && !related
                drawCircle(color.copy(alpha = if (isSelected) 0.52f else if (dim) 0.035f else if (related) 0.28f else 0.16f), if (isSelected) 23f else if (related) 16f else 13f, point)
                drawCircle((if (isSelected) Color.White else color).copy(alpha = if (dim) 0.12f else 1f), if (isSelected) 7.5f else 4.2f, point)
                if (isSelected) drawCircle(Color(0xFFE4C9FF), 13f, point, style = Stroke(1.5f))
            }
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GRAPHE OBSIDIAN", color = Color(0xFFE8D9FF), fontSize = 16.sp, letterSpacing = 3.sp)
            Text("${nodes.size} NOTES  ·  ${edges.size} LIENS", color = Color(0xFF9F83BF), fontSize = 10.sp, letterSpacing = 1.5.sp)
        }
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFFB77BFF))
        selected?.let { node ->
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
                    .background(Color(0xE8171025), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text(node.label, color = Color.White, fontSize = 14.sp)
                Text(if (node.ghost) "Référence non résolue" else node.folder.ifBlank { "Note" },
                    color = Color(0xFFB99AD8), fontSize = 10.sp)
                TextButton(onClick = { selected = null }) { Text("FERMER", color = Color(0xFFD5B6FF)) }
            }
        }
    }
}
