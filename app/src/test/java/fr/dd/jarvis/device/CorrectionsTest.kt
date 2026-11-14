package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionsTest {
    private val introuvable = "Aucun serveur « bag » sur « Discord ». Disponibles : B&G, Les Copains."

    @Test fun correctionApresIntrouvable() {
        assertEquals(Triple("bag", "B&G", "discord_serveur"), Corrections.correction("non, c'est B&G", introuvable))
        assertEquals(Triple("bag", "B&G", "discord_serveur"), Corrections.correction("je parlais du serveur « B&G »", introuvable))
        assertEquals("discord_salon", Corrections.correction("je voulais dire le salon discussion",
            "Aucun salon « discu » sur « B&G ». Disponibles : discussion.")?.third)
        assertNull(Corrections.correction("c'est B&G", "Voici le résumé."))
        assertNull(Corrections.correction("résume la discussion d'hier", introuvable))
        assertEquals("contact" to "maman", Corrections.introuvable("Contact introuvable (ou sans numéro) : maman."))
    }

    @Test fun definitions() {
        assertTrue(Corrections.definitionSeule("quand je dis bag je parle du serveur B&G"))
        assertFalse(Corrections.definitionSeule("quand je dis bag je parle du serveur B&G, puis résume la discussion"))
        assertTrue(Corrections.GARDE.containsMatchIn("« les potes » c'est le serveur Les Copains"))
        assertFalse(Corrections.GARDE.containsMatchIn("envoie « salut » c'est tout"))
    }
}
