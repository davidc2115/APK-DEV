package fr.dd.jarvis.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
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

private const val MAX_NOEUDS = 260

private data class PosNoeud(val node: ObsidianWriter.GraphNode, val pos: Offset, val degre: Int, val categorie: String)
private data class CategorieInfo(val nom: String, val pos: Offset, val nbNotes: Int)

/** Résultat complet de la disposition : nœuds positionnés + arêtes + hubs de dossiers, renvoyés ENSEMBLE (plus de
 *  variable globale partagée entre threads/écrans pour faire transiter les arêtes — source d'un bug réel : rien ne
 *  garantissait qu'elle ne soit pas réécrite entre le calcul et sa lecture si deux écrans tournaient en même temps). */
private data class Disposition(val noeuds: List<PosNoeud>, val aretes: List<ObsidianWriter.GraphEdge>, val categories: List<CategorieInfo>)

private fun racineDossier(folder: String): String {
    val r = folder.substringBefore('/').trim()
    return r.ifBlank { "Racine" }
}

private fun calculerDisposition(data: ObsidianWriter.GraphData): Disposition {
    val degres = HashMap<String, Int>()
    data.edges.forEach { e -> degres[e.from] = (degres[e.from] ?: 0) + 1; degres[e.to] = (degres[e.to] ?: 0) + 1 }
    val tries = data.nodes.sortedByDescending { degres[it.id] ?: 0 }.take(MAX_NOEUDS)
    val idsGardes = tries.map { it.id }.toHashSet()
    val edges = data.edges.filter { it.from in idsGardes && it.to in idsGardes }

    val n = tries.size
    if (n == 0) return Disposition(emptyList(), emptyList(), emptyList())

    // Regroupement par dossier racine (catégorie) : chaque catégorie obtient un hub fixe, placé en cercle autour du
    // centre — c'est CE positionnement, pas la simulation de forces, qui organise tout le graphe par dossier.
    val parCategorie = tries.groupBy { racineDossier(it.folder) }
    val categories = parCategorie.keys.sortedByDescending { parCategorie[it]!!.size }
    val nbCat = categories.size.coerceAtLeast(1)
    // Rayon du cercle de hubs : croît avec le nombre de catégories pour garder de la place entre elles, et avec le
    // nombre de notes (un coffre avec beaucoup de notes a des grappes plus larges, qui ont besoin de plus d'écart).
    val rayonCategories = 230f + nbCat * 34f + min(n, 150) * 1.1f
    val posCategorie = HashMap<String, Offset>()
    categories.forEachIndexed { i, cat ->
        val angle = (i.toFloat() / nbCat) * 2 * Math.PI.toFloat()
        posCategorie[cat] = Offset(cos(angle) * rayonCategories, sin(angle) * rayonCategories)
    }

    // Sous-dossiers (chemin complet) : une légère attraction mutuelle entre notes du MÊME sous-dossier, en plus de
    // la gravité vers le hub de leur catégorie — ça fait émerger des petites grappes de sous-dossier à l'intérieur
    // de la grappe de leur dossier parent, sans avoir besoin de dessiner un second niveau de hubs.
    val parSousDossier = tries.filter { it.folder.isNotBlank() }.groupBy { it.folder }.filterValues { it.size > 1 }

    val rnd = Random(7)
    val pos = HashMap<String, Offset>()
    val catDe = HashMap<String, String>()
    tries.forEach { node ->
        val cat = racineDossier(node.folder)
        catDe[node.id] = cat
        val hub = posCategorie[cat]!!
        val tailleCat = parCategorie[cat]!!.size
        val etalement = 36f + min(tailleCat, 35) * 5f
        val angle = rnd.nextFloat() * 2 * Math.PI.toFloat()
        val rayon = 26f + rnd.nextFloat() * etalement
        pos[node.id] = Offset(hub.x + cos(angle) * rayon, hub.y + sin(angle) * rayon)
    }

    val k = 44f
    val disp = HashMap<String, Offset>()
    repeat(130) { iter ->
        tries.forEach { disp[it.id] = Offset.Zero }
        // Répulsion : seulement entre notes de la MÊME catégorie — nettement moins cher que la répulsion globale
        // d'avant (O(n²) sur TOUT le graphe), et ça garde chaque grappe compacte sans être perturbée par les autres,
        // puisque les hubs sont de toute façon fixes (pas besoin de répulsion inter-catégories).
        parCategorie.values.forEach { groupe ->
            for (i in groupe.indices) {
                val a = groupe[i].id; val pa = pos[a]!!
                for (j in i + 1 until groupe.size) {
                    val b = groupe[j].id; val pb = pos[b]!!
                    var dx = pa.x - pb.x; var dy = pa.y - pb.y
                    var dist = hypot(dx, dy); if (dist < 0.01f) dist = 0.01f
                    val force = (k * k) / dist
                    val fx = dx / dist * force; val fy = dy / dist * force
                    disp[a] = disp[a]!!.let { Offset(it.x + fx, it.y + fy) }
                    disp[b] = disp[b]!!.let { Offset(it.x - fx, it.y - fy) }
                }
            }
        }
        // Arêtes réelles (liens « [[...]] ») : peuvent traverser les catégories, c'est voulu — ça garde les vrais
        // liens visibles même entre deux dossiers différents.
        for (e in edges) {
            val pa = pos[e.from] ?: continue; val pb = pos[e.to] ?: continue
            var dx = pa.x - pb.x; var dy = pa.y - pb.y
            var dist = hypot(dx, dy); if (dist < 0.01f) dist = 0.01f
            val force = (dist * dist) / (k * 4f)
            val fx = dx / dist * force; val fy = dy / dist * force
            disp[e.from] = disp[e.from]!!.let { Offset(it.x - fx, it.y - fy) }
            disp[e.to] = disp[e.to]!!.let { Offset(it.x + fx, it.y + fy) }
        }
        // Gravité vers le hub de SA PROPRE catégorie (pas vers l'origine) : c'est ce qui organise tout en grappes
        // par dossier au lieu d'un nuage global façon confettis dispersés sur tout l'écran.
        tries.forEach { node ->
            val hub = posCategorie[catDe[node.id]]!!
            val p = pos[node.id]!!
            disp[node.id] = disp[node.id]!!.let { Offset(it.x - (p.x - hub.x) * 0.07f, it.y - (p.y - hub.y) * 0.07f) }
        }
        // Attraction douce vers le centre de masse du sous-dossier (chemin complet).
        parSousDossier.values.forEach { groupe ->
            var sx = 0f; var sy = 0f
            groupe.forEach { val p = pos[it.id]!!; sx += p.x; sy += p.y }
            val cxg = sx / groupe.size; val cyg = sy / groupe.size
            groupe.forEach { node ->
                val p = pos[node.id]!!
                disp[node.id] = disp[node.id]!!.let { Offset(it.x + (cxg - p.x) * 0.05f, it.y + (cyg - p.y) * 0.05f) }
            }
        }
        val temp = 20f * (1f - iter / 130f)
        tries.forEach { node ->
            val d = disp[node.id]!!
            val dist = hypot(d.x, d.y).coerceAtLeast(0.01f)
            val lim = min(dist, temp)
            pos[node.id] = pos[node.id]!!.let { Offset(it.x + d.x / dist * lim, it.y + d.y / dist * lim) }
        }
    }
    // Passe de « décollage » (par catégorie, puisque les catégories sont déjà séparées par construction) : écarte
    // toute paire plus proche que la somme de leurs rayons + marge généreuse (le rayon réellement affiché à l'écran
    // est ce rayon « monde » multiplié par densité ET zoom, inconnus ici — marge large pour rester correct malgré ça).
    fun rayonMonde(id: String) = 20f + min(degres[id] ?: 0, 10) * 3f
    repeat(60) {
        parCategorie.values.forEach { groupe ->
            for (i in groupe.indices) {
                val a = groupe[i].id; val pa = pos[a]!!
                for (j in i + 1 until groupe.size) {
                    val b = groupe[j].id; val pb = pos[b]!!
                    val dx = pa.x - pb.x; val dy = pa.y - pb.y
                    var dist = hypot(dx, dy); if (dist < 0.01f) dist = 0.01f
                    val minDist = rayonMonde(a) + rayonMonde(b) + 50f
                    if (dist < minDist) {
                        val push = (minDist - dist) / 2f
                        val ux = dx / dist; val uy = dy / dist
                        pos[a] = Offset(pa.x + ux * push, pa.y + uy * push)
                        pos[b] = Offset(pb.x - ux * push, pb.y - uy * push)
                    }
                }
            }
        }
    }
    val noeuds = tries.map { PosNoeud(it, pos[it.id]!!, degres[it.id] ?: 0, catDe[it.id]!!) }
    val catInfos = categories.map { CategorieInfo(it, posCategorie[it]!!, parCategorie[it]!!.size) }
    return Disposition(noeuds, edges, catInfos)
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
            colors = listOf(Color(0xFF2A1452), Color(0xFF140A2E), Color(0xFF07040F)),
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
    val accent = LocalAccent.current
    var chargement by remember { mutableStateOf(true) }
    var noeuds by remember { mutableStateOf<List<PosNoeud>>(emptyList()) }
    var aretes by remember { mutableStateOf<List<ObsidianWriter.GraphEdge>>(emptyList()) }
    var categories by remember { mutableStateOf<List<CategorieInfo>>(emptyList()) }
    var totalNotes by remember { mutableStateOf(0) }
    var version by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf<ObsidianWriter.GraphNode?>(null) }
    var apercu by remember { mutableStateOf("") }

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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("GRAPHE OBSIDIAN", color = accent, fontFamily = FontFamily.Monospace, fontSize = 15.sp, letterSpacing = 2.sp)
                Text(
                    if (chargement) "Calcul de la disposition…" else "$totalNotes notes · ${aretes.size} liens · ${categories.size} dossiers" + (if (totalNotes > MAX_NOEUDS) " (affiche les $MAX_NOEUDS plus connectées)" else ""),
                    color = HudDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
            Text(
                "⟲ Recalculer", color = accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(6.dp).pointerInput(Unit) { detectTapGestures { version++ } }
            )
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
                    val rayonCerveau = (46f * density * scale).coerceIn(22f * density, 90f * density)

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
                            color = Color(0xFFBFA6FF).copy(alpha = 0.75f),
                            start = Offset(cx + pa.x * scale, cy + pa.y * scale),
                            end = Offset(cx + pb.x * scale, cy + pb.y * scale),
                            strokeWidth = 2.2f * density * scale.coerceAtLeast(0.4f)
                        )
                    }
                    // Passe 3 : cerveau central (virtuel), toujours présent.
                    drawCircle(color = accent.copy(alpha = 0.18f * pulse), radius = rayonCerveau * (1.7f + 0.3f * pulse), center = Offset(cx, cy))
                    drawCircle(color = accent.copy(alpha = 0.95f), radius = rayonCerveau, center = Offset(cx, cy))
                    drawCircle(color = Color.White.copy(alpha = 0.5f), radius = rayonCerveau, center = Offset(cx, cy), style = Stroke(width = 1.6f * density))
                    repeat(8) { i ->
                        val a = (i / 8f) * 2 * Math.PI.toFloat()
                        val r1 = rayonCerveau * 1.05f; val r2 = rayonCerveau * (1.35f + 0.15f * pulse)
                        drawLine(
                            color = accent.copy(alpha = 0.35f * pulse),
                            start = Offset(cx + cos(a) * r1, cy + sin(a) * r1),
                            end = Offset(cx + cos(a) * r2, cy + sin(a) * r2),
                            strokeWidth = 1.4f * density
                        )
                    }
                    // Passe 4 : hubs de dossier (cercle coloré + icône + halo).
                    for (cat in categories) {
                        val hcol = couleurCategorie(cat.nom, accent)
                        val sx = cx + cat.pos.x * scale; val sy = cy + cat.pos.y * scale
                        val r = rayonHubEcran(cat)
                        drawCircle(color = hcol.copy(alpha = 0.2f * pulse), radius = r * (1.4f + 0.25f * pulse), center = Offset(sx, sy))
                        drawCircle(color = hcol.copy(alpha = 0.92f), radius = r, center = Offset(sx, sy))
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
                            color = if (pn.node.ghost) couleur.copy(alpha = 0.4f) else if (estHub) couleur else couleur.copy(alpha = 0.82f),
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
                        val label = pn.node.label.take(22)
                        val taille = 13f * scale.coerceIn(0.9f, 2.2f)
                        val style = TextStyle(
                            color = if (pn.node.ghost) Color.LightGray else Color.White,
                            fontSize = taille.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal, textAlign = TextAlign.Center
                        )
                        dessinerPlaqueEtTexte(mesureurTexte, label, style, Offset(sx, sy + rayon + 8f * density), density)
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
            containerColor = Color(0xFF0B1424), titleContentColor = accent, textContentColor = HudText,
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
