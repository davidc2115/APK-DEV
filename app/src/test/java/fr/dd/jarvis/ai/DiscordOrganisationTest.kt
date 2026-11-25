package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordOrganisationTest {
    private fun c(id: String, nom: String, type: Int, pos: Int, parent: String? = null) =
        JSONObject().put("id", id).put("name", nom).put("type", type).put("position", pos).apply { if (parent != null) put("parent_id", parent) }

    // Accueil (règles, bienvenue) · Loisirs (musique, jeux, vocal) · Divers (vrac)
    private val tous = listOf(
        c("100", "Accueil", 4, 0), c("200", "Loisirs", 4, 1), c("300", "Divers", 4, 2),
        c("1", "règles", 0, 0, "100"), c("2", "bienvenue", 0, 1, "100"),
        c("3", "musique", 0, 0, "200"), c("4", "jeux", 0, 1, "200"), c("5", "vocal", 2, 2, "200"),
        c("6", "vrac", 0, 0, "300"))

    private fun pos(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("id") }

    @Test fun deplacerVersUneAutreCategorie() {
        val a = pos(DiscordOrganisation.deplacement(tous, "3", "100", DiscordOrganisation.Ancre.Apres("1"), synchroniser = true))
        assertEquals(listOf("1", "3", "2"), a.values.sortedBy { it.getInt("position") }.map { it.getString("id") })
        assertEquals("100", a.getValue("3").getString("parent_id"))
        assertTrue(a.getValue("3").getBoolean("lock_permissions"))
        assertFalse(a.getValue("1").has("parent_id"))   // les voisins ne changent que de position
    }

    @Test fun sortirDeToutesCategoriesEtCategorieEnHaut() {
        val a = pos(DiscordOrganisation.deplacement(tous, "6", null, DiscordOrganisation.Ancre.Bas))
        assertTrue(a.getValue("6").isNull("parent_id"))
        val b = DiscordOrganisation.deplacement(tous, "300", null, DiscordOrganisation.Ancre.Haut)
        assertEquals(listOf("300", "100", "200"), (0 until b.length()).map { b.getJSONObject(it) }.sortedBy { it.getInt("position") }.map { it.getString("id") })
        assertFalse(b.getJSONObject(0).has("parent_id"))
    }

    @Test fun planLu() {
        val p = DiscordOrganisation.plan("📁 Loisirs : jeux, #musique\nAccueil: bienvenue, règles\nSans catégorie : vrac\n- Divers")
        assertEquals(DiscordOrganisation.Ligne("Loisirs", listOf("jeux", "musique")), p[0])
        assertEquals(DiscordOrganisation.Ligne("Accueil", listOf("bienvenue", "règles")), p[1])
        assertEquals(DiscordOrganisation.Ligne(null, listOf("vrac")), p[2])
        assertEquals(DiscordOrganisation.Ligne("Divers", emptyList()), p[3])
    }

    @Test fun reorganisationComplete() {
        // Loisirs en premier (jeux puis musique ; vocal reste après), Accueil reçoit « vrac » en tête.
        val a = DiscordOrganisation.reorganisation(tous, listOf("200" to listOf("4", "3"), "100" to listOf("6")))
        val p = pos(a)
        assertEquals(0, p.getValue("200").getInt("position")); assertEquals(1, p.getValue("100").getInt("position")); assertEquals(2, p.getValue("300").getInt("position"))
        assertEquals(listOf("4", "3", "5"), listOf("4", "3", "5").sortedBy { p.getValue(it).getInt("position") })
        assertEquals("100", p.getValue("6").getString("parent_id"))
        assertFalse(p.getValue("4").has("parent_id"))    // déjà dans Loisirs : pas de changement de catégorie
        assertEquals(0, p.getValue("6").getInt("position")); assertEquals(1, p.getValue("1").getInt("position"))
        val apercu = DiscordOrganisation.apercu(tous, a)
        assertTrue(apercu, apercu.startsWith("📁 Loisirs : #jeux, #musique, #vocal\n📁 Accueil : #vrac, #règles, #bienvenue\n📁 Divers : (vide)"))
    }

    @Test fun types() {
        assertEquals(0, DiscordOrganisation.type("")); assertEquals(2, DiscordOrganisation.type("vocal")); assertEquals(4, DiscordOrganisation.type("Catégorie"))
        assertEquals(15, DiscordOrganisation.type("forum")); assertEquals(null, DiscordOrganisation.type("frigo"))
    }

    @Test fun ajouterUnDroitGardeLesAutres() {
        val actuel = Discord.Perm.VIEW_CHANNEL or Discord.Perm.SEND_MESSAGES
        val r = (actuel or Discord.Perm.parse("gérer les messages")) and Discord.Perm.parse("envoyer des messages").inv()
        assertEquals(Discord.Perm.VIEW_CHANNEL or Discord.Perm.MANAGE_MESSAGES, r)
    }


    @Test fun permissionsDeRoleInconnuesSontRefusees() {
        assertEquals(Discord.Perm.VIEW_CHANNEL or Discord.Perm.SEND_MESSAGES,
            Discord.Perm.parseStrict("voir le salon, envoyer des messages"))
        assertEquals(Discord.Perm.TOUT_SAUF_ADMINISTRATEUR,
            Discord.Perm.parseStrict("toutes_sauf_administrateur"))
        assertTrue(runCatching { Discord.Perm.parseStrict("permission_inconnue") }.isFailure)
    }
}