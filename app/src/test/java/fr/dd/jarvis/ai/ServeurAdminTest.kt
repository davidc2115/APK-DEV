package fr.dd.jarvis.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServeurAdminTest {
    private val pre = "sudo -S -p '' env HOME=\"\$HOME\" bash -c "

    @Test fun sudoUneSeuleFois() {
        assertEquals("ls /root", Serveur.commandeSudo("ls /root", false))
        assertEquals(pre + "'docker stop ha && docker start ha'", Serveur.commandeSudo("sudo docker stop ha && sudo docker start ha", false))
        assertEquals("docker ps", Serveur.commandeSudo("sudo docker ps", true))
        assertEquals(pre + "'echo '\\''a b'\\'''", Serveur.commandeSudo("sudo echo 'a b'", false))
        assertEquals("docker ps || sudo -n docker ps", Serveur.commandeSudo("docker ps || sudo -n docker ps", false))
        assertEquals("sudo -S -p '' -u plex ls", Serveur.commandeSudo("sudo -u plex ls", false))
    }

    @Test fun demandesStockageHomeAssistantEtPlexDeclenchentInspectionServeur() {
        assertTrue(ServeurAdminTools.concerned("Où se trouve le stockage de Home Assistant ?"))
        assertTrue(ServeurAdminTools.concerned("Où sont les volumes Docker de Plex ?"))
        assertTrue(ServeurAdminTools.concerned("Liste les services du serveur Debian"))
    }

    @Test fun homeAssistantApiEtStockageDebianRestentDistincts() {
        assertTrue(ServeurAdminTools.actionLectureSeuleHa("etat"))
        assertTrue(ServeurAdminTools.actionLectureSeuleHa("logs"))
        assertTrue(ServeurAdminTools.actionLectureSeuleHa("stockage"))
        assertEquals(false, ServeurAdminTools.actionLectureSeuleHa("demarrer"))
        assertEquals(false, ServeurAdminTools.actionLectureSeuleHa("redemarrer"))
        assertEquals(false, ServeurAdminTools.actionLectureSeuleHa("mettre_a_jour"))
    }

    @Test fun motifsDesServices() {
        assertTrue(ServeurAdminTools.motifs("Home Assistant").contains("homeassistant"))
        assertTrue(ServeurAdminTools.motifs("le bot Discord").contains("discord"))
        assertEquals(listOf("plex"), ServeurAdminTools.motifs("plex"))
        assertEquals(listOf("vaultwarden"), ServeurAdminTools.motifs("le service vaultwarden"))
    }

    @Test fun plexLibelles() {
        val ep = JSONObject().put("type", "episode").put("grandparentTitle", "Dark").put("parentIndex", 2).put("index", 5).put("title", "Lost and Found")
        assertEquals("Dark — S02E05 « Lost and Found »", Plex.titre(ep))
        assertEquals("1 h 52", Plex.duree(112 * 60_000L))
        assertEquals("Inception (2010)", Plex.titre(JSONObject().put("type", "movie").put("title", "Inception").put("year", 2010)))
    }
}
