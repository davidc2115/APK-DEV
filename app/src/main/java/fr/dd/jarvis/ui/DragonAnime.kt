package fr.dd.jarvis.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import fr.dd.jarvis.R
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

/**
 * Serpent-dragon ANIMÉ de l'image de référence (Orbe et Graphe), peint à partir de vraies images :
 *  - le corps est une bande d'écailles « déroulée » depuis l'image de référence (motif périodique) ;
 *  - la tête est le gros plan de dragon fourni (gueule ouverte, œil lumineux) ;
 *  - à chaque image, la bande est « pliée » le long de l'anneau qui entoure l'écran (petits morceaux tournés)
 *    et défile : le serpent glisse sur lui-même ; une ondulation douce parcourt le corps ; la tête oscille,
 *    l'œil et la gueule pulsent. Halo et liseré sont tracés en vectoriel.
 * Le coût par image reste faible (une centaine d'appels drawBitmap, aucun flou en direct).
 */
class RessourcesDragon(
    /** Bande du corps répétée 3 fois (les fenêtres de lecture peuvent déborder d'une période). */
    val bande: Bitmap,
    /** Période du motif et hauteur de la bande, en pixels de [bande]. */
    val periode: Float, val bh: Float,
    val tete: Bitmap,
    /** Pixels de [tete] par unité « image d'origine » (340 × 300). */
    val teteEch: Float,
    /** Le cerveau lumineux de l'image de référence (noyau central). */
    val cerveau: ImageBitmap?
)

object DragonAnime {
    @Volatile private var cache: RessourcesDragon? = null

