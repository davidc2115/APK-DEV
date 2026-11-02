package fr.dd.jarvis.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Palette « obsidienne & néon violet » de l'image de référence. */
object Neon {
    val violet = Color(0xFFB27CFF)
    val violetVif = Color(0xFF9A55FF)
    val violetProfond = Color(0xFF5B23B0)
    val lavande = Color(0xFFE6D6FF)
    val blancViolet = Color(0xFFF4EDFF)
    val sousTitre = Color(0xFFCDB6EE)
    val fondPanneau = Color(0xE60A0614)
    val fondEtiquette = Color(0xF00B0617)
    val obsidienne = Color(0xFF040208)
    val serif = FontFamily.Serif
}

/** Décor de fond fixe (généré une fois) : étoiles, nébuleuses et fissures d'énergie. */
class DecorCosmique(seed: Int = 7) {
    val etoiles: List<Triple<Float, Float, Float>>
    val nebuleuses: List<Triple<Offset, Float, Float>>
    val fissures: List<List<Offset>>

    init {
        val r = Random(seed)
        etoiles = List(130) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
        nebuleuses = List(6) { Triple(Offset(r.nextFloat(), r.nextFloat()), 0.22f + r.nextFloat() * 0.3f, 0.035f + r.nextFloat() * 0.05f) }
        // Fissures d'énergie en périphérie (fractions de l'écran), comme les éclairs violets de l'image.
        fissures = List(7) {
            val bord = r.nextInt(4)
            var p = when (bord) {
                0 -> Offset(r.nextFloat(), 0f); 1 -> Offset(1f, r.nextFloat()); 2 -> Offset(r.nextFloat(), 1f); else -> Offset(0f, r.nextFloat())
            }
            val dir = Offset(0.5f - p.x, 0.5f - p.y).let { d -> val l = hypot(d.x, d.y).coerceAtLeast(0.01f); Offset(d.x / l, d.y / l) }
            val pts = mutableListOf(p)
            repeat(4 + r.nextInt(4)) {
                val ang = atan2(dir.y, dir.x) + (r.nextFloat() - 0.5f) * 1.7f
                p = Offset(p.x + cos(ang) * 0.028f, p.y + sin(ang) * 0.028f)
                pts += p
            }
            pts
        }
    }
}

