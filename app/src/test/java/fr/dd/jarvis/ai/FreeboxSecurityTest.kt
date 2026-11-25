package fr.dd.jarvis.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeboxSecurityTest {
    @Test fun dmzNePeutPasEtreActiveeMaisPeutEtreDesactivee() {
        assertTrue(FreeboxOs.dmzActivationInterdite("", "192.168.1.20"))
        assertTrue(FreeboxOs.dmzActivationInterdite("activer", "192.168.1.20"))
        assertFalse(FreeboxOs.dmzActivationInterdite("", ""))
        assertFalse(FreeboxOs.dmzActivationInterdite("desactiver", ""))
        assertFalse(FreeboxOs.dmzActivationInterdite("off", ""))
    }

    @Test fun redirectionsFreeboxLimiteesAuxPortsHttpsConnus() {
        listOf(443, 8443, 9443).forEach { assertTrue(AccesDistant.publicationDirecteAutorisee(it)) }
        listOf(80, 8123, 32400, 5555, 8009, 445, 3389, 8081).forEach {
            assertFalse(AccesDistant.publicationDirecteAutorisee(it))
            assertTrue(FreeboxOs.redirectionPortInterdit(it))
        }
        assertFalse(FreeboxOs.redirectionPortInterdit(443))
    }
}
