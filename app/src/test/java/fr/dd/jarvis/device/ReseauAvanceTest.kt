package fr.dd.jarvis.device

import fr.dd.jarvis.device.ReseauHistorique.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReseauAvanceTest {
    private val r = ReseauReglages

    @Test fun masqueDesReseaux() {
        val bin = java.lang.Long.toBinaryString(r.PRESET_5G)
        assertEquals(r.PRESET_5G to true, r.lireMasque(bin))
        assertEquals(r.PRESET_5G to true, r.lireMasque("Allowed network types: $bin"))
        assertEquals(r.PRESET_4G to false, r.lireMasque(r.PRESET_4G.toString()))
        assertEquals(true, r.lireMasque("GPRS|EDGE|UMTS|LTE|NR")!!.first and r.BIT_NR != 0L)
        assertNull(r.lireMasque("Unknown command: get-allowed-network-types-for-users"))
        assertNull(r.lireMasque("java.lang.SecurityException: denied"))
        // 4G : seule la 5G est retirée ; 5G : ajoutée ; jamais sans la 4G.
        val m = r.PRESET_5G or (1L shl 17)   // + IWLAN (appels Wi-Fi) : conservé
        assertEquals(m and r.BIT_NR.inv(), r.appliquer(m, "4g"))
        assertEquals(m, r.appliquer(m and r.BIT_NR.inv(), "5g"))
        assertTrue(r.appliquer(0b111L, "4g") and (1L shl 12) != 0L)
        assertEquals("5G / 4G automatique", r.libelle(r.PRESET_5G))
        assertEquals("4G (5G désactivée)", r.libelle(r.PRESET_4G))
        assertEquals(r.PRESET_4G, java.lang.Long.parseLong(r.ecrireMasque(r.PRESET_4G, true), 2))
    }

    @Test fun historique() {
        val h = 3_600_000L
        val t0 = 1_700_000_000_000L
        assertTrue(ReseauHistorique.doitNoter(null, Point(t0, "4G", -90, 1)))
        assertFalse(ReseauHistorique.doitNoter(Point(t0, "4G", -90, 1), Point(t0 + 60_000, "4G", -91, 1)))
        assertTrue(ReseauHistorique.doitNoter(Point(t0, "4G", -90, 1), Point(t0 + 60_000, "4G", -108, 3)))
        assertTrue(ReseauHistorique.doitNoter(Point(t0, "4G", -90, 1), Point(t0 + ReseauHistorique.PAS_MS, "4G", -90, 1)))
        val pts = (0 until 40).map { i ->
            if (i < 20) Point(t0 + i * 5 * 60_000L, "4G", -85, 1, 47.32, 5.04)       // Dijon : bon
            else Point(t0 + i * 5 * 60_000L, "4G", -112, 4, 47.03, 4.84)             // Beaune : très faible
        }
        assertEquals(pts, ReseauHistorique.lire(ReseauHistorique.serialiser(pts)))
        val s = ReseauHistorique.synthese(pts, t0 + 40 * 5 * 60_000L, 7) { la, _ -> if (la < 47.1) "Beaune" else "Dijon" }
        assertTrue(s, s.contains("Beaune : mauvais 100 %") && !s.contains("Dijon :"))
        assertTrue(s, s.contains("bon 50 %"))
        assertTrue(ReseauHistorique.synthese(pts.take(2), t0, 7).contains("Pas encore assez"))
    }

    @Test fun verdictDuDebit() {
        assertTrue(TestDebit.verdict(TestDebit.Resultat(30, 40.0, 10.0, 0, true)).contains("excellent"))
        assertTrue(TestDebit.verdict(TestDebit.Resultat(220, 2.0, 0.5, 0, true)).let { it.contains("lent") && it.contains("latence élevée") })
        assertTrue(TestDebit.verdict(TestDebit.Resultat(null, null, null, 0, true)).contains("Pas de connexion"))
    }

    @Test fun nouvellesPhrases() {
        val attendus = mapOf(
            "passe en 4G" to "type_reseau:4g", "force la 4G" to "type_reseau:4g", "désactive la 5G" to "type_reseau:4g",
            "remets la 5G" to "type_reseau:5g", "active la 5G" to "type_reseau:5g", "remets le réseau comme avant" to "type_reseau:avant",
            "teste mon débit" to "debit", "fais un speedtest" to "debit", "quel débit j'ai" to "debit", "mesure la vitesse de ma connexion" to "debit",
            "où est-ce que je capte mal" to "historique", "historique du réseau" to "historique", "mes cartes SIM" to "sim",
            "comment est mon réseau" to "diagnostic",
        )
        attendus.forEach { (p, a) -> assertEquals(p, a, ReseauMobile.intention(p)) }
        for (p in listOf("quelle est la vitesse du vent", "quel est le débit de mon compte", "teste le débit de la Freebox", "change le code PIN de ma SIM",
                "comment est le wifi", "mets France 2", "passe JARVIS en mode performance"))
            assertNull(p, ReseauMobile.intention(p))
    }
}
