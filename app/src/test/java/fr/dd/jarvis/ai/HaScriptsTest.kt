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

    /** Objectifs dits naturellement. */
    @Test fun objectifsCompris() {
        assertEquals("eteindre", HaScripts.objectif("éteins le poêle").type)
        assertEquals(HaScripts.Objectif("consigne", "21"), HaScripts.objectif("consigne à 21 degrés"))
        assertEquals(HaScripts.Objectif("puissance", "3"), HaScripts.objectif("mets la puissance 3"))
        assertEquals(HaScripts.Objectif("mode", "eco"), HaScripts.objectif("mode eco"))
        assertEquals(HaScripts.Objectif("ouvrir", "50"), HaScripts.objectif("ouvre le volet à 50 %"))
        assertEquals("allumer", HaScripts.objectif("allume").type)
    }

    /** Poêle : préréglage, puissance par « fan modes », interrupteur ou sélecteur du même appareil, extinction. */
    @Test fun methodesPoele() {
        val poele = JSONObject("""{"entity_id":"climate.poele_etage","state":"heat","attributes":{"friendly_name":"Poêle étage","supported_features":401,
            "hvac_modes":["off","heat"],"preset_modes":["none","eco","comfort"],"fan_modes":["1","2","3","4","5"],"temperature":20,"min_temp":7,"max_temp":30}}""")
        val freres = mapOf(
            "switch.poele_etage_power" to JSONObject("""{"state":"on","attributes":{"friendly_name":"Poêle étage marche"}}"""),
            "select.poele_etage_mode" to JSONObject("""{"state":"Auto","attributes":{"options":["Auto","Manuel","Éco"]}}"""))
        val eco = HaScripts.methodesPour(HaScripts.objectif("mode eco"), "climate.poele_etage", poele, freres, emptySet(), null, "mcz")
        assertEquals("climate.set_preset_mode", eco.first().etape!!.getString("action"))
        assertTrue(eco.any { it.etape?.optString("action") == "select.select_option" && it.etape!!.getJSONObject("data").getString("option") == "Éco" })
        val p3 = HaScripts.methodesPour(HaScripts.objectif("puissance 3"), "climate.poele_etage", poele, freres, emptySet(), null, "mcz")
        assertEquals("climate.set_fan_mode", p3.first().etape!!.getString("action"))
        val off = HaScripts.methodesPour(HaScripts.objectif("éteindre"), "climate.poele_etage", poele, freres, emptySet(), null, "mcz")
        assertEquals(listOf("climate.turn_off", "climate.set_hvac_mode", "switch.turn_off"), off.mapNotNull { it.etape?.optString("action") })
        val consigne = HaScripts.methodesPour(HaScripts.objectif("consigne 21"), "climate.poele_etage", poele, freres, emptySet(), null, "mcz")
        assertEquals(21.0, consigne.first().etape!!.getJSONObject("data").getDouble("temperature"), 0.01)
        val inconnu = HaScripts.methodesPour(HaScripts.objectif("mode turbo"), "climate.poele_etage", poele, freres, emptySet(), null, "mcz")
        assertTrue(inconnu.single().etape == null && inconnu.single().note.contains("eco"))
        assertEquals(listOf("consigne", "préréglages", "éteindre", "allumer"), HaScripts.fonctionsClimat(401).filter { it != "modes ventilation/puissance" })
    }

    /** Navigation : plan B avec l'autre appli GPS installée. */
    @Test fun navigationAutreAppli() {
        assertEquals("Google Maps", fr.dd.jarvis.car.GuidageVoiture.autre(listOf("Waze", "Google Maps"), "Waze"))
        assertEquals("Waze", fr.dd.jarvis.car.GuidageVoiture.autre(listOf("Waze", "Google Maps"), ""))
        assertNull(fr.dd.jarvis.car.GuidageVoiture.autre(listOf("Waze"), "Waze"))
    }
}
