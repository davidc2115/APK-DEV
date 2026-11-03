package fr.dd.jarvis.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Décor de fond « Cerveau / Graphe Obsidian », dans l'esprit de l'image de référence : veines
 * d'énergie violettes, arbre mort, ruines, rochers flottants, cercle runique et cristal, nébuleuse étoilée.
 *
 * Tout est dessiné par le code (aucune image embarquée), une seule fois, dans un Bitmap logiciel — en tâche de
 * fond — puis simplement affiché à chaque image : le décor ne coûte rien pendant les animations du graphe.
 * Pensé pour l'écran portrait du téléphone (le serpent longe les bords, la tête en haut à droite).
 */
object FondDragon {
    /** Dernier décor rendu, réutilisé tant que la taille ne change pas (passage 2D ↔ 3D, retour sur l'onglet). */
    @Volatile private var cache: Pair<Long, Bitmap>? = null

    fun rendre(largeur: Int, hauteur: Int, graine: Int = 7): Bitmap {
        val cle = (largeur.toLong() shl 32) or hauteur.toLong()
        cache?.let { (k, b) -> if (k == cle && !b.isRecycled) return b }
        val bmp = Bitmap.createBitmap(largeur.coerceAtLeast(16), hauteur.coerceAtLeast(16), Bitmap.Config.ARGB_8888)
        PeintreDragon(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), graine).tout()
        cache = cle to bmp
        return bmp
    }
}

/**
 * Décor à la taille de l'écran, rendu en tâche de fond (null tant qu'il n'est pas prêt : on affiche alors le fond
 * simple). [synchrone] ne sert qu'aux captures d'écran hors appareil.
 */
@Composable
fun rememberFondDragon(largeur: Int, hauteur: Int, synchrone: Boolean = false): ImageBitmap? {
    if (largeur < 16 || hauteur < 16) return null
    if (synchrone) return remember(largeur, hauteur) { runCatching { FondDragon.rendre(largeur, hauteur).asImageBitmap() }.getOrNull() }
    val etat = produceState<ImageBitmap?>(null, largeur, hauteur) {
        value = withContext(Dispatchers.Default) { runCatching { FondDragon.rendre(largeur, hauteur).asImageBitmap() }.getOrNull() }
    }
    return etat.value
}

private class PeintreDragon(private val g: Canvas, private val W: Float, private val H: Float, graine: Int) {
    private val hasard = Random(graine)
    /** Échelle : le prototype a été réglé pour 786 px de large. */
    private val u = W / 786f

    private fun rnd() = hasard.nextFloat()
    private fun c(r: Number, v: Number, b: Number, a: Float = 1f) =
        Color.argb((a.coerceIn(0f, 1f) * 255).roundToInt(), r.toFloat().roundToInt().coerceIn(0, 255), v.toFloat().roundToInt().coerceIn(0, 255), b.toFloat().roundToInt().coerceIn(0, 255))
    private fun hex(s: String) = Color.parseColor(s)

