package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClesGeminiTest {
    private val fuite = """Gemini 403 : {"error":{"code":403,"message":"Your API key was reported as leaked. Please use another API key.","status":"PERMISSION_DENIED"}}"""
    private val modele = """Gemini 403 : {"error":{"code":403,"message":"The caller does not have permission","status":"PERMISSION_DENIED"}}"""

    @Test fun classement() {
        assertTrue(KeyRing.cleRefusee(403, fuite))
        assertFalse(KeyRing.cleRefusee(403, modele))
        assertTrue(KeyRing.cleRefusee(401, "x"))
        assertFalse(KeyRing.cleRefusee(429, "quota"))
        assertEquals(60_000L, KeyRing.pause(403, modele))
        assertEquals(6 * 3_600_000L, KeyRing.pause(403, fuite))
        assertTrue(GeminiModeles.modeleInaccessible(403, modele))
        assertFalse(GeminiModeles.modeleInaccessible(403, fuite))
        assertTrue(GeminiModeles.modeleInaccessible(404, "models/gemini-x is not found"))
    }

    @Test fun motifLisible() {
        assertEquals("403 · Your API key was reported as leaked. Please use another API key.", KeyRing.motif(403, fuite))
    }
}
