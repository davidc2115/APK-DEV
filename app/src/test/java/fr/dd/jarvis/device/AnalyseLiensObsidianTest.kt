package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyseLiensObsidianTest {
    @Test
    fun distingueNotesSansLienNotesIsoleesEtPiecesJointesOrphelines() {
        val data = ObsidianWriter.GraphData(
            nodes = listOf(
                ObsidianWriter.GraphNode("A", "Alpha", "Notes"),
                ObsidianWriter.GraphNode("B", "Beta", "Notes"),
                ObsidianWriter.GraphNode("C", "Gamma", "Notes")
            ),
            edges = listOf(ObsidianWriter.GraphEdge("A", "B")),
            references = listOf(
                ObsidianWriter.GraphReference("A", "B", true, "B"),
                ObsidianWriter.GraphReference("A", "Images/liee.png", false, "Images/liee.png"),
                ObsidianWriter.GraphReference("C", "NoteManquante", false, "NoteManquante")
            ),
            attachments = listOf(
                ObsidianWriter.GraphAttachment("Images/liee.png", "liee.png", "IMAGE"),
                ObsidianWriter.GraphAttachment("Images/orpheline.png", "orpheline.png", "IMAGE")
            ),
            notesLues = 3,
            notesCandidates = 3,
            limiteNotesAtteinte = false
        )

        val audit = AnalyseLiensObsidian.analyser(data)

        assertEquals(listOf("A", "C"), audit.notesSansLienEntrant.map { it.id })
        assertEquals(listOf("C"), audit.notesOrphelines.map { it.id })
        assertEquals(listOf("Images/orpheline.png"), audit.fichiersOrphelins.map { it.path })
        assertEquals(listOf("NoteManquante"), audit.referencesNonResolues.map { it.rawTarget })
        assertTrue(audit.analyseComplete)
    }

    @Test
    fun neSignalePasLindexRacineCommeNoteOrpheline() {
        val data = ObsidianWriter.GraphData(
            nodes = listOf(
                ObsidianWriter.GraphNode("_Jarvis/Index coffre", "Index coffre", "_Jarvis"),
                ObsidianWriter.GraphNode("Société/Clients/Alpha", "Alpha", "Société/Clients")
            ),
            edges = emptyList(),
            references = emptyList()
        )

        val audit = AnalyseLiensObsidian.analyser(data)

        assertEquals(listOf("Alpha"), audit.notesSansLienEntrant.map { it.label })
        assertEquals(listOf("Alpha"), audit.notesOrphelines.map { it.label })
    }

    @Test
    fun signaleUneAnalysePartielleQuandDesNotesNontPasPuEtreLues() {
        val data = ObsidianWriter.GraphData(
            nodes = listOf(ObsidianWriter.GraphNode("A", "Alpha", "")),
            edges = emptyList(),
            notesLues = 0,
            notesCandidates = 1,
            limiteNotesAtteinte = false
        )

        assertTrue(!AnalyseLiensObsidian.analyser(data).analyseComplete)
    }
}
