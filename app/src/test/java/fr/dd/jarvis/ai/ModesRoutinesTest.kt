package fr.dd.jarvis.ai

import fr.dd.jarvis.device.Routine
import fr.dd.jarvis.device.Routines
import fr.dd.jarvis.societe.core.normaliser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModesRoutinesTest {
    private fun m(t: String) = Brain.modeEnergieDemande(normaliser(t))

    @Test fun modeDeJarvisParDefaut() {
        assertEquals("jarvis?" to "equilibre", m("passe en mode équilibré"))
        assertEquals("jarvis?" to "equilibre", m("mode équilibré"))
        assertEquals("jarvis" to "equilibre", m("passe le mode de fonctionnement Jarvis en mode équilibré"))
        assertEquals("jarvis" to "economie", m("mets Jarvis en mode économie"))
        assertEquals("telephone" to "equilibre", m("passe le téléphone en mode équilibré"))
        assertEquals("telephone" to "performance", m("mets le portable en mode performance"))
        // Plusieurs actions : au découpage habituel ; autres « modes » : pas ce réflexe.
        assertNull(m("passe Jarvis en mode économie et le téléphone aussi"))
        assertNull(m("active le mode coucher"))
        assertNull(m("active le mode téléphone sécurisé"))
        assertNull(m("mets le téléphone en mode normal"))
    }

    @Test fun routineParSonNom() {
        val r = Routine("coucher_1", "Coucher", "phrase", listOf("je vais dormir"), actions = listOf("Éteins les lumières"))
        listOf("mode coucher", "active le mode coucher", "Jarvis, passe en mode coucher", "lance la routine coucher", "coucher", "je vais dormir")
            .forEach { assertTrue(it, Routines.correspond(r, it)) }
        assertFalse(Routines.correspond(r, "je vais me coucher tard ce soir"))
        val r2 = Routine("mc", "Mode coucher", "phrase", emptyList(), actions = listOf("x"))
        assertTrue(Routines.correspond(r2, "active le mode coucher"))
        assertTrue(Routines.correspond(r2, "mode coucher"))
        assertFalse(Routines.correspond(r2.copy(active = false), "mode coucher"))
    }
}
