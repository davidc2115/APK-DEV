package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoeleDirectTest {
    private val etats = listOf(
        "climate.poele_etage" to "Poêle étage", "climate.poele_salon" to "Poêle salon",
        "switch.poele_etage_mode_nuit" to "Poêle étage Mode nuit", "switch.poele_etage_silence" to "Poêle étage Silence",
        "switch.poele_etage_natural_mode" to "Poêle étage Natural mode", "light.salon" to "Salon")

    @Test fun demandes() {
        PoeleDirect.demande("Rallume mon poêle à l'étage")!!.let { assertTrue(it.allumer); assertEquals(listOf("etage"), it.lieu) }
        PoeleDirect.demande("éteins le poêle du salon")!!.let { assertTrue(!it.allumer); assertEquals(listOf("salon"), it.lieu) }
        assertNotNull(PoeleDirect.demande("rallume le poil de l'étage"))
        assertNull(PoeleDirect.demande("mets le poêle de l'étage à 21 degrés"))
        assertNull(PoeleDirect.demande("passe le poêle en mode nuit"))
        assertNull(PoeleDirect.demande("allume la lumière du salon"))
    }

    @Test fun visePoeleJamaisUnMode() {
        assertEquals("climate.poele_etage", PoeleDirect.choisir(etats, PoeleDirect.demande("rallume mon poêle à l'étage")!!))
        assertEquals("climate.poele_salon", PoeleDirect.choisir(etats, PoeleDirect.demande("éteins le poêle du salon")!!))
        // Deux poêles, aucun lieu : ambigu → le cerveau demande.
        assertNull(PoeleDirect.choisir(etats, PoeleDirect.demande("rallume le poêle")!!))
        // Note de l'utilisateur pointant vers un interrupteur de mode : ignorée.
        assertEquals("climate.poele_etage", PoeleDirect.choisir(etats, PoeleDirect.demande("rallume mon poêle à l'étage")!!, listOf("switch.poele_etage_mode_nuit", "climate.poele_etage")))
    }

    @Test fun serviceSelonLesModes() {
        val a = JSONObject().put("hvac_modes", JSONArray(listOf("off", "heat")))
        assertEquals("set_hvac_mode", PoeleDirect.service(true, a).first)
        assertEquals("heat", PoeleDirect.service(true, a).second!!.getString("hvac_mode"))
        assertEquals("off", PoeleDirect.service(false, a).second!!.getString("hvac_mode"))
        assertEquals("turn_on", PoeleDirect.service(true, null).first)
    }

    @Test fun gardeContreLesModesNonDemandes() {
        val nuit = JSONObject().put("domaine", "switch").put("service", "turn_on").put("entity_id", "switch.poele_etage_mode_nuit")
        assertNotNull(PoeleDirect.modeNonDemande(nuit, "rallume mon poêle à l'étage"))
        assertNull(PoeleDirect.modeNonDemande(nuit, "active le mode nuit du poêle de l'étage"))
        val climat = JSONObject().put("domaine", "climate").put("service", "set_hvac_mode").put("entity_id", "climate.poele_etage")
        assertNull(PoeleDirect.modeNonDemande(climat, "rallume mon poêle à l'étage"))
        val prise = JSONObject().put("domaine", "switch").put("service", "turn_on").put("entity_id", "switch.prise_sapin")
        assertNull(PoeleDirect.modeNonDemande(prise, "allume le sapin"))
    }

    @Test fun pasDeConfusionAvecLeTelephoneNiLesModesJarvis() {
        assertNull(fr.dd.jarvis.device.ModeSonore.intention("mets le poêle en mode silence"))
        assertEquals(fr.dd.jarvis.device.ModeSonore.NPD, fr.dd.jarvis.device.ModeSonore.intention("mets le téléphone en mode silence"))
        val modes = listOf(fr.dd.jarvis.securite.Systemes.Systeme("1", "Mode nuit"))
        assertNull(fr.dd.jarvis.securite.Systemes.parPhrase("mets le poêle en mode nuit", modes))
        assertNotNull(fr.dd.jarvis.securite.Systemes.parPhrase("active le mode nuit", modes))
        assertNull(CommandesDirectes.intention("rallume mon poêle à l'étage"))
        assertTrue(PoeleDirect.visePasLeTelephone("mets le poêle de l'étage en mode silence"))
        assertTrue(!PoeleDirect.visePasLeTelephone("mets le téléphone en silencieux"))
        assertTrue(!PoeleDirect.visePasLeTelephone("active le mode ne pas déranger"))
    }
}
