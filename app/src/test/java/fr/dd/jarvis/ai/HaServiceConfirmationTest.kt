package fr.dd.jarvis.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        val muteOff = JSONObject().put("is_volume_muted", false)
        assertFalse(Serveur.confirmeService("media_player", "volume_mute", muteOff, etat("on")))
        assertTrue(Serveur.confirmeService("media_player", "volume_mute", muteOff, etat("on", JSONObject().put("is_volume_muted", false))))
        val preset = JSONObject().put("preset_mode", "eco")
        assertFalse(Serveur.confirmeService("climate", "set_preset_mode", preset, etat("heat")))
        assertTrue(Serveur.confirmeService("climate", "set_preset_mode", preset, etat("heat", JSONObject().put("preset_mode", "eco"))))
    }

    @Test fun appelSansEntiteConfirmeSeulementLAcceptationDeHomeAssistant() {
        val r = Serveur.compteRenduSansEntite("tts", "speak")
        assertTrue(r.contains("accepté par Home Assistant"))
        assertTrue(r.contains("aucun état cible à relire"))
        assertTrue(r.contains("n'est pas confirmé"))
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


    /** Appareils lents : délai plus long, suivi en arrière-plan et message « en cours de vérification » (pas « pas suivi »). */
    @Test fun appareilLentSuiviSansConclureTropTot() {
        assertTrue(Serveur.budgetMs("climate") > Serveur.budgetMs("light"))
        assertTrue(Serveur.suiviLong("climate") && Serveur.suiviLong("media_player") && Serveur.suiviLong("cover"))
        assertFalse(Serveur.suiviLong("light"))
        val d = JSONObject().put("hvac_mode", "off")
        val poele = etat("heat", JSONObject().put("friendly_name", "poele etage").put("temperature", 20.0).put("current_temperature", 18.5))
        val r = Serveur.compteRenduService("climate", "set_hvac_mode", "climate.poele_etage", d, poele, suivi = true, dureeMs = 8_000)
        assertTrue(r, r.startsWith("⏳") && r.contains("notification") && !r.contains("Ne dis PAS"))
        val sansSuivi = Serveur.compteRenduService("climate", "set_hvac_mode", "climate.poele_etage", d, poele, dureeMs = 8_000)
        assertTrue(sansSuivi, sansSuivi.contains("après 8 s") && sansSuivi.contains("relecture"))
        assertEquals("1 min 30", SuiviHa.duree(90_000)); assertEquals("4 min", SuiviHa.duree(240_000)); assertEquals("45 s", SuiviHa.duree(45_000))
    }

    /** Lecture, pause, volume +/−, piste suivante, scène, script : confirmés par le changement d'état. */
    @Test fun confirmationParChangement() {
        val lecture = etat("paused", JSONObject().put("volume_level", 0.30).put("media_content_id", "a"))
        assertTrue(Serveur.verificationServiceConnue("media_play_pause", JSONObject(), "media_player", lecture))
        assertTrue(Serveur.confirmeService("media_player", "media_play_pause", JSONObject(), etat("playing"), lecture))
        assertFalse(Serveur.confirmeService("media_player", "media_play_pause", JSONObject(), etat("paused"), lecture))
        assertTrue(Serveur.confirmeService("media_player", "volume_up", JSONObject(), etat("on", JSONObject().put("volume_level", 0.35)), lecture))
        assertFalse(Serveur.confirmeService("media_player", "volume_up", JSONObject(), etat("on", JSONObject().put("volume_level", 0.30)), lecture))
        assertTrue(Serveur.confirmeService("media_player", "media_next_track", JSONObject(), etat("playing", JSONObject().put("media_content_id", "b")), lecture))
        assertTrue(Serveur.confirmeService("media_player", "play_media", JSONObject(), etat("playing")))
        assertTrue(Serveur.confirmeService("media_player", "media_pause", JSONObject(), etat("paused")))
        val scene = etat("2026-11-28T10:00:00+00:00")
        assertTrue(Serveur.confirmeService("scene", "turn_on", JSONObject(), etat("2026-11-28T13:05:00+00:00"), scene))
        assertFalse(Serveur.confirmeService("scene", "turn_on", JSONObject(), scene, scene))
        assertTrue(Serveur.confirmeService("script", "turn_on", JSONObject(), etat("on")))
        assertTrue(Serveur.confirmeService("cover", "set_cover_position", JSONObject().put("position", 50), etat("open", JSONObject().put("current_position", 51))))
    }
}
