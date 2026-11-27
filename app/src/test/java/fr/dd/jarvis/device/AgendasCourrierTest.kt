package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendasCourrierTest {
    private val compte = "dd@gmail.com"
    private fun local(id: Long, nom: String, ids: Set<String>, ecriture: Boolean = true, type: String = "com.google", c: String = compte, sync: Boolean = true) =
        CalendarRepo.DetailAgenda(id, nom, c, type, ids, 0, ecriture, sync)

    @Test fun unAgendaSynchroniseNApparaitQuUneFoisAvecLeNomDuTelephone() {
        val locaux = listOf(local(3, "Pro (DAVID)", setOf("abc123@group.calendar.google.com")))
        val distants = listOf(AgendasCourrier.Distant(compte, "abc123@group.calendar.google.com", "Ent pv. DAVID CORTOT", 0))
        val r = AgendasCourrier.reunir(locaux, distants)
        assertEquals(1, r.size)
        assertEquals("Pro (DAVID)", r[0].nom)
        assertEquals(3L, r[0].localId)
        assertEquals("abc123@group.calendar.google.com", r[0].googleId)
        assertEquals(AgendasCourrier.Lieu.GOOGLE_ET_TELEPHONE, r[0].lieu)
    }

    @Test fun memeAgendaVuParUnAutreCompteDuTelephoneReconnuParSonIdentifiant() {
        val locaux = listOf(local(5, "Famille", setOf("fam@group.calendar.google.com"), c = "autre@gmail.com"))
        val distants = listOf(AgendasCourrier.Distant(compte, "FAM@group.calendar.google.com", "Famille Cortot", 0))
        val r = AgendasCourrier.reunir(locaux, distants)
        assertEquals(1, r.size)
        assertEquals(5L, r[0].localId)
    }

    @Test fun lectureSeuleAfficheeMaisPasRetenue() {
        val locaux = listOf(local(7, "Super u", setOf("superu@import.calendar.google.com"), ecriture = false), local(8, "Perso", setOf(compte)))
        val r = AgendasCourrier.reunir(locaux, emptyList())
        val su = r.first { it.nom == "Super u" }
        assertFalse(su.ecriture)
        assertTrue(su.etiquette.contains("lecture seule"))
        assertEquals("Perso", r.first().nom)  // les agendas modifiables d'abord
        assertNull(AgendasCourrier.retrouver(r, "l:7\tSuper u"))
    }

    @Test fun agendaGoogleNonSynchroniseEtAgendaDuTelephoneSeul() {
        val locaux = listOf(local(9, "Téléphone", emptySet(), type = "LOCAL", c = "Téléphone"))
        val distants = listOf(AgendasCourrier.Distant(compte, "superu@group.calendar.google.com", "Super u", 0))
        val r = AgendasCourrier.reunir(locaux, distants)
        assertEquals(2, r.size)
        val su = r.first { it.nom == "Super u" }
        assertEquals(AgendasCourrier.Lieu.GOOGLE, su.lieu); assertNull(su.localId)
        assertEquals(AgendasCourrier.Lieu.TELEPHONE, r.first { it.nom == "Téléphone" }.lieu)
    }

    @Test fun agendaPartageAvecDeuxComptesReliesProposeUneSeuleFois() {
        val distants = listOf(AgendasCourrier.Distant(compte, "x@group.calendar.google.com", "Chantiers", 0, ecriture = false),
            AgendasCourrier.Distant("pro@gmail.com", "x@group.calendar.google.com", "Chantiers", 0, ecriture = true))
        val r = AgendasCourrier.reunir(emptyList(), distants)
        assertEquals(1, r.size); assertTrue(r[0].ecriture); assertEquals("pro@gmail.com", r[0].googleCompte)
    }
}
