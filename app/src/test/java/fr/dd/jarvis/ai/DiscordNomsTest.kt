package fr.dd.jarvis.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DiscordNomsTest {
    private fun g(id: String, nom: String) = JSONObject().put("id", id).put("name", nom)
    private val guildes = listOf(g("1", "Brigade & Gang 🎮"), g("2", "Famille Cortot"), g("3", "Tests du bot"))

    @Test fun parNomEtSigle() {
        assertEquals("1", Discord.choisir(guildes, "Brigade et Gang", emptyList())?.getString("id"))
        assertEquals("1", Discord.choisir(guildes, "B&G", emptyList())?.getString("id"))
        assertEquals("1", Discord.choisir(guildes, "bag", emptyList())?.getString("id"))
        assertEquals("2", Discord.choisir(guildes, "famille", emptyList())?.getString("id"))
        assertEquals("3", Discord.choisir(guildes, "3", emptyList())?.getString("id"))
        assertNull(Discord.choisir(guildes, "travail", emptyList()))
    }

    @Test fun parSurnomNote() {
        val l = listOf(g("10", "Les Copains d'abord"), g("11", "Serveur pro"))
        assertEquals("10", Discord.choisir(l, "potes", listOf("Discord : le serveur des potes c'est Les Copains d'abord")).let { it?.getString("id") })
        assertEquals("10", Discord.choisir(l, "potes", listOf("potes = Les Copains d'abord")).let { it?.getString("id") })
    }

    @Test fun salons() {
        val s = listOf(g("a", "💬・discussion"), g("b", "annonces"), g("c", "discussion-jeux"))
        assertEquals("a", Discord.choisir(s, "discussion", emptyList())?.getString("id"))
        assertEquals("c", Discord.choisir(s, "discussion jeux", emptyList())?.getString("id"))
    }

    @Test fun periodeEtSnowflake() {
        val z = ZoneId.of("Europe/Paris")
        val (d, f) = Discord.periode("hier", "", LocalDate.of(2026, 11, 14), z)!!
        assertEquals(LocalDate.of(2026, 11, 13).atStartOfDay(z).toInstant().toEpochMilli(), d)
        assertEquals(24 * 3_600_000L, f - d)
        assertNull(Discord.periode("", "", LocalDate.of(2026, 11, 14), z))
        assertEquals("175928847298985984", Discord.snowflake(1_462_015_105_796L))
    }
}

class DiscordStylesTest {
    private fun g(id: String, nom: String) = org.json.JSONObject().put("id", id).put("name", nom)

    @Test fun policesDecoratives() {
        for (n in listOf("💬┃ᴅɪsᴄᴜssɪᴏɴ", "𝐝𝐢𝐬𝐜𝐮𝐬𝐬𝐢𝐨𝐧", "ｄｉｓｃｕｓｓｉｏｎ", "🇩🇮🇸🇨🇺🇸🇸🇮🇴🇳", "dіscussіon", "・DISCUSSION・", "ⓓⓘⓢⓒⓤⓢⓢⓘⓞⓝ"))
            assertEquals(n, "discussion", DiscordNoms.cle(n))
        val salons = listOf(g("a", "📢┃ᴀɴɴᴏɴᴄᴇs"), g("b", "💬┃ᴅɪsᴄᴜssɪᴏɴ"), g("c", "🎮・jeux-vidéo"), g("d", "discussion-jeux"))
        assertEquals("b", Discord.choisir(salons, "discussion", emptyList())?.getString("id"))
        assertEquals("a", Discord.choisir(salons, "annonces", emptyList())?.getString("id"))
        assertEquals("c", Discord.choisir(salons, "jeux vidéo", emptyList())?.getString("id"))
        assertEquals("c", Discord.choisir(salons, "#jeux-video", emptyList())?.getString("id"))
        assertNull(Discord.choisir(listOf(g("x", "discussion-générale"), g("y", "discussion-jeux")), "discussion", emptyList()))
        assertEquals("x", Discord.choisir(listOf(g("x", "💬・discussion-générale"), g("y", "📁 Archives discussion vieux")), "discussion", emptyList())?.getString("id"))
    }

    @Test fun rolesEtPermissions() {
        val roles = listOf(g("1", "👑・Modérateur"), g("2", "🛡️ ᴀᴅᴍɪɴ"), g("3", "@everyone"))
        assertEquals("1", Discord.choisir(roles, "moderateur", emptyList())?.getString("id"))
        assertEquals("2", Discord.choisir(roles, "admin", emptyList())?.getString("id"))
        assertEquals("envoyer_messages", Discord.Perm.trouver("envoyer des messages"))
        assertEquals("voir_salon", Discord.Perm.trouver("Voir le salon"))
        assertEquals("gerer_roles", Discord.Perm.trouver("gérer les rôles"))
        assertEquals("historique_messages", Discord.Perm.trouver("lire l'historique"))
        assertEquals("joindre_fichiers", Discord.Perm.trouver("joindre_fichiers"))
        assertNull(Discord.Perm.trouver("faire le café"))
        assertEquals(Discord.Perm.SEND_MESSAGES or Discord.Perm.VIEW_CHANNEL, Discord.Perm.parse("envoyer des messages, voir le salon"))
        assertEquals("💬┃ᴅɪsᴄᴜssɪᴏɴ (discussion)", Discord.affiche("💬┃ᴅɪsᴄᴜssɪᴏɴ"))
        assertEquals("general", Discord.affiche("general"))
    }
}
