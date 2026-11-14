package fr.dd.jarvis.hermes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RelaisGroqTest {
    private fun outil(nom: String, desc: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", nom).put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("description", "Action détaillée ".repeat(10)))
            .put("chemin", JSONObject().put("type", "string").put("description", "Chemin du fichier ".repeat(8))))))

    @Test fun compaction() {
        val sp = java.nio.file.Files.createTempDirectory("relais").toFile()
        val outils = JSONArray()
        for (i in 0 until 100) outils.put(outil("mcp_jarvis_outil_$i", "Outil numéro $i qui fait beaucoup de choses très utiles pour le téléphone. ".repeat(10)))
        outils.put(outil("mcp_jarvis_freebox", "Freebox : pilotage complet, redirections de ports, VPN WireGuard, wifi. ".repeat(6)))
        outils.put(outil("tool_search", "Recherche un outil dans le catalogue."))
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", "Tu es Hermès. ".repeat(3000)))
        for (k in 0 until 12) {
            msgs.put(JSONObject().put("role", "user").put("content", "Question ancienne $k ".repeat(80)))
            msgs.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("reasoning_content", "réflexion").put("tool_calls", JSONArray().put(
                JSONObject().put("id", "c$k").put("type", "function").put("function", JSONObject().put("name", "mcp_jarvis_outil_$k").put("arguments", "{}")))))
            msgs.put(JSONObject().put("role", "tool").put("tool_call_id", "c$k").put("content", "Résultat très long ".repeat(500)))
            msgs.put(JSONObject().put("role", "assistant").put("content", "Réponse $k ".repeat(100)))
        }
        msgs.put(JSONObject().put("role", "user").put("content", "Active le VPN WireGuard de ma Freebox"))
        val rapport = StringBuilder("brut=" + RelaisGroq.estimationPourTest(msgs, outils) + " jetons\n")
        for (n in 0..3) {
            val m = RelaisGroq.compacterPourTest(msgs, n); val o = RelaisGroq.outilsPourTest(outils, msgs, n)
            val noms = (0 until o!!.length()).map { o.getJSONObject(it).getJSONObject("function").getString("name") }
            rapport.append("palier $n : ${RelaisGroq.estimationPourTest(m, o)} jetons, ${m.length()} messages, ${o.length()} outils, freebox=${"mcp_jarvis_freebox" in noms}, tool_search=${"tool_search" in noms}\n")
            // cohérence : chaque message tool suit un assistant avec tool_calls ; pas de champ inconnu
            for (i in 0 until m.length()) {
                val x = m.getJSONObject(i)
                assertTrue(!x.has("reasoning_content"))
                if (x.optString("role") == "tool") assertTrue("tool orphelin au palier $n", i > 0 && (m.getJSONObject(i - 1).has("tool_calls") || m.getJSONObject(i - 1).optString("role") == "tool"))
            }
            assertTrue(m.getJSONObject(m.length() - 1).optString("content").contains("WireGuard"))
            assertTrue("mcp_jarvis_freebox" in noms)
        }
        val c = JSONObject().put("id", "x").put("created", 1).put("model", "m").put("choices", JSONArray().put(JSONObject().put("index", 0).put("finish_reason", "tool_calls")
            .put("message", JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
                .put("function", JSONObject().put("name", "mcp_jarvis_freebox").put("arguments", "{\"action\":\"activer_vpn\"}"))))))).put("usage", JSONObject().put("total_tokens", 10))
        File(sp, "relais_sse.txt").writeText(PontMcp.enFlux(c))
        File(sp, "relais_rapport.txt").writeText(rapport.toString())
    }
}
