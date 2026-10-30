package fr.dd.jarvis.ai

import android.content.ContentValues
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import fr.dd.jarvis.JarvisApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Création de fichiers par JARVIS : texte, csv, json, html, vcf, ics, docx, xlsx, pdf, zip… Enregistrés dans l'appli et dans Téléchargements/JARVIS. */
object Fichiers {
    private data class Bloc(val type: String, val texte: String = "", val lignes: List<List<String>> = emptyList())

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Markdown léger → blocs (titres, puces, tableaux, paragraphes). */
    private fun blocs(md: String): List<Bloc> {
        val out = mutableListOf<Bloc>()
        val lines = md.lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trimEnd(); i++
            when {
                l.isBlank() -> out.add(Bloc("vide"))
                l.trimStart().startsWith("|") -> {
                    val rows = mutableListOf(l)
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += lines[i].trimEnd(); i++ }
                    out.add(Bloc("table", lignes = rows.filter { r -> !Regex("^\\|?[\\s:|-]+\\|?$").matches(r) }.map { r -> r.trim().trim('|').split("|").map { it.trim() } }))
                }
                l.startsWith("### ") -> out.add(Bloc("h3", l.drop(4)))
                l.startsWith("## ") -> out.add(Bloc("h2", l.drop(3)))
                l.startsWith("# ") -> out.add(Bloc("h1", l.drop(2)))
                l.trimStart().startsWith("- ") || l.trimStart().startsWith("* ") -> out.add(Bloc("li", l.trimStart().drop(2)))
                else -> out.add(Bloc("p", l))
            }
        }
        return out
    }

    private fun sansMd(t: String) = t.replace(Regex("\\*\\*|__|`"), "").replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")

    /** Tableau depuis du texte : JSON [[..],[..]] ou lignes séparées par ; | tabulation (ou virgule). */
    private fun tableau(t: String): List<List<String>> {
        val s = t.trim()
        if (s.startsWith("[")) runCatching {
            val a = JSONArray(s)
            return (0 until a.length()).map { r ->
                val x = a.get(r)
                if (x is JSONArray) (0 until x.length()).map { c -> x.get(c).toString() } else listOf(x.toString())
            }
        }
        val sep = when { s.contains('\t') -> "\t"; s.contains(';') -> ";"; s.contains('|') -> "|"; else -> "," }
        return s.lines().filter { it.isNotBlank() && !Regex("^\\|?[\\s:|-]+\\|?$").matches(it.trim()) }.map { l -> l.trim().trim('|').split(sep).map { it.trim().trim('"') } }
    }

    private fun zip(entrees: List<Pair<String, ByteArray>>): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z -> entrees.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return b.toByteArray()
    }

    // ------------------------------------------------------------------ DOCX
    private fun docx(md: String): ByteArray {
        fun run(t: String, gras: Boolean = false, taille: Int = 22) = "<w:r><w:rPr>" + (if (gras) "<w:b/>" else "") + "<w:sz w:val=\"$taille\"/></w:rPr><w:t xml:space=\"preserve\">${esc(sansMd(t))}</w:t></w:r>"
        val body = StringBuilder()
        blocs(md).forEach { b ->
            when (b.type) {
                "vide" -> body.append("<w:p/>")
                "h1" -> body.append("<w:p><w:pPr><w:spacing w:before=\"240\" w:after=\"120\"/></w:pPr>${run(b.texte, true, 40)}</w:p>")
                "h2" -> body.append("<w:p><w:pPr><w:spacing w:before=\"200\" w:after=\"100\"/></w:pPr>${run(b.texte, true, 32)}</w:p>")
                "h3" -> body.append("<w:p><w:pPr><w:spacing w:before=\"160\" w:after=\"80\"/></w:pPr>${run(b.texte, true, 26)}</w:p>")
                "li" -> body.append("<w:p><w:pPr><w:ind w:left=\"360\" w:hanging=\"240\"/></w:pPr>${run("• " + b.texte)}</w:p>")
                "table" -> {
                    body.append("<w:tbl><w:tblPr><w:tblBorders>" + listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/>" } + "</w:tblBorders></w:tblPr>")
                    b.lignes.forEachIndexed { ri, row ->
                        body.append("<w:tr>")
                        row.forEach { c -> body.append("<w:tc><w:p>${run(c, ri == 0)}</w:p></w:tc>") }
                        body.append("</w:tr>")
                    }
                    body.append("</w:tbl><w:p/>")
                }
                else -> body.append("<w:p>${run(b.texte)}</w:p>")
            }
        }
        val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        return zip(listOf(
            "[Content_Types].xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>").toByteArray(),
            "_rels/.rels" to ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>").toByteArray(),
            "word/document.xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $ns><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>").toByteArray()
        ))
    }

    // ------------------------------------------------------------------ XLSX
    private fun colonne(i: Int): String { var n = i; var s = ""; do { s = ('A' + n % 26) + s; n = n / 26 - 1 } while (n >= 0); return s }

    private fun xlsx(contenu: String, feuille: String): ByteArray {
        val rows = tableau(contenu)
        val sd = StringBuilder()
        rows.forEachIndexed { r, row ->
            sd.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, v ->
                val ref = colonne(c) + (r + 1)
                val num = v.replace(',', '.').trim()
                when {
                    v.startsWith("=") -> sd.append("<c r=\"$ref\"><f>${esc(v.drop(1))}</f></c>")
                    Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?$").matches(num) -> sd.append("<c r=\"$ref\"><v>$num</v></c>")
                    v.isNotEmpty() -> sd.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
                }
            }
            sd.append("</row>")
        }
        val nom = esc(feuille.ifBlank { "Feuille1" }.take(30).replace(Regex("[\\\\/?*\\[\\]:]"), " "))
        return zip(listOf(
            "[Content_Types].xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>").toByteArray(),
            "_rels/.rels" to ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>").toByteArray(),
            "xl/workbook.xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"$nom\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>").toByteArray(),
            "xl/_rels/workbook.xml.rels" to ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>").toByteArray(),
            "xl/worksheets/sheet1.xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$sd</sheetData></worksheet>").toByteArray()
        ))
    }

    // ------------------------------------------------------------------ PDF
    private fun pdf(md: String, titre: String): ByteArray {
        val doc = PdfDocument()
        val W = 595; val H = 842; val M = 42
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        var canvas = page.canvas
        var y = M.toFloat(); var n = 1
        fun nouvelle() { doc.finishPage(page); n++; page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create()); canvas = page.canvas; y = M.toFloat() }
        fun ecrire(t: String, taille: Float, gras: Boolean, indent: Int = 0, couleur: Int = Color.BLACK) {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = taille; color = couleur; typeface = if (gras) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            val largeur = W - 2 * M - indent
            val sl = StaticLayout.Builder.obtain(t, 0, t.length, p, largeur).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
            if (y + sl.height > H - M) nouvelle()
            canvas.save(); canvas.translate((M + indent).toFloat(), y); sl.draw(canvas); canvas.restore()
            y += sl.height + 4
        }
        if (titre.isNotBlank()) { ecrire(titre, 22f, true); y += 6 }
        blocs(md).forEach { b ->
            when (b.type) {
                "vide" -> y += 6
                "h1" -> { y += 6; ecrire(sansMd(b.texte), 20f, true) }
                "h2" -> { y += 4; ecrire(sansMd(b.texte), 16f, true) }
                "h3" -> ecrire(sansMd(b.texte), 13.5f, true)
                "li" -> ecrire("•  " + sansMd(b.texte), 11f, false, 12)
                "table" -> b.lignes.forEachIndexed { ri, row -> ecrire(row.joinToString("   |   ") { sansMd(it) }, 10.5f, ri == 0, 6) }
                else -> ecrire(sansMd(b.texte), 11f, false)
            }
        }
        doc.finishPage(page)
        val out = ByteArrayOutputStream(); doc.writeTo(out); doc.close()
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ VCF depuis les contacts du téléphone
    private fun vcfContacts(app: JarvisApp, recherche: String): String {
        val l = app.contacts.search(recherche.trim().let { if (it.lowercase() in setOf("tous", "tout", "all")) "" else it })
        return l.joinToString("\n") { c ->
            buildString {
                append("BEGIN:VCARD\nVERSION:3.0\nFN:${c.name}\n")
                val parts = c.name.trim().split(" ", limit = 2)
                append("N:${parts.getOrElse(1) { "" }};${parts.first()};;;\n")
                c.phones.forEach { append("TEL;TYPE=CELL:$it\n") }
                c.emails.forEach { append("EMAIL:$it\n") }
                if (c.company.isNotBlank()) append("ORG:${c.company}\n")
                if (c.note.isNotBlank()) append("NOTE:${c.note.replace("\n", "\\n")}\n")
                append("END:VCARD")
            }
        } + "\n"
    }

    fun dossier(app: JarvisApp) = File(app.filesDir, "exports").apply { mkdirs() }

    private fun mime(ext: String) = when (ext) {
        "pdf" -> "application/pdf"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; "zip" -> "application/zip"
        "vcf" -> "text/x-vcard"; "csv" -> "text/csv"; "json" -> "application/json"; "html", "htm" -> "text/html"; "xml" -> "text/xml"
        "ics" -> "text/calendar"; "md" -> "text/markdown"; "svg" -> "image/svg+xml"; "rtf" -> "application/rtf"; else -> "text/plain"
    }

    fun mimeDe(nom: String) = mime(nom.substringAfterLast('.', "").lowercase())

    /** Crée le fichier ; retourne la ligne Markdown à afficher (lien « jarvis-file: »). */
    fun creer(app: JarvisApp, a: JSONObject): String {
        val format = a.optString("format").trim().lowercase().removePrefix(".").ifBlank { a.optString("nom").substringAfterLast('.', "txt").lowercase() }
        val base = a.optString("nom").trim().substringBeforeLast('.', a.optString("nom").trim()).replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "document" }
        val contenu = a.optString("contenu")
        val titre = a.optString("titre")
        val ext = when (format) { "doc", "word" -> "docx"; "excel", "xls" -> "xlsx"; "vcard" -> "vcf"; "markdown" -> "md"; "texte", "text" -> "txt"; else -> format }
        val bytes: ByteArray = when (ext) {
            "docx" -> docx((if (titre.isNotBlank()) "# $titre\n\n" else "") + contenu)
            "xlsx" -> xlsx(contenu, a.optString("feuille"))
            "pdf" -> pdf(contenu, titre)
            "vcf" -> (if (contenu.isBlank() || contenu.trim().lowercase() in setOf("tous", "tout")) vcfContacts(app, a.optString("recherche")) else contenu).toByteArray()
            "csv" -> tableau(contenu).joinToString("\r\n") { r -> r.joinToString(";") { c -> if (c.contains(';') || c.contains('"') || c.contains('\n')) "\"" + c.replace("\"", "\"\"") + "\"" else c } }.let { "\uFEFF$it".toByteArray() }
            "html", "htm" -> (if (contenu.trimStart().startsWith("<")) contenu else {
                val b = StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>${esc(titre.ifBlank { base })}</title><style>body{font-family:sans-serif;max-width:800px;margin:2em auto;line-height:1.5}table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px 8px}</style></head><body>")
                if (titre.isNotBlank()) b.append("<h1>${esc(titre)}</h1>")
                blocs(contenu).forEach { x -> when (x.type) {
                    "h1" -> b.append("<h1>${esc(x.texte)}</h1>"); "h2" -> b.append("<h2>${esc(x.texte)}</h2>"); "h3" -> b.append("<h3>${esc(x.texte)}</h3>")
                    "li" -> b.append("<ul><li>${esc(sansMd(x.texte))}</li></ul>"); "p" -> b.append("<p>${esc(sansMd(x.texte))}</p>")
                    "table" -> { b.append("<table>"); x.lignes.forEachIndexed { i, r -> b.append("<tr>" + r.joinToString("") { c -> (if (i == 0) "<th>" else "<td>") + esc(c) + (if (i == 0) "</th>" else "</td>") } + "</tr>") }; b.append("</table>") }
                } }
                b.append("</body></html>").toString()
            }).toByteArray()
            "zip" -> {
                val e = mutableListOf<Pair<String, ByteArray>>()
                val d = dossier(app)
                a.optString("inclure").split(Regex("[,;\\n]")).map { it.trim() }.filter { it.isNotBlank() }.forEach { n ->
                    val f = File(d, n.substringAfterLast('/')).takeIf { it.exists() } ?: File(File(app.filesDir, "images"), n.substringAfterLast('/')).takeIf { it.exists() }
                    if (f != null) e.add(f.name to f.readBytes())
                }
                runCatching { JSONObject(contenu) }.getOrNull()?.let { j -> j.keys().forEach { k -> e.add(k to j.optString(k).toByteArray()) } }
                if (e.isEmpty()) return "❌ ZIP vide : indique `inclure` (noms de fichiers déjà créés) ou `contenu` (JSON {\"nom.txt\": \"texte\"})."
                zip(e)
            }
            else -> contenu.toByteArray()
        }
        val nom = "$base.$ext"
        File(dossier(app), nom).writeBytes(bytes)
        val dl = runCatching { versTelechargements(app, nom, bytes, mime(ext)) }.getOrDefault(false)
        val ko = (bytes.size + 512) / 1024
        return "[📎 $nom ($ko Ko)](jarvis-file:$nom)" + (if (dl) "\n📁 Aussi enregistré dans Téléchargements › JARVIS." else "")
    }

    private fun versTelechargements(app: JarvisApp, nom: String, bytes: ByteArray, mime: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val cr = app.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nom); put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/JARVIS")
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
        cr.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        return true
    }
}
