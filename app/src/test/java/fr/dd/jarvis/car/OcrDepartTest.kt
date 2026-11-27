package fr.dd.jarvis.car

import fr.dd.jarvis.car.OcrDepart.Mot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrDepartTest {
    private fun ligne(vararg mots: Mot) = mots.joinToString(" ") { it.texte } to mots.toList()

    @Test fun boutonYAllerSeulSurSaLigne() {
        val lignes = listOf(
            ligne(Mot("21", 40, 300, 80, 340), Mot("rue", 90, 300, 150, 340), Mot("de", 160, 300, 190, 340), Mot("Massingy", 200, 300, 380, 340)),
            ligne(Mot("Partir", 40, 2100, 160, 2150), Mot("plus", 170, 2100, 250, 2150), Mot("tard", 260, 2100, 340, 2150)),
            ligne(Mot("Y", 700, 2100, 730, 2150), Mot("aller", 740, 2100, 860, 2150)))
        val c = OcrDepart.choisir(lignes)!!
        assertEquals("Y aller", c.libelle)
        assertEquals(780f, c.x, 0.1f); assertEquals(2125f, c.y, 0.1f)
    }

    @Test fun deuxBoutonsLusSurUneSeuleLigne() {
        // « Partir plus tard » et « Y aller » côte à côte, lus comme une seule ligne : jamais « Partir » (plus tard).
        val l = ligne(Mot("Partir", 40, 2100, 160, 2150), Mot("plus", 170, 2100, 250, 2150), Mot("tard", 260, 2100, 340, 2150),
            Mot("Y", 700, 2100, 730, 2150), Mot("aller", 740, 2100, 860, 2150))
        assertEquals("Y aller", OcrDepart.choisir(listOf(l))!!.libelle)
    }

    @Test fun rienATouchersansBouton() {
        assertNull(OcrDepart.choisir(listOf(ligne(Mot("Partir", 0, 0, 10, 10), Mot("plus", 12, 0, 20, 10), Mot("tard", 22, 0, 30, 10)))))
        assertNull(OcrDepart.choisir(listOf(ligne(Mot("Tournez", 0, 0, 10, 10), Mot("à", 12, 0, 20, 10), Mot("droite", 22, 0, 30, 10)))))
        assertNull(OcrDepart.choisir(emptyList()))
    }
}
