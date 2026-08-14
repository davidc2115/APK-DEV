package com.jarvis.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JarvisColorScheme = darkColorScheme(
    background = JarvisBackground,
    surface = JarvisSurface,
    primary = OrbCyan,
    secondary = OrbViolet,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    // Jarvis est pensé dark-mode-first (esthétique orb) ; on garde une seule
    // palette volontairement plutôt que de suivre le thème système.
    val useDark = isSystemInDarkTheme() || true
    MaterialTheme(
        colorScheme = JarvisColorScheme,
        content = content
    )
}
