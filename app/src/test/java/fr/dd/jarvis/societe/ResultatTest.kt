package fr.dd.jarvis.societe

import fr.dd.jarvis.societe.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ResultatTest {
    private fun facture(n: String, ht: Double, paye: String) = Doc(n, "facture", "c1", date = paye, lignes = listOf(Ligne("Maintenance", 1.0, "", ht, 0.0, 20.0)), statut = "payee", payeLe = paye)

    @Test fun impotEtReserve() {
        val s = Societe(forme = "SASU", capitalSocial = 1000.0)
        // 42 500 × 15 % + 57 500 × 25 % = 6 375 + 14 375
        assertEquals(20_750.0, Resultat.impot(100_000.0, s), 0.01)
        assertEquals(0.0, Resultat.impot(-50.0, s), 0.0)
        assertEquals(50.0, Resultat.reserveLegale(10_000.0, s, dejaDotee = 50.0), 0.01)
        assertEquals(500.0, Resultat.reserveLegale(10_000.0, Societe()), 0.01)
    }

    @Test fun calcul() {
        val s = Societe(forme = "SASU", capitalSocial = 1000.0, chargesMensuelles = 200.0, estimerSalaires = true)
        val e = Employe("e1", "Paul", brutMensuel = 2000.0, dateEntree = "2026-01-01")
        val d = SocieteData(societe = s, employes = listOf(e),
            docs = listOf(facture("FAC-2026-0001", 10_000.0, "2026-02-10"), facture("FAC-2026-0002", 30_000.0, "2026-03-05")),
            bulletins = listOf(BulletinRef("b1", "e1", "2026-01", 2000.0, 1560.0, 2800.0), BulletinRef("b2", "e1", "2026-02", 2000.0, 1560.0, 2800.0)))
        val b = Resultat.bilan(d, 2026, LocalDate.of(2026, 3, 31))
        assertEquals(40_000.0, b.caHt, 0.01)
        assertEquals(8_000.0, b.tva, 0.01)
        // Janvier, février réels + mars estimé
        assertEquals(1, b.salaires.moisEstimes)
        assertTrue(b.salaires.cout > 5600.0)
        assertEquals(600.0, b.charges, 0.01)
        assertEquals(round2(40_000.0 - b.salaires.cout - 600.0), b.avantImpot, 0.01)
        assertEquals(round2(b.avantImpot * 0.15), b.impot, 0.02)
        // Réserve légale plafonnée à 10 % du capital (100 €)
        assertEquals(100.0, b.reserveLegale, 0.01)
        assertEquals(round2(b.net - 100.0), b.dividendeBrut, 0.02)
        assertEquals(round2(b.dividendeBrut * (1 - 0.314)), b.dividendeNet, 0.02)
        val texte = Resultat.texte(b, Resultat.projection(d, 2026, LocalDate.of(2026, 3, 31)), s)
        assertTrue(texte, texte.contains("Dividende de fin d'année possible") && texte.contains("Projection au 31/12"))
    }

    @Test fun factureCouvreLesSalaires() {
        val s = Societe(forme = "SASU", capitalSocial = 1000.0)
        val e = Employe("e1", "Paul", brutMensuel = 2000.0, dateEntree = "2026-01-01")
        val bul = listOf(BulletinRef("b1", "e1", "2026-01", 2000.0, 1560.0, 2800.0))
        val f1 = facture("FAC-2026-0001", 2000.0, "2026-01-20")
        val d = SocieteData(societe = s, employes = listOf(e), docs = listOf(f1), bulletins = bul)
        val p = Resultat.partFacture(d, f1)
        // 2 000 € HT ne couvrent même pas les 2 800 € de salaire de janvier : aucun bénéfice.
        assertEquals(2000.0, p.couvre, 0.01); assertEquals(0.0, p.benefice, 0.01); assertEquals(0.0, p.dividendeBrut, 0.01)
        val f2 = facture("FAC-2026-0002", 5000.0, "2026-01-25")
        val d2 = d.copy(docs = listOf(f1, f2))
        val p2 = Resultat.partFacture(d2, f2)
        assertEquals(800.0, p2.couvre, 0.01); assertEquals(4200.0, p2.benefice, 0.01)
        assertEquals(630.0, p2.impot, 0.01)
        assertTrue(p2.dividendeBrut > 3000.0)
        assertTrue(Resultat.texteFacture(p2, s).contains("Couvre les salaires"))
    }

    @Test fun microEntreprise() {
        val s = Societe(forme = "micro-entreprise")
        val d = SocieteData(societe = s, docs = listOf(facture("FAC-2026-0001", 1000.0, "2026-05-01")))
        val b = Resultat.bilan(d, 2026, LocalDate.of(2026, 5, 31))
        assertTrue(!b.soumisIs); assertEquals(0.0, b.impot, 0.0); assertEquals(0.0, b.dividendeBrut, 0.0)
    }

    @Test fun seulsLesBulletinsComptent() {
        val s = Societe(forme = "SASU")
        val e = Employe("e1", "Paul", brutMensuel = 1300.0, dateEntree = "2026-01-01")
        val sans = SocieteData(societe = s, employes = listOf(e))
        val b0 = Resultat.bilan(sans, 2026, LocalDate.of(2026, 11, 13))
        // Aucun bulletin (supprimés) : aucun salaire versé, et une note l'explique.
        assertEquals(0.0, b0.salaires.cout, 0.0)
        assertTrue(b0.notes.joinToString(), b0.notes.any { it.contains("Seuls les bulletins") })
        val avec = sans.copy(bulletins = listOf(BulletinRef("b1", "e1", "2026-10", 1300.0, 1000.0, 1700.0)))
        assertEquals(1700.0, Resultat.bilan(avec, 2026, LocalDate.of(2026, 11, 13)).salaires.cout, 0.01)
        // La projection estime seulement les mois à venir.
        val p = Resultat.projection(avec.copy(docs = listOf(facture("FAC-2026-0001", 5000.0, "2026-10-01"))), 2026, LocalDate.of(2026, 11, 13))!!
        assertEquals(1, p.salaires.moisEstimes)
    }
}
