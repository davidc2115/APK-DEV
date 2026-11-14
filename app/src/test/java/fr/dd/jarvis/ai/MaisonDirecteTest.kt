package fr.dd.jarvis.ai

import fr.dd.jarvis.ai.MaisonDirecte.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaisonDirecteTest {
    private fun media(t: String) = MaisonDirecte.analyser(t)?.media

    @Test fun nombresEnLettres() {
        assertEquals("mets la chaine 23", MaisonDirecte.chiffres("mets la chaine vingt trois"))
        assertEquals("passe sur france 2", MaisonDirecte.chiffres("passe sur france deux"))
        assertEquals("mets la 1", MaisonDirecte.chiffres("mets la une"))
        assertEquals("volume a 75", MaisonDirecte.chiffres("volume a soixante quinze"))
        assertEquals("volume a 91", MaisonDirecte.chiffres("volume a quatre vingt onze"))
        assertEquals("mets une scene", MaisonDirecte.chiffres("mets une scene"))
    }

    @Test fun chaines() {
        assertEquals(Media.Chaine(1, "TF1"), media("Mets TF1"))
        assertEquals(Media.Chaine(2, "France 2"), media("Jarvis, passe sur France 2 sur la télé du salon"))
        assertEquals(Media.Chaine(5, "France 5"), media("mets la chaîne 5"))
        assertEquals(Media.Chaine(13, "BFM TV"), media("Mets la 13"))
        assertEquals(Media.Chaine(20, "TF1 Séries Films"), media("mets TF1 Séries Films"))
        assertEquals(Media.Chaine(1, "TF1"), media("mets la une"))
        assertEquals(Media.Chaine(6, "M6"), media("allume la télé sur M6"))
        assertTrue(MaisonDirecte.analyser("allume la télé sur M6")!!.allumer)
        assertEquals(Media.Zapping(true), media("change de chaîne"))
        assertEquals(Media.Zapping(true), media("chaîne suivante"))
        assertEquals(Media.Zapping(false), media("reviens sur la chaîne précédente"))
    }

    @Test fun volumeEtLecture() {
        assertEquals(Media.Volume(1, 4), media("monte le son de la télé"))
        assertEquals(Media.Volume(-1, 2), media("baisse un peu le volume de la télé"))
        assertEquals(Media.VolumeA(20), media("mets le volume de la télé à 20"))
        assertEquals(Media.Muet(true), media("coupe le son de la télé"))
        assertEquals(Media.Muet(false), media("remets le son de la télé"))
        assertEquals(Media.Lecture(true), media("mets la télé en pause"))
        assertEquals(Media.Source("netflix"), media("mets Netflix sur la télé"))
        assertEquals(Media.Source("hdmi 2"), media("passe sur HDMI 2"))
    }

    @Test fun cibleDeLaPiece() {
        assertEquals(listOf("salon"), MaisonDirecte.analyser("mets TF1 sur la télé du salon")!!.mots)
        assertEquals(listOf("chambre"), MaisonDirecte.analyser("monte le son de la télé de la chambre")!!.mots)
    }

    @Test fun pasPourLaTele() {
        assertNull(media("monte le son"))                       // téléphone
        assertNull(media("mets la musique"))
        assertNull(media("quelle chaîne passe le match ce soir ?"))
        assertNull(media("enregistre TF1 ce soir"))
        assertNull(media("mets un minuteur de 5 minutes"))
        assertNull(media("abonne-toi à ma chaîne YouTube"))
        assertNull(media("lance YouTube"))                      // appli du téléphone
        assertNull(media("mets 20 minutes"))
    }

    @Test fun lumieresEtScenes() {
        assertEquals(MaisonDirecte.Lumiere.Pourcent("lumiere du salon", 40), MaisonDirecte.analyserLumiere("mets la lumière du salon à 40 %"))
        assertEquals(MaisonDirecte.Lumiere.Pas("lumiere du salon", -25), MaisonDirecte.analyserLumiere("baisse la lumière du salon"))
        assertEquals(MaisonDirecte.Lumiere.Scene("scene", "cinema"), MaisonDirecte.analyserLumiere("active la scène cinéma"))
        assertNull(MaisonDirecte.analyserLumiere("allume la lumière du salon"))      // déjà géré par DomotiqueDirecte
    }

    @Test fun aiguillageMaison() {
        assertTrue(MaisonDirecte.concerne("change de chaîne sur la télé"))
        assertTrue(MaisonDirecte.concerne("règle le chauffage du salon à 20 degrés"))
        assertTrue(MaisonDirecte.concerne("est-ce que les volets sont fermés ?"))
        assertFalse(MaisonDirecte.concerne("redémarre le serveur Home Assistant"))
        assertFalse(MaisonDirecte.concerne("combien d'abonnés sur ma chaîne YouTube"))
        assertFalse(MaisonDirecte.concerne("prise de rendez-vous chez le dentiste"))
    }
}
