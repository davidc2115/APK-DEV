package fr.dd.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Écoute décidée par les mots du modèle hors-ligne (trames de 20 ms). */
class EcouteParMotsTest {
    /** Rejoue une suite (trame → texte en direct) ; renvoie la trame de fin ou -1. */
    private fun jouer(e: EcouteParMots, textes: Map<Int, String>, jusqua: Int): Int {
        var t = ""
        for (n in 1..jusqua) { textes[n]?.let { t = it }; if (e.trame(t, n)) return n }
        return -1
    }

    @Test fun laTeleSansMotsNeDeclenchePas() {
        val e = EcouteParMots(pauseTrames = 85)
        assertEquals(-1, jouer(e, mapOf(100 to "euh", 300 to "hum"), 500))
        assertFalse(e.parle)
    }

    @Test fun finDeuxSecondesApresLeDernierMot() {
        val e = EcouteParMots(pauseTrames = 85)
        val n = jouer(e, mapOf(50 to "allume", 60 to "allume la", 75 to "allume la lampe", 90 to "allume la lampe torche"), 1000)
        assertTrue(e.parle)
        assertEquals(50, e.trameDebut)
        assertEquals(90 + 85, n)                  // le bruit de la télé ensuite ne prolonge rien
    }

    @Test fun phraseInacheveeAttendSaSuite() {
        val e = EcouteParMots(pauseTrames = 85)
        val n = jouer(e, mapOf(50 to "envoie un message à", 250 to "envoie un message à paul"), 1000)
        assertEquals(250 + 85, n)                 // la suite est venue 4 s plus tard : attendue
    }

    @Test fun decoupeAutourDesMotsSurs() {
        val mots = listOf(MotDate("passe", 0.5, 0.8, 0.3), MotDate("allume", 3.0, 3.4, 0.95), MotDate("torche", 3.9, 4.3, 0.9),
            MotDate("jardin", 7.0, 7.4, 0.4))
        val (de, a) = EcouteParMots.decoupe(mots, 16000 * 10, 16000)!!
        assertEquals(((3.0 - 0.4) * 16000).toInt(), de)    // le mot douteux de la télé avant est laissé
        assertEquals(((4.3 + 0.5) * 16000).toInt(), a)     // et celui d'après aussi
        assertNull(EcouteParMots.decoupe(emptyList(), 1000, 16000))
    }
}
