package fr.dd.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les réponses longues doivent être dites intégralement, même sans ponctuation. */
class VoixNaturelleTest {
    @Test fun decoupeLesLonguesPhrasesSansPerdreDeMots() {
        val texte = List(120) { "mot$it" }.joinToString(" ")
        val (morceaux, reste) = VoixNaturelle.decouper(
            texte = texte,
            dejaUnMorceau = false,
            toutDire = true,
            attendreBloc = false,
            taille = 80
        )

        assertEquals("", reste)
        assertTrue("un segment dépasse 80 caractères", morceaux.all { it.length <= 80 })
        assertEquals(texte, morceaux.joinToString(" "))
    }

    @Test fun decoupeProgressivementUnFluxSansPonctuation() {
        val texte = List(40) { "terme$it" }.joinToString(" ")
        val (morceaux, reste) = VoixNaturelle.decouper(
            texte = texte,
            dejaUnMorceau = true,
            toutDire = false,
            attendreBloc = false,
            taille = 70
        )

        assertTrue(morceaux.isNotEmpty())
        assertTrue(morceaux.all { it.length <= 70 })
        assertEquals(texte, (morceaux + reste.trim()).joinToString(" "))
    }
}
