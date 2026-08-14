package com.jarvis.ai.core.documents

import java.io.File
import javax.inject.Inject

/**
 * Génère un .xlsx à partir d'un tableau simple (lignes/colonnes).
 * TODO Phase 5 : Apache POI (XSSFWorkbook) pour un vrai xlsx ; en attendant, ce squelette
 * produit un CSV (ouvrable dans tout tableur) pour ne pas bloquer les tests fonctionnels.
 */
class XlsxGenerator @Inject constructor() : DocumentGenerator {
    override val extension = "csv" // deviendra "xlsx" une fois POI intégré

    data class SheetContent(val headers: List<String>, val rows: List<List<String>>)

    override suspend fun generate(outputDir: File, fileName: String, content: Any): File {
        require(content is SheetContent)
        val file = File(outputDir, "$fileName.$extension")
        file.writeText(buildString {
            appendLine(content.headers.joinToString(","))
            content.rows.forEach { appendLine(it.joinToString(",")) }
        })
        return file
    }
}
