package com.jarvis.ai.ui.orb

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.ai.ui.theme.OrbCyan
import com.jarvis.ai.ui.theme.OrbIdle
import com.jarvis.ai.ui.theme.OrbViolet

/**
 * Sphère animée façon "Jarvis / Obsidian orb". Le rayon et l'intensité du glow
 * réagissent à [state] : pulsation lente en veille, vibration rapide en écoute,
 * rotation/pulsation en réflexion, halo stable en réponse vocale.
 */
@Composable
fun OrbView(state: OrbState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orb")

    val pulseDurationMs = when (state) {
        OrbState.IDLE -> 2600
        OrbState.LISTENING -> 700
        OrbState.THINKING -> 900
        OrbState.SPEAKING -> 500
        OrbState.ERROR -> 400
    }

    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = pulseDurationMs),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbPulse"
    )

    val coreColor = when (state) {
        OrbState.IDLE -> OrbIdle
        OrbState.LISTENING -> OrbCyan
        OrbState.THINKING -> OrbViolet
        OrbState.SPEAKING -> OrbCyan
        OrbState.ERROR -> Color(0xFFFF5C5C)
    }

    Canvas(modifier = modifier.size(220.dp)) {
        val radius = (size.minDimension / 2.2f) * pulse
        val center = Offset(size.width / 2f, size.height / 2f)

        // Halo externe
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(coreColor.copy(alpha = 0.35f), Color.Transparent),
                center = center,
                radius = radius * 1.8f
            ),
            radius = radius * 1.8f,
            center = center
        )
        // Cœur de l'orb
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(coreColor, coreColor.copy(alpha = 0.4f)),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
}
