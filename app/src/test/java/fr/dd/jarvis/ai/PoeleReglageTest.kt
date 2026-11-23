package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoeleReglageTest {
    private fun etat(id: String, nom: String, attrs: JSONObject = JSONObject()) =
        JSONObject().put("entity_id", id).put("state", "heat").put("attributes", attrs.put("friendly_name", nom))

    @Test fun reglages() {
        PoeleDirect.reglage("règle le poil du salon à 20 degrés")!!.let { assertEquals(20.0, it.valeur!!, 0.0); assertEquals(listOf("salon"), it.lieu) }
        PoeleDirect.reglage("mets le poêle de l'étage à 21,5°")!!.let { assertEquals(21.5, it.valeur!!, 0.0); assertEquals(listOf("etage"), it.lieu) }
        PoeleDirect.reglage("passe le poêle du salon à dix-neuf et demi")!!.let { assertEquals(19.5, it.valeur!!, 0.0) }
        PoeleDirect.reglage("règle la température du poêle à vingt et un degrés")!!.let { assertEquals(21.0, it.valeur!!, 0.0); assertTrue(it.lieu.isEmpty()) }
        PoeleDirect.reglage("monte le poêle du salon de 2 degrés")!!.let { assertNull(it.valeur); assertEquals(2.0, it.ecart!!, 0.0) }
        PoeleDirect.reglage("baisse le poêle d'un degré")!!.let { assertEquals(-1.0, it.ecart!!, 0.0) }
        PoeleDirect.reglage("monte un peu le chauffage du salon")!!.let { assertEquals(1.0, it.ecart!!, 0.0) }
        PoeleDirect.reglage("monte le poêle de l'étage à 22")!!.let { assertEquals(22.0, it.valeur!!, 0.0) }
        assertTrue(PoeleDirect.reglage("mets les poêles à 20 degrés")!!.tous)
        // Programmation, question, marche / arrêt, autre appareil : pas ce réflexe.
        assertNull(PoeleDirect.reglage("règle le poêle à 20 degrés demain à 7 h"))
        assertNull(PoeleDirect.reglage("quelle est la température du poêle"))
        assertNull(PoeleDirect.reglage("allume le poêle du salon"))
        assertNull(PoeleDirect.reglage("mets la télé à 20"))
        assertNull(PoeleDirect.reglage("règle le poêle à 80 degrés"))
    }

    @Test fun consigneBorneeEtArrondie() {
        val a = JSONObject().put("temperature", 19.0).put("target_temp_step", 0.5).put("min_temp", 7).put("max_temp", 25)
        assertEquals(20.0, PoeleDirect.cible(PoeleDirect.Reglage(20.2, null, emptyList(), false), a)!!, 0.0)
        assertEquals(21.0, PoeleDirect.cible(PoeleDirect.Reglage(null, 2.0, emptyList(), false), a)!!, 0.0)
        assertEquals(25.0, PoeleDirect.cible(PoeleDirect.Reglage(30.0, null, emptyList(), false), a)!!, 0.0)
        assertNull(PoeleDirect.cible(PoeleDirect.Reglage(null, 1.0, emptyList(), false), JSONObject()))
    }

    @Test fun cibles() {
        val toutes = listOf(etat("climate.poele_etage", "Poêle étage"), etat("climate.poele_salon", "Poêle salon"), etat("light.salon", "Salon"))
        assertEquals(listOf("climate.poele_salon"), PoeleDirect.cibles(toutes, PoeleDirect.reglage("règle le poêle du salon à 20")!!, emptyList()))
        assertEquals(listOf("climate.poele_etage", "climate.poele_salon"), PoeleDirect.cibles(toutes, PoeleDirect.reglage("mets les poêles à 20 degrés")!!, emptyList()))
        assertTrue(PoeleDirect.cibles(toutes, PoeleDirect.reglage("règle le poêle à 20 degrés")!!, emptyList()).isEmpty())   // lequel ?
    }

    @Test fun modes() {
        PoeleDirect.modeDemande("mets le poêle du salon en mode nuit")!!.let { assertTrue(it.activer); assertEquals(listOf("salon"), it.lieu); assertTrue("nuit" in it.mode) }
        PoeleDirect.modeDemande("Poêle de l'étage en mode silence, active-le")!!.let { assertEquals(listOf("etage"), it.lieu) }
        PoeleDirect.modeDemande("enlève le mode nuit du poêle de l'étage")!!.let { assertTrue(!it.activer) }
        assertNull(PoeleDirect.modeDemande("passe en mode nuit"))                  // pas de poêle
        assertNull(PoeleDirect.modeDemande("mets le poêle à 20 degrés"))
        // « mode éco » du poêle ≠ mode économie de JARVIS.
        assertNull(Brain.modeEnergieDemande(fr.dd.jarvis.societe.core.normaliser("mets le poêle du salon en mode éco")))
    }

    @Test fun commandesDeMode() {
        val toutes = listOf(
            etat("climate.poele_etage", "Poêle étage"), etat("climate.poele_salon", "Poêle salon"),
            etat("switch.poele_etage_mode_nuit", "Poêle étage Mode nuit"), etat("switch.poele_salon_mode_nuit", "Poêle salon Mode nuit"),
            etat("switch.poele_etage_silence", "Poêle étage Silence"))
        val salon = PoeleDirect.commandesMode(toutes, PoeleDirect.modeDemande("mets le poêle du salon en mode nuit")!!)!!.single()
        assertEquals("switch.poele_salon_mode_nuit", salon.id); assertEquals("turn_on", salon.service)
        assertEquals("turn_off", PoeleDirect.commandesMode(toutes, PoeleDirect.modeDemande("enlève le mode nuit du poêle de l'étage")!!)!!.single().service)
        // Préréglage de l'entité climate quand il existe.
        val presets = listOf(etat("climate.mcz_salon", "MCZ salon", JSONObject().put("preset_modes", JSONArray(listOf("none", "night", "eco")))))
        PoeleDirect.commandesMode(presets, PoeleDirect.modeDemande("mets le poêle du salon en mode nuit")!!)!!.single().let {
            assertEquals("set_preset_mode", it.service); assertEquals("night", it.donnees!!.getString("preset_mode"))
        }
        // Mode inexistant pour ce poêle : le cerveau reprend.
        assertNull(PoeleDirect.commandesMode(toutes, PoeleDirect.modeDemande("mets le poêle du salon en mode silence")!!))
    }
}
