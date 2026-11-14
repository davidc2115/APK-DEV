package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaitsTest {
    @Test fun lieux() {
        assertEquals("domicile", Faits.nomLieu("la maison"))
        assertEquals("domicile", Faits.nomLieu("chez moi"))
        assertEquals("domicile", Faits.nomLieu("mon adresse"))
        assertEquals("travail", Faits.nomLieu("le bureau"))
        assertEquals("salle de sport", Faits.nomLieu("la salle de sport"))
    }

    @Test fun carnetRetiens() {
        val i = Carnet.intention("Jarvis, retiens que mon adresse est 12 rue des Lilas 21000 Dijon")
        assertTrue(i.toString(), i is Carnet.Intention.Noter && (i as Carnet.Intention.Noter).texte.contains("12 rue des Lilas"))
        val j = Carnet.intention("souviens-toi que « les potes » c'est le serveur Discord « Les Copains du 21 »")
        assertTrue(j.toString(), j is Carnet.Intention.Noter)
        assertNull(Carnet.intention("rappelle-moi d'appeler Paul demain"))
    }
}
