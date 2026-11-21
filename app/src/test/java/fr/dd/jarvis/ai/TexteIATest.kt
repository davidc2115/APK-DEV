package fr.dd.jarvis.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TexteIATest {
    @Test fun contenuNullJamaisAffiche() {
        assertEquals("", JSONObject().put("role", "assistant").put("content", JSONObject.NULL).texteIA())
        assertEquals("", JSONObject().put("content", "null").texteIA())
        assertEquals("", JSONObject().texteIA())
        assertEquals("", (null as JSONObject?).texteIA())
        assertEquals("21 °C", JSONObject().put("content", "21 °C").texteIA())
        assertTrue(estNul("null") && estNul(" NULL ") && estNul("None") && estNul(""))
        assertFalse(estNul("Il fait 21 °C, rien de null là-dedans"))
    }
}
