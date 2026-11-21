package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsignesIATest {
    @Test fun candidatsAAnalyser() {
        assertTrue(ConsignesIA.candidat("non je t'avais demandé un tableau"))
        assertTrue(ConsignesIA.candidat("pourquoi tu ne mets pas la consigne comme je t'ai demandé ?"))
        assertTrue(ConsignesIA.candidat("je préfère que les films soient classés par année"))
        assertTrue(ConsignesIA.candidat("Pour le serveur, utilise toujours l'utilisateur dd"))
        assertFalse(ConsignesIA.candidat("allume la lumière du salon"))
        assertFalse(ConsignesIA.candidat("quelle est la température de mes poêles"))
        assertFalse(ConsignesIA.candidat("quelles sont mes consignes"))
        assertFalse(ConsignesIA.candidat("ok"))
    }

    @Test fun reponseDeLIALue() {
        val e = ConsignesIA.lire("Voici : {\"consigne\":true,\"domaine\":\"maison\",\"regle\":\"Pour la température des poêles : toujours un tableau avec la température actuelle et la température de consigne.\"}")!!
        assertEquals("maison", e.rubrique)
        assertEquals("Pour la température des poêles : toujours un tableau avec la température actuelle et la température de consigne", e.texte)
        assertNull(ConsignesIA.lire("{\"consigne\":false}"))
        assertNull(ConsignesIA.lire("je ne sais pas"))
        assertNull(ConsignesIA.lire("{\"consigne\":true,\"domaine\":\"serveur\",\"regle\":\"Le mot de passe SSH est 1234 à chaque fois\"}"))
        assertEquals("plex", ConsignesIA.lire("{\"consigne\":true,\"domaine\":\"films\",\"regle\":\"Pour Plex : classer les films par année de sortie\"}")?.rubrique)
        assertEquals("reponses", ConsignesIA.lire("{\"consigne\":true,\"domaine\":\"reponses\",\"regle\":\"Toujours répondre en deux phrases maximum\"}")?.rubrique)
    }
}
