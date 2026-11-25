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

    @Test fun resultatPartielDeclencheSansAttendreLaFinDuSilence() {
        assertTrue(VoskWake.detectePartiel(mots("jarvis" to 0.9), 0.6))
        assertTrue(VoskWake.detectePartiel(mots("dis" to 1.0, "jarvis" to 0.9), 0.6))
    }

    @Test fun resultatPartielConserveLesGardeFousAntiFauxReveil() {
        assertFalse(VoskWake.detectePartiel(mots("jarvis" to 0.5), 0.6))
        assertFalse(VoskWake.detectePartiel(mots("j'arrive" to 1.0, "jarvis" to 0.9), 0.6))
        assertFalse(VoskWake.detectePartiel(mots("service" to 1.0), 0.6))
        assertFalse(VoskWake.detectePartiel(null, 0.6))
    }

    @Test fun resultatPartielEnVoitureAccepteJarvisDansLaPhrase() {
        assertTrue(VoskWake.detectePartiel(mots("bonjour" to 1.0, "jarvis" to 0.9), 0.6, partout = true))
    }

    @Test fun fermetureRapideApresLeMot() {
        val porte = PorteVocale().apply { sensible = true }
        assertEquals(PorteVocale.Decision.IGNORER, porte.trame(100.0))
        assertEquals(PorteVocale.Decision.OUVRIR, porte.trame(300.0))
        repeat(11) {
            assertEquals(PorteVocale.Decision.CONTINUER, porte.trame(100.0))
        }
        assertEquals(PorteVocale.Decision.FERMER, porte.trame(100.0))
        assertEquals(12, PorteVocale.MAINTIEN)
    }

    @Test fun fenetresMusiquePlusCourtes() {
        // 30 trames de 50 ms = 1,5 s, chevauchées par la seconde reconnaissance.
        assertEquals(30, VoskWake.FENETRE_MUSIQUE)
    }
}
