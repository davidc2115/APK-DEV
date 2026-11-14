package fr.dd.jarvis.hermes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaisGeminiTest {
    private fun outil(nom: String, desc: String) = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", nom).put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("x", JSONObject().put("type", "string").put("description", "d".repeat(400))))))

    @Test fun geminiRecoitLaDemandeQuasiComplete() {
        val consignes = "Consignes d'Hermès. " + "a".repeat(9000) + " FIN-HERMES"
        val jarvis = "IDENTITÉ JARVIS. " + "b".repeat(12000) + " Mémoire : Clairotte = client. Nous sommes le lundi."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", consignes))
            .put(JSONObject().put("role", "system").put("content", jarvis))
        repeat(16) { i -> messages.put(JSONObject().put("role", if (i % 2 == 0) "user" else "assistant").put("content", "message $i")) }
        messages.put(JSONObject().put("role", "user").put("content", "quel est mon prochain rendez-vous chez Clairotte ?"))
        val outils = JSONArray()
        listOf("terminal", "web_search", "cronjob", "delegate_task", "memory", "skill_view").forEach { outils.put(outil(it, "Native Hermes tool, english description")) }
        outils.put(outil("mcp_jarvis_agent", "Agent téléphone Jarvis"))
        repeat(90) { i -> outils.put(outil("mcp_jarvis_outil_$i", "Outil Jarvis numéro $i " + (if (i == 42) "agenda rendez-vous prochain" else "divers") + " " + "z".repeat(900))) }

        val (msgs, tls) = RelaisGroq.pourGemini(messages, outils)
        val sys = msgs.getJSONObject(0).getString("content")
        assertTrue("consignes coupées", sys.contains("FIN-HERMES") && sys.contains("Mémoire : Clairotte") && !sys.contains("abrégées"))
        assertEquals("historique : 12 messages + la demande", 1 + 12 + 1, msgs.length())
        val noms = (0 until tls!!.length()).map { tls.getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(64, noms.size)
        assertTrue(noms.containsAll(listOf("terminal", "web_search", "cronjob", "delegate_task", "memory", "skill_view", "mcp_jarvis_agent", "mcp_jarvis_outil_42")))
        assertTrue(tls.getJSONObject(0).getJSONObject("function").getString("description").length <= 700)
    }
}
