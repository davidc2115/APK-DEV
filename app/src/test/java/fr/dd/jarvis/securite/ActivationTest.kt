package fr.dd.jarvis.securite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivationTest {
    private val l = listOf(
        Systemes.Systeme(id = "telephone_securise", nom = "Téléphone sécurisé", modele = "telephone_securise", phrasesActiver = listOf("active le mode telephone securise")),
        Systemes.Systeme(id = "maison_securisee", nom = "Maison sécurisée", modele = "maison_securisee"),
        Systemes.Systeme(id = "mode_cinema", nom = "Mode cinéma"),
    )

    @Test fun phrasesNaturelles() {
        assertEquals(0 to true, Systemes.parPhrase("Jarvis, active le mode téléphone sécurisé", l))
        assertEquals(0 to true, Systemes.parPhrase("mets le téléphone en mode sécurisé", l))
        assertEquals(0 to false, Systemes.parPhrase("désactive le mode sécurisé du portable", l))
        assertEquals(1 to true, Systemes.parPhrase("sécurise la maison", l))
        assertEquals(1 to true, Systemes.parPhrase("active la sécurité de la maison", l))
        assertEquals(1 to false, Systemes.parPhrase("désactive la maison sécurisée", l))
        assertEquals(2 to true, Systemes.parPhrase("lance le mode cinéma", l))
        assertEquals(2 to false, Systemes.parPhrase("arrête le mode cinéma", l))
    }

    @Test fun pasDeFauxPositif() {
        assertNull(Systemes.parPhrase("passe en mode sécurisé", l))            // téléphone ou maison ? → l'IA demande
        assertNull(Systemes.parPhrase("est-ce que la maison sécurisée est active ?", l))
        assertNull(Systemes.parPhrase("ajoute la caméra du garage au mode maison sécurisée", l))
        assertNull(Systemes.parPhrase("allume la lumière du salon", l))
    }

    @Test fun activationAvantInstallation() {
        val a = Systemes.intention("active le mode maison sécurisée", emptyList())!!
        assertEquals("creer", a.getString("action")); assertEquals(true, a.optBoolean("activer_apres"))
    }

    @Test fun questionPuisReponse() {
        val (noms, activer) = Systemes.ambigus("passe en mode sécurisé", l)!!
        assertEquals(listOf("Téléphone sécurisé", "Maison sécurisée"), noms); assertEquals(true, activer)
        assertEquals(0, Systemes.choix("le téléphone", l))
        assertEquals(1, Systemes.choix("la maison", l))
        assertNull(Systemes.choix("sécurisé", l))
        assertNull(Systemes.ambigus("passe en mode performance", l))
    }
}
