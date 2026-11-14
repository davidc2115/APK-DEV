package fr.dd.jarvis.coffre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SauvegardesTest {
    @Test fun rotation() {
        val auj = LocalDate.of(2026, 11, 14)
        val noms = (0 until 400).map { "societe-" + auj.minusDays(it.toLong()) + "_0300.jsauv" }
        val g = Sauvegardes.aGarder(noms, auj)
        // 31 jours récents (0 à 30) + 1 par mois sur les 12 mois précédents (dont certains déjà comptés).
        assertTrue(g.contains("societe-2026-11-14_0300.jsauv") && g.contains("societe-2026-10-15_0300.jsauv"))
        assertFalse(g.contains("societe-2026-10-13_0300.jsauv"))
        assertTrue(g.contains("societe-2026-09-30_0300.jsauv"))   // la plus récente de septembre
        assertFalse(g.contains("societe-2026-09-29_0300.jsauv"))
        assertFalse(g.contains("societe-2025-10-31_0300.jsauv"))  // plus d'un an
        assertTrue(g.size in 40..45)
        // Peu de sauvegardes anciennes : les 3 dernières restent toujours.
        val vieilles = listOf("societe-2024-01-01_0300.jsauv", "societe-2024-02-01_0300.jsauv", "societe-2024-03-01_0300.jsauv", "societe-2023-01-01_0300.jsauv")
        assertEquals(3, Sauvegardes.aGarder(vieilles, auj).size)
        assertEquals(LocalDate.of(2026, 3, 2), Sauvegardes.dateDe("societe-2026-03-02_2215.jsauv"))
    }
}