    /** Charge (une seule fois) les images du dragon et du cerveau. */
    fun preparer(res: Resources): RessourcesDragon {
        cache?.let { if (!it.bande.isRecycled && !it.tete.isRecycled) return it }
        val opt = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val corps = BitmapFactory.decodeResource(res, R.drawable.dragon_corps, opt)
        val bande = Bitmap.createBitmap(corps.width * 3, corps.height, Bitmap.Config.ARGB_8888)
        Canvas(bande).apply { for (i in 0 until 3) drawBitmap(corps, (corps.width * i).toFloat(), 0f, null) }
        val periode = corps.width.toFloat(); val bh = corps.height.toFloat()
        corps.recycle()
        val tete = BitmapFactory.decodeResource(res, R.drawable.dragon_tete, opt)
        val cerveau = runCatching { BitmapFactory.decodeResource(res, R.drawable.cerveau_ref, opt).asImageBitmap() }.getOrNull()
        val r = RessourcesDragon(bande, periode, bh, tete, tete.width / 340f, cerveau)
        cache = r
        return r
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun rememberDragon(largeur: Int, synchrone: Boolean = false): RessourcesDragon? {
    val res = LocalContext.current.resources
    if (synchrone) return remember { runCatching { DragonAnime.preparer(res) }.getOrNull() }
    val etat = produceState<RessourcesDragon?>(null) {
        value = withContext(Dispatchers.Default) { runCatching { DragonAnime.preparer(res) }.getOrNull() }
    }
    return etat.value
}

// =================================================================================================== animation

/** Objets réutilisés d'une image à l'autre (aucune allocation lourde pendant l'animation). */
class OutilsDragon {
    val n = 451
    val px = FloatArray(n); val py = FloatArray(n)
    val ax = FloatArray(n); val ay = FloatArray(n)
    val nx = FloatArray(n); val ny = FloatArray(n)
    val s = FloatArray(n); val larg = FloatArray(n)
    val chemin = Path()
    val src = Rect(); val dst = RectF()
    val pBitmap = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val pHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    val pBord = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(140, 205, 170, 255); strokeJoin = Paint.Join.ROUND }
    val pLueur = Paint(Paint.ANTI_ALIAS_FLAG)
    var lueurOeil: Shader? = null
    var lueurGueule: Shader? = null
    val segA = IntArray(n); val segB = IntArray(n); val segLen = FloatArray(n); val segU = FloatArray(n + 1)
}

private fun sgnPow(v: Float, e: Float) = sign(v) * abs(v).pow(e)

// Repères dans la tête (pixels de l'image d'origine 340 × 300, tête tournée vers la droite).
private const val COU_X = 35f        // le cou sort par la gauche de l'image
private const val COU_Y = 125f
private const val MUSEAU_X = 305f
private const val OEIL_X = 200f
private const val OEIL_Y = 120f
private const val GUEULE_X = 240f
private const val GUEULE_Y = 200f

/**
 * Dessine le dragon animé (temps [t] en secondes) sur toute la surface de dessin.
 * [margeHaut] (px) abaisse la tête et le haut de l'anneau, pour les laisser visibles sous un panneau.
 */
fun DrawScope.dessinerDragon(r: RessourcesDragon, o: OutilsDragon, t: Float, margeHaut: Float = 0f) {
    val W = size.width; val H = size.height
    if (W < 16f || H < 16f) return
    val m0 = margeHaut.coerceIn(0f, H * 0.12f)
    // Anneau (super-ellipse irrégulière) qui fait le tour de l'écran.
    val cx0 = W * 0.5f; val cy0 = H * 0.55f + m0 / 2; val rx = W * 0.43f; val ry = H * 0.39f - m0 / 2
    fun ond(th: Float) = 1f + 0.03f * sin(th * 4f + 0.7f) + 0.012f * sin(th * 9f)
    fun bx(th: Float) = cx0 + rx * sgnPow(cos(th), 0.7f) * ond(th)
    fun by(th: Float) = cy0 + ry * sgnPow(sin(th), 0.8f) * ond(th)
    // Tête en haut à droite, tournée vers l'extérieur comme sur l'image de référence ; le cou part vers la gauche.
    val ech = W * 0.40f / (MUSEAU_X - COU_X)
    val rot = 0.045f * sin(t * 0.9f)
    val baseX = W * 0.985f - (MUSEAU_X - COU_X) * ech; val baseY = H * 0.135f + m0
    val dcx = -cos(rot); val dcy = -sin(rot)
    val th0 = Math.toRadians(250.0).toFloat(); val th1 = th0 - 2f * PI.toFloat() * 0.8f
    val n = o.n - 1
    val nCou = 30
    // Axe : cou (Bézier) puis anneau parcouru du haut vers la gauche, le bas, puis la droite (queue sous la tête).
    run {
        val p3x = bx(th0); val p3y = by(th0)
        val qx = bx(th0 - 0.01f) - p3x; val qy = by(th0 - 0.01f) - p3y; val ql = hypot(qx, qy).coerceAtLeast(1e-4f)
        val d = hypot(p3x - baseX, p3y - baseY)
        val c1x = baseX + dcx * d * 0.4f; val c1y = baseY + dcy * d * 0.4f
        val c2x = p3x - qx / ql * d * 0.4f; val c2y = p3y - qy / ql * d * 0.4f
        for (i in 0 until nCou) {
            val s = i / nCou.toFloat(); val u = 1 - s
            o.px[i] = u * u * u * baseX + 3 * u * u * s * c1x + 3 * u * s * s * c2x + s * s * s * p3x
            o.py[i] = u * u * u * baseY + 3 * u * u * s * c1y + 3 * u * s * s * c2y + s * s * s * p3y
        }
        val m = n - nCou
        for (i in 0..m) { val th = th0 + (th1 - th0) * i / m.toFloat(); o.px[nCou + i] = bx(th); o.py[nCou + i] = by(th) }
    }
    o.s[0] = 0f
    for (i in 1..n) o.s[i] = o.s[i - 1] + hypot(o.px[i] - o.px[i - 1], o.py[i] - o.py[i - 1])
    val ltot = o.s[n].coerceAtLeast(1f)
    val largeur = W * 0.12f
    for (i in 0..n) {
        val a = max(0, i - 2); val b = min(n, i + 2)
        var dx = o.px[b] - o.px[a]; var dy = o.py[b] - o.py[a]; val l = hypot(dx, dy).let { if (it < 1e-5f) 1f else it }
        dx /= l; dy /= l
        var nx = dy; var ny = -dx
        if ((o.px[i] - cx0) * nx + (o.py[i] - cy0) * ny < 0) { nx = -nx; ny = -ny }
        o.nx[i] = nx; o.ny[i] = ny
        val f = o.s[i] / ltot
        // Cou plus fin (raccord avec l'image de la tête), queue effilée.
        val cou = 0.55f + 0.45f * min(1f, f / 0.12f).pow(0.7f)
        val q = if (f < 0.7f) 1f else max(0f, (1 - f) / 0.3f).pow(0.8f)
        o.larg[i] = largeur * cou * max(q, 0.02f)
        // Ondulation douce qui parcourt le corps (amortie près de la tête).
        val off = o.larg[i] * 0.08f * min(1f, f / 0.12f) * sin(o.s[i] / (W * 0.75f) * 2f * PI.toFloat() - t * 1.4f)
        o.ax[i] = o.px[i] + nx * off; o.ay[i] = o.py[i] + ny * off
    }
    val pulseCorps = 0.8f + 0.2f * sin(t * 2f)

    drawIntoCanvas { cv ->
        val c = cv.nativeCanvas
        // Halo : contours élargis remplis en transparence (dégradé doux sans flou en direct).
        for ((f, al) in HALO) {
            o.chemin.reset()
            for (i in 0..n) { val x = o.ax[i] + o.nx[i] * o.larg[i] / 2 * f; val y = o.ay[i] + o.ny[i] * o.larg[i] / 2 * f; if (i == 0) o.chemin.moveTo(x, y) else o.chemin.lineTo(x, y) }
            for (i in n downTo 0) o.chemin.lineTo(o.ax[i] - o.nx[i] * o.larg[i] / 2 * f, o.ay[i] - o.ny[i] * o.larg[i] / 2 * f)
            o.chemin.close()
            o.pHalo.color = Color.argb((al * pulseCorps * 255).roundToInt(), 140, 60, 240)
            c.drawPath(o.chemin, o.pHalo)
        }
        // Corps : la bande d'écailles pliée le long de l'axe, de la queue vers la tête ; elle défile (le serpent glisse).
        var ns = 0; var acc = 0f; var iPrec = 0
        for (i in 1..n) {
            acc += hypot(o.ax[i] - o.ax[i - 1], o.ay[i] - o.ay[i - 1])
            if (acc >= max(6f, o.larg[i] * 0.3f) || i == n) { o.segA[ns] = iPrec; o.segB[ns] = i; o.segLen[ns] = acc; ns++; iPrec = i; acc = 0f }
        }
        o.segU[0] = 0f
        for (k in 0 until ns) o.segU[k + 1] = o.segU[k] + o.segLen[k] * r.bh / ((o.larg[o.segA[k]] + o.larg[o.segB[k]]) / 2f)
        val defil = t * r.bh * 0.35f
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
            o.dst.set(-len / 2 - over, -r.bh * sc / 2, len / 2 + over, r.bh * sc / 2)
            c.save(); c.translate(mx, my); c.rotate(Math.toDegrees(ang.toDouble()).toFloat()); if (!ext) c.scale(1f, -1f)
            c.drawBitmap(r.bande, o.src, o.dst, o.pBitmap)
            c.restore()
        }
        // Liseré extérieur discret.
        o.pBord.strokeWidth = max(1f, W * 0.0019f)
        o.chemin.reset()
        for (i in 0..n) { val x = o.ax[i] + o.nx[i] * o.larg[i] / 2 * 0.98f; val y = o.ay[i] + o.ny[i] * o.larg[i] / 2 * 0.98f; if (i == 0) o.chemin.moveTo(x, y) else o.chemin.lineTo(x, y) }
        c.drawPath(o.chemin, o.pBord)
        // Tête peinte, posée à la base du cou, qui oscille ; halo de l'œil et de la gueule qui pulsent.
        c.save()
        c.translate(baseX, baseY); c.rotate(Math.toDegrees(rot.toDouble()).toFloat()); c.scale(ech, ech)
        c.save(); c.scale(1f / r.teteEch, 1f / r.teteEch)
        c.drawBitmap(r.tete, -COU_X * r.teteEch, -COU_Y * r.teteEch, o.pBitmap)
        c.restore()
        val pulse = 0.7f + 0.3f * sin(t * 2.4f)
        val ex = OEIL_X - COU_X; val ey = OEIL_Y - COU_Y
        if (o.lueurOeil == null) o.lueurOeil = RadialGradient(ex, ey, 46f,
            intArrayOf(Color.argb(153, 255, 235, 255), Color.argb(102, 200, 120, 255), Color.argb(0, 110, 30, 210)), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        val gx = GUEULE_X - COU_X; val gy = GUEULE_Y - COU_Y
        if (o.lueurGueule == null) o.lueurGueule = RadialGradient(gx, gy, 70f,
            intArrayOf(Color.argb(77, 200, 90, 255), Color.argb(0, 100, 20, 200)), null, Shader.TileMode.CLAMP)
        o.pLueur.shader = o.lueurOeil; o.pLueur.alpha = (255 * pulse).roundToInt()
        c.drawCircle(ex, ey, 46f, o.pLueur)
        o.pLueur.shader = o.lueurGueule
        c.drawCircle(gx, gy, 70f, o.pLueur)
        c.restore()
    }
}

private val HALO = listOf(2.1f to 0.035f, 1.8f to 0.045f, 1.55f to 0.06f, 1.35f to 0.075f, 1.18f to 0.09f)
