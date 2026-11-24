package fr.dd.jarvis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionOutilsTest {
    private fun outil(nom: String, desc: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", nom).put("description", desc))

    private fun catalogue(): Pair<JSONArray, Set<String>> {
        val s = JSONArray()
        val generaux = mutableSetOf<String>()
        SelectionOutils.NOYAU.forEach { s.put(outil(it, "outil du quotidien $it")); generaux += it }
        s.put(outil("alarme_supprimer", "Supprime une alarme du réveil.")); generaux += "alarme_supprimer"
        s.put(outil("analyser_video", "Analyse une vidéo (résumé, scènes).")); generaux += "analyser_video"
        s.put(outil("export_kml", "Exporte les clients en KML pour Google Earth.")); generaux += "export_kml"
        repeat(70) { i -> s.put(outil("divers_$i", "Fonction rare numéro $i")); generaux += "divers_$i" }
        s.put(outil("ha_service", "Home Assistant : exécute un service."))
        s.put(outil("ha_entites", "Home Assistant : entités."))
        s.put(outil("plex_diagnostic", "Diagnostic Plex : état du serveur, erreurs, journaux et transcodage."))
        s.put(outil("obsidian_chercher", "Recherche un dossier ou une note dans le coffre Obsidian."))
        s.put(outil("freebox_appareils", "Liste et diagnostique les appareils connectés à la Freebox et au réseau Wi-Fi."))
        s.put(outil("ha_diagnostic", "Diagnostic Home Assistant : état, erreurs, entités indisponibles et journaux."))
        return s to generaux
    }

    private fun noms(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") }

    @Test fun listeAllegeeGardeNoyauFamillesEtPertinents() {
        val (s, generaux) = catalogue()
        val r = noms(SelectionOutils.compacte(s, generaux, setOf("ha_entites"), "supprime mon alarme de 7 h et allume la lumière"))
        assertTrue(r.size < s.length() / 2 + 10)
        assertTrue(r.containsAll(SelectionOutils.NOYAU))
        assertTrue("ha_service" in r && "ha_entites" in r)
        assertTrue("alarme_supprimer" in r)
        assertEquals(SelectionOutils.META, r.last())
    }

    @Test fun petiteListeInchangee() {
        val s = JSONArray().put(outil("meteo", "Météo")).put(outil("ha_service", "HA"))
        assertEquals(2, SelectionOutils.compacte(s, setOf("meteo"), emptySet(), "météo").length())
    }

    @Test fun rechercheDansLeCatalogue() {
        val (s, _) = catalogue()
        val r = SelectionOutils.rechercher(s, "analyser une vidéo", setOf("meteo"))
        assertEquals("analyser_video", r.first().getJSONObject("function").getString("name"))
        assertTrue(SelectionOutils.rechercher(s, "zzzz", emptySet()).isEmpty())
    }

    @Test fun synonymesDePanneOriententVersLeDiagnosticConcerne() {
        val (s, _) = catalogue()
        val r = SelectionOutils.rechercher(s, "Plex rame et ne répond plus, regarde les journaux", emptySet())
        assertEquals("plex_diagnostic", r.first().getJSONObject("function").getString("name"))
    }

    @Test fun vocabulaireDuCoffreRetrouveLaRechercheObsidian() {
        val (s, _) = catalogue()
        val r = SelectionOutils.rechercher(s, "le coffre ne retrouve pas mes dossiers", emptySet())
        assertEquals("obsidian_chercher", r.first().getJSONObject("function").getString("name"))
    }

    @Test fun formulationsFamilieresDePanneRetrouventLeDiagnostic() {
        val (s, _) = catalogue()
        val r = SelectionOutils.rechercher(s, "Plex déconne et se fige depuis hier", emptySet())
        assertEquals("plex_diagnostic", r.first().getJSONObject("function").getString("name"))
    }

    @Test fun oubliDesConsignesOrienteVersLaMemoire() {
        val (s, _) = catalogue()
        val r = SelectionOutils.rechercher(s, "Jarvis oublie mes consignes et préférences", emptySet())
        assertTrue(r.isNotEmpty())
        assertTrue(r.first().getJSONObject("function").getString("name").startsWith("memoire_"))
    }
}
