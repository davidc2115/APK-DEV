package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DatesFrTest {
    private val ven = LocalDate.of(2026, 11, 20)   // vendredi
    private fun j(t: String, futur: Boolean = true) = DatesFr.jour(DatesFr.normaliser(t), ven, futur)?.date

    @Test fun joursEtDates() {
        assertEquals(LocalDate.of(2026, 11, 23), j("mon rendez-vous de lundi"))
        assertEquals(LocalDate.of(2026, 11, 20), j("vendredi"))
        assertEquals(LocalDate.of(2026, 11, 27), j("vendredi prochain"))
        assertEquals(LocalDate.of(2026, 11, 23), j("lundi prochain"))
        assertEquals(LocalDate.of(2026, 11, 30), j("lundi en huit"))
        assertEquals(LocalDate.of(2026, 11, 25), j("mercredi de la semaine prochaine"))
        assertEquals(LocalDate.of(2026, 11, 16), j("lundi dernier"))
        assertEquals(LocalDate.of(2026, 11, 21), j("demain"))
        assertEquals(LocalDate.of(2026, 11, 22), j("après-demain"))
        assertEquals(LocalDate.of(2026, 11, 24), j("mardi 24"))
        assertEquals(LocalDate.of(2026, 11, 24), j("le 24 novembre"))
        assertEquals(LocalDate.of(2026, 12, 1), j("le 1er décembre"))
        assertEquals(LocalDate.of(2026, 11, 24), j("24/11"))
        assertEquals(LocalDate.of(2027, 1, 5), j("le 05/01"))
        assertEquals(LocalDate.of(2026, 11, 24), j("2026-11-24"))
        assertEquals(LocalDate.of(2026, 11, 24), j("le 24.11.2026"))
        assertEquals(LocalDate.of(2026, 12, 2), j("le 2"))
        assertEquals(LocalDate.of(2026, 11, 2), j("le 2", futur = false))
        assertEquals(LocalDate.of(2026, 11, 23), j("dans 3 jours"))
        assertEquals(LocalDate.of(2026, 11, 27), j("dans une semaine"))
        assertEquals(LocalDate.of(2027, 3, 3), j("le 3 mars"))
        assertNull(j("mon 2e rendez-vous"))
        assertNull(j("le rendez-vous de 14 h"))
        assertNull(j("le 1er rendez-vous"))
        assertEquals("lundi 23 novembre", DatesFr.libelle(LocalDate.of(2026, 11, 23), ven))
        assertEquals("demain", DatesFr.libelle(LocalDate.of(2026, 11, 21), ven))
        assertEquals("après-midi", DatesFr.partie(DatesFr.normaliser("lundi après-midi"))?.first)
    }

    @Test fun planningAUneDatePrecise() {
        val maintenant = java.time.LocalDateTime.of(2026, 11, 20, 11, 0)
        fun date(p: String) = CommandesDirectes.intention(p, maintenant)?.takeIf { it.outil == "get_planning" }?.args?.optString("date")
        assertEquals("2026-11-23", date("montre-moi mon planning de lundi"))
        assertEquals("2026-11-24", date("mon planning du 24 novembre"))
        assertEquals("2026-11-24", date("donne-moi mon planning du 24/11"))
        assertEquals("2026-12-03", date("qu'est-ce que j'ai de prévu le 3 décembre"))
        assertEquals("2026-11-30", date("mon planning de lundi en huit"))
        assertEquals("2026-11-23", date("mon agenda dans 3 jours"))
        assertEquals("2026-11-21", date("mon planning de demain"))
    }

    @Test fun indicePourLIA() {
        val i = DatesFr.indice("lance l'itinéraire vers mon rendez-vous de lundi", ven)
        assertEquals(true, i.contains("lundi 23 novembre 2026") && i.contains("2026-11-23"))
        assertEquals("", DatesFr.indice("quelle heure est-il", ven))
        assertEquals("", DatesFr.indice("mon planning d'aujourd'hui", ven))
    }
}
