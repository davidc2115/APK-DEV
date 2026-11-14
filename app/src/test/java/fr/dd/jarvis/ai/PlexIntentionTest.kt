package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
