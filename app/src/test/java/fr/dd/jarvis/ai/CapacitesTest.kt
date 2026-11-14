package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CapacitesTest {
    /** Tous les outils déclarés dans le code (tool("nom" … ou .put("name", "nom") des outils musique / site web). */
    private fun outilsDuCode(): Set<String> {
        val racine = listOf(File("src/main/java/fr/dd/jarvis"), File("app/src/main/java/fr/dd/jarvis")).first { it.exists() }
        val noms = mutableSetOf<String>()
        racine.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            Regex("tool\\(\\s*\"([a-z_0-9]+)\"").findAll(t).forEach { noms += it.groupValues[1] }
            if (f.name in setOf("MusicTools.kt", "SitesWeb.kt")) Regex("put\\(\"name\",\\s*\"([a-z_0-9]+)\"\\)").findAll(t).forEach { noms += it.groupValues[1] }
        }
        return noms
    }

    @Test fun catalogueComplet() {
        val code = outilsDuCode()
        assertTrue("trop peu d'outils trouvés : ${code.size}", code.size > 200)
        val manquants = code - Capacites.outilsCouverts
        assertTrue("outils absents de la liste des capacités : $manquants", manquants.isEmpty())
        val inconnus = Capacites.outilsCouverts - code
        assertTrue("outils du catalogue qui n'existent pas : $inconnus", inconnus.isEmpty())
    }

    @Test fun demandesReconnues() {
        for (p in listOf("Que sais-tu faire ?", "Qu'est-ce que tu sais faire ?", "tu sais faire quoi", "Jarvis, qu'est-ce que tu peux faire ?",
            "Fais-moi la liste complète de tes capacités", "liste de tout ce que tu sais faire", "De quoi es-tu capable ?", "aide",
            "quelles sont tes fonctionnalités", "donne-moi la liste de toutes tes fonctions", "qu'est-ce que tu sais faire d'autre ?", "à quoi tu sers ?"))
            assertNotNull(p, Capacites.demande(p))
        assertEquals(listOf("mails"), Capacites.demande("Que sais-tu faire avec les mails ?")!!.categories)
        assertEquals(listOf("societe"), Capacites.demande("qu'est-ce que tu peux faire pour la société ?")!!.categories)
        assertTrue(Capacites.demande("liste tous tes outils")!!.technique)
        assertFalse(Capacites.demande("que sais-tu faire ?")!!.technique)
    }

    @Test fun pasUneDemandeDeListe() {
        for (p in listOf("tu peux faire un devis pour Martin", "est-ce que tu sais faire une omelette", "Hermès, que sais-tu faire ?",
            "peux-tu faire des pâtes", "aide-moi à envoyer un mail", "que sais-tu faire avec ce fichier ?"))
            assertNull(p, Capacites.demande(p))
    }

    @Test fun listeTechnique() {
        val outils = JSONArray()
        for (n in listOf("meteo", "plex_lancer", "outil_inconnu")) outils.put(JSONObject().put("function", JSONObject().put("name", n).put("description", "Description de $n. Deuxième phrase.")))
        val t = Capacites.technique(outils)
        assertTrue(t, t.contains("`meteo`") && t.contains("`plex_lancer`") && t.contains("`outil_inconnu`") && t.contains("Mes outils (3)"))
        assertTrue(t.startsWith("J'ai 3 outils"))
        assertTrue(t.contains(Capacites.FIN_ORAL))
    }

    @Test fun oralCoupe() {
        assertEquals("Résumé dit.\n", fr.dd.jarvis.voice.TexteParle.oral("Résumé dit.\n${Capacites.FIN_ORAL}\n# Liste"))
        assertEquals("Résumé dit.", fr.dd.jarvis.voice.TexteParle.preparer("Résumé dit.\n${Capacites.FIN_ORAL}\n# Liste\n- un\n- deux"))
    }
}
