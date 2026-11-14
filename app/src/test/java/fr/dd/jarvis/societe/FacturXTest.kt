package fr.dd.jarvis.societe

import fr.dd.jarvis.societe.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FacturXTest {
    private val societe = Societe(nom = "Solaire Plus", forme = "SASU", adresse = "1 rue du Soleil, 21000 Dijon", siret = "123 456 789 00012",
        tvaNum = "FR12123456789", email = "contact@solaire.test", iban = "FR76 3000 6000 0112 3456 7890 189", bic = "AGRIFRPP", tvaDefaut = 20.0)
    private val dupont = Client("c1", "Jean Dupont", adresse = "5 chemin des Vignes 21200 Beaune", email = "jean@exemple.test")
    private val sarl = Client("c2", "SARL Énergie & Co", type = "professionnel", adresse = "10 avenue de la Gare, 71100 Chalon-sur-Saône", siren = "987654321")
    private val facture = Doc("FAC-2026-0007", "facture", "c1", date = "2026-11-14", echeance = "2026-12-14", statut = "a_payer", datePrestation = "2026-11-10",
        lignes = listOf(Ligne("Maintenance annuelle – 6 kWc", 1.0, "forfait", 180.0, remisePct = 10.0, tva = 20.0),
            Ligne("Déplacement", 42.0, "km", 0.45, tva = 20.0, genre = "deplacement"), Ligne("Main d'œuvre", 1.5, "h", 55.0, tva = 10.0)))
    private val d = SocieteData(societe = societe, clients = listOf(dupont, sarl), docs = listOf(facture, facture.copy(numero = "FAC-2026-0008", clientId = "c2")))

    private fun sortie(nom: String) = File(System.getenv("FACTURX_OUT")?.takeIf { it.isNotBlank() } ?: System.getProperty("java.io.tmpdir"), nom)

    @Test fun adresse() {
        assertEquals(FacturX.Adresse("1 rue du Soleil", "21000", "Dijon"), FacturX.adresse("1 rue du Soleil, 21000 Dijon"))
        assertEquals(FacturX.Adresse("5 chemin des Vignes", "21200", "Beaune"), FacturX.adresse("5 chemin des Vignes 21200 Beaune"))
        assertEquals("21000", FacturX.adresse("12 rue X\n21000 Dijon, France").codePostal)
    }

    @Test fun xmlCoherent() {
        val x = FacturX.xml(d, facture)
        val t = d.totaux(facture)
        assertTrue(x.contains("<ram:ID>urn:cen.eu:en16931:2017</ram:ID>"))
        assertTrue(x.contains("<ram:ID schemeID=\"0002\">123456789</ram:ID>"))
        assertTrue(x.contains("<ram:IBANID>FR7630006000011234567890189</ram:IBANID>"))
        assertTrue(x, x.contains("<ram:GrandTotalAmount>${"%.2f".format(java.util.Locale.US, t.ttc)}</ram:GrandTotalAmount>"))
        // Somme des lignes = total HT (règle BR-CO-10).
        val lignes = Regex("<ram:SpecifiedTradeSettlementLineMonetarySummation><ram:LineTotalAmount>([0-9.]+)<").findAll(x).sumOf { it.groupValues[1].toDouble() }
        assertEquals(t.ht, round2(lignes), 0.001)
        assertTrue(x.contains("unitCode=\"HUR\"") && x.contains("unitCode=\"KMT\""))
        sortie("facturx_test.xml").writeText(x)
        sortie("facturx_test_pro.xml").writeText(FacturX.xml(d, d.doc("FAC-2026-0008")!!))
        sortie("facturx_franchise.xml").writeText(FacturX.xml(d.copy(societe = societe.copy(franchiseTva = true, tvaNum = "")), facture))
    }

    @Test fun controles() {
        assertTrue(FacturX.controles(d, facture).toString(), FacturX.controles(d, facture).isEmpty())
        val sansSiren = d.copy(clients = listOf(dupont, sarl.copy(siren = "")))
        assertTrue(FacturX.controles(sansSiren, sansSiren.doc("FAC-2026-0008")!!).any { it.contains("SIREN") })
        assertTrue(FacturX.controles(d.copy(societe = Societe()), facture).size >= 3)
    }

    /** PDF minimal (table xref classique, comme celui produit par le téléphone). */
    private fun pdfMinimal(): ByteArray {
        val objets = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << >> >>",
            "<< /Length 0 >>\nstream\n\nendstream",
            "<< /Producer (Skia/PDF m120) >>")
        val sb = StringBuilder("%PDF-1.4\n%âãÏÓ\n")
        val off = mutableListOf<Int>()
        objets.forEachIndexed { i, o -> off += sb.length; sb.append("${i + 1} 0 obj\n$o\nendobj\n") }
        val x = sb.length
        sb.append("xref\n0 ${objets.size + 1}\n0000000000 65535 f\r\n")
        off.forEach { sb.append(String.format("%010d 00000 n\r\n", it)) }
        sb.append("trailer\n<< /Size ${objets.size + 1} /Root 1 0 R /Info 5 0 R >>\nstartxref\n$x\n%%EOF")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    @Test fun pdfFacturX() {
        val xml = FacturX.xml(d, facture).toByteArray()
        val pdf = FacturX.pdfAvecXml(pdfMinimal(), xml, "Facture FAC-2026-0007 — Jean Dupont", "Solaire Plus",
            java.time.ZonedDateTime.of(2026, 11, 14, 14, 0, 0, 0, java.time.ZoneId.of("Europe/Paris")))
        val t = String(pdf, Charsets.ISO_8859_1)
        assertTrue(t.contains("/AFRelationship /Data") && t.contains("/EmbeddedFiles") && t.contains("pdfaid:part>3") && t.contains("/OutputIntents"))
        assertTrue(t.contains("/Prev ") && t.trimEnd().endsWith("%%EOF"))
        assertTrue(t.contains("(D:20261114140000+01'00')"))
        sortie("facturx_test.pdf").writeBytes(pdf)
    }

    @Test fun profilIcc() {
        val icc = FacturX.iccSrgb()
        assertEquals(icc.size, java.nio.ByteBuffer.wrap(icc, 0, 4).int)
        assertEquals("acsp", String(icc, 36, 4))
        assertEquals(0, icc.size % 4)
    }

    @Test fun eReportingEtTableaux() {
        val payee = facture.copy(statut = "payee", payeLe = "2026-11-20", modePaiement = "virement")
        val dd = d.copy(docs = listOf(payee, d.doc("FAC-2026-0008")!!))
        val csv = FacturX.eReporting(dd, "2026-11")
        assertTrue(csv.startsWith("﻿Type;Date;Facture"))
        assertTrue(csv.contains("Transaction;2026-11-14;FAC-2026-0007"))
        assertTrue(csv.contains("Paiement;2026-11-20;FAC-2026-0007"))
        assertFalse("pas de B2B dans l'e-reporting", csv.contains("FAC-2026-0008"))
        val f = Miroir.tableFactures(dd)
        assertTrue(f, f.contains("FAC-2026-0007;2026-11-14;2026-12-14;Jean Dupont;Payée;"))
        assertTrue(Miroir.csv(listOf(listOf("a;b", "c\"d"))).contains("\"a;b\";\"c\"\"d\""))
    }

    @Test fun planMiroir() {
        val inter = Intervention("i1", "2026-11-03", description = "Contrôle")
        val dd = d.copy(clients = listOf(dupont.copy(sites = listOf(Site("s1", "Toiture", interventions = listOf(inter)))), sarl),
            bulletins = listOf(BulletinRef("b1", "e1", "2026-10", 2000.0, 1500.0, 2800.0, nomEmploye = "Paul")))
        val fichiers = mapOf("societe/interventions/i1/avant_1.jpg" to "10:1", "societe/paie/b1.pdf" to "5:1", "societe/data.json" to "1:1", "societe_logo.png" to "3:3")
        val p = Miroir.plan(dd, fichiers, "id", "lisez", "{}")
        val chemins = p.map { it.chemin }
        assertTrue(chemins.toString(), "Factures/2026/FAC-2026-0007 Jean Dupont.pdf" in chemins)
        assertTrue("Interventions/2026-11-03 Jean Dupont - Toiture (i1)/avant_1.jpg" in chemins)
        assertTrue("Paie/2026/2026-10 Paul.pdf" in chemins)
        assertTrue("Fichiers/Logo.png" in chemins)
        assertFalse(chemins.any { it.contains("data.json") || it.contains("paie/b1") })
        assertTrue(chemins.none { it.startsWith("Factures électroniques") })
        assertTrue(Miroir.plan(dd, fichiers, "id", "lisez", "{}", facturX = true).any { it.chemin == "Factures électroniques/2026/FAC-2026-0007.pdf" })
        // Même contenu → mêmes empreintes ; facture modifiée → seule sa ligne change.
        val p2 = Miroir.plan(dd.copy(docs = dd.docs.map { if (it.numero == "FAC-2026-0008") it.copy(notes = "x") else it }), fichiers, "id", "lisez", "{}")
        val diff = p.zip(p2).filter { it.first.empreinte != it.second.empreinte }.map { it.first.chemin }
        assertTrue(diff.toString(), diff.any { it.contains("FAC-2026-0008") } && diff.none { it.contains("FAC-2026-0007") })
        assertEquals("a_b_c", Miroir.nom("a/b:c"))
    }
}
