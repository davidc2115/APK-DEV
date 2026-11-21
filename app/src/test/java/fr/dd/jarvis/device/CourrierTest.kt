package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CourrierTest {
    private val ven = LocalDate.of(2026, 11, 20)
    private fun a(titre: String, texte: String) = Courrier.analyser(titre, texte, "SMS", 0L, ven)

    @Test fun colis() {
        val c = a("Colissimo", "Votre colis 6A12345678901 est disponible dans votre point relais Tabac du Centre. Code de retrait : 4821.").single()
        assertEquals(Courrier.COLIS, c.type)
        assertEquals("Colissimo", c.transporteur)
        assertEquals("6A12345678901", c.numero)
        assertEquals("à retirer", c.statut)
        assertEquals("4821", c.code)
        assertTrue(c.lien.contains("laposte.fr") && c.lien.contains("6A12345678901"))
        val ch = a("Chronopost", "Bonne nouvelle ! Votre colis XY123456789FR sera livré aujourd'hui entre 10h et 13h.").single()
        assertEquals("en livraison aujourd'hui", ch.statut)
        assertEquals("XY123456789FR", ch.numero)
        assertEquals("livré", a("Amazon", "Votre colis a été livré. Commande 405-1234567-1234567").single().statut)
        assertEquals("UPS", a("UPS", "Votre envoi 1Z999AA10123456784 est en transit").single().transporteur)
    }

    @Test fun factureDevisRdv() {
        val f = a("EDF", "Votre facture de 128,45 € est disponible. Elle sera prélevée le 5 décembre.").single()
        assertEquals(Courrier.FACTURE, f.type)
        assertEquals(128.45, f.montant!!, 0.001)
        assertEquals("2026-12-05", f.echeance)
        val d = a("Leroy Merlin", "Votre devis n° 4512 d'un montant de 1 250,00 € est prêt.").single()
        assertEquals(Courrier.DEVIS, d.type)
        assertEquals(1250.0, d.montant!!, 0.001)
        val r = a("Cabinet Dr Martin", "Rappel : rendez-vous le mardi 24 novembre à 14h30, 12 rue des Lilas, 21000 Dijon.").single()
        assertEquals(Courrier.RDV, r.type)
        assertTrue(r.lieu, r.lieu.startsWith("12 rue des Lilas"))
        val z = java.time.Instant.ofEpochMilli(r.dateRdv!!).atZone(java.time.ZoneId.systemDefault())
        assertEquals(LocalDate.of(2026, 11, 24), z.toLocalDate()); assertEquals(14, z.hour); assertEquals(30, z.minute)
    }

    @Test fun rienAGarder() {
        assertTrue(a("Banque", "Votre code de vérification est 123456. Ne le communiquez à personne.").isEmpty())
        assertTrue(a("Maman", "Tu viens manger dimanche ?").isEmpty())
        assertTrue(a("Promo", "-50 % sur tout le magasin ce week-end").isEmpty())
        assertEquals(Courrier.Intention.Colis, Courrier.intention("où en sont mes colis ?"))
        assertEquals(Courrier.Intention.ATraiter, Courrier.intention("qu'est-ce que j'ai reçu d'important"))
        assertEquals(Courrier.Intention.AjouterRdv, Courrier.intention("ajoute le rendez-vous reçu à mon agenda"))
        assertNull(Courrier.intention("ajoute un rendez-vous chez Martin demain à 14h"))
    }

    @Test fun societeOuPerso() {
        val data = fr.dd.jarvis.societe.core.SocieteData(
            societe = fr.dd.jarvis.societe.core.Societe(email = "contact@dd-solaire.fr"),
            clients = listOf(fr.dd.jarvis.societe.core.Client("c1", "Pressigny", tel = "06 73 47 08 27"), fr.dd.jarvis.societe.core.Client("c2", "SCEA Apert", email = "apert@ferme.fr")))
        fun el(em: String, r: String) = Courrier.Element("x", Courrier.FACTURE, "Gmail", em, r, 0L, montant = 10.0)
        assertEquals(Courrier.SOCIETE to true, Courrier.domaine(el("M. Pressigny", "Bon pour accord"), "", data, emptyMap()))
        assertEquals(Courrier.SOCIETE to true, Courrier.domaine(el("06 73 47 08 27", "rappelez-moi"), "", data, emptyMap()))
        assertEquals(Courrier.SOCIETE to true, Courrier.domaine(el("Orange", "votre facture"), "contact@dd-solaire.fr", data, emptyMap()))
        assertEquals("" to false, Courrier.domaine(el("EDF", "votre facture de 80 €"), "perso@gmail.com", data, emptyMap()))
        // Classement retenu pour l'expéditeur.
        assertEquals(Courrier.PERSO to true, Courrier.domaine(el("EDF", "votre facture"), "", data, mapOf(Courrier.cleEmetteur("EDF") to Courrier.PERSO)))
    }
}
