package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class RoutinesDescriptionTest {
    private val routines = listOf(
        Routine("mode_lever", "mode lever", "phrase", listOf("mode lever"), actions = listOf("- passe Jarvis en mode équilibré", "- retire le mode nuit du poêle salon")),
        Routine("coucher", "Coucher", "phrase", listOf("mode coucher"), actions = listOf("mets les poêles en mode nuit")))

    @Test fun descriptionDUneRoutine() {
        listOf("liste les actions du mode lever", "quelles sont les actions de la routine mode lever", "que fait le mode lever ?",
            "montre-moi le mode lever", "Jarvis, détaille la routine lever").forEach { q ->
            val d = Routines.description(routines, q)
            assertTrue(q, d != null && d.contains("retire le mode nuit du poêle salon"))
        }
        assertTrue(Routines.description(routines, "liste les actions du mode coucher")!!.contains("mets les poêles en mode nuit"))
        assertNull(Routines.description(routines, "liste mes modes"))
        assertNull(Routines.description(routines, "que fait le mode vacances"))
    }

    @Test fun lesModesDeJarvisNeVolentPasLesAppareils() {
        // « Retire le mode nuit du poêle salon » avec un mode JARVIS « Nuit » : pas une modification de ce mode.
        assertNull(fr.dd.jarvis.securite.Systemes.intention("retire le mode nuit du poêle salon", listOf("Nuit")))
        assertNull(fr.dd.jarvis.securite.Systemes.intention("passe le téléphone en mode économie", listOf("Économie")))
        // « Dans le mode nuit, retire le poêle » vise bien le mode de JARVIS.
        assertEquals("modifier", fr.dd.jarvis.securite.Systemes.intention("dans le mode nuit, retire le poêle", listOf("Nuit"))!!.getString("action"))
    }

    @Test fun pucesRetirees() {
        assertEquals("passe Jarvis en mode équilibré", "- passe Jarvis en mode équilibré".trim().replace(Regex("^(?:[-•*·]+|\\d+[.)])\\s*"), "").trim())
    }
}

class PoeleConsigneDeuxPoelesTest {
    private fun etat(id: String, nom: String, state: String = "off") =
        JSONObject().put("entity_id", id).put("state", state).put("attributes", JSONObject().put("friendly_name", nom))

    @Test fun consigneQuiCiteLesDeuxPoeles() {
        val toutes = listOf(etat("climate.poele_etage", "Poêle étage", "heat"), etat("climate.poele_rdc", "Poêle rez de chaussée", "heat"),
            etat("switch.poeleetagenaturalmode", "-poele etage Natural Mode", "on"), etat("switch.poelerdcnaturalmode", "-poele rez de chaussée Natural Mode", "on"))
        val ligne = "maison : mode nuit des poêles : switch.poeleetagenaturalmode pour l'étage et switch.poelerdcnaturalmode pour le salon"
        val notes = listOf("switch.poeleetagenaturalmode" to ligne, "switch.poelerdcnaturalmode" to ligne)
        val etage = fr.dd.jarvis.ai.PoeleDirect.commandesMode(toutes, fr.dd.jarvis.ai.PoeleDirect.modeDemande("retire le mode nuit du poêle étage")!!, notes.map { it.first }, notes)!!
        assertEquals(listOf("switch.poeleetagenaturalmode"), etage.map { it.id }); assertEquals("turn_off", etage.single().service)
        val salon = fr.dd.jarvis.ai.PoeleDirect.commandesMode(toutes, fr.dd.jarvis.ai.PoeleDirect.modeDemande("retire le mode nuit du poêle salon")!!, notes.map { it.first }, notes)!!
        assertEquals(listOf("switch.poelerdcnaturalmode"), salon.map { it.id })
    }
}

class RoutinesComptesRendusTest {
    @Test fun echecsReconnus() {
        listOf("Le mode nuit du poêle de l’étage n’a pas pu être désactivé ; le commutateur reste sur « on ».",
            "Home Assistant a accepté la commande pour désactiver le mode nuit du poêle du salon, mais l'état de l'appareil ne confirme pas encore le changement",
            "⚠️ Poêle salon : mode nuit demandé, mais Home Assistant ne le confirme pas").forEach {
            assertTrue(it, Routines.ligne("x", it).startsWith("❌"))
        }
        assertTrue(Routines.ligne("x", "🔥 -poele etage Natural Mode : mode nuit retiré.").startsWith("✅"))
    }

    @Test fun pasDeRoutineDansLaRoutine() {
        Routines.pendantRoutine(60_000)
        try {
            assertTrue(Routines.refus("routines", JSONObject().put("action", "lancer").put("nom", "mode coucher")) != null)
            assertNull(Routines.refus("routines", JSONObject().put("action", "lister")))
        } finally { Routines.pendantRoutine(0) }
    }

    @Test fun modesEcritsSansVerbe() {
        assertEquals("telephone" to "economie", fr.dd.jarvis.ai.Brain.modeEnergieDemande(fr.dd.jarvis.societe.core.normaliser("Téléphone en mode éco")))
        assertEquals("jarvis" to "economie", fr.dd.jarvis.ai.Brain.modeEnergieDemande(fr.dd.jarvis.societe.core.normaliser("Jarvis en mode économie")))
        assertEquals("telephone" to "equilibre", fr.dd.jarvis.ai.Brain.modeEnergieDemande(fr.dd.jarvis.societe.core.normaliser("le téléphone qui passe en mode équilibré")))
    }
}
