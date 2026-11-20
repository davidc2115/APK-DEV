package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaSurTest {
    @Test fun filtreLesEtatsIndisponiblesDeHomeAssistantSansConfondreUnknown() {
        assertTrue(Serveur.correspondEtatHa("unavailable", "indisponible"))
        assertTrue(Serveur.correspondEtatHa("unavailable", "injoignable"))
        assertTrue(Serveur.correspondEtatHa("unavailable", "hors ligne"))
        assertTrue(Serveur.correspondEtatHa("unknown", "inconnu"))
        assertTrue(Serveur.correspondEtatHa("unknown", "tous les problèmes"))
        assertTrue(Serveur.correspondEtatHa("unavailable", "tous les problèmes"))
        assertTrue(!Serveur.correspondEtatHa("on", "indisponible"))
        assertTrue(!Serveur.correspondEtatHa("off", "injoignable"))
        assertTrue(Serveur.correspondEtatHa("on", ""))
    }
    @Test fun routeLesAppareilsIndisponiblesVersHomeAssistantEtPasLeWifi() {
        assertTrue(ServeurTools.prioriteHaDisponibilite("Quels appareils sont indisponibles sur Home Assistant ?"))
        assertTrue(ServeurTools.prioriteHaDisponibilite("liste les appareils injoignables sur HA"))
        assertTrue(!ServeurTools.prioriteHaDisponibilite("Quels appareils sont déconnectés du Wi-Fi ?"))
        assertTrue(!ServeurTools.prioriteHaDisponibilite("Quels appareils sont indisponibles ?"))
    }

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

class CommandeIncompleteTest {
    @Test fun detecteLesCommandesCoupees() {
        org.junit.Assert.assertNull(Serveur.commandeIncomplete("docker restart homeassistant"))
        org.junit.Assert.assertNull(Serveur.commandeIncomplete("cat <<'EOF' > /config/a.yaml\nname: l'entrée\nEOF\necho ok"))
        org.junit.Assert.assertNull(Serveur.commandeIncomplete("grep -c x <<< \"abc\""))
        org.junit.Assert.assertNotNull(Serveur.commandeIncomplete("cd /opt/ha && docker compose pull &&"))
        org.junit.Assert.assertNotNull(Serveur.commandeIncomplete("docker logs homeassistant |"))
        org.junit.Assert.assertNotNull(Serveur.commandeIncomplete("cat <<EOF > /config/configuration.yaml\nhomeassistant:\n  name: Maison"))
        org.junit.Assert.assertNotNull(Serveur.commandeIncomplete("sudo apt-get install -y \\"))
    }
}
