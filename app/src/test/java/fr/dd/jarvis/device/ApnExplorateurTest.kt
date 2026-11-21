package fr.dd.jarvis.device

import fr.dd.jarvis.device.ApnExplorateur.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApnExplorateurTest {
    private val actuelOrange = mapOf("_id" to "12", "name" to "Orange World", "apn" to "orange", "user" to "orange", "password" to "orange", "authtype" to "1",
        "type" to "default,supl,mms", "protocol" to "IP", "mmsc" to "http://mms.orange.fr", "mmsproxy" to "192.168.10.200", "mmsport" to "8080")
    private val orange = ReseauMobile.apnRecommande("20801", "Orange F")

    @Test fun partDeLApnQuiMarche() {
        val b = ApnExplorateur.depuisApn(actuelOrange)
        assertEquals("orange", b.apn); assertEquals("IP", b.protocole); assertEquals("orange", b.user); assertEquals("http://mms.orange.fr", b.mmsc)
        // Type sans « default » : remplacé par un type internet.
        assertEquals("default,supl", ApnExplorateur.depuisApn(mapOf("apn" to "x", "type" to "mms")).type)
        // Opérateur virtuel inconnu : on part quand même de son APN, avec ses identifiants.
        val nrj = ApnExplorateur.depuisApn(mapOf("apn" to "fnetnrj", "protocol" to "IPV4V6"))
        val noms = ApnExplorateur.etapeNoms(nrj, null, emptySet()).map { it.apn }
        assertEquals(listOf("internet"), noms)
    }

    @Test fun exploreAuDelaDesValeursDeLOperateur() {
        val b = ApnExplorateur.depuisApn(actuelOrange)
        val e1 = ApnExplorateur.etapeNoms(b, orange, emptySet())
        // L'actuel en IPv4/IPv6 + les autres passerelles Orange, avec les identifiants de l'opérateur.
        assertEquals(listOf("orange", "orange.fr", "orange-mib"), e1.map { it.apn })
        assertTrue(e1.all { it.protocole == "IPV4V6" && it.user == "orange" && it.mmsc == "http://mms.orange.fr" })
        // Un réglage qui a échoué récemment n'est pas retenté.
        assertEquals(listOf("orange", "orange-mib"), ApnExplorateur.etapeNoms(b, orange, setOf("orange.fr|IPV4V6|0")).map { it.apn })
        val meilleur = e1[1]
        val e2 = ApnExplorateur.etapeProtocoles(meilleur, setOf(meilleur.cle), emptySet())
        assertEquals(listOf("IP", "IPV6"), e2.map { it.protocole })
        assertEquals(listOf(1420, 1380), ApnExplorateur.etapeMtu(meilleur, 1420, 1500, emptySet(), emptySet()).map { it.mtu })
        assertTrue(ApnExplorateur.etapeMtu(meilleur, 1500, 1500, emptySet(), emptySet()).isEmpty())
        assertEquals(listOf(1440, 1400), ApnExplorateur.etapeMtu(meilleur, null, null, emptySet(), emptySet()).map { it.mtu })
    }

    @Test fun commandeEtDecision() {
        val c = Config("orange.fr", "IPV6", 1420, "orange", "orange", 1, "default,supl", mmsc = "http://mms.orange.fr")
        val cmd = ApnExplorateur.commande(c, "JARVIS-Essai", "20801", 1)
        assertTrue(cmd, cmd.contains("'apn:s:orange.fr'") && cmd.contains("protocol:s:IPV6") && cmd.contains("--bind mtu:i:1420") &&
            cmd.contains("'user:s:orange'") && cmd.contains("authtype:i:1") && cmd.contains("'mmsc:s:http://mms.orange.fr'") && cmd.contains("'mnc:s:01'"))
        assertFalse(ApnExplorateur.commande(c, "x", "20801", 2).contains("mtu"))
        // Garder seulement si nettement meilleur en moyenne.
        assertTrue(ApnExplorateur.garder(listOf(10.0, 11.0), listOf(13.0, 12.5)))
        assertFalse(ApnExplorateur.garder(listOf(10.0, 11.0), listOf(11.0, 11.5)))
        assertFalse(ApnExplorateur.garder(listOf(10.0), listOf(0.0)))
        assertTrue(ApnExplorateur.garder(listOf(0.0), listOf(5.0)))
    }

    @Test fun memoire() {
        val t = 1_700_000_000_000L
        val l = listOf(ApnExplorateur.Souvenir("a|IP|0", 0.0, false, t), ApnExplorateur.Souvenir("b|IP|0", 8.0, true, t),
            ApnExplorateur.Souvenir("c|IP|0", 0.0, false, t - 40L * 86_400_000L))
        assertEquals(l, ApnExplorateur.lireMemoire(ApnExplorateur.ecrireMemoire(l)))
        assertEquals(setOf("a|IP|0"), ApnExplorateur.echecsRecents(l, t + 1000))
        val c = Config("free", "IP", 1420, type = "default,supl")
        assertEquals(c, ApnExplorateur.configDepuisJson(org.json.JSONObject().put("apn", "free").put("protocole", "IP").put("mtu", 1420).put("type", "default,supl")))
    }
}
