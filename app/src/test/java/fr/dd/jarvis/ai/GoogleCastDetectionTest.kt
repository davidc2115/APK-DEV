package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCastDetectionTest {
    @Test fun inventaireDistingueEnceintesEtLecteursVideoCast() {
        assertEquals("🔊 enceinte Google / Nest",
            Reseau.typeDe(listOf(8008, 8009), "Google-Nest-Mini", "_googlecast._tcp"))
        assertEquals("🔊 enceinte Google / Nest",
            Reseau.typeDe(listOf(8009), "", "Nest Audio _googlecast._tcp"))
        assertEquals("🔊 enceinte Google / Nest",
            Reseau.typeDe(listOf(8009), "Chromecast Audio", "_googlecast._tcp"))
        assertEquals("📺 Chromecast / TV",
            Reseau.typeDe(listOf(8008, 8009), "Chromecast Salon", "_googlecast._tcp"))
        assertEquals("📺 Chromecast / TV",
            Reseau.typeDe(listOf(8008, 8009), "Google TV Salon", "_googlecast._tcp"))
        assertEquals("📡 appareil Google Cast",
            Reseau.typeDe(listOf(8008, 8009), "", "_googlecast._tcp"))
        assertEquals("📡 appareil Google Cast",
            Reseau.typeDe(listOf(8009), "", "_googlecast._tcp"))
    }

    @Test fun uneEnceinteGoogleNeDevientJamaisUneTeleADB() {
        assertTrue(Tele.estEnceinteGoogle("Nest Mini Salon _googlecast._tcp", "🔊 enceinte Google / Nest"))
        assertTrue(Tele.estEnceinteGoogle("Google Home", ""))
        assertFalse(Tele.estEnceinteGoogle("Chromecast Google TV Salon", "📺 Chromecast / TV"))
    }
}
