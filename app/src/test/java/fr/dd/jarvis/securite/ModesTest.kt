package fr.dd.jarvis.securite

import fr.dd.jarvis.hermes.Francais
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModesTest {
    private val connus = setOf("cover.volet_salon", "light.salon", "light.couloir", "media_player.tv", "binary_sensor.entree_person", "camera.entree")

    @Test fun creationDepuisLIA() {
        val brut = """Voici le mode :
```json
{"nom":"Mode cinéma","description":"Ambiance film au salon","phrases_activation":["lance le cinéma"],"phrases_desactivation":[],
 "activation":[{"type":"ha","service":"cover.close_cover","entites":["cover.volet_salon"]},
               {"type":"ha","service":"light.turn_on","entites":["light.salon","light.inconnue"],"donnees":{"brightness_pct":20}},
               {"type":"telephone","action":"ne_pas_deranger_on"},
               {"type":"jarvis","texte":"Lance Netflix sur la télé"}],
 "desactivation":[{"type":"ha","service":"cover.open_cover","entites":["cover.volet_salon"]}],
 "surveillance":{"capteurs":[],"cameras":[]},"remarques":""}
```"""
        val (s, remarques) = Systemes.interpreter(brut, null, connus, emptyList(), emptySet())
        assertEquals("Mode cinéma", s.nom)
        assertEquals("mode_cinema", s.id)
        assertEquals(4, s.activation.size)
        assertEquals(listOf("light.salon"), s.activation[1].entites)
        assertEquals(20, JSONObject(s.activation[1].texte).getInt("brightness_pct"))
        assertTrue(remarques.contains("light.inconnue"))
        assertTrue(s.phrasesActiver.contains("active le mode cinema") && s.phrasesDesactiver.contains("desactive le mode cinema"))
        assertFalse(s.securite)
    }

    @Test fun modificationGardeLIdentite() {
        val avant = Systemes.Systeme(id = "mode_nuit", nom = "Mode nuit", activation = listOf(Systemes.Etape("ha", "light.turn_off", listOf("light.salon"))))
        val brut = """{"nom":"Mode nuit","activation":[{"type":"ha","service":"light.turn_off","entites":["light.salon","light.couloir"]}],"desactivation":[]}"""
        val (s, _) = Systemes.interpreter(brut, avant, connus, emptyList(), setOf("mode_nuit"))
        assertEquals("mode_nuit", s.id)
        assertEquals(listOf("light.salon", "light.couloir"), s.activation.single().entites)
    }

    @Test fun surveillanceRendLeModeSensible() {
        val brut = """{"nom":"Mode absence","activation":[],"surveillance":{"capteurs":["binary_sensor.entree_person","binary_sensor.faux"],"cameras":["camera.entree"]}}"""
        val (s, r) = Systemes.interpreter(brut, null, connus, emptyList(), emptySet())
        assertEquals(listOf("binary_sensor.entree_person"), s.capteurs)
        assertTrue(s.securite && r.contains("binary_sensor.faux"))
    }

    @Test fun francais() {
        assertTrue(Francais.estAnglais("I have restarted the Home Assistant container and it is now running. Let me know if you need anything else."))
        assertFalse(Francais.estAnglais("J'ai redémarré le conteneur Home Assistant, il tourne de nouveau. Dis-moi si tu as besoin d'autre chose."))
        assertFalse(Francais.estAnglais("```\ndocker ps -a\nthe container is up\n```\nVoici l'état des conteneurs."))
        val m = Francais.avecRegle(JSONArray().put(JSONObject().put("role", "system").put("content", "You are Hermes")).put(JSONObject().put("role", "user").put("content", "salut")))
        assertTrue(m.getJSONObject(0).getString("content").startsWith("LANGUE OBLIGATOIRE") && m.getJSONObject(0).getString("content").endsWith("You are Hermes"))
        assertEquals(2, m.length())
    }

    @Test fun phrasesDesModes() {
        // Correspondance générique « active le mode X » sans fichier : via la normalisation des noms.
        assertEquals("mode cinema", Systemes.norm("Mode cinéma"))
    }
}
