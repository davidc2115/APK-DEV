package com.jarvis.ai.ui.chat

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

private val RICH_TOKEN = Regex("""\[([^\]]+)]\(([^)\s]+)\)|\*\*([^*]+)\*\*""")
private val HEADING = Regex("(?m)^(#{1,6})\\s*")

/**
 * Texte de chat avec liens cliquables : [libellé](https://...) ou (tel:...), et **gras**.
 * Les titres Markdown (#) sont affichés sans les dièses.
 */
@Composable
fun RichText(text: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) { buildRichText(text, linkColor) }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = LocalTextStyle.current.copy(color = LocalContentColor.current),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                runCatching { uriHandler.openUri(it.item) }
            }
        }
    )
}

private fun buildRichText(raw: String, linkColor: Color): AnnotatedString {
    val text = raw.replace(HEADING, "")
    return buildAnnotatedString {
        var last = 0
        for (m in RICH_TOKEN.findAll(text)) {
            append(text.substring(last, m.range.first))
            if (m.groups[1] != null) {
                pushStringAnnotation("URL", m.groupValues[2])
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(m.groupValues[1])
                }
                pop()
            } else {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[3]) }
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}
