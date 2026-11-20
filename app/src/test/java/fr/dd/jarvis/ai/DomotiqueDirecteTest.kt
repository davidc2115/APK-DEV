package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomotiqueDirecteTest {
    private fun c(t: String) = DomotiqueDirecte.commande(fr.dd.jarvis.societe.core.normaliser(t))
    private val ents = listOf("light.salon_plafond" to "Plafonnier salon", "light.cuisine" to "Lumière cuisine", "cover.volet_chambre" to "Volet chambre",
        "cover.volet_salon" to "Volet salon", "switch.prise_tv" to "Prise TV", "media_player.tv_salon" to "Télé du salon", "switch.lampe_bureau" to "Lampe bureau")

    @Test fun commandes() {
        c("Allume la lumière du salon")!!.let { assertEquals("on", it.verbe); assertEquals(listOf("light", "switch"), it.domaines) }
        c("ferme les volets")!!.let { assertEquals("fermer", it.verbe); assertTrue(it.pluriel) }
        c("éteins la télé")!!.let { assertEquals("off", it.verbe); assertEquals(listOf("media_player"), it.domaines) }
        assertNull(c("allume le chauffage"))
        assertNull(c("mets le poêle à 20 degrés"))
        assertNull(c("ouvre le portail"))
        assertNull(c("allume la lumière du salon tous les soirs"))
        assertNull(c("allume le salon"))
        assertNull(c("ouvre la lumière"))
    }

    @Test fun candidats() {
        assertEquals(listOf("light.salon_plafond"), DomotiqueDirecte.candidats(ents, c("allume la lumière du salon")!!))
        assertEquals(listOf("cover.volet_chambre"), DomotiqueDirecte.candidats(ents, c("ferme le volet de la chambre")!!))
        assertEquals(2, DomotiqueDirecte.candidats(ents, c("ferme les volets")!!).size)
        // Lumière du bureau : une prise dont le nom dit « lampe ».
        assertEquals(listOf("switch.lampe_bureau"), DomotiqueDirecte.candidats(ents, c("allume la lampe du bureau")!!))
        assertEquals(listOf("media_player.tv_salon"), DomotiqueDirecte.candidats(ents, c("éteins la télé")!!))
    }

    @Test fun appareilDesigneParSonNom() {
        val e = ents + listOf("switch.prise_sapin" to "Sapin de Noël", "switch.chauffe_eau" to "Chauffe-eau", "input_boolean.mode_invites" to "Invités")
        c("allume le sapin")!!.let { assertTrue(it.libre); assertEquals("on", it.verbe); assertEquals(listOf("switch.prise_sapin"), DomotiqueDirecte.candidats(e, it)) }
        c("éteins le chauffe-eau")!!.let { assertEquals("off", it.verbe); assertEquals(listOf("switch.chauffe_eau"), DomotiqueDirecte.candidats(e, it)) }
        assertTrue(DomotiqueDirecte.candidats(e, c("allume la piscine")?.copy(cible = "inconnu") ?: c("allume le truc")!!).isEmpty())
        assertNull(c("lance la vidéo"))
        assertNull(c("allume les sapins"))
        assertNull(c("allume le salon"))
        assertNull(c("allume la salle de bain"))
    }
}
