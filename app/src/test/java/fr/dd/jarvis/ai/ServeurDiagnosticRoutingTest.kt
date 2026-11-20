package fr.dd.jarvis.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class ServeurDiagnosticRoutingTest {
    @Test
    fun lesDemandesPlexHomeAssistantEtSshExposentLesOutilsServeur() {
        assertTrue(ServeurTools.concerned("Lance un diagnostic Plex"))
        assertTrue(ServeurTools.concerned("Fais un diagnostic Home Assistant"))
        assertTrue(ServeurTools.concerned("Vérifie le serveur SSH"))
        assertTrue(ServeurTools.concerned("Fais un diagnostic Bagbot avec PM2 sur Debian"))
        assertTrue(ServeurTools.concerned("Regarde les journaux du bot Discord sur le serveur Debian"))
    }
}
