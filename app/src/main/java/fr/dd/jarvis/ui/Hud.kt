package fr.dd.jarvis.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue

val HudShape = CutCornerShape(12.dp)
val HudShapeSmall = CutCornerShape(8.dp)

@Composable
fun HudBackground(content: @Composable BoxScope.() -> Unit) {
    val accent = LocalAccent.current
    Box(Modifier.fillMaxSize().background(HudBg)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.12f),
                    radius = size.width * 1.1f
                )
            )
            val step = 32.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(accent.copy(alpha = 0.05f), Offset(x, 0f), Offset(x, size.height), 1f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(accent.copy(alpha = 0.05f), Offset(0f, y), Offset(size.width, y), 1f)
                y += step
            }
        }
        content()
    }
}

/** Tuile HUD : fond translucide teinté, bordure néon, coins coupés, repères lumineux aux angles. */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = LocalAccent.current,
    fillAlpha: Float = LocalTileAlpha.current,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(HudShape)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = fillAlpha + 0.05f), accent.copy(alpha = fillAlpha * 0.45f))))
            .border(1.dp, accent.copy(alpha = 0.65f), HudShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind {
                val len = 26.dp.toPx()
                val sw = 3.dp.toPx()
                val c = accent.copy(alpha = 0.95f)
                val cut = 12.dp.toPx()
                drawLine(c, Offset(cut + 2f, 1.5f), Offset(cut + len, 1.5f), sw)
                drawLine(c, Offset(size.width - cut - len, size.height - 1.5f), Offset(size.width - cut - 2f, size.height - 1.5f), sw)
            }
            .padding(12.dp)
    ) {
        if (title != null) {
            Text(
                title.uppercase(), color = accent, fontSize = 11.sp, letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

@Composable
fun HudButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val accent = LocalAccent.current
    val c = if (enabled) accent else accent.copy(alpha = 0.35f)
    Row(
        modifier
            .clip(HudShapeSmall)
            .background(c.copy(alpha = if (filled) 0.35f else 0.10f))
            .border(1.dp, c.copy(alpha = 0.8f), HudShapeSmall)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c, modifier = Modifier.size(18.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (text.isNotEmpty()) {
            Text(
                text.uppercase(), color = c, fontSize = 12.sp, letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** Petit bouton rond (micro, envoi, navigation). */
@Composable
fun HudRoundButton(icon: ImageVector, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val c = if (enabled) accent else accent.copy(alpha = 0.35f)
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(c.copy(alpha = if (active) 0.5f else 0.12f))
            .border(1.5.dp, c, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (active) Color.White else c, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun HudChip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier
            .clip(HudShapeSmall)
            .background(accent.copy(alpha = if (selected) 0.38f else 0.08f))
            .border(1.dp, accent.copy(alpha = if (selected) 0.95f else 0.5f), HudShapeSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = if (selected) Color.White else accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun HudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 5,
    password: Boolean = false
) {
    val accent = LocalAccent.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label?.let { { Text(it, fontSize = 12.sp) } },
        placeholder = placeholder?.let { { Text(it, fontSize = 13.sp) } },
        singleLine = singleLine,
        maxLines = maxLines,
        shape = HudShapeSmall,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = HudText,
            unfocusedTextColor = HudText,
            cursorColor = accent,
            focusedBorderColor = accent,
            unfocusedBorderColor = accent.copy(alpha = 0.45f),
            focusedLabelColor = accent,
            unfocusedLabelColor = HudDim,
            focusedPlaceholderColor = HudDim,
            unfocusedPlaceholderColor = HudDim,
            focusedContainerColor = accent.copy(alpha = 0.06f),
            unfocusedContainerColor = accent.copy(alpha = 0.04f)
        )
    )
}

@Composable
fun HudDivider() {
    val accent = LocalAccent.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.35f)))
}

/** Orbe animé de l'en-tête : tourne plus vite quand JARVIS réfléchit. */
@Composable
fun HudOrb(modifier: Modifier = Modifier.size(52.dp), active: Boolean = false) {
    val accent = LocalAccent.current
    val t = rememberInfiniteTransition(label = "orb")
    val rot by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (active) 2200 else 9000, easing = LinearEasing)),
        label = "rot"
    )
    val pulse by t.animateFloat(
        0.6f, 1f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(accent.copy(alpha = 0.22f * pulse), radius = r * 0.62f, center = c)
        drawCircle(accent, radius = r * 0.17f * (0.8f + 0.2f * pulse), center = c)
        drawCircle(accent.copy(alpha = 0.45f), radius = r * 0.95f, center = c, style = Stroke(1.5f))
        rotate(rot, c) {
            drawArc(accent, 0f, 80f, false, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Size(r * 1.6f, r * 1.6f), style = Stroke(4f, cap = StrokeCap.Round))
            drawArc(accent, 180f, 80f, false, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Size(r * 1.6f, r * 1.6f), style = Stroke(4f, cap = StrokeCap.Round))
        }
        rotate(-rot * 1.5f, c) {
            drawArc(accent.copy(alpha = 0.7f), 90f, 100f, false, Offset(c.x - r * 0.55f, c.y - r * 0.55f), Size(r * 1.1f, r * 1.1f), style = Stroke(2.5f, cap = StrokeCap.Round))
        }
    }
}

/** Sélecteur de couleur (cercles). [selected] null = « par défaut ». */
@Composable
fun ColorPalette(selected: Int?, onPick: (Int?) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EventPalette.take(10).forEach { col ->
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(col))
                    .border(if (selected == col) 3.dp else 1.dp, if (selected == col) Color.White else Color(0x55FFFFFF), CircleShape)
                    .clickable { onPick(col) }
            )
        }
    }
}
