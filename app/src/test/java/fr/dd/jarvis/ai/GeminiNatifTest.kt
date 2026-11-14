package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNatifTest {
    @Test fun conversionMessages() {
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Tu es JARVIS."))
            .put(JSONObject().put("role", "user").put("content", "Quel temps ?"))
            .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
                .put("function", JSONObject().put("name", "meteo").put("arguments", "{\"lieu\":\"Dijon\"}")))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "Soleil, 12 °C"))
        val (sys, contents) = GeminiChatClient.versNatif(GeminiChatClient.messagesPourGemini(msgs))
        assertEquals("Tu es JARVIS.", sys)
        assertEquals(3, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        val appel = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0)
        assertEquals("meteo", appel.getJSONObject("functionCall").getString("name"))
        assertEquals("Dijon", appel.getJSONObject("functionCall").getJSONObject("args").getString("lieu"))
        assertTrue(appel.has("thoughtSignature"))
        val rep = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("meteo", rep.getString("name"))
        assertEquals("Soleil, 12 °C", rep.getJSONObject("response").getString("content"))
    }

    @Test fun declarationsEnMajuscules() {
        val outils = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "meteo").put("description", "Météo")
            .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", JSONObject().put("lieu", JSONObject().put("type", "string").put("description", "Ville")))
                .put("required", JSONArray().put("lieu")))))
        val d = GeminiChatClient.declarations(GeminiChatClient.outilsPourGemini(outils)).getJSONObject(0)
        assertEquals("OBJECT", d.getJSONObject("parameters").getString("type"))
        assertEquals("STRING", d.getJSONObject("parameters").getJSONObject("properties").getJSONObject("lieu").getString("type"))
        assertTrue(!d.getJSONObject("parameters").has("additionalProperties"))
    }

    @Test fun reponseNative() {
        val txt = """{"candidates":[{"content":{"role":"model","parts":[{"text":"pensée","thought":true},{"functionCall":{"name":"meteo","args":{"lieu":"Dijon"}},"thoughtSignature":"abc"}]}}]}"""
        val m = GeminiChatClient.depuisNatif(txt)
        val a = m.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("meteo", a.getJSONObject("function").getString("name"))
        assertEquals("Dijon", JSONObject(a.getJSONObject("function").getString("arguments")).getString("lieu"))
        assertEquals("abc", a.getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
        assertTrue(m.isNull("content"))
        assertEquals("Bonjour", GeminiChatClient.depuisNatif("""{"candidates":[{"content":{"parts":[{"text":"Bonjour"}]}}]}""").getString("content"))
    }

    @Test fun voiesEtPasserelle() {
        val c = GeminiChatClient(KeyRing { emptyList() }) { "gemini-2.5-flash" }
        assertEquals(GeminiChatClient.Voie.NATIF, c.voies("AQ.Ab8RN6abc").first())
        assertEquals(GeminiChatClient.Voie.OPENAI_BEARER, c.voies("AIzaSyabc").first())
        assertEquals("https://gateway.ai.cloudflare.com/v1/abc/default/google-ai-studio/v1beta/models/gemini-2.5-flash:generateContent",
            GeminiPasserelle.url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", "abc"))
    }
}

class GeminiAplatirTest {
    private fun outil(): org.json.JSONArray = org.json.JSONArray().put(org.json.JSONObject("""{"type":"function","function":{"name":"document_creer","parameters":
        {"type":"object","properties":{"client":{"type":"string"},"lignes":{"type":"array","items":{"type":"object","properties":{"designation":{"type":"string"},
        "options":{"type":"object","properties":{"x":{"type":"object","properties":{"y":{"type":"string"}}}}}}}}}}}}"""))

    @Test fun aplatitEtRestaure() {
        val aplatis = HashMap<String, MutableSet<String>>()
        val t = GeminiChatClient.aplatir(outil(), 1, aplatis)
        val lignes = t.getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").getJSONObject("lignes")
        assertEquals("string", lignes.getString("type"))
        assertEquals(setOf("lignes"), aplatis["document_creer"])
        val msg = org.json.JSONObject().put("tool_calls", org.json.JSONArray().put(org.json.JSONObject().put("function", org.json.JSONObject().put("name", "document_creer")
            .put("arguments", org.json.JSONObject().put("client", "Dupont").put("lignes", "[{\"designation\":\"Maintenance\"}]").toString()))))
        GeminiChatClient.restaurer(msg, aplatis)
        val args = org.json.JSONObject(msg.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals("Maintenance", args.getJSONArray("lignes").getJSONObject(0).getString("designation"))
        // Profondeur 2 : chaque ligne devient un texte JSON (tableau de textes).
        val a2 = HashMap<String, MutableSet<String>>()
        GeminiChatClient.aplatir(outil(), 2, a2)
        assertEquals(setOf("lignes[]"), a2["document_creer"])
        val m2 = org.json.JSONObject().put("tool_calls", org.json.JSONArray().put(org.json.JSONObject().put("function", org.json.JSONObject().put("name", "document_creer")
            .put("arguments", org.json.JSONObject().put("lignes", org.json.JSONArray().put("{\"designation\":\"A\"}")).toString()))))
        GeminiChatClient.restaurer(m2, a2)
        assertEquals("A", org.json.JSONObject(m2.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments")).getJSONArray("lignes").getJSONObject(0).getString("designation"))
        // Profondeur 4 : seul l'objet le plus profond (lignes[].options.x) devient du texte.
        val a4 = HashMap<String, MutableSet<String>>()
        GeminiChatClient.aplatir(outil(), 4, a4)
        assertEquals(setOf("lignes[].options.x"), a4["document_creer"])
    }
}
