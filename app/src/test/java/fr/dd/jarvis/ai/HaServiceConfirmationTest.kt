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
        assertFalse(Serveur.confirmeService("cover", "close_cover", JSONObject(), etat("closing")))
        assertTrue(Serveur.confirmeService("cover", "close_cover", JSONObject(), etat("closed")))
        assertFalse(Serveur.confirmeService("cover", "open_cover", JSONObject(), etat("opening")))
        assertFalse(Serveur.confirmeService("lock", "lock", JSONObject(), etat("locking")))
        assertFalse(Serveur.confirmeService("lock", "unlock", JSONObject(), etat("unlocking")))
        assertTrue(Serveur.serviceEnTransition("close_cover", "closing"))
        assertFalse(Serveur.serviceEnTransition("close_cover", "closed"))
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
        val enCours = Serveur.compteRenduService("cover", "close_cover", "cover.volet", JSONObject(), etat("closing"))
        assertTrue(enCours.startsWith("⏳"))
        assertTrue(enCours.contains("pas encore terminée"))
    }
    @Test fun actionsSansEtatDeConfirmationNeSontPasDeclareesReussies() {
        assertTrue(Serveur.verificationServiceConnue("turn_on", JSONObject()))
        assertTrue(Serveur.verificationServiceConnue("volume_set", JSONObject().put("volume_level", 0.35)))
        assertFalse(Serveur.verificationServiceConnue("media_play_pause", JSONObject()))
        assertFalse(Serveur.confirmeService("media_player", "media_play_pause", JSONObject(), etat("playing")))
        val retour = Serveur.compteRenduService(
            "media_player", "media_play_pause", "media_player.salon", JSONObject(),
            etat("playing", JSONObject().put("friendly_name", "Salon"))
        )
        assertTrue(retour.startsWith("⚠️"))
        assertTrue(retour.contains("pas vérifiable automatiquement"))
    }

    @Test fun etatInconnuNePeutPasEtrePrisPourUneReussite() {
        val retour = Serveur.compteRenduService("light", "turn_on", "light.salon", JSONObject(), etat("unknown"))
        assertTrue(retour.startsWith("⚠️"))
        assertTrue(retour.contains("INCONNU"))
    }

}
