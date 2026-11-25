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
        assertEquals(null, Autorisations.cle("discord_message_modifier", "Modifier le message ?"))
        assertEquals(null, Autorisations.cle("discord_message_epingler", "Épingler le message ?"))
        assertEquals(null, Autorisations.cle("discord_fil_creer", "Créer un fil ?"))
        assertEquals(null, Autorisations.cle("discord_fil_gerer", "Supprimer ce fil ?"))
        assertEquals(true, Autorisations.discordCritique("discord_membre_bannir", "Bannir ce membre ?"))
    }

    @Test fun toutesLesActionsDiscordDeGestionExigentUneConfirmationFraiche() {
        val actionsSensibles = listOf(
            "discord_membre_timeout", "discord_membre_expulser", "discord_membre_bannir", "discord_membre_pseudo",
            "discord_role_creer", "discord_role_modifier", "discord_role_supprimer", "discord_role_position", "discord_role_attribuer",
            "discord_salon_permission", "discord_salon_creer", "discord_salon_modifier", "discord_salon_supprimer",
            "discord_salons_reorganiser", "discord_serveur_modifier", "discord_messages_purger",
            "discord_message_modifier", "discord_message_supprimer", "discord_message_epingler",
            "discord_fil_creer", "discord_fil_gerer", "discord_invitations"
        )
        actionsSensibles.forEach { outil ->
            assertEquals("Une confirmation fraîche est requise pour $outil", null,
                Autorisations.cle(outil, "Confirmer l'action Discord ?"))
            assertEquals("La commande précise ne doit pas être mémorisable pour $outil", false,
                Autorisations.discordCritique(outil, "Confirmer l'action Discord ?").not())
        }
    }

    @Test fun messageDiscordOrdinairePeutGarderSonAutorisation() {
        assertNotEquals(null, Autorisations.cle("discord_message_envoyer", "Envoyer ce message dans le salon ?"))
        assertEquals(false, Autorisations.discordCritique("discord_message_envoyer", "Envoyer ce message ?"))
    }
}