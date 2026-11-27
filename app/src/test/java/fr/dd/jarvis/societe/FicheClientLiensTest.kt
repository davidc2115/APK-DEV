package fr.dd.jarvis.societe

import fr.dd.jarvis.societe.core.Client
import fr.dd.jarvis.societe.core.Rendu
import fr.dd.jarvis.societe.core.Site
import fr.dd.jarvis.societe.core.Societe
import fr.dd.jarvis.societe.core.SocieteData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fiche client dans l'application : adresse, GPS, téléphone et mail cliquables ; Obsidian et PDF inchangés. */
class FicheClientLiensTest {
    private val c = Client("c1", "lapree", tel = "06 36 68 17 93", email = "lapree@exemple.fr", adresse = "47.298225, 4.388040", depuis = "2026-11-27",
        sites = listOf(Site("s1", "Installation principale", adresse = "21 rue de Massingy, 21350 Vitteaux", lat = 47.298225, lon = 4.38804, gpsPrecision = "MANUELLE", notes = "36kWc")))
    private val d = SocieteData(societe = Societe(nom = "Test"), clients = listOf(c))

    @Test fun liensDansLApplication() {
        val md = Rendu.rendreClient(d, c, interactif = true)
        assertTrue(md, md.contains("- Adresse : [47.298225, 4.388040](jarvis-nav:47.298225%2C%204.388040)"))
        assertTrue(md, md.contains("[06 36 68 17 93](tel:0636681793)"))
        assertTrue(md, md.contains("[lapree@exemple.fr](mailto:lapree@exemple.fr)"))
        assertTrue(md, md.contains("- 📍 [21 rue de Massingy, 21350 Vitteaux](jarvis-nav:21%20rue%20de%20Massingy%2C%2021350%20Vitteaux)"))
        // GPS : plus dans un bloc de code, donc cliquable.
        assertTrue(md, md.contains("GPS : [47.298225, 4.388040](jarvis-nav:47.298225%2C4.38804)"))
        assertFalse(md, md.contains("`["))
    }

    @Test fun obsidianEtPdfSansLiensInternes() {
        val md = Rendu.rendreClient(d, c)
        assertFalse(md, md.contains("jarvis-nav:") || md.contains("(tel:") || md.contains("(mailto:"))
        assertTrue(md, md.contains("`47.298225, 4.388040`"))
    }
}
