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

        // Onduleurs en tableau aligné.
        assertTrue(rapport, rapport.contains("| Onduleur 1 | Sungrow 125cx |"))
        assertTrue(rapport, rapport.contains("| Onduleur 2 | Sungrow 125cx |"))
        assertTrue(rapport.contains("Mesures par onduleur"))
        assertTrue(rapport.contains("775 V"))
        assertTrue(rapport.contains("805 V"))
        assertTrue(rapport.contains("≈ 810 V"))
        assertTrue(rapport.contains("≈ 770 V"))
        assertTrue(rapport.contains("| Série 18 |"))
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
        val entete = "| Série | Valeur | Série | Valeur |"
        assertTrue("Le tableau doit afficher deux paires de colonnes", rapport.contains(entete))
        assertEquals("Les 18 séries de chaque onduleur tiennent sur 9 lignes", 9,
            rapport.lines().count { it.startsWith("|") && (it.contains("775 V") || it.contains("805 V")) })
        assertEquals("Le second onduleur tient également sur 9 lignes", 9,
            rapport.lines().count { it.startsWith("|") && (it.contains("810 V") || it.contains("770 V")) })
        assertTrue("Le tableau de secours ne doit pas être ajouté en double",
            !rapport.contains("Détail individuel des séries par onduleur"))
        for (n in 1..16) assertTrue("Série $n de l'onduleur 1 absente", rapport.contains("| Série $n | 775 V |"))
        assertTrue(rapport.contains("| Série 17 | 805 V |"))
        assertTrue(rapport.contains("| Série 18 | 805 V |"))
        for (n in 1..4) assertTrue("Série $n de l'onduleur 2 absente", rapport.contains("| Série $n | ≈ 810 V |"))
        for (n in 5..18) assertTrue("Série $n de l'onduleur 2 absente", rapport.contains("| Série $n | ≈ 770 V |"))
    }


    /** Modèle personnalisé du type de celui de Dd : un onduleur dans le tableau d'en-tête, pas de section de séries. */
    private val MODELE_PERSO = """
# 📋 {{titre}}

**{{client}}** — {{installation}}

| | |
|---|---|
| 📅 **Date** | {{date}} |
| 📌 **Statut** | {{statut}} |
{{#adresse}}| 📍 **Adresse** | {{adresse}} |
{{/adresse}}| ⚡ **Onduleur** | {{onduleur}} |
{{#kwc}}| ☀️ **Puissance** | {{kwc}} kWc |
{{/kwc}}
{{#description}}
## 📝 Description
{{description}}
{{/description}}
{{#a_actions}}
## 🛠️ Actions réalisées
{{#actions}}- {{emoji}} {{action}}
{{/actions}}{{/a_actions}}
{{#prochaine_maintenance}}
## 📅 Prochaine maintenance
{{prochaine_maintenance}}
{{/prochaine_maintenance}}
{{#notes}}
## 🗒️ Notes
{{notes}}
{{/notes}}
""".trim()

    @Test fun sixOnduleursEnTableauAligneSansCasserLEntete() {
        val site = Site("s-lapree", "Installation 2", adresse = "Mairey, 21320 Mont-Saint-Jean", kwc = 100.0)
        val client = Client("c-lapree", "lapree", sites = listOf(site))
        val onduleurs = (1..6).joinToString("\n") { "Onduleur $it : marque SMA" }
        val dictee = """
            onduleur 1 : 4 séries à 750 volts
            onduleur numéro deux : quatre séries à environ 760 volts
            onduleur 3 : 3 séries à 745 V
        """.trimIndent()
        val inter = Intervention("i-lapree", "2026-11-27", "maintenance", "Maintenance préventive", actions = "coffret AC\nresserrage ok",
            mesures = dictee, onduleurs = onduleurs, prochaineMaintenance = "2026-11-27", notes = "RAS")
        val rapport = Rendu.rendreIntervention(SocieteData(clients = listOf(client), modeles = mapOf("rapport" to MODELE_PERSO)), client, site, inter)

        // L'en-tête reste un tableau : la case Onduleur tient sur une ligne, plus de lignes orphelines.
        assertTrue(rapport, rapport.contains("| ⚡ **Onduleur** | 6 onduleurs · SMA |"))
        assertTrue(rapport, rapport.lines().none { it.trim() == "Onduleur 2 : marque SMA" })
        // Tableau aligné des onduleurs, juste après l'en-tête.
        assertTrue(rapport, rapport.contains("| Onduleur | Marque |"))
        for (n in 1..6) assertTrue(rapport, rapport.contains("| Onduleur $n | SMA |"))
        assertTrue(rapport.indexOf("Onduleurs installés") < rapport.indexOf("## 📝 Description"))
        // Séries classées par onduleur (même dictées « numéro deux » / « quatre »), avant la prochaine maintenance.
        assertTrue(rapport, rapport.contains("### 🔌 Onduleur 1 · SMA"))
        assertTrue(rapport, rapport.contains("### 🔌 Onduleur 2 · SMA"))
        assertTrue(rapport, rapport.contains("| Série 1 | 750 V |") && rapport.contains("| Série 4 | ≈ 760 V |") && rapport.contains("| Série 3 | 745 V |"))
        assertTrue(rapport.indexOf("Mesures par onduleur") < rapport.indexOf("Prochaine maintenance"))
        // Prochaine maintenance : un an après, pas le jour même.
        assertTrue(rapport, rapport.contains("27/11/2027"))
    }

    @Test fun onduleursAvecMarqueModeleEtNumeroDeSerie() {
        val f = OnduleursIntervention.fiches("Onduleur 1 : marque SMA modèle STP 25000TL n° de série 1900123456\nOnduleur 2 : Huawei SUN2000-100KTL")
        assertEquals(2, f.size)
        assertEquals("SMA", f[0].marque); assertEquals("STP 25000TL", f[0].modele); assertEquals("1900123456", f[0].serie)
        val t = OnduleursIntervention.tableau(f)
        assertTrue(t, t.startsWith("| Onduleur | Marque | Modèle | N° de série |"))
        assertTrue(t, t.contains("| Onduleur 1 | SMA | STP 25000TL | 1900123456 |"))
        assertTrue(t, t.contains("| Onduleur 2 | Huawei SUN2000-100KTL |  |  |"))
    }

    @Test fun onduleurUnPeuChaudNEstPasUnNumero() {
        assertTrue(OnduleursIntervention.blocs("l'onduleur un peu chaud au toucher").isEmpty())
        assertEquals(listOf(1), OnduleursIntervention.blocs("onduleur numéro un : 2 séries à 700 V").map { it.numero })
        assertEquals(listOf(3), OnduleursIntervention.blocs("onduleur trois : 2 séries à 700 V").map { it.numero })
    }

    @Test fun prochaineMaintenanceUnAnApres() {
        assertEquals("2027-11-27", ProchaineMaintenance.planifier("2026-11-27", "", "maintenance"))
        assertEquals("2027-11-27", ProchaineMaintenance.planifier("2026-11-27", "2026-11-27", "maintenance"))
        assertEquals("2027-03-15", ProchaineMaintenance.planifier("2026-11-27", "2027-03-15", "maintenance"))
        assertEquals("", ProchaineMaintenance.planifier("2026-11-27", "", "panne"))
        assertEquals("2027-11-27", ProchaineMaintenance.effective("2026-11-27", "2026-11-27"))
        assertEquals("", ProchaineMaintenance.effective("2025-01-10", ""))
        assertEquals("2027-06-01", ProchaineMaintenance.effective("2026-11-27", "2027-06-01"))
    }

    /** Rapport de lapree (27/11) : séries rangées par l'IA dans les actions, demandes du chat recopiées dans les mesures. */
    @Test fun seriesDansLesActionsEtDemandesDuChatRetirees() {
        val site = Site("s2", "Installation 2", kwc = 100.0)
        val client = Client("c-lapree", "lapree", sites = listOf(site))
        val actions = """
            Coffret AC : resserrage OK
            Onduleur 5 :
            5 séries de 517 V
            Poignée ESS : OK
            Onduleur 6 :
            Séries : 352, 352 V et 377, 358 V
            Poignée ESS : OK
            Nettoyage et maintenance :
            Nettoyage des ventilateurs : OK
            Remise en service : OK
        """.trimIndent()
        val chat = "cre 1 nouveau client lapree avec 1 installation 36kwc et 3 onduleur sma pour l adresse utilise ma localisation actuelle\n" +
            "lance l itineraire jusqu a mon domicile\nrelance la recherche courrier analyse de nouveau les courriers\nmode couche"
        val inter = Intervention("i-l", "2026-11-27", "maintenance", "Maintenance préventive", actions = actions, mesures = chat,
            onduleurs = (1..6).joinToString("\n") { "Onduleur $it : marque SMA" })
        val rapport = Rendu.rendreIntervention(SocieteData(clients = listOf(client)), client, site, inter)
        // Les demandes faites au chat n'apparaissent nulle part.
        assertTrue(rapport, !rapport.contains("itineraire") && !rapport.contains("courrier") && !rapport.contains("mode couche"))
        // Un tableau par onduleur, au format « Série N | valeur ».
        assertTrue(rapport, rapport.contains("### 🔌 Onduleur 5 · SMA") && rapport.contains("### 🔌 Onduleur 6 · SMA"))
        for (n in 1..5) assertTrue(rapport, rapport.contains("| Série $n | 517 V |"))
        assertTrue(rapport, rapport.contains("| Série 1 | 352 V | Série 2 | 352 V |"))
        assertTrue(rapport, rapport.contains("| Série 3 | 377 V | Série 4 | 358 V |"))
        assertTrue(rapport, rapport.contains("📝 Poignée ESS : OK"))
        // Les séries ne sont plus répétées dans les actions ; les autres actions restent.
        assertTrue(rapport, !rapport.contains("5 séries de 517 V") && !rapport.contains("Séries : 352"))
        assertTrue(rapport, rapport.contains("Nettoyage des ventilateurs : OK") && rapport.contains("## ⚡ Coffret AC\n- ✅ Resserrage OK"))
        assertTrue(rapport, rapport.contains("## 🧹 Nettoyage et maintenance\n- ✅ Nettoyage des ventilateurs : OK\n- ✅ Remise en service : OK"))
    }

    @Test fun listeDeTensionsParSerie() {
        val r = MesuresSeries.analyser("Séries : 352, 352 V et 377, 358 V")
        assertEquals(listOf("352 V", "352 V", "377 V", "358 V"), r.series.map { it.valeur })
        // « Série 7 : 320 V » reste une seule série.
        assertEquals(listOf(7), MesuresSeries.analyser("Série 7 : 320 V").series.map { it.numero })
    }

    /** Bon de lapree du 27/11 : texte complet de Dd, actions résumées par l'IA en une phrase. */
    private val TEXTE_LAPREE = """Actions: Coffret AC:

Serrage: OK

• Parafoudre: OK

Coffret Enedis:

• Serrage: OK

• Disjoncteur: OK

Vérification de terre:

• Pas de défaut de terre

Onduleur 1

6 séries de 520 V

Poignée ESS: OK Onduleur 2:

• 6 séries de 500 V

Poignée ESS: OK

Onduleur 3:

5 séries de 520 V

Poignée ESS: OK Onduleur 4:

5 séries de 520 V

Poignée ESS: OK

Onduleur 5:

• 5 séries de 517 V

• Poignée ESS: OK

Onduleur 6:

Séries: 352, 352 V et 377, 358 V
Poignée ESS: OK

Nettoyage et maintenance:

Nettoyage des ventilateurs : OK

• Nettoyage des onduleurs: OK

Remise en service: OK

Compteur Linky:

• Production: 1021418

• Non conso: 0"""

    @Test fun bonCompletAvecLesSixOnduleursEtLeDetailDesActions() {
        val site = Site("s2", "Installation 2", kwc = 100.0)
        val client = Client("c", "lapree", sites = listOf(site))
        val inter = Intervention("i", "2026-11-27", "maintenance", "Intervention de maintenance préventive",
            actions = "Contrôle des coffrets (AC et Enedis), vérification de la terre, contrôle et mesures des 6 onduleurs, nettoyage des ventilateurs et onduleurs, remise en service",
            mesures = TEXTE_LAPREE, onduleurs = (1..6).joinToString("\n") { "Onduleur $it : SMA" })
        val r = Rendu.rendreIntervention(SocieteData(clients = listOf(client)), client, site, inter)
        for (n in 1..6) assertTrue("Onduleur $n absent", r.contains("### 🔌 Onduleur $n · SMA"))
        assertTrue(r, r.contains("| Série 3 | 377 V | Série 4 | 358 V |"))
        // Observation de chaque onduleur, telle qu'écrite (y compris « Poignée ESS: OK Onduleur 2: » sur une ligne).
        assertEquals(r, 6, Regex("📝 Poignée ESS: OK").findAll(r).count())
        // Détail complet des actions, sans les séries déjà en tableaux.
        // Chaque rubrique dictée est une vraie section (pas une ligne en gras dans « Actions réalisées »).
        listOf("## ⚡ Coffret AC", "## ⚡ Coffret Enedis", "Disjoncteur: OK", "## 🌍 Vérification de terre", "Pas de défaut de terre",
            "## 🧹 Nettoyage et maintenance", "Nettoyage des ventilateurs : OK", "Remise en service: OK", "Production: 1021418", "Non conso: 0")
            .forEach { assertTrue("« $it » absent :\n$r", r.contains(it)) }
        assertTrue(r, !r.contains("**Coffret AC**") && !r.contains("## 🛠️ Actions réalisées"))
        assertTrue(r, r.contains("## 📟 Compteur Linky\n- 📏 Production: 1021418\n- 📏 Non conso: 0"))
        // Les relevés déjà dans leur catégorie ne sont pas répétés dans « Autres mesures ».
        assertTrue(r, !r.contains("Autres mesures"))
        val actions = r.substring(r.indexOf("## ⚡ Coffret AC"), r.indexOf("## ⚡ Mesures par onduleur"))
        assertTrue(actions, !actions.contains("séries") && !actions.contains("Séries") && !actions.contains("Onduleur 1"))
        assertTrue(actions, !actions.contains("Contrôle des coffrets (AC et Enedis)"))
    }

    @Test fun observationApresLaDerniereValeurGardeeTelleQuEcrite() {
        val g = MesuresSeries.parOnduleur("Onduleur 1 : 6 séries de 520 V, Poignée ESS OK").single().second
        assertEquals(6, g.series.size)
        assertEquals(listOf("Poignée ESS OK"), g.autres)
    }

    /** Fiche de lapree après « modifie-la » (15 h) : l'historique affiché par JARVIS avait été recopié dans les actions. */
    @Test fun ficheRecopieeRetireeEtOnduleur6Lu() {
        val site = Site("s2", "Installation 2", kwc = 100.0)
        val client = Client("c", "lapree", sites = listOf(site))
        val dump = listOf("# Historique des interventions — lapree", "## Installation : Installation 2", "### 2026-11-27", "maintenance", "terminee",
            "ID #11912fb4", "Intervention de maintenance préventive sur l'installation photovoltaïque comprenant le contrôle des coffrets, le nettoyage et la vérification des onduleurs",
            "Diagnostic : Aucun problème à signaler",
            "Contrôle des coffrets (AC et Enedis), vérification de la terre, contrôle et mesures des 6 onduleurs, nettoyage des ventilateurs et onduleurs, remise en service",
            "Mesures : Onduleur 1 : 6 séries de 520 V, poignée ESS OK").joinToString("\n")
        val mesures = (1..5).joinToString("\n") { n -> "Onduleur $n: ${if (n <= 2) 6 else 5} séries de ${if (n == 2) 500 else if (n == 5) 517 else 520} V, poignée ESS OK" } +
            "\nOnduleur 6: séries 352 V et 377-358 V, poignée ESS OK\nCompteur Linky - Production: 1021418, Non conso : 0"
        val inter = Intervention("11912fb4", "2026-11-27", "maintenance",
            "Intervention de maintenance préventive sur l'installation photovoltaïque comprenant le contrôle des coffrets, le nettoyage et la vérification des onduleurs.",
            diagnostic = "Aucun problème à signaler.", actions = dump, mesures = mesures, onduleurs = (1..6).joinToString("\n") { "Onduleur $it: SMA" })
        val r = Rendu.rendreIntervention(SocieteData(clients = listOf(client)), client, site, inter)
        val actions = r.substring(r.indexOf("## 🛠️"), r.indexOf("## ⚡ Mesures par onduleur"))
        listOf("Historique", "ID #", "11912fb4", "2026-11-27", "terminee", "Diagnostic", "Mesures :", "6 séries de 520 V").forEach { assertTrue("« $it » dans les actions :\n$actions", !actions.contains(it)) }
        assertTrue(actions, actions.contains("Contrôle des coffrets (AC et Enedis)"))
        // Onduleur 6 : tableau des tensions données.
        assertTrue(r, r.contains("### 🔌 Onduleur 6 · SMA"))
        assertTrue(r, r.contains("| Série 1 | 352 V | Série 2 | 377 V |") && r.contains("| Série 3 | 358 V |"))
        assertTrue(r, r.contains("poignée ESS OK"))
        // Compteur Linky : dans les autres mesures, pas sous l'onduleur 6.
        assertTrue(r, r.contains("Compteur Linky - Production: 1021418"))
    }

    @Test fun historiqueAfficheParJarvisNEstPasUneListeDActions() {
        assertTrue(ActionsDetaillees.extraire("# Historique des interventions — lapree\n## Installation : Installation 2\nmaintenance\nterminee\nID #11912fb4\nDiagnostic : RAS\nMesures : Onduleur 1 : OK").isEmpty())
        assertEquals(15, ActionsDetaillees.extraire(TEXTE_LAPREE).filter { !it.startsWith("Onduleur") && !it.contains("séries") && !it.startsWith("Poignée") && !it.startsWith("Séries") }.size)
    }

    /** Liste envoyée SANS les séries (déjà dans les tableaux) : reprise telle quelle comme actions. */
    @Test fun listeDActionsSansLesSeries() {
        val liste = """
            Coffret AC:
            Serrage: OK
            Parafoudre: OK
            Coffret Enedis:
            Serrage: OK
            Disjoncteur: OK
            Vérification de terre:
            Pas de défaut de terre
            Nettoyage et maintenance:
            Nettoyage des ventilateurs : OK
            Nettoyage des onduleurs: OK
            Remise en service: OK
            Compteur Linky:
            Production: 1021418
            Non conso: 0
        """.trimIndent()
        assertEquals(15, ActionsDetaillees.extraire("Modifie l'intervention de lapree :\n$liste").size)
    }

    /** Modèle de Dd du 27/11 : sections « Nettoyage » et « Compteur Linky et relevés » à part, actions avec ✅. */
    private val MODELE_SECTIONS = """
# 📋 {{titre}}

**{{client}}** — {{installation}}
{{#description}}
## 📝 Description
{{description}}
{{/description}}
{{#a_actions}}
## 🛠️ Actions réalisées
{{#actions}}- ✅ {{action}}
{{/actions}}{{/a_actions}}
## 🧹 Nettoyage
- 💨 Nettoyage des ventilateurs
- 🧽 Nettoyage de l'installation
{{#a_mesures}}
## ⚡ Compteur Linky et relevés
{{#mesures}}- 📐 {{mesure}}
{{/mesures}}{{/a_mesures}}
{{#prochaine_maintenance}}
## 📆 Prochaine maintenance
{{prochaine_maintenance}}
{{/prochaine_maintenance}}
""".trim()

    /** Capture du 27/11 (16 h) : « coffret AC » / « coffret enedis » en gras au lieu de vraies catégories. */
    @Test fun rubriquesDicteesEnVraiesSections() {
        val site = Site("s2", "Installation 2", kwc = 100.0)
        val client = Client("c", "lapree", sites = listOf(site))
        val actions = """
            coffret AC :
            serrage OK
            disjoncteur OK
            parafoudre OK
            coffret enedis :
            test du disjoncteur OK
            serrage OK
            nettoyage et ventilation OK
            nettoyage de l'installation OK
            remise en service OK
            installation pleinement fonctionnel et aucun problème
        """.trimIndent()
        val inter = Intervention("i", "2026-11-27", "maintenance", "Maintenance préventive", actions = actions,
            mesures = "Compteur Linky — Production : 1021418, Non conso : 0", prochaineMaintenance = "2027-11-27")
        val r = Rendu.rendreIntervention(SocieteData(clients = listOf(client), modeles = mapOf("rapport" to MODELE_SECTIONS)), client, site, inter)
        assertTrue(r, r.contains("## ⚡ Coffret AC\n- ✅ Serrage OK\n- ✅ Disjoncteur OK\n- ✅ Parafoudre OK"))
        assertTrue(r, r.contains("## ⚡ Coffret Enedis\n- ✅ Test du disjoncteur OK\n- ✅ Serrage OK\n"))
        assertTrue(r, r.contains("## ▶️ Remise en service\n- ✅ Remise en service OK\n- ✅ Installation pleinement fonctionnel et aucun problème"))
        // Plus de rubrique en gras ni de titre « Actions réalisées » vide ; le nettoyage reste dans SA section, sans doublon.
        assertTrue(r, !r.contains("**") || !r.contains("**coffret"))
        assertTrue(r, !r.contains("Actions réalisées"))
        assertEquals(r, 1, Regex("## 🧹 Nettoyage").findAll(r).count())
        assertTrue(r, !r.contains("Nettoyage et ventilation OK") && !r.contains("Nettoyage de l'installation OK"))
        // Les lignes du modèle reçoivent le « OK » dicté.
        assertTrue(r, r.contains("- 💨 Nettoyage des ventilateurs : OK\n- 🧽 Nettoyage de l'installation : OK"))
        assertTrue(r, r.indexOf("## ⚡ Coffret AC") < r.indexOf("## 🧹 Nettoyage") && r.contains("📐 Compteur Linky — Production : 1021418"))
        // L'ordre du modèle est conservé.
        assertTrue(r, r.indexOf("## 📝 Description") < r.indexOf("## ⚡ Coffret AC") && r.indexOf("## ⚡ Compteur Linky") < r.indexOf("## 📆 Prochaine maintenance"))
    }

    @Test fun nettoyageDicteAjouteASaSectionExistante() {
        val site = Site("s2", "Installation 2")
        val client = Client("c", "lapree", sites = listOf(site))
        val inter = Intervention("i", "2026-11-27", "maintenance", "Maintenance", actions = "Coffret AC :\nSerrage : OK\nNettoyage des onduleurs : OK")
        val r = Rendu.rendreIntervention(SocieteData(clients = listOf(client), modeles = mapOf("rapport" to MODELE_SECTIONS)), client, site, inter)
        val nettoyage = r.substringAfter("## 🧹 Nettoyage").substringBefore("##")
        assertTrue(r, nettoyage.contains("- ✅ Nettoyage des onduleurs : OK") && nettoyage.contains("Nettoyage des ventilateurs"))
        assertTrue(r, r.contains("## ⚡ Coffret AC\n- ✅ Serrage : OK"))
    }

    @Test fun categoriesDesActions() {
        val c = CategoriesActions.classer(listOf("Contrôle visuel OK", "Coffret DC : sectionneur OK, fusibles OK", "Vérification de terre:", "Pas de défaut de terre",
            "Onduleur 3 :", "Nettoyage des ventilateurs OK", "Compteur Linky - Production : 1021418"))
        assertEquals(listOf("", "coffret dc", "terre", "onduleur 3", "linky"), c.map { it.cle })
        assertEquals(listOf("Sectionneur OK", "Fusibles OK"), c[1].lignes)
        assertEquals(listOf("Nettoyage des ventilateurs OK"), c[3].lignes)   // sous « Onduleur 3 », reste à l'onduleur
        assertEquals("⚠️", CategoriesActions.emojiLigne("Parafoudre HS à remplacer"))
        assertEquals("✅", CategoriesActions.emojiLigne("Pas de défaut de terre"))
        // Un résumé couvrant plusieurs domaines n'est pas rangé dans l'un d'eux.
        assertEquals(listOf(""), CategoriesActions.classer(listOf("Contrôle des coffrets, vérification de la terre, nettoyage des ventilateurs, remise en service")).map { it.cle })
    }

    /** « Ajoute OK pour le nettoyage des ventilateurs et le nettoyage de l'installation pour l'intervention lapree ». */
    @Test fun ajoutDeLignesSansPerdreLesAutres() {
        val actuelles = listOf("Coffret AC :", "Serrage OK", "Coffret Enedis :", "Test du disjoncteur OK", "nettoyage et ventilation", "nettoyage de l'installation", "remise en service OK")
        val r = ActionsDetaillees.completer(actuelles, listOf("Nettoyage des ventilateurs : OK", "Nettoyage de l'installation : OK", "Contrôle du parafoudre : OK"))
        // Sans rubrique ni domaine, la ligne nouvelle rejoint les actions générales (en tête), pas la dernière rubrique.
        assertEquals(listOf("Contrôle du parafoudre : OK", "Coffret AC :", "Serrage OK", "Coffret Enedis :", "Test du disjoncteur OK", "Nettoyage des ventilateurs : OK",
            "Nettoyage de l'installation : OK", "remise en service OK"), r)
    }

    private val ACTIONS_LAPREE = listOf("Coffret AC :", "Serrage : OK", "Disjoncteur : OK", "Contrôle parafoudre : OK", "Coffret Enedis :",
        "Test du disjoncteur : OK", "Serrage : OK", "Nettoyage & ventilation :", "Nettoyage et ventilation : OK", "Remise en service :", "Remise en service : OK")

    /** Bon de lapree (18 h) : « coffret DC : contrôle parafoudre OK, contrôle sectionneur OK, serrage OK » n'apparaissait qu'en partie. */
    @Test fun memeControleDansPlusieursRubriques() {
        val dicte = listOf("coffret DC: contrôle parafoudre OK", "contrôle sectionneur OK", "serrage OK")
        val r = ActionsDetaillees.completer(ACTIONS_LAPREE, dicte)
        // La rubrique DC est créée après le coffret Enedis, avec ses trois contrôles ; le coffret AC n'est pas touché.
        val dc = r.indexOf("coffret DC :")
        assertTrue(r.toString(), dc > r.indexOf("Coffret Enedis :") && dc < r.indexOf("Nettoyage & ventilation :"))
        assertEquals(listOf("contrôle parafoudre OK", "contrôle sectionneur OK", "serrage OK"), r.subList(dc + 1, dc + 4))
        assertEquals(ACTIONS_LAPREE.subList(0, 7), r.subList(0, 7))
        // DC déjà là avec une seule ligne : complété.
        val avecDc = ACTIONS_LAPREE + listOf("Coffret DC :", "Contrôle sectionneur : OK")
        val r2 = ActionsDetaillees.completer(avecDc, dicte)
        assertEquals(listOf("Coffret DC :", "contrôle sectionneur OK", "contrôle parafoudre OK", "serrage OK"), r2.takeLast(4))
        assertEquals(avecDc.subList(0, 7), r2.subList(0, 7))
        // Partie remplacée telle que donnée (« modifie la partie coffret DC »).
        val r3 = ActionsDetaillees.remplacerRubriques(avecDc, listOf("Coffret DC :", "Contrôle parafoudre : OK", "Contrôle sectionneur : OK", "Serrage : OK"))
        assertEquals(listOf("Coffret DC :", "Contrôle parafoudre : OK", "Contrôle sectionneur : OK", "Serrage : OK"), r3.takeLast(4))
        assertEquals(avecDc.dropLast(2), r3.dropLast(4))
        // Une ancienne liste dictée sans coffret DC ne « couvre » pas la nouvelle : elle ne la remplace plus.
        assertTrue(!ActionsDetaillees.couvre(ACTIONS_LAPREE + "Contrôle visuel : OK", r2))
        assertTrue(ActionsDetaillees.couvre(r2, ACTIONS_LAPREE))
    }

    @Test fun memeControleAfficheDansChaqueSection() {
        val site = Site("s2", "Installation 2")
        val client = Client("c", "lapree", sites = listOf(site))
        val actions = ActionsDetaillees.completer(ACTIONS_LAPREE, listOf("coffret DC: contrôle parafoudre OK", "contrôle sectionneur OK", "serrage OK"))
        val inter = Intervention("i", "2026-11-27", "maintenance", "Maintenance", actions = actions.joinToString("\n"))
        val r = Rendu.rendreIntervention(SocieteData(clients = listOf(client)), client, site, inter)
        assertTrue(r, r.contains("## ⚡ Coffret DC\n- ✅ Contrôle parafoudre OK\n- ✅ Contrôle sectionneur OK\n- ✅ Serrage OK"))
        assertTrue(r, r.contains("## ⚡ Coffret AC\n- ✅ Serrage : OK\n- ✅ Disjoncteur : OK\n- ✅ Contrôle parafoudre : OK"))
        assertTrue(r, r.contains("## ⚡ Coffret Enedis\n- ✅ Test du disjoncteur : OK\n- ✅ Serrage : OK"))
        assertTrue(r, r.indexOf("## ⚡ Coffret DC") < r.indexOf("## 🧹 Nettoyage"))
    }
}
