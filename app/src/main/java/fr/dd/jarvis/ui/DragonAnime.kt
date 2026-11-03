package fr.dd.jarvis.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * Serpent-dragon ANIMÉ de l'image de référence (Orbe et Graphe) :
 *  - trois images préparées une seule fois en tâche de fond : une longue bande d'écailles périodique (le corps),
 *    la tête (crâne, cornes, œil) et la mâchoire inférieure ;
 *  - à chaque image, la bande est « pliée » le long de l'anneau qui entoure l'écran (petits morceaux tournés)
 *    et défile : le serpent glisse sur lui-même ; une ondulation douce parcourt le corps ; la gueule s'ouvre et se
 *    referme, l'œil et la gueule pulsent, la tête oscille. Épines, halo et liserés sont tracés en vectoriel.
 * Le coût par image reste faible (quelques centaines d'appels de dessin simples, aucun flou en direct).
 */
class RessourcesDragon(
    val bande: Bitmap, val tete: Bitmap, val machoire: Bitmap,
    val bh: Float, val sh: Float, val sx: Float, val periode: Float,
    val l: Float, val hh: Float, val ox: Float, val oy: Float, val pivX: Float, val pivY: Float
)

object DragonAnime {
    @Volatile private var cache: Pair<Int, RessourcesDragon>? = null

    /** Prépare (ou reprend du cache) les images du dragon pour un écran de [largeur] pixels. */
    fun preparer(largeur: Int): RessourcesDragon {
        cache?.let { (w, r) -> if (w == largeur && !r.bande.isRecycled) return r }
        val r = PeintreCorpsDragon(largeur.toFloat()).tout()
        cache = largeur to r
        return r
    }
}

@Composable
fun rememberDragon(largeur: Int, synchrone: Boolean = false): RessourcesDragon? {
    if (largeur < 16) return null
    if (synchrone) return remember(largeur) { runCatching { DragonAnime.preparer(largeur) }.getOrNull() }
    val etat = produceState<RessourcesDragon?>(null, largeur) {
        value = withContext(Dispatchers.Default) { runCatching { DragonAnime.preparer(largeur) }.getOrNull() }
    }
    return etat.value
}

// =================================================================================================== préparation

private class PeintreCorpsDragon(private val W: Float) {
    private val hasard = Random(11)
    private fun rnd() = hasard.nextFloat()
    private fun c(r: Number, v: Number, b: Number, a: Float = 1f) =
        Color.argb((a.coerceIn(0f, 1f) * 255).roundToInt(), r.toFloat().roundToInt().coerceIn(0, 255), v.toFloat().roundToInt().coerceIn(0, 255), b.toFloat().roundToInt().coerceIn(0, 255))
    private fun hex(s: String) = Color.parseColor(s)
    private fun lueur(g: Canvas, p: Path, couleur: Int, largeur: Float, flou: Float, alpha: Float = 1f) {
        g.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = largeur; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = couleur; this.alpha = (Color.alpha(couleur) * alpha).roundToInt().coerceIn(0, 255)
            if (flou > 0.3f) maskFilter = BlurMaskFilter(max(0.5f, flou * 1.7f), BlurMaskFilter.Blur.NORMAL)
        })
    }
    private fun trait(g: Canvas, p: Path, couleur: Int, largeur: Float) = g.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = largeur; color = couleur; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    })
    private fun remplir(g: Canvas, p: Path, shader: Shader) = g.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
    private fun remplir(g: Canvas, p: Path, couleur: Int) = g.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = couleur })
    private fun lineaire(x0: Float, y0: Float, x1: Float, y1: Float, vararg a: Pair<Float, Int>): Shader =
        LinearGradient(x0, y0, x1, y1, a.map { it.second }.toIntArray(), a.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
    private fun radial(x: Float, y: Float, r: Float, vararg a: Pair<Float, Int>): Shader =
        RadialGradient(x, y, max(r, 0.5f), a.map { it.second }.toIntArray(), a.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
    /** Courbe fermée passant par les points (Catmull-Rom → Bézier). */
    private fun lisse(vararg p: Float): Path {
        val m = p.size / 2
        fun px(i: Int): Float = p[((i % m + m) % m) * 2]
        fun py(i: Int): Float = p[((i % m + m) % m) * 2 + 1]
        return Path().apply {
            moveTo(px(0), py(0))
            for (i in 0 until m) cubicTo(
                px(i) + (px(i + 1) - px(i - 1)) / 6, py(i) + (py(i + 1) - py(i - 1)) / 6,
                px(i + 1) - (px(i + 2) - px(i)) / 6, py(i + 1) - (py(i + 2) - py(i)) / 6, px(i + 1), py(i + 1)
            )
            close()
        }
    }

    fun tout(): RessourcesDragon {
        val bh = (W * 0.125f).coerceAtLeast(24f)
        val (bandeBmp, sh, sx, periode) = bande(bh)
        val l = (W * 0.34f).coerceAtLeast(40f)
        val hh = l * 0.42f
        val tw = (l * 2.2f).roundToInt(); val th = (l * 1.9f).roundToInt()
        val ox = l * 0.9f; val oy = l * 1.05f
        val tete = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val mach = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        Canvas(tete).apply { translate(ox, oy); dessinerTete(this, l, hh) }
        Canvas(mach).apply { translate(ox, oy); dessinerMachoire(this, l, hh) }
        return RessourcesDragon(bandeBmp, tete, mach, bh, sh, sx, periode, l, hh, ox, oy, 0.12f * l, 0.22f * hh)
    }

    private data class Bande(val bmp: Bitmap, val sh: Float, val sx: Float, val periode: Float)

    // ------------------------------------------------------------------------------------ bande du corps
    /** Corps de hauteur [bh] (haut = côté extérieur) ; motif périodique de période [Bande.periode]. */
    private fun bande(bh: Float): Bande {
        val k = bh / 160f
        val m = 4f * k
        val sh = bh + 2 * m
        val sx = bh * 0.24f
        val periode = (sx * 30).roundToInt().toFloat()
        val sw = (periode + 900f * k).roundToInt()
        val bmp = Bitmap.createBitmap(sw, sh.roundToInt().coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        val g = Canvas(bmp)
        g.drawRect(0f, m, sw.toFloat(), m + bh, Paint().apply {
            shader = lineaire(0f, m, 0f, m + bh, 0f to hex("#2c2140"), 0.1f to hex("#140e1d"), 0.5f to hex("#060409"), 0.82f to hex("#0c0911"), 1f to hex("#1d1626"))
        })
        val nr = 8
        val remp = Paint(Paint.ANTI_ALIAS_FLAG)
        val noir = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c(0, 0, 0, 0.9f); strokeWidth = 2.2f * k }
        val lum = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.7f * k; strokeCap = Paint.Cap.ROUND }
        val sc = Path(); val bl = Path()
        var x = sw + sx * 2
        while (x > -sx * 3) {
            val col = (x / sx).roundToInt()
            for (kk in 0 until nr) {
                val v = (kk + (if (col % 2 != 0) 0.5f else 0f)) / (nr - 1)
                if (v > 1.04f) continue
                val y = m + bh * (0.03f + 0.94f * v)
                val cyl = sin(PI.toFloat() * (0.08f + 0.84f * v))
                val s = sx * 0.95f * (0.55f + 0.45f * cyl)
                val l = max(0f, 0.12f + 0.88f * (0.6f * cyl * (1 - v * 0.5f) + 0.4f * max(0f, 1 - v * 1.6f)))
                g.save(); g.translate(x, y)
                sc.reset()
                sc.moveTo(-s * 0.3f, -s * 0.6f)
                sc.cubicTo(s * 0.55f, -s * 0.7f, s * 1.0f, -s * 0.2f, s * 1.0f, 0f)
                sc.cubicTo(s * 1.0f, s * 0.2f, s * 0.55f, s * 0.7f, -s * 0.3f, s * 0.6f)
                sc.close()
                remp.shader = RadialGradient(s * 0.5f, -s * 0.1f, max(s * 1.05f, 0.5f),
                    intArrayOf(c(26 + l * 95, 20 + l * 70, 38 + l * 125), c(9 + l * 26, 7 + l * 18, 15 + l * 38), hex("#030205")),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                g.drawPath(sc, remp); g.drawPath(sc, noir)
                bl.reset(); bl.moveTo(s * 0.3f, -s * 0.58f); bl.cubicTo(s * 0.78f, -s * 0.45f, s * 0.98f, -s * 0.16f, s * 0.98f, 0f)
                lum.color = c(185 + l * 60, 150 + l * 55, 255, 0.12f + l * 0.6f)
                g.drawPath(bl, lum)
                g.restore()
            }
            x -= sx
        }
        val reflet = Path().apply { moveTo(0f, m + bh * 0.22f); lineTo(sw.toFloat(), m + bh * 0.22f) }
        lueur(g, reflet, c(150, 90, 255), bh * 0.14f, bh * 0.07f, 0.35f)
        lueur(g, reflet, c(245, 235, 255), bh * 0.035f, bh * 0.02f, 0.6f)
        val ombre = Path().apply { moveTo(0f, m + bh * 0.86f); lineTo(sw.toFloat(), m + bh * 0.86f) }
        lueur(g, ombre, c(0, 0, 0), bh * 0.2f, bh * 0.06f, 0.6f)
        return Bande(bmp, sh, sx, periode)
    }

    // ------------------------------------------------------------------------------------ tête
    private fun neon(g: Canvas, p: Path, w: Float, a: Float = 0.7f) {
        lueur(g, p, c(170, 100, 255), w, w, a)
        trait(g, p, c(220, 195, 255, 0.85f), max(1f, w * 0.35f))
    }
    private fun texture(g: Canvas, p: Path, n: Int, taille: Float, l: Float, hh: Float) {
        g.save(); g.clipPath(p)
        val sombre = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c(0, 0, 0, 0.55f); strokeWidth = max(1f, l * 0.0047f) }
        val clair = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c(190, 160, 240, 0.22f); strokeWidth = max(0.8f, l * 0.0033f) }
        repeat(n) {
            val x = (rnd() * 1.5f - 0.3f) * l; val y = (rnd() * 2.2f - 1.1f) * hh; val s = l * taille * (0.6f + rnd() * 0.8f)
            g.drawArc(RectF(x - s, y - s, x + s, y + s), 27f, -234f, false, sombre)
            g.drawArc(RectF(x - s, y - s - 1.2f, x + s, y + s - 1.2f), 207f, 81f, false, clair)
        }
        g.restore()
    }
    private fun corne(g: Canvas, bx: Float, by: Float, ang: Float, len: Float, ep: Float, courb: Float) {
        val dx = cos(ang); val dy = sin(ang); val nx = -dy; val ny = dx
        val tipX = bx + dx * len + nx * len * courb; val tipY = by + dy * len + ny * len * courb
        val c1x = bx + dx * len * 0.55f + nx * len * courb * 0.1f; val c1y = by + dy * len * 0.55f + ny * len * courb * 0.1f
        val p = Path().apply {
            moveTo(bx + nx * ep / 2, by + ny * ep / 2)
            quadTo(c1x + nx * ep * 0.3f, c1y + ny * ep * 0.3f, tipX, tipY)
            quadTo(c1x - nx * ep * 0.3f, c1y - ny * ep * 0.3f, bx - nx * ep / 2, by - ny * ep / 2)
            close()
        }
        remplir(g, p, lineaire(bx, by, tipX, tipY, 0f to hex("#07050b"), 0.55f to hex("#231a30"), 1f to hex("#5a4874")))
        g.save(); g.clipPath(p)
        val an = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(0, 0, 0, 0.6f); strokeWidth = max(1f, ep * 0.03f) }
        for (k in 1 until 9) {
            val t = k / 9f
            val xx = bx + (tipX - bx) * t + nx * len * courb * t * t * 0.8f; val yy = by + (tipY - by) * t + ny * len * courb * t * t * 0.8f
            g.drawLine(xx + nx * ep, yy + ny * ep, xx - nx * ep, yy - ny * ep, an)
        }
        g.restore()
        neon(g, p, max(2f, ep * 0.08f), 0.6f)
    }
    private fun epine(g: Canvas, x: Float, y: Float, ang: Float, len: Float, ep: Float) {
        val dx = cos(ang); val dy = sin(ang); val nx = -dy; val ny = dx
        val p = Path().apply {
            moveTo(x + nx * ep / 2, y + ny * ep / 2)
            quadTo(x + dx * len * 0.6f + nx * ep * 0.15f, y + dy * len * 0.6f + ny * ep * 0.15f, x + dx * len, y + dy * len)
            lineTo(x - nx * ep / 2, y - ny * ep / 2); close()
        }
        remplir(g, p, hex("#0e0a15")); neon(g, p, max(1.5f, ep * 0.12f), 0.55f)
    }

    private fun dessinerTete(g: Canvas, l: Float, hh: Float) {
        fun qx(u: Float) = u * l
        fun qy(v: Float) = v * hh
        val w = l / 300f
        // Collerette d'épines derrière la mâchoire et le long du cou.
        for (e in listOf(floatArrayOf(-0.05f, 0.5f, 2.55f, 0.55f, 0.07f), floatArrayOf(-0.12f, 0.25f, 2.85f, 0.6f, 0.07f),
                floatArrayOf(-0.2f, 0f, 3.05f, 0.5f, 0.06f), floatArrayOf(-0.28f, -0.2f, 3.3f, 0.42f, 0.05f)))
            epine(g, qx(e[0]), qy(e[1]), e[2], e[3] * l, e[4] * l)
        // Cornes massives, recourbées vers l'arrière.
        corne(g, l * 0.08f, -hh * 0.5f, PI.toFloat() + 0.55f, l * 0.95f, l * 0.24f, -0.45f)
        corne(g, 0f, -hh * 0.15f, PI.toFloat() + 0.22f, l * 0.68f, l * 0.17f, -0.38f)
        corne(g, l * 0.3f, -hh * 0.62f, PI.toFloat() + 1.0f, l * 0.4f, l * 0.11f, -0.3f)
        // Crâne + mâchoire supérieure : museau long légèrement relevé, bosse nasale, arcade marquée.
        val haut = lisse(qx(-0.25f), qy(0.05f), qx(-0.2f), qy(-0.35f), qx(0.05f), qy(-0.6f), qx(0.28f), qy(-0.68f), qx(0.42f), qy(-0.58f),
            qx(0.55f), qy(-0.5f), qx(0.75f), qy(-0.45f), qx(0.95f), qy(-0.42f), qx(1.1f), qy(-0.38f), qx(1.2f), qy(-0.25f), qx(1.22f), qy(-0.08f),
            qx(1.12f), qy(0f), qx(0.85f), qy(0.04f), qx(0.55f), qy(0.08f), qx(0.28f), qy(0.16f), qx(0.08f), qy(0.32f), qx(-0.12f), qy(0.35f))
        remplir(g, haut, lineaire(0f, -hh * 0.8f, 0f, hh * 0.4f, 0f to hex("#3a2c4c"), 0.35f to hex("#140f1c"), 1f to hex("#040306")))
        texture(g, haut, 140, 0.028f, l, hh)
        g.save(); g.clipPath(haut)
        val pl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(0, 0, 0, 0.6f); strokeWidth = 2f * w }
        for (i in 0 until 7) { val a = qx(0.5f + i * 0.1f); val b = qy(-0.46f + i * 0.006f); g.drawLine(a, b, a + l * 0.02f, b + hh * 0.22f, pl) }
        g.restore()
        // Dents du haut et croc.
        val dent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hex("#f4edff") }
        for (i in 0 until 9) {
            val a = qx(0.32f + i * 0.095f); val b = qy(0.07f - i * 0.008f); val t = l * (0.026f + (i % 2) * 0.017f)
            g.drawPath(Path().apply { moveTo(a - t * 0.5f, b - 2 * w); quadTo(a, b + t * 1.2f, a + t * 0.12f, b + t * 2.1f); lineTo(a + t * 0.5f, b - 2 * w); close() }, dent)
        }
        run { val a = qx(1.1f); val b = qy(0f); val t = l * 0.08f
            g.drawPath(Path().apply { moveTo(a - t * 0.35f, b - 2 * w); quadTo(a, b + t * 1.1f, a + t * 0.05f, b + t * 2f); lineTo(a + t * 0.35f, b - 2 * w); close() }, dent) }
        neon(g, haut, 5f * w, 0.7f)
        // Arcade sourcilière épaisse avec épines.
        val arc = Path().apply { moveTo(qx(0.18f), qy(-0.62f)); quadTo(qx(0.42f), qy(-0.86f), qx(0.68f), qy(-0.5f)) }
        trait(g, arc, hex("#050308"), l * 0.06f)
        lueur(g, arc, c(225, 200, 255), 2f * w, 1.2f * w, 0.75f)
        for (e in listOf(floatArrayOf(0.25f, -0.7f, PI.toFloat() + 0.9f, 0.2f), floatArrayOf(0.36f, -0.76f, PI.toFloat() + 1.1f, 0.17f), floatArrayOf(0.47f, -0.74f, PI.toFloat() + 1.3f, 0.13f)))
            epine(g, qx(e[0]), qy(e[1]), e[2], e[3] * l, 0.04f * l)
        // Narine, plis du museau, long reflet.
        run {
            val a = qx(1.12f); val b = qy(-0.28f)
            g.save(); g.rotate(Math.toDegrees(0.4).toFloat(), a, b)
            val r = RectF(a - l * 0.035f, b - l * 0.014f, a + l * 0.035f, b + l * 0.014f)
            g.drawOval(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            lueur(g, Path().apply { addOval(r, Path.Direction.CW) }, c(170, 100, 255), 2f * w, 2f * w, 0.5f)
            g.restore()
        }
        for (v in floatArrayOf(-0.3f, -0.16f)) {
            val p = Path().apply { moveTo(qx(0.62f), qy(v)); quadTo(qx(0.85f), qy(v + 0.05f), qx(1.05f), qy(v)) }
            trait(g, p, c(0, 0, 0, 0.75f), 2f * w)
            g.save(); g.translate(0f, -1.5f * w); trait(g, p, c(170, 140, 220, 0.25f), 1f * w); g.restore()
        }
        lueur(g, Path().apply { moveTo(qx(0f), qy(-0.5f)); quadTo(qx(0.6f), qy(-0.58f), qx(1.15f), qy(-0.32f)) }, c(245, 235, 255), 3f * w, 2f * w, 0.55f)
        // Œil en amande, pupille fendue (le halo qui pulse est ajouté à chaque image).
        val ex = qx(0.46f); val ey = qy(-0.42f)
        val oeil = Path().apply {
            moveTo(ex - l * 0.085f, ey + l * 0.01f)
            quadTo(ex, ey - l * 0.06f, ex + l * 0.085f, ey - l * 0.012f)
            quadTo(ex, ey + l * 0.045f, ex - l * 0.085f, ey + l * 0.01f); close()
        }
        remplir(g, oeil, radial(ex, ey, l * 0.09f, 0f to Color.WHITE, 0.4f to hex("#e6c2ff"), 1f to hex("#8a35f0")))
        g.drawOval(RectF(ex + l * 0.005f - l * 0.009f, ey - l * 0.003f - l * 0.032f, ex + l * 0.005f + l * 0.009f, ey - l * 0.003f + l * 0.032f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hex("#16001f") })
        trait(g, oeil, hex("#050308"), 2.5f * w)
    }

    private fun dessinerMachoire(g: Canvas, l: Float, hh: Float) {
        fun qx(u: Float) = u * l
        fun qy(v: Float) = v * hh
        val w = l / 300f
        // Intérieur de la gueule (lueur violette) et langue fourchue.
        val gueule = Path().apply {
            moveTo(qx(0.15f), qy(0.16f)); lineTo(qx(1.12f), qy(0.06f)); lineTo(qx(1.02f), qy(0.62f)); lineTo(qx(0.55f), qy(0.48f)); lineTo(qx(0.2f), qy(0.3f)); close()
        }
        remplir(g, gueule, radial(qx(0.7f), qy(0.3f), l * 0.6f, 0f to c(235, 130, 255), 0.35f to c(130, 35, 185), 1f to c(22, 4, 32)))
        val langue = Path().apply {
            moveTo(qx(0.25f), qy(0.3f)); quadTo(qx(0.7f), qy(0.42f), qx(0.98f), qy(0.36f)); lineTo(qx(1.06f), qy(0.3f))
            moveTo(qx(0.98f), qy(0.36f)); lineTo(qx(1.04f), qy(0.42f))
        }
        trait(g, langue, c(90, 20, 60, 0.9f), l * 0.035f)
        trait(g, langue, c(230, 120, 200, 0.5f), 1.2f * w)
        val bas = lisse(qx(0.08f), qy(0.22f), qx(0.5f), qy(0.38f), qx(0.88f), qy(0.56f), qx(1.05f), qy(0.66f), qx(1.0f), qy(0.8f),
            qx(0.65f), qy(0.78f), qx(0.3f), qy(0.66f), qx(0.02f), qy(0.5f), qx(-0.06f), qy(0.34f))
        remplir(g, bas, lineaire(0f, hh * 0.2f, 0f, hh * 0.9f, 0f to hex("#2a2036"), 0.5f to hex("#0d0a12"), 1f to hex("#030205")))
        texture(g, bas, 60, 0.026f, l, hh)
        g.save(); g.clipPath(bas)
        val pl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(150, 120, 190, 0.3f); strokeWidth = 1.2f * w }
        for (i in 0 until 8) { val a = qx(0.2f + i * 0.1f); val b = qy(0.62f + i * 0.02f); g.drawLine(a, b, a + l * 0.03f, b + hh * 0.2f, pl) }
        g.restore()
        val dent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hex("#f4edff") }
        for (i in 0 until 8) {
            val a = qx(0.34f + i * 0.09f); val b = qy(0.42f + i * 0.03f); val t = l * (0.024f + (i % 2) * 0.014f)
            g.drawPath(Path().apply { moveTo(a - t * 0.5f, b + 2 * w); lineTo(a + t * 0.25f, b - t * 1.9f); lineTo(a + t * 0.5f, b + 2 * w); close() }, dent)
        }
        run { val a = qx(1.0f); val b = qy(0.62f); val t = l * 0.075f
            g.drawPath(Path().apply { moveTo(a - t * 0.35f, b + 2 * w); quadTo(a, b - t * 1.1f, a + t * 0.1f, b - t * 1.9f); lineTo(a + t * 0.35f, b + 2 * w); close() }, dent) }
        neon(g, bas, 4f * w, 0.65f)
        epine(g, qx(0.82f), qy(0.78f), PI.toFloat() * 0.62f, l * 0.14f, l * 0.04f)
    }
}

