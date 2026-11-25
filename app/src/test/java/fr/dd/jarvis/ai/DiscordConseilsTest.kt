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
        assertTrue(rapport.contains("aucune modification n'a été effectuée"))
    }

    @Test
    fun rapportIndiqueQuandAucunPointEvidentNestDetecte() {
        val rapport = DiscordConseils.analyser("Demo", "guild1", JSONArray(), JSONArray())
        assertTrue(rapport.contains("Aucun point évident détecté"))
    }


    @Test
    fun signaleCategoriesPresDeLaLimiteEtRolesDupliques() {
        val salons = JSONArray().put(JSONObject().put("id", "cat").put("name", "Communauté").put("type", 4))
        repeat(45) { i -> salons.put(JSONObject().put("id", "c$i").put("name", "salon-$i").put("type", 0).put("parent_id", "cat")) }
        val roles = JSONArray()
            .put(JSONObject().put("id", "guild1").put("name", "@everyone").put("permissions", "0"))
            .put(JSONObject().put("id", "r1").put("name", "Modérateur").put("permissions", "0"))
            .put(JSONObject().put("id", "r2").put("name", "Modérateur").put("permissions", "0"))
        val rapport = DiscordConseils.analyser("Demo", "guild1", salons, roles)
        assertTrue(rapport.contains("proche de la limite Discord de 50 salons"))
        assertTrue(rapport.contains("Noms de rôles identiques ou proches"))
    }
}