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
    @Test fun rapportConserveTousLesOnduleurs() {
        val site = Site("site-1", "Toiture", onduleur = "Onduleur historique")
        val client = Client("client-1", "Client test", sites = listOf(site))
        val intervention = Intervention(
            id = "inter-1",
            date = "2026-11-19",
            description = "Contrôle des onduleurs",
            onduleurs = "SMA Sunny Tripower\nFronius Symo"
        )
        val rapport = Rendu.rendreIntervention(SocieteData(clients = listOf(client)), client, site, intervention)

        assertTrue(rapport.contains("SMA Sunny Tripower"))
        assertTrue(rapport.contains("Fronius Symo"))
        assertTrue(!rapport.contains("Onduleur historique"))
    }


    @Test fun tousLesOnduleursNumerotesSontRecuperesDepuisLaDictee() {
        val source = "Onduleur 1 : Huawei SUN2000, onduleur 2 : SMA Tripower, onduleur 3 : Fronius Symo"
        val lignes = OnduleursIntervention.numerotes(source)

        assertEquals(3, lignes.size)
        assertTrue(lignes[0].contains("Onduleur 1") && lignes[0].contains("Huawei SUN2000"))
        assertTrue(lignes[1].contains("Onduleur 2") && lignes[1].contains("SMA Tripower"))
        assertTrue(lignes[2].contains("Onduleur 3") && lignes[2].contains("Fronius Symo"))
    }

    @Test fun completeLesOnduleursOubliesParLeResumeIA() {
        val source = "Onduleur 1 : Huawei SUN2000\\nOnduleur 2 : SMA Tripower"
        val resultat = OnduleursIntervention.completer("Onduleur 1 : Huawei SUN2000", source)

        assertTrue(resultat.contains("Onduleur 1"))
        assertTrue(resultat.contains("Onduleur 2"))
        assertTrue(resultat.contains("SMA Tripower"))
    }

}
