package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServeurTimeoutPolicyTest {
    @Test fun lecturesEtatsUtilisentDesDelaisCourtsEtUnSeulRetry() {
        val liste = Serveur.delaisHa("GET", "/api/states")
        val entite = Serveur.delaisHa("GET", "/api/states/climate.poele")
        assertEquals(4L, liste.connexion)
        assertEquals(8L, liste.lecture)
        assertEquals(10L, liste.appel)
        assertEquals(liste, entite)
        assertTrue(Serveur.estLectureEtatRapide("GET", "/api/states"))
        assertTrue(Serveur.estLectureEtatRapide("GET", "/api/states/light.salon"))
        assertFalse(Serveur.estLectureEtatRapide("POST", "/api/states"))
    }

    @Test fun serviceDomestiqueEstEncadreSansRelancerUnPost() {
        val d = Serveur.delaisHa("POST", "/api/services/climate/set_temperature")
        assertEquals(6L, d.connexion)
        assertEquals(12L, d.lecture)
        assertEquals(15L, d.appel)
        assertFalse(Serveur.estLectureEtatRapide("POST", "/api/services/light/turn_on"))
    }

    @Test fun operationsAdministrationConserventLesDelaisLongs() {
        assertEquals(Serveur.DelaisHa(10L, 40L, 45L), Serveur.delaisHa("GET", "/api/config"))
        assertEquals(Serveur.DelaisHa(10L, 40L, 45L), Serveur.delaisHa("POST", "/api/config/core/stop"))
    }
}
