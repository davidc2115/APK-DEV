package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RappelsLieuTest {
    @Test fun demandes() {
        assertEquals(RappelsLieu.Demande("Acheter du pain", "maison", false, false), RappelsLieu.intention("Rappelle-moi d'acheter du pain quand j'arrive à la maison"))
        assertEquals(RappelsLieu.Demande("Vérifier l'onduleur", "Dupont", false, false), RappelsLieu.intention("Quand j'arrive chez Dupont, rappelle-moi de vérifier l'onduleur"))
        assertEquals(RappelsLieu.Demande("Fermer le coffret", "chantier", true, false), RappelsLieu.intention("rappelle-moi de fermer le coffret en partant du chantier"))
        assertEquals(RappelsLieu.Demande("Sortir les poubelles", "maison", false, true), RappelsLieu.intention("à chaque fois que j'arrive à la maison, rappelle-moi de sortir les poubelles"))
        assertEquals("ici", RappelsLieu.intention("rappelle-moi de prendre les clés quand je pars d'ici")?.lieu)
        assertTrue(RappelsLieu.intention("rappelle-moi de prendre les clés quand je pars d'ici")!!.depart)
        assertNull(RappelsLieu.intention("rappelle-moi d'appeler Martin demain à 9h"))
        assertNull(RappelsLieu.intention("rappelle-moi dans 10 minutes de sortir le gâteau"))
        assertNull(RappelsLieu.intention("quand j'arrive à 9h, rappelle-moi de badger"))
        assertNull(RappelsLieu.intention("où est ma voiture"))
        assertTrue(RappelsLieu.distance(47.0, 5.0, 47.001, 5.0) in 100.0..120.0)
    }
}
