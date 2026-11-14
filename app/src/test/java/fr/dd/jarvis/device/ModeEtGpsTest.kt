package fr.dd.jarvis.device

import fr.dd.jarvis.hermes.RelaisGroq
import fr.dd.jarvis.societe.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeEtGpsTest {
    @Test fun modesDuTelephone() {
        assertEquals("economie", ModeTelephone.lire("mets le téléphone en mode économie"))
        assertEquals("economie", ModeTelephone.lire("active l'économiseur de batterie"))
        assertEquals("equilibre", ModeTelephone.lire("désactive l'économiseur de batterie"))
        assertEquals("equilibre", ModeTelephone.lire("passe le téléphone en mode équilibré"))
        assertEquals("performance", ModeTelephone.lire("mets mon portable en mode performance"))
        assertEquals("etat", ModeTelephone.lire("dans quel mode est le téléphone ?"))
    }

    @Test fun pointGpsWaze() {
        val p = Geo.Point(47.5415581, 4.4598449)
        assertEquals("47.541558, 4.459845", AgendaGps.lieu(p))
        assertEquals(p.lat, Geo.lireCoordonnees(AgendaGps.lieu(p))!!.lat, 1e-6)
        assertEquals("https://waze.com/ul?ll=47.541558,4.459845&navigate=yes", Geo.lienWaze(p))
        val d = AgendaGps.description("Code portail 1234\nAdresse : ancienne\nWaze : vieux lien", "3 rue des Lilas 21000 Dijon")
        assertEquals("Code portail 1234\nAdresse : 3 rue des Lilas 21000 Dijon", d)
        assertFalse(AgendaGps.lieu(p).contains("http"))
    }

    @Test fun relaisPrioriseLesNouveauxOutils() {
        assertTrue(RelaisGroq.domaines("remplace l'adresse de mon rendez-vous de demain par le point GPS Waze").contains("agenda_lieu_gps"))
        assertTrue(RelaisGroq.domaines("mets le téléphone en mode performance").contains("telephone_mode_energie"))
        assertFalse(RelaisGroq.domaines("passe en mode économie").contains("telephone_mode_energie"))
    }
}
