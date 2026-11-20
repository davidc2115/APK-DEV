package fr.dd.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiensCoffreTest {
    private val dupont = LiensCoffre.Mention("Jean Dupont", "Clients/Jean Dupont", false)

    @Test fun mentionEnClairDevientUnLien() {
        val (t, l) = LiensCoffre.lierMentions("Appelé jean dupont hier. Rappeler Jean Dupont demain.", listOf(dupont))
        assertEquals("Appelé [[Jean Dupont|jean dupont]] hier. Rappeler Jean Dupont demain.", t)
        assertEquals(listOf("Clients/Jean Dupont"), l)
        assertEquals("Voir [[Clients/Jean Dupont|Jean Dupont]].", LiensCoffre.lierMentions("Voir Jean Dupont.", listOf(dupont.copy(ambigu = true))).first)
    }

    @Test fun zonesJamaisTouchees() {
        val texte = "---\nclient: Jean Dupont\n---\n`Jean Dupont` [[Jean Dupont]] [fiche](Jean Dupont.md) https://x.fr/Jean-Dupont\n```\nJean Dupont\n```\n"
        assertEquals(texte, LiensCoffre.lierMentions(texte, listOf(dupont)).first)
        // Mot soudé (« Saint-Jean ») ou nom de fichier : pas une mention.
        val jean = LiensCoffre.Mention("Jean", "Jean", false)
        assertEquals("Saint-Jean et jean.pdf", LiensCoffre.lierMentions("Saint-Jean et jean.pdf", listOf(jean)).first)
        assertFalse(LiensCoffre.titreUtilisable("abc"))
        assertFalse(LiensCoffre.titreUtilisable("2024"))
        assertTrue(LiensCoffre.titreUtilisable("Poêle"))
    }

    @Test fun planCompletEtIdempotent() {
        val notes = linkedMapOf(
            "Journal/Lundi.md" to "Visite chez Jean Dupont pour le poêle. ![photo](../Images/chantier.jpg)",
            "Clients/Jean Dupont.md" to "Client depuis 2023.",
            "Idées/Projet serre.md" to "Une serre au fond du jardin.",
            "Société/Clients/Martin.md" to "Nom : Jean Dupont",
            "Accueil.md" to "Bienvenue [[Lundi]].",
        )
        val fichiers = listOf("Images/chantier.jpg", "Images/plan.pdf")
        val p = LiensCoffre.planifier(notes, fichiers)
        // Mention transformée dans la note de l'utilisateur, jamais dans la note de la Société.
        assertEquals(setOf("Journal/Lundi.md"), p.mentions.keys)
        assertEquals(1, p.nbMentions)
        // Restent isolés : Projet serre, Martin (Société), Accueil (personne ne la cite), plan.pdf ; chantier.jpg est lié.
        val idees = p.index.getValue("Idées/Index — Idées.md")
        assertTrue(idees, idees.contains("[[Idées/Projet serre|Projet serre]]"))
        val images = p.index.getValue("Images/Index — Images.md")
        assertTrue(images, images.contains("[[Images/plan.pdf|plan.pdf]]") && !images.contains("chantier"))
        val racine = p.index.getValue("Index du coffre.md")
        assertTrue(racine, racine.contains("[[Idées/Index — Idées|Index — Idées]]") && racine.contains("[[Accueil|Accueil]]"))
        assertFalse(p.index.keys.any { it.startsWith("Clients/") })

        // Deuxième passage avec les index écrits : mêmes index (leurs propres liens ne comptent pas).
        val apres = LinkedHashMap(notes).apply { putAll(p.index); put("Journal/Lundi.md", LiensCoffre.lierMentions(notes.getValue("Journal/Lundi.md"), p.mentions.getValue("Journal/Lundi.md")).first) }
        val p2 = LiensCoffre.planifier(apres, fichiers)
        assertEquals(0, p2.nbMentions)
        assertEquals(p.index, p2.index)
    }

    @Test fun indexIgnoresParLesPanneaux() {
        assertTrue(LiensCoffre.estIndex("Index du coffre"))
        assertTrue(LiensCoffre.estIndex("Clients/Index — Clients.md"))
        assertFalse(LiensCoffre.estIndex("Clients/Index des prix"))
    }
}
