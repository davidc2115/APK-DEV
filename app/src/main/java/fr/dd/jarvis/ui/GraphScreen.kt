package fr.dd.jarvis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
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

private fun calculerDisposition(data: ObsidianWriter.GraphData): List<PosNoeud> {
    val degres = HashMap<String, Int>()
    data.edges.forEach { e -> degres[e.from] = (degres[e.from] ?: 0) + 1; degres[e.to] = (degres[e.to] ?: 0) + 1 }
    val tries = data.nodes.sortedByDescending { degres[it.id] ?: 0 }.take(MAX_NOEUDS)
    val idsGardes = tries.map { it.id }.toHashSet()
    val edges = data.edges.filter { it.from in idsGardes && it.to in idsGardes }

    val n = tries.size
    if (n == 0) return emptyList()
    val k = 220f / kotlin.math.sqrt(n.toFloat().coerceAtLeast(1f))
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
        val temp = 24f * (1f - iter / 140f)
        tries.forEach { node ->
            val d = disp[node.id]!!
            val dist = hypot(d.x, d.y).coerceAtLeast(0.01f)
            val lim = min(dist, temp)
            pos[node.id] = pos[node.id]!!.let { Offset(it.x + d.x / dist * lim, it.y + d.y / dist * lim) }
        }
    }
    return tries.map { PosNoeud(it, pos[it.id]!!, degres[it.id] ?: 0) }
        .also { l -> edgesCache = edges }
}

// Rempli par calculerDisposition() juste avant le retour ; lu par le Composable pour tracer les arêtes.
private var edgesCache: List<ObsidianWriter.GraphEdge> = emptyList()

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
            val dispo = calculerDisposition(data)
            Triple(dispo, edgesCache, data.nodes.size)
        }
        noeuds = resultat.first
        aretes = resultat.second
        totalNotes = resultat.third
        chargement = false
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

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

        Box(Modifier.weight(1f).fillMaxSize()) {
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

                    val lignePaint = Color(0x3390B8FF)
                    for (a in aretes) {
                        val pa = parId[a.from]?.pos ?: continue
                        val pb = parId[a.to]?.pos ?: continue
                        drawLine(
                            color = lignePaint,
                            start = Offset(cx + pa.x * scale, cy + pa.y * scale),
                            end = Offset(cx + pb.x * scale, cy + pb.y * scale),
                            strokeWidth = 1.2f * scale.coerceAtLeast(0.4f)
                        )
                    }
                    for (pn in noeuds) {
                        val sx = cx + pn.pos.x * scale
                        val sy = cy + pn.pos.y * scale
                        val rayon = (6f + min(pn.degre, 10) * 1.6f) * scale
                        val estHub = pn.degre >= 5
                        drawCircle(
                            color = if (pn.node.ghost) Color(0x55777777) else if (estHub) accent else accent.copy(alpha = 0.55f),
                            radius = rayon,
                            center = Offset(sx, sy)
                        )
                        if (estHub) drawCircle(
                            color = accent.copy(alpha = 0.22f), radius = rayon * 2f, center = Offset(sx, sy),
                            style = Stroke(width = 1.5f * scale.coerceAtLeast(0.3f))
                        )
                        if (scale > 0.6f) {
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = android.graphics.Paint().apply {
                                    color = if (pn.node.ghost) android.graphics.Color.GRAY else android.graphics.Color.WHITE
                                    textSize = 11f * scale.coerceIn(0.6f, 1.6f)
                                    isAntiAlias = true
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                                drawText(pn.node.label.take(22), sx, sy + rayon + 13f, paint)
                            }
                        }
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
