package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SitesWebTest {
    @Test fun lectureDesFichiers() {
        val rep = """Voici le site :
=== FICHIER: index.html ===
<!doctype html><title>Accueil</title>
=== FIN ===
=== FICHIER: css/style.css ===
```css
body { color: #222; }
```
=== FIN ===
=== FICHIER: ../../etc/passwd ===
pirate
=== FIN ===
=== SUPPRIMER: vieux.html ==="""
        val (f, s) = SitesWebTools.lireFichiers(rep)
        assertEquals(listOf("index.html", "css/style.css"), f.keys.toList())
        assertEquals("body { color: #222; }", f["css/style.css"])
        assertEquals(listOf("vieux.html"), s)
    }

    @Test fun cheminsEtNoms() {
        assertNull(SitesWebTools.propre("/etc/../passwd"))
        assertEquals("pages/contact.html", SitesWebTools.propre("./pages/contact.html"))
        assertEquals("site-entreprise-pv", SitesWebTools.slug("Site entreprise PV"))
        assertTrue(SitesWebTools.concerned("crée un site vitrine pour mon entreprise"))
    }

    @Test fun intentions() {
        val c = SitesWebTools.intention("crée-moi un site vitrine pour mon entreprise de maintenance photovoltaïque avec une page contact et mets-le en ligne", emptyList())!!
        assertEquals("creer", c.getString("action"))
        assertTrue(c.getString("nom").contains("entreprise"))
        assertEquals(null, SitesWebTools.intention("crée un site", emptyList()))
        assertEquals("voir", SitesWebTools.intention("où en est mon site ?", listOf("Site entreprise PV"))!!.getString("action"))
        val e = SitesWebTools.intention("mets le site entreprise pv en ligne", listOf("Site entreprise PV"))!!
        assertEquals("mettre_en_ligne", e.getString("action")); assertEquals("Site entreprise PV", e.getString("nom"))
        val m = SitesWebTools.intention("ajoute une page tarifs au site", listOf("Site entreprise PV"))!!
        assertEquals("modifier", m.getString("action"))
        assertEquals("lister", SitesWebTools.intention("montre mes sites", emptyList())!!.getString("action"))
        assertEquals(null, SitesWebTools.intention("allume la lumière du salon", emptyList()))
    }
}
