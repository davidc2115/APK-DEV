package fr.dd.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteurActionsTest {
    private fun cible(t: String, mode: String = "tout") = Routeur.decider(mode, false, false, t, null, rapide = true).cible

    @Test fun actionsChezJarvis() {
        for (t in listOf("envoie un mail à Paul pour lui expliquer pourquoi je suis en retard", "résume mes mails non lus", "crée un devis pour Martin",
            "ajoute un rendez-vous chez Martin jeudi à 14h", "rappelle-moi tous les jours à 8h de prendre mes médicaments", "lance Avatar sur la télé",
            "cherche le mail des impôts", "quel temps fait-il demain", "supprime les appareils sans nom de la box", "analyse les factures impayées",
            "trouve le mail de Paul puis réponds-lui que c'est d'accord", "résume les messages du salon discussion sur Discord"))
            assertEquals(t, Routeur.Cible.JARVIS, cible(t))
        assertEquals(Routeur.Cible.JARVIS, cible("allume la lumière du salon", "auto"))
    }

    @Test fun hermesGardeLeReste() {
        for (t in listOf("Hermès, trie mes mails", "explique-moi le fonctionnement d'un onduleur", "cherche sur internet le prix des panneaux",
            "redémarre le conteneur Plex sur le serveur", "tous les lundis envoie-moi les factures impayées", "surveille Plex et préviens-moi s'il s'arrête",
            "écris un poème sur la mer", "c'est quoi la capitale du Pérou", "écris un script python qui trie des fichiers"))
            assertEquals(t, Routeur.Cible.HERMES, cible(t))
    }

    @Test fun serveurDiscordPasSsh() {
        assertTrue(ServeurInventaire.serveurDemande("montre l'espace disque du serveur"))
        assertFalse(ServeurInventaire.serveurDemande("résume les messages du serveur discord B&G"))
        assertTrue(ServeurInventaire.serveurDemande("redémarre le bot discord sur le serveur"))
        assertTrue(fr.dd.jarvis.ai.DiscordNoms.cite("résume le salon discussion du serveur B&G", setOf("b et g", "discussion")))
        assertFalse(fr.dd.jarvis.ai.DiscordNoms.cite("allume la lumière du salon", setOf("b et g", "salon", "general")))
        assertTrue(RelaisGroq.domaines("lis les messages du serveur discord B&G").contains("discord"))
        assertFalse(RelaisGroq.domaines("lis les messages du serveur discord B&G").contains("ssh_"))
    }

    /** « Fais une vidéo TikTok en lien avec ma niche avec Pixabay » : Hermès demandait « voulez-vous que je lance… ? ». */
    @org.junit.Test fun tiktokEtMontagesParJarvis() {
        for (t in listOf("Fais-moi une vidéo TikTok en lien avec ma niche avec Pixabay", "crée une vidéo de 30 secondes sur le 11 novembre",
            "analyse mes concurrents tiktok", "monte une vidéo avec mes photos du chantier", "prépare 3 reels sur les panneaux solaires"))
            org.junit.Assert.assertEquals(t, Routeur.Cible.JARVIS, Routeur.decider("tout", false, false, t, null).cible)
        // Explicitement pour Hermès, ou recherche sur internet : inchangé.
        org.junit.Assert.assertEquals(Routeur.Cible.HERMES, Routeur.decider("tout", false, false, "Hermès, fais une vidéo TikTok", null).cible)
        org.junit.Assert.assertFalse(Routeur.tiktokJarvis("quelle est la capitale de l'Australie"))
    }
}
