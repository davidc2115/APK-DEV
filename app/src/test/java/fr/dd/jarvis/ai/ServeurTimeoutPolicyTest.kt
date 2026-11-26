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

    @Test fun leReseauMobileNeDoitJamaisEtrePrisPourLeLanMemeSiLoperateurUtiliseDu10x() {
        val local = "10.23.4.12" to 48222
        val distant = "ssh.exemple.net" to 48222
        val lan = listOf("10.23.4.12" to 22)
        assertEquals(listOf(distant), Serveur.ordreCiblesSsh(local, distant, lan, surReseauMaison = false))
    }

    @Test fun horsMaisonSansAdresseDistanteNessaiePasUneIpPriveeInjoignable() {
        assertTrue(Serveur.ordreCiblesSsh(
            local = "192.168.1.20" to 22,
            distant = null,
            lan = listOf("192.168.1.20" to 22),
            surReseauMaison = false
        ).isEmpty())
    }

    @Test fun aLaMaisonLeLanPasseAvantLeDistantEtGardeUnRepli() {
        val local = "serveur.exemple.net" to 48222
        val lan = listOf("192.168.1.20" to 22)
        val distant = "serveur.exemple.net" to 48222
        assertEquals(lan + listOf(local), Serveur.ordreCiblesSsh(local, distant, lan, surReseauMaison = true))
    }

    @Test fun leMasqueReseauAndroidEstRespecteAuLieuDeSupposerUnSlash24() {
        assertTrue(Serveur.memeSousReseau("192.168.10.20", "192.168.200.5", 16))
        assertFalse(Serveur.memeSousReseau("192.168.10.20", "192.168.11.5", 24))
        assertTrue(Serveur.memeSousReseau("192.168.10.20", "192.168.10.5", 24))
        assertFalse(Serveur.memeSousReseau("192.168.10.20", "192.168.10.5", 33))
    }

    @Test fun operationsAdministrationConserventLesDelaisLongs() {
        assertEquals(Serveur.DelaisHa(10L, 40L, 45L), Serveur.delaisHa("GET", "/api/config"))
        assertEquals(Serveur.DelaisHa(10L, 40L, 45L), Serveur.delaisHa("POST", "/api/config/core/stop"))
    }
}
