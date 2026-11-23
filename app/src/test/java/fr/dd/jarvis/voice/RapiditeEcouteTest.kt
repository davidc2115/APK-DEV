package fr.dd.jarvis.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RapiditeEcouteTest {
    @Test fun finDePhraseAnticipeeSeulementPourLesCommandesCompletes() {
        listOf("pause", "chanson suivante", "plus fort", "quelle heure est il", "mets mes titres j'aime", "quel temps fait il").forEach {
            assertTrue(it, VoiceManager.commandeComplete(it))
        }
        // Une suite pourrait changer la demande : la pause habituelle est gardée.
        listOf("envoie un message à paul", "rappelle moi de", "lance la navigation vers dijon", "joue daft punk", "mets ma playlist",
            "lance la navigation vers", "bonjour").forEach { assertFalse(it, VoiceManager.commandeComplete(it)) }
    }

    @Test fun commandesSimplesSansWhisper() {
        assertTrue(VoiceManager.commandeSimple("pause"))
        assertTrue(VoiceManager.commandeSimple("chanson suivante"))
        assertFalse(VoiceManager.commandeSimple("envoie un message à paul"))
    }

    @Test fun gainSansEcretage() {
        val g = GainAuto(max = 4.0)
        val faible = ShortArray(320) { if (it % 2 == 0) 120 else -120 }
        repeat(60) { g.appliquer(faible, faible.size) }
        assertTrue(g.gain > 3.0)
        // Voix forte juste après le silence : le gain est encore haut, mais la crête reste sous la saturation.
        val fort = ShortArray(320) { if (it % 2 == 0) 20_000 else -20_000 }
        val sortie = g.appliquer(fort, fort.size)
        assertTrue(sortie.maxOf { abs(it.toInt()) } <= 30_000)
    }
}
