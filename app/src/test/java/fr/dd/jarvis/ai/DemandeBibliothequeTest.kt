package fr.dd.jarvis.ai

import fr.dd.jarvis.device.MusiqueBibliotheque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DemandeBibliothequeTest {
    private fun i(t: String) = CommandesDirectes.intention(t)
    private fun biblio(t: String): Triple<String, String, String>? = i(t)?.takeIf { it.outil == "musique_bibliotheque" }?.args?.let {
        Triple(it.getString("section"), it.getString("nom"), it.getString("mode"))
    }

    @Test fun jaime() {
        listOf("mets mes titres J'aime", "joue ma bibliothèque J'aime", "lance la playlist J'aime", "mets les titres que j'aime",
            "joue mes favoris", "mets mes titres likés", "lance mes coups de cœur", "mets ma liste j'aime sur YouTube Music",
            "tu peux me mettre mes titres préférés", "lance ma bibliothèque", "mets mes chansons").forEach {
            assertEquals(it, Triple("jaime", "", "normal"), biblio(it))
        }
        assertEquals(Triple("jaime", "", "aleatoire"), biblio("mets mes titres J'aime en aléatoire"))
        assertEquals(Triple("jaime", "", "aleatoire"), biblio("joue mes favoris au hasard s'il te plaît"))
        assertEquals(Triple("jaime", "", "repeter_playlist"), biblio("mets ma playlist j'aime en boucle"))
    }

    @Test fun sectionsPersonnelles() {
        assertEquals(Triple("playlists", "sport", "normal"), biblio("mets ma playlist sport"))
        assertEquals(Triple("playlists", "chill du soir", "aleatoire"), biblio("joue ma playlist chill du soir en aléatoire"))
        assertEquals(Triple("playlists", "", "normal"), biblio("lance mes playlists"))
        assertEquals(Triple("albums", "discovery", "normal"), biblio("mets mon album Discovery"))
        assertEquals(Triple("telechargements", "", "normal"), biblio("joue mes téléchargements"))
    }

    @Test fun catalogueInchange() {
        // Sans possessif ni « J'aime » : recherche dans le catalogue, comme avant.
        assertEquals("jouer_musique", i("mets la playlist sport")?.outil)
        assertEquals("jouer_musique", i("joue Daft Punk")?.outil)
        assertEquals("jouer_musique", i("joue J'aime")?.outil)                     // peut être un titre
        assertNull(DemandeBibliotheque.analyser("la chanson de daft punk"))
        assertNull(DemandeBibliotheque.analyser("mes sms"))
    }

    @Test fun requeteDuModele() {
        // Requête envoyée par l'IA à jouer_musique : reconnue aussi.
        assertEquals("jaime", DemandeBibliotheque.analyser(CommandesDirectes.plat("mes titres J'aime").trim())?.section)
        assertEquals("jaime", DemandeBibliotheque.analyser(CommandesDirectes.plat("titres aimés").trim().replace(Regex("\\s+"), " "))?.section)
    }

    @Test fun playlistDejaVue() {
        val connues = listOf("Sport 💪", "Chill du soir", "Été 2025")
        assertEquals("Chill du soir", MusiqueBibliotheque.playlistParmi(connues, "la playlist chill du soir"))
        assertEquals("Été 2025", MusiqueBibliotheque.playlistParmi(connues, "playlist ete 2025"))
        assertEquals("Sport 💪", MusiqueBibliotheque.playlistParmi(connues, "Sport"))
        assertNull(MusiqueBibliotheque.playlistParmi(connues, "daft punk"))
    }
}
