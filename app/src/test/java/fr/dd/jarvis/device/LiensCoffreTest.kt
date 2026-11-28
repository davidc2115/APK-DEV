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

    @Test fun passageAutomatiqueSansToucherAuxNotes() {
        val notes = linkedMapOf(
            "Journal/Lundi.md" to "Visite chez Jean Dupont.",
            "Clients/Jean Dupont.md" to "Client depuis 2023.",
        )
        val p = LiensCoffre.planifier(notes, listOf("JARVIS/Images/phenix.png", "JARVIS/Apprentissage/index.json"), mentions = false)
        assertTrue(p.mentions.isEmpty())
        assertEquals(0, p.nbMentions)
        // Tout ce qui est isolé est rangé dans l'index de son dossier, fichiers de données compris.
        assertTrue(p.index.getValue("Clients/Index — Clients.md").contains("[[Clients/Jean Dupont|Jean Dupont]]"))
        assertTrue(p.index.getValue("JARVIS/Images/Index — Images.md").contains("[[JARVIS/Images/phenix.png|phenix.png]]"))
        assertTrue(p.index.getValue("JARVIS/Apprentissage/Index — Apprentissage.md").contains("index.json"))
        assertTrue(p.index.getValue("Index du coffre.md").contains("[[JARVIS/Images/Index — Images|Index — Images]]"))
    }

    @Test fun nomsQuiCassentUnWikilien() {
        // « # » (titre), « | » (alias), « ^ » (bloc), crochets : lien Markdown à chemin encodé, relu comme un vrai lien.
        val lien = LiensCoffre.lien("Société/Factures/Facture #12 [Martin].pdf")
        assertEquals("[Facture ＃12 (Martin).pdf](Soci%C3%A9t%C3%A9/Factures/Facture%20%2312%20%5BMartin%5D.pdf)", lien)
        val idx = LiensCoffre.Index(emptyList(), listOf("Société/Factures/Facture #12 [Martin].pdf"))
        assertEquals(listOf(LiensCoffre.Cible(fichier = "Société/Factures/Facture #12 [Martin].pdf")), idx.liens(lien, "Société/Factures/Index — Factures.md"))
        assertEquals("[[Images/plan.pdf|plan.pdf]]", LiensCoffre.lien("Images/plan.pdf"))
    }

    @Test fun accentsDecomposesEtHomonymes() {
        // Android renvoie parfois « é » décomposé (e + accent) : le lien compte quand même.
        val idx = LiensCoffre.Index(listOf("Clients/H\u00e9l\u00e8ne.md"), emptyList())
        assertEquals(listOf(LiensCoffre.Cible(note = "Clients/H\u00e9l\u00e8ne")), idx.liens("Voir [[He\u0301le\u0300ne]]", "Journal/x.md"))
        // Deux notes « Notes » : un lien par simple nom ne dit pas laquelle, les deux restent rangées dans un index.
        val notes = linkedMapOf("A/Notes.md" to "a", "B/Notes.md" to "b", "Accueil.md" to "[[Notes]]")
        val p = LiensCoffre.planifier(notes, emptyList(), mentions = false)
        assertTrue(p.index.getValue("A/Index — A.md").contains("[[A/Notes|Notes]]"))
        assertTrue(p.index.getValue("B/Index — B.md").contains("[[B/Notes|Notes]]"))
    }
}
