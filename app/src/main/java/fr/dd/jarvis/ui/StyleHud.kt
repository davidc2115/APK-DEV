package fr.dd.jarvis.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** Style de l'interface, choisi dans Réglages › Système › Apparence. */
enum class StyleHud(val id: String, val nom: String, val aide: String) {
    HUD("hud", "HUD animé", "Bordures lumineuses qui pulsent, coins coupés, ligne de scan discrète. Léger pour la batterie."),
    IRONMAN("ironman", "Iron Man", "Grille et cercles animés, éclat qui parcourt les bordures, sons d'interface. Plus spectaculaire, plus gourmand.");

    companion object { fun de(id: String) = entries.firstOrNull { it.id == id } ?: HUD }
}

val LocalStyleHud = staticCompositionLocalOf { StyleHud.HUD }

/**
 * Phase d'animation partagée par toute l'interface (0 → 1 en boucle). Elle n'est lue qu'au DESSIN : les tuiles se
 * redessinent sans être recomposées, et une seule horloge tourne pour tout l'écran (cadence réduite en style HUD).
 */
val LocalPhaseHud = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

/** Fournit le style et l'horloge d'animation. [anime] faux (économie d'énergie, animations Android coupées) : figé. */
@Composable
fun AmbianceHud(style: StyleHud, anime: Boolean, content: @Composable () -> Unit) {
    val phase = remember { mutableFloatStateOf(0.25f) }
    LaunchedEffect(style, anime) {
        if (!anime) { phase.floatValue = 0.25f; return@LaunchedEffect }
        val pas = if (style == StyleHud.IRONMAN) 33L else 66L
        val periode = if (style == StyleHud.IRONMAN) 6_000.0 else 9_000.0
        val debut = System.nanoTime()
        while (true) {
            val ms = (System.nanoTime() - debut) / 1_000_000.0
            phase.floatValue = ((ms % periode) / periode).toFloat()
            delay(pas)
        }
    }
    CompositionLocalProvider(LocalStyleHud provides style, LocalPhaseHud provides phase) { content() }
}

/**
 * Bordure HUD vivante : la lueur pulse doucement ; en style Iron Man, un éclat parcourt en plus le contour.
 * [base] : opacité moyenne du trait.
 */
fun Modifier.bordureHud(accent: Color, shape: Shape, largeur: Dp = 1.dp, base: Float = 0.65f): Modifier = composed {
    val style = LocalStyleHud.current
    val phase = LocalPhaseHud.current
    drawWithContent {
        drawContent()
        val p = phase.value
        val outline = shape.createOutline(size, layoutDirection, this)
        val vitesse = if (style == StyleHud.IRONMAN) 3f else 1f
        val pulse = 0.5f + 0.5f * sin(2f * PI.toFloat() * p * vitesse)
        drawOutline(outline, accent.copy(alpha = (base * (0.72f + 0.28f * pulse)).coerceIn(0f, 1f)), style = Stroke(largeur.toPx()))
        if (style == StyleHud.IRONMAN) {
            // Éclat qui fait le tour du cadre (dégradé balayé décalé avec la phase).
            val q = (p * 2f) % 1f
            val l = 0.07f
            val stops = listOf(
                (q - l).coerceIn(0f, 1f) to Color.Transparent,
                q.coerceIn(0f, 1f) to Color.White.copy(alpha = 0.85f),
                (q + l).coerceIn(0f, 1f) to Color.Transparent,
            ).distinctBy { it.first }
            if (stops.size >= 2) drawOutline(outline, Brush.sweepGradient(*stops.toTypedArray(), center = Offset(size.width / 2f, size.height / 2f)),
                style = Stroke(largeur.toPx() * 1.8f))
        }
    }
}

/** Petits sons d'interface du style Iron Man (désactivables). */
object SonsHud {
    private var tonalite: ToneGenerator? = null

    fun clic(style: StyleHud, actif: Boolean) {
        if (style != StyleHud.IRONMAN || !actif) return
        runCatching {
            val t = tonalite ?: ToneGenerator(AudioManager.STREAM_SYSTEM, 22).also { tonalite = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 18)
        }
    }

    fun ouverture(style: StyleHud, actif: Boolean) {
        if (style != StyleHud.IRONMAN || !actif) return
        runCatching {
            val t = tonalite ?: ToneGenerator(AudioManager.STREAM_SYSTEM, 22).also { tonalite = it }
            t.startTone(ToneGenerator.TONE_PROP_ACK, 60)
        }
    }
}
