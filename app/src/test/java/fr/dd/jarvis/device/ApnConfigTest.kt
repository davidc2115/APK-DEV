package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApnConfigTest {
    private val free = ReseauMobile.apnRecommande("20815", "Free")!!

    @Test fun lectureDesApn() {
        val sortie = """
            Row: 0 _id=2745, name=Free, numeric=20815, mcc=208, mnc=15, apn=free, user=NULL, type=default,supl, protocol=IPV4V6, carrier_enabled=1
            Row: 1 _id=2746, name=Free MMS, numeric=20815, mcc=208, mnc=15, apn=mmsfree, mmsc=http://mms.free.fr, type=mms, protocol=IP, carrier_enabled=1
        """.trimIndent()
        val l = ApnConfig.lignes(sortie)
        assertEquals(2, l.size)
        assertEquals("mmsfree", l[1]["apn"]); assertEquals("http://mms.free.fr", l[1]["mmsc"]); assertEquals("default,supl", l[0]["type"])
        assertTrue(ApnConfig.refuse("Error while accessing provider:telephony\njava.lang.SecurityException: No permission to access APN settings"))
        // APN conformes : rien à signaler.
        val an = ApnConfig.analyser(l[0], l, free)
        assertTrue(an.toString(), an.problemes.isEmpty() && an.ameliorations.isEmpty())
    }

    @Test fun problemesEtAmeliorations() {
        val faux = mapOf("_id" to "1", "name" to "Internet", "apn" to "internet", "type" to "default", "protocol" to "IP", "user" to "free")
        val an = ApnConfig.analyser(faux, listOf(faux), free)
        val p = an.problemes.joinToString("\n"); val a = an.ameliorations.joinToString("\n")
        assertTrue(p, p.contains("« internet » au lieu de « free »") && p.contains("Aucun APN MMS"))
        assertTrue(a, a.contains("IPV4V6") && a.contains("supl") && a.contains("nom d'utilisateur"))
        val mmsFaux = mapOf("_id" to "2", "apn" to "mmsfree", "type" to "mms", "mmsc" to "http://mms.autre.fr")
        assertTrue(ApnConfig.analyser(null, listOf(faux, mmsFaux), free).problemes.any { it.contains("MMSC différent") })
        val limite = mapOf("_id" to "3", "apn" to "free", "type" to "default,supl", "protocol" to "IPV4V6", "network_type_bitmask" to "4", "carrier_enabled" to "0")
        val p2 = ApnConfig.analyser(limite, listOf(limite), null).problemes.joinToString("\n")
        assertTrue(p2, p2.contains("DÉSACTIVÉ") && p2.contains("2G / 3G"))
    }

    @Test fun commandesDeCreation() {
        val c = ApnConfig.commandesAjout(free, "20815", avecMms = true)
        assertEquals(2, c.size)
        assertTrue(c[0].second, c[0].second.contains("'apn:s:free'") && c[0].second.contains("'type:s:default,supl'") && c[0].second.contains("protocol:s:IPV4V6") && c[0].second.contains("'mnc:s:15'"))
        assertTrue(c[1].second, c[1].second.contains("'apn:s:mmsfree'") && c[1].second.contains("'mmsc:s:http://mms.free.fr'") && c[1].first.endsWith("-MMS"))
        val bouygues = ApnConfig.commandesAjout(ReseauMobile.apnRecommande("20820", "Bouygues Telecom")!!, "20820", avecMms = false)
        assertEquals(1, bouygues.size)
        assertTrue(bouygues[0].second.contains("'type:s:default,supl,mms'") && bouygues[0].second.contains("'mmsproxy:s:62.201.129.226'"))
        val orange = ApnConfig.commandesAjout(ReseauMobile.apnRecommande("20801", "Orange F")!!, "20801", avecMms = true)
        assertTrue(orange[0].second.contains("'user:s:orange'") && orange[0].second.contains("authtype:i:1"))
        assertTrue(c.all { it.first.startsWith(ApnConfig.PREFIXE + "-") })
    }

    @Test fun phrases() {
        assertEquals("apn_configurer", ReseauMobile.intention("configure l'APN"))
        assertEquals("apn_configurer", ReseauMobile.intention("règle l'APN"))
        assertEquals("apn_optimiser", ReseauMobile.intention("améliore mon APN"))
        assertEquals("apn_restaurer", ReseauMobile.intention("remets l'APN d'origine"))
        assertEquals("apn", ReseauMobile.intention("montre-moi l'APN"))
        assertEquals("apn", ReseauMobile.intention("vérifie mon APN"))
    }
}
