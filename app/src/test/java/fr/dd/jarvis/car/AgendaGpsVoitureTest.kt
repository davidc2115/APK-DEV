package fr.dd.jarvis.car

import fr.dd.jarvis.device.EventInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AgendaGpsVoitureTest {
    @Test fun lieuGpsAvecAdresseEnDescription() {
        val e = EventInfo(1, 1, "Clairotte", 0, 0, false, "47.541558, 4.459845",
            "Code portail 12\nAdresse : 3 rue des Lilas 21000 Dijon\nWaze : https://waze.com/ul?ll=47.541558,4.459845&navigate=yes", null)
        val d = AgendaVoiture.destinations(e)
        assertEquals(1, d.size)
        assertEquals("47.541558, 4.459845", d[0].adresse)
        assertEquals("📍 GPS — 3 rue des Lilas 21000 Dijon", d[0].libelle)
        assertEquals("Dijon", AgendaVoiture.villeDe(d[0]))
        assertEquals(null, AgendaVoiture.villeDe(AgendaVoiture.Destination("📍 GPS — 47.5, 4.4", "47.541558, 4.459845")))
    }
}
