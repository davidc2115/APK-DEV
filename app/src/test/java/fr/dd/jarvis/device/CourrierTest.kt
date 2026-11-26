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

    @Test fun commandeAmazonSansPreuveDeLivraisonNestPasUnColis() {
        assertTrue(a("Amazon", "Votre commande 405-1234567-1234567 a bien été confirmée. Merci pour votre achat.").isEmpty())
        assertTrue(a("Amazon", "Profitez de la livraison gratuite sur votre prochaine commande.").isEmpty())
    }

    @Test fun expeditionAmazonEstReconnuSansConfondreNumeroDeCommandeEtSuivi() {
        val colis = a("Amazon", "Votre colis a été expédié. Numéro de suivi TBA123456789.").single()
        assertEquals(Courrier.COLIS, colis.type)
        assertEquals("Amazon", colis.transporteur)
        assertEquals("TBA123456789", colis.numero)
        assertEquals("expédié", colis.statut)
    }

    @Test fun dernierMessageSmsEstAnalyseMemeSiLeResumeNotificationEstTronque() {
        val texte = Courrier.contenuNotification("1 nouveau message", "Rappel : votre rendez-vous est confirmé le 27/11/2026 à 10h30.")
        val rdv = a("Cabinet Martin", texte).single()
        assertEquals(Courrier.RDV, rdv.type)
        val date = java.time.Instant.ofEpochMilli(rdv.dateRdv!!).atZone(java.time.ZoneId.systemDefault())
        assertEquals(LocalDate.of(2026, 11, 27), date.toLocalDate())
        assertEquals(10, date.hour)
        assertEquals(30, date.minute)
    }

    @Test fun rendezVousAutomobileSansMotRendezVousSontDetectesDepuisLeSms() {
        val base = LocalDate.of(2026, 11, 26)
        val vidange = Courrier.analyser("Garage", "Jeudi 3 à 15h kadjar vidange garage vitteaux", "SMS", 0L, base).single()
        assertEquals(Courrier.RDV, vidange.type)
        val dateVidange = java.time.Instant.ofEpochMilli(vidange.dateRdv!!).atZone(java.time.ZoneId.systemDefault())
        assertEquals(LocalDate.of(2026, 12, 3), dateVidange.toLocalDate())
        assertEquals(15, dateVidange.hour)
        assertEquals(0, dateVidange.minute)
        assertEquals("Garage vitteaux", vidange.lieu)
        assertTrue(Courrier.ligne(vidange).contains("Kadjar — Vidange"))

        val ct = Courrier.analyser("Garage", "Lundi 7 à 17h20 CT kadjar", "SMS", 0L, base).single()
        assertEquals(Courrier.RDV, ct.type)
        val dateCt = java.time.Instant.ofEpochMilli(ct.dateRdv!!).atZone(java.time.ZoneId.systemDefault())
        assertEquals(LocalDate.of(2026, 12, 7), dateCt.toLocalDate())
        assertEquals(17, dateCt.hour)
        assertEquals(20, dateCt.minute)
        assertTrue(Courrier.ligne(ct).contains("Kadjar — Contrôle technique"))
    }
    @Test fun texteNotificationResteUnSecoursSiAucunDernierSmsNestExpose() {
        assertEquals("Rendez-vous demain à 9h", Courrier.contenuNotification("Rendez-vous demain à 9h", " "))
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
    @Test fun rendezVousSansDateResteDansLeCourrierPourEtreComplete() {
        val r = a("M. Dupont", "Bonjour, je vous confirme notre rendez-vous pour votre installation. Je vous rappelle l'horaire.").single()
        assertEquals(Courrier.RDV, r.type)
        assertNull(r.dateRdv)
        assertEquals("date non précisée", r.statut)
    }

    @Test fun factureEtDevisSansMontantSontConserves() {
        val f = a("Contact inconnu", "Bonjour, veuillez trouver votre facture en pièce jointe.").single()
        assertEquals(Courrier.FACTURE, f.type)
        assertNull(f.montant)
        val d = a("Artisan Martin", "Votre devis numéro 4587 est prêt, merci de nous confirmer votre accord.").single()
        assertEquals(Courrier.DEVIS, d.type)
        assertNull(d.montant)
    }

    @Test fun expeditionAvecFormulationLibreEstReconnue() {
        val c = a("Transport", "Votre paquet a été remis au transporteur et est en cours d'expédition.").single()
        assertEquals(Courrier.COLIS, c.type)
    }

    @Test fun lesPublicitesNeDeviennentPasDesRendezVousFacturesOuColis() {
        assertTrue(a("Option Budget", "Découvrez notre option budget et profitez de nos offres exclusives.").isEmpty())
        assertTrue(a("Garage", "Découvrez notre garage et nos offres promotionnelles sur l'entretien.").isEmpty())
        assertTrue(a("Banque", "Votre option budget évolue, un prélèvement sera effectué selon votre choix.").isEmpty())
        assertTrue(a("Transport", "Profitez de la livraison gratuite sur toutes nos commandes.").isEmpty())
        assertTrue(a("Boutique", "Découvrez votre colis idéal pour vos vacances avec notre offre spéciale.").isEmpty())
    }

    @Test fun rendezVousPersonnelSansMotRendezVousEstDetecte() {
        val r = Courrier.analyser("Ma chérie", "Je passerai chez toi jeudi 3 décembre vers 19h.", "Mail", 0L, LocalDate.of(2026, 11, 26)).single()
        assertEquals(Courrier.RDV, r.type)
        val date = java.time.Instant.ofEpochMilli(r.dateRdv!!).atZone(java.time.ZoneId.systemDefault())
        assertEquals(LocalDate.of(2026, 12, 3), date.toLocalDate())
        assertEquals(19, date.hour)
    }

    @Test fun exemplesCapturesNeSontPasClassesCommeRdvFactureOuDevis() {
        val base = LocalDate.of(2026, 11, 26)
        assertTrue(Courrier.analyser("SUEZ", "Relevez votre compteur d'eau avant le 24/11/2026 ! Relevez compteur", "Mail", 0L, base).isEmpty())
        assertTrue(Courrier.analyser("Leroy Merlin", "Votre simulation est sauvegardée. Nos experts vous aident à la peaufiner.", "Mail", 0L, base).isEmpty())
        assertTrue(Courrier.analyser("Google Play — 4,99 €", "Confirmation de votre commande Google Play du 18 nov. Votre abonnement a été prolongé et facturé en conséquence.", "Mail", 0L, base).isEmpty())
        assertTrue(Courrier.analyser("Rendez-vous Chaussea — date inconnue", "Plus que 48 h pour profiter de vos -25%. David, votre code expire bientôt.", "Mail", 0L, base).isEmpty())
    }

    @Test fun vraiRappelDeRendezVousResteDetecteMalgreObjetCourt() {
        val r = Courrier.analyser("Cabinet Martin", "Rappel : votre rendez-vous est confirmé le 3 décembre 2026 à 14h30 au 12 rue des Lilas.", "Mail", 0L, LocalDate.of(2026, 11, 26)).single()
        assertEquals(Courrier.RDV, r.type)
        assertTrue(r.dateRdv != null)
    }

}
