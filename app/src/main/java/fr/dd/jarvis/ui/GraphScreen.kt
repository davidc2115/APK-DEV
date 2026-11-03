package fr.dd.jarvis.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.runtime.saveable.rememberSaveable
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
private const val MAX_NOTES_PAR_DOSSIER = 18
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
            affichees.size <= 4 -> 1.05f
            affichees.size <= 7 -> 0.72f
            else -> 0.52f
        }
        affichees.forEachIndexed { k, node ->
            val angle = angleBase + (k - (affichees.size - 1) / 2f) * ecartAngle
            val rayon = 158f + min(affichees.size, 8) * 5f
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
            calculerDisposition(app.obsidian.graph(limiteNotes = 400, limitePieces = 800, limiteLecture = 400))
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
    // Deux vues du même « Cerveau / Graphe Obsidian » (style de l'image de référence) : l'Orbe 3D et le graphe 2D.
    var showOrb by rememberSaveable { mutableStateOf(true) }
    val bascule: @Composable () -> Unit = {
        Text(
            if (showOrb) "GRAPHE 2D" else "ORBE 3D",
            color = Neon.lavande, fontSize = 10.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            modifier = Modifier
                .border(1.dp, Neon.violet, RoundedCornerShape(50))
                .background(Color(0xCC12081F), RoundedCornerShape(50))
                .clickable { showOrb = !showOrb }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF030208))) {
        if (showOrb) ObsidianOrb3D(app, enTete = bascule) else GrapheCerveau2D(app, enTete = bascule)
    }
}
