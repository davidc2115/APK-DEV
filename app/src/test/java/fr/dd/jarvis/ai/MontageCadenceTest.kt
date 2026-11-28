package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MontageCadenceTest {
    /** 16 images/s → 30 images/s constants : chaque image reprise 1 ou 2 fois, durée conservée. */
    @Test fun seizeVersTrente() {
        val t = Montage.repartitionTrames(65, 16.0)
        assertEquals(65, t.size)
        assertTrue(t.all { it in 1..2 })
        assertEquals(Math.round(65 * 30 / 16.0).toInt(), t.sum())          // 122 images de 1/30 s = 4,06 s, comme le clip
        assertEquals(List(10) { 1 }, Montage.repartitionTrames(10, 30.0))  // déjà à 30 : rien à répéter
        assertEquals(30, Montage.repartitionTrames(24, 24.0).sum())        // 24 i/s pendant 1 s → 30 images
    }

    /** Le phénix tricolore est imposé aux plans sans symbole de la chaîne ; les plans déjà dans le thème restent tels quels. */
    @Test fun sujetRecurrentImpose() {
        val a = Montage.ANCRE_PHENIX
        assertTrue(Montage.ancrer("a glowing orb floating in a dark alley", a).startsWith(a))
        assertTrue(Montage.ancrer("a masked woman walking through a glowing portal", a).startsWith(a))
        assertEquals("a proud French rooster on a fence", Montage.ancrer("a proud French rooster on a fence", a))
        assertEquals("the Eiffel Tower at dusk", Montage.ancrer("the Eiffel Tower at dusk", a))
        assertEquals("phoenix rising from ashes", Montage.ancrer("phoenix rising from ashes", a))
        assertEquals("anything", Montage.ancrer("anything", ""))
    }

    /** Jamais de texte dans l'image ; pas de personnage sauf si le plan en parle (pompiers, agriculteurs…). */
    @Test fun interditsDeLImage() {
        val sansPersonne = Montage.interdits("phoenix over ruins")
        assertTrue(sansPersonne.contains("no text") && sansPersonne.contains("no people") && sansPersonne.contains("no masks"))
        val pompiers = Montage.interdits("firefighters in front of a burning barn")
        assertTrue(pompiers.contains("no text") && !pompiers.contains("no people"))
    }

    /** Le meilleur export garde le son et le texte : 1 ou 2 images abîmées valent mieux qu'une vidéo muette sans accroche. */
    @Test fun meilleurExportGardeSonEtTexte() {
        val completLegerDefaut = Montage.scoreExport(avecSon = true, avecTexte = true, abimees = 4, cote = 1080)
        val muetSansTexte = Montage.scoreExport(avecSon = false, avecTexte = false, abimees = 1, cote = 720)
        assertTrue(completLegerDefaut > muetSansTexte)
        val completPropreBasse = Montage.scoreExport(true, true, 0, 432)
        val completUneImageHd = Montage.scoreExport(true, true, 1, 1080)
        assertTrue(completUneImageHd > completPropreBasse)                     // 1 image abîmée en HD plutôt que du 432p flou
        assertTrue(Montage.scoreExport(true, true, 0, 720) > completUneImageHd) // propre en 720p : le mieux
        assertTrue(Montage.scoreExport(true, false, 0, 1080) > Montage.scoreExport(false, true, 0, 1080)) // le son d'abord
    }

    /** Personnages protégés jamais reproduits : remplacés par des guerriers gaulois originaux. */
    @Test fun personnagesProtegesRemplaces() {
        val v = Montage.sansPersonnagesProteges("a tricolor phoenix with Astérix et Obélix standing beside it")
        assertFalse(v.contains("Astérix", true) || v.contains("Obélix", true))
        assertTrue(v.contains("original ancient Gaulish warriors"))
        assertEquals("a phoenix over Paris", Montage.sansPersonnagesProteges("a phoenix over Paris"))
    }
}
