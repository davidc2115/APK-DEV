package fr.dd.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

data class Accent(val id: String, val label: String, val color: Color)

val Accents = listOf(
    Accent("cyan", "Cyan", Color(0xFF00E5FF)),
    Accent("blue", "Bleu", Color(0xFF2F80FF)),
    Accent("green", "Vert", Color(0xFF00E676)),
    Accent("red", "Rouge", Color(0xFFFF2D55)),
    Accent("violet", "Violet", Color(0xFFA855F7)),
    Accent("orange", "Orange", Color(0xFFFF9100)),
    Accent("pink", "Rose", Color(0xFFFF4FA3))
)

fun accentById(id: String): Accent = Accents.firstOrNull { it.id == id } ?: Accents[0]

val LocalAccent = compositionLocalOf { Accents[0].color }
val LocalTileAlpha = compositionLocalOf { 0.16f }

val HudBg = Color(0xFF050A16)
val HudText = Color(0xFFE6F4FF)
val HudDim = Color(0xFF8FA8C0)

/** Palette proposée pour colorer les agendas et les rendez-vous. */
val EventPalette = listOf(
    0xFF00E5FF, 0xFF2F80FF, 0xFF00E676, 0xFFFFD600, 0xFFFF9100,
    0xFFFF2D55, 0xFFFF4FA3, 0xFFA855F7, 0xFF8D6E63, 0xFF90A4AE
).map { it.toInt() }

@Composable
fun HudTheme(accent: Color, tileAlpha: Float, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color.Black,
        secondary = accent,
        background = HudBg,
        surface = HudBg,
        onBackground = HudText,
        onSurface = HudText,
        surfaceVariant = Color(0xFF0B1424),
        outline = accent.copy(alpha = 0.5f)
    )
    CompositionLocalProvider(LocalAccent provides accent, LocalTileAlpha provides tileAlpha) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
