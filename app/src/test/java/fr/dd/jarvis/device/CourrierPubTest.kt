package fr.dd.jarvis.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Cas des captures du 27/11/2026 : publicités prises pour du courrier, dates et adresses tirées des mentions légales. */
class CourrierPubTest {
    private val jour = LocalDate.of(2026, 11, 27)
    private fun mail(de: String, texte: String, labels: List<String> = emptyList()) = Courrier.analyser(de, texte, "Mail", 0L, jour, labels)
    private fun z(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

    @Test fun publicitesEtNewslettersIgnorees() {
        assertTrue(mail("OKAÏDI OBAÏBI", "Top départ ⏱ BLACK FRIDAY : -20% sur tout !\nEt -50% sur une sélection\nCAGNOTTE 2€ FILLE BÉBÉ JOUETS\n" +
            "Nous vous attendons en magasin dès jeudi 30 septembre.\nConformément au règlement du 27 avril 2016 (droit d'accès, de rectification) " +
            "vous pouvez vous désinscrire.").isEmpty())
        assertTrue(mail("OK+ Fidélité Okaïdi Oxybul", "Melinda aura bientôt 9 ans ! Découvrez notre sélection adaptée\nCAGNOTTE 2€ LA FIDÉLITÉ OKAIDI, OBAIBI, OXYBUL… " +
            "9 BOUGIES POUR MELINDA ! Rendez-vous en magasin.").isEmpty())
        assertTrue(mail("Bpifrance Création", "Financer son entreprise avec ses allocations chômage\nSi vous ne visualisez pas correctement cet email, " +
            "https://click.media.bpifrance.fr/?qs=ABp7ImQiOjUwNzAsInQiOjI2MTYsInMiOjUwfQAyAAAAAAK-b74Zx6D2XQew\nRetrouvez vos factures et devis types.").isEmpty())
        assertTrue(mail("ENGIE Particuliers", "Votre bilan mensuel de consommation d'électricité\nVersion en ligne\nENGIE Particuliers Votre suivi de consommation en kWh " +
            "et en euros &zwnj; Votre conso d'&#xE9;lectricit&#xE9; - Octobre 2026 Cortot. Votre prochaine facture arrivera bientôt.").isEmpty())
        // Gmail « Promotions » : ignoré même s'il parle de rendez-vous.
        assertTrue(mail("Netatmo", "Votre rendez-vous avec la maison connectée : lundi 30 novembre à 9h", listOf("CATEGORY_PROMOTIONS", "INBOX")).isEmpty())
    }

    @Test fun vraieFactureMalgreLePiedDePageCommercial() {
        val f = mail("ENGIE Particuliers", "Votre facture d'électricité est disponible\nVersion en ligne\nFacture n° 12345678 : total TTC 84,12 €, prélevé le 5 décembre 2026.\n" +
            "Économisez jusqu'à 200 € avec notre offre Elec Verte.").single()
        assertEquals(Courrier.FACTURE, f.type)
        assertEquals(84.12, f.montant!!, 0.001)
        assertEquals("2026-12-05", f.echeance)
    }

    @Test fun messageTransfereSansRendezVousNestPasUnRendezVous() {
        val long = "Fwd: Bilan orthophonique\\n---------- Forwarded message --------- De : Marguerite Fouilland <mfouilland.orthophoniste@gmail.com> " +
            "Date: jeu. 22 oct. 2026, 17:07 Subject: Fwd: Bilan orthophonique To: <an> Bonjour, veuillez trouver ci-joint le bilan orthophonique de Léa. " +
            "Les séances se poursuivent comme convenu. N'hésitez pas à prendre rendez-vous si besoin. ".repeat(2) +
            "Angeline Carpentier, orthophoniste, 27 rue Basse des Halles 21540 SOMBERNON <https://www.example.fr>"
        assertTrue(mail("Angeline Carpentier", long).isEmpty())
    }

    @Test fun dateEtLieuDuRendezVousPasCeuxDeLOffreNiDuPiedDePage() {
        val texte = "Confirmation de votre rendez-vous\nOffre d'hiver valable jusqu'au 30 septembre.\n" + "Bonjour, ".repeat(30) +
            "\nVotre rendez-vous est confirmé le mardi 8 décembre à 14h30 au garage, 5 avenue de la Gare, 21000 Dijon.\n" + "Merci de votre confiance. ".repeat(20) +
            "\nSiège social : 245 rue du Vieux Pont de Sèvres, 92100 Boulogne. Règlement du 27 avril 2016."
        val r = mail("Garage Central", texte).single()
        assertEquals(Courrier.RDV, r.type)
        val d = z(r.dateRdv!!)
        assertEquals(LocalDate.of(2026, 12, 8), d.toLocalDate()); assertEquals(14, d.hour); assertEquals(30, d.minute)
        assertTrue(r.lieu, r.lieu.startsWith("5 avenue de la Gare"))
    }

    @Test fun texteNettoye() {
        assertEquals("Votre conso d'électricité - Octobre", Courrier.nettoyerTexte("Votre conso d'&#xE9;lectricit&#xE9;&zwnj; - Octobre"))
        assertEquals("a\nb", Courrier.nettoyerTexte("a\\nb"))
        assertFalse(Courrier.resumeDe("Voir https://click.media.bpifrance.fr/?qs=ABC123 ici").contains("http"))
        // Une date des mentions légales n'est jamais une adresse.
        assertEquals("", Courrier.lieuValide("27 avril 2016 (droit d'accès"))
        assertEquals("27 rue Basse des Halles 21540 SOMBERNON", Courrier.lieuValide("27 rue Basse des Halles 21540 SOMBERNON"))
    }

    @Test fun memeMessageVuDeuxFois() {
        val a = Courrier.Element("a", Courrier.FACTURE, "Mail", "ENGIE Particuliers", "Votre facture d'électricité est disponible. Montant 84,12 €.", 1_000L)
        val b = a.copy(id = "b", source = "Mail cortot@exemple.fr", ts = 3_600_000L)
        assertTrue(Courrier.memeMessage(a, b))
        assertFalse(Courrier.memeMessage(a, b.copy(ts = 10 * 86_400_000L)))
        assertFalse(Courrier.memeMessage(a, b.copy(emetteur = "EDF")))
    }

    @Test fun lectureParLIAControlee() {
        val e = Courrier.Element("x", Courrier.RDV, "Mail", "Garage Central", "…", 0L)
        val texte = "Votre rendez-vous vidange Kadjar est confirmé jeudi 3 décembre à 15h30 au 5 avenue de la Gare, 21000 Dijon."
        val r = CourrierIA.appliquer(e, JSONObject("""{"type":"rdv","publicite":false,"objet":"Vidange Kadjar","date":"2026-12-03","heure":"15:30","lieu":"5 avenue de la Gare, 21000 Dijon","resume":"Vidange du Kadjar jeudi 3 décembre à 15 h 30."}"""), texte, jour)!!
        assertEquals("Vidange Kadjar", r.objet)
        assertTrue(r.verifie)
        val d = z(r.dateRdv!!); assertEquals(LocalDate.of(2026, 12, 3), d.toLocalDate()); assertEquals(15, d.hour); assertEquals(30, d.minute)
        assertEquals("5 avenue de la Gare, 21000 Dijon", r.lieu)
        // Publicité : retirée.
        assertNull(CourrierIA.appliquer(e, JSONObject("""{"type":"rdv","publicite":true}"""), texte, jour))
        assertNull(CourrierIA.appliquer(e, JSONObject("""{"type":"aucun","publicite":false}"""), texte, jour))
        // Montant absent du message : ignoré ; présent : gardé.
        val f = Courrier.Element("f", Courrier.FACTURE, "Mail", "EDF", "…", 0L)
        val fact = "Votre facture : total 1 250,00 € à payer avant le 15/12/2026."
        assertNull(CourrierIA.appliquer(f, JSONObject("""{"type":"facture","publicite":false,"montant":999.99}"""), fact, jour)!!.montant)
        assertEquals(1250.0, CourrierIA.appliquer(f, JSONObject("""{"type":"facture","publicite":false,"montant":1250,"echeance":"2026-12-15"}"""), fact, jour)!!.montant!!, 0.001)
        // Adresse inventée : ignorée.
        assertEquals("", CourrierIA.appliquer(e.copy(lieu = ""), JSONObject("""{"type":"rdv","publicite":false,"date":"2026-12-03","lieu":"12 rue de Paris, Lyon"}"""), texte, jour)!!.lieu)
    }

    @Test fun anciensElementsDesCapturesRetires() {
        fun el(type: String, de: String, r: String, rdv: Long? = null, lieu: String = "") = Courrier.Element(de.hashCode().toString(), type, "Mail", de, r, 1_764_000_000_000L, dateRdv = rdv, lieu = lieu)
        assertNull(Courrier.revalider(el(Courrier.FACTURE, "Bpifrance Création", "Financer son entreprise avec ses allocations chômage Si vous ne visualisez pas correctement cet email, https://click.media.bpifrance.fr/?qs= ABp7ImQiOjUwNzAsInQiOjI2MTYsInMiOjUwfQAyAAAAAAK -b74Zx6D2XQew")))
        assertNull(Courrier.revalider(el(Courrier.RDV, "OKAÏDI OBAÏBI", "Top départ ⏱ BLACK F R I D A Y : -20% sur tout ! 96 Et -50% sur une sélection CAGNOTTE 2€ FILLE FILLE BÉBÉ FILLE BÉBÉ FILLE JOUETS BÉBÉ JOUETS BÉBÉ GARÇON GARÇON BÉBÉ GARÇON BÉBÉ GARÇON JOUETS ENFANT", 1_822_381_200_000L, "27 avril 2016 (droit d'accès")))
        assertNull(Courrier.revalider(el(Courrier.RDV, "OK+ Fidélité Okaïdi Oxybul", "Melinda aura bientôt 9 ans ! 96 Découvrez notre sélection adaptée CAGNOTTE 2€ LA FIDÉLITÉ OKAIDI, OBAIBI , OXYBUL… 9 BOUGIES POUR MELINDA! Ses talents grandissent en même temps que lui ! Voici notre s", lieu = "27 avril 2016 (droit d'accès")))
        assertNull(Courrier.revalider(el(Courrier.RDV, "Angeline Carpentier", "Fwd: Bilan orthophonique\\n---------- Forwarded message --------- De : Marguerite Fouilland <mfouilland.orthophoniste@gmail.com> Date: jeu. 22 oct. 2026, 17:07 Subject: Fwd: Bilan orthophonique To: <an", lieu = "27 rue Basse des Halles 21540 SOMBERNON <https://www")))
        assertNull(Courrier.revalider(el(Courrier.FACTURE, "ENGIE Particuliers", "Votre bilan mensuel de consommation d'électricité Version en ligne 96 ENGIE Particuliers Votre suivi de consommation en kWh et en euros &zwnj; Votre conso d'&#xE9;lectricit&#xE9; - Octobre 2026 Cortot")))
        // Un vrai élément reste.
        val vrai = Courrier.revalider(el(Courrier.FACTURE, "EDF", "Votre facture de 128,45 € est disponible. Elle sera prélevée le 5 décembre."))
        assertEquals(Courrier.FACTURE, vrai!!.type)
    }

    @Test fun smsPublicitairesEtExpediteursDeMasse() {
        assertTrue(Courrier.analyser("38015", "OKAIDI : jusqu'à -30% ce week-end en magasin ! Rendez-vous samedi 28/11. STOP au 36111", "SMS", 0L, jour).isEmpty())
        assertTrue(Courrier.analyser("Intermarché", "Vente flash : 10€ offerts dès 50€ d'achat jusqu'au 30/11. Stop SMS 36173", "SMS", 0L, jour).isEmpty())
        assertTrue(Courrier.expediteurMarketing("news@okaidi.fr"))
        assertTrue(Courrier.expediteurMarketing("noreply@e.engie.fr"))
        assertTrue(Courrier.expediteurMarketing("contact@mailing.bpifrance.fr"))
        assertFalse(Courrier.expediteurMarketing("paul.martin@gmail.com"))
        assertFalse(Courrier.expediteurMarketing("facturation@edf.fr"))
        // Envoi de masse : publicité… sauf un vrai document.
        assertTrue(mail("Netatmo", "Votre maison connectée\nLaura vous attend lundi 30 novembre à 9h pour un atelier.", listOf(Courrier.EXPEDITEUR_MARKETING)).isEmpty())
        assertEquals(Courrier.FACTURE, mail("ENGIE", "Votre facture est disponible\nMontant : 84,12 €", listOf(Courrier.EXPEDITEUR_MARKETING)).single().type)
        // Un vrai SMS de rendez-vous reste.
        assertEquals(Courrier.RDV, Courrier.analyser("Garage Vitteaux", "Rappel : votre rendez-vous vidange est confirmé le 3/12 à 15h. Garage Vitteaux", "SMS", 0L, jour).single().type)
    }

    @Test fun smsVuParLaReceptionPuisParLaNotification() {
        val a = Courrier.Element("a", Courrier.RDV, "SMS", "+33612345678", "Rappel : votre rendez-vous vidange est confirmé le 3/12 à 15h.", 1_000_000L)
        val b = a.copy(id = "b", source = "Messages", emetteur = "Garage Vitteaux", ts = 1_030_000L)
        assertTrue(Courrier.memeMessage(a, b))
        assertFalse(Courrier.memeMessage(a, b.copy(ts = 1_000_000L + 3_600_000L)))
    }
}
