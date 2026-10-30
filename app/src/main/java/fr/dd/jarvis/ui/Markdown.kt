package fr.dd.jarvis.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
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

/** Rendu Markdown minimal : titres, tableaux, listes, gras, liens cliquables ; plannings (🕗) en cartes par événement. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = HudText) {
    val accent = LocalAccent.current
    var body = text.trimStart()
    if (body.startsWith("---\n")) {
        val fin = body.indexOf("\n---", 3)
        if (fin > 0) body = body.substring(fin + 4)
    }
    val lines = body.lines()
    if (!body.contains("🕗")) {
        Column(modifier) { MdLines(lines, color, accent) }
        return
    }
    // Mode planning : un bloc visuel par événement (## …), titres de jour en grand, séparateurs --- supprimés.
    val blocs = mutableListOf<Pair<Boolean, MutableList<String>>>() // (carte ?, lignes)
    for (l in lines) {
        val t = l.trim()
        when {
            t == "---" || t == "***" -> continue
            l.startsWith("## ") -> blocs += true to mutableListOf(l)
            l.startsWith("# ") -> blocs += false to mutableListOf(l)
            else -> if (blocs.isEmpty()) blocs += false to mutableListOf(l) else blocs.last().second += l
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((carte, ls) in blocs) {
            val contenu = ls.dropLastWhile { it.isBlank() }
            if (contenu.isEmpty()) continue
            if (carte) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.09f)).height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                ) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(accent.copy(alpha = 0.85f)))
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) { MdLines(contenu, color, accent) }
                }
            } else Column { MdLines(contenu, color, accent) }
        }
    }
}

@Composable
private fun MdLines(lines: List<String>, color: Color, accent: Color) {
    val ctx = LocalContext.current
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
            line.startsWith("![") && line.contains("jarvis-img:") -> {
                val name = Regex("""jarvis-img:([^)\s]+)""").find(line)?.groupValues?.get(1).orEmpty()
                ChatImage(name)
            }
            line.contains("jarvis-file:") -> {
                val m = Regex("""\[([^\]]*)]\(jarvis-file:([^)\s]+)\)""").find(line)
                if (m != null) FichierChip(m.groupValues[1], m.groupValues[2], accent) else Line(inline(line, accent), color, 14.sp, FontWeight.Normal, accent, ctx)
            }
            line.isBlank() -> Spacer(Modifier.height(4.dp))
            line.trim() == "---" || line.trim() == "***" -> {
                Spacer(Modifier.height(4.dp))
                HudDivider()
                Spacer(Modifier.height(4.dp))
            }
            line.startsWith("### ") -> Line(inline(line.removePrefix("### "), accent), color, 14.sp, FontWeight.SemiBold, accent, ctx)
            line.startsWith("## ") -> Line(inline(line.removePrefix("## "), accent), color, 15.sp, FontWeight.Bold, accent, ctx)
            line.startsWith("# ") -> Line(inline(line.removePrefix("# "), accent), accent, 18.sp, FontWeight.Bold, accent, ctx)
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                Line(inline("• " + line.trimStart().drop(2), accent), color, 14.sp, FontWeight.Normal, accent, ctx)
            else -> Line(inline(line, accent), color, 14.sp, FontWeight.Normal, accent, ctx)
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

@Composable
private fun ChatImage(name: String) {
    val ctx = LocalContext.current
    val file = java.io.File(java.io.File(ctx.filesDir, "images"), name.substringAfterLast('/'))
    val bmp = androidx.compose.runtime.remember(name) {
        runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)?.asImageBitmap()
        }.getOrNull()
    }
    if (bmp == null) { androidx.compose.material3.Text("🖼️ (image introuvable)", color = HudDim, fontSize = 12.sp); return }
    androidx.compose.foundation.Image(
        bmp, contentDescription = "Image générée",
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).clickable {
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Partager l'image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
    )
}


@Composable
private fun FichierChip(label: String, name: String, accent: Color) {
    val ctx = LocalContext.current
    val file = java.io.File(java.io.File(ctx.filesDir, "exports"), name.substringAfterLast('/'))
    androidx.compose.material3.Text(
        label, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.14f))
            .clickable {
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                    val mime = fr.dd.jarvis.ai.Fichiers.mimeDe(file.name)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Ouvrir " + file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }.padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
