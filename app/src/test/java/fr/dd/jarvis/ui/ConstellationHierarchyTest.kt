package fr.dd.jarvis.ui

import fr.dd.jarvis.device.ObsidianWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConstellationHierarchyTest {
    @Test fun conserveTousLesNiveauxEtLesElementsDuCoffre() {
        val chemins = (1..12).runningFold("Dossier") { parent, n -> "$parent/Niveau$n" }.drop(1)
        val dossierFinal = chemins.last()
        val noteRacine = ObsidianWriter.GraphNode("Accueil", "Accueil", "")
        val noteProfonde = ObsidianWriter.GraphNode("Note profonde", "Note profonde", dossierFinal)
        val piece = ObsidianWriter.GraphAttachment("$dossierFinal/schema.pdf", "schema.pdf", "PDF")
        val data = ObsidianWriter.GraphData(
            nodes = listOf(noteRacine, noteProfonde),
            edges = emptyList(),
            attachments = listOf(piece),
            dossiers = chemins
        )

        val constellation = ConstellationLayout.construire(data)
        val racine = constellation.arborescence.single()

        assertEquals("Dossier", racine.chemin)
        var courant = racine.enfants.single { it.chemin == chemins.first() }
        chemins.drop(1).forEach { chemin ->
            courant = courant.enfants.single { it.chemin == chemin }
        }
        assertEquals(12, courant.profondeur)
        assertEquals(listOf("Note profonde"), courant.notes.map { it.label })
        assertEquals(listOf("schema.pdf"), courant.pieces.map { it.name })
        assertEquals(2, courant.totalRecursif)
        assertEquals(2, constellation.totalNotes)
        assertEquals(1, constellation.totalPieces)
    }

    @Test fun conservePlusieursDossiersRacineSansLesFusionnerDansLarbre() {
        val data = ObsidianWriter.GraphData(
            nodes = listOf(
                ObsidianWriter.GraphNode("A/NoteA", "NoteA", "A"),
                ObsidianWriter.GraphNode("B/NoteB", "NoteB", "B"),
                ObsidianWriter.GraphNode("C/NoteC", "NoteC", "C")
            ),
            edges = emptyList(),
            dossiers = listOf("A", "B", "C")
        )

        val constellation = ConstellationLayout.construire(data)

        assertEquals(setOf("A", "B", "C"), constellation.arborescence.map { it.chemin }.toSet())
        assertEquals(3, constellation.arborescence.sumOf { it.totalRecursif })
    }
}
