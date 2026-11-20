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

    @Test fun modeNormalEstLeDefautEtDesactiveLeMelange() {
        assertEquals("normal", MusicTools.modeLecture(""))
        assertEquals("normal", MusicTools.modeLecture("normal"))
        assertEquals("normal", MusicTools.modeLecture("", "non"))
    }

    @Test fun reconnaitLesQuatreModesDeLecture() {
        assertEquals("aleatoire", MusicTools.modeLecture("aléatoire"))
        assertEquals("aleatoire", MusicTools.modeLecture("", "oui"))
        assertEquals("repeter_titre", MusicTools.modeLecture("répéter une seule musique"))
        assertEquals("repeter_titre", MusicTools.modeLecture("repeat one song"))
        assertEquals("repeter_playlist", MusicTools.modeLecture("répéter toute la playlist"))
        assertEquals("repeter_playlist", MusicTools.modeLecture("repeat entire playlist"))
    }

    @Test fun reconnaitLesAutresSectionsDeBibliotheque() {
        assertEquals("playlists", MusicTools.sectionBibliotheque("mes playlists"))
        assertEquals("titres", MusicTools.sectionBibliotheque("mes titres"))
        assertEquals("albums", MusicTools.sectionBibliotheque("albums"))
        assertEquals("artistes", MusicTools.sectionBibliotheque("artistes"))
        assertEquals("telechargements", MusicTools.sectionBibliotheque("téléchargements"))
    }
}
