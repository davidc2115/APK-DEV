package fr.dd.jarvis.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.zip.ZipInputStream
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/** Bibliothèque privée des documents importés. Les originaux sont conservés. */
object DocumentStore {
    private const val MAX_IMPORT = 200L * 1024L * 1024L
    fun directory(context: Context) = File(context.filesDir, "documents").apply { mkdirs() }

    fun importFile(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val meta = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() to if (c.isNull(1)) -1L else c.getLong(1) else "" to -1L
        } ?: ("" to -1L)
        val display = meta.first.ifBlank { "document_${System.currentTimeMillis()}" }
        if (meta.second > MAX_IMPORT) return "❌ $display dépasse la limite d'import de 200 Mo."
        val safe = display.substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "document" }
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var target = File(directory(context), safe)
        var n = 2
        while (target.exists()) { target = File(directory(context), "$base ($n)$ext"); n++ }
        val input = resolver.openInputStream(uri) ?: return "❌ Impossible de lire $display."
        input.use { ins -> target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) {
                val count = ins.read(buffer); if (count < 0) break
                total += count
                if (total > MAX_IMPORT) { target.delete(); throw IllegalArgumentException("fichier supérieur à 200 Mo") }
                out.write(buffer, 0, count)
            }
        } }
        return target.name
    }

    fun files(context: Context): List<File> = directory(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
    fun find(context: Context, name: String): File? {
        val clean = name.substringAfterLast('/').trim()
        return files(context).firstOrNull { it.name.equals(clean, true) || it.nameWithoutExtension.equals(clean, true) }
    }

    fun list(context: Context): String {
        val all = files(context)
        if (all.isEmpty()) return "La bibliothèque de documents est vide."
        return all.joinToString("\n") { f ->
            "- ${f.name} · ${(f.length() + 1023) / 1024} Ko · ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRENCH).format(java.util.Date(f.lastModified()))}"
        }
    }

    /** Extraction commune avec indicateurs explicites de couverture et de troncature. */
    fun extract(context: Context, name: String): String {
        val f = find(context, name) ?: return "Document introuvable : $name"
        val ext = f.extension.lowercase()
        if (f.length() > MAX_IMPORT) return "Document trop volumineux pour l'analyse intégrée (limite 200 Mo)."
        val limit = 180_000
        return when (ext) {
            "txt", "md", "csv", "tsv", "json", "xml", "kml", "gpx", "html", "htm", "rtf", "ics", "vcf", "log" -> {
                val raw = f.readText(Charsets.UTF_8)
                "DOCUMENT : ${f.name}\nFormat : ${ext.uppercase()}\nCaractères : ${raw.length}\n" +
                    (if (raw.length <= limit) "Extraction complète.\n\n" else "ATTENTION : extrait limité à $limit caractères sur ${raw.length}.\n\n") +
                    raw.take(limit)
            }
            "docx", "xlsx", "pptx", "odt", "ods" -> extractZipText(f, limit)
            "zip" -> zipListing(f)
            "pdf" -> extractPdf(context, f, limit)
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "mp4", "mov", "mkv", "webm", "avi", "3gp" ->
                "MÉDIA : ${f.name} (${f.length()} octets). Utilise document_analyser pour la lecture visuelle ou multimodale."
            else -> "FORMAT_NON_PRIS_EN_CHARGE|${f.name}|.$ext : le fichier est conservé, mais son contenu ne peut pas être extrait automatiquement."
        }
    }

    /** Extrait toutes les pages d'un PDF texte en conservant l'ordre spatial. */
    fun extractPdf(context: Context, file: File, maxChars: Int = 180_000): String = runCatching {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { pdf ->
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.setAddMoreFormatting(true)
            stripper.setStartPage(1)
            stripper.setEndPage(pdf.numberOfPages)
            val raw = stripper.getText(pdf).replace("\u0000", "").trim()
            if (raw.length < maxOf(40, pdf.numberOfPages * 30)) {
                "PDF_SANS_TEXTE|${pdf.numberOfPages}|${file.name}"
            } else {
                val complete = raw.length <= maxChars
                buildString {
                    append("CONTENU PDF EXTRAIT — ").append(file.name)
                    append("\nPages totales : ").append(pdf.numberOfPages)
                    append("\nPages parcourues : 1 à ").append(pdf.numberOfPages)
                    append("\nTexte extrait : ").append(raw.length).append(" caractères")
                    append(if (complete) "\nExtraction complète." else "\nATTENTION : sortie limitée à $maxChars caractères ; le document source contient davantage de texte.")
                    append("\n\n").append(raw.take(maxChars))
                }
            }
        }
    }.getOrElse { "ÉCHEC_EXTRACTION_PDF|${file.name}|${it.message ?: "erreur inconnue"}" }
    private fun zipListing(f: File): String = runCatching {
        val out = mutableListOf<String>()
        ZipInputStream(f.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) out += e.name.take(240); z.closeEntry(); if (out.size >= 500) break }
        }
        if (out.isEmpty()) "Archive vide ou illisible." else "Contenu de ${f.name} :\n" + out.joinToString("\n") { "- $it" }
    }.getOrElse { "Archive ZIP illisible : ${it.message}" }

    /** Extrait les documents bureautiques en conservant les frontières paragraphes, diapositives et feuilles. */
    private fun extractZipText(f: File, maxChars: Int): String = runCatching {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(f.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".xml")) {
                    val bytes = zip.readBytes()
                    if (bytes.size <= 8 * 1024 * 1024) entries[entry.name] = bytes.toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        fun decode(value: String) = value.replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        fun textNodes(xml: String): String = decode(
            Regex("<(?:[A-Za-z0-9_]+:)?t(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?t>", setOf(RegexOption.DOT_MATCHES_ALL))
                .findAll(xml).joinToString("") { it.groupValues[1] }
        )
        val out = StringBuilder()
        when (f.extension.lowercase()) {
            "docx" -> {
                val xml = entries["word/document.xml"].orEmpty()
                Regex("<(?:[A-Za-z0-9_]+:)?p(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?p>", setOf(RegexOption.DOT_MATCHES_ALL))
                    .findAll(xml).forEach { m ->
                        val line = textNodes(m.groupValues[1])
                        if (line.isNotBlank()) out.append(line).append("\n")
                    }
            }
            "pptx" -> {
                entries.filterKeys { it.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) }
                    .toSortedMap(compareBy { it.substringAfter("slide").substringBefore(".").toIntOrNull() ?: 0 })
                    .forEach { (path, xml) ->
                        out.append("\n[ DIAPOSITIVE ").append(path.substringAfter("slide").substringBefore(".")).append(" ]\n")
                        Regex("<(?:[A-Za-z0-9_]+:)?p(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?p>", setOf(RegexOption.DOT_MATCHES_ALL))
                            .findAll(xml).forEach { m ->
                                val line = textNodes(m.groupValues[1])
                                if (line.isNotBlank()) out.append(line).append("\n")
                            }
                    }
            }
            "xlsx" -> {
                val shared = Regex("<(?:[A-Za-z0-9_]+:)?si(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?si>", setOf(RegexOption.DOT_MATCHES_ALL))
                    .findAll(entries["xl/sharedStrings.xml"].orEmpty()).map { textNodes(it.groupValues[1]) }.toList()
                entries.filterKeys { it.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) }
                    .toSortedMap(compareBy { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 })
                    .forEach { (path, xml) ->
                        out.append("\n[ FEUILLE ").append(path.substringAfter("sheet").substringBefore(".")).append(" ]\n")
                        Regex("<(?:[A-Za-z0-9_]+:)?row(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?row>", setOf(RegexOption.DOT_MATCHES_ALL))
                            .findAll(xml).forEach { row ->
                                val cells = Regex("<(?:[A-Za-z0-9_]+:)?c\\b([^>]*)>(.*?)</(?:[A-Za-z0-9_]+:)?c>", setOf(RegexOption.DOT_MATCHES_ALL))
                                    .findAll(row.groupValues[1]).map { cell ->
                                        val attrs = cell.groupValues[1]
                                        val ref = Regex("""\\br="([^"]+)"""").find(attrs)?.groupValues?.get(1).orEmpty()
                                        val type = Regex("""\\bt="([^"]+)"""").find(attrs)?.groupValues?.get(1).orEmpty()
                                        val value = Regex("<(?:[A-Za-z0-9_]+:)?v(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?v>", setOf(RegexOption.DOT_MATCHES_ALL))
                                            .find(cell.groupValues[2])?.groupValues?.get(1).orEmpty()
                                        val display = if (type == "s") shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty() else decode(value)
                                        "$ref=$display"
                                    }.toList()
                                if (cells.isNotEmpty()) out.append(cells.joinToString(" | ")).append("\n")
                            }
                    }
            }
            "odt", "ods" -> {
                val xml = entries["content.xml"].orEmpty()
                val pattern = if (f.extension.equals("odt", true))
                    "<(?:text:)?p(?:\\s[^>]*)?>(.*?)</(?:text:)?p>"
                else "<(?:table:)?table-cell(?:\\s[^>]*)?>(.*?)</(?:table:)?table-cell>"
                Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL)).findAll(xml).forEach { m ->
                    val line = decode(Regex("<[^>]+>").replace(m.groupValues[1], ""))
                    if (line.isNotBlank()) out.append(line).append(if (f.extension.equals("ods", true)) " | " else "\n")
                }
            }
        }
        val raw = out.toString().trim()
        if (raw.isBlank()) "Aucun texte exploitable trouvé dans ${f.name}."
        else "DOCUMENT : ${f.name}\nFormat : ${f.extension.uppercase()}\nCaractères extraits : ${raw.length}\n" +
            (if (raw.length <= maxChars) "Extraction complète des éléments textuels détectés.\n\n" else "ATTENTION : sortie limitée à $maxChars caractères sur ${raw.length}.\n\n") +
            raw.take(maxChars)
    }.getOrElse { "ÉCHEC_EXTRACTION_DOCUMENT|${f.name}|${it.message ?: "erreur inconnue"}" }
}
