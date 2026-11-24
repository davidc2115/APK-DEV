package fr.dd.jarvis.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantDashboardTest {
    @Test fun ignoreLesEvenementsSansIdentifiant() {
        assertFalse(HomeAssistantDashboard.correspondCommande("""{"type":"event","event":{"data":{}}}""", 7))
    }

    @Test fun ignoreLaReponseDuneAutreCommande() {
        assertFalse(HomeAssistantDashboard.correspondCommande("""{"id":6,"type":"result","success":true}""", 7))
    }

    @Test fun reconnaitUniquementLidentifiantAttendu() {
        assertTrue(HomeAssistantDashboard.correspondCommande("""{"id":7,"type":"result","success":true}""", 7))
    }

    @Test fun ignoreUnMessageJsonInvalideSansFaireEchouerLaCorrelation() {
        assertFalse(HomeAssistantDashboard.correspondCommande("message incomplet", 7))
    }
}
