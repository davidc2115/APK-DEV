package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutinesLigneTest {
    @Test fun compteRenduUneLigneParAction() {
        assertEquals("✅ Get Lucky — Daft Punk", Routines.ligne("mets mes titres J'aime", "Get Lucky — Daft Punk"))
        assertEquals("✅ Wi-Fi activé.", Routines.ligne("active le wifi", "Wi-Fi activé.\nDétails…"))
        assertEquals("❌ Impossible d'ouvrir Waze.", Routines.ligne("ouvre Waze", "❌ Impossible d'ouvrir Waze."))
        assertEquals("❌ Échec Bluetooth : refusé", Routines.ligne("active le bluetooth", "Échec Bluetooth : refusé"))
        assertEquals("✅ allume la lampe", Routines.ligne("allume la lampe", "   "))
        assertEquals("✅ 🧭 Itinéraire vers maison envoyé à Waze.", Routines.ligne("rentre à la maison", "## 🧭 **Itinéraire vers maison envoyé à Waze.**"))
    }
}
