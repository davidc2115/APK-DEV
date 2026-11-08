package fr.dd.jarvis.ui

import androidx.compose.foundation.Canvas
import kotlin.math.pow
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
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
import fr.dd.jarvis.voice.Presence
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Violet de l'œil et de la gueule du dragon (image de référence). */
private val VioletDragon = Color(0xFFB06CFF)

/** Mâchoire inférieure dans l'image du dragon (680 × 600 px) : contour et charnière, pour la faire bouger avec la voix. */
private val MACHOIRE = listOf(395f to 360f, 455f to 365f, 500f to 400f, 545f to 450f, 585f to 495f, 585f to 530f,
    540f to 555f, 470f to 560f, 410f to 545f, 370f to 500f, 360f to 430f)
private const val CHARNIERE_X = 390f
private const val CHARNIERE_Y = 380f

/** Angle de la mâchoire (degrés, positif = gueule plus ouverte) : légère respiration au repos, suit la voix quand il parle. */
internal fun angleMachoire(parle: Float, niveau: Float, t: Float): Float {
    val repos = -3f + 1.5f * sin(t * 1.6f)
    val voix = -6f + 13f * niveau.coerceIn(0f, 1f)
    return repos + (voix - repos) * parle.coerceIn(0f, 1f)
}

/**
 * Tête du serpent-dragon VIVANTE (remplace l'orbe) : la tête « respire » et ondule doucement, l'œil brille et cligne,
 * la gueule rougeoie de violet. Avec [vivant], il suit l'état réel de JARVIS (Presence) : il écoute (anneaux qui
 * suivent ta voix au micro), réfléchit (étincelles, cercles qui accélèrent) et PARLE — sa mâchoire s'ouvre et se
 * ferme au rythme exact de la voix jouée (synchronisation labiale). [active]/[parle] restent possibles pour forcer.
 * Peinte à partir de l'image du dragon (gueule ouverte, œil lumineux) dans un médaillon cerclé façon HUD.
 */
