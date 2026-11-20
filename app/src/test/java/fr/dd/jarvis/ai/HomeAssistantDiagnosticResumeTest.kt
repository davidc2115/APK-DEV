package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantDiagnosticResumeTest {
    @Test
    fun listeLesEntitesIndisponiblesEtInconnuesAvecLeursDomaines() {
        val states = JSONArray()
            .put(JSONObject().put("entity_id", "sensor.onduleur_1").put("state", "unavailable")
                .put("attributes", JSONObject().put("friendly_name", "Onduleur 1")))
            .put(JSONObject().put("entity_id", "light.salon").put("state", "unknown")
                .put("attributes", JSONObject().put("friendly_name", "Lumière salon")))
            .put(JSONObject().put("entity_id", "switch.prise").put("state", "on"))
        val result = HomeAssistantDiagnosticResume.formater(states, "2026.11.1")
        assertTrue(result.contains("version 2026.11.1"))
        assertTrue(result.contains("3 entités"))
        assertTrue(result.contains("1 indisponible(s)"))
        assertTrue(result.contains("1 état(s) inconnu(s)"))
        assertTrue(result.contains("sensor.onduleur_1 — Onduleur 1"))
        assertTrue(result.contains("light.salon — Lumière salon"))
        assertTrue(result.contains("sensor (1)"))
        assertTrue(result.contains("light (1)"))
    }

    @Test
    fun limiteLesExemplesAffichesSansPerdreLesComptages() {
        val states = JSONArray()
        repeat(30) { i ->
            states.put(JSONObject().put("entity_id", "sensor.test_" + i).put("state", "unavailable"))
        }
        val result = HomeAssistantDiagnosticResume.formater(states, "")
        assertTrue(result.contains("30 indisponible(s)"))
        assertTrue(result.contains("Liste limitée aux 12 premiers éléments par état."))
        assertTrue(!result.contains("sensor.test_29"))
    }

    @Test
    fun signaleUneListeTronqueeDesQueLeTreiziemeElementEstMasque() {
        val states = JSONArray()
        repeat(13) { i ->
            states.put(JSONObject().put("entity_id", "sensor.indispo_" + i).put("state", "unavailable"))
        }
        val result = HomeAssistantDiagnosticResume.formater(states, "")
        assertTrue(result.contains("13 indisponible(s)"))
        assertTrue(result.contains("Liste limitée aux 12 premiers éléments par état."))
        assertTrue(!result.contains("sensor.indispo_12"))
    }

    @Test
    fun neSignalePasDeTroncatureQuandLesDouzeExemplesSontComplets() {
        val states = JSONArray()
        repeat(12) { i ->
            states.put(JSONObject().put("entity_id", "sensor.indispo_" + i).put("state", "unavailable"))
        }
        val result = HomeAssistantDiagnosticResume.formater(states, "")
        assertTrue(result.contains("12 indisponible(s)"))
        assertTrue(!result.contains("Liste limitée aux 12 premiers éléments par état."))
    }
}
