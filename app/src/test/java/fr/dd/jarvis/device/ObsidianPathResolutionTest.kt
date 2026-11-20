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

    @Test fun retrouveUnCheminCompletSousUnDossierParent() {
        val folders = listOf("JARVIS/Images", "JARVIS/Vidéos", "Documents")
        assertEquals(
            "JARVIS/Images/coloriage train",
            ObsidianPathResolution.uniqueNestedPath("image", folders, ::key) { parent ->
                if (parent == "JARVIS/Images") "$parent/coloriage train" else null
            }
        )
    }

    @Test fun distingueLesDossiersImagesGraceAuSousDossierDemande() {
        val folders = listOf("JARVIS/Images", "Archives/Images", "Photos/Images")
        assertEquals(
            "JARVIS/Images/coloriage train",
            ObsidianPathResolution.uniqueNestedPath("Images", folders, ::key) { parent ->
                if (parent == "JARVIS/Images") "$parent/coloriage train" else null
            }
        )
    }

    @Test fun refuseDeuxCorrespondancesCompletes() {
        val folders = listOf("JARVIS/Images", "Archives/Images")
        assertNull(
            ObsidianPathResolution.uniqueNestedPath("Images", folders, ::key) { parent ->
                "$parent/coloriage train"
            }
        )
    }

    @Test fun ignoreLesDifferencesDAccentsEtDeCasse() {
        assertEquals(
            "JARVIS/Coloriés/Train",
            ObsidianPathResolution.uniqueNestedPath("colories", listOf("JARVIS/Coloriés"), ::key) { parent ->
                "$parent/Train"
            }
        )
    }
    @Test fun refuseLesNomsApprochesAmbigusAuLieuDeChoisirLePremier() {
        try {
            ObsidianPathResolution.uniqueNameMatch(
                listOf("Images", "image"),
                "images",
                { it.lowercase() },
                { it.lowercase().removeSuffix("s") }
            )
            throw AssertionError("Une correspondance ambiguë ne doit pas être choisie.")
        } catch (e: IllegalArgumentException) {
            assertEquals(true, e.message?.contains("Précise le chemin complet") == true)
        }
    }

    @Test fun privilegieLeNomExactAvantLesCorrespondancesApprochees() {
        assertEquals(
            "Images",
            ObsidianPathResolution.uniqueNameMatch(
                listOf("Images", "image"),
                "Images",
                { it.lowercase() },
                { it.lowercase().removeSuffix("s") }
            )
        )
    }
}
