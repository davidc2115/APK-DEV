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
        val coffre = constellation.arborescence.single()

        // La note située à la racine est conservée dans un nœud « Coffre » explicite.
        assertEquals("", coffre.chemin)
        assertEquals("Coffre", coffre.nom)
        var courant = coffre.enfants.single { it.chemin == "Dossier" }
        chemins.forEach { chemin ->
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
    @Test fun eviteLesChevauchementsEntreDossiersEtPiecesJointes() {
        val notes = (1..18).map { n ->
            ObsidianWriter.GraphNode("Jarvis/Agents/Note$n", "Note$n", "Jarvis/Agents")
        } + (1..8).map { n ->
            ObsidianWriter.GraphNode("Société/Clients/Client$n", "Client$n", "Société/Clients")
        }
        val pieces = (1..16).map { n ->
            ObsidianWriter.GraphAttachment("Jarvis/Agents/visuel$n.pdf", "visuel$n.pdf", "PDF")
        } + listOf(
            ObsidianWriter.GraphAttachment("capture.png", "capture.png", "IMAGE"),
            ObsidianWriter.GraphAttachment("rapport.pdf", "rapport.pdf", "PDF")
        )
        val data = ObsidianWriter.GraphData(
            nodes = notes,
            edges = emptyList(),
            attachments = pieces,
            dossiers = listOf("Jarvis", "Jarvis/Agents", "Société", "Société/Clients", "Archives", "Médias")
        )
        val c = ConstellationLayout.construire(data)
        val hubs = c.hubs.filter { it.rayon > 0f }

        for (i in hubs.indices) for (j in i + 1 until hubs.size) {
            val a = hubs[i]; val b = hubs[j]
            val distance = kotlin.math.hypot(a.pos.x - b.pos.x, a.pos.y - b.pos.y)
            assertTrue("Cercles en collision : ${a.titre} / ${b.titre}", distance + 0.5f >= a.rayon + b.rayon + 12f)
        }
        for (i in c.etiquettes.indices) {
            val a = c.etiquettes[i]
            for (j in i + 1 until c.etiquettes.size) {
                val b = c.etiquettes[j]
                val dx = kotlin.math.abs(a.pos.x - b.pos.x)
                val dy = kotlin.math.abs(a.pos.y - b.pos.y)
                assertTrue("Étiquettes en collision : ${a.texte} / ${b.texte}",
                    dx + 0.5f >= (a.w + b.w) / 2f + 12f || dy + 0.5f >= (a.h + b.h) / 2f + 12f)
            }
            val coreX = a.pos.x.coerceIn(-a.w / 2f, a.w / 2f)
            val coreY = a.pos.y.coerceIn(-a.h / 2f, a.h / 2f)
            assertTrue("Une étiquette recouvre le noyau : ${a.texte}",
                kotlin.math.hypot(coreX, coreY) + 0.5f >= ConstellationLayout.RAYON_CERVEAU + 18f)
            for (h in hubs) {
                val nx = h.pos.x.coerceIn(a.pos.x - a.w / 2f, a.pos.x + a.w / 2f)
                val ny = h.pos.y.coerceIn(a.pos.y - a.h / 2f, a.pos.y + a.h / 2f)
                assertTrue("Étiquette et dossier en collision : ${a.texte} / ${h.titre}",
                    kotlin.math.hypot(nx - h.pos.x, ny - h.pos.y) + 0.5f >= h.rayon + 12f)
            }
        }
    }

}
