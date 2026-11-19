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


    @Test fun mesuresDeChaqueOnduleurRestentSepareesAvecLesNombresEnLettres() {
        val source = """
            Onduleur 1 : 16 séries à 775 volts et deux séries à 805 volts
            de légères tensions à la terre dues à la pluie mais rien d'anormal
            Onduleur 2 : à quatre séries à environ 810 volts, et 14 séries environ 770 volts
            de légères tensions à la terre dues à la pluie mais rien d'anormal
        """.trimIndent()

        val groupes = MesuresSeries.parOnduleur(source)
        assertEquals(listOf("Onduleur 1", "Onduleur 2"), groupes.map { it.first })
        assertEquals(18, groupes[0].second.series.size)
        assertEquals("775 V", groupes[0].second.series.first().valeur)
        assertEquals("805 V", groupes[0].second.series.last().valeur)
        assertEquals(18, groupes[1].second.series.size)
        assertEquals("≈ 810 V", groupes[1].second.series.first().valeur)
        assertEquals("≈ 770 V", groupes[1].second.series.last().valeur)
        assertTrue(groupes.all { g -> g.second.autres.any { it.contains("pluie") } })
    }

    @Test fun blocsOnduleursRecuperentLesLignesDeContinuation() {
        val blocs = OnduleursIntervention.blocs(
            """
                Onduleur 1 : Huawei
                16 séries à 775 V
                Onduleur 2 : SMA
                14 séries à 770 V
            """.trimIndent()
        )
        assertEquals(2, blocs.size)
        assertTrue(blocs[0].contenu.contains("16 séries"))
        assertTrue(blocs[1].contenu.contains("14 séries"))
    }

    @Test fun lesActionsSuivantLesMesuresNeSontPasAjouteesAuBlocOnduleur() {
        val blocs = OnduleursIntervention.blocs(
            """
                Onduleur 2 : 14 séries à 770 V
                de légères tensions à la terre
                remise en service de l'installation OK
                nettoyage des ventilations
            """.trimIndent()
        )
        assertEquals(1, blocs.size)
        assertTrue(blocs.single().contenu.contains("légères tensions"))
        assertTrue(!blocs.single().contenu.contains("remise en service"))
        assertTrue(!blocs.single().contenu.contains("nettoyage"))
    }
    @Test fun mesuresSontRecupereesMemeSiLeModeleLesPlaceDansLeChampOnduleurs() {
        val onduleursCommeTableau = """['Onduleur 1 : Sungrow 125cx', 'Onduleur 2 : à quatre séries à environ 810 volts, et 14 séries environ 770 volts']"""
        val source = onduleursCommeTableau + """
            Onduleur 1 : 16 séries à 775 volts et deux séries à 805 volts
            Onduleur 2 : quatre séries à environ 810 volts et 14 séries environ 770 volts
        """.trimIndent()
        val groupes = MesuresSeries.parOnduleur(source)
        assertEquals(listOf("Onduleur 1", "Onduleur 2"), groupes.map { it.first })
        assertEquals(18, groupes[0].second.series.size)
        assertEquals(18, groupes[1].second.series.size)
        assertTrue(groupes[0].second.series.first().valeur.contains("775"))
        assertTrue(groupes[0].second.series.last().valeur.contains("805"))
        assertTrue(groupes[1].second.series.first().valeur.contains("810"))
        assertTrue(groupes[1].second.series.last().valeur.contains("770"))
    }

    @Test fun enteteOnduleursNeContientQueLesModelesEtPasLesSeries() {
        val source = """
            Onduleur 1 : Sungrow 125cx
            Onduleur 2 : Sungrow 125cx
            Onduleur 1 : 16 séries à 775 volts et deux séries à 805 volts
            Onduleur 2 : quatre séries à environ 810 volts et 14 séries environ 770 volts
        """.trimIndent()
        val entete = OnduleursIntervention.identites(source)
        assertEquals(listOf("Onduleur 1 : Sungrow 125cx", "Onduleur 2 : Sungrow 125cx"), entete)
        val groupes = MesuresSeries.parOnduleur(source)
        assertEquals(2, groupes.size)
        assertEquals(18, groupes[0].second.series.size)
        assertEquals(18, groupes[1].second.series.size)
    }

    @Test fun renduGardeLesTableauxDeChaqueOnduleurQuandLesIdentitesSontStockeesAilleurs() {
        val site = Site("site-mesures", "Toiture")
        val client = Client("client-mesures", "Client mesures", sites = listOf(site))
        val dictee = """
            Onduleur 1 : 16 séries à 775 volts et deux séries à 805 volts
            de légères tensions à la terre dues à la pluie mais rien d'anormal
            Onduleur 2 : quatre séries à environ 810 volts et 14 séries environ 770 volts
            de légères tensions à la terre dues à la pluie mais rien d'anormal
        """.trimIndent()
        val intervention = Intervention(
            id = "inter-mesures", date = "2026-11-19", description = "Mesures des onduleurs",
            mesures = dictee,
            onduleurs = """['Onduleur 1 : Sungrow 125cx', 'Onduleur 2 : Sungrow 125cx']"""
        )
        val rapport = Rendu.rendreIntervention(SocieteData(clients = listOf(client)), client, site, intervention)

        assertTrue(rapport.contains("Onduleur 1 : Sungrow 125cx"))
        assertTrue(rapport.contains("Onduleur 2 : Sungrow 125cx"))
        assertTrue(rapport.contains("Mesures par onduleur"))
        assertTrue(rapport.contains("775 V"))
        assertTrue(rapport.contains("805 V"))
        assertTrue(rapport.contains("≈ 810 V"))
        assertTrue(rapport.contains("≈ 770 V"))
        assertTrue(rapport.contains("| 18 |"))
        assertTrue(!rapport.contains("Sungrow 125cx',"))
    }

    @Test fun propageUniteVoltageOmetteeDansLeMemeBlocSansCompterLeTotal() {
        val resultat = MesuresSeries.analyser(
            "16 séries à 775 volts et deux séries à 805 (18 séries au total)"
        )
        assertEquals(18, resultat.series.size)
        assertEquals("775 V", resultat.series[0].valeur)
        assertEquals("805 V", resultat.series[16].valeur)
        assertEquals("805 V", resultat.series[17].valeur)
    }


    @Test fun uniteDejaPresenteNestPasDupliqueeEtLeTotalNestPasUneMesure() {
        val resultat = MesuresSeries.analyser(
            "16 séries à 775 volts et deux séries à 805 volts (18 séries au total)"
        )

        assertEquals(18, resultat.series.size)
        assertEquals((1..16).toList(), resultat.series.take(16).map { it.numero })
        assertTrue(resultat.series.take(16).all { it.valeur == "775 V" })
        assertEquals("805 V", resultat.series[16].valeur)
        assertEquals("805 V", resultat.series[17].valeur)
    }

    @Test fun rapportAfficheChaqueSerieSurSaPropreLignePourChaqueOnduleur() {
        val client = Client("c-test", "Client test")
        val site = Site("s-test", "Installation")
        val dict = """
            Onduleur 1 : 16 séries à 775 volts et deux séries à 805 volts
            Onduleur 2 : quatre séries à environ 810 volts et 14 séries environ 770 volts
        """.trimIndent()
        val intervention = Intervention(
            id = "i-test", date = "2026-11-19", description = "Mesures",
            mesures = dict, onduleurs = "Onduleur 1 : Sungrow\nOnduleur 2 : Sungrow"
        )
        val rapport = Rendu.rendreIntervention(SocieteData(clients = listOf(client)), client, site, intervention)
        for (n in 1..16) assertTrue("Série $n de l'onduleur 1 absente", rapport.contains("| $n | 775 V |"))
        assertTrue(rapport.contains("| 17 | 805 V |"))
        assertTrue(rapport.contains("| 18 | 805 V |"))
        for (n in 1..4) assertTrue("Série $n de l'onduleur 2 absente", rapport.contains("| $n | ≈ 810 V |"))
        for (n in 5..18) assertTrue("Série $n de l'onduleur 2 absente", rapport.contains("| $n | ≈ 770 V |"))
    }

}
