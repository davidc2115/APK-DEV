package fr.dd.jarvis.societe

import fr.dd.jarvis.societe.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DocumentsTest {
    private val societe = Societe(nom = "Solaire Plus", forme = "SASU", capitalSocial = 1000.0, adresse = "1 rue du Soleil 21000 Dijon",
        siret = "12345678900012", rcs = "RCS Dijon 123 456 789", codeApe = "4321A", tvaNum = "FR12123456789", conventionCollective = "Bâtiment ouvriers (IDCC 1597)")
    private val paul = Employe("e1", "Paul Martin", "Technicien", brutMensuel = 2100.0, tauxPas = 5.0, dateEntree = "2025-03-01")

    @Test fun modelesValides() {
        assertNull(Rendu.valider("paie", Templates.PAIE))
        assertNull(Rendu.valider("facture", Templates.DEVIS_FACTURE))
        assertNull(Rendu.valider("devis", Templates.DEVIS_FACTURE))
    }

    @Test fun bulletinComptable() {
        val d = SocieteData(societe = societe, employes = listOf(paul),
            bulletins = listOf(BulletinRef("b0", "e1", "2026-10", 2100.0, 1550.0, 2900.0, netImposable = 1700.0, heures = 151.67)))
        val b = Paie.bulletin(paul, d.paie, "2026-11", 0.0)
        val md = Rendu.rendreBulletin(d, b)
        listOf("BULLETIN DE PAIE", "Période du 01/11/2026 au 30/11/2026", "| Employeur | Salarié |", "SIRET : 12345678900012", "Code APE : 4321A",
            "Convention collective : Bâtiment ouvriers", "**SANTÉ**", "**RETRAITE**", "**CSG / CRDS**", "NET À PAYER AVANT IMPÔT", "NET PAYÉ",
            "Montant net social", "Cumuls 2026", "conservez ce bulletin de paie sans limitation de durée").forEach {
            assertTrue("absent : $it\n$md", md.contains(it))
        }
        // Le PAS porte sur le net imposable (net avant impôt + CSG/CRDS non déductible), pas sur le net avant impôt.
        assertTrue(b.netImposable > b.netAvantImpot)
        assertEquals(round2(b.netImposable * 0.05), b.pas, 0.01)
        // Cumul brut = bulletin d'octobre + celui de novembre.
        assertTrue(md, md.contains(euro(round2(2100.0 + b.brut))))
        // Tableau du bloc employeur : l'en-tête est suivi du séparateur (sinon la 1re ligne disparaît du PDF).
        val l = md.lines(); val i = l.indexOf("| Employeur | Salarié |")
        assertEquals("|---|---|", l[i + 1])
    }

    @Test fun rubriquesOfficielles() {
        assertEquals(0, Rendu.rubrique("Santé — maladie, maternité, invalidité, décès"))
        assertEquals(1, Rendu.rubrique("Accidents du travail — maladies professionnelles"))
        assertEquals(2, Rendu.rubrique("Retraite complémentaire AGIRC-ARRCO (+ CEG, CET)"))
        assertEquals(2, Rendu.rubrique("CET — contribution d'équilibre technique"))
        assertEquals(3, Rendu.rubrique("Famille — allocations familiales"))
        assertEquals(4, Rendu.rubrique("Assurance chômage (+ AGS)"))
        assertEquals(4, Rendu.rubrique("APEC (cadres)"))
        assertEquals(5, Rendu.rubrique("Taxe d'apprentissage"))
        assertEquals(6, Rendu.rubrique("CSG déductible de l'impôt sur le revenu"))
        assertEquals(7, Rendu.rubrique("Réduction générale / exonérations de cotisations patronales"))
    }

    @Test fun factureMentions() {
        val pro = Client("c1", "Garage Dupont", "professionnel", adresse = "2 rue X", siren = "987654321", tvaNum = "FR99987654321")
        val f = Doc("FAC-2026-0003", "facture", "c1", date = "2026-11-10", echeance = "2026-12-10", lignes = listOf(Ligne("Maintenance", 1.0, "forfait", 200.0, 0.0, 20.0)), statut = "a_payer")
        val d = SocieteData(societe = societe, clients = listOf(pro), docs = listOf(f))
        val md = Rendu.rendreDoc(d, f)
        listOf("FACTURE N° FAC-2026-0003", "| Émetteur | Client |", "SASU au capital de 1 000,00 €", "RCS Dijon", "Code APE : 4321A", "SIREN : 987654321",
            "TVA intracommunautaire : FR99987654321", "Date de la prestation : 10/11/2026", "Nature de l'opération", "40 €", "Net à payer").forEach {
            assertTrue("absent : $it\n$md", md.contains(it))
        }
        val particulier = d.copy(clients = listOf(pro.copy(type = "particulier", siren = "", tvaNum = "")))
        assertTrue(!Rendu.rendreDoc(particulier, f).contains("40 €"))
        assertTrue(Rendu.identiteLegale(d).contains("SIRET 12345678900012"))
    }

    @Test fun creationDocumentTestEstRouteeSansCreerDeClient() {
        val client = Client("c1", "Client Test")
        val tarif = Tarif("Maintenance annuelle", Mode.PAR_KWC, listOf(Tranche(10.0, 120.0)))
        val depannage = Tarif("Dépannage", Mode.UNITE, listOf(Tranche(KWC_MAX, 90.0)))
        val d = SocieteData(societe = societe, clients = listOf(client), tarifs = listOf(tarif, depannage))
        val a = SocieteTools.intentionCreationTest(d,
            "Fais-moi un devis test pour une installation de 5 kWc avec 50 km de déplacement sans aucun dépannage")!!
        assertEquals("devis", a.optString("type"))
        assertTrue(a.optBoolean("test"))
        assertEquals("Client Test", a.optString("client"))
        assertEquals("Maintenance annuelle", a.optString("prestations"))
        assertEquals(5.0, a.optDouble("kwc"), 0.001)
        assertEquals(50.0, a.optDouble("distance_km"), 0.001)
        assertTrue(a.optBoolean("deplacement"))
    }

    @Test fun creationTestDemandeUnClientSiPlusieurs() {
        val d = SocieteData(clients = listOf(Client("c1", "Dupont"), Client("c2", "Martin")),
            tarifs = listOf(Tarif("Maintenance", Mode.UNITE, listOf(Tranche(KWC_MAX, 100.0)))))
        val r = SocieteTools.intentionCreationTest(d, "Crée un devis test")!!
        assertTrue(r.optString("question").contains("Dupont"))
        assertTrue(r.optString("question").contains("Martin"))
        assertTrue(!r.has("client"))
    }

    @Test fun creationTestDemandeUnTarifSiCatalogueAmbigu() {
        val d = SocieteData(clients = listOf(Client("c1", "Client Test")), tarifs = listOf(
            Tarif("Maintenance", Mode.UNITE, listOf(Tranche(KWC_MAX, 100.0))),
            Tarif("Contrôle onduleur", Mode.UNITE, listOf(Tranche(KWC_MAX, 80.0)))))
        val r = SocieteTools.intentionCreationTest(d, "Crée un devis test pour Client Test")!!
        assertTrue(r.optString("question").contains("Maintenance"))
        assertTrue(r.optString("question").contains("Contrôle onduleur"))
    }

    @Test fun reflexesGestion() {
        val d = SocieteData(societe = societe, employes = listOf(paul), clients = listOf(Client("c1", "Dupont")),
            docs = listOf(Doc("FAC-2026-0003", "facture", "c1", date = "2026-11-10")))
        assertEquals("documents_supprimer" to "oui", SocieteTools.intentionGestion(d, "Supprime toutes les factures")!!.let { it.first to it.second.optString("tous") })
        val a = SocieteTools.intentionGestion(d, "annule et supprime toutes les factures")!!.second
        assertEquals("supprimer", a.optString("action"))
        assertEquals("annuler", SocieteTools.intentionGestion(d, "annule les factures de Dupont")!!.second.optString("action"))
        assertEquals("Dupont", SocieteTools.intentionGestion(d, "annule les factures de Dupont")!!.second.optString("client"))
        assertEquals("FAC-2026-0003", SocieteTools.intentionGestion(d, "supprime la facture 3")!!.second.optString("numeros"))
        assertNull(SocieteTools.intentionGestion(d, "annule la facture 3"))       // un seul document : document_action
        assertNull(SocieteTools.intentionGestion(d, "crée une facture pour Dupont"))
        val b = SocieteTools.intentionGestion(d, "supprime la fiche de paie de novembre de Paul")!!
        assertEquals("bulletins", b.first)
        assertEquals("Paul Martin", b.second.optString("employe"))
        assertEquals("supprimer", b.second.optString("action"))
        assertTrue(b.second.optString("mois").endsWith("-11"))
        assertEquals("lister", SocieteTools.intentionGestion(d, "montre-moi mes fiches de paie")!!.second.optString("action"))
        assertNull(SocieteTools.intentionGestion(d, "fais la fiche de paie de novembre"))
        assertEquals("2026-03", SocieteTools.mois("supprime le bulletin de mars", LocalDate.of(2026, 11, 13)))
        assertEquals("2025-12", SocieteTools.mois("bulletin de decembre", LocalDate.of(2026, 11, 13)))
    }
}
