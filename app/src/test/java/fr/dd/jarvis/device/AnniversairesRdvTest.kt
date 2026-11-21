package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnniversairesRdvTest {
    private val auj = LocalDate.of(2026, 11, 21)

    @Test fun datesDesContacts() {
        assertEquals(Triple(1985, 3, 14), Anniversaires.parser("1985-03-14"))
        assertEquals(Triple(null, 3, 14), Anniversaires.parser("--03-14"))
        assertEquals(Triple(1990, 12, 2), Anniversaires.parser("02/12/1990"))
        assertEquals(Triple(null, 7, 1), Anniversaires.parser("1er juillet"))
        assertNull(Anniversaires.parser("n'importe quoi"))
    }

    @Test fun prochainEtAge() {
        val a = Anniversaires.Anniv("Marie", 11, 22, 1986)
        assertEquals(LocalDate.of(2026, 11, 22), Anniversaires.prochaine(a, auj))
        assertEquals(40, Anniversaires.age(a, LocalDate.of(2026, 11, 22)))
        assertEquals(LocalDate.of(2027, 3, 14), Anniversaires.prochaine(Anniversaires.Anniv("Paul", 3, 14), auj))
        // 29 février une année non bissextile → 28 février.
        assertEquals(LocalDate.of(2027, 2, 28), Anniversaires.prochaine(Anniversaires.Anniv("Léa", 2, 29), auj))
    }

    @Test fun pointsDuMatinEtDuSoir() {
        val l = listOf(Anniversaires.Anniv("Marie", 11, 22, 1986), Anniversaires.Anniv("Paul", 11, 21), Anniversaires.Anniv("Zoé", 11, 25))
        val soir = Anniversaires.texteBriefing(l, true, auj)!!
        assertTrue(soir.contains("Marie (40 ans)")); assertFalse(soir.contains("Paul"))
        val matin = Anniversaires.texteBriefing(l, false, auj)!!
        assertTrue(matin.startsWith("🎂 Aujourd'hui : anniversaire de Paul")); assertTrue(matin.contains("Marie (demain)")); assertTrue(matin.contains("Zoé"))
        assertNull(Anniversaires.texteBriefing(listOf(Anniversaires.Anniv("X", 1, 5)), true, auj))
    }

    @Test fun phrasesAnniversaires() {
        (Anniversaires.intention("L'anniversaire de Marie c'est le 14 mars") as Anniversaires.Intention.Ajouter).let {
            assertEquals("Marie", it.nom); assertEquals(3, it.mois); assertEquals(14, it.jour) }
        (Anniversaires.intention("retiens que l'anniversaire d'Hélène est le 2 août 1990") as Anniversaires.Intention.Ajouter).let {
            assertEquals("Hélène", it.nom); assertEquals(1990, it.annee) }
        (Anniversaires.intention("Paul Martin est né le 03/04/1979") as Anniversaires.Intention.Ajouter).let { assertEquals("Paul Martin", it.nom); assertEquals(4, it.mois) }
        assertEquals("Paul", (Anniversaires.intention("c'est quand l'anniversaire de Paul ?") as Anniversaires.Intention.DeQui).nom)
        assertEquals(7, (Anniversaires.intention("quels sont les anniversaires de la semaine ?") as Anniversaires.Intention.Lister).jours)
        assertTrue(Anniversaires.intention("quels sont les prochains anniversaires") is Anniversaires.Intention.Lister)
        assertTrue(Anniversaires.intention("oublie l'anniversaire de Paul") is Anniversaires.Intention.Supprimer)
        assertNull(Anniversaires.intention("rappelle-moi d'acheter un cadeau demain"))
        // La note d'un anniversaire daté n'est pas une note du carnet.
        assertNull(Carnet.intention("note que l'anniversaire de Marie c'est le 14 mars"))
    }

    @Test fun rendezVousConcernes() {
        val h = 3_600_000L
        assertTrue(SilenceRdv.eligible("Intervention Dupont", "", "", false, 0, h, emptyList(), false))
        assertTrue(SilenceRdv.eligible("Mme Durand", "Gray", "", false, 0, h, listOf("Durand"), false))
        assertFalse(SilenceRdv.eligible("Dentiste", "", "", false, 0, h, listOf("Durand"), false))
        assertTrue(SilenceRdv.eligible("Dentiste", "", "", false, 0, h, emptyList(), true))
        assertFalse(SilenceRdv.eligible("RDV chantier", "", "", true, 0, 24 * h, emptyList(), true))
        assertFalse(SilenceRdv.eligible("Anniversaire de Marie", "", "", false, 0, h, emptyList(), true))
    }

    @Test fun appelsManques() {
        assertEquals("Marie Dupont", SilenceRdv.appelant("Appel manqué", "Marie Dupont"))
        assertEquals("Marie Dupont", SilenceRdv.appelant("Marie Dupont", "Appel manqué"))
        assertEquals("06 12 34 56 78", SilenceRdv.appelant("Appel manqué", "06 12 34 56 78"))
        assertNull(SilenceRdv.appelant("3 appels manqués", "Marie, Paul"))
        assertTrue(SilenceRdv.estAppelManque("com.android.incallui", null, "Appel manqué", "Marie"))
        assertFalse(SilenceRdv.estAppelManque("com.whatsapp", null, "Marie", "tu as manqué le début"))
    }

    @Test fun phrasesModeRendezVous() {
        assertEquals(SilenceRdv.Intention.Activer(false), SilenceRdv.intention("mets-moi en silencieux automatique pendant mes rendez-vous clients"))
        assertEquals(SilenceRdv.Intention.Activer(true), SilenceRdv.intention("active le mode rendez-vous pour tous mes rendez-vous"))
        assertEquals(SilenceRdv.Intention.Desactiver, SilenceRdv.intention("désactive le mode rendez-vous"))
        assertEquals(SilenceRdv.Intention.Sms(true), SilenceRdv.intention("active la réponse automatique par SMS pendant mes rendez-vous"))
        assertEquals(SilenceRdv.Intention.Sms(false), SilenceRdv.intention("désactive la réponse automatique"))
        assertEquals(SilenceRdv.Intention.Etat, SilenceRdv.intention("est-ce que le mode rendez-vous est actif ?"))
        assertEquals("Je suis en intervention, je te rappelle vers {fin}", (SilenceRdv.intention("le message de la réponse automatique : Je suis en intervention, je te rappelle vers {fin}") as SilenceRdv.Intention.Message).texte)
        assertNull(SilenceRdv.intention("mets le téléphone en silencieux"))
        assertNull(SilenceRdv.intention("j'ai un rendez-vous demain à 10h"))
    }
}
