package fr.dd.jarvis.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskReveilTest {
    private fun mots(vararg m: Pair<String, Double>): JSONArray {
        val a = JSONArray(); var t = 0.0
        m.forEach { (w, c) -> a.put(JSONObject().put("word", w).put("conf", c).put("start", t).put("end", t + 0.45)); t += 0.5 }
        return a
    }

    @Test fun reveilEnDebutDePhrase() {
        assertTrue(VoskWake.estReveil(mots("jarvis" to 0.7, "allume" to 1.0), 0.6))
        assertTrue(VoskWake.estReveil(mots("dis" to 1.0, "jarvis" to 0.9), 0.6))
        assertTrue(VoskWake.estReveil(mots("ok" to 1.0, "jarvis" to 0.9), 0.6))
        assertTrue(VoskWake.estReveil(mots("[unk]" to 1.0, "jarvis" to 0.9), 0.6))
    }

    @Test fun reveilVoixBasseAvecConfianceAdaptee() {
        assertTrue(VoskWake.estReveil(mots("jarvis" to 0.48), 0.48))
        assertFalse(VoskWake.estReveil(mots("jarvis" to 0.47), 0.48))
    }

    @Test fun porteVocaleSensibleAuChuchotementSansIgnorerLeBruitAmbiant() {
        assertEquals(55.0, PorteVocale.seuilPourPlancher(20.0), 0.001)
        assertEquals(150.0, PorteVocale.seuilPourPlancher(100.0), 0.001)
        val porte = PorteVocale()
        assertEquals(PorteVocale.Decision.IGNORER, porte.trame(20.0))
        assertEquals(PorteVocale.Decision.OUVRIR, porte.trame(80.0))
    }

    @Test fun finDePhraseRespecteLaPauseEtAttendLeSilenceReel() {
        assertFalse(EcouteParMots.finPhrase(finMots = true, porteParle = true, finVoix = false))
        assertTrue(EcouteParMots.finPhrase(finMots = true, porteParle = true, finVoix = true))
        assertTrue(EcouteParMots.finPhrase(finMots = true, porteParle = false, finVoix = false))
        assertFalse(EcouteParMots.finPhrase(finMots = false, porteParle = true, finVoix = true))
    }

    @Test fun pasDeReveil() {
        assertFalse(VoskWake.estReveil(mots("jarvis" to 0.5), 0.6))                         // confiance trop basse
        assertFalse(VoskWake.estReveil(mots("j'arrive" to 1.0, "jarvis" to 0.9), 0.6))     // pas en tête de phrase
        assertFalse(VoskWake.estReveil(mots("le" to 1.0, "service" to 1.0), 0.6))
        assertFalse(VoskWake.estReveil(null, 0.6))
    }

    @Test fun grammaireFrancaise() {
        val g = VoskWake.GRAMMAIRE_FR
        assertTrue("jarvis" in g && "[unk]" in g)
        assertEquals(g.size, g.toSet().size)
        // Quasi-homonymes présents comme leurres (sinon ils réveilleraient JARVIS).
        listOf("j'arrive", "service", "gervais", "marvin", "avis").forEach { assertTrue(it, it in g) }
        // Prénoms et mots qui réveillaient JARVIS à tort.
        listOf("joris", "doris", "boris", "jadis", "javier", "janvier", "charlie", "carvi", "parvis").forEach { assertTrue(it, it in g) }
    }
}
