package fr.dd.jarvis.ai

import fr.dd.jarvis.hermes.ServeurInventaire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonneOuTest {
    @Test fun questionsDePosition() {
        assertEquals("angeline", PersonneOu.personne("Où se trouvait Angeline ?"))
        assertEquals("angeline", PersonneOu.personne("Jarvis, où est Angeline en ce moment"))
        assertEquals("ma femme", PersonneOu.personne("où se trouve ma femme ?"))
        assertEquals("paul", PersonneOu.personne("donne-moi la position de Paul"))
        assertNull(PersonneOu.personne("où est le fichier de facture"))
        assertNull(PersonneOu.personne("où sont mes clés"))
        assertNull(PersonneOu.personne("où est la pharmacie la plus proche"))
        assertNull(PersonneOu.personne("allume la lumière du salon"))
        assertNull(PersonneOu.personne("où est mon téléphone"))
    }

    @Test fun sshSeulementPourLeServeur() {
        assertFalse(ServeurInventaire.serveurDemande("Où se trouvait Angeline ?"))
        assertFalse(ServeurInventaire.serveurDemande("allume la lumière du salon dans home assistant"))
        assertFalse(ServeurInventaire.serveurDemande("Qu'est-ce que j'ai noté sur le rendez-vous de lundi ?"))
        assertTrue(ServeurInventaire.serveurDemande("regarde le docker home assistant et règle le problème de TLS"))
        assertFalse(ServeurInventaire.serveurDemande("cherche l'entité de la lampe du salon dans home assistant"))
        assertFalse(ServeurInventaire.serveurDemande("home assistant ne répond plus"))
        assertTrue(ServeurInventaire.serveurDemande("home assistant ne répond plus, regarde sur le serveur"))
        assertTrue(ServeurInventaire.serveurDemande("redémarre le bot discord sur le serveur"))
        assertTrue(ServeurInventaire.serveurDemande("connecte-toi en ssh"))
        assertTrue(ServeurInventaire.serveurDemande("modifie /opt/homeassistant/configuration.yaml"))
    }
}
