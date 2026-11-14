package fr.dd.jarvis.hermes

import fr.dd.jarvis.ai.SitesWebTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepriseTest {
    @Test fun phrasesDeReprise() {
        assertTrue(Reprise.estReprise("est-ce que tu peux recommencer et reprendre là où tu en étais"))
        assertTrue(Reprise.estReprise("Reprends"))
        assertTrue(Reprise.estReprise("relance la demande stp"))
        assertTrue(Reprise.estReprise("continue là où tu en étais"))
        assertFalse(Reprise.estReprise("reprends la musique"))
        assertFalse(Reprise.estReprise("continue l'histoire"))
        assertFalse(Reprise.estReprise("continue"))
        assertFalse(Reprise.estReprise("recommence le minuteur de 10 minutes"))
        assertTrue(Reprise.echec("⚠️ Connexion au moteur Hermès interrompue : timeout. La demande n'a pas pu être confirmée."))
        assertFalse(Reprise.echec("✅ Site créé."))
    }

    @Test fun siteSociete() {
        val a = SitesWebTools.intention("Est-ce que tu peux créer un site web pour la société", emptyList())!!
        assertEquals("creer", a.getString("action"))
        val b = SitesWebTools.intention("crée le site de ma société", emptyList())!!
        assertEquals("creer", b.getString("action"))
    }
}