// =================================================================================================== animation

/** Objets réutilisés d'une image à l'autre (aucune allocation lourde pendant l'animation). */
class OutilsDragon {
    val n = 461
    val px = FloatArray(n); val py = FloatArray(n)
    val ax = FloatArray(n); val ay = FloatArray(n)
    val tx = FloatArray(n); val ty = FloatArray(n)
    val nx = FloatArray(n); val ny = FloatArray(n)
    val s = FloatArray(n); val larg = FloatArray(n)
    val chemin = Path(); val epine = Path()
    val src = Rect(); val dst = RectF()
    val pBitmap = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val pHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    val pEpine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#120d1a") }
    val pEpineBord = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(191, 205, 175, 255) }
    val pBordExt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(217, 215, 185, 255); strokeJoin = Paint.Join.ROUND }
    val pBordInt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(140, 150, 80, 245); strokeJoin = Paint.Join.ROUND }
    val pLueur = Paint(Paint.ANTI_ALIAS_FLAG)
    var lueurOeil: Shader? = null
    var lueurGueule: Shader? = null
    val segA = IntArray(n); val segB = IntArray(n); val segLen = FloatArray(n); val segU = FloatArray(n + 1)
}

private fun sgnPow(v: Float, e: Float) = sign(v) * abs(v).pow(e)

