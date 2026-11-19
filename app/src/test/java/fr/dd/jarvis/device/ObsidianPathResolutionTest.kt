package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObsidianPathResolutionTest {
    private fun key(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .lowercase()
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .replace(Regex("\\s+"), " ")
        .let { words -> words.split(' ').joinToString(" ") { word ->
            if (word.length > 3 && (word.endsWith("s") || word.endsWith("x"))) word.dropLast(1) else word
        } }

    @Test fun retrouveUnDossierUniqueSousUnDossierParent() {
        assertEquals(
            "JARVIS/Images",
            ObsidianPathResolution.uniqueNestedFolder(
                "image",
                listOf("JARVIS/Images", "JARVIS/Vidéos", "Documents"),
                ::key
            )
        )
    }

    @Test fun refuseDeChoisirEntreDeuxDossiersHomonymes() {
        assertNull(
            ObsidianPathResolution.uniqueNestedFolder(
                "Images",
                listOf("JARVIS/Images", "Archives/Images"),
                ::key
            )
        )
    }

    @Test fun ignoreLesDifferencesDAccentsEtDeCasse() {
        assertEquals(
            "JARVIS/Coloriés",
            ObsidianPathResolution.uniqueNestedFolder(
                "colories",
                listOf("JARVIS/Coloriés"),
                ::key
            )
        )
    }
}
