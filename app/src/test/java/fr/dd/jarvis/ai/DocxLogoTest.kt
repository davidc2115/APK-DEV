package fr.dd.jarvis.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class DocxLogoTest {
    /** PNG 1×1 valide (aucun fichier externe nécessaire). */
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

    private fun entrees(octets: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(octets.inputStream()).use { z -> var e = z.nextEntry; while (e != null) { out[e.name] = z.readBytes().toString(Charsets.UTF_8); e = z.nextEntry } }
        return out
    }

    @Test fun docxAvecLogoEtPied() {
        val avec = entrees(Fichiers.docx("# Attestation\n\nTexte **important**.\n\n| A | B |\n|---|---|\n| 1 | 2 |", Triple(png, 300, 120), "Solaire Plus, SASU · SIRET 123"))
        assertTrue(avec.keys.toString(), avec.keys.any { it.startsWith("word/media/") })
        assertTrue(avec.values.any { it.contains("SIRET 123") })
        val sans = entrees(Fichiers.docx("# Coloriage\n\nTexte"))
        assertTrue(sans.keys.none { it.startsWith("word/media/") })
    }
}