    private fun lueur(p: Path, couleur: Int, largeur: Float, flou: Float, alpha: Float = 1f) {
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = largeur; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = couleur; this.alpha = (Color.alpha(couleur) * alpha).roundToInt().coerceIn(0, 255)
            if (flou > 0.3f) maskFilter = BlurMaskFilter(max(0.5f, flou * 1.7f), BlurMaskFilter.Blur.NORMAL)
        }
        g.drawPath(p, pt)
    }
    private fun trait(p: Path, couleur: Int, largeur: Float, rond: Boolean = true) {
        g.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = largeur; color = couleur
            if (rond) { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        })
    }
    private fun remplir(p: Path, couleur: Int) = g.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = couleur })
    private fun remplir(p: Path, shader: Shader) = g.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
    private fun radial(x: Float, y: Float, r: Float, vararg arrets: Pair<Float, Int>): Shader =
        RadialGradient(x, y, max(r, 0.5f), arrets.map { it.second }.toIntArray(), arrets.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
    private fun lineaire(x0: Float, y0: Float, x1: Float, y1: Float, vararg arrets: Pair<Float, Int>): Shader =
        LinearGradient(x0, y0, x1, y1, arrets.map { it.second }.toIntArray(), arrets.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
    private fun disque(x: Float, y: Float, r: Float, shader: Shader) = g.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
    private fun ovale(x: Float, y: Float, rx: Float, ry: Float, rot: Float, paint: Paint) {
        g.save(); g.rotate(Math.toDegrees(rot.toDouble()).toFloat(), x, y); g.drawOval(RectF(x - rx, y - ry, x + rx, y + ry), paint); g.restore()
    }

    fun tout() {
        nebuleuse()
        repeat(8) { i ->
            val x: Float; val y: Float; val a: Float
            when (i % 4) {
                0 -> { x = rnd() * W; y = H * 0.05f + rnd() * H * 0.15f; a = (PI / 2).toFloat() + (rnd() - 0.5f) }
                1 -> { x = W * 0.95f; y = rnd() * H; a = PI.toFloat() + (rnd() - 0.5f) }
                2 -> { x = rnd() * W; y = H * 0.95f; a = (-PI / 2).toFloat() + (rnd() - 0.5f) }
                else -> { x = W * 0.05f; y = rnd() * H; a = rnd() - 0.5f }
            }
            eclair(x, y, a, W * (0.14f + rnd() * 0.14f), 2, 1.6f * u)
        }
        repeat(14) {
            val cote = rnd() < 0.5f
            val x = if (cote) rnd() * W * 0.22f else W - rnd() * W * 0.22f
            val y = H * 0.1f + rnd() * H * 0.8f
            rocher(x, y, (6f + rnd() * 22f) * u)
        }
        branche(W * 0.5f, H * 0.37f, (-PI / 2).toFloat(), H * 0.075f, 26f * u, 8)
        ruine(W * 0.1f, H * 0.93f, W * 0.1f); ruine(W * 0.9f, H * 0.94f, W * 0.09f)
        cercleRunique(W * 0.5f, H * 0.885f, W * 0.34f, H * 0.03f)
        // Le serpent-dragon n'est plus figé dans le décor : il est animé à chaque image (voir DragonAnime).
    }

    // ------------------------------------------------------------------------------------ nébuleuse & étoiles
    private fun nebuleuse() {
        g.drawRect(0f, 0f, W, H, Paint().apply {
            shader = radial(W * 0.5f, H * 0.48f, max(W, H) * 0.75f, 0f to hex("#1a0b30"), 0.45f to hex("#0b0516"), 1f to hex("#020104"))
        })
        repeat(9) {
            val x = rnd() * W; val y = rnd() * H; val r = (0.15f + rnd() * 0.35f) * W
            disque(x, y, r, radial(x, y, r, 0f to c(120, 50, 220, 0.10f + rnd() * 0.08f), 1f to c(60, 20, 120, 0f)))
        }
        val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(260) {
            val a = rnd()
            pt.color = c(230, 215, 255, 0.15f + a * 0.6f)
            g.drawCircle(rnd() * W, rnd() * H, (0.5f + a * 1.6f) * u, pt)
        }
    }

    // ------------------------------------------------------------------------------------ veines d'énergie
    private fun eclair(x: Float, y: Float, angle: Float, longueur: Float, profondeur: Int, epaisseur: Float) {
        val p = Path(); p.moveTo(x, y)
        var cx = x; var cy = y; var ang = angle
        val n = 8
        for (i in 0 until n) {
            ang += (rnd() - 0.5f) * 0.9f
            cx += cos(ang) * longueur / n; cy += sin(ang) * longueur / n
            p.lineTo(cx, cy)
            if (profondeur > 0 && rnd() < 0.35f) eclair(cx, cy, ang + (rnd() - 0.5f) * 1.8f, longueur * 0.5f, profondeur - 1, epaisseur * 0.6f)
        }
        lueur(p, c(150, 80, 255), epaisseur * 5f, epaisseur * 4f, 0.35f)
        lueur(p, c(190, 140, 255), epaisseur * 1.6f, epaisseur * 0.8f, 0.55f)
        trait(p, c(240, 225, 255, 0.6f), max(0.6f * u, epaisseur * 0.45f))
    }

    // ------------------------------------------------------------------------------------ rochers flottants
    private fun rocher(x: Float, y: Float, r: Float) {
        val p = Path()
        val n = 6 + floor(rnd() * 3).toInt()
        for (i in 0 until n) {
            val a = i.toFloat() / n * 2f * PI.toFloat() + rnd() * 0.4f; val rr = r * (0.6f + rnd() * 0.5f)
            if (i == 0) p.moveTo(x + cos(a) * rr, y + sin(a) * rr) else p.lineTo(x + cos(a) * rr, y + sin(a) * rr)
        }
        p.close()
        remplir(p, radial(x - r * 0.3f, y - r * 0.4f, r * 1.6f, 0f to hex("#2a2036"), 1f to hex("#07050b")))
        lueur(p, c(170, 110, 255), 2f * u, 1.5f * u, 0.6f)
    }

    // ------------------------------------------------------------------------------------ arbre mort
    private fun branche(x: Float, y: Float, ang: Float, len: Float, w: Float, prof: Int) {
        if (prof == 0 || len < 6f * u) return
        val x2 = x + cos(ang) * len; val y2 = y + sin(ang) * len
        val mx = (x + x2) / 2 + (rnd() - 0.5f) * len * 0.25f; val my = (y + y2) / 2 + (rnd() - 0.5f) * len * 0.25f
        val p = Path().apply { moveTo(x, y); quadTo(mx, my, x2, y2) }
        if (w > 3f * u) lueur(p, c(140, 70, 230), w + 4f * u, 4f * u, 0.22f)
        trait(p, hex("#0a0612"), w)
        trait(p, c(160, 110, 230, 0.35f), max(0.7f * u, w * 0.18f))
        val k = 2 + if (rnd() < 0.4f) 1 else 0
        for (i in 0 until k) branche(x2, y2, ang + (i - (k - 1) / 2f) * 0.55f + (rnd() - 0.5f) * 0.3f, len * (0.68f + rnd() * 0.12f), w * 0.66f, prof - 1)
    }

    // ------------------------------------------------------------------------------------ ruines
    private fun pilier(x: Float, yb: Float, w: Float, h: Float) {
        val p = Path(); p.moveTo(x - w / 2, yb); p.lineTo(x - w / 2, yb - h)
        val n = 5
        for (i in 0..n) p.lineTo(x - w / 2 + w * i / n, yb - h - rnd() * w * 0.8f)
        p.lineTo(x + w / 2, yb); p.close()
        remplir(p, lineaire(x - w / 2, 0f, x + w / 2, 0f, 0f to hex("#05030a"), 0.35f to hex("#1d1628"), 1f to hex("#040208")))
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(0, 0, 0, 0.6f); strokeWidth = 1.5f * u }
        for (i in 1 until 6) g.drawLine(x - w / 2, yb - h * i / 6, x + w / 2, yb - h * i / 6, pt)
        lueur(p, c(150, 90, 240), 2f * u, 2f * u, 0.45f)
    }
    private fun ruine(xc: Float, yb: Float, s: Float) {
        pilier(xc - s * 0.9f, yb, s * 0.42f, s * 2.2f); pilier(xc + s * 0.1f, yb, s * 0.38f, s * 1.4f); pilier(xc + s * 0.9f, yb + s * 0.1f, s * 0.34f, s * 0.9f)
        val arc = Path().apply { moveTo(xc - s * 1.1f, yb - s * 2.2f); quadTo(xc - s * 0.4f, yb - s * 2.9f, xc + s * 0.3f, yb - s * 1.5f) }
        trait(arc, hex("#120c1c"), s * 0.18f, rond = false)
        lueur(arc, c(150, 90, 240), 2f * u, 2f * u, 0.35f)
    }

    // ------------------------------------------------------------------------------------ cercle runique & cristal
    private fun cercleRunique(cx: Float, cy: Float, rx: Float, ry: Float) {
        g.drawOval(RectF(cx - rx * 1.3f, cy - ry * 1.6f, cx + rx * 1.3f, cy + ry * 1.6f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, rx * 1.3f, intArrayOf(c(150, 80, 255, 0.35f), c(80, 30, 160, 0f)), null, Shader.TileMode.CLAMP)
        })
        for (f in floatArrayOf(1f, 0.82f, 0.62f, 0.4f)) {
            val p = Path().apply { addOval(RectF(cx - rx * f, cy - ry * f, cx + rx * f, cy + ry * f), Path.Direction.CW) }
            lueur(p, c(170, 110, 255), 3f * u, 3f * u, 0.6f)
            trait(p, c(225, 205, 255, 0.85f), 1.3f * u)
        }
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(220, 200, 255, 0.75f); strokeWidth = 1.2f * u; strokeCap = Paint.Cap.ROUND }
        for (i in 0 until 48) {
            val a = i / 48f * 2f * PI.toFloat(); val co = cos(a); val si = sin(a)
            g.drawLine(cx + co * rx * 0.84f, cy + si * ry * 0.84f, cx + co * rx * 0.97f, cy + si * ry * 0.97f, pt)
        }
        pt.color = c(230, 215, 255, 0.8f); pt.strokeWidth = 1.1f * u
        for (i in 0 until 20) {
            val a = i / 20f * 2f * PI.toFloat() + 0.1f
            val x = cx + cos(a) * rx * 0.72f; val y = cy + sin(a) * ry * 0.72f; val t = rx * 0.035f
            g.drawLine(x, y - t, x, y + t, pt)
            when (i % 3) {
                0 -> g.drawLine(x, y - t, x + t * 0.8f, y, pt)
                1 -> g.drawLine(x - t * 0.7f, y - t * 0.3f, x + t * 0.7f, y + t * 0.3f, pt)
                else -> g.drawLine(x, y, x + t * 0.7f, y - t * 0.7f, pt)
            }
        }
        // Cristal
        val h = ry * 1.9f; val w = rx * 0.11f; val haut = cy - ry * 0.2f - h
        val cr = Path().apply { moveTo(cx, haut); lineTo(cx + w, haut + h * 0.62f); lineTo(cx, cy - ry * 0.15f); lineTo(cx - w, haut + h * 0.62f); close() }
        remplir(cr, radial(cx, haut + h * 0.55f, h * 0.8f, 0f to c(255, 240, 255), 0.25f to c(190, 130, 255, 0.95f), 1f to c(70, 20, 150, 0.9f)))
        lueur(cr, c(200, 150, 255), 6f * u, 8f * u, 0.8f)
        val pl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(255, 245, 255, 0.7f); strokeWidth = 1f * u }
        g.drawLine(cx, haut, cx, cy - ry * 0.15f, pl); g.drawLine(cx - w, haut + h * 0.62f, cx + w, haut + h * 0.62f, pl)
        disque(cx, haut + h * 0.5f, h * 1.1f, radial(cx, haut + h * 0.5f, h * 1.1f, 0f to c(200, 150, 255, 0.35f), 1f to c(120, 60, 220, 0f)))
    }

    // ------------------------------------------------------------------------------------ serpent-dragon
    private fun sgnPow(v: Float, e: Float) = sign(v) * abs(v).pow(e)

    @Suppress("unused") private fun serpentFige() {
        val cx0 = W * 0.5f; val cy0 = H * 0.545f; val rx = W * 0.43f; val ry = H * 0.385f
        fun boucle(th: Float): FloatArray {
            val ond = 1f + 0.03f * sin(th * 4f + 0.7f) + 0.012f * sin(th * 9f)
            return floatArrayOf(cx0 + rx * sgnPow(cos(th), 0.7f) * ond, cy0 + ry * sgnPow(sin(th), 0.8f) * ond)
        }
        val longTete = W * 0.31f
        val baseTete = floatArrayOf(W * 0.88f, H * 0.195f)
        val aTete = atan2(0.62f, -0.78f)
        val dirTete = floatArrayOf(cos(aTete), sin(aTete))
        val th0 = Math.toRadians(-62.0).toFloat()
        val th1 = th0 + 2f * PI.toFloat() * 0.9f

        val pts = ArrayList<FloatArray>()
        // Cou : courbe de Bézier entre la base de la tête et le début de la boucle.
        run {
            val p0 = baseTete; val p3 = boucle(th0); val q = boucle(th0 + 0.01f)
            val dx = q[0] - p3[0]; val dy = q[1] - p3[1]; val dl = hypot(dx, dy).coerceAtLeast(1e-4f)
            val c1 = floatArrayOf(p0[0] - dirTete[0] * longTete * 0.55f, p0[1] - dirTete[1] * longTete * 0.55f)
            val c2 = floatArrayOf(p3[0] - dx / dl * longTete * 0.6f, p3[1] - dy / dl * longTete * 0.6f)
            for (i in 0 until 60) {
                val t = i / 60f; val v = 1 - t
                pts += floatArrayOf(
                    v * v * v * p0[0] + 3 * v * v * t * c1[0] + 3 * v * t * t * c2[0] + t * t * t * p3[0],
                    v * v * v * p0[1] + 3 * v * v * t * c1[1] + 3 * v * t * t * c2[1] + t * t * t * p3[1]
                )
            }
        }
        for (i in 0..900) pts += boucle(th0 + (th1 - th0) * i / 900f)
        val n = pts.size - 1
        val base = W * 0.105f
        val wd = FloatArray(n + 1) { i ->
            val t = i.toFloat() / n
            val cou = 0.78f + 0.22f * min(1f, t / 0.08f)
            val queue = if (t < 0.68f) 1f else max(0f, (1 - t) / 0.32f).pow(0.85f)
            base * cou * max(queue, 0.015f)
        }
        val tx = FloatArray(n + 1); val ty = FloatArray(n + 1); val nx = FloatArray(n + 1); val ny = FloatArray(n + 1)
        for (i in 0..n) {
            val a = pts[max(0, i - 2)]; val b = pts[min(n, i + 2)]
            var dx = b[0] - a[0]; var dy = b[1] - a[1]; val l = hypot(dx, dy).let { if (it < 1e-5f) 1f else it }
            dx /= l; dy /= l; tx[i] = dx; ty[i] = dy
            var ax = dy; var ay = -dx
            if ((pts[i][0] - cx0) * ax + (pts[i][1] - cy0) * ay < 0) { ax = -ax; ay = -ay }
            nx[i] = ax; ny[i] = ay
        }
        fun bx(i: Int, s: Float) = pts[i][0] + nx[i] * wd[i] / 2 * s
        fun by(i: Int, s: Float) = pts[i][1] + ny[i] * wd[i] / 2 * s
        fun bande(off: Float) = Path().apply {
            for (i in 0..n) { val x = pts[i][0] + nx[i] * wd[i] * off; val y = pts[i][1] + ny[i] * wd[i] * off; if (i == 0) moveTo(x, y) else lineTo(x, y) }
        }
        val corps = Path().apply {
            moveTo(bx(0, 1f), by(0, 1f)); for (i in 1..n) lineTo(bx(i, 1f), by(i, 1f))
            for (i in n downTo 0) lineTo(bx(i, -1f), by(i, -1f)); close()
        }

        lueur(bande(0.5f), c(110, 40, 210), 14f * u, 18f * u, 0.4f)
        lueur(bande(-0.5f), c(110, 40, 210), 10f * u, 14f * u, 0.3f)
        remplir(corps, hex("#060409"))
        g.save(); g.clipPath(corps)
        // Écailles : de la queue vers la tête, pour que celles proches de la tête recouvrent les suivantes.
        val rangs = ArrayList<Int>()
        var acc = 0f
        for (i in 1..n) {
            acc += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
            if (acc >= max(3f * u, wd[i] * 0.17f)) { acc = 0f; rangs += i }
        }
        val noir = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c(0, 0, 0, 0.85f) }
        val lum = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val remp = Paint(Paint.ANTI_ALIAS_FLAG)
        val ecaille = Path(); val bordLum = Path()
        for (r in rangs.indices.reversed()) {
            val i = rangs[r]; val w = wd[i]; val nr = 7
            for (k in 0 until nr) {
                val off = ((k - (nr - 1) / 2f) + (if (r % 2 == 1) 0.5f else 0f)) / (nr - 1)
                if (abs(off) > 0.56f) continue
                val cyl = sqrt(max(0f, 1f - (off * 1.75f) * (off * 1.75f)))
                val l = max(0f, 0.15f + 0.85f * (0.55f * cyl + 0.45f * max(0f, off * 1.6f)))
                val x = pts[i][0] + nx[i] * w * off * 0.95f; val y = pts[i][1] + ny[i] * w * off * 0.95f
                val s = w * 0.15f * (0.55f + 0.45f * cyl)
                g.save(); g.translate(x, y); g.rotate(Math.toDegrees(atan2(ty[i], tx[i]).toDouble()).toFloat())
                ecaille.reset()
                ecaille.moveTo(-s * 0.2f, -s * 0.62f)
                ecaille.cubicTo(s * 0.75f, -s * 0.7f, s * 1.15f, -s * 0.15f, s * 1.15f, 0f)
                ecaille.cubicTo(s * 1.15f, s * 0.15f, s * 0.75f, s * 0.7f, -s * 0.2f, s * 0.62f)
                ecaille.close()
                remp.shader = RadialGradient(s * 0.7f, 0f, max(s * 1.1f, 0.5f),
                    intArrayOf(c(18 + l * 70, 14 + l * 52, 26 + l * 95), c(8 + l * 22, 6 + l * 16, 12 + l * 30), hex("#030205")),
                    floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                g.drawPath(ecaille, remp)
                noir.strokeWidth = max(0.7f * u, s * 0.1f); g.drawPath(ecaille, noir)
                bordLum.reset(); bordLum.moveTo(s * 0.6f, -s * 0.55f); bordLum.cubicTo(s * 1.0f, -s * 0.4f, s * 1.12f, -s * 0.1f, s * 1.12f, 0f)
                lum.color = c(170 + l * 70, 130 + l * 60, 240, 0.12f + l * 0.45f); lum.strokeWidth = max(0.6f * u, s * 0.07f)
                g.drawPath(bordLum, lum)
                g.restore()
            }
        }
        // Reflet spéculaire (verre noir brillant), reflet violet doux, ombre propre côté intérieur.
        lueur(bande(0.24f), c(150, 90, 255), max(2f * u, base * 0.22f), base * 0.12f, 0.25f)
        lueur(bande(0.28f), c(240, 225, 255), max(1f * u, base * 0.045f), base * 0.03f, 0.45f)
        lueur(bande(-0.42f), c(0, 0, 0), base * 0.25f, base * 0.08f, 0.7f)
        g.restore()
        // Liserés néon.
        lueur(bande(0.5f), c(170, 100, 255), 6f * u, 6f * u, 0.6f)
        trait(bande(0.5f), c(215, 185, 255, 0.8f), 1.4f * u)
        lueur(bande(-0.5f), c(140, 70, 240), 3f * u, 4f * u, 0.4f)
        // Épines dorsales recourbées vers la queue.
        acc = 0f
        val epineContour = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c(190, 150, 255, 0.65f); strokeWidth = 1f * u }
        for (i in 1..n) {
            acc += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
            val w = wd[i]
            if (acc < w * 0.62f || w < 5f * u) continue
            acc = 0f
            val x0 = bx(i, 0.92f); val y0 = by(i, 0.92f); val h = w * 0.42f; val ww = w * 0.15f
            val sp = Path().apply {
                moveTo(x0 - tx[i] * ww, y0 - ty[i] * ww)
                quadTo(x0 + nx[i] * h * 0.6f - tx[i] * ww * 0.2f, y0 + ny[i] * h * 0.6f - ty[i] * ww * 0.2f, x0 + nx[i] * h + tx[i] * h * 0.45f, y0 + ny[i] * h + ty[i] * h * 0.45f)
                quadTo(x0 + nx[i] * h * 0.3f + tx[i] * ww, y0 + ny[i] * h * 0.3f + ty[i] * ww, x0 + tx[i] * ww, y0 + ty[i] * ww)
                close()
            }
            remplir(sp, lineaire(x0, y0, x0 + nx[i] * h, y0 + ny[i] * h, 0f to hex("#0a0710"), 1f to hex("#2a1f3a")))
            g.drawPath(sp, epineContour)
        }
        tete(baseTete[0], baseTete[1], dirTete, longTete)
    }

    // ------------------------------------------------------------------------------------ tête de dragon
    private fun tete(x: Float, y: Float, dir: FloatArray, L: Float) {
        g.save(); g.translate(x, y); g.rotate(Math.toDegrees(atan2(dir[1], dir[0]).toDouble()).toFloat())
        if (dir[0] < 0) g.scale(1f, -1f) // jamais la tête à l'envers
        val h = L * 0.42f
        fun qx(a: Float) = a * L
        fun qy(b: Float) = b * h
        fun poly(vararg p: Float): Path = Path().apply { var i = 0; while (i < p.size) { if (i == 0) moveTo(qx(p[i]), qy(p[i + 1])) else lineTo(qx(p[i]), qy(p[i + 1])); i += 2 }; close() }
        /** Courbe fermée passant par les points (Catmull-Rom → Bézier). */
        fun lisse(vararg p: Float): Path {
            val m = p.size / 2
            fun px(i: Int): Float = qx(p[((i % m + m) % m) * 2])
            fun py(i: Int): Float = qy(p[((i % m + m) % m) * 2 + 1])
            return Path().apply {
                moveTo(px(0), py(0))
                for (i in 0 until m) cubicTo(
                    px(i) + (px(i + 1) - px(i - 1)) / 6, py(i) + (py(i + 1) - py(i - 1)) / 6,
                    px(i + 1) - (px(i + 2) - px(i)) / 6, py(i + 1) - (py(i + 2) - py(i)) / 6,
                    px(i + 1), py(i + 1)
                )
                close()
            }
        }
        fun remplirTete(p: Path, haut: String, bas: String) = remplir(p, lineaire(0f, -h * 0.9f, 0f, h * 0.9f, 0f to hex(haut), 0.5f to hex("#0b0811"), 1f to hex(bas)))
        fun ecaillesTete(p: Path, nb: Int) {
            g.save(); g.clipPath(p)
            val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c(160, 130, 215, 0.22f); strokeWidth = 0.9f * u }
            repeat(nb) {
                val a = qx(rnd() * 1.25f - 0.15f); val b = qy(rnd() * 1.6f - 0.8f); val s = L * (0.02f + rnd() * 0.02f)
                g.drawArc(RectF(a - s, b - s, a + s, b + s), 18f, -216f, false, pt)
            }
            g.restore()
        }
        fun neon(p: Path, w: Float = 4f) { lueur(p, c(170, 100, 255), w * u, w * u, 0.65f); trait(p, c(220, 195, 255, 0.85f), 1.3f * u) }

        // Cornes : épaisses à la base, recourbées vers l'arrière, annelées.
        fun corne(bxx: Float, byy: Float, ang: Float, len: Float, ep: Float, courb: Float) {
            val dx = cos(ang); val dy = sin(ang); val ox = -dy; val oy = dx
            val tipX = bxx + dx * len + ox * len * courb; val tipY = byy + dy * len + oy * len * courb
            val c1x = bxx + dx * len * 0.55f + ox * len * courb * 0.15f; val c1y = byy + dy * len * 0.55f + oy * len * courb * 0.15f
            val p = Path().apply {
                moveTo(bxx + ox * ep / 2, byy + oy * ep / 2)
                quadTo(c1x + ox * ep * 0.35f, c1y + oy * ep * 0.35f, tipX, tipY)
                quadTo(c1x - ox * ep * 0.35f, c1y - oy * ep * 0.35f, bxx - ox * ep / 2, byy - oy * ep / 2)
                close()
            }
            remplir(p, lineaire(bxx, byy, tipX, tipY, 0f to hex("#08060c"), 0.6f to hex("#241a31"), 1f to hex("#4a3a60")))
            g.save(); g.clipPath(p)
            val anneau = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(0, 0, 0, 0.55f); strokeWidth = 1.2f * u }
            for (k in 1 until 8) {
                val t = k / 8f
                val xx = bxx + (tipX - bxx) * t * 0.95f + ox * len * courb * t * t * 0.9f
                val yy = byy + (tipY - byy) * t * 0.95f + oy * len * courb * t * t * 0.9f
                g.drawLine(xx + ox * ep, yy + oy * ep, xx - ox * ep, yy - oy * ep, anneau)
            }
            g.restore()
            lueur(p, c(170, 100, 255), 3f * u, 3f * u, 0.6f)
            trait(p, c(215, 190, 255, 0.75f), 1.1f * u)
        }
        corne(L * 0.12f, -h * 0.38f, PI.toFloat() + 0.62f, L * 0.95f, L * 0.13f, -0.28f)
        corne(0f, -h * 0.12f, PI.toFloat() + 0.28f, L * 0.7f, L * 0.1f, -0.22f)
        corne(L * 0.22f, -h * 0.44f, PI.toFloat() + 0.95f, L * 0.55f, L * 0.08f, -0.2f)

        // Collerette / épines de joue.
        for (e in listOf(floatArrayOf(0f, 0.25f, -0.42f, 0.42f), floatArrayOf(-0.06f, 0.05f, -0.5f, 0.12f), floatArrayOf(0.08f, 0.4f, -0.28f, 0.7f))) {
            val (uu, vv, du, dv) = e.toList()
            val ax = qx(uu); val ay = qy(vv - 0.07f); val bx2 = qx(uu + du); val by2 = qy(vv + dv); val cx2 = qx(uu + 0.06f); val cy2 = qy(vv + 0.08f)
            val p = Path().apply { moveTo(ax, ay); quadTo((ax + bx2) / 2, (ay + by2) / 2 - h * 0.1f, bx2, by2); lineTo(cx2, cy2); close() }
            remplir(p, hex("#0d0914")); neon(p, 2f)
        }

        // Mâchoire inférieure (ouverte) et gueule lumineuse.
        val bas = lisse(0.16f, 0.16f, 0.55f, 0.34f, 0.9f, 0.56f, 1.0f, 0.66f, 0.94f, 0.76f, 0.6f, 0.7f, 0.25f, 0.55f, 0.02f, 0.38f)
        val gueule = poly(0.22f, 0.12f, 1.05f, 0.02f, 0.97f, 0.6f, 0.5f, 0.38f, 0.2f, 0.2f)
        remplir(gueule, radial(qx(0.75f), qy(0.3f), L * 0.55f, 0f to c(220, 110, 255), 0.35f to c(120, 30, 170), 1f to c(20, 4, 30)))
        remplirTete(bas, "#1d1528", "#020103"); ecaillesTete(bas, 40)
        // Crâne / mâchoire supérieure.
        val haut = lisse(-0.12f, -0.3f, 0.1f, -0.46f, 0.3f, -0.5f, 0.42f, -0.42f, 0.62f, -0.37f, 0.88f, -0.3f, 1.05f, -0.24f, 1.13f, -0.12f,
            1.1f, -0.02f, 0.92f, 0.03f, 0.55f, 0.07f, 0.25f, 0.15f, 0.05f, 0.3f, -0.15f, 0.22f, -0.22f, 0f)
        remplirTete(haut, "#30253f", "#030205"); ecaillesTete(haut, 90)

        // Dents et crocs.
        val dent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hex("#f3ebff") }
        for (i in 0 until 8) {
            val a = qx(0.36f + i * 0.085f); val b = qy(0.08f - i * 0.006f); val t = L * (0.028f + (i % 2) * 0.016f)
            g.drawPath(Path().apply { moveTo(a - t * 0.5f, b - u); lineTo(a + t * 0.1f, b + t * 2f); lineTo(a + t * 0.5f, b - u); close() }, dent)
        }
        for (i in 0 until 7) {
            val a = qx(0.38f + i * 0.085f); val b = qy(0.38f + i * 0.04f); val t = L * (0.026f + (i % 2) * 0.014f)
            g.drawPath(Path().apply { moveTo(a - t * 0.5f, b + u); lineTo(a + t * 0.25f, b - t * 1.9f); lineTo(a + t * 0.5f, b + u); close() }, dent)
        }
        for ((uu, vv, sgn) in listOf(Triple(1.03f, 0.02f, 1f), Triple(0.95f, 0.6f, -1f))) {
            val a = qx(uu); val b = qy(vv); val t = L * 0.075f
            g.drawPath(Path().apply { moveTo(a - t * 0.35f, b); quadTo(a, b + sgn * t * 1.2f, a + t * 0.1f, b + sgn * t * 1.9f); lineTo(a + t * 0.35f, b); close() }, dent)
        }
        neon(bas); neon(haut)

        // Reliefs : arcade sourcilière, plis du museau, reflet, narine.
        val arcade = Path().apply { moveTo(qx(0.3f), qy(-0.44f)); quadTo(qx(0.48f), qy(-0.6f), qx(0.7f), qy(-0.4f)) }
        trait(arcade, hex("#050308"), L * 0.04f)
        lueur(arcade, c(220, 190, 255), 1.5f * u, 1f * u, 0.7f)
        for (v in floatArrayOf(-0.2f, -0.08f)) {
            val p = Path().apply { moveTo(qx(0.62f), qy(v - 0.06f)); quadTo(qx(0.85f), qy(v - 0.02f), qx(1.02f), qy(v - 0.05f)) }
            trait(p, c(0, 0, 0, 0.7f), 1.4f * u)
        }
        lueur(Path().apply { moveTo(qx(0.15f), qy(-0.4f)); quadTo(qx(0.6f), qy(-0.42f), qx(1.02f), qy(-0.22f)) }, c(245, 235, 255), 2f * u, 1.5f * u, 0.5f)
        ovale(qx(1.03f), qy(-0.14f), L * 0.03f, L * 0.012f, 0.35f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })

        // Œil lumineux.
        val ex = qx(0.5f); val ey = qy(-0.32f)
        disque(ex, ey, L * 0.3f, radial(ex, ey, L * 0.3f, 0f to c(255, 235, 255, 0.95f), 0.18f to c(210, 120, 255, 0.75f), 0.5f to c(140, 50, 240, 0.25f), 1f to c(100, 30, 200, 0f)))
        ovale(ex, ey, L * 0.065f, L * 0.034f, -0.25f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = radial(ex, ey, L * 0.065f, 0f to Color.WHITE, 0.45f to hex("#e2b8ff"), 1f to hex("#8a3cf0"))
        })
        ovale(ex, ey, L * 0.009f, L * 0.031f, -0.25f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hex("#14001c") })
        g.restore()
    }
}
