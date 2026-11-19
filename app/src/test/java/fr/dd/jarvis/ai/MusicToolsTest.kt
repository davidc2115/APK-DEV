package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicToolsTest {
    @Test fun reconnaitLesFormulationsDeLaBibliothequeJaime() {
        listOf(
            "J'aime",
            "bibliothèque J'aime",
            "mes titres aimés",
            "titres likés",
            "mes favoris",
            "coups de cœur"
        ).forEach { assertEquals(it, "jaime", MusicTools.sectionBibliotheque(it)) }
    }

    @Test fun reconnaitLesAutresSectionsDeBibliotheque() {
        assertEquals("playlists", MusicTools.sectionBibliotheque("mes playlists"))
        assertEquals("titres", MusicTools.sectionBibliotheque("mes titres"))
        assertEquals("albums", MusicTools.sectionBibliotheque("albums"))
        assertEquals("artistes", MusicTools.sectionBibliotheque("artistes"))
        assertEquals("telechargements", MusicTools.sectionBibliotheque("téléchargements"))
    }
}
