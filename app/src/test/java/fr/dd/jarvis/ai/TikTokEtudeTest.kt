package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TikTokEtudeTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun t(j: Int, h: Int) = LocalDateTime.of(2026, 11, j, h, 15).atZone(paris).toEpochSecond()
    private fun v(id: String, auteur: String, vues: Long, cree: Long = 0, titre: String = "t") =
        TikWm.Video(id, titre, auteur, vues, 0, 0, 0, 0, 20, cree, "", false, "", "")

    private val miennes = listOf(
        v("1", "dd", 1_000, t(2, 18), "Le phénix tricolore renaît #france"),
        v("2", "dd", 9_000, t(5, 19), "Marianne et le coq gaulois"),
        v("3", "dd", 500, t(8, 7), "Pourquoi la France tient debout"),
    )

    @Test fun rangDesVideos() {
        assertEquals("3", TikTokEtude.rang("la dernière", miennes)?.id)
        assertEquals("2", TikTokEtude.rang("ma vidéo la plus vue", miennes)?.id)
        assertEquals("3", TikTokEtude.rang("la moins vue", miennes)?.id)
        assertEquals("2", TikTokEtude.rang("l'avant-dernière", miennes)?.id)
        assertEquals("3", TikTokEtude.rang("1", miennes)?.id)
        assertEquals("1", TikTokEtude.rang("la 3e", miennes)?.id)
        assertEquals("2", TikTokEtude.rang("ma 2e vidéo", miennes)?.id)
        // Un titre n'est pas un rang : « meilleure » ou un nombre dans une longue phrase désignent un sujet.
        assertNull(TikTokEtude.rang("ma vidéo sur la meilleure baguette de toute la France", miennes))
        assertNull(TikTokEtude.rang("la vidéo des 3 mousquetaires", miennes))
        assertNull(TikTokEtude.rang("1789", miennes))
        assertNull(TikTokEtude.rang("la 9e", miennes))
    }

    @Test fun surperformancesRelativesAuCompte() {
        // Un petit compte qui fait 6× sa médiane compte plus qu'un gros compte qui fait sa moyenne.
        val l = listOf(
            v("a1", "petit", 3_000), v("a2", "petit", 2_500), v("a3", "petit", 18_000),
            v("b1", "gros", 500_000), v("b2", "gros", 520_000), v("b3", "gros", 480_000),
        )
        val s = TikTokEtude.surperformances(l)
        assertEquals(listOf("a3"), s.map { it.first.id })
        assertTrue(s[0].second > 5.9)
    }

    @Test fun creneauxEtJours() {
        val pts = listOf(t(2, 18) to 3.0, t(9, 19) to 2.0, t(3, 7) to 0.5, t(10, 6) to 0.4, t(4, 12) to 1.0)
        val (heures, jours) = TikTokEtude.creneaux(pts)
        assertEquals("18h-20h", heures.first().libelle)
        assertEquals(2.5, heures.first().ratioMedian, 0.001)
        assertEquals(2, heures.first().n)
        assertEquals(listOf("18h-20h", "06h-08h"), heures.map { it.libelle })   // 12 h : une seule vidéo, écarté
        assertEquals("lundi", jours.first().libelle)                          // 2 et 9 novembre 2026 : des lundis
    }

    @Test fun conseilPrivilegieTesDonnees() {
        val moi = listOf(TikTokEtude.Creneau("18h-20h", 2.0, 3))
        val conc = listOf(TikTokEtude.Creneau("12h-14h", 2.5, 4), TikTokEtude.Creneau("18h-20h", 1.2, 5))
        assertEquals(listOf("18h-20h", "12h-14h"), TikTokEtude.conseil(moi, conc))
    }

    @Test fun ratiosParCompte() {
        val r = TikTokEtude.ratios(listOf(v("1", "x", 100, 10), v("2", "x", 300, 20), v("3", "y", 50, 30)))
        assertEquals(listOf(0.5, 1.5, 1.0), r.map { it.second })
    }

    @Test fun fichiersDesConcurrentsReconnus() {
        val c = v("7300000000000000009", "elec.pro_2", 1)
        assertEquals("tiktok_moi_7300000000000000009.mp4", TikTokEtude.nomFichier(c, true))
        val nom = TikTokEtude.nomFichier(c, false)
        assertEquals("tiktok_de_elec.pro_2_7300000000000000009.mp4", nom)
        assertEquals("elec.pro_2", TikTokEtude.auteurConcurrent(nom))
        assertEquals("elec.pro_2", TikTokEtude.auteurConcurrent("/data/cache/pieces_jointes/$nom"))
        assertNull(TikTokEtude.auteurConcurrent("tiktok_moi_7300000000000000009.mp4"))
        assertNull(TikTokEtude.auteurConcurrent("tiktok_1795884948282.mp4"))   // vidéo montée par JARVIS
    }
}
