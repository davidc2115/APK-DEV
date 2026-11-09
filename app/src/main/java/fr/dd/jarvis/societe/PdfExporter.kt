package fr.dd.jarvis.societe

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * Markdown (titres, tableaux, listes, séparateurs, images) → PDF A4.
 * Documents (devis, factures, paie) : emojis retirés, mise en page sobre.
 * Rapports d'intervention ([rapport] = true) : emojis dessinés en couleur (images), **gras** dans le texte, titres de
 * section colorés et soulignés, tableaux zébrés, tableau « clé / valeur » sans ligne d'en-tête.
 */
object PdfExporter {
    private const val W = 595
    private const val H = 842
    private const val M = 40f
    private val ENCRE = Color.rgb(20, 24, 31)
    private val ACCENT = Color.rgb(23, 59, 110)

    fun nettoyer(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp >= 0x1F000 || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D || cp in 0x2B00..0x2BFF) continue
            sb.appendCodePoint(cp)
        }
        return liens(sb.toString())
    }

    /** Liens et code Markdown retirés (le texte reste). */
    private fun liens(s: String): String = s
        .replace(Regex("\\[\\[([^\\]]+)\\]\\]"), "$1")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .replace("`", "")
        .replace(Regex("\\s{2,}$"), "")
        .trim()

    private fun sansGras(s: String) = s.replace("**", "").replace(Regex("(?<!\\*)\\*(?!\\*)"), "")

    // ------------------------------------------------------------------ emojis (rapports)

    private fun estEmoji(cp: Int) = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
        cp in 0x2300..0x23FF || cp == 0x2139 || cp == 0x203C || cp == 0x2049 || cp == 0x00A9 || cp == 0x00AE

    /** Plages des emojis (séquences comprises : variation, ZWJ, teinte, drapeaux, touches). */
    internal fun plagesEmoji(s: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!estEmoji(cp)) { i += Character.charCount(cp); continue }
            val debut = i
            i += Character.charCount(cp)
            while (i < s.length) {
                val n = s.codePointAt(i)
                when {
                    n == 0xFE0F || n == 0x20E3 || n in 0x1F3FB..0x1F3FF || n in 0xE0020..0xE007F -> i += Character.charCount(n)
                    n == 0x200D && i + 1 < s.length -> { i += 1; val m = s.codePointAt(i); i += Character.charCount(m) }
                    cp in 0x1F1E6..0x1F1FF && n in 0x1F1E6..0x1F1FF && i - debut == 2 -> i += Character.charCount(n)
                    else -> break
                }
            }
            out += debut until i
        }
        return out
    }

    internal fun commenceParEmoji(s: String) = plagesEmoji(s.trimStart()).firstOrNull()?.first == 0

    private val cacheEmoji = HashMap<String, Bitmap?>()

    /** Emoji dessiné en couleur dans une image (police emoji du téléphone), réutilisée pour tout le document. */
    private fun imageEmoji(e: String): Bitmap? = synchronized(cacheEmoji) {
        cacheEmoji.getOrPut(e) {
            runCatching {
                val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 96f }
                val fm = p.fontMetricsInt
                val w = ceil(p.measureText(e).toDouble()).toInt().coerceAtLeast(1)
                val h = (fm.descent - fm.ascent).coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawText(e, 0f, -fm.ascent.toFloat(), p) }
            }.getOrNull()
        }
    }

    /** Texte d'un rapport : **gras** et emojis en images, au corps [taille]. */
    private fun riche(brut: String, taille: Float): CharSequence {
        val sb = SpannableStringBuilder()
        liens(brut).replace(Regex("(?<!\\*)\\*(?!\\*)"), "").split("**").forEachIndexed { k, morceau ->
            val debut = sb.length
            sb.append(morceau)
            if (k % 2 == 1 && morceau.isNotEmpty()) sb.setSpan(StyleSpan(Typeface.BOLD), debut, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val s = sb.toString()
        for (r in plagesEmoji(s)) {
            val bmp = imageEmoji(s.substring(r)) ?: continue
            val hauteur = (taille * 1.12f).toInt().coerceAtLeast(1)
            val largeur = (hauteur.toFloat() * bmp.width / bmp.height).toInt().coerceAtLeast(1)
            val d = BitmapDrawable(Resources.getSystem(), bmp).apply { setBounds(0, 0, largeur, hauteur); isFilterBitmap = true }
            sb.setSpan(ImageSpan(d, DynamicDrawableSpan.ALIGN_BASELINE), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    // ------------------------------------------------------------------ lecture du Markdown

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

    // ------------------------------------------------------------------ dessin

    /** Pages successives où dessiner (PDF réel, ou images pour un aperçu / un test). */
    interface Pages {
        fun nouvelle(): Canvas
        fun finir()
    }

    /** [logo] : dessiné en haute qualité (bitmap d'origine, filtré, jamais agrandi au-delà de sa résolution réelle) en
     *  haut de la première page, au-dessus du contenu — qui démarre ensuite en dessous, sans jamais le chevaucher. */
    fun toPdf(markdown: String, logo: Bitmap? = null, rapport: Boolean = false, images: (String) -> Bitmap? = { null }): ByteArray {
        val doc = PdfDocument()
        var numero = 0
        var page: PdfDocument.Page? = null
        dessiner(markdown, logo, rapport, images, object : Pages {
            override fun nouvelle(): Canvas {
                page?.let { doc.finishPage(it) }
                numero++
                return doc.startPage(PdfDocument.PageInfo.Builder(W, H, numero).create()).also { page = it }.canvas
            }
            override fun finir() { page?.let { doc.finishPage(it) }; page = null }
        })
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    /** Aperçu : pages en images (fond blanc), [echelle] = pixels par point PDF. */
    fun apercu(markdown: String, logo: Bitmap? = null, rapport: Boolean = false, echelle: Float = 1.5f, images: (String) -> Bitmap? = { null }): List<Bitmap> {
        val pages = mutableListOf<Bitmap>()
        dessiner(markdown, logo, rapport, images, object : Pages {
            override fun nouvelle(): Canvas {
                val b = Bitmap.createBitmap((W * echelle).toInt(), (H * echelle).toInt(), Bitmap.Config.ARGB_8888)
                pages += b
                return Canvas(b).apply { drawColor(Color.WHITE); scale(echelle, echelle) }
            }
            override fun finir() {}
        })
        return pages
    }

    internal fun dessiner(markdown: String, logo: Bitmap?, rapport: Boolean, images: (String) -> Bitmap?, pages: Pages) {
        var canvas: Canvas = pages.nouvelle()
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

        fun nouvellePage() { canvas = pages.nouvelle(); y = M }

        fun paint(size: Float, bold: Boolean = false, couleur: Int = ENCRE) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = couleur; textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        fun layout(t: CharSequence, p: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
            StaticLayout.Builder.obtain(t, 0, t.length, p, max(width, 10)).setAlignment(align).setLineSpacing(0f, 1.15f).build()

        /** Texte prêt à dessiner : enrichi (rapport) ou nettoyé (documents). */
        fun texte(brut: String, taille: Float): CharSequence = if (rapport) riche(brut, taille) else nettoyer(sansGras(brut))

        val largeur = (W - 2 * M).toInt()
        val blocs = parser(markdown)

        for ((idx, b) in blocs.withIndex()) {
            when (b) {
                is Bloc.Vide -> y += if (rapport) 3f else 5f
                is Bloc.Trait -> {
                    if (y + 12 > H - M) nouvellePage()
                    val p = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
                    canvas.drawLine(M, y + 4, W - M, y + 4, p); y += 12f
                }
                is Bloc.Titre -> {
                    val size = when (b.niveau) { 1 -> 20f; 2 -> if (rapport) 13.5f else 15f; else -> 12.5f }
                    val couleur = if (rapport && b.niveau <= 2) ACCENT else ENCRE
                    val t = if (rapport) riche(b.texte.replace("**", ""), size) else nettoyer(sansGras(b.texte))
                    val lay = layout(t, paint(size, true, couleur), largeur)
                    // Un titre de section ne reste jamais seul en bas de page.
                    val suite = if (b.niveau >= 2) 40f else 0f
                    if (y + lay.height + 8 + suite > H - M) nouvellePage()
                    y += if (b.niveau == 1) 0f else if (rapport) 10f else 6f
                    canvas.save(); canvas.translate(M, y); lay.draw(canvas); canvas.restore()
                    y += lay.height + (if (rapport && b.niveau <= 2) 3f else 6f)
                    if (rapport && b.niveau <= 2) {
                        val trait = Paint().apply { color = if (b.niveau == 1) ACCENT else Color.rgb(205, 214, 228); strokeWidth = if (b.niveau == 1) 1.6f else 0.8f }
                        canvas.drawLine(M, y, W - M, y, trait)
                        y += 6f
                    }
                }
                is Bloc.Texte -> {
                    val gras = b.texte.contains("**")
                    val pucePropre = b.puce && !(rapport && commenceParEmoji(b.texte))
                    val retrait = if (b.puce) (if (rapport) 6f else 10f) else 0f
                    val t: CharSequence = if (rapport) {
                        val corps = riche(b.texte, 10.5f)
                        if (pucePropre) SpannableStringBuilder("•  ").append(corps) else corps
                    } else (if (b.puce) "•  " else "") + nettoyer(sansGras(b.texte))
                    if (t.isBlank()) continue
                    val enGras = !rapport && gras && b.texte.trim().startsWith("**")
                    val lay = layout(t, paint(10.5f, enGras), largeur - retrait.toInt())
                    if (y + lay.height > H - M) nouvellePage()
                    canvas.save(); canvas.translate(M + retrait, y); lay.draw(canvas); canvas.restore()
                    y += lay.height + (if (rapport && b.puce) 3f else 2f)
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
                    // En-tête entièrement vide (« | | | ») : tableau clé / valeur, sans ligne d'en-tête.
                    val sansEntete = rapport && b.lignes.firstOrNull()?.all { it.isBlank() } == true
                    val lignes = if (sansEntete) b.lignes.drop(1) else b.lignes
                    if (lignes.isEmpty()) continue
                    val poids = (0 until nCols).map { c ->
                        (lignes.maxOf { r -> nettoyer(sansGras(r.getOrElse(c) { "" })).length }).coerceIn(6, 40).toFloat()
                    }
                    val total = poids.sum()
                    val widths = if (sansEntete && nCols == 2) listOf(largeur * 0.30f, largeur * 0.70f) else poids.map { largeur * it / total }
                    // Le tableau suit son titre : s'il ne tient pas en entier et que la page est déjà bien remplie, page suivante.
                    lignes.forEachIndexed { ri, row ->
                        val entete = ri == 0 && !sansEntete
                        val ligneGrasse = entete || (!rapport && row.any { it.contains("**") })
                        val lays = (0 until nCols).map { c ->
                            val brut = row.getOrElse(c) { "" }
                            val align = if (b.droite.getOrElse(c) { false } && (rapport || !entete)) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
                            val t = if (rapport) riche(if (entete) "**${brut.replace("**", "")}**" else brut, 9.5f) else nettoyer(sansGras(brut))
                            layout(t, paint(9.5f, ligneGrasse && !rapport), (widths[c] - 8).toInt(), align)
                        }
                        val h = (lays.maxOf { it.height } + (if (rapport) 9 else 8)).toFloat()
                        if (y + h > H - M) nouvellePage()
                        when {
                            entete -> canvas.drawRect(M, y, W - M, y + h, Paint().apply { color = Color.rgb(232, 238, 246) })
                            rapport && ri % 2 == (if (sansEntete) 0 else 1) -> canvas.drawRect(M, y, W - M, y + h, Paint().apply { color = Color.rgb(247, 249, 252) })
                        }
                        var x = M
                        lays.forEachIndexed { c, lay ->
                            canvas.save(); canvas.translate(x + 4, y + (if (rapport) 4.5f else 4f)); lay.draw(canvas); canvas.restore()
                            x += widths[c]
                        }
                        val line = Paint().apply { color = Color.rgb(214, 219, 226); strokeWidth = 0.6f }
                        canvas.drawLine(M, y + h, W - M, y + h, line)
                        y += h
                    }
                    y += 6f
                }
            }
        }
        pages.finir()
    }
}
