package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccesDistantTest {
    @Test fun portsAdministrationRestentDansUnTunnelSecurise() {
        listOf(21, 22, 23, 80, 445, 554, 1883, 3389, 5000, 5555, 5900, 631, 8000, 8009, 8080, 8123, 8883, 9000, 9100, 32400)
            .forEach { assertEquals("Le port $it doit rester derrière un tunnel", true, AccesDistant.tunnelObligatoire(it)) }
        assertEquals(true, AccesDistant.tunnelObligatoire(32400))
        assertEquals(false, AccesDistant.tunnelObligatoire(443))
    }

    @Test fun inventaireDetecteUneDmzDejaActive() {
        assertTrue(AccesDistant.dmzActive("DMZ : activée vers 192.168.1.20"))
        assertTrue(!AccesDistant.dmzActive("DMZ : désactivée"))
    }

    @Test fun inventaireSignaleLesAnciennesRedirectionsNonHttpsSansLesSupprimer() {
        val texte = "🔀 Redirections de ports:\n" +
            "🟢 #1 TCP 8123 → 192.168.1.20:8123 (Home Assistant)\n" +
            "🟢 #2 TCP 443 → 192.168.1.10:443 (reverse proxy)\n" +
            "⚪ #3 TCP 5555 → 192.168.1.30:5555 (ADB désactivé)"
        val dangereuses = AccesDistant.lignesRedirectionsDangereuses(texte)
        assertEquals(1, dangereuses.size)
        assertTrue(dangereuses.single().contains("8123"))
    }

    @Test fun seulesLesEntreesHttpsConnuesPeuventEtrePubliees() {
        listOf(443, 8443, 9443).forEach { assertEquals(true, AccesDistant.publicationDirecteAutorisee(it)) }
        listOf(80, 8080, 8123, 32400, 5555, 8081).forEach { assertEquals(false, AccesDistant.publicationDirecteAutorisee(it)) }
    }

    @Test fun ports() {
        assertEquals(8123, AccesDistant.portExterne(8123, emptySet()))
        assertEquals(48123, AccesDistant.portExterne(8123, setOf(8123)))
        assertEquals(40022, AccesDistant.portExterne(22, emptySet()))
        assertEquals(443, AccesDistant.portExterne(443, emptySet()))
        assertEquals(40554, AccesDistant.portExterne(554, emptySet()))
        assertEquals(null, AccesDistant.portPrincipal(JSONObject().put("ports", JSONArray(listOf(22, 8123, 80)))))
        assertEquals(443, AccesDistant.portPrincipal(JSONObject().put("ports", JSONArray(listOf(80, 443)))))
        assertEquals(554, AccesDistant.portPrincipal(JSONObject().put("ports", JSONArray(listOf(554)))))
    }
}
