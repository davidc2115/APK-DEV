package fr.dd.jarvis.societe

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Markdown (titres, tableaux, listes, séparateurs) → PDF A4. Les emojis sont retirés (absents des polices PDF). */
object PdfExporter {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    fun nettoyer(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp >= 0x1F000 || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D || cp in 0x2B00..0x2BFF) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
            .replace(Regex("\\[\\[([^\\]]+)\\]\\]"), "$1")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
            .replace("`", "")
            .replace(Regex("\\s{2,}$"), "")
            .trim()
    }

    private fun sansGras(s: String) = s.replace("**", "").replace(Regex("(?<!\\*)\\*(?!\\*)"), "")

    private sealed class Bloc {
        data class Titre(val niveau: Int, val texte: String) : Bloc()
        data class Texte(val texte: String, val puce: Boolean) : Bloc()
        data class Tableau(val lignes: List<List<String>>, val droite: List<Boolean>) : Bloc()
        object Trait : Bloc()
        object Vide : Bloc()
    }

    private fun parser(markdown: String): List<Bloc> {
        var texte = markdown.trimStart()
        if (texte.startsWith("---")) {
            val fin = texte.indexOf("\n---", 3)
            if (fin > 0) texte = texte.substring(fin + 4)
        }
        val out = mutableListOf<Bloc>()
        val lignes = texte.lines()
        var i = 0
        while (i < lignes.size) {
            val l = lignes[i].trimEnd()
            when {
                l.startsWith("|") -> {
                    val rows = mutableListOf<String>()
                    while (i < lignes.size && lignes[i].trimStart().startsWith("|")) rows += lignes[i].trim().also { i++ }
                    val cells = rows.map { r -> r.trim('|').split('|').map { it.trim() } }
                    val sep = cells.getOrNull(1)
                    val droite = sep?.map { it.endsWith(":") && !it.startsWith(":") } ?: emptyList()
                    val data = cells.filterIndexed { idx, _ -> idx != 1 }
                    out += Bloc.Tableau(data, droite)
                    continue
                }
                l.trim() == "---" || l.trim() == "***" -> out += Bloc.Trait
                l.startsWith("#") -> {
                    val n = l.takeWhile { it == '#' }.length
                    out += Bloc.Titre(n, l.drop(n).trim())
                }
                l.isBlank() -> out += Bloc.Vide
                l.trimStart().startsWith("- ") -> out += Bloc.Texte(l.trimStart().drop(2), true)
                else -> out += Bloc.Texte(l, false)
            }
            i++
        }
        return out
    }

    fun toPdf(markdown: String): ByteArray {
        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNum).create())
        var canvas: Canvas = page.canvas
        var y = M

        fun nouvellePage() {
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNum).create())
            canvas = page.canvas
            y = M
        }

        fun paint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 24, 31); textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        fun layout(t: String, p: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
            StaticLayout.Builder.obtain(t, 0, t.length, p, max(width, 10)).setAlignment(align).setLineSpacing(0f, 1.1f).build()

        val largeur = (W - 2 * M).toInt()

        for (b in parser(markdown)) {
            when (b) {
                is Bloc.Vide -> y += 5f
                is Bloc.Trait -> {
                    if (y + 12 > H - M) nouvellePage()
                    val p = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
                    canvas.drawLine(M, y + 4, W - M, y + 4, p); y += 12f
                }
                is Bloc.Titre -> {
                    val size = when (b.niveau) { 1 -> 20f; 2 -> 15f; else -> 12.5f }
                    val t = nettoyer(sansGras(b.texte))
                    val lay = layout(t, paint(size, true), largeur)
                    if (y + lay.height + 8 > H - M) nouvellePage()
                    y += if (b.niveau == 1) 0f else 6f
                    canvas.save(); canvas.translate(M, y); lay.draw(canvas); canvas.restore()
                    y += lay.height + 6f
                }
                is Bloc.Texte -> {
                    val gras = b.texte.contains("**")
                    val t = (if (b.puce) "•  " else "") + nettoyer(sansGras(b.texte))
                    if (t.isBlank()) continue
                    val lay = layout(t, paint(10.5f, gras && b.texte.trim().startsWith("**")), largeur - (if (b.puce) 10 else 0))
                    if (y + lay.height > H - M) nouvellePage()
                    canvas.save(); canvas.translate(M + (if (b.puce) 10f else 0f), y); lay.draw(canvas); canvas.restore()
                    y += lay.height + 2f
                }
                is Bloc.Tableau -> {
                    val nCols = b.lignes.maxOfOrNull { it.size } ?: 0
                    if (nCols == 0) continue
                    val poids = (0 until nCols).map { c ->
                        (b.lignes.maxOf { r -> nettoyer(sansGras(r.getOrElse(c) { "" })).length }).coerceIn(6, 40).toFloat()
                    }
                    val total = poids.sum()
                    val widths = poids.map { largeur * it / total }
                    b.lignes.forEachIndexed { ri, row ->
                        val ligneGrasse = ri == 0 || row.any { it.contains("**") }
                        val lays = (0 until nCols).map { c ->
                            val t = nettoyer(sansGras(row.getOrElse(c) { "" }))
                            val align = if (b.droite.getOrElse(c) { false } && ri > 0) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
                            layout(t, paint(9.5f, ligneGrasse), (widths[c] - 8).toInt(), align)
                        }
                        val h = (lays.maxOf { it.height } + 8).toFloat()
                        if (y + h > H - M) nouvellePage()
                        if (ri == 0) {
                            val bg = Paint().apply { color = Color.rgb(232, 238, 246) }
                            canvas.drawRect(M, y, W - M, y + h, bg)
                        }
                        var x = M
                        lays.forEachIndexed { c, lay ->
                            canvas.save(); canvas.translate(x + 4, y + 4); lay.draw(canvas); canvas.restore()
                            x += widths[c]
                        }
                        val line = Paint().apply { color = Color.rgb(200, 205, 212); strokeWidth = 0.7f }
                        canvas.drawLine(M, y + h, W - M, y + h, line)
                        y += h
                    }
                    y += 6f
                }
            }
        }
        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
