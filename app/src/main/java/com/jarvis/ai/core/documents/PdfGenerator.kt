package com.jarvis.ai.core.documents

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * Génère un PDF texte simple via android.graphics.pdf (inclus dans le SDK Android,
 * aucune dépendance externe nécessaire pour un premier rendu fonctionnel).
 * TODO Phase 5 : passer à PdfBox-Android ou iText pour une mise en page avancée
 * (tableaux, images, styles) si besoin au-delà du texte brut.
 */
class PdfGenerator @Inject constructor() : DocumentGenerator {
    override val extension = "pdf"

    data class PdfContent(val title: String, val body: String)

    override suspend fun generate(outputDir: File, fileName: String, content: Any): File {
        require(content is PdfContent)
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 @ 72dpi
        val page = document.startPage(pageInfo)
        val canvas: Canvas = page.canvas
        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 12f }

        canvas.drawText(content.title, 40f, 60f, titlePaint)
        var y = 100f
        content.body.split("\n").forEach { line ->
            canvas.drawText(line, 40f, y, bodyPaint)
            y += 18f
        }
        document.finishPage(page)

        val file = File(outputDir, "$fileName.$extension")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }
}
