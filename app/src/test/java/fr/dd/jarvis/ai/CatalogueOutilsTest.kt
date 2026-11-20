package fr.dd.jarvis.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chaque outil déclaré dans le schéma d'une famille doit être routé par ToolBox (ensemble NAMES) : sinon le modèle
 * l'appelle et JARVIS répond « Outil inconnu » (c'était le cas de bulletins et documents_supprimer).
 */
class CatalogueOutilsTest {
    private fun source(chemin: String): String {
        val f = listOf(File("src/main/java/fr/dd/jarvis/$chemin"), File("app/src/main/java/fr/dd/jarvis/$chemin")).first { it.exists() }
        return f.readText()
    }

    private fun declares(chemin: String): Set<String> {
        val s = source(chemin)
        return (Regex("""\btool\("([a-z_0-9]+)"""").findAll(s) + Regex("""put\("name", "([a-z_0-9]+)"\)""").findAll(s)).map { it.groupValues[1] }.toSet()
    }

    private fun verifier(chemin: String, routes: Set<String>) {
        val manquants = declares(chemin) - routes
        assertTrue("$chemin : outils déclarés mais non routés : $manquants", manquants.isEmpty())
    }

    @Test fun toutesLesFamillesSontRoutees() {
        verifier("societe/SocieteTools.kt", fr.dd.jarvis.societe.SocieteTools.NAMES)
        verifier("ai/ServeurTools.kt", ServeurTools.NAMES)
        verifier("ai/DiscordTools.kt", DiscordTools.NAMES)
        verifier("ai/PlexTools.kt", PlexTools.NAMES)
        verifier("ai/TikTokTools.kt", TikTokTools.NAMES)
        verifier("ai/MusicTools.kt", MusicTools.NAMES)
        verifier("ai/SitesWeb.kt", SitesWebTools.NAMES)
    }

    @Test fun lesOutilsDeBaseOntUneBrancheDexecution() {
        val s = source("ai/ToolBox.kt")
        val base = Regex("""put\(tool\("([a-z_0-9]+)"""").findAll(s).map { it.groupValues[1] }.toSet()
        val branches = Regex(""""([a-z_0-9]+)"(?:\s*,\s*"[a-z_0-9]+")*\s*->""").findAll(s).flatMap { m -> Regex(""""([a-z_0-9]+)"""").findAll(m.value).map { it.groupValues[1] } }.toSet()
        val manquants = base - branches
        assertTrue("Outils de base sans branche d'exécution : $manquants", manquants.isEmpty())
    }
}
