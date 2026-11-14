package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AccesDistantTest {
    @Test fun ports() {
        assertEquals(8123, AccesDistant.portExterne(8123, emptySet()))
        assertEquals(48123, AccesDistant.portExterne(8123, setOf(8123)))
        assertEquals(40022, AccesDistant.portExterne(22, emptySet()))
        assertEquals(443, AccesDistant.portExterne(443, emptySet()))
        assertEquals(40554, AccesDistant.portExterne(554, emptySet()))
        assertEquals(8123, AccesDistant.portPrincipal(JSONObject().put("ports", JSONArray(listOf(22, 8123, 80)))))
        assertEquals(null, AccesDistant.portPrincipal(JSONObject().put("ports", JSONArray(listOf(80, 443)))))
        assertEquals(554, AccesDistant.portPrincipal(JSONObject().put("ports", JSONArray(listOf(554)))))
    }
}
