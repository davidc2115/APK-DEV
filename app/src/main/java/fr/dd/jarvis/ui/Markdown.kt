package fr.dd.jarvis.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
    var body = text.trimStart()
    if (body.startsWith("---\n")) {
        val fin = body.indexOf("\n---", 3)
        if (fin > 0) body = body.substring(fin + 4)
    }
    val lines = body.lines()
    Column(modifier) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            i++
            when {
                line.trimStart().startsWith("|") -> {
                    val rows = mutableListOf(line)
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += lines[i].trimEnd(); i++ }
                    Table(rows, color, accent, ctx)
                }
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

@Composable
private fun Table(rows: List<String>, color: Color, accent: Color, ctx: Context) {
    val cells = rows.map { r -> r.trim().trim('|').split('|').map { it.trim() } }
    val right = cells.getOrNull(1)?.map { it.endsWith(":") && !it.startsWith(":") } ?: emptyList()
    val data = cells.filterIndexed { idx, c -> !(idx == 1 && c.all { it.matches(Regex(":?-{2,}:?")) }) }
    val cols = data.maxOfOrNull { it.size } ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        data.forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth().background(if (ri == 0) accent.copy(alpha = 0.14f) else Color.Transparent).padding(vertical = 3.dp, horizontal = 2.dp)) {
                for (c in 0 until cols) {
                    val t = row.getOrElse(c) { "" }
                    val first = c == 0
                    Box(Modifier.weight(if (first && cols > 2) 2.2f else 1f).padding(horizontal = 3.dp)) {
                        androidx.compose.material3.Text(
                            inline(t, accent), color = color, fontSize = 12.sp,
                            fontWeight = if (ri == 0) FontWeight.Bold else FontWeight.Normal,
                            textAlign = if (right.getOrElse(c) { false } && ri > 0) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            HudDivider()
        }
    }
}
