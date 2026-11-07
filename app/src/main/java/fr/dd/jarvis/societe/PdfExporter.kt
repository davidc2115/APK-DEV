package fr.dd.jarvis.societe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
        /** Images consécutives (« ![[nom]] » ou « ![texte](nom) ») : disposées en grille dans le PDF. */
        data class Images(val noms: List<String>) : Bloc()
        object Trait : Bloc()
        object Vide : Bloc()
    }

    /** Nom de l'image d'une ligne « ![[nom]] » / « ![texte](nom) », sinon null. */
    internal fun image(ligne: String): String? {
        val l = ligne.trim()
        Regex("^!\\[\\[([^\\]|]+)(\\|[^\\]]*)?]]$").find(l)?.let { return it.groupValues[1].trim() }
        Regex("^!\\[[^\\]]*]\\(([^)]+)\\)$").find(l)?.let { return it.groupValues[1].trim() }
        return null
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
                image(l) != null -> {
                    val noms = mutableListOf<String>()
                    while (i < lignes.size && image(lignes[i]) != null) { noms += image(lignes[i])!!; i++ }
                    out += Bloc.Images(noms)
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

    /** [logo] : dessiné en haute qualité (bitmap d'origine, filtré, jamais agrandi au-delà de sa résolution réelle) en
     *  haut de la première page, au-dessus du contenu — qui démarre ensuite en dessous, sans jamais le chevaucher. */
    fun toPdf(markdown: String, logo: Bitmap? = null, images: (String) -> Bitmap? = { null }): ByteArray {
        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNum).create())
        var canvas: Canvas = page.canvas
        var y = M

        if (logo != null && logo.width > 0 && logo.height > 0) {
            val maxW = 150f; val maxH = 85f
            // On ne force jamais une image en dessous de sa résolution réelle (en points PDF, 1pt ≈ 1,33 px à 96 dpi,
            // donc une image de quelques centaines de pixels reste largement nette à cette taille d'affichage) : seule
            // une image plus grande que la case réservée est réduite, jamais agrandie — garde le texte du logo net.
            val ratio = minOf(maxW / logo.width, maxH / logo.height, 1f)
            val w = logo.width * ratio; val h = logo.height * ratio
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            canvas.drawBitmap(logo, null, RectF(M, y, M + w, y + h), p)
            y += h + 12f
        }

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
                is Bloc.Images -> {
                    // Grille de 2 images par ligne (une seule, plus grande, si elle est seule ; signature : petite).
                    val pp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    val cadre = Paint().apply { color = Color.rgb(200, 205, 212); style = Paint.Style.STROKE; strokeWidth = 0.7f }
                    val parLigne = if (b.noms.size == 1) 1 else 2
                    b.noms.chunked(parLigne).forEach { rang ->
                        val bmps = rang.map { it to runCatching { images(it) }.getOrNull() }
                        val signature = rang.size == 1 && rang[0].contains("signature", true)
                        val cell = if (signature) 200f else (largeur - (parLigne - 1) * 10f) / parLigne
                        val hMax = if (signature) 90f else if (parLigne == 1) 300f else 210f
                        val tailles = bmps.map { (_, bm) -> if (bm == null || bm.width <= 0) cell to 14f else minOf(cell / bm.width, hMax / bm.height).let { r -> bm.width * r to bm.height * r } }
                        val h = tailles.maxOf { it.second }
                        if (y + h + 8 > H - M) nouvellePage()
                        var x = M
                        bmps.forEachIndexed { k, (nom, bm) ->
                            val (w, hh) = tailles[k]
                            if (bm != null) {
                                val r = RectF(x, y, x + w, y + hh)
                                canvas.drawBitmap(bm, null, r, pp); canvas.drawRect(r, cadre)
                            } else {
                                val lay = layout("[Photo : ${nettoyer(nom)}]", paint(9.5f), cell.toInt())
                                canvas.save(); canvas.translate(x, y); lay.draw(canvas); canvas.restore()
                            }
                            x += cell + 10f
                        }
                        y += h + 8f
                    }
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
