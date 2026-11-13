package fr.dd.jarvis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Grille 3 × 3 où l'on TRACE son schéma de déverrouillage au doigt, comme sur l'écran de verrouillage — au lieu de taper
 * les numéros des points. Comme Android, un point survolé entre deux points (1 → 3 passe par 2) est ajouté
 * automatiquement s'il n'est pas encore pris : le schéma enregistré est exactement celui que le téléphone attend.
 * [onTrace] reçoit les points dans l'ordre (« 1596… », 1 = en haut à gauche), ou "" si le tracé a moins de 4 points.
 */
@Composable
fun SchemaDessin(initial: String, visible: Boolean, onTrace: (String) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val rappel by rememberUpdatedState(onTrace)
    val points = remember { mutableStateListOf<Int>().apply { if (visible) addAll(initial.mapNotNull { (it - '0').takeIf { d -> d in 1..9 } }) } }
    var doigt by remember { mutableStateOf<Offset?>(null) }
    Box(modifier.size(260.dp).background(HudBg.copy(alpha = 0.6f), HudShape)
        .pointerInput(Unit) {
            val cote = size.width.toFloat()
            fun centre(d: Int) = Offset(((d - 1) % 3 * 2 + 1) * cote / 6f, ((d - 1) / 3 * 2 + 1) * cote / 6f)
            fun toucher(o: Offset): Int? = (1..9).firstOrNull { (centre(it) - o).getDistance() < cote / 9f }
            fun ajouter(d: Int) {
                if (d in points) return
                points.lastOrNull()?.let { a -> SchemaDessinLogique.milieu(a, d)?.takeIf { it !in points }?.let { points.add(it) } }
                points.add(d)
            }
            detectDragGestures(
                onDragStart = { o -> points.clear(); doigt = o; toucher(o)?.let { ajouter(it) } },
                onDrag = { change, _ -> doigt = change.position; toucher(change.position)?.let { ajouter(it) } },
                onDragEnd = { doigt = null; rappel(if (points.size >= 4) points.joinToString("") else "") },
                onDragCancel = { doigt = null; points.clear(); rappel("") },
            )
        }) {
        Canvas(Modifier.size(260.dp)) {
            val cote = size.width
            fun centre(d: Int) = Offset(((d - 1) % 3 * 2 + 1) * cote / 6f, ((d - 1) / 3 * 2 + 1) * cote / 6f)
            val trait = cote / 40f
            points.zipWithNext().forEach { (a, b) -> drawLine(accent, centre(a), centre(b), strokeWidth = trait, cap = StrokeCap.Round) }
            val dernier = points.lastOrNull()
            val d = doigt
            if (dernier != null && d != null) drawLine(accent.copy(alpha = 0.5f), centre(dernier), d, strokeWidth = trait, cap = StrokeCap.Round)
            for (i in 1..9) {
                val pris = i in points
                drawCircle(if (pris) accent else HudDim, radius = if (pris) cote / 26f else cote / 34f, center = centre(i))
                if (pris) drawCircle(accent.copy(alpha = 0.35f), radius = cote / 13f, center = centre(i), style = Stroke(width = cote / 120f))
            }
        }
    }
}

/** Règles du schéma Android, sans interface (testables). */
object SchemaDessinLogique {
    /** Point situé exactement entre a et b (1 et 3 → 2, 1 et 9 → 5, 2 et 8 → 5…), ou null. */
    fun milieu(a: Int, b: Int): Int? {
        val (ra, ca) = (a - 1) / 3 to (a - 1) % 3
        val (rb, cb) = (b - 1) / 3 to (b - 1) % 3
        if ((ra + rb) % 2 != 0 || (ca + cb) % 2 != 0) return null
        val m = (ra + rb) / 2 * 3 + (ca + cb) / 2 + 1
        return m.takeIf { it != a && it != b }
    }

    /** Suite de points complétée comme le fait Android (points survolés ajoutés). */
    fun completer(points: List<Int>): List<Int> {
        val out = mutableListOf<Int>()
        for (d in points) {
            if (d in out) continue
            out.lastOrNull()?.let { a -> milieu(a, d)?.takeIf { it !in out }?.let { out += it } }
            out += d
        }
        return out
    }
}
