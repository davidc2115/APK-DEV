package fr.dd.jarvis.device

import fr.dd.jarvis.ai.Annulation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListesStationnementTest {
    @Test fun listes() {
        assertEquals(Listes.Intention.Ajouter("Courses", listOf("Lait", "Œufs", "2 baguettes")), Listes.intention("Ajoute du lait, des œufs et 2 baguettes à la liste de courses"))
        assertEquals(Listes.Intention.Ajouter("Courses", listOf("Pâtes")), Listes.intention("mets des pâtes sur ma liste"))
        assertEquals(Listes.Intention.Ajouter("Bricolage", listOf("Vis de 6")), Listes.intention("ajoute des vis de 6 à ma liste bricolage"))
        assertEquals(Listes.Intention.Ajouter("Courses", listOf("Pain")), Listes.intention("il faut acheter du pain"))
        assertEquals(Listes.Intention.Lire("Courses"), Listes.intention("qu'est-ce qu'il y a sur ma liste de courses ?"))
        assertEquals(Listes.Intention.Lire("Courses"), Listes.intention("qu'est-ce qu'il me manque"))
        assertEquals(Listes.Intention.Cocher(null, listOf("Lait")), Listes.intention("j'ai pris le lait"))
        assertEquals(Listes.Intention.Retirer("Courses", listOf("Pain")), Listes.intention("enlève le pain de la liste"))
        assertEquals(Listes.Intention.Vider("Courses"), Listes.intention("vide ma liste de courses"))
        assertEquals(Listes.Intention.Vider("Courses", cochesSeulement = true), Listes.intention("enlève les articles cochés"))
        assertEquals(Listes.Intention.Toutes, Listes.intention("quelles sont mes listes"))
        assertEquals(Listes.Intention.Envoyer("Courses", "Angeline"), Listes.intention("envoie la liste de courses à Angeline"))
        for (t in listOf("ma liste de tâches", "ajoute la tâche appeler Martin", "montre-moi la liste des appareils", "donne-moi la liste de tes capacités",
            "ajoute un rendez-vous à mon agenda", "liste mes contacts", "mets la chanson sur la liste de lecture", "ajoute le code du portail dans mes notes",
            "lis mes SMS", "quelle est la liste de mes derniers mails"))
            assertNull(t, Listes.intention(t))
        assertNull(Carnet.intention("note du lait sur la liste de courses"))
        val md = Listes.markdown("Courses", listOf(Listes.Element("Lait"), Listes.Element("Œufs", coche = true)))
        assertTrue(md, md.contains("- [ ] Lait") && md.contains("- [x] Œufs"))
    }

    @Test fun stationnement() {
        assertEquals(Stationnement.Intention.Noter(""), Stationnement.intention("je suis garé ici"))
        assertEquals(Stationnement.Intention.Noter("niveau -2, place 45"), Stationnement.intention("Je me suis garé, niveau -2, place 45"))
        assertEquals(Stationnement.Intention.Noter(""), Stationnement.intention("retiens où je suis garé"))
        assertEquals(Stationnement.Intention.OuEst, Stationnement.intention("où est ma voiture ?"))
        assertEquals(Stationnement.Intention.OuEst, Stationnement.intention("où est-ce que je me suis garé"))
        assertEquals(Stationnement.Intention.Guider, Stationnement.intention("ramène-moi à ma voiture"))
        assertEquals(Stationnement.Intention.Oublier, Stationnement.intention("oublie ma place de parking"))
        for (t in listOf("où est Angeline", "emmène-moi à la gare", "où suis-je", "garde ça en mémoire", "je suis à la maison"))
            assertNull(t, Stationnement.intention(t))
    }

    @Test fun annulation() {
        assertTrue(Annulation.demande("annule"))
        assertTrue(Annulation.demande("Non, annule ce que tu viens de faire"))
        assertTrue(Annulation.demande("oups annule ça"))
        assertFalse(Annulation.demande("annule le rendez-vous de demain"))
        assertFalse(Annulation.demande("annule la facture 12"))
        assertTrue(Annulation.reussi("create_event", "Événement « Visite » créé le 2026-11-23T09:00 dans « Pro » (#123)."))
        assertFalse(Annulation.reussi("create_event", "Échec de la création."))
        assertFalse(Annulation.reussi("rappel_creer", "❌ Heure illisible"))
        val ev = Annulation.Action("create_event", JSONObject().put("title", "Visite"), "Événement « Visite » créé (#123).")
        assertEquals("delete_event" to "123", Annulation.inverse(ev)!!.let { it.first to it.second.optString("event_id") })
        val rap = Annulation.Action("rappel_creer", JSONObject().put("texte", "appeler Martin"), "⏰ Rappel programmé demain 9 h : « Appeler Martin »." )
        assertEquals("Appeler Martin", Annulation.inverse(rap)!!.second.optString("texte"))
    }
}
