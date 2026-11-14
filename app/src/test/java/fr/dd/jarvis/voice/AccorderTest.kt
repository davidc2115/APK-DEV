package fr.dd.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cas tirés du journal du téléphone (télé et personnes autour). */
class AccorderTest {
    @Test fun mauvaiseLangue() {
        assertTrue(etranger("Erumina náðum tors, sýlstöfley"))
        assertFalse(etranger("Éteins la lumière, s'il te plaît, ça ira mieux"))
        assertEquals("allume la lampe torche te plaît", accorder("Erumina náðum tors, sýlstöfley", "allume la lampe torche te plaît").first)
    }

    @Test fun phraseDeLaTeleRetiree() {
        val (t, note) = accorder("Éteins la lampe torche. Ah ouais, c'est vraiment bien", "éteindre la lampe torche")
        assertEquals("Éteins la lampe torche.", t)
        assertEquals("voix autour retirée", note)
    }

    @Test fun autreVoix() {
        assertEquals("la lampe torche si", accorder("Ah mon dieu, vous n'êtes pas prêtes. Si ? Ah oui ? Ah pardon. Allons-y", "la lampe torche si").first)
    }

    @Test fun accordNormal() {
        val (t, note) = accorder("Montre le son à 25 sur la télé", "monte le son à vingt-cinq sur la télé jack")
        assertEquals("Montre le son à 25 sur la télé", t)
        assertNull(note)
        assertEquals("Allume la lampe torche", accorder("Allume la lampe torche", "allume la lampe torche").first)
        assertEquals("Oui", accorder("Oui", "").first)
    }
}
