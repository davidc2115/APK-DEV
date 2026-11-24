package fr.dd.jarvis.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AutorisationsCommandeTest {
    @Test fun memeCommandeMemeEmpreinte() {
        val a = Autorisations.canonique(JSONObject().put("action", "supprimer_appareil").put("filtre", "sans_nom"))
        val b = Autorisations.canonique(JSONObject().put("filtre", "sans_nom ").put("action", "supprimer_appareil"))
        assertEquals(a, b)
        assertEquals(Autorisations.empreinte("freebox", a), Autorisations.empreinte("freebox", b))
        val c = Autorisations.canonique(JSONObject().put("action", "supprimer_appareil").put("filtre", "hors_ligne"))
        assertNotEquals(Autorisations.empreinte("freebox", a), Autorisations.empreinte("freebox", c))
        assertNotEquals(Autorisations.empreinte("freebox", a), Autorisations.empreinte("ssh_commande", a))
    }


    @Test fun actionsDiscordSensiblesNeSontJamaisAutoriseesEnPermanence() {
        assertEquals(null, Autorisations.cle("discord_membre_bannir", "Bannir ce membre ?"))
        assertEquals(null, Autorisations.cle("discord_membre_expulser", "Expulser ce membre ?"))
        assertEquals(null, Autorisations.cle("discord_membre_pseudo", "Changer le pseudo de ce membre ?"))
        assertEquals(null, Autorisations.cle("discord_invitations", "Créer une invitation Discord ?"))
        assertEquals(null, Autorisations.cle("discord_salon_permission", "Modifier les permissions de ce salon ?"))
        assertEquals(null, Autorisations.cle("discord_role_attribuer", "Donner ce rôle à ce membre ?"))
        assertEquals(null, Autorisations.cle("discord_messages_purger", "Purger les messages ?"))
        assertEquals(null, Autorisations.cle("discord_fil_gerer", "Supprimer ce fil ?"))
        assertEquals(true, Autorisations.discordCritique("discord_membre_bannir", "Bannir ce membre ?"))
    }

    @Test fun messageDiscordOrdinairePeutGarderSonAutorisation() {
        assertNotEquals(null, Autorisations.cle("discord_message_envoyer", "Envoyer ce message dans le salon ?"))
        assertEquals(false, Autorisations.discordCritique("discord_message_envoyer", "Envoyer ce message ?"))
    }
}