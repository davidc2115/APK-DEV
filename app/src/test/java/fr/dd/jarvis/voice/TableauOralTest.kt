package fr.dd.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableauOralTest {
    @Test fun tableauLuEnPhrases() {
        val t = "🔥 Poêles\n\n| Poêle | Température actuelle | Consigne | État |\n|---|---|---|---|\n| Poêle étage | 21,5 °C | 22 °C | en chauffe |\n| Poêle salon | — | — | indisponible |"
        assertEquals("🔥 Poêles\n\nPoêle étage : température actuelle 21,5 °C, consigne 22 °C, état en chauffe.\nPoêle salon : état indisponible.", TexteParle.tableauxEnPhrases(t))
        val dit = TexteParle.preparer(t)
        assertTrue(dit, dit.contains("Poêle étage") && dit.contains("21,5 degrés") && !dit.contains("|"))
    }
}
