package fr.dd.jarvis.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidageVoitureTest {
    @Test fun adresseAuFormatAndroidAuto() {
        assertEquals("12+rue+de+la+Paix,+Dijon", GuidageVoiture.requete("12 rue de la Paix,  Dijon"))
        assertEquals("%C3%89glise+Saint-B%C3%A9nigne", GuidageVoiture.requete("Église Saint-Bénigne"))
        assertEquals("geo:0,0?q=8+place+d%27Arcy,+21000+Dijon", GuidageVoiture.geo("8 place d'Arcy, 21000 Dijon", null))
        assertEquals("geo:47.322047,5.041480", GuidageVoiture.geo("peu importe", 47.322047 to 5.04148))
    }

    @Test fun guidageVerifieParLaNotificationDeNavigation() {
        val t = 1_000_000_000_000L
        GuidageVoiture.noter("com.spotify.music", t + 500, "navigation", true)       // pas une appli de navigation
        GuidageVoiture.noter("com.waze", t + 500, null, false)                       // ni permanente ni « navigation »
        assertNull(GuidageVoiture.demarreDepuis(t))                                  // sans accès aux notifications : inconnu
        GuidageVoiture.noter("com.waze", t + 800, null, true)
        assertTrue(GuidageVoiture.demarreDepuis(t) == true)
        assertNull(GuidageVoiture.demarreDepuis(t + 10_000))                         // plus ancienne que la demande
    }
}
