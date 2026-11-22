package fr.dd.jarvis.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioImagesTest {

    private fun refus(t: String, contacts: List<String> = emptyList()) = GardeImages.verifier(t, true, contacts)

    @Test fun detectionExplicite() {
        assertTrue(GardeImages.estExplicite("une femme nue sur un lit"))
        assertTrue(GardeImages.estExplicite("photo érotique en lingerie"))
        assertTrue(GardeImages.estExplicite("NSFW anime"))
        assertFalse(GardeImages.estExplicite("un chat roux sur un canapé"))
        assertFalse(GardeImages.estExplicite("un enfant pieds nus sur la plage"))
        assertFalse(GardeImages.estExplicite("un homme torse nu qui court"))
        assertFalse(GardeImages.estExplicite("une nuée d'oiseaux"))
    }

    @Test fun mineursToujoursRefuses() {
        listOf("une lycéenne sexy", "adolescente nue", "teen nude", "schoolgirl lingerie", "loli hentai",
            "femme nue de 16 ans", "fille nue, 15yo", "seize ans, nue", "jeune fille nue", "nude, childlike body", "écolière érotique")
            .forEach { assertEquals(it, GardeImages.Motif.MINEUR, refus(it)?.motif) }
    }

    @Test fun agesMajeursAcceptes() {
        listOf("femme nue de 25 ans", "nude woman, 30 years old", "femme de dix-huit ans", "femme de vingt-deux ans en lingerie",
            "woman twenty one years old, nude", "femme de 18 ans")
            .forEach { assertNull(it, GardeImages.ageMineur(it)) }
        assertEquals("16 ans", GardeImages.ageMineur("une femme de 16 ans"))
        assertNotNull(GardeImages.ageMineur("une fille de quinze ans"))
    }

    @Test fun consentementPersonnesReellesFamilleAnimaux() {
        assertEquals(GardeImages.Motif.CONSENTEMENT, refus("femme endormie nue")?.motif)
        assertEquals(GardeImages.Motif.CONSENTEMENT, refus("scène de viol")?.motif)
        assertEquals(GardeImages.Motif.CONSENTEMENT, refus("forced, nude")?.motif)
        assertEquals(GardeImages.Motif.PERSONNE_REELLE, refus("une célébrité nue")?.motif)
        assertEquals(GardeImages.Motif.PERSONNE_REELLE, refus("ma voisine en lingerie")?.motif)
        assertEquals(GardeImages.Motif.PERSONNE_REELLE, refus("sosie d'une actrice, nue")?.motif)
        assertEquals(GardeImages.Motif.INCESTE, refus("ma sœur nue")?.motif)
        assertEquals(GardeImages.Motif.ANIMAL, refus("femme nue avec un chien")?.motif)
    }

    @Test fun contactsProteges() {
        val contacts = listOf("Sophie Martin", "Maman", "Garage Dupont", "Karim", "Dr Leroy")
        assertEquals("Sophie Martin", refus("Sophie Martin nue", contacts)?.terme)
        assertEquals("Sophie Martin", refus("martin sophie en lingerie", contacts)?.terme)
        assertEquals("Sophie Martin", refus("une femme nommée Sophie, nue", contacts)?.terme)
        assertEquals("Karim", refus("karim torse nu sexy", contacts)?.terme)
        assertNull(refus("une femme rousse nue, lumière de bougies", contacts))
        assertNull(refus("homme en lingerie dans un garage", contacts))
    }

    @Test fun adulteFictifAccepte() {
        assertNull(refus("femme adulte rousse nue, style photo boudoir, lumière douce"))
        assertNull(refus("couple adulte enlacé, érotique, peinture à l'huile"))
        assertNull(refus("homme musclé nu, statue grecque"))
    }

    @Test fun rienBloqueHorsExplicite() {
        assertNull(GardeImages.verifier("un enfant qui joue au parc", false))
        assertNull(GardeImages.verifier("portrait d'une actrice des années 50", false))
    }

    @Test fun styleEtFormat() {
        assertEquals(PromptImage.Style.PHOTO, PromptImage.style("photo d'une femme en robe"))
        assertEquals(PromptImage.Style.ANIME, PromptImage.style("une guerrière style manga"))
        assertEquals(PromptImage.Style.TEXTE, PromptImage.style("une affiche avec le texte SOLDES"))
        assertEquals(PromptImage.Style.ART, PromptImage.style("peinture à l'huile d'un phare"))
        assertEquals(PromptImage.Style.RENDU3D, PromptImage.style("figurine 3D d'un robot"))
        assertEquals(PromptImage.Style.PHOTO, PromptImage.style("hyperréaliste, femme au bord de la mer"))
        assertEquals(PromptImage.Format.PORTRAIT, PromptImage.format("fond d'écran téléphone d'une forêt"))
        assertEquals(PromptImage.Format.PAYSAGE, PromptImage.format("panorama de montagnes en 16:9"))
        assertEquals(PromptImage.Format.CARRE, PromptImage.format("avatar carré"))
        assertNull(PromptImage.format("un chat"))
    }

    @Test fun choixDuModele() {
        val dispo = listOf("flux", "zimage", "turbo", "qwen-image", "gptimage", "kontext")
        assertEquals("zimage", PromptImage.choisirModele(PromptImage.Style.PHOTO, true, false, dispo).modele)
        assertEquals("flux", PromptImage.choisirModele(PromptImage.Style.ANIME, true, false, dispo).modele)
        assertEquals("qwen-image", PromptImage.choisirModele(PromptImage.Style.TEXTE, false, false, dispo).modele)
        assertEquals("turbo", PromptImage.choisirModele(PromptImage.Style.PHOTO, true, true, dispo).modele)
        // Jamais un modèle filtré pour l'adulte, même s'il ne reste que lui et un non filtré.
        assertEquals("flux", PromptImage.choisirModele(PromptImage.Style.PHOTO, true, false, listOf("gptimage", "flux")).modele)
        assertFalse(PromptImage.modere(PromptImage.choisirModele(PromptImage.Style.TEXTE, true, false, listOf("gptimage", "nanobanana", "wan-image")).modele))
        assertTrue(PromptImage.modere("gptimage") && PromptImage.modere("nanobanana-pro") && !PromptImage.modere("zimage"))
    }

    @Test fun finalisationAdulte() {
        val p = PromptImage.finaliser(PromptImage.enrichirLocal("femme rousse nue, photo", true), true)
        assertTrue(p.positif.startsWith("mature adults"))
        assertTrue(p.negatif.contains("underage") && p.negatif.contains("childlike"))
        val c = PromptImage.finaliser(PromptImage.enrichirLocal("un phare au coucher du soleil", false), false)
        assertFalse(c.positif.contains("adult"))
        assertTrue(c.negatif.contains("nsfw"))
    }

    @Test fun lectureReecriture() {
        val ok = PromptImage.lireReponse("```json\n{\"prompt\":\"a red-haired woman in an evening gown, candlelight, 85mm\",\"negatif\":\"blurry\",\"style\":\"photo\",\"format\":\"portrait\"}\n```",
            "femme rousse", false, "Gemini")
        assertTrue(ok is PromptImage.Reponse.Reecrit)
        ok as PromptImage.Reponse.Reecrit
        assertEquals(PromptImage.Style.PHOTO, ok.prompt.style)
        assertEquals(PromptImage.Format.PORTRAIT, ok.prompt.format)
        assertTrue(PromptImage.lireReponse("I'm sorry, but I can't help with that request.", "x", true, "p") is PromptImage.Reponse.Refuse)
        assertTrue(PromptImage.lireReponse("{\"refus\":\"mineur\"}", "x", true, "p") is PromptImage.Reponse.Refuse)
        assertTrue(PromptImage.lireReponse("Je ne peux pas générer ce contenu.", "x", true, "p") is PromptImage.Reponse.Refuse)
        assertTrue(PromptImage.lireReponse("", "x", true, "p") is PromptImage.Reponse.Illisible)
        val brut = PromptImage.lireReponse("cinematic photo of a lighthouse at dusk, golden hour", "phare", false, "p")
        assertTrue(brut is PromptImage.Reponse.Reecrit)
    }

    @Test fun urlEtTaille() {
        val u = Pollinations.urlImage("a woman / red hair? #1", "blurry", "zimage", 832, 1216, 42, true)
        assertTrue(u.startsWith("https://gen.pollinations.ai/image/a%20woman%20red%20hair%201?"))
        assertTrue(u.contains("model=zimage") && u.contains("width=832") && u.contains("height=1216") && u.contains("seed=42"))
        assertTrue(u.contains("safe=false") && u.contains("private=true") && u.contains("nologo=true") && u.contains("negative_prompt=blurry"))
        assertTrue(Pollinations.urlImage("x", "", "flux", 1024, 1024, 1, false, filtre = true).let { it.startsWith("https://image.pollinations.ai/prompt/x?") && it.contains("safe=true") && it.contains("width=768") })
        assertEquals(Pair(512, 768), Pollinations.taille(832, 1216, false))
        assertEquals(Pair(1216, 832), Pollinations.taille(1216, 832, true))
    }

    @Test fun listeDesModeles() {
        val objets = Pollinations.parserModeles("""[{"name":"flux","description":"Flux Schnell","paid_only":false,"aliases":["flux-schnell"],"output_modalities":["image"]},
            {"name":"nanobanana-pro","paid_only":true,"output_modalities":["image"]},{"name":"veo","output_modalities":["video"]}]""")
        assertEquals(listOf("flux", "nanobanana-pro"), objets.map { it.nom })
        assertTrue(objets[1].payant)
        assertEquals(listOf("flux-schnell"), objets[0].aliases)
        assertEquals(listOf("flux", "turbo"), Pollinations.parserModeles("[\"flux\",\"turbo\"]").map { it.nom })
        assertTrue(Pollinations.parserModeles("pas du json").isEmpty())
    }

    @Test fun erreursLisibles() {
        assertTrue(Pollinations.messageErreur(402, "{\"error\":{\"message\":\"Insufficient pollen\"}}", true).contains("Pollen"))
        assertTrue(Pollinations.messageErreur(401, "", false).contains("enter.pollinations.ai"))
        assertTrue(Pollinations.messageErreur(429, "", false).contains("sans clé"))
        assertTrue(Pollinations.estImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(100)))
        assertFalse(Pollinations.estImage("<html>error</html>".toByteArray() + ByteArray(100)))
    }
}
