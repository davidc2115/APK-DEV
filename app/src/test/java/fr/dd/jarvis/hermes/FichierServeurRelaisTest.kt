package fr.dd.jarvis.hermes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FichierServeurRelaisTest {
    @Test fun detection() {
        assertTrue(RelaisGroq.fichierServeur("modifie le fichier configuration.yaml de home assistant sur le serveur"))
        assertTrue(RelaisGroq.fichierServeur("réécris /etc/nginx/sites-available/default"))
        assertTrue(RelaisGroq.fichierServeur("change le port dans ~/bot/config.json du bot discord"))
        assertFalse(RelaisGroq.fichierServeur("envoie un sms à marie"))
        assertFalse(RelaisGroq.fichierServeur("ouvre le fichier pdf dans mes téléchargements"))
        assertTrue(RelaisGroq.domaines("modifie le fichier docker-compose.yml sur le serveur").contains("ssh_fichier"))
    }

    @Test fun suiteBavardage() {
        Routeur.marquerBavardage()
        assertFalse(Routeur.suiteBavardage("très bien alors tu peux regarder ça", "🔔 **Diagnostic**\n\nle certificat…"))
        Routeur.marquerBavardage()
        assertFalse(Routeur.suiteBavardage("du coup vérifie", "Ça va bien, merci !"))
        Routeur.marquerBavardage()
        assertTrue(Routeur.suiteBavardage("haha oui moi aussi", "Ça va bien, merci ! Et toi ?"))
    }
}
