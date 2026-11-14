package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaSurTest {
    @Test fun interception() {
        assertEquals("redemarrer", HaSur.interception("docker restart homeassistant", "homeassistant"))
        assertEquals("redemarrer", HaSur.interception("sudo docker restart -t 10 ha_core", "ha_core"))
        assertEquals("redemarrer", HaSur.interception("cd ~/ha && docker compose restart homeassistant".substringAfter("&& "), "homeassistant"))
        assertEquals("mettre_a_jour", HaSur.interception("docker compose pull homeassistant", "homeassistant"))
        assertEquals("mettre_a_jour", HaSur.interception("docker pull ghcr.io/home-assistant/home-assistant:stable", null))
        assertNull(HaSur.interception("docker restart plex", "homeassistant"))
        assertNull(HaSur.interception("docker logs homeassistant", "homeassistant"))
    }

    @Test fun scriptMiseAJour() {
        val s = HaSur.scriptMiseAJour(HaSur.Conteneur("homeassistant", "/home/dd/ha/config", "/home/dd/ha", "homeassistant", "ghcr.io/home-assistant/home-assistant:stable"))
        assertTrue(s.contains("set -u -o pipefail"))
        assertTrue(s.contains("docker tag \"\$AVANT\" jarvis/ha-precedente:latest"))
        assertTrue(s.contains("docker stop -t 120"))
        assertTrue(s.contains("Retour à la version précédente"))
    }
}
