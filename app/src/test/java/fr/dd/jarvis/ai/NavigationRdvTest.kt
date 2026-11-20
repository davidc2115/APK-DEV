package fr.dd.jarvis.ai

import fr.dd.jarvis.device.EventInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NavigationRdvTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun t(h: Int, m: Int = 0, jour: Int = 20) = LocalDateTime.of(2026, 11, jour, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ev(id: Long, titre: String, h: Int, lieu: String, jour: Int = 20) = EventInfo(id, 1, titre, t(h, 0, jour), t(h + 1, 0, jour), false, lieu, "", null)

    private val agenda = listOf(
        ev(1, "Maintenance onduleur — Dupont", 9, "12 rue des Lilas, Dijon"),
        ev(2, "Visite Martin", 14, "3 place Darcy, Dijon"),
        ev(3, "Contrôle panneaux Bernard", 16, "5 route de Beaune, Nuits"),
        ev(4, "Chantier Leroy", 10, "8 rue Neuve, Auxonne", jour = 21),
        EventInfo(5, 1, "Congés Thomas", t(0), t(23), true, "", "", null),
    )

    private fun choix(phrase: String, maintenant: Long = t(11)) =
        (NavigationRdv.choisir(agenda, NavigationRdv.demande(phrase)!!, maintenant, zone) as? NavigationRdv.Choix.Trouve)?.e?.id

    @Test fun reconnaitLesDemandes() {
        assertNotNull(NavigationRdv.demande("Lance la navigation vers mon rendez-vous"))
        assertNotNull(NavigationRdv.demande("emmène-moi à mon 1er rendez-vous d'aujourd'hui"))
        assertNotNull(NavigationRdv.demande("lance le GPS chez Dupont"))
        assertNull(NavigationRdv.demande("quel est mon prochain rendez-vous"))
        assertNull(NavigationRdv.demande("lance la navigation vers la gare de Dijon"))
        assertNull(NavigationRdv.demande("emmène-moi chez moi"))
    }

    @Test fun choisitLeBonRendezVous() {
        assertEquals(2L, choix("lance la navigation vers mon rendez-vous"))          // 11 h : le prochain
        assertEquals(1L, choix("emmène-moi à mon 1er rendez-vous d'aujourd'hui"))
        assertEquals(1L, choix("lance la navigation vers mon 1 et rendez d'aujourd'hui"))
        assertEquals(3L, choix("navigation vers mon dernier rendez-vous"))
        assertEquals(3L, choix("guide-moi au rendez-vous de 16h"))
        assertEquals(3L, choix("lance la navigation vers le rendez-vous de Bernard"))
        assertEquals(4L, choix("navigation vers le chantier Leroy"))                 // un autre jour : retrouvé par le nom
        assertEquals(4L, choix("emmène-moi à mon rendez-vous de demain"))
        assertEquals(1L, choix("navigation vers mon rendez-vous", maintenant = t(9, 20)))   // en cours
        // Mots dictés approximativement : ignorés.
        assertEquals(2L, choix("lance la navigation vers dmon rendez-vous"))
        val echec = NavigationRdv.choisir(agenda, NavigationRdv.demande("lance le GPS chez Durand")!!, t(11), zone)
        assertTrue(echec is NavigationRdv.Choix.Echec)
    }

    @Test fun lePlanningNeTerminePlusUneDemandeDAction() {
        assertTrue(Brain.ACTION_APRES_LECTURE.containsMatchIn("lance la navigation vers mon rendez vous"))
        assertTrue(!Brain.ACTION_APRES_LECTURE.containsMatchIn("montre moi mon planning de demain"))
    }
}

class NavigationRdvAvantTest {
    @Test fun aucunReflexePrecedentNeLIntercepte() {
        for (p in listOf("Lance la navigation vers mon rendez-vous", "emmène-moi à mon 1er rendez-vous d'aujourd'hui", "lance le GPS chez Dupont")) {
            org.junit.Assert.assertNull(p, MaisonDirecte.analyser(p))
            org.junit.Assert.assertNull(p, PoeleDirect.demande(p))
            org.junit.Assert.assertNull(p, PoeleDirect.lecture(p))
            org.junit.Assert.assertNull(p, fr.dd.jarvis.device.ModeSonore.intention(p))
        }
    }
}
