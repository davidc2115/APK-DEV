package com.jarvis.ai.core.documents

import java.io.File

/** Contrat commun pour toute génération de fichier à la demande depuis le chat. */
interface DocumentGenerator {
    val extension: String
    suspend fun generate(outputDir: File, fileName: String, content: Any): File
}
