package com.jarvis.ai.core.documents

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Zippe un ensemble de fichiers/dossiers (ex: export d'un projet de site web généré). */
class ZipGenerator @Inject constructor() : DocumentGenerator {
    override val extension = "zip"

    override suspend fun generate(outputDir: File, fileName: String, content: Any): File {
        require(content is List<*>)
        @Suppress("UNCHECKED_CAST")
        val sourceFiles = content as List<File>
        val zipFile = File(outputDir, "$fileName.$extension")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            sourceFiles.forEach { source -> addToZip(source, source.name, zos) }
        }
        return zipFile
    }

    private fun addToZip(file: File, entryName: String, zos: ZipOutputStream) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { addToZip(it, "$entryName/${it.name}", zos) }
        } else {
            zos.putNextEntry(ZipEntry(entryName))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}
