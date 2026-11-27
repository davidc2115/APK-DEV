package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantDiagnosticAvanceTest {
    @Test fun regroupeLesEntitesIndisponiblesParIntegrationEtRemonteLesIntegrationsEnErreur() {
        val integrations = JSONArray()
            .put(JSONObject().put("title", "Freebox").put("domain", "freebox").put("entry_id", "abc").put("state", "setup_error"))
            .put(JSONObject().put("title", "Mobile App").put("domain", "mobile_app").put("entry_id", "def").put("state", "loaded"))
        val states = JSONArray()
            .put(JSONObject().put("entity_id", "sensor.freebox_download").put("state", "unavailable").put("attributes", JSONObject().put("friendly_name", "Débit descendant")))
            .put(JSONObject().put("entity_id", "sensor.freebox_upload").put("state", "unavailable"))
            .put(JSONObject().put("entity_id", "sensor.phone_battery").put("state", "unknown"))
        val registry = JSONArray()
            .put(JSONObject().put("entity_id", "sensor.freebox_download").put("platform", "freebox"))
            .put(JSONObject().put("entity_id", "sensor.freebox_upload").put("platform", "freebox"))
        val report = HomeAssistantDiagnosticAvance.analyser(integrations, states, registry, "ERROR (MainThread) [freebox] Connection timeout", "valide")
        assertTrue(report.contains("Freebox (freebox) : setup_error"))
        assertTrue(report.contains("freebox : 2 entité(s)"))
        assertTrue(report.contains("Débit descendant"))
        assertTrue(report.contains("inconnues : 1"))
        assertTrue(report.contains("Connection timeout"))
        assertTrue(report.contains("integration_recharger"))
    }

    @Test fun diagnosticResteUtileSansErreurNiEntiteIndisponible() {
        val integrations = JSONArray().put(JSONObject().put("title", "Home Assistant Core").put("domain", "homeassistant").put("state", "loaded"))
        val report = HomeAssistantDiagnosticAvance.analyser(integrations, JSONArray(), JSONArray(), "", "valide")
        assertTrue(report.contains("Validation YAML : valide"))
        assertTrue(report.contains("Aucune intégration en état d'erreur"))
        assertTrue(report.contains("Aucune ligne d'erreur reconnue"))
    }
}
