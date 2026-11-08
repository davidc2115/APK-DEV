package fr.dd.jarvis.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Texte d'agenda (souvent du HTML de Google Agenda : <b>, <br>, <a href>, &nbsp;) affiché proprement : gras, italique,
 * retours à la ligne, puces ; numéros de téléphone (appel), liens et adresses e-mail cliquables.
 */
object TexteRiche {
    val TELEPHONE = Regex("""(?<![\d+])(?:\+33[\s.-]?|0)[1-9](?:[\s.-]?\d{2}){4}(?!\d)""")
    private val URL = Regex("""https?://[^\s<>"')]+""")
    private val MAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    private val JETON = Regex("""<[^>]*>|[^<]+""")
    private val HREF = Regex("""(?i)href\s*=\s*["']([^"']+)["']""")

    private fun entites(s: String): String = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }

    /** Construit le texte enrichi. [lien] : couleur des éléments cliquables. */
    fun construire(source: String, lien: Color): AnnotatedString {
        // Morceaux : (texte, gras, italique, souligné, lien explicite)
        data class Morceau(val t: String, val gras: Boolean, val ital: Boolean, val soul: Boolean, val href: String?)
        val morceaux = mutableListOf<Morceau>()
        var gras = 0; var ital = 0; var soul = 0; var href: String? = null
        val html = source.contains(Regex("<\\s*/?\\s*[a-zA-Z][^>]*>"))
        if (!html) morceaux += Morceau(source, false, false, false, null)
        else JETON.findAll(source).forEach { m ->
            val j = m.value
            if (!j.startsWith("<")) { morceaux += Morceau(entites(j).replace(Regex("[ \\t\\r\\n]+"), " "), gras > 0, ital > 0, soul > 0, href); return@forEach }
            val nom = j.trim('<', '>', ' ', '/').substringBefore(' ').lowercase()
            val fermant = j.startsWith("</")
            when (nom) {
                "br", "br/" -> morceaux += Morceau("\n", false, false, false, null)
                "p", "div", "tr", "h1", "h2", "h3", "h4" -> morceaux += Morceau("\n", false, false, false, null)
                "li" -> if (!fermant) morceaux += Morceau(if (morceaux.lastOrNull()?.t?.endsWith("\n") == true) "• " else "\n• ", false, false, false, null)
                "b", "strong" -> gras = (gras + if (fermant) -1 else 1).coerceAtLeast(0)
                "i", "em" -> ital = (ital + if (fermant) -1 else 1).coerceAtLeast(0)
                "u" -> soul = (soul + if (fermant) -1 else 1).coerceAtLeast(0)
                "a" -> href = if (fermant) null else HREF.find(j)?.groupValues?.get(1)
            }
        }
        // Nettoyage des espaces et des lignes vides en trop, en gardant les styles.
        val brut = morceaux.joinToString("") { it.t }
        val propre = StringBuilder()
        val styles = mutableListOf<Triple<IntRange, Morceau, Int>>()
        var i = 0
        morceaux.forEach { mo ->
            val debut = propre.length
            mo.t.forEach { c ->
                val dernier = propre.lastOrNull()
                when {
                    c == ' ' && (dernier == null || dernier == ' ' || dernier == '\n') -> Unit
                    c == '\n' && propre.endsWith("\n\n") -> Unit
                    c == '\n' && dernier == ' ' -> { propre.setLength(propre.length - 1); propre.append('\n') }
                    else -> propre.append(c)
                }
            }
            if (propre.length > debut) styles += Triple(debut until propre.length, mo, i)
            i++
        }
        val texte = propre.toString().trim('\n', ' ')
        val decalage = propre.indexOf(texte).coerceAtLeast(0)
        if (brut.isEmpty()) return AnnotatedString("")
        return buildAnnotatedString {
            append(texte)
            styles.forEach { (r, mo, _) ->
                val a = (r.first - decalage).coerceIn(0, texte.length); val b = (r.last + 1 - decalage).coerceIn(0, texte.length)
                if (a >= b) return@forEach
                if (mo.gras || mo.ital || mo.soul) addStyle(SpanStyle(
                    fontWeight = if (mo.gras) FontWeight.Bold else null, fontStyle = if (mo.ital) FontStyle.Italic else null,
                    textDecoration = if (mo.soul) TextDecoration.Underline else null), a, b)
                mo.href?.let { h -> addStringAnnotation("lien", h, a, b); addStyle(SpanStyle(color = lien, textDecoration = TextDecoration.Underline), a, b) }
            }
            // Numéros, liens et e-mails écrits en clair.
            TELEPHONE.findAll(texte).forEach { m ->
                addStringAnnotation("tel", m.value.filter { it.isDigit() || it == '+' }, m.range.first, m.range.last + 1)
                addStyle(SpanStyle(color = lien, textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
            }
            URL.findAll(texte).forEach { m ->
                addStringAnnotation("lien", m.value, m.range.first, m.range.last + 1)
                addStyle(SpanStyle(color = lien, textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
            }
            MAIL.findAll(texte).forEach { m ->
                addStringAnnotation("lien", "mailto:" + m.value, m.range.first, m.range.last + 1)
                addStyle(SpanStyle(color = lien, textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
            }
        }
    }

    fun ouvrir(ctx: Context, tag: String, valeur: String) {
        val intent = when (tag) {
            "tel" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$valeur"))
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(valeur))
        }
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Texte enrichi cliquable (téléphone → composeur, lien → navigateur, e-mail → messagerie). */
@Composable
fun TexteCliquable(source: String, couleur: Color, taille: TextUnit = 13.sp, lien: Color = LocalAccent.current,
                   gras: Boolean = false, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val texte = remember(source, lien) { TexteRiche.construire(source, lien) }
    ClickableText(texte, modifier, style = TextStyle(color = couleur, fontSize = taille, fontWeight = if (gras) FontWeight.Bold else null,
        lineHeight = taille * 1.35f)) { pos ->
        texte.getStringAnnotations(pos, pos).firstOrNull { it.tag == "tel" || it.tag == "lien" }?.let { TexteRiche.ouvrir(ctx, it.tag, it.item) }
    }
}