/** Dessine le dragon animé (temps [t] en secondes) sur toute la surface de dessin. */
fun DrawScope.dessinerDragon(r: RessourcesDragon, o: OutilsDragon, t: Float) {
    val W = size.width; val H = size.height
    if (W < 16f || H < 16f) return
    val cx0 = W * 0.5f; val cy0 = H * 0.545f; val rx = W * 0.425f; val ry = H * 0.385f
    fun bx(th: Float) = cx0 + rx * sgnPow(cos(th), 0.7f) * (1f + 0.03f * sin(th * 4f + 0.7f) + 0.012f * sin(th * 9f))
    fun by(th: Float) = cy0 + ry * sgnPow(sin(th), 0.8f) * (1f + 0.03f * sin(th * 4f + 0.7f) + 0.012f * sin(th * 9f))
    val lt = r.l
    val baseX = W * 0.87f; val baseY = H * 0.2f
    val aTete = atan2(0.62f, -0.78f) + 0.06f * sin(t * 0.9f)
    val dX = cos(aTete); val dY = sin(aTete)
    val th0 = Math.toRadians(-62.0).toFloat(); val th1 = th0 + 2f * PI.toFloat() * 0.9f
    val n = o.n - 1
    // Axe : cou (Bézier) puis anneau.
    run {
        val p3x = bx(th0); val p3y = by(th0)
        val qx = bx(th0 + 0.01f) - p3x; val qy = by(th0 + 0.01f) - p3y; val ql = hypot(qx, qy).coerceAtLeast(1e-4f)
        val c1x = baseX - dX * lt * 0.5f; val c1y = baseY - dY * lt * 0.5f
        val c2x = p3x - qx / ql * lt * 0.6f; val c2y = p3y - qy / ql * lt * 0.6f
        for (i in 0 until 40) {
            val s = i / 40f; val u = 1 - s
            o.px[i] = u * u * u * baseX + 3 * u * u * s * c1x + 3 * u * s * s * c2x + s * s * s * p3x
            o.py[i] = u * u * u * baseY + 3 * u * u * s * c1y + 3 * u * s * s * c2y + s * s * s * p3y
        }
        for (i in 0..420) { val th = th0 + (th1 - th0) * i / 420f; o.px[40 + i] = bx(th); o.py[40 + i] = by(th) }
    }
    o.s[0] = 0f
    for (i in 1..n) o.s[i] = o.s[i - 1] + hypot(o.px[i] - o.px[i - 1], o.py[i] - o.py[i - 1])
    val ltot = o.s[n].coerceAtLeast(1f)
    for (i in 0..n) {
        val a = max(0, i - 2); val b = min(n, i + 2)
        var dx = o.px[b] - o.px[a]; var dy = o.py[b] - o.py[a]; val l = hypot(dx, dy).let { if (it < 1e-5f) 1f else it }
        dx /= l; dy /= l; o.tx[i] = dx; o.ty[i] = dy
        var nx = dy; var ny = -dx
        if ((o.px[i] - cx0) * nx + (o.py[i] - cy0) * ny < 0) { nx = -nx; ny = -ny }
        o.nx[i] = nx; o.ny[i] = ny
        val f = o.s[i] / ltot
        val cou = 0.8f + 0.2f * min(1f, f / 0.08f)
        val q = if (f < 0.66f) 1f else max(0f, (1 - f) / 0.34f).pow(0.85f)
        o.larg[i] = W * 0.125f * cou * max(q, 0.02f)
        // Ondulation douce qui parcourt le corps (amortie près de la tête).
        val off = o.larg[i] * 0.09f * min(1f, f / 0.12f) * sin(o.s[i] / (W * 0.75f) * 2f * PI.toFloat() - t * 1.4f)
        o.ax[i] = o.px[i] + nx * off; o.ay[i] = o.py[i] + ny * off
    }
    val pulseCorps = 0.8f + 0.2f * sin(t * 2f)

    drawIntoCanvas { cv ->
        val c = cv.nativeCanvas
        // Halo : trois contours élargis remplis en transparence.
        for ((f, al) in listOf(1.9f to 0.06f, 1.55f to 0.08f, 1.28f to 0.12f)) {
            o.chemin.reset()
            for (i in 0..n) { val x = o.ax[i] + o.nx[i] * o.larg[i] / 2 * f; val y = o.ay[i] + o.ny[i] * o.larg[i] / 2 * f; if (i == 0) o.chemin.moveTo(x, y) else o.chemin.lineTo(x, y) }
            for (i in n downTo 0) o.chemin.lineTo(o.ax[i] - o.nx[i] * o.larg[i] / 2 * f, o.ay[i] - o.ny[i] * o.larg[i] / 2 * f)
            o.chemin.close()
            o.pHalo.color = Color.argb((al * pulseCorps * 255).roundToInt(), 140, 60, 240)
            c.drawPath(o.chemin, o.pHalo)
        }
        // Épines dorsales, recourbées vers la queue.
        var acc = 0f
        o.pEpineBord.strokeWidth = max(1f, W * 0.0017f)
        for (i in 1..n) {
            acc += hypot(o.ax[i] - o.ax[i - 1], o.ay[i] - o.ay[i - 1])
            val w = o.larg[i]
            if (acc < w * 0.5f || w < 8f) continue
            acc = 0f
            val bxx = o.ax[i] + o.nx[i] * w / 2 * 0.9f; val byy = o.ay[i] + o.ny[i] * w / 2 * 0.9f
            val tx = o.tx[i]; val ty = o.ty[i]; val nx = o.nx[i]; val ny = o.ny[i]; val h = w * 0.48f; val ww = w * 0.14f
            o.epine.reset()
            o.epine.moveTo(bxx - tx * ww, byy - ty * ww)
            o.epine.quadTo(bxx + nx * h * 0.65f - tx * ww * 0.2f, byy + ny * h * 0.65f - ty * ww * 0.2f, bxx + nx * h + tx * h * 0.42f, byy + ny * h + ty * h * 0.42f)
            o.epine.quadTo(bxx + nx * h * 0.3f + tx * ww, byy + ny * h * 0.3f + ty * ww, bxx + tx * ww, byy + ty * ww)
            o.epine.close()
            c.drawPath(o.epine, o.pEpine); c.drawPath(o.epine, o.pEpineBord)
        }
        // Corps : la bande d'écailles pliée le long de l'axe, de la queue vers la tête ; elle défile (le serpent glisse).
        var ns = 0; acc = 0f; var iPrec = 0
        for (i in 1..n) {
            acc += hypot(o.ax[i] - o.ax[i - 1], o.ay[i] - o.ay[i - 1])
            if (acc >= max(6f, o.larg[i] * 0.3f) || i == n) { o.segA[ns] = iPrec; o.segB[ns] = i; o.segLen[ns] = acc; ns++; iPrec = i; acc = 0f }
        }
        o.segU[0] = 0f
        for (k in 0 until ns) o.segU[k + 1] = o.segU[k] + o.segLen[k] * r.bh / ((o.larg[o.segA[k]] + o.larg[o.segB[k]]) / 2f)
        val defil = t * r.sx * 1.2f
        for (k in ns - 1 downTo 0) {
            val a = o.segA[k]; val b = o.segB[k]
            val len = hypot(o.ax[b] - o.ax[a], o.ay[b] - o.ay[a]); if (len < 0.5f) continue
            val w = (o.larg[a] + o.larg[b]) / 2f
            val ang = atan2(o.ay[b] - o.ay[a], o.ax[b] - o.ax[a])
            val mx = (o.ax[a] + o.ax[b]) / 2f; val my = (o.ay[a] + o.ay[b]) / 2f
            val ext = (mx - cx0) * sin(ang) + (my - cy0) * -cos(ang) > 0
            val k2 = r.bh / w; val over = min(w * 0.12f, len * 0.35f)
            var src = (o.segU[k] - defil - over * k2) % r.periode; if (src < 0) src += r.periode
            val srcW = (o.segU[k + 1] - o.segU[k]) + 2 * over * k2
            val sxEnd = min(r.bande.width.toFloat(), src + max(1f, srcW))
            o.src.set(src.roundToInt(), 0, sxEnd.roundToInt().coerceAtLeast(src.roundToInt() + 1), r.bande.height)
            val sc = w / r.bh
            o.dst.set(-len / 2 - over, -r.sh * sc / 2, len / 2 + over, r.sh * sc / 2)
            c.save(); c.translate(mx, my); c.rotate(Math.toDegrees(ang.toDouble()).toFloat()); if (!ext) c.scale(1f, -1f)
            c.drawBitmap(r.bande, o.src, o.dst, o.pBitmap)
            c.restore()
        }
        // Liserés néon.
        o.pBordExt.strokeWidth = max(1.2f, W * 0.0025f); o.pBordInt.strokeWidth = max(1f, W * 0.0019f)
        for ((sgn, p) in listOf(1f to o.pBordExt, -1f to o.pBordInt)) {
            o.chemin.reset()
            for (i in 0..n) { val x = o.ax[i] + o.nx[i] * o.larg[i] / 2 * sgn; val y = o.ay[i] + o.ny[i] * o.larg[i] / 2 * sgn; if (i == 0) o.chemin.moveTo(x, y) else o.chemin.lineTo(x, y) }
            c.drawPath(o.chemin, p)
        }
        // Tête : mâchoire (ouverture animée) puis crâne, à la base du cou ; halo de l'œil et de la gueule qui pulsent.
        val ouverture = 0.08f + 0.16f * (0.5f + 0.5f * sin(t * 1.3f))
        c.save()
        c.translate(baseX, baseY); c.rotate(Math.toDegrees(aTete.toDouble()).toFloat()); if (dX < 0) c.scale(1f, -1f)
        c.save(); c.translate(r.pivX, r.pivY); c.rotate(Math.toDegrees(ouverture.toDouble()).toFloat()); c.translate(-r.pivX, -r.pivY)
        c.drawBitmap(r.machoire, -r.ox, -r.oy, o.pBitmap)
        c.restore()
        c.drawBitmap(r.tete, -r.ox, -r.oy, o.pBitmap)
        val pulse = 0.75f + 0.25f * sin(t * 2.4f)
        val ex = 0.46f * r.l; val ey = -0.42f * r.hh
        if (o.lueurOeil == null) o.lueurOeil = RadialGradient(ex, ey, r.l * 0.32f,
            intArrayOf(Color.argb(191, 255, 235, 255), Color.argb(140, 205, 120, 255), Color.argb(0, 110, 30, 210)), floatArrayOf(0f, 0.2f, 1f), Shader.TileMode.CLAMP)
        val mx = 0.7f * r.l; val my = 0.2f * r.hh
        if (o.lueurGueule == null) o.lueurGueule = RadialGradient(mx, my, r.l * 0.45f,
            intArrayOf(Color.argb(90, 200, 90, 255), Color.argb(0, 100, 20, 200)), null, Shader.TileMode.CLAMP)
        o.pLueur.shader = o.lueurOeil; o.pLueur.alpha = (255 * pulse).roundToInt()
        c.drawCircle(ex, ey, r.l * 0.32f, o.pLueur)
        o.pLueur.shader = o.lueurGueule
        c.drawCircle(mx, my, r.l * 0.45f, o.pLueur)
        c.restore()
    }
}
