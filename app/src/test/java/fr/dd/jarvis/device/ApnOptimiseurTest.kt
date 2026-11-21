package fr.dd.jarvis.device

import fr.dd.jarvis.device.ApnOptimiseur.Essai
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApnOptimiseurTest {
    @Test fun ping() {
        assertTrue(ApnOptimiseur.pingOk("PING 1.1.1.1 (1.1.1.1) 1372(1400) bytes of data.\n1400 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=31.2 ms\n1 packets transmitted, 1 received, 0% packet loss"))
        assertFalse(ApnOptimiseur.pingOk("ping: local error: message too long, mtu=1430\n1 packets transmitted, 0 received, +1 errors, 100% packet loss"))
        assertFalse(ApnOptimiseur.pingOk("1 packets transmitted, 0 received, 100% packet loss"))
    }

    @Test fun mtu() {
        val essais = ArrayList<Int>()
        val m = ApnOptimiseur.chercherMtu(1200, 1500) { essais += it; it <= 1430 }!!
        assertTrue("$m", m in 1426..1430)
        assertTrue(essais.size <= 12)
        assertEquals(1500, ApnOptimiseur.chercherMtu(1200, 1500) { true })
        assertNull(ApnOptimiseur.chercherMtu(1200, 1500) { false })
        assertEquals(1428, ApnOptimiseur.mtuConseillee(1500, 1428))
        assertNull(ApnOptimiseur.mtuConseillee(1500, 1496))     // écart minime : on laisse l'opérateur
        assertNull(ApnOptimiseur.mtuConseillee(1430, 1430))
        assertNull(ApnOptimiseur.mtuConseillee(1500, null))
        assertEquals(3, ApnOptimiseur.bindsMtu(1428, 0).size)
        assertEquals(listOf("--bind mtu:i:1428"), ApnOptimiseur.bindsMtu(1428, 1))
        assertTrue(ApnOptimiseur.bindsMtu(1428, 2).isEmpty() && ApnOptimiseur.bindsMtu(null, 0).isEmpty())
    }

    @Test fun choixDuMeilleurReglage() {
        fun e(nom: String, d: Double?, lat: Long?, actuel: Boolean = false) = Essai(nom, nom, TestDebit.Mesure(lat, d, 0), actuel)
        val base = e("actuel", 20.0, 40, actuel = true)
        val proche = e("IPv4/IPv6", 21.0, 40)
        val net = e("IPv4", 30.0, 35)
        assertSame(base, ApnOptimiseur.meilleur(base, listOf(proche)))           // +5 % : bruit de mesure, on garde l'actuel
        assertSame(net, ApnOptimiseur.meilleur(base, listOf(proche, net)))
        assertSame(proche, ApnOptimiseur.meilleur(e("actuel", null, null, true), listOf(proche)))   // l'actuel ne marche pas
        assertSame(base, ApnOptimiseur.meilleur(base, listOf(e("x", null, null))))
        assertTrue(ApnOptimiseur.ligne(net, true).startsWith("✅ IPv4 : ⬇️ 30,0 Mbit/s"))
    }

    @Test fun dns() {
        assertEquals("dns.google" to 12L, ApnOptimiseur.dnsConseille(60, listOf("one.one.one.one" to 15L, "dns.google" to 12L, "dns.quad9.net" to null)))
        assertNull(ApnOptimiseur.dnsConseille(20, listOf("one.one.one.one" to 18L)))
        assertEquals("one.one.one.one" to 18L, ApnOptimiseur.dnsConseille(null, listOf("one.one.one.one" to 18L)))
    }

    @Test fun phrases() {
        val attendus = mapOf("optimise l'APN à fond" to "apn_optimiser", "trouve le meilleur APN" to "apn_optimiser", "configure l'APN" to "apn_configurer",
            "teste les DNS" to "dns", "quel DNS est le plus rapide" to "dns", "active le DNS Cloudflare" to "dns_appliquer:cloudflare",
            "mets le DNS privé Google" to "dns_appliquer:google", "remets le DNS d'origine" to "dns_restaurer", "mesure la MTU" to "mtu")
        attendus.forEach { (p, a) -> assertEquals(p, a, ReseauMobile.intention(p)) }
        assertNull(ReseauMobile.intention("change le DNS de la Freebox"))
    }
}
