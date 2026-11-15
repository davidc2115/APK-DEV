package fr.dd.jarvis.hermes

import fr.dd.jarvis.ai.MaisonDirecte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversation sur la maison : les suites restent à JARVIS, même en mode « Hermès pour tout ». */
class SuiteMaisonTest {
    private fun cible(t: String, suite: Boolean, precedent: String? = "Le volume de la télévision du salon est actuellement réglé à 23 %.") =
        Routeur.decider("tout", false, false, t, precedent, suiteMaison = suite).cible

    @Test fun suitesDeLaConversationMaison() {
        assertEquals(Routeur.Cible.JARVIS, cible("Peux-tu le régler avant ?", true))
        assertEquals(Routeur.Cible.JARVIS, cible("Là c'est bon, ça fonctionne", true))
        assertEquals(Routeur.Cible.JARVIS, cible("un peu plus fort", true))
        assertEquals(Routeur.Cible.JARVIS, cible("remets-la comme avant", true))
    }

    @Test fun horsConversationMaison() {
        // sans conversation maison en cours, une question ouverte reste à Hermès dans ce mode
        assertEquals(Routeur.Cible.HERMES, cible("Peux-tu le régler avant ?", false, precedent = "Voici le résumé de l'article."))
        // une vraie nouvelle demande (recherche web) ne devient pas « maison »
        assertEquals(Routeur.Cible.HERMES, cible("cherche sur internet le prix d'une pompe à chaleur", true))
    }

    @Test fun appareilsDeLaMaison() {
        listOf("allume le ventilo", "combien produisent les panneaux solaires aujourd'hui", "est-ce que la porte d'entrée est fermée",
            "quelle est la consommation électrique de la maison", "montre la caméra du jardin", "le lave-vaisselle a fini ?")
            .forEach { assertTrue(it, MaisonDirecte.concerne(it)) }
        assertEquals(Routeur.Cible.JARVIS, Routeur.decider("tout", false, false, "allume le ventilo", null).cible)
    }
}
