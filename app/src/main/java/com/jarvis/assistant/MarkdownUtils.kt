package com.jarvis.assistant

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Rendu markdown minimal (gras **texte**, code `texte`, titres #, listes -)
 * pour éviter d'afficher les astérisques bruts dans les bulles de chat.
 */
object MarkdownUtils {

    private val headerRegex = Regex("^#{1,6}\\s+(.*)$", RegexOption.MULTILINE)
    private val bulletRegex = Regex("^[-*]\\s+", RegexOption.MULTILINE)
    private val combinedRegex = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`(.+?)`")
    private val boldOnlyRegex = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")
    private val codeOnlyRegex = Regex("`(.+?)`")

    fun toSpannable(raw: String): CharSequence {
        var text = headerRegex.replace(raw) { "**${it.groupValues[1]}**" }
        text = bulletRegex.replace(text) { "• " }

        val builder = SpannableStringBuilder()
        var lastEnd = 0
        for (match in combinedRegex.findAll(text)) {
            builder.append(text.substring(lastEnd, match.range.first))
            val boldContent = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val codeContent = match.groupValues[3]
            if (codeContent.isNotEmpty()) {
                val start = builder.length
                builder.append(codeContent)
                builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val start = builder.length
                builder.append(boldContent)
                builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            lastEnd = match.range.last + 1
        }
        builder.append(text.substring(lastEnd))
        return builder
    }

    fun stripForSpeech(raw: String): String {
        var text = boldOnlyRegex.replace(raw) { it.groupValues[1].ifEmpty { g -> g.groupValues[2] } }
        text = codeOnlyRegex.replace(text) { it.groupValues[1] }
        text = headerRegex.replace(text) { it.groupValues[1] }
        text = bulletRegex.replace(text, "")
        text = text.replace("*", "").replace("_", "").replace("#", "")
        return text
    }
}
