package fr.dd.jarvis.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun openLink(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

private val inlineRegex = Regex("""\*\*(.+?)\*\*|\[([^\]]+)]\(([^)\s]+)\)""")

private fun inline(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRegex.findAll(text)) {
        append(text.substring(last, m.range.first))
        val bold = m.groups[1]
        if (bold != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.value) }
        } else {
            val label = m.groups[2]!!.value
            val url = m.groups[3]!!.value
            pushStringAnnotation("URL", url)
            withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(label) }
            pop()
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** Rendu Markdown minimal : titres, séparateurs, listes, gras et liens cliquables (http, tel:, geo:). */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = HudText) {
    val accent = LocalAccent.current
    val ctx = LocalContext.current
    Column(modifier) {
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line.trim() == "---" || line.trim() == "***" -> {
                    Spacer(Modifier.height(4.dp))
                    HudDivider()
                    Spacer(Modifier.height(4.dp))
                }
                line.startsWith("### ") -> Line(inline(line.removePrefix("### "), accent), color, 14.sp, FontWeight.SemiBold, accent, ctx)
                line.startsWith("## ") -> Line(inline(line.removePrefix("## "), accent), color, 16.sp, FontWeight.Bold, accent, ctx)
                line.startsWith("# ") -> Line(inline(line.removePrefix("# "), accent), accent, 18.sp, FontWeight.Bold, accent, ctx)
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                    Line(inline("• " + line.trimStart().drop(2), accent), color, 14.sp, FontWeight.Normal, accent, ctx)
                else -> Line(inline(line, accent), color, 14.sp, FontWeight.Normal, accent, ctx)
            }
        }
    }
}

@Composable
private fun Line(
    text: AnnotatedString, color: Color, size: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight, accent: Color, ctx: Context
) {
    ClickableText(
        text = text,
        modifier = Modifier.padding(vertical = 1.dp),
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, lineHeight = size * 1.35f, fontFamily = FontFamily.Default),
        onClick = { off ->
            text.getStringAnnotations("URL", off, off).firstOrNull()?.let { openLink(ctx, it.item) }
        }
    )
}
