package fr.dd.jarvis.securite

import fr.dd.jarvis.securite.TelephoneSecurise.Noeud
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrilleSchemaTest {
    @Test fun conteneurPleinEcranIgnore() {
        // Ce qu'a vu le téléphone : le conteneur « pattern » plein écran, puis la vraie grille carrée plus bas.
        val noeuds = listOf(
            Noeud("com.android.systemui:id/keyguard_pattern_view", "android.widget.FrameLayout", "", 0, 26, 1280, 2619),
            Noeud("com.android.systemui:id/lockPatternView", "com.android.internal.widget.LockPatternView", "", 190, 1290, 1090, 2190))
        assertArrayEquals(intArrayOf(190, 1290, 1090, 2190), TelephoneSecurise.choisirGrille(noeuds)!!.first)
        // Conteneur seul (non carré) : pas de grille devinée.
        assertNull(TelephoneSecurise.choisirGrille(noeuds.take(1)))
        assertNull(TelephoneSecurise.grilleEnregistree("0,26,1280,2619"))
    }

    @Test fun neufPoints() {
        val points = (0 until 9).map { i -> val x = 300 + (i % 3) * 340; val y = 1400 + (i / 3) * 340; Noeud("", "android.view.View", "Point ${i + 1}", x - 60, y - 60, x + 60, y + 60) }
        val g = TelephoneSecurise.choisirGrille(listOf(Noeud("x:id/keyguard_pattern_view", "FrameLayout", "", 0, 26, 1280, 2619)) + points)!!
        assertEquals("les 9 points du schéma", g.second)
        // Les centres calculés retombent sur les points.
        assertEquals(300 to 1400, TelephoneSecurise.centre(1, g.first))
        assertEquals(980 to 2080, TelephoneSecurise.centre(9, g.first))
    }

    @Test fun uiautomator() {
        val xml = """<hierarchy><node index="0" text="" resource-id="com.android.systemui:id/keyguard_pattern_view" class="android.widget.FrameLayout" bounds="[0,26][1280,2619]">""" +
            """<node index="1" text="" resource-id="com.android.systemui:id/lockPatternView" class="com.android.internal.widget.LockPatternView" content-desc="" bounds="[190,1290][1090,2190]"/></node></hierarchy>"""
        val n = TelephoneSecurise.noeudsUiautomator(xml)
        assertEquals(2, n.size)
        assertArrayEquals(intArrayOf(190, 1290, 1090, 2190), TelephoneSecurise.choisirGrille(n)!!.first)
    }
}
