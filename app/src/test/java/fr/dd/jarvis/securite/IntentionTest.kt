package fr.dd.jarvis.securite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentionTest {
    private val aucun = emptyList<String>()
    private val installes = listOf("Téléphone sécurisé", "Maison sécurisée", "Mode cinéma")

    @Test fun creationDesModeles() {
        val a = Systemes.intention("installe le mode téléphone sécurisé", aucun)!!
        assertEquals("creer", a.getString("action")); assertEquals("telephone_securise", a.getString("modele"))
        val b = Systemes.intention("Installe le mode maison sécurisée", aucun)!!
        assertEquals("maison_securisee", b.getString("modele"))
        assertEquals("maison_securisee", Systemes.intention("active le mode maison sécurisée", aucun)!!.getString("modele"))
        // Déjà installé : l'activation est laissée aux phrases d'activation.
        assertNull(Systemes.intention("active le mode maison sécurisée", installes))
    }

    @Test fun creationLibre() {
        val a = Systemes.intention("crée un mode cinéma qui ferme les volets du salon et baisse les lumières", aucun)!!
        assertEquals("creer", a.getString("action"))
        assertNull(Systemes.intention("crée un nouveau mode", aucun))   // trop vague : l'IA pose ses questions
    }

    @Test fun modificationEtSuppression() {
        val m = Systemes.intention("ajoute le numéro de Marie au mode maison sécurisée", installes)!!
        assertEquals("modifier", m.getString("action")); assertEquals("Maison sécurisée", m.getString("nom"))
        val d = Systemes.intention("dans le mode cinéma, enlève les volets", installes)!!
        assertEquals("modifier", d.getString("action")); assertEquals("Mode cinéma", d.getString("nom"))
        val s = Systemes.intention("supprime le mode cinéma", installes)!!
        assertEquals("supprimer", s.getString("action"))
        assertEquals("lister", Systemes.intention("montre mes modes", installes)!!.getString("action"))
        assertNull(Systemes.intention("mets le téléphone en mode performance", installes))
        assertNull(Systemes.intention("passe en mode économie", installes))
    }
}
