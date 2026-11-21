package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RdvTerminesTest {
    private val auj = LocalDate.of(2026, 11, 20)
    private val z = ZoneId.of("Europe/Paris")
    private fun ms(h: Int, m: Int = 0, d: LocalDate = auj) = LocalDateTime.of(d, java.time.LocalTime.of(h, m)).atZone(z).toInstant().toEpochMilli()
    private fun ev(id: Long, titre: String, h: Int, fin: Int, d: LocalDate = auj) = EventInfo(id, 1, titre, ms(h, 0, d), ms(fin, 0, d), false, "", "", null)
    private fun terminer(t: String) = RdvTermines.intention(t, auj) as RdvTermines.Intention.Terminer

    @Test fun phrasesComprises() {
        assertTrue(RdvTermines.intention("le rendez-vous est terminé", auj) is RdvTermines.Intention.Terminer)
        assertTrue(RdvTermines.intention("rendez-vous terminé", auj) is RdvTermines.Intention.Terminer)
        assertTrue(RdvTermines.intention("j'ai fini mon rendez-vous", auj) is RdvTermines.Intention.Terminer)
        assertTrue(RdvTermines.intention("marque le rdv de Dupont comme terminé", auj) is RdvTermines.Intention.Terminer)
        terminer("le rendez-vous avec Dupont est terminé, mets-le en gris").let { assertEquals("gris", it.couleur); assertEquals(listOf("dupont"), it.cible.mots) }
        terminer("le rendez-vous de 14h est fini").let { assertEquals(14, it.cible.heure); assertNull(it.couleur) }
        assertTrue(terminer("tous mes rendez-vous d'aujourd'hui sont terminés").cible.tous)
        assertEquals("vert fonce", terminer("passe le rendez-vous en vert foncé, il est terminé").couleur)
        assertEquals(LocalDate.of(2026, 11, 19), terminer("le rendez-vous d'hier avec Martin est terminé").cible.jour)
    }

    @Test fun reglagesEtAutres() {
        assertEquals(RdvTermines.Intention.Reglage("gris", null), RdvTermines.intention("quand un rendez-vous est terminé, mets-le en gris", auj))
        assertEquals(RdvTermines.Intention.Reglage(null, false), RdvTermines.intention("pour les rendez-vous terminés, la couleur seulement dans l'application, pas sur Google", auj))
        assertTrue(RdvTermines.intention("le rendez-vous n'est pas terminé", auj) is RdvTermines.Intention.Reprendre)
        assertTrue(RdvTermines.intention("quels rendez-vous sont terminés aujourd'hui ?", auj) is RdvTermines.Intention.Lister)
        // Questions et autres sujets : pas pour ce réflexe.
        assertNull(RdvTermines.intention("à quelle heure se termine le rendez-vous ?", auj))
        assertNull(RdvTermines.intention("est-ce que le rendez-vous est terminé ?", auj))
        assertNull(RdvTermines.intention("j'ai fait 3 rendez-vous aujourd'hui", auj))
        assertNull(RdvTermines.intention("le minuteur est terminé", auj))
        assertNull(RdvTermines.intention("prends un rendez-vous demain à 10h", auj))
    }

    @Test fun bonRendezVousChoisi() {
        val l = listOf(ev(1, "Intervention Dupont", 9, 10), ev(2, "RDV Martin", 11, 12), ev(3, "Visite Durand", 15, 16))
        fun choix(t: String, h: Int, deja: Set<String> = emptySet()) =
            (RdvTermines.choisir(l, terminer(t).cible, ms(h), deja, zone = z) as RdvTermines.Choix.Trouve).l.map { it.id }
        assertEquals(listOf(2L), choix("le rendez-vous est terminé", 11))          // en cours
        assertEquals(listOf(2L), choix("le rendez-vous est terminé", 13))          // le dernier commencé
        assertEquals(listOf(1L), choix("le rendez-vous est terminé", 13, setOf("2|${ms(11)}")))  // déjà terminé → celui d'avant
        assertEquals(listOf(1L), choix("le rendez-vous avec Dupont est terminé", 13))
        assertEquals(listOf(3L), choix("le rendez-vous de 15h est terminé", 17))
        assertEquals(listOf(1L, 2L), choix("tous mes rendez-vous sont terminés", 13))
        assertTrue(RdvTermines.choisir(l, terminer("le rendez-vous est terminé").cible, ms(7), emptySet(), zone = z) is RdvTermines.Choix.Echec)
    }

    @Test fun nonCarnet() {
        assertNull(RdvTermines.intention("note que le client veut un devis", auj))
        // Ni le carnet de notes ni le mode rendez-vous ne prennent ces phrases.
        assertNull(Carnet.intention("le rendez-vous est terminé"))
        assertNull(Carnet.intention("le rendez-vous avec Dupont est terminé, mets-le en gris"))
        assertNull(SilenceRdv.intention("le rendez-vous est terminé"))
    }
}
