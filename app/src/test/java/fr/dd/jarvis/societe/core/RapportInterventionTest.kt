package fr.dd.jarvis.societe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RapportInterventionTest {
    @Test fun groupesDeSeriesSuccessifsSontCumules() {
        val resultat = MesuresSeries.analyser("2 séries à 100 volts et 5 séries à 200 volts")

        assertEquals((1..7).toList(), resultat.series.map { it.numero })
        assertEquals(listOf("100 V", "100 V"), resultat.series.take(2).map { it.valeur })
        assertEquals(listOf("200 V", "200 V", "200 V", "200 V", "200 V"), resultat.series.drop(2).map { it.valeur })
    }

    @Test fun groupesSurPlusieursLignesSontCumulesSansEcraserLesPremiers() {
        val resultat = MesuresSeries.analyser("2 séries à 100 V\n5 séries à 200 V")

        assertEquals((1..7).toList(), resultat.series.map { it.numero })
        assertEquals("100 V", resultat.series[0].valeur)
        assertEquals("100 V", resultat.series[1].valeur)
        assertTrue(resultat.series.drop(2).all { it.valeur == "200 V" })
    }

    @Test fun numerosExplicitesCorrigentUneSerieDejaDetectee() {
        val resultat = MesuresSeries.analyser("5 séries à 200 V\nSérie 3 : 185 V")

        assertEquals(5, resultat.series.size)
        assertEquals("185 V", resultat.series.first { it.numero == 3 }.valeur)
    }
}
