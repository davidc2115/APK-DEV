package fr.dd.jarvis.hermes

import fr.dd.jarvis.ai.MaisonDirecte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        listOf("allume le ventilo", "combien produisent mes panneaux solaires aujourd'hui", "est-ce que la porte d'entrée est fermée",
            "quelle est la consommation électrique de la maison", "montre la caméra du jardin", "le lave-vaisselle a fini ?",
            "quelle est la production des panneaux de la maison", "mon onduleur est en défaut ?")
            .forEach { assertTrue(it, MaisonDirecte.concerne(it)) }
        assertEquals(Routeur.Cible.JARVIS, Routeur.decider("tout", false, false, "allume le ventilo", null).cible)
    }

    @Test fun photovoltaiqueDeLaSociete() {
        // Panneaux, onduleurs, production : la société (clients, interventions), pas la maison.
        listOf("combien produisent les panneaux solaires du client Dupont", "l'onduleur de M. Martin est en défaut",
            "planifie une intervention sur les panneaux de Mme Durand", "prépare un devis pour un onduleur", "quelle est la production de l'installation de Clairotte 3",
            "les panneaux solaires", "contrôle de l'onduleur chez monsieur Petit")
            .forEach { assertFalse(it, MaisonDirecte.concerne(it)) }
        // même pendant une conversation sur la maison, une demande de la société n'est pas une « suite maison »
        assertEquals(Routeur.Cible.HERMES, cible("et l'onduleur du client Dupont ?", true, precedent = "Volume réglé à 20 %."))
    }
}
