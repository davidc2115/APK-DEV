package fr.dd.jarvis.ai

import fr.dd.jarvis.hermes.Routeur
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionsCombineesTest {
    @Test fun decoupage() {
        assertEquals(listOf("passe Jarvis en mode économie", "le téléphone en mode économie"),
            Brain.decouperActions("passe Jarvis en mode économie et le téléphone en mode économie"))
        assertEquals(listOf("allume la lampe torche", "mets en vibreur"), Brain.decouperActions("allume la lampe torche puis mets en vibreur"))
        assertEquals("economie", Brain.modeEnergieDans("passe jarvis et le telephone en mode economie"))
        assertEquals("performance", Brain.modeEnergieDans("mode haute performance"))
    }

    @Test fun commandesSimplesChezJarvis() {
        assertEquals(Routeur.Cible.JARVIS, Routeur.decider("auto", false, false, "passe le téléphone en mode économie", null, rapide = true).cible)
        assertEquals(Routeur.Cible.JARVIS, Routeur.decider("auto", false, false, "mets le téléphone en vibreur", null, rapide = true).cible)
        assertEquals(Routeur.Cible.JARVIS, Routeur.decider("auto", false, false, "allume la lampe torche", null, rapide = true).cible)
        assertEquals(Routeur.Cible.HERMES, Routeur.decider("auto", false, false, "tous les matins mets le téléphone en mode économie", null, rapide = true).cible)
        assertEquals(Routeur.Cible.HERMES, Routeur.decider("auto", false, false, "explique-moi pourquoi la batterie se vide", null, rapide = true).cible)
    }
}
