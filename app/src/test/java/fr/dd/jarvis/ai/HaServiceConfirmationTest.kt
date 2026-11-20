package fr.dd.jarvis.ai

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaServiceConfirmationTest {
    private fun etat(s: String, attrs: JSONObject = JSONObject()) = JSONObject().put("entity_id", "x.y").put("state", s).put("attributes", attrs)

    @Test fun allumageConfirmeOuNon() {
        assertTrue(Serveur.confirmeService("light", "turn_on", JSONObject(), etat("on")))
        assertFalse(Serveur.confirmeService("light", "turn_on", JSONObject(), etat("off")))
        assertFalse(Serveur.confirmeService("light", "turn_on", JSONObject(), etat("unavailable")))
        assertTrue(Serveur.confirmeService("media_player", "turn_off", JSONObject(), etat("standby")))
        assertTrue(Serveur.confirmeService("cover", "close_cover", JSONObject(), etat("closing")))
    }

    @Test fun consigneEtVolume() {
        val d = JSONObject().put("temperature", 20)
        assertTrue(Serveur.confirmeService("climate", "set_temperature", d, etat("heat", JSONObject().put("temperature", 20.0))))
        assertFalse(Serveur.confirmeService("climate", "set_temperature", d, etat("heat", JSONObject().put("temperature", 18.0))))
        val v = JSONObject().put("volume_level", 0.35)
        assertTrue(Serveur.confirmeService("media_player", "volume_set", v, etat("on", JSONObject().put("volume_level", 0.35))))
    }

    @Test fun compteRenduHonnete() {
        val ok = Serveur.compteRenduService("light", "turn_on", "light.salon", JSONObject(), etat("on", JSONObject().put("friendly_name", "Salon")))
        assertTrue(ok.startsWith("✅") && ok.contains("Salon"))
        val ko = Serveur.compteRenduService("light", "turn_on", "light.salon", JSONObject(), etat("off"))
        assertTrue(ko.startsWith("⚠️") && ko.contains("Ne dis PAS"))
        assertTrue(Serveur.compteRenduService("switch", "turn_on", "switch.x", JSONObject(), etat("unavailable")).contains("INDISPONIBLE"))
    }
}
