package fr.dd.jarvis.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordToolsCapabilitiesTest {
    @Test
    fun outilsAvancesSontEnregistres() {
        assertTrue(DiscordTools.NAMES.containsAll(setOf(
            "discord_serveur_info",
            "discord_message_modifier",
            "discord_message_supprimer",
            "discord_message_epingler",
            "discord_reaction",
            "discord_fil_creer",
            "discord_membre_info",
            "discord_membre_timeout",
            "discord_membre_expulser",
            "discord_membre_bannir",
            "discord_membre_pseudo"
        )))
    }

    @Test
    fun demandesDiscordEtModerationSontRoutees() {
        assertTrue(DiscordTools.concerned("Épingle ce message Discord"))
        assertTrue(DiscordTools.concerned("Mets un timeout à ce membre sur Discord"))
        assertTrue(DiscordTools.concerned("Crée un fil Discord à partir de ce message"))
    }
}
