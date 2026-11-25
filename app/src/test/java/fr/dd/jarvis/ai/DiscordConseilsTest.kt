package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordConseilsTest {
    @Test
    fun detecteCategoriesVidesSalonsOrphelinsEtRolesTropPuissants() {
        val salons = JSONArray()
            .put(JSONObject().put("id", "cat1").put("name", "Archives").put("type", 4).put("position", 0))
            .put(JSONObject().put("id", "chan1").put("name", "general").put("type", 0).put("position", 0))
        val roles = JSONArray()
            .put(JSONObject().put("id", "guild1").put("name", "@everyone").put("permissions", "0"))
            .put(JSONObject().put("id", "role1").put("name", "SuperAdmin").put("permissions", Discord.Perm.ADMINISTRATOR.toString()))
        val rapport = DiscordConseils.analyser("Demo", "guild1", salons, roles)
        assertTrue(rapport.contains("Catégorie vide"))
        assertTrue(rapport.contains("hors catégorie"))
        assertTrue(rapport.contains("permission Administrateur"))
        assertTrue(rapport.contains("discord_salons_reorganiser"))
        assertTrue(rapport.contains("Aucune modification n'a été effectuée"))
    }

    @Test
    fun rapportIndiqueQuandAucunPointEvidentNestDetecte() {
        val rapport = DiscordConseils.analyser("Demo", "guild1", JSONArray(), JSONArray())
        assertTrue(rapport.contains("Aucun point évident détecté"))
    }
}