fun DrawScope.dessinerFondCosmique(decor: DecorCosmique, pulse: Float = 1f) {
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF170A2B), Color(0xFF080410), Color(0xFF020104)),
            center = Offset(size.width * 0.5f, size.height * 0.46f),
            radius = max(size.width, size.height) * 0.8f
        )
    )
    for ((c, r, a) in decor.nebuleuses) {
        val centre = Offset(c.x * size.width, c.y * size.height)
        val rayon = r * max(size.width, size.height)
        drawCircle(Brush.radialGradient(listOf(Neon.violetVif.copy(alpha = a), Color.Transparent), centre, rayon), rayon, centre)
    }
    for ((fx, fy, a) in decor.etoiles) {
        drawCircle(Color.White.copy(alpha = 0.1f + a * 0.5f), (0.4f + a * 1.1f) * density, Offset(fx * size.width, fy * size.height))
    }
    for (f in decor.fissures) {
        val p = Path().apply { f.forEachIndexed { i, o -> val q = Offset(o.x * size.width, o.y * size.height); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) } }
        drawPath(p, Neon.violetVif.copy(alpha = 0.06f * pulse), style = Stroke(5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(p, Neon.violet.copy(alpha = 0.22f * pulse), style = Stroke(0.9f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ------------------------------------------------------------------------------------------------ texte

private fun tailleSp(px: Float, density: Float, fontScale: Float): TextUnit = (px / density / fontScale).sp

/** Texte centré horizontalement sur [centre].x, verticalement sur [centre].y ; réduit si plus large que [largeurMax]. */
fun DrawScope.texteCentre(
    mesureur: TextMeasurer, texte: String, centre: Offset, taillePx: Float, couleur: Color,
    fontScale: Float, gras: Boolean = false, largeurMax: Float = Float.MAX_VALUE, halo: Color? = null,
    famille: FontFamily = Neon.serif, espacement: Float = 0f
) {
    if (texte.isBlank() || taillePx < 2f) return
    var px = taillePx
    var style = TextStyle(
        color = couleur, fontSize = tailleSp(px, density, fontScale), fontFamily = famille,
        fontWeight = if (gras) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
        letterSpacing = if (espacement != 0f) tailleSp(espacement * px, density, fontScale) else TextUnit.Unspecified
    )
    var res = mesureur.measure(texte, style, softWrap = false, maxLines = 1)
    if (res.size.width > largeurMax && largeurMax > 0f) {
        px *= (largeurMax / res.size.width).coerceAtLeast(0.55f)
        style = style.copy(fontSize = tailleSp(px, density, fontScale))
        res = mesureur.measure(texte, style, softWrap = false, maxLines = 1)
    }
    val tl = Offset(centre.x - res.size.width / 2f, centre.y - res.size.height / 2f)
    if (halo != null) {
        drawCircle(
            Brush.radialGradient(listOf(halo.copy(alpha = 0.35f), Color.Transparent), centre, res.size.width * 0.6f),
            res.size.width * 0.6f, centre
        )
    }
    drawText(res, topLeft = tl)
}

// ------------------------------------------------------------------------------------------------ icônes

/** Icônes vectorielles simples (aucune police d'icônes), remplies en dégradé violet comme sur l'image. */
fun DrawScope.dessinerIcone(icone: Icone, c: Offset, t: Float, couleur: Color = Neon.violet, alpha: Float = 1f) {
    val col = couleur.copy(alpha = alpha)
    val remplissage = Brush.verticalGradient(listOf(Neon.lavande.copy(alpha = alpha), col), c.y - t / 2f, c.y + t / 2f)
    val trait = Stroke(max(t * 0.09f, 1.2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val h = t / 2f
    when (icone) {
        Icone.DOSSIER -> {
            val p = Path().apply {
                moveTo(c.x - h, c.y - h * 0.62f); lineTo(c.x - h * 0.2f, c.y - h * 0.62f); lineTo(c.x, c.y - h * 0.4f)
                lineTo(c.x + h, c.y - h * 0.4f); lineTo(c.x + h, c.y + h * 0.7f); lineTo(c.x - h, c.y + h * 0.7f); close()
            }
            drawPath(p, remplissage)
            drawRect(Neon.lavande.copy(alpha = 0.35f * alpha), Offset(c.x - h, c.y - h * 0.25f), Size(t, h * 0.08f))
        }
        Icone.PERSONNE -> {
            drawCircle(remplissage, h * 0.42f, Offset(c.x, c.y - h * 0.32f))
            val p = Path().apply {
                moveTo(c.x - h * 0.85f, c.y + h * 0.8f)
                cubicTo(c.x - h * 0.85f, c.y + h * 0.05f, c.x + h * 0.85f, c.y + h * 0.05f, c.x + h * 0.85f, c.y + h * 0.8f); close()
            }
            drawPath(p, remplissage)
        }
        Icone.TRAVAIL -> {
            drawRoundRect(remplissage, Offset(c.x - h, c.y - h * 0.45f), Size(t, h * 1.3f), CornerRadius(h * 0.15f))
            drawRoundRect(col, Offset(c.x - h * 0.35f, c.y - h * 0.78f), Size(h * 0.7f, h * 0.4f), CornerRadius(h * 0.1f), style = trait)
            drawRect(Neon.obsidienne.copy(alpha = 0.5f * alpha), Offset(c.x - h, c.y - h * 0.05f), Size(t, h * 0.08f))
            drawRect(Neon.lavande.copy(alpha = alpha), Offset(c.x - h * 0.12f, c.y - h * 0.12f), Size(h * 0.24f, h * 0.22f))
        }
        Icone.RECHERCHE -> {
            drawCircle(col, h * 0.5f, Offset(c.x - h * 0.15f, c.y - h * 0.15f), style = Stroke(max(t * 0.12f, 1.4f)))
            drawLine(col, Offset(c.x + h * 0.22f, c.y + h * 0.22f), Offset(c.x + h * 0.85f, c.y + h * 0.85f), max(t * 0.16f, 1.6f), StrokeCap.Round)
        }
        Icone.ARCHIVE -> {
            drawRoundRect(remplissage, Offset(c.x - h * 0.85f, c.y - h * 0.35f), Size(h * 1.7f, h * 1.15f), CornerRadius(h * 0.12f))
            drawRoundRect(col, Offset(c.x - h, c.y - h * 0.72f), Size(t, h * 0.34f), CornerRadius(h * 0.08f))
            drawCircle(Neon.obsidienne.copy(alpha = 0.7f * alpha), h * 0.28f, Offset(c.x, c.y + h * 0.2f))
            drawCircle(Neon.lavande.copy(alpha = alpha), h * 0.13f, Offset(c.x, c.y + h * 0.2f))
        }
        Icone.TROMBONE -> {
            val p = Path().apply {
                moveTo(c.x + h * 0.35f, c.y - h * 0.15f); lineTo(c.x + h * 0.35f, c.y + h * 0.55f)
                cubicTo(c.x + h * 0.35f, c.y + h * 1.0f, c.x - h * 0.45f, c.y + h * 1.0f, c.x - h * 0.45f, c.y + h * 0.55f)
                lineTo(c.x - h * 0.45f, c.y - h * 0.55f)
                cubicTo(c.x - h * 0.45f, c.y - h * 1.0f, c.x + h * 0.15f, c.y - h * 1.0f, c.x + h * 0.15f, c.y - h * 0.55f)
                lineTo(c.x + h * 0.15f, c.y + h * 0.45f)
                cubicTo(c.x + h * 0.15f, c.y + h * 0.65f, c.x - h * 0.25f, c.y + h * 0.65f, c.x - h * 0.25f, c.y + h * 0.45f)
                lineTo(c.x - h * 0.25f, c.y - h * 0.2f)
            }
            drawPath(p, col, style = Stroke(max(t * 0.1f, 1.3f), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Icone.JOURNAL -> {
            drawRoundRect(remplissage, Offset(c.x - h * 0.75f, c.y - h * 0.9f), Size(h * 1.5f, h * 1.8f), CornerRadius(h * 0.12f))
            drawRect(Neon.obsidienne.copy(alpha = 0.55f * alpha), Offset(c.x - h * 0.55f, c.y - h * 0.9f), Size(h * 0.1f, h * 1.8f))
            for (i in 0..2) drawLine(Neon.obsidienne.copy(alpha = 0.55f * alpha), Offset(c.x - h * 0.3f, c.y - h * 0.4f + i * h * 0.35f), Offset(c.x + h * 0.5f, c.y - h * 0.4f + i * h * 0.35f), max(t * 0.06f, 1f))
        }
        Icone.SOLEIL -> {
            drawCircle(remplissage, h * 0.45f, c)
            for (i in 0 until 8) {
                val a = i * PI.toFloat() / 4f
                drawLine(col, Offset(c.x + cos(a) * h * 0.62f, c.y + sin(a) * h * 0.62f), Offset(c.x + cos(a) * h * 0.95f, c.y + sin(a) * h * 0.95f), max(t * 0.08f, 1.2f), StrokeCap.Round)
            }
        }
        Icone.ETOILE -> {
            val p = Path().apply {
                for (i in 0 until 8) {
                    val a = -PI.toFloat() / 2f + i * PI.toFloat() / 4f
                    val r = if (i % 2 == 0) h else h * 0.32f
                    val q = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
                    if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
                }
                close()
            }
            drawPath(p, remplissage)
        }
        Icone.ETIQUETTE -> {
            val p = Path().apply {
                moveTo(c.x - h * 0.9f, c.y - h * 0.1f); lineTo(c.x - h * 0.1f, c.y - h * 0.9f); lineTo(c.x + h * 0.8f, c.y - h * 0.9f)
                lineTo(c.x + h * 0.8f, c.y); lineTo(c.x, c.y + h * 0.8f); close()
            }
            drawPath(p, remplissage)
            drawCircle(Neon.obsidienne.copy(alpha = 0.8f * alpha), h * 0.15f, Offset(c.x + h * 0.38f, c.y - h * 0.48f))
        }
        Icone.IMAGE -> {
            drawRoundRect(col, Offset(c.x - h, c.y - h * 0.75f), Size(t, h * 1.5f), CornerRadius(h * 0.12f), style = trait)
            val m = Path().apply { moveTo(c.x - h * 0.8f, c.y + h * 0.55f); lineTo(c.x - h * 0.2f, c.y - h * 0.1f); lineTo(c.x + h * 0.15f, c.y + h * 0.25f); lineTo(c.x + h * 0.45f, c.y); lineTo(c.x + h * 0.8f, c.y + h * 0.55f); close() }
            drawPath(m, remplissage)
            drawCircle(Neon.lavande.copy(alpha = alpha), h * 0.15f, Offset(c.x + h * 0.45f, c.y - h * 0.38f))
        }
        Icone.VIDEO -> {
            drawRoundRect(col, Offset(c.x - h, c.y - h * 0.72f), Size(t, h * 1.44f), CornerRadius(h * 0.18f), style = trait)
            val p = Path().apply { moveTo(c.x - h * 0.25f, c.y - h * 0.4f); lineTo(c.x + h * 0.45f, c.y); lineTo(c.x - h * 0.25f, c.y + h * 0.4f); close() }
            drawPath(p, remplissage)
        }
        Icone.AUDIO -> {
            drawCircle(remplissage, h * 0.28f, Offset(c.x - h * 0.45f, c.y + h * 0.6f))
            drawCircle(remplissage, h * 0.28f, Offset(c.x + h * 0.55f, c.y + h * 0.45f))
            drawLine(col, Offset(c.x - h * 0.2f, c.y + h * 0.6f), Offset(c.x - h * 0.2f, c.y - h * 0.65f), max(t * 0.09f, 1.2f))
            drawLine(col, Offset(c.x + h * 0.8f, c.y + h * 0.45f), Offset(c.x + h * 0.8f, c.y - h * 0.85f), max(t * 0.09f, 1.2f))
            drawLine(col, Offset(c.x - h * 0.2f, c.y - h * 0.65f), Offset(c.x + h * 0.8f, c.y - h * 0.85f), max(t * 0.14f, 1.6f))
        }
        Icone.PDF, Icone.NOTE, Icone.FICHIER -> {
            val p = Path().apply {
                moveTo(c.x - h * 0.7f, c.y - h); lineTo(c.x + h * 0.3f, c.y - h); lineTo(c.x + h * 0.7f, c.y - h * 0.6f)
                lineTo(c.x + h * 0.7f, c.y + h); lineTo(c.x - h * 0.7f, c.y + h); close()
            }
            drawPath(p, col, style = trait)
            drawPath(Path().apply { moveTo(c.x + h * 0.3f, c.y - h); lineTo(c.x + h * 0.3f, c.y - h * 0.6f); lineTo(c.x + h * 0.7f, c.y - h * 0.6f) }, col, style = trait)
            if (icone == Icone.PDF) {
                drawRoundRect(remplissage, Offset(c.x - h * 0.85f, c.y - h * 0.05f), Size(h * 1.3f, h * 0.55f), CornerRadius(h * 0.08f))
            } else {
                for (i in 0..2) drawLine(col, Offset(c.x - h * 0.4f, c.y - h * 0.25f + i * h * 0.38f), Offset(c.x + h * 0.4f, c.y - h * 0.25f + i * h * 0.38f), max(t * 0.07f, 1f), StrokeCap.Round)
            }
        }
        Icone.CERVEAU -> dessinerCerveauSeul(c, h, 1f)
    }
}

// ------------------------------------------------------------------------------------------------ cerveau

/** Silhouette d'un hémisphère cérébral (vu de dessus), contour bosselé. side = -1 (gauche) ou 1 (droite). */
private fun cheminHemisphere(c: Offset, s: Float, side: Float): Path {
    val hx = c.x + side * s * 0.5f
    val a = s * 0.5f; val b = s * 0.66f
    return Path().apply {
        val n = 72
        for (i in 0..n) {
            val th = 2f * PI.toFloat() * i / n
            val bosse = 1f + 0.055f * sin(7f * th + side) + 0.03f * sin(13f * th + 2f) + 0.02f * sin(19f * th)
            var x = hx + cos(th) * a * bosse
            val y = c.y + sin(th) * b * bosse
            // Côté de la scissure : bord presque droit.
            if (side * (x - c.x) < s * 0.035f) x = c.x + side * s * 0.035f
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/** Cerveau violet lumineux (deux hémisphères, circonvolutions), centré sur [c], demi-largeur [s]. */
fun DrawScope.dessinerCerveauSeul(c: Offset, s: Float, eclat: Float) {
    drawCircle(Brush.radialGradient(listOf(Neon.violetVif.copy(alpha = 0.55f * eclat), Color.Transparent), c, s * 1.5f), s * 1.5f, c)
    for (side in floatArrayOf(-1f, 1f)) {
        val p = cheminHemisphere(c, s, side)
        drawPath(p, Brush.radialGradient(
            listOf(Color(0xFFE2C8FF), Color(0xFFA066F2), Color(0xFF4A1C8C)),
            Offset(c.x + side * s * 0.45f, c.y - s * 0.2f), s * 0.95f
        ))
        clipPath(p) {
            // Circonvolutions : courbes ondulées plus claires.
            for (j in 0 until 7) {
                val y = c.y - s * 0.6f + j * s * 0.2f
                val q = Path()
                val n = 16
                for (i in 0..n) {
                    val fx = i / n.toFloat()
                    val x = c.x + side * (s * 0.04f + fx * s * 1.0f)
                    val yy = y + sin(fx * 9f + j * 1.7f + side) * s * 0.07f + (fx - 0.5f) * (fx - 0.5f) * s * 0.25f * (if (j < 3) -1f else 1f)
                    if (i == 0) q.moveTo(x, yy) else q.lineTo(x, yy)
                }
                drawPath(q, Neon.blancViolet.copy(alpha = 0.22f * eclat), style = Stroke(s * 0.075f, cap = StrokeCap.Round))
                drawPath(q, Color(0xFF5A2499).copy(alpha = 0.75f), style = Stroke(s * 0.03f, cap = StrokeCap.Round))
            }
        }
        drawPath(p, Neon.lavande.copy(alpha = 0.85f * eclat), style = Stroke(max(s * 0.035f, 1.2f)))
        drawPath(p, Neon.violet.copy(alpha = 0.3f * eclat), style = Stroke(max(s * 0.12f, 3f)))
    }
    drawLine(Neon.blancViolet.copy(alpha = 0.8f * eclat), Offset(c.x, c.y - s * 0.62f), Offset(c.x, c.y + s * 0.6f), max(s * 0.025f, 1f))
}

/** Noyau central : sphère d'obsidienne au liseré néon, cerveau lumineux, « CERVEAU / Graphe Obsidian / ∞ ». */
fun DrawScope.dessinerNoyau(
    c: Offset, r: Float, pulse: Float, mesureur: TextMeasurer, fontScale: Float,
    sphere: Boolean = true, sousTitre: String = "Graphe Obsidian"
) {
    if (sphere) {
        drawCircle(Brush.radialGradient(listOf(Neon.violetVif.copy(alpha = 0.42f * pulse), Neon.violetProfond.copy(alpha = 0.12f), Color.Transparent), c, r * 1.85f), r * 1.85f, c)
        // Vrilles d'énergie qui s'échappent du noyau.
        val rnd = Random(3)
        repeat(16) { i ->
            val a = i * 2f * PI.toFloat() / 16f + rnd.nextFloat() * 0.2f
            var p = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
            val q = Path().apply { moveTo(p.x, p.y) }
            repeat(4) {
                val aa = a + (rnd.nextFloat() - 0.5f) * 0.9f
                p = Offset(p.x + cos(aa) * r * 0.12f, p.y + sin(aa) * r * 0.12f); q.lineTo(p.x, p.y)
            }
            drawPath(q, Neon.violet.copy(alpha = 0.45f * pulse), style = Stroke(max(r * 0.012f, 1f), cap = StrokeCap.Round))
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFF2B1446), Color(0xFF0D0619), Neon.obsidienne), Offset(c.x - r * 0.3f, c.y - r * 0.35f), r * 1.4f), r, c)
        drawCircle(Neon.violetVif.copy(alpha = 0.35f), r * 1.04f, c, style = Stroke(r * 0.09f))
        drawCircle(Neon.lavande.copy(alpha = 0.95f), r, c, style = Stroke(max(r * 0.022f, 1.5f)))
        drawCircle(Neon.violet.copy(alpha = 0.35f), r * 0.93f, c, style = Stroke(max(r * 0.01f, 1f)))
        // Reflet vitreux en haut à gauche.
        drawArc(Color.White.copy(alpha = 0.18f), 200f, 70f, false, Offset(c.x - r * 0.82f, c.y - r * 0.82f), Size(r * 1.64f, r * 1.64f), style = Stroke(r * 0.05f, cap = StrokeCap.Round))
    }
    dessinerCerveauSeul(Offset(c.x, c.y - r * 0.3f), r * 0.5f, pulse)
    texteCentre(mesureur, "CERVEAU", Offset(c.x, c.y + r * 0.27f), r * 0.24f, Neon.blancViolet, fontScale, gras = true, largeurMax = r * 1.6f, espacement = 0.06f)
    texteCentre(mesureur, sousTitre, Offset(c.x, c.y + r * 0.5f), r * 0.135f, Neon.sousTitre, fontScale, largeurMax = r * 1.5f)
    // Symbole infini.
    val iy = c.y + r * 0.72f; val iw = r * 0.16f
    val inf = Path().apply {
        moveTo(c.x, iy)
        cubicTo(c.x + iw * 0.5f, iy - iw * 0.6f, c.x + iw * 1.2f, iy - iw * 0.6f, c.x + iw * 1.2f, iy)
        cubicTo(c.x + iw * 1.2f, iy + iw * 0.6f, c.x + iw * 0.5f, iy + iw * 0.6f, c.x, iy)
        cubicTo(c.x - iw * 0.5f, iy - iw * 0.6f, c.x - iw * 1.2f, iy - iw * 0.6f, c.x - iw * 1.2f, iy)
        cubicTo(c.x - iw * 1.2f, iy + iw * 0.6f, c.x - iw * 0.5f, iy + iw * 0.6f, c.x, iy)
    }
    drawPath(inf, Neon.lavande, style = Stroke(max(r * 0.028f, 1.2f)))
}

// ------------------------------------------------------------------------------------------------ hubs & étiquettes

/** Hub de dossier : disque d'obsidienne, double anneau néon, icône, titre serif et sous-titre. */
fun DrawScope.dessinerHub(
    hub: Hub, c: Offset, r: Float, mesureur: TextMeasurer, fontScale: Float,
    alpha: Float = 1f, actif: Boolean = false, pulse: Float = 1f
) {
    drawCircle(Brush.radialGradient(listOf(Neon.violetVif.copy(alpha = (if (actif) 0.6f else 0.38f) * alpha * pulse), Color.Transparent), c, r * 1.7f), r * 1.7f, c)
    drawCircle(Neon.violet.copy(alpha = 0.45f * alpha), r * 1.13f, c, style = Stroke(max(r * 0.02f, 1f)))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF231238), Color(0xFF0A0513), Neon.obsidienne), Offset(c.x - r * 0.3f, c.y - r * 0.35f), r * 1.35f), r, c, alpha = alpha)
    drawCircle(Neon.violetVif.copy(alpha = 0.4f * alpha), r * 1.02f, c, style = Stroke(r * 0.1f))
    drawCircle((if (actif) Color.White else Neon.lavande).copy(alpha = 0.95f * alpha), r, c, style = Stroke(max(r * 0.04f, 1.4f)))
    drawArc(Color.White.copy(alpha = 0.16f * alpha), 205f, 60f, false, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Size(r * 1.6f, r * 1.6f), style = Stroke(r * 0.05f, cap = StrokeCap.Round))
    dessinerIcone(hub.icone, Offset(c.x, c.y - r * 0.4f), r * 0.5f, Neon.violet, alpha)
    texteCentre(mesureur, hub.titre, Offset(c.x, c.y + r * 0.13f), max(r * 0.31f, 8.5f * density), Neon.blancViolet.copy(alpha = alpha), fontScale, gras = true, largeurMax = r * 1.78f)
    texteCentre(mesureur, hub.sousTitre, Offset(c.x, c.y + r * 0.5f), max(r * 0.2f, 6.5f * density), Neon.sousTitre.copy(alpha = alpha), fontScale, largeurMax = r * 1.6f)
}

/** Petit hub « Tags » : pastille allongée avec icône d'étiquette, comme sur l'image. */
fun DrawScope.dessinerHubTags(hub: Hub, c: Offset, k: Float, mesureur: TextMeasurer, fontScale: Float, alpha: Float = 1f) {
    val w = 118f * k; val h = 40f * k
    val tl = Offset(c.x - w / 2f, c.y - h / 2f)
    drawRoundRect(Neon.violetVif.copy(alpha = 0.3f * alpha), tl - Offset(3f * k, 3f * k), Size(w + 6f * k, h + 6f * k), CornerRadius(h * 0.55f))
    drawRoundRect(Color(0xF00A0514), tl, Size(w, h), CornerRadius(h * 0.5f), alpha = alpha)
    drawRoundRect(Neon.lavande.copy(alpha = 0.9f * alpha), tl, Size(w, h), CornerRadius(h * 0.5f), style = Stroke(max(1.3f * k, 1f)))
    dessinerIcone(Icone.ETIQUETTE, Offset(tl.x + h * 0.55f, c.y), h * 0.5f, Neon.violet, alpha)
    texteCentre(mesureur, hub.titre, Offset(c.x + h * 0.3f, c.y - h * 0.15f), h * 0.36f, Neon.blancViolet.copy(alpha = alpha), fontScale, gras = true)
    texteCentre(mesureur, hub.sousTitre, Offset(c.x + h * 0.3f, c.y + h * 0.22f), h * 0.22f, Neon.sousTitre.copy(alpha = alpha), fontScale)
}

/**
 * Étiquette de note / pièce jointe / tag : cartouche sombre arrondi, liseré néon, icône à gauche.
 * Retourne le rectangle dessiné (pour le test de toucher).
 */
fun DrawScope.dessinerEtiquette(
    e: Etiquette, c: Offset, k: Float, mesureur: TextMeasurer, fontScale: Float,
    alpha: Float = 1f, surbrillance: Boolean = false, taillePxMin: Float = 0f
): Rect {
    val avecIcone = e.type != TypeEtiquette.TAG && e.type != TypeEtiquette.PLUS
    val px = max(11f * k, taillePxMin)
    val style = TextStyle(
        color = (if (e.type == TypeEtiquette.PLUS) Neon.sousTitre else Neon.blancViolet).copy(alpha = alpha),
        fontSize = (px / density / fontScale).sp, fontFamily = Neon.serif,
        fontWeight = if (e.type == TypeEtiquette.TAG) FontWeight.Bold else FontWeight.Normal
    )
    val res = mesureur.measure(e.texte, style, softWrap = false, maxLines = 1)
    val h = max(e.h * k, res.size.height + 6f * k)
    val icone = if (avecIcone) h * 0.95f else 0f
    val w = res.size.width + icone + h * 0.55f
    val tl = Offset(c.x - w / 2f, c.y - h / 2f)
    val coin = CornerRadius(h * 0.42f)
    val bord = if (surbrillance) Color.White else if (e.type == TypeEtiquette.TAG) Neon.violet else Neon.lavande
    drawRoundRect(Neon.violetVif.copy(alpha = (if (surbrillance) 0.55f else 0.22f) * alpha), tl - Offset(2.5f * k, 2.5f * k), Size(w + 5f * k, h + 5f * k), CornerRadius(h * 0.5f))
    drawRoundRect(Neon.fondEtiquette, tl, Size(w, h), coin, alpha = alpha)
    drawRoundRect(bord.copy(alpha = 0.9f * alpha), tl, Size(w, h), coin,
        style = Stroke(max(1.1f * k, 0.9f), pathEffect = if (e.type == TypeEtiquette.PLUS) PathEffect.dashPathEffect(floatArrayOf(4f * k, 3f * k)) else null))
    if (avecIcone) dessinerIcone(e.icone, Offset(tl.x + h * 0.6f, c.y), h * 0.52f, Neon.violet, alpha)
    drawText(res, topLeft = Offset(tl.x + icone + h * 0.28f, c.y - res.size.height / 2f))
    return Rect(tl, Size(w, h))
}

// ------------------------------------------------------------------------------------------------ traits

/** Trait néon (halo + cœur) avec pointe de flèche à l'arrivée. */
fun DrawScope.fleche(a: Offset, b: Offset, couleur: Color, epaisseur: Float, alpha: Float = 1f, pointe: Boolean = true, courbe: Float = 0f) {
    val d = b - a
    val l = hypot(d.x, d.y)
    if (l < 2f) return
    val ux = d.x / l; val uy = d.y / l
    val p = Path().apply {
        moveTo(a.x, a.y)
        if (courbe != 0f) {
            val m = Offset((a.x + b.x) / 2f - uy * l * courbe, (a.y + b.y) / 2f + ux * l * courbe)
            quadraticBezierTo(m.x, m.y, b.x, b.y)
        } else lineTo(b.x, b.y)
    }
    drawPath(p, couleur.copy(alpha = 0.22f * alpha), style = Stroke(epaisseur * 4f, cap = StrokeCap.Round))
    drawPath(p, couleur.copy(alpha = 0.9f * alpha), style = Stroke(epaisseur, cap = StrokeCap.Round))
    if (pointe) {
        // Direction d'arrivée (tangente en b pour une courbe).
        val (tx, ty) = if (courbe != 0f) {
            val m = Offset((a.x + b.x) / 2f - uy * l * courbe, (a.y + b.y) / 2f + ux * l * courbe)
            val dd = b - m; val ll = hypot(dd.x, dd.y).coerceAtLeast(0.01f); dd.x / ll to dd.y / ll
        } else ux to uy
        val t = epaisseur * 4.2f
        val pt = Path().apply {
            moveTo(b.x, b.y)
            lineTo(b.x - tx * t - ty * t * 0.55f, b.y - ty * t + tx * t * 0.55f)
            lineTo(b.x - tx * t + ty * t * 0.55f, b.y - ty * t - tx * t * 0.55f)
            close()
        }
        drawPath(pt, couleur.copy(alpha = alpha))
    }
}

/** Toile d'énergie entre le cerveau et les hubs : rayons lumineux + anneaux reliant les rayons, nœuds brillants. */
fun DrawScope.dessinerToile(centre: Offset, rCentre: Float, hubs: List<Pair<Offset, Float>>, k: Float, pulse: Float, alpha: Float = 1f) {
    if (hubs.isEmpty()) return
    val tries = hubs.sortedBy { atan2(it.first.y - centre.y, it.first.x - centre.x) }
    // Rayons (faisceaux) cerveau → hub.
    for ((h, r) in tries) {
        val a = ConstellationLayout.bordCercle(Pt(centre.x, centre.y), rCentre, Pt(h.x, h.y)).let { Offset(it.x, it.y) }
        val b = ConstellationLayout.bordCercle(Pt(h.x, h.y), r, Pt(centre.x, centre.y)).let { Offset(it.x, it.y) }
        drawLine(Neon.violetVif.copy(alpha = 0.2f * alpha * pulse), a, b, 9f * k, StrokeCap.Round)
        drawLine(Brush.linearGradient(listOf(Neon.lavande.copy(alpha = 0.95f * alpha), Neon.violet.copy(alpha = 0.7f * alpha)), a, b), a, b, 2f * k, StrokeCap.Round)
        // Veines d'énergie : deux éclairs dentelés qui serpentent le long du faisceau (forme fixe par hub,
        // seule l'intensité pulse — pas de scintillement aléatoire à chaque image).
        val graine = (h.x * 7f + h.y * 13f).toInt()
        for (v in 0 until 2) {
            val rnd = Random(graine * 31 + v)
            val d = b - a
            val l = hypot(d.x, d.y).coerceAtLeast(1f)
            val nx = -d.y / l; val ny = d.x / l
            val p = Path().apply {
                moveTo(a.x, a.y)
                val n = 9
                for (i in 1 until n) {
                    val t = i / n.toFloat()
                    val ecart = (rnd.nextFloat() - 0.5f) * l * 0.09f * sin(t * PI.toFloat())
                    lineTo(a.x + d.x * t + nx * ecart, a.y + d.y * t + ny * ecart)
                }
                lineTo(b.x, b.y)
            }
            drawPath(p, Neon.violetVif.copy(alpha = 0.22f * alpha * pulse), style = Stroke(4f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(p, Neon.blancViolet.copy(alpha = 0.5f * alpha * pulse), style = Stroke(0.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    if (tries.size < 3) return
    // Anneaux de la toile (comme une toile d'araignée lumineuse), avec petits nœuds aux croisements.
    for (f in floatArrayOf(0.42f, 0.7f)) {
        val pts = tries.map { (h, _) -> Offset(centre.x + (h.x - centre.x) * f, centre.y + (h.y - centre.y) * f) }
        val p = Path().apply {
            pts.forEachIndexed { i, q ->
                if (i == 0) moveTo(q.x, q.y) else {
                    val prev = pts[i - 1]
                    val m = Offset((prev.x + q.x) / 2f, (prev.y + q.y) / 2f)
                    val vers = Offset(m.x - centre.x, m.y - centre.y)
                    quadraticBezierTo(centre.x + vers.x * 0.86f, centre.y + vers.y * 0.86f, q.x, q.y)
                }
            }
            val prev = pts.last(); val q = pts.first()
            val m = Offset((prev.x + q.x) / 2f, (prev.y + q.y) / 2f)
            val vers = Offset(m.x - centre.x, m.y - centre.y)
            quadraticBezierTo(centre.x + vers.x * 0.86f, centre.y + vers.y * 0.86f, q.x, q.y)
        }
        drawPath(p, Neon.violet.copy(alpha = 0.38f * alpha), style = Stroke(1.1f * k))
        pts.forEach { q ->
            drawCircle(Neon.violetVif.copy(alpha = 0.35f * alpha * pulse), 5f * k, q)
            drawCircle(Neon.blancViolet.copy(alpha = 0.95f * alpha), 1.8f * k, q)
        }
    }
    // Arcs extérieurs reliant les hubs voisins.
    for (i in tries.indices) {
        val (h1, _) = tries[i]; val (h2, _) = tries[(i + 1) % tries.size]
        val m = Offset((h1.x + h2.x) / 2f, (h1.y + h2.y) / 2f)
        val vers = Offset(m.x - centre.x, m.y - centre.y)
        val p = Path().apply { moveTo(h1.x, h1.y); quadraticBezierTo(centre.x + vers.x * 1.12f, centre.y + vers.y * 1.12f, h2.x, h2.y) }
        drawPath(p, Neon.violet.copy(alpha = 0.22f * alpha), style = Stroke(1f * k))
    }
}

/** Distance d'un point à un segment (test de toucher sur une flèche). */
fun distanceSegment(p: Offset, a: Offset, b: Offset): Float {
    val d = b - a
    val l2 = d.x * d.x + d.y * d.y
    if (l2 < 1e-3f) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * d.x + (p.y - a.y) * d.y) / l2).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + d.x * t), p.y - (a.y + d.y * t))
}

/** Courbure d'un lien réel entre deux notes, tournée vers le cerveau (le lien s'arrondit vers l'intérieur
 *  du graphe au lieu de longer le bord de l'écran). */
fun courbeVersCentre(a: Offset, b: Offset, centre: Offset, force: Float = 0.24f): Float {
    val d = b - a
    val l = hypot(d.x, d.y).coerceAtLeast(1f)
    val nx = -d.y / l; val ny = d.x / l
    val m = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val versCentre = (centre.x - m.x) * nx + (centre.y - m.y) * ny
    return if (versCentre >= 0f) force else -force
}