@Composable
fun TeteDragonAnimee(modifier: Modifier = Modifier.size(52.dp), active: Boolean = false, parle: Boolean = false, cadre: Boolean = true, vivant: Boolean = true,
                     apercu: Float? = null) {
    val accent = LocalAccent.current
    val tete = ImageBitmap.imageResource(R.drawable.dragon_tete)
    // Horloge d'animation BRIDÉE : 30 images/s quand JARVIS écoute, réfléchit ou parle, 20 au repos (8 en économie
    // d'énergie) au lieu de 60 à 120 : le médaillon est affiché en permanence dans l'en-tête, c'était le premier poste de
    // consommation de l'appli ouverte. Synchronisée sur l'horloge d'images : s'arrête d'elle-même appli en arrière-plan.
    val animeForce = androidx.compose.runtime.rememberUpdatedState(active || parle)
    val temps by produceState(0f) {
        val debut = withFrameNanos { it }
        while (true) {
            withFrameNanos { value = (it - debut) / 1_000_000_000f }
            val anime = animeForce.value || Presence.parle || Presence.ecoute || Presence.etat == Presence.Etat.REFLEXION
            kotlinx.coroutines.delay(when { anime -> 30L; fr.dd.jarvis.device.Energie.economie -> 120L; else -> 48L })
        }
    }
    // Valeurs lissées d'une image à l'autre : intensité, parole, niveau de la voix, niveau du micro.
    val lisse = remember { FloatArray(4) }
    val tPrecedent = remember { FloatArray(1) { -1f } }

    Canvas(modifier) {
        val t = temps
        val c = center
        val r = size.minDimension / 2f
        val pouls = 0.5f + 0.5f * sin(t * 2.2f)

        val ecouteP = vivant && Presence.ecoute
        val reflechitP = vivant && Presence.etat == Presence.Etat.REFLEXION
        val parleReel = vivant && Presence.parle
        val parleP = parle || parleReel
        // Lissage indépendant du nombre d'images par seconde (coefficients réglés pour 60 images/s).
        val dt = if (tPrecedent[0] < 0f) 1f / 60f else (t - tPrecedent[0]).coerceIn(0f, 0.5f)
        tPrecedent[0] = t
        fun a(x: Float) = 1f - (1f - x).pow(dt * 60f)
        lisse[0] += ((if (active || parleP || ecouteP || reflechitP) 1f else 0f) - lisse[0]) * a(0.06f)
        lisse[1] += ((if (parleP) 1f else 0f) - lisse[1]) * a(0.15f)
        val cibleNiveau = when { parleReel -> Presence.niveau(); parleP -> 0.45f + 0.45f * sin(t * 12f); else -> 0f }
        lisse[2] += (cibleNiveau - lisse[2]) * a(if (cibleNiveau > lisse[2]) 0.6f else 0.22f)
        lisse[3] += ((if (ecouteP) Presence.micro else 0f) - lisse[3]) * a(0.3f)
        // [apercu] (captures de test) : niveau de voix imposé, sans lissage.
        if (apercu != null) { lisse[0] = 1f; lisse[1] = 1f; lisse[2] = apercu }
        val intensite = lisse[0]; val voix = lisse[1]; val niveau = lisse[2]; val micro = lisse[3]

        // Halo violet autour du médaillon (bat avec la voix).
        drawCircle(
            Brush.radialGradient(listOf(VioletDragon.copy(alpha = (0.18f + 0.28f * intensite * pouls + 0.25f * niveau).coerceAtMost(0.8f)), Color.Transparent), c, r * 1.05f),
            r * 1.05f, c
        )
        // Écoute : anneaux qui s'élargissent avec ta voix.
        if (micro > 0.02f || ecouteP) for (k in 0 until 3) {
            val p = (t * 0.8f + k / 3f) % 1f
            drawCircle(accent.copy(alpha = (1f - p) * (0.25f + 0.6f * micro)), r * (0.97f + 0.12f * p + 0.06f * micro), c, style = Stroke(max(1f, r * 0.012f)))
        }
        // Fond spatial du médaillon.
        drawCircle(Brush.radialGradient(listOf(Color(0xFF1C0B33), Color(0xFF05030B)), c, r), r * 0.95f, c)

        val disque = Path().apply { addOval(Rect(c, r * 0.95f)) }
        clipPath(disque) {
            // Respiration, flottement, balancement du cou (plus marqué quand il parle).
            val respire = 1f + 0.025f * sin(t * 1.6f) + 0.02f * intensite * sin(t * 4.5f) + 0.015f * voix * niveau
            val flotte = r * (0.035f * sin(t * 1.1f) + 0.012f * voix * sin(t * 9f))
            val angle = 2.5f * sin(t * 0.8f) + 1.6f * voix * (niveau - 0.4f)
            val dw = r * 2.3f
            val dh = dw * tete.height / tete.width
            val gauche = c.x - dw * 0.57f
            val haut = c.y - dh * 0.46f + flotte
            fun pt(x: Float, y: Float) = Offset(gauche + x / tete.width * dw, haut + y / tete.height * dh)
            val machoire = Path().apply {
                MACHOIRE.forEachIndexed { i, (x, y) -> val p = pt(x * tete.width / 680f, y * tete.height / 600f); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                close()
            }
            val charniere = pt(CHARNIERE_X * tete.width / 680f, CHARNIERE_Y * tete.height / 600f)
            val dst = IntOffset(gauche.toInt(), haut.toInt()); val taille = IntSize(dw.toInt(), dh.toInt())
            withTransform({
                rotate(angle, pivot = Offset(c.x - r * 0.75f, c.y + r * 0.7f))
                scale(respire, respire, pivot = c)
            }) {
                // Tête sans la mâchoire, intérieur de la gueule, puis mâchoire tournée autour de sa charnière.
                clipPath(machoire, ClipOp.Difference) { drawImage(tete, dstOffset = dst, dstSize = taille, filterQuality = FilterQuality.Medium) }
                val fond = pt(525f * tete.width / 680f, 430f * tete.height / 600f)
                clipPath(machoire) {
                    drawCircle(Brush.radialGradient(listOf(Color(0xE6461478).copy(alpha = 0.75f + 0.25f * niveau), Color.Transparent), fond, dw * 0.14f), dw * 0.14f, fond)
                }
                rotate(angleMachoire(voix, niveau, t), pivot = charniere) {
                    clipPath(machoire) { drawImage(tete, dstOffset = dst, dstSize = taille, filterQuality = FilterQuality.Medium) }
                }
                // Œil : éclat qui pulse, clignement toutes les ~5 s ; plus vif quand il t'écoute.
                val oeil = Offset(gauche + dw * 0.603f, haut + dh * 0.39f)
                val phase = t % 5.3f
                val cligne = if (phase > 5.12f) 0.12f else 1f
                val eclat = ((0.55f + 0.3f * pouls) * (0.75f + 0.45f * intensite) * cligne + 0.25f * micro).coerceIn(0f, 1f)
                val ro = r * (0.13f + 0.05f * intensite + 0.04f * micro)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = eclat), VioletDragon.copy(alpha = 0.65f * eclat), Color.Transparent), oeil, ro), ro, oeil)
                // Gueule : braise violette, plus vive quand il écoute/réfléchit, qui suit la voix quand il parle.
                val gueule = Offset(gauche + dw * 0.69f, haut + dh * 0.665f)
                val feu = (0.18f + 0.3f * intensite + 0.55f * voix * niveau).coerceIn(0f, 1f)
                val rg = r * (0.26f + 0.08f * niveau)
                drawCircle(Brush.radialGradient(listOf(VioletDragon.copy(alpha = feu), Color.Transparent), gueule, rg), rg, gueule)
                // Étincelles qui s'échappent de la gueule.
                if (intensite > 0.05f) for (i in 0 until 12) {
                    val p = (t * (0.45f + 0.25f * voix + 0.3f * niveau) + i / 12f) % 1f
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
            // Les arcs tournent plus vite quand il réfléchit.
            val tour = (t * (25f + 75f * intensite + (if (reflechitP) 90f else 0f))) % 360f
            val boite = Size(r * 1.9f, r * 1.9f)
            val coin = Offset(c.x - r * 0.95f, c.y - r * 0.95f)
            rotate(tour, c) {
                drawArc(VioletDragon, 0f, 70f + 30f * niveau, false, coin, boite, style = Stroke(trait, cap = StrokeCap.Round))
                drawArc(VioletDragon, 180f, 70f + 30f * niveau, false, coin, boite, style = Stroke(trait, cap = StrokeCap.Round))
            }
            rotate(-tour * 1.4f, c) {
                drawArc(accent.copy(alpha = 0.8f), 95f, 40f, false, coin, boite, style = Stroke(trait * 0.6f, cap = StrokeCap.Round))
                drawArc(accent.copy(alpha = 0.8f), 275f, 40f, false, coin, boite, style = Stroke(trait * 0.6f, cap = StrokeCap.Round))
            }
        }
    }
}
