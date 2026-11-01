package fr.dd.jarvis.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.ObsidianWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Vue « graphe » du coffre Obsidian : chaque note est un nœud, chaque lien « [[...]] » une arête —
 *  dans le style d'un graphe de connaissances (orbe central pour les notes très liées, nœuds satellites
 *  reliés par des traits, comme la vue graphe native d'Obsidian). Disposition calculée une fois (force-directed
 *  léger) puis pan/zoom/tap à la main pour explorer. */

private const val MAX_NOEUDS = 260

private data class PosNoeud(val node: ObsidianWriter.GraphNode, val pos: Offset, val degre: Int)

/** Résultat complet de la disposition : nœuds positionnés + arêtes correspondantes, renvoyés ENSEMBLE
 *  (plus de variable globale partagée entre threads/écrans pour faire transiter les arêtes — source d'un bug
 *  réel : rien ne garantissait qu'elle ne soit pas réécrite entre le calcul et sa lecture si GraphBackdrop et
 *  GraphScreen tournaient en même temps, par exemple pendant une transition d'écran). */
private data class Disposition(val noeuds: List<PosNoeud>, val aretes: List<ObsidianWriter.GraphEdge>)

private fun calculerDisposition(data: ObsidianWriter.GraphData): Disposition {
    val degres = HashMap<String, Int>()
    data.edges.forEach { e -> degres[e.from] = (degres[e.from] ?: 0) + 1; degres[e.to] = (degres[e.to] ?: 0) + 1 }
    val tries = data.nodes.sortedByDescending { degres[it.id] ?: 0 }.take(MAX_NOEUDS)
    val idsGardes = tries.map { it.id }.toHashSet()
    val edges = data.edges.filter { it.from in idsGardes && it.to in idsGardes }

    val n = tries.size
    if (n == 0) return Disposition(emptyList(), emptyList())
    // Avec peu de notes (petit coffre), un k trop faible entassait tout au centre, illisible une fois dézoomé pour
    // tout faire tenir à l'écran. 170 donne un graphe nettement plus aéré, surtout quand n est petit (cas courant ici).
    val k = 170f / kotlin.math.sqrt(n.toFloat().coerceAtLeast(1f))
    val rnd = Random(7)
    val pos = HashMap<String, Offset>()
    tries.forEachIndexed { i, node ->
        val angle = (i.toFloat() / n) * 2 * Math.PI.toFloat()
        val rayon = 80f + rnd.nextFloat() * 60f
        pos[node.id] = Offset(cos(angle) * rayon, sin(angle) * rayon)
    }
    val disp = HashMap<String, Offset>()
    repeat(140) { iter ->
        tries.forEach { disp[it.id] = Offset.Zero }
        for (i in tries.indices) {
            val a = tries[i].id
            val pa = pos[a]!!
            for (j in i + 1 until tries.size) {
                val b = tries[j].id
                val pb = pos[b]!!
                var dx = pa.x - pb.x; var dy = pa.y - pb.y
                var dist = hypot(dx, dy); if (dist < 0.01f) dist = 0.01f
                val force = (k * k) / dist
                val fx = dx / dist * force; val fy = dy / dist * force
                disp[a] = disp[a]!!.let { Offset(it.x + fx, it.y + fy) }
                disp[b] = disp[b]!!.let { Offset(it.x - fx, it.y - fy) }
            }
        }
        for (e in edges) {
            val pa = pos[e.from] ?: continue; val pb = pos[e.to] ?: continue
            var dx = pa.x - pb.x; var dy = pa.y - pb.y
            var dist = hypot(dx, dy); if (dist < 0.01f) dist = 0.01f
            val force = (dist * dist) / (k * 3f)
            val fx = dx / dist * force; val fy = dy / dist * force
            disp[e.from] = disp[e.from]!!.let { Offset(it.x - fx, it.y - fy) }
            disp[e.to] = disp[e.to]!!.let { Offset(it.x + fx, it.y + fy) }
        }
        // Gravité légère vers le centre : sans ça, une note isolée (sans aucun lien) ne subit QUE la répulsion et
        // dérive indéfiniment loin du reste du graphe au fil des itérations — un coffre avec beaucoup de notes peu
        // liées se retrouvait éclaté sur une zone énorme, la plupart des notes hors-écran même dézoomé à fond.
        tries.forEach { node -> val p = pos[node.id]!!; disp[node.id] = disp[node.id]!!.let { Offset(it.x - p.x * 0.02f, it.y - p.y * 0.02f) } }
        val temp = 24f * (1f - iter / 140f)
        tries.forEach { node ->
            val d = disp[node.id]!!
            val dist = hypot(d.x, d.y).coerceAtLeast(0.01f)
            val lim = min(dist, temp)
            pos[node.id] = pos[node.id]!!.let { Offset(it.x + d.x / dist * lim, it.y + d.y / dist * lim) }
        }
    }
    // Passe de « décollage » : la simulation ci-dessus laisse parfois plusieurs nœuds très liés au même hub
    // quasiment superposés (visible en grappe illisible). On écarte ici, sans y toucher sinon, toute paire de
    // nœuds plus proches que la somme de leurs rayons + marge, jusqu'à ce qu'il n'y ait plus de chevauchement.
    // Marge volontairement généreuse : le rayon réellement affiché à l'écran est ce rayon « monde » multiplié par la
    // densité de l'écran ET par le zoom auto-adapté (calculés plus tard, inconnus ici) — avec un petit écart on se
    // retrouvait donc avec des nœuds dont le DISQUE AFFICHÉ se chevauchait quand même une fois ces deux facteurs
    // appliqués (visible en grappe façon « fleur » autour des notes très liées, qui cachait aussi les traits de lien).
    fun rayonMonde(id: String) = 22f + min(degres[id] ?: 0, 10) * 3.2f
    repeat(80) {
        for (i in tries.indices) {
            val a = tries[i].id; val pa = pos[a]!!
            for (j in i + 1 until tries.size) {
                val b = tries[j].id; val pb = pos[b]!!
                val dx = pa.x - pb.x; val dy = pa.y - pb.y
                var dist = hypot(dx, dy); if (dist < 0.01f) dist = 0.01f
                val minDist = rayonMonde(a) + rayonMonde(b) + 55f
                if (dist < minDist) {
                    val push = (minDist - dist) / 2f
                    val ux = dx / dist; val uy = dy / dist
                    pos[a] = Offset(pa.x + ux * push, pa.y + uy * push)
                    pos[b] = Offset(pb.x - ux * push, pb.y - uy * push)
                }
            }
        }
    }
    return Disposition(tries.map { PosNoeud(it, pos[it.id]!!, degres[it.id] ?: 0) }, edges)
}

