package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetRequeteIATest {
    @Test fun limiteLeNombreDoutilsAuPlafondGroq() {
        val tools = JSONArray()
        repeat(150) { i ->
            tools.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", "outil_$i")
                    .put("description", "Description détaillée ".repeat(20))
                    .put("parameters", JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("arg", JSONObject()
                            .put("type", "string").put("description", "Paramètre détaillé ".repeat(10)))))))
        }
        val safe = BudgetRequeteIA.outils(tools)!!
        assertEquals(128, safe.length())
        assertEquals("outil_0", safe.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("outil_149", safe.getJSONObject(127).getJSONObject("function").getString("name"))
        assertTrue(safe.getJSONObject(0).getJSONObject("function").getString("description").length <= 180)
    }

    @Test fun conserveLeDernierMessageEtLesAppelsOutils() {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", "S".repeat(10_000)))
        repeat(12) { messages.put(JSONObject().put("role", "user").put("content", "ancien $it " + "x".repeat(2_000))) }
        messages.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "call_1"))))
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "résultat ".repeat(2_000)))
        messages.put(JSONObject().put("role", "user").put("content", "LANCE LA LECTURE MAINTENANT"))
        val safe = BudgetRequeteIA.messages(messages, niveau = 2)
        assertTrue(safe.length() < messages.length())
        assertTrue((0 until safe.length()).any { safe.getJSONObject(it).optString("role") == "tool" })
        assertTrue((0 until safe.length()).any { safe.getJSONObject(it).optJSONArray("tool_calls") != null })
        assertTrue((0 until safe.length()).any { safe.getJSONObject(it).optString("content").contains("LANCE LA LECTURE MAINTENANT") })
        assertTrue(safe.toString().length < 24_000)
    }

    @Test fun neSupprimePasLeSchemaQuandIlYEnAMoinsDe128() {
        val tools = JSONArray()
        repeat(12) { tools.put(JSONObject().put("function", JSONObject().put("name", "tool_$it").put("description", "desc"))) }
        val safe = BudgetRequeteIA.outils(tools)
        assertEquals(12, safe!!.length())
        assertFalse(safe.toString().contains("tool_12"))
    }
}
