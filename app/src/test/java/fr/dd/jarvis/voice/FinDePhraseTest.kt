package fr.dd.jarvis.voice

import fr.dd.jarvis.car.FinDePhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fin de phrase de l'écoute JARVIS : trames de 20 ms, pause de fin réglable. */
class FinDePhraseTest {
    /** Nombre de trames avant que la porte déclare la phrase finie (-1 si jamais). */
    private fun finApres(f: FinDePhrase, niveaux: List<Double>): Int {
        niveaux.forEachIndexed { i, r -> if (f.trame(r)) return i }
        return -1
    }
    private fun voix(s: Double) = List((s * 50).toInt()) { 1500.0 }
    private fun silence(s: Double) = List((s * 50).toInt()) { 40.0 }

    @Test fun unePauseDUneSecondeNeCoupePasLaPhrase() {
        val f = FinDePhrase(40.0, minimum = 150.0, silenceFin = 100, ratio = 2.8)   // 2 s
        val n = finApres(f, voix(1.0) + silence(1.0) + voix(1.0) + silence(3.0))
        // fin 2 s après la DEUXIÈME partie de la phrase (trame 150 + 100), pas pendant la pause du milieu
        assertTrue("fin à la trame $n", n in 245..255)
    }

    @Test fun personneNeParle() {
        val f = FinDePhrase(40.0, minimum = 150.0, silenceFin = 100, ratio = 2.8)
        assertEquals(-1, finApres(f, silence(5.0)))
        assertFalse(f.parle)
    }

    @Test fun prolongerRetardeLaFin() {
        val f = FinDePhrase(40.0, minimum = 150.0, silenceFin = 50, ratio = 2.8)
        var n = 0
        (voix(0.5) + silence(0.98)).forEach { f.trame(it); n++ }
        f.prolonger(75)                                       // phrase inachevée : 1,5 s de plus
        val reste = finApres(f, silence(3.0))
        assertTrue("reste $reste", reste in 70..76)
    }

    @Test fun voixBasseForceeParLesMots() {
        val f = FinDePhrase(40.0, minimum = 150.0, silenceFin = 50, ratio = 2.8)
        silence(0.5).forEach { f.trame(it) }
        f.forcerParole()
        assertTrue(f.parle)
        assertTrue(f.debut >= 0)
    }

    @Test fun dureeDeVoixReelle() {
        val f = FinDePhrase(40.0, minimum = 150.0, silenceFin = 100, ratio = 2.8)
        (voix(1.0) + silence(0.5) + voix(0.4)).forEach { f.trame(it) }
        assertEquals(70, f.tramesVoix)
    }

    @Test fun coupeLeSilenceFinalSansDepasserLeTampon() {
        val taille = 16000 * 2 * 3
        // Dernière voix à 1,0 s : garder 300 ms, pas les 1,1 s de silence de détection.
        assertEquals(1_300 * 32, Pcm.finUtile(taille, 50))
        assertEquals(taille, Pcm.finUtile(taille, 200))
        assertEquals(0, Pcm.finUtile(taille, -100))
    }

    @Test fun echelleDuNiveau() {
        assertTrue(MicroJarvis.db(30.0) < 0.5f)
        assertTrue(MicroJarvis.db(3000.0) > 9f)
    }
}
