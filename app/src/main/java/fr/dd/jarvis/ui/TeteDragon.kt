package fr.dd.jarvis.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import fr.dd.jarvis.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Violet de l'œil et de la gueule du dragon (image de référence). */
private val VioletDragon = Color(0xFFB06CFF)

/**
 * Tête du serpent-dragon ANIMÉE (remplace l'orbe) : la tête « respire » et ondule doucement, l'œil brille et cligne,
 * la gueule rougeoie de violet. Quand Jarvis écoute ou réfléchit ([active]) tout s'intensifie et des étincelles
 * s'échappent de la gueule ; quand il parle ([parle]) la tête marque le rythme et la gueule pulse plus vite.
 * Peinte à partir de l'image du dragon (gueule ouverte, œil lumineux) dans un médaillon cerclé façon HUD.
 */
@Composable
fun TeteDragonAnimee(modifier: Modifier = Modifier.size(52.dp), active: Boolean = false, parle: Boolean = false, cadre: Boolean = true) {
    val accent = LocalAccent.current
    val tete = ImageBitmap.imageResource(R.drawable.dragon_tete)
    val temps by produceState(0f) {
        val debut = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - debut) / 1_000_000_000f }
    }
    val intensite by animateFloatAsState(if (active || parle) 1f else 0f, tween(700), label = "intensiteDragon")
    val voix by animateFloatAsState(if (parle) 1f else 0f, tween(300), label = "voixDragon")

    Canvas(modifier) {
        val t = temps
        val c = center
        val r = size.minDimension / 2f
        val pouls = 0.5f + 0.5f * sin(t * 2.2f)

        // Halo violet autour du médaillon.
        drawCircle(
            Brush.radialGradient(listOf(VioletDragon.copy(alpha = 0.18f + 0.28f * intensite * pouls), Color.Transparent), c, r * 1.05f),
            r * 1.05f, c
        )
        // Fond spatial du médaillon.
        drawCircle(Brush.radialGradient(listOf(Color(0xFF1C0B33), Color(0xFF05030B)), c, r), r * 0.95f, c)

        val disque = Path().apply { addOval(Rect(c, r * 0.95f)) }
        clipPath(disque) {
            // Respiration, flottement, balancement du cou (plus marqué quand il parle).
            val respire = 1f + 0.025f * sin(t * 1.6f) + 0.02f * intensite * sin(t * 4.5f) + 0.02f * voix * sin(t * 10f)
            val flotte = r * (0.035f * sin(t * 1.1f) + 0.015f * voix * sin(t * 9f))
            val angle = 2.5f * sin(t * 0.8f) + 2.2f * voix * sin(t * 8.5f)
            val dw = r * 2.3f
            val dh = dw * tete.height / tete.width
            val gauche = c.x - dw * 0.57f
            val haut = c.y - dh * 0.46f + flotte
            withTransform({
                rotate(angle, pivot = Offset(c.x - r * 0.75f, c.y + r * 0.7f))
                scale(respire, respire, pivot = c)
            }) {
                drawImage(
                    tete, dstOffset = IntOffset(gauche.toInt(), haut.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()),
                    filterQuality = FilterQuality.Medium
                )
                // Œil : éclat qui pulse, clignement toutes les ~5 s.
                val oeil = Offset(gauche + dw * 0.603f, haut + dh * 0.39f)
                val phase = t % 5.3f
                val cligne = if (phase > 5.12f) 0.12f else 1f
                val eclat = ((0.55f + 0.3f * pouls) * (0.75f + 0.45f * intensite) * cligne).coerceIn(0f, 1f)
                val ro = r * (0.13f + 0.05f * intensite)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = eclat), VioletDragon.copy(alpha = 0.65f * eclat), Color.Transparent), oeil, ro), ro, oeil)
                // Gueule : braise violette, plus vive quand il écoute/réfléchit, pulsée quand il parle.
                val gueule = Offset(gauche + dw * 0.69f, haut + dh * 0.665f)
                val feu = (0.18f + 0.3f * intensite + 0.4f * voix * (0.5f + 0.5f * sin(t * 12f))).coerceIn(0f, 1f)
                val rg = r * (0.28f + 0.06f * voix)
                drawCircle(Brush.radialGradient(listOf(VioletDragon.copy(alpha = feu), Color.Transparent), gueule, rg), rg, gueule)
                // Étincelles qui s'échappent de la gueule.
                if (intensite > 0.05f) for (i in 0 until 12) {
                    val p = (t * (0.45f + 0.25f * voix) + i / 12f) % 1f
                    val a = -0.9f + 0.15f * i + 0.2f * sin(t + i)
                    val pos = Offset(gueule.x + r * 0.75f * p * cos(a), gueule.y + r * 0.55f * p * sin(a) - r * 0.25f * p)
                    drawCircle(VioletDragon.copy(alpha = (1f - p) * 0.9f * intensite), r * 0.028f * (1f - p * 0.7f), pos)
                    drawCircle(Color.White.copy(alpha = (1f - p) * 0.6f * intensite), r * 0.011f * (1f - p), pos)
                }
            }
            // Vignettage pour fondre l'image dans le médaillon.
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color(0xCC05030B)), c, r * 0.95f), r * 0.95f, c)
        }

        if (cadre) {
            val trait = max(1.5f, r * 0.035f)
            drawCircle(accent.copy(alpha = 0.55f), r * 0.95f, c, style = Stroke(max(1f, r * 0.02f)))
            val tour = (t * (25f + 75f * intensite)) % 360f
            val boite = Size(r * 1.9f, r * 1.9f)
            val coin = Offset(c.x - r * 0.95f, c.y - r * 0.95f)
            rotate(tour, c) {
                drawArc(VioletDragon, 0f, 70f, false, coin, boite, style = Stroke(trait, cap = StrokeCap.Round))
                drawArc(VioletDragon, 180f, 70f, false, coin, boite, style = Stroke(trait, cap = StrokeCap.Round))
            }
            rotate(-tour * 1.4f, c) {
                drawArc(accent.copy(alpha = 0.8f), 95f, 40f, false, coin, boite, style = Stroke(trait * 0.6f, cap = StrokeCap.Round))
                drawArc(accent.copy(alpha = 0.8f), 275f, 40f, false, coin, boite, style = Stroke(trait * 0.6f, cap = StrokeCap.Round))
            }
        }
    }
}
