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
        assertTrue(script.contains("Transcodages actifs"))
        assertTrue(script.contains("plexmediaserver"))
        assertTrue(script.contains("Détails des lectures"))
        assertTrue(script.contains("Failed password"))
        assertTrue(script.contains("fail2ban-client status"))
        assertTrue(script.contains("Currently banned"))
        assertTrue(script.contains("Total failed"))
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
    fun diagnosticDiscordInspectePm2SansAfficherLesSecrets() {
        val script = ServeurDiagnostic.commande("discord", "", 40)
        assertTrue(script.contains("pm2 jlist"))
        assertTrue(script.contains("pm2 logs"))
        assertTrue(script.contains("métadonnées non sensibles"))
        assertFalse(script.contains("pm2 restart"))
        assertFalse(script.contains("pm2 stop"))
    }

    @Test
    fun limitesEtCibleInconnueSontControlees() {
        assertTrue(ServeurDiagnostic.commande("debian", "", 1).contains("--tail 20"))
        assertTrue(ServeurDiagnostic.commande("debian", "", 500).contains("--tail 200"))
        assertTrue(ServeurDiagnostic.commande("debian", "", 40, sudo = true).contains("sudo -n docker ps -a"))
        assertTrue(ServeurDiagnostic.commande("debian", "", 40, sudo = true).contains("sudo -n fail2ban-client status"))
        var refuse = false
        try {
            ServeurDiagnostic.commande("freebox", "", 80)
        } catch (_: IllegalArgumentException) {
            refuse = true
        }
        assertTrue(refuse)
    }

    @Test
    fun depannagesVerifientLesErreursEtLesEtatsFinaux() {
        val nettoyage = ServeurDiagnostic.commandeRemede(ServeurDiagnostic.Remede.NETTOYER_DISQUE)
        val services = ServeurDiagnostic.commandeRemede(ServeurDiagnostic.Remede.RELANCER_ECHECS)
        val heure = ServeurDiagnostic.commandeRemede(ServeurDiagnostic.Remede.SYNCHRONISER_HEURE)
        val docker = ServeurDiagnostic.commandeRemede(ServeurDiagnostic.Remede.REDEMARRER_DOCKER)
        assertTrue(nettoyage.contains("set -euo pipefail"))
        assertTrue(services.contains("set -euo pipefail"))
        assertTrue(services.contains(" = active ]"))
        assertTrue(heure.contains(" = yes ]"))
        assertTrue(docker.contains("systemctl is-active --quiet docker"))
    }

    @Test
    fun echecDeRemedeNePeutPasEtreAnnonceCommeReussi() {
        val r = ServeurDiagnostic.Remede.REDEMARRER_DOCKER
        val echec = ServeurDiagnostic.resultatRemede(r, "permission denied", 1)
        assertTrue(echec.contains("Échec du dépannage"))
        assertFalse(echec.contains(" : vérifié."))
        val nettoyageNonMesure = ServeurDiagnostic.resultatRemede(
            ServeurDiagnostic.Remede.NETTOYER_DISQUE, "commande terminée", 0
        )
        assertTrue(nettoyageNonMesure.contains("non confirmé"))
    }
}
