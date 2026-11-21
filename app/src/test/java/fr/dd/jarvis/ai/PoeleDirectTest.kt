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

    private fun climat(id: String, nom: String, etat: String, mesure: Double?, consigne: Double?, action: String?) = JSONObject()
        .put("entity_id", id).put("state", etat).put("attributes", JSONObject().put("friendly_name", nom).apply {
            mesure?.let { put("current_temperature", it) }; consigne?.let { put("temperature", it) }; action?.let { put("hvac_action", it) }
        })

    @Test fun lectureDesTemperatures() {
        assertNotNull(PoeleDirect.lecture("Quelle est la température de mes poêles ?"))
        assertEquals(listOf("etage"), PoeleDirect.lecture("la température du poêle de l'étage")!!.lieu)
        assertNotNull(PoeleDirect.lecture("est-ce que le poêle du salon est allumé ?"))
        assertNull(PoeleDirect.lecture("mets le poêle à 21 degrés"))
        assertNull(PoeleDirect.lecture("rallume mon poêle à l'étage"))
        assertNull(PoeleDirect.lecture("quelle température fait-il dehors ?"))
        assertNull(PoeleDirect.demande("Quelle est la température de mes poêles ?"))
        assertNull(CommandesDirectes.intention("Quelle est la température de mes poêles ?"))

        val etats = listOf(
            climat("climate.poele_etage", "Poêle étage", "heat", 21.5, 22.0, "heating"),
            climat("climate.poele_salon", "Poêle salon", "off", 19.0, 20.0, "off"),
            JSONObject().put("entity_id", "switch.poele_etage_mode_nuit").put("state", "off").put("attributes", JSONObject().put("friendly_name", "Poêle étage Mode nuit")),
        )
        val tout = PoeleDirect.lire(etats, PoeleDirect.lecture("Quelle est la température de mes poêles ?")!!)!!
        assertTrue(tout, tout.contains("| Poêle | Température actuelle | Consigne | État |"))
        assertTrue(tout, tout.contains("| Poêle étage | 21,5 °C | 22 °C | en chauffe |"))
        assertTrue(tout, tout.contains("| Poêle salon | 19 °C | 20 °C | éteint |"))
        // Entités sans le mot « poêle » (climate.etage, climate.salon) : « mes poêles » = tous les chauffages.
        val anonymes = listOf(climat("climate.etage", "Étage", "heat", 21.0, 22.0, "heating").apply { getJSONObject("attributes").put("hvac_modes", org.json.JSONArray(listOf("off", "heat"))) },
            climat("climate.rdc", "Rez-de-chaussée", "off", 18.0, 20.0, "off"))
        val t2 = PoeleDirect.lire(anonymes, PoeleDirect.lecture("Quelle est la température de mes poêles ?")!!)!!
        assertTrue(t2, t2.contains("| Étage | 21 °C | 22 °C | en chauffe |") && t2.contains("| Rez-de-chaussée | 18 °C | 20 °C | éteint |"))
        assertTrue(tout, !tout.contains("Mode nuit"))
        val haut = PoeleDirect.lire(etats, PoeleDirect.lecture("la température du poêle de l'étage ?")!!)!!
        assertTrue(haut, haut.contains("étage") && !haut.contains("salon"))
    }

    @Test fun listeSeulementSiDemandee() {
        assertTrue(!Brain.listeHaDemandee("Quelle est la température de mes poêles ?"))
        assertTrue(Brain.listeHaDemandee("liste les entités du poêle"))
        assertTrue(Brain.listeHaDemandee("quelles sont les entités indisponibles"))
        assertTrue(Serveur.valeursClimat(climat("climate.poele_etage", "Poêle étage", "heat", 21.5, 22.0, "heating")).contains("mesurée 21,5 °C"))
    }

    @Test fun lectureAvecPresentationEtSuiteLesDeux() {
        assertEquals(emptyList<String>(), PoeleDirect.lecture("Donne-moi la température de mes poêles sous forme de tableau avec la température actuelle et la température de consigne")?.lieu)
        assertEquals(emptyList<String>(), PoeleDirect.lecture("Présente-moi la température des deux poêles")?.lieu)
        val h = listOf(ChatMsg(true, "quelle est la température du poêle"), ChatMsg(false, "Quel poêle veux-tu : étage ou salon ?"), ChatMsg(true, "les deux"))
        assertEquals("quelle est la température du poêle", PoeleDirect.suiteTous("les deux", h))
        assertNull(PoeleDirect.suiteTous("les deux", listOf(ChatMsg(true, "mets la musique"), ChatMsg(false, "Laquelle ?"), ChatMsg(true, "les deux"))))
        assertNull(PoeleDirect.suiteTous("allume la lumière", h))
    }
}
