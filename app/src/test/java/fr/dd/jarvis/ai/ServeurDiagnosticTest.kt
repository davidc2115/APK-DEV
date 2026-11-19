package fr.dd.jarvis.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServeurDiagnosticTest {
    @Test
    fun diagnosticCompletResteEnLectureSeuleEtCouvreLesCouches() {
        val script = ServeurDiagnostic.commande("complet", "", 999)
        assertTrue(script.contains("== Hôte Debian =="))
        assertTrue(script.contains("== SSH : service, écoute"))
        assertTrue(script.contains("== Docker : conteneurs Plex / Home Assistant =="))
        assertTrue(script.contains("== Plex Media Server : API locale =="))
        assertTrue(script.contains("docker stats --no-stream"))
        assertTrue(script.contains("OOM="))
        assertTrue(script.contains("Volumes (hôte -> conteneur)"))
        assertTrue(script.contains("--tail 200"))
        assertFalse(Regex("docker\\s+(restart|stop|rm)\\b|systemctl\\s+(restart|stop)\\b|rm\\s+-rf|apt-get\\s+").containsMatchIn(script))
    }

    @Test
    fun diagnosticsCiblesNeMelangentPasLesServices() {
        val plex = ServeurDiagnostic.commande("plex", "jeton-test", 40)
        assertTrue(plex.contains("X-Plex-Token"))
        assertTrue(plex.contains("/library/sections"))
        assertFalse(plex.contains("sshd -T"))
        val ha = ServeurDiagnostic.commande("home assistant", "", 40)
        assertTrue(ha.contains("home.?assistant|hass"))
        assertFalse(ha.contains("32400/identity"))
        assertFalse(ha.contains("sshd -T"))
        val ssh = ServeurDiagnostic.commande("ssh", "", 40)
        assertTrue(ssh.contains("sshd -T"))
        assertFalse(ssh.contains("docker ps -a"))
        assertFalse(ssh.contains("32400/identity"))
    }

    @Test
    fun limitesEtCibleInconnueSontControlees() {
        assertTrue(ServeurDiagnostic.commande("debian", "", 1).contains("--tail 20"))
        assertTrue(ServeurDiagnostic.commande("debian", "", 500).contains("--tail 200"))
        var refuse = false
        try {
            ServeurDiagnostic.commande("freebox", "", 80)
        } catch (_: IllegalArgumentException) {
            refuse = true
        }
        assertTrue(refuse)
    }
}
