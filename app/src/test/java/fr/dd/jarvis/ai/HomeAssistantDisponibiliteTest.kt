package fr.dd.jarvis.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantDisponibiliteTest {
    @Test fun intentionExpliciteResteDansHomeAssistant() {
        assertTrue(ServeurTools.prioriteHaDisponibilite("Quels appareils sont indisponibles sur Home Assistant ?"))
        assertTrue(ServeurTools.prioriteHaDisponibilite("Quels appareils sont injoignables dans HA ?"))
        assertTrue(ServeurTools.prioriteHaDisponibilite("Liste les appareils hors ligne de Home Assistant"))
        assertFalse(ServeurTools.prioriteHaDisponibilite("Quels appareils sont indisponibles sur mon réseau Wi-Fi ?"))
        assertFalse(ServeurTools.prioriteHaDisponibilite("Liste les appareils connectés à Jarvis"))
    }

    @Test fun filtreLesEtatsHomeAssistantSansConfondreWifi() {
        assertTrue(Serveur.correspondEtatHa("unavailable", "indisponible"))
        assertTrue(Serveur.correspondEtatHa("unknown", "inconnu"))
        assertTrue(Serveur.correspondEtatHa("unavailable", "indisponible ou inconnu"))
        assertTrue(Serveur.correspondEtatHa("unknown", "tous les problèmes"))
        assertFalse(Serveur.correspondEtatHa("on", "indisponible"))
        assertFalse(Serveur.correspondEtatHa("off", "inconnu"))
        assertTrue(Serveur.correspondEtatHa("on", ""))
    }
}
