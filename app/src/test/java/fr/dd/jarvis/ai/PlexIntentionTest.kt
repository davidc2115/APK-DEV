package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexIntentionTest {
    @Test fun lancement() {
        val (o, a) = PlexTools.intention("Lance le film Inception sur la télé du salon")!!
        assertEquals("plex_lancer", o); assertEquals("Inception", a.getString("titre")); assertEquals("la télé du salon", a.getString("appareil"))
        val (_, b) = PlexTools.intention("Jarvis, mets la série Lupin sur le téléphone de Marie")!!
        assertEquals("Lupin", b.getString("titre")); assertEquals("le téléphone de Marie", b.getString("appareil"))
        val (_, c) = PlexTools.intention("lance Amélie Poulain sur la télé")!!
        assertEquals("Amélie Poulain", c.getString("titre")); assertEquals("oui", c.optString("verifier"))
        assertNull(PlexTools.intention("mets de la musique sur la télé"))
        assertNull(PlexTools.intention("mets France 2 sur la télé"))
        assertNull(PlexTools.intention("regarde si le film Inception est dans plex"))
        assertNull(PlexTools.intention("allume la télé"))
    }

    @Test fun telecommande() {
        assertEquals("pause", PlexTools.intention("mets le film en pause")!!.second.getString("commande"))
        assertEquals("lecture", PlexTools.intention("reprends le film sur la télé")!!.second.getString("commande"))
        assertEquals("la tele", PlexTools.intention("reprends le film sur la télé")!!.second.getString("appareil"))
        assertEquals("stop", PlexTools.intention("arrête le film")!!.second.getString("commande"))
        assertNull(PlexTools.intention("mets la musique en pause"))
    }

    @Test fun episodesEtPhrasesQuiNeSontPasPourPlex() {
        PlexTools.intention("lance la série Lupin saison 2 épisode 3 sur la télé")!!.second.let {
            assertEquals("Lupin", it.getString("titre")); assertEquals("2", it.getString("saison")); assertEquals("3", it.getString("episode")) }
        PlexTools.intention("lance l'épisode 3 de la saison 2 de Lupin")!!.second.let { assertEquals("Lupin", it.getString("titre")); assertEquals("2", it.getString("saison")) }
        PlexTools.intention("mets le dernier épisode de The Office")!!.second.let { assertEquals("The Office", it.getString("titre")); assertEquals("dernier", it.getString("episode")) }
        PlexTools.intention("lance The Office S02E05 sur la télé")!!.second.let { assertEquals("The Office", it.getString("titre")); assertEquals("5", it.getString("episode")) }
        PlexTools.intention("mets la saison 3 de Friends")!!.second.let { assertEquals("Friends", it.getString("titre")); assertEquals("3", it.getString("saison")) }
        assertEquals("Friends", PlexTools.intention("lance un épisode de Friends sur la télé")!!.second.getString("titre"))
        // « sur Plex » n'est pas un lecteur.
        assertEquals("", PlexTools.intention("Lance Interstellar sur Plex.")!!.second.getString("appareil"))
        // Phrases qui parlent d'un film sans demander d'en lancer un.
        assertNull(PlexTools.intention("mets le film sur la télé"))
        assertNull(PlexTools.intention("mets un rappel pour regarder le film demain"))
        assertNull(PlexTools.intention("je regarde un film ce soir mets le mode cinéma"))
        assertNull(PlexTools.intention("rappelle-moi de regarder la série demain"))
    }

    @Test fun titreDicteRetrouve() {
        fun m(t: String, type: String = "movie", annee: String = "") = org.json.JSONObject().put("title", t).put("type", type).put("year", annee)
        assertTrue(Plex.score(m("Le Roi Lion"), "roi lion") >= 80)
        assertTrue(Plex.score(m("Amélie"), "amelie") >= 80)
        assertTrue(Plex.score(m("Interstellar"), "Interstelar") >= 80)
        assertTrue(Plex.score(m("Avatar : La Voie de l'eau"), "Avatar 2") < Plex.score(m("Avatar 2"), "Avatar 2"))
        assertTrue(Plex.score(m("Avatar"), "un rappel pour regarder le film demain") < 45)
        assertTrue(Plex.variantes("Le Roi Lion (1994)").contains("Roi Lion"))
        assertTrue(Plex.variantes("Amélie").contains("Amelie"))
    }
}
