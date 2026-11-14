package fr.dd.jarvis.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiteProbableTest {
    @Test fun phrasesInachevees() {
        listOf(
            "lance la création de", "envoie un message à", "allume la lumière du salon et", "crée une facture pour",
            "mets la musique dans", "rappelle-moi de", "dis à Paul que", "ouvre la page des", "c'est le prix du",
            "mets le volume à 10,", "je vais", "et quel",
        ).forEach { assertTrue(it, VoiceManager.suiteProbable(it)) }
    }

    @Test fun phrasesCompletes() {
        listOf(
            "allume la lumière du salon", "quelle heure est-il", "comment vas-tu", "fais-le", "oui", "non merci",
            "mets la musique", "c'est qui", "c'est combien", "il y a", "on y va", "j'en veux un", "mais si",
            "envoie-le", "Jarvis", "", "   ",
        ).forEach { assertFalse(it, VoiceManager.suiteProbable(it)) }
    }
}