/** Couleur stable par dossier (premier niveau du chemin), pour repérer visuellement les groupes de notes
 *  (Contacts, Société/Devis, Journal…) dans le graphe — une teinte dérivée du nom, toujours la même. */
private fun couleurDossier(folder: String, accent: Color): Color {
    val racine = folder.substringBefore('/').trim()
    if (racine.isBlank()) return accent
    val h = (racine.lowercase().fold(0) { acc, c -> acc * 31 + c.code } and 0xFFFFFF)
    val hue = (h % 360).toFloat()
    return Color.hsv(hue, 0.55f, 1f)
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
        totalNotes = resultat.second
        chargement = false
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Le « cerveau » du graphe : la note la plus liée, mise en avant par un halo pulsant (repère visuel clair,
    // même compact), à la place de l'ancienne orbe centrale qui disparaissait complètement dans ce nouvel écran.
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "hubPulse").animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse
        ), label = "hubPulse"
    )
    val hubId = remember(noeuds) { noeuds.maxByOrNull { it.degre }?.takeIf { it.degre > 0 }?.node?.id }
    // API Compose standard pour le texte (TextMeasurer + DrawScope.drawText), à la place de l'ancien
    // android.graphics.Paint via nativeCanvas : ce dernier restait invisible sur certains rendus matériels
    // (constaté même sur des nœuds totalement isolés, sans rien à proximité pour les recouvrir — donc pas
    // un simple souci de chevauchement mais bien un problème du chemin de rendu nativeCanvas lui-même).
    val mesureurTexte = rememberTextMeasurer()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("GRAPHE OBSIDIAN", color = accent, fontFamily = FontFamily.Monospace, fontSize = 15.sp, letterSpacing = 2.sp)
                Text(
                    if (chargement) "Calcul de la disposition…" else "$totalNotes notes · ${aretes.size} liens" + (if (totalNotes > MAX_NOEUDS) " (affiche les $MAX_NOEUDS plus connectées)" else ""),
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
            // Cadrage automatique : dès que la disposition est calculée, on choisit le zoom et le centrage pour que le
            // graphe entier tienne bien dans l'écran (avant : zoom fixe à 1, ce qui laissait les nœuds minuscules et
            // perdus dans un immense vide si le graphe calculé était plus petit — ou plus grand — que l'écran).
            LaunchedEffect(noeuds) {
                if (noeuds.isNotEmpty()) {
                    val minX = noeuds.minOf { it.pos.x }; val maxX = noeuds.maxOf { it.pos.x }
                    val minY = noeuds.minOf { it.pos.y }; val maxY = noeuds.maxOf { it.pos.y }
                    val largeur = (maxX - minX).coerceAtLeast(40f); val hauteur = (maxY - minY).coerceAtLeast(40f)
                    val canvasW = with(localDensity) { maxWidth.toPx() }; val canvasH = with(localDensity) { maxHeight.toPx() }
                    if (canvasW > 0f && canvasH > 0f) {
                        // Doit pouvoir descendre bien en dessous de 1 pour qu'un graphe large (notes peu reliées entre
                        // elles) tienne entièrement à l'écran dès l'ouverture, plutôt que de forcer un zoom minimum qui
                        // laisse la majorité des notes hors-écran (il fallait alors les chercher en déplaçant la vue).
                        // Plafond aligné sur celui du pincer-zoomer (4f) : un cadrage initial plus zoomé que ce que
                        // l'utilisateur peut ensuite atteindre lui-même n'a pas de sens, et rendait les cercles
                        // disproportionnés sur un petit coffre (peu de notes = petite zone = fort zoom auto).
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

                    // Rayon plafonné en pixels écran (pas seulement en unités « monde ») : quel que soit le résultat du
                    // calcul de disposition ou du zoom auto-adapté, un nœud ne peut plus jamais devenir assez gros pour
                    // avaler ses voisins (et donc cacher leurs traits de lien et leur texte dessous) — filet de sécurité
                    // indépendant de la justesse du calcul de décollage plus haut.
                    fun rayonEcran(pn: PosNoeud) = ((10f + min(pn.degre, 10) * 2.2f) * density * scale).coerceIn(5f * density, 34f * density)

                    // Tailles en fonction de la densité de l'écran (`density`, ici le float du DrawScope) : avant, des tailles
                    // fixes en pixels bruts (rayon 6, trait 1.2…) étaient quasi invisibles sur un écran haute résolution
                    // (ex. 3 px = 1 dp), d'où des nœuds réduits à des points perdus dans le vide.
                    // Passe 1/3 : les traits de lien, tout en dessous.
                    val lignePaint = Color(0x9990B8FF)
                    for (a in aretes) {
                        val pa = parId[a.from]?.pos ?: continue
                        val pb = parId[a.to]?.pos ?: continue
                        drawLine(
                            color = lignePaint,
                            start = Offset(cx + pa.x * scale, cy + pa.y * scale),
                            end = Offset(cx + pb.x * scale, cy + pb.y * scale),
                            strokeWidth = 2.2f * density * scale.coerceAtLeast(0.4f)
                        )
                    }
                    // Passe 2/3 : les cercles des nœuds (et leur halo), par-dessus les traits.
                    for (pn in noeuds) {
                        val sx = cx + pn.pos.x * scale
                        val sy = cy + pn.pos.y * scale
                        val rayon = rayonEcran(pn)
                        val estHub = pn.degre >= 5
                        val estCerveau = pn.node.id == hubId
                        val couleur = if (pn.node.ghost) Color(0xFF999999) else couleurDossier(pn.node.folder, accent)
                        if (estCerveau) drawCircle(
                            color = couleur.copy(alpha = 0.22f * pulse), radius = rayon * (1.5f + 0.3f * pulse), center = Offset(sx, sy)
                        )
                        drawCircle(
                            color = if (pn.node.ghost) couleur.copy(alpha = 0.4f) else if (estHub) couleur else couleur.copy(alpha = 0.8f),
                            radius = rayon,
                            center = Offset(sx, sy)
                        )
                        if (estHub) drawCircle(
                            color = couleur.copy(alpha = 0.3f), radius = rayon * 2f, center = Offset(sx, sy),
                            style = Stroke(width = 1.6f * density * scale.coerceAtLeast(0.3f))
                        )
                    }
                    // Passe 3/3 : TOUS les textes en dernier, par-dessus absolument tout — aucun cercle dessiné après
                    // ne peut donc plus jamais recouvrir le label d'un nœud dessiné avant lui.
                    for (pn in noeuds) {
                        val sx = cx + pn.pos.x * scale
                        val sy = cy + pn.pos.y * scale
                        val rayon = rayonEcran(pn)
                        val estCerveau = pn.node.id == hubId
                        val label = pn.node.label.take(22)
                        val taille = (13.5f * scale.coerceIn(0.9f, 2.2f)) * (if (estCerveau) 1.15f else 1f)
                        val style = androidx.compose.ui.text.TextStyle(
                            color = if (pn.node.ghost) Color.LightGray else Color.White,
                            fontSize = taille.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (estCerveau) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        val resultatTexte = mesureurTexte.measure(label, style)
                        val texteY = sy + rayon + 10f * density
                        val topLeft = Offset(sx - resultatTexte.size.width / 2f, texteY)
                        drawRoundRect(
                            color = Color(0xB9080614),
                            topLeft = Offset(topLeft.x - 6f * density, topLeft.y - 3f * density),
                            size = androidx.compose.ui.geometry.Size(
                                resultatTexte.size.width + 12f * density,
                                resultatTexte.size.height + 6f * density
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f * density, 6f * density)
                        )
                        drawText(resultatTexte, topLeft = topLeft)
                    }
                }
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
