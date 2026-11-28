package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaScriptsTest {
    /** Identifiant de script valide, quelle que soit la façon dont l'IA nomme le script. */
    @Test fun identifiantValide() {
        assertEquals("allumer_la_tele", HaScripts.slug("Allumer la télé !"))
        assertEquals("tv_l_ecran_du_salon", HaScripts.slug("TV — l'écran du salon"))
        assertTrue(HaScripts.versionAuMoins("2025.11.3", 2024, 8))
        assertFalse(HaScripts.versionAuMoins("2024.7.4", 2024, 8))
        assertTrue(HaScripts.versionAuMoins("", 2024, 8))
    }

    /** Script : « actions » devient « sequence » (sinon refusé par Home Assistant) ; ancienne syntaxe pour un vieux HA. */
    @Test fun scriptAuBonFormat() {
        val ia = JSONObject("""{"alias":"Allumer la télé","triggers":[],"actions":[{"action":"media_player.turn_on","target":{"entity_id":"media_player.tv"}}]}""")
        val recent = HaScripts.normaliserScript(ia, "2025.11.0")
        assertEquals(1, recent.getJSONArray("sequence").length())
        assertFalse(recent.has("actions") || recent.has("triggers"))
        assertEquals("media_player.turn_on", recent.getJSONArray("sequence").getJSONObject(0).getString("action"))
        assertEquals("single", recent.getString("mode"))
        val vieux = HaScripts.normaliserScript(ia, "2024.6.2")
        assertEquals("media_player.turn_on", vieux.getJSONArray("sequence").getJSONObject(0).getString("service"))
        // Une seule étape donnée comme objet : mise en liste.
        val seul = HaScripts.normaliserScript(JSONObject("""{"alias":"x","sequence":{"action":"light.turn_on"}}"""), "2025.1.0")
        assertTrue(seul.get("sequence") is JSONArray)
    }

    /** Automatisation : clés au pluriel (2024.10+) ou au singulier avant ; « trigger: » → « platform: » sur un vieux HA. */
    @Test fun automatisationAuBonFormat() {
        val ia = JSONObject("""{"alias":"Matin","triggers":[{"trigger":"time","at":"07:00:00"}],"actions":[{"action":"light.turn_on","target":{"entity_id":"light.salon"}}]}""")
        val recent = HaScripts.normaliserAutomatisation(ia, "2025.2.0")
        assertTrue(recent.has("triggers") && recent.has("actions"))
        val vieux = HaScripts.normaliserAutomatisation(ia, "2024.5.0")
        assertTrue(vieux.has("trigger") && vieux.has("action") && !vieux.has("triggers"))
        assertEquals("time", vieux.getJSONArray("trigger").getJSONObject(0).getString("platform"))
        assertEquals("light.turn_on", vieux.getJSONArray("action").getJSONObject(0).getString("service"))
        assertEquals(setOf("light.salon"), HaScripts.entitesCitees(recent.toString()))
    }

    /** Allumer une télé : la méthode dépend de l'intégration et de ce que l'appareil sait faire. */
    @Test fun methodesAllumageSelonLaTele() {
        val lgSansWol = HaScripts.methodesAllumage("media_player.lg", "webostv", 0, emptyList(), setOf("media_player", "webostv"), null)
        assertNull(lgSansWol.first().etape)
        assertTrue(lgSansWol.first().note.contains("wake_on_lan"))
        val lg = HaScripts.methodesAllumage("media_player.lg", "webostv", 0, emptyList(), setOf("wake_on_lan"), "aa:bb:cc:dd:ee:ff")
        assertEquals("wake_on_lan.send_magic_packet", lg.first().etape!!.getString("action"))
        assertEquals("aa:bb:cc:dd:ee:ff", lg.first().etape!!.getJSONObject("data").getString("mac"))
        val androidTv = HaScripts.methodesAllumage("media_player.tv", "androidtv_remote", 128, listOf("remote.tv"), setOf("remote"), null)
        assertEquals("remote.turn_on", androidTv.first().etape!!.getString("action"))
        assertTrue(androidTv.any { it.etape?.optString("action") == "media_player.turn_on" })
        val muet = HaScripts.methodesAllumage("media_player.x", "dlna_dmr", 0, emptyList(), emptySet(), null)
        assertTrue(muet.single().titre.startsWith("Aucune"))
        assertTrue("allumer" in HaScripts.fonctionsMedia(128 or 256 or 4))
        assertFalse("allumer" in HaScripts.fonctionsMedia(256))
    }

    /** Plex : pause / lecture / stop / suivant vérifiés par les sessions du serveur. */
    @Test fun commandesPlexVerifiees() {
        fun s(etat: String, cle: String = "1") = JSONObject().put("ratingKey", cle).put("Player", JSONObject().put("state", etat))
        assertEquals(true, Plex.commandeAppliquee("pause", s("playing"), s("paused")))
        assertEquals(false, Plex.commandeAppliquee("pause", s("playing"), s("playing")))
        assertEquals(true, Plex.commandeAppliquee("stop", s("playing"), null))
        assertEquals(true, Plex.commandeAppliquee("suivant", s("playing", "1"), s("playing", "2")))
        assertNull(Plex.commandeAppliquee("volume", s("playing"), s("playing")))
        assertEquals("en pause", Plex.etatLecture(s("paused")))
    }
}
