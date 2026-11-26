package fr.dd.jarvis.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServeurFastPathTest {
    @Test fun commandesDomestiquesCourtesSontClasseesCommeSimples() {
        listOf("Allume la lumière du salon", "Éteins la lampe de la cuisine",
            "Mets le chauffage à 20 degrés", "Baisse le volume de la télé",
            "Ouvre le volet de la chambre", "Active la prise du bureau")
            .forEach { assertTrue("$it devrait être simple", ServeurTools.actionMaisonSimple(it)) }
    }

    @Test fun demandesComplexesNeSontPasRaccourcies() {
        listOf("Allume la lumière et ouvre les volets",
            "Crée une routine cinéma avec la télé et les lumières",
            "Diagnostique pourquoi le chauffage ne répond plus",
            "Configure une automatisation pour le poêle",
            "Liste les appareils Home Assistant indisponibles",
            "Cherche toutes les lampes du salon")
            .forEach { assertFalse("$it ne doit pas être simple", ServeurTools.actionMaisonSimple(it)) }
    }
}
