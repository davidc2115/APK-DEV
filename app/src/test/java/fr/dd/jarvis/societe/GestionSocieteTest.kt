package fr.dd.jarvis.societe

import fr.dd.jarvis.societe.core.Doc
import fr.dd.jarvis.societe.core.Ligne
import fr.dd.jarvis.societe.core.NoteFrais
import fr.dd.jarvis.societe.core.Societe
import fr.dd.jarvis.societe.core.SocieteData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GestionSocieteTest {
    private val auj = LocalDate.of(2026, 11, 21)
    private fun facture(n: String, ht: Double, payeLe: String, tva: Double = 0.0) =
        Doc(n, "facture", "c1", date = payeLe, lignes = listOf(Ligne("Maintenance", 1.0, "", ht, 0.0, tva)), statut = "payee", payeLe = payeLe)

    @Test fun microMensuelle() {
        val d = SocieteData(societe = Societe(nom = "Test", forme = "Micro-entreprise", franchiseTva = true, activite = "services_artisan"),
            docs = listOf(facture("F1", 1000.0, "2026-10-05"), facture("F2", 500.0, "2026-10-28"), facture("F3", 800.0, "2026-11-03")))
        val e = Declarations.echeances(d, auj)
        val oct = e.first { it.id == "urssaf-2026-10" }
        assertEquals(LocalDate.of(2026, 11, 30), oct.date)
        assertTrue(oct.montant, oct.montant.contains("1 500,00 €"))
        // 21,2 % + CFP 0,3 % + CMA 0,48 % = 21,98 % → 329,70 €
        assertTrue(oct.montant, oct.montant.contains("329,70 €"))
        assertTrue(oct.etapes.any { it.contains("autoentrepreneur.urssaf.fr") })
        assertTrue(e.any { it.id == "urssaf-2026-11" && it.montant.contains("800,00 €") })
        val txt = Declarations.texte(d, aujourdhui = auj)
        assertTrue(txt, txt.contains("Déclaration du chiffre d'affaires — octobre 2026"))
        assertEquals(Declarations.Statut.MICRO, Declarations.statut(d.societe))
    }

    @Test fun acreEtTrimestriel() {
        val s = Societe(forme = "micro", acre = true, dateCreation = "2026-03-10")
        assertEquals(0.5, Declarations.facteurAcre(s, LocalDate.of(2026, 9, 30)), 0.0)
        assertEquals(1.0, Declarations.facteurAcre(s, LocalDate.of(2027, 1, 31)), 0.0)
        assertEquals(0.75, Declarations.facteurAcre(s.copy(dateCreation = "2026-08-01"), LocalDate.of(2026, 9, 30)), 0.0)
        val d = SocieteData(societe = Societe(forme = "Micro-entreprise", franchiseTva = true, periodicite = "trimestrielle"), docs = listOf(facture("F1", 2000.0, "2026-08-10")))
        val t3 = Declarations.echeances(d, auj).first { it.id == "urssaf-2026-T3" }
        assertEquals(LocalDate.of(2026, 10, 31), t3.date)
    }

    @Test fun sasuTvaEtIs() {
        val d = SocieteData(societe = Societe(forme = "SASU", franchiseTva = false, regimeTva = "reel_normal"),
            docs = listOf(facture("F1", 1000.0, "2026-10-12", tva = 20.0)),
            frais = listOf(NoteFrais("n1", "2026-10-15", "Rexel", "câbles", "materiel", 120.0, 20.0)))
        val e = Declarations.echeances(d, auj)
        val tva = e.first { it.id == "tva-2026-10" }
        assertTrue(tva.montant, tva.montant.contains("collectée 200,00 €") && tva.montant.contains("déductible 20,00 €") && tva.montant.contains("à payer 180,00 €"))
        assertTrue(tva.etapes.any { it.contains("Ligne 08") && it.contains("1 000,00 €") })
        assertTrue(e.any { it.id == "is-solde-2027" })
        assertTrue(e.any { it.id == "cfe-2026" })
        assertEquals(Declarations.Statut.SASU, Declarations.statut(d.societe))
        assertEquals(Declarations.Statut.SAS, Declarations.statut(Societe(forme = "SAS")))
        assertEquals(Declarations.Statut.EURL, Declarations.statut(Societe(forme = "EURL")))
    }

    @Test fun fraisALaVoix() {
        val i = Frais.intention("Ajoute une note de frais de 45,50 € chez Total pour du gazole", auj) as Frais.Intention.Ajouter
        assertEquals(45.5, i.nf.ttc, 0.001)
        assertEquals("Total", i.nf.fournisseur)
        assertEquals("carburant", i.nf.categorie)
        assertEquals("gazole", i.nf.description)
        val hier = Frais.intention("note de frais : 12 € de parking hier", auj) as Frais.Intention.Ajouter
        assertEquals("2026-11-20", hier.nf.date); assertEquals("parking", hier.nf.categorie)
        assertEquals(Frais.Intention.Lister("a_traiter", null), Frais.intention("mes notes de frais à traiter", auj))
        assertEquals(Frais.Intention.Lister(null, "2026-10"), Frais.intention("mes notes de frais d'octobre", auj))
        assertEquals(Frais.Intention.Lister(null, "2026-11"), Frais.intention("total des frais du mois", auj))
        assertNull(Frais.intention("ajoute du lait à la liste de courses", auj))
        assertEquals("peage", Frais.categorieDe("APRR autoroute"))
    }

    @Test fun siret() {
        assertTrue(Creation.siretValide("73282932000074"))
        assertFalse(Creation.siretValide("73282932000075"))
        assertFalse(Creation.siretValide("123"))
        assertTrue(Creation.etapes(Declarations.Statut.MICRO).none { it.id == "statuts" })
        assertTrue(Creation.etapes(Declarations.Statut.SASU).any { it.id == "statuts" })
    }
}
