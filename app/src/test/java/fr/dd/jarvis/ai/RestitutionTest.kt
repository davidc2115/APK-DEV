package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestitutionTest {
    @Test fun consignesExplicitesDetectees() {
        assertEquals("musique", ConsignesExplicites.detecter("Quand tu mets une musique, donne-moi seulement le titre et l'artiste")?.first)
        assertEquals("localisation", ConsignesExplicites.detecter("Pour la localisation d'une personne, donne-moi seulement l'adresse exacte, pas d'autres informations")?.first)
        assertEquals("reponses", ConsignesExplicites.detecter("À l'avenir, réponds-moi uniquement en deux phrases")?.first)
        assertNull(ConsignesExplicites.detecter("mets du Johnny Hallyday s'il te plaît"))
        assertNull(ConsignesExplicites.detecter("donne-moi seulement l'adresse de Paul"))
        assertNull(ConsignesExplicites.detecter("est-ce que tu mets toujours la musique trop fort ?"))
    }

    @Test fun consignesDeFormeQuiConcernentLEchange() {
        val memoire = mapOf(
            "musique" to "- Toujours passer par YT Music Morphe\n- Donne seulement le titre et l'artiste du morceau lancé",
            "localisation" to "- Pour la position d'une personne, seulement l'adresse exacte",
            "contacts" to "- Fiches au format Obsidian")
        val musique = Restitution.consignes(memoire, "mets Johnny Hallyday", "▶️ « Johnny » est en lecture dans ta bibliothèque en aléatoire depuis ta bibliothèque.")
        assertEquals(1, musique.size)
        assertTrue(musique.first().contains("titre et l'artiste"))
        val position = Restitution.consignes(memoire, "où est Angeline", "📍 Angeline — zone maison\n- GPS 47.1, 5.2\n- précision 12 m")
        assertTrue(position.single().contains("adresse exacte"))
        assertTrue(Restitution.consignes(memoire, "quel temps fait-il", "☀️ 18° à Vesoul, vent faible, ciel dégagé toute la journée.").isEmpty())
    }

    @Test fun consigneAjouteeSansEffacerLesAutres() {
        assertTrue(fr.dd.jarvis.device.Memo.proximite("Donne seulement le titre et l'artiste", "Donne-moi seulement le titre et l'artiste du morceau") >= 0.6)
        assertTrue(fr.dd.jarvis.device.Memo.proximite("Toujours passer par YT Music Morphe", "Donne seulement le titre et l'artiste") < 0.6)
    }
}
