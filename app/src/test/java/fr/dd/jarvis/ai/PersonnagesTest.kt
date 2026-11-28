package fr.dd.jarvis.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonnagesTest {
    private fun p(nom: String, description: String = "an old Gaulish chief with a grey beard") =
        Personnages.Personnage(nom, description, "original", "", "/x/image.png", "/x/reference.jpg")

    @Test fun personnagesProtegesReperesEtExpliques() {
        assertEquals(listOf("Astérix", "Obélix"), Personnages.proteges("une vidéo avec le phénix et Astérix et Obélix les Gaulois"))
        assertEquals(listOf("Pikachu"), Personnages.proteges("fais danser pikachu"))
        // Mythologie, histoire, noms courants : pas protégés.
        assertTrue(Personnages.proteges("Vercingétorix, Jeanne d'Arc, Thor et Hercule, Marianne et le capitaine Nemo").isEmpty())
        val refus = Personnages.refusProteges(listOf("Astérix", "Obélix"))
        assertTrue(refus, refus.contains("Astérix et Obélix sont des personnages protégés") && refus.contains("guerriers gaulois ORIGINAUX"))
        assertTrue(Personnages.refusProteges(listOf("Mickey")).contains("ses images"))
        assertEquals("", Personnages.refusProteges(emptyList()))
    }

    @Test fun remplacementDansLesPrompts() {
        assertEquals("a phoenix next to original ancient Gaulish warriors of our own design",
            Personnages.remplacerProteges("a phoenix next to Astérix and Obélix"))
        assertEquals("original characters of our own design dancing", Personnages.remplacerProteges("Pikachu dancing"))
        assertEquals("a phoenix over Paris", Personnages.remplacerProteges("a phoenix over Paris"))
    }

    @Test fun personnagesCitesEtDecrits() {
        val l = listOf(p("Brennos"), p("Jeanne d'Arc", "a young medieval French heroine in armour"))
        assertEquals(listOf("Brennos"), Personnages.cites(l, "Une vidéo TikTok avec BRENNOS devant le phénix").map { it.nom })
        assertEquals(listOf("Jeanne d'Arc"), Personnages.cites(l, "jeanne d'arc à cheval").map { it.nom })
        assertTrue(Personnages.cites(l, "Brennosaure géant").isEmpty())   // mot entier seulement
        assertEquals("Brennos (an old Gaulish chief with a grey beard) raises his sword", Personnages.decrire("Brennos raises his sword", listOf(l[0])))
        assertEquals("jeanne-d-arc", Personnages.slug("Jeanne d'Arc"))
    }

    @Test fun seulementLesImagesLibres() {
        assertTrue(Personnages.licenceLibre("pd", "Public domain"))
        assertTrue(Personnages.licenceLibre("cc0", "CC0"))
        assertTrue(Personnages.licenceLibre("", "Public domain"))
        assertFalse(Personnages.licenceLibre("cc-by-sa-4.0", "CC BY-SA 4.0"))
        assertFalse(Personnages.licenceLibre("cc-by-3.0", "CC BY 3.0"))
        val reponse = JSONObject("""{"query":{"pages":[
          {"title":"File:Statue.jpg","index":2,"imageinfo":[{"mime":"image/jpeg","width":1600,"height":1200,"thumburl":"https://t/statue.jpg","descriptionurl":"https://c/statue",
            "extmetadata":{"License":{"value":"cc-by-sa-2.0-fr"},"LicenseShortName":{"value":"CC BY-SA 2.0 fr"}}}]},
          {"title":"File:Lionel Royer - Vercingetorix.jpg","index":3,"imageinfo":[{"mime":"image/jpeg","width":2204,"height":1469,"thumburl":"https://t/royer.jpg","descriptionurl":"https://c/royer",
            "extmetadata":{"License":{"value":"pd"},"LicenseShortName":{"value":"Public domain"},"Artist":{"value":"<bdi><a href=\"x\">Lionel Royer</a></bdi>"}}}]},
          {"title":"File:Gravure.png","index":1,"imageinfo":[{"mime":"image/png","width":900,"height":1300,"thumburl":"https://t/gravure.png","descriptionurl":"https://c/gravure",
            "extmetadata":{"License":{"value":"pd"},"LicenseShortName":{"value":"Public domain"}}}]},
          {"title":"File:Minuscule.jpg","index":4,"imageinfo":[{"mime":"image/jpeg","width":120,"height":90,"thumburl":"https://t/m.jpg",
            "extmetadata":{"License":{"value":"pd"}}}]}
        ]}}""")
        val c = Personnages.candidats(reponse)
        assertEquals(listOf("Gravure", "Lionel Royer - Vercingetorix"), c.map { it.titre })   // ordre de pertinence, libres seulement
        assertEquals("Lionel Royer", c[1].auteur)
        assertEquals("https://t/royer.jpg", c[1].url)
    }

    @Test fun personnagesNeSontPlusInterditsDansLImage() {
        assertFalse(Montage.interdits("Brennos (an old Gaulish chief) raises his sword", avecPersonnages = true).contains("no people"))
        assertFalse(Montage.interdits("original ancient Gaulish warriors of our own design beside a phoenix").contains("no people"))
    }
}
