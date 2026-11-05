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

    /** Extrait du texte pour les formats texte et les conteneurs Office Open XML. */
    fun extract(context: Context, name: String): String {
        val f = find(context, name) ?: return "Document introuvable : $name"
        val ext = f.extension.lowercase()
        if (f.length() > 12L * 1024 * 1024) return "Document trop volumineux pour une extraction textuelle directe (limite 12 Mo)."
        return when (ext) {
            "txt", "md", "csv", "tsv", "json", "xml", "kml", "gpx", "html", "htm", "rtf", "ics", "vcf", "log" ->
                f.readText(Charsets.UTF_8).take(100_000)
            "docx", "xlsx", "pptx", "odt", "ods" -> extractZipText(f)
            "zip" -> zipListing(f)
            "pdf" -> extractPdf(context, f)
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "mp4", "mov", "mkv", "webm", "avi" ->
                "Fichier média importé : ${f.name} (${f.length()} octets). Utilise l'analyse multimodale pour examiner son contenu."
            else -> "Format .$ext conservé, mais aucune extraction textuelle intégrée n'est disponible pour ce type."
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
            if (raw.length < 40) {
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

    private fun extractZipText(f: File): String = runCatching {
        val out = StringBuilder()
        ZipInputStream(f.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val wanted = when (f.extension.lowercase()) {
                    "docx" -> e.name == "word/document.xml"
                    "xlsx" -> e.name.startsWith("xl/worksheets/sheet") && e.name.endsWith(".xml")
                    "pptx" -> e.name.startsWith("ppt/slides/slide") && e.name.endsWith(".xml")
                    "odt", "ods" -> e.name == "content.xml"
                    else -> false
                }
                if (wanted) {
                    val xml = z.readBytes().take(1_500_000).toByteArray().toString(Charsets.UTF_8)
                    val text = Regex("<(?:\\w+:)?t(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?t>", setOf(RegexOption.DOT_MATCHES_ALL))
                        .findAll(xml).joinToString(" ") { it.groupValues[1].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"") }
                    if (text.isNotBlank()) out.append(text).append("\n")
                }
                z.closeEntry()
                if (out.length > 80_000) break
            }
        }
        out.toString().takeIf { it.isNotBlank() } ?: "Aucun texte exploitable trouvé dans ce document."
    }.getOrElse { "Lecture du document impossible : ${it.message}" }
}
