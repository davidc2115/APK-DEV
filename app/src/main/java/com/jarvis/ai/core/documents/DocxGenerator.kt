package com.jarvis.ai.core.documents

import java.io.File
import javax.inject.Inject

/**
 * Génère un .docx à partir d'un texte structuré (titres/paragraphes).
 * TODO Phase 5 : ajouter la dépendance Apache POI (org.apache.poi:poi-ooxml) — attention,
 * certaines transitives ne sont pas 100% compatibles Android telles quelles ; alternative
 * plus légère si besoin : générer directement le XML OOXML minimal d'un docx (zip + XML),
 * suffisant pour du texte simple sans mise en forme avancée.
 */
class DocxGenerator @Inject constructor() : DocumentGenerator {
    override val extension = "docx"

    data class DocContent(val title: String, val paragraphs: List<String>)

    override suspend fun generate(outputDir: File, fileName: String, content: Any): File {
        require(content is DocContent)
        val file = File(outputDir, "$fileName.$extension")
        // TODO: remplacer par une vraie écriture docx (POI XWPFDocument).
        file.writeText("${content.title}\n\n${content.paragraphs.joinToString("\n\n")}")
        return file
    }
}
