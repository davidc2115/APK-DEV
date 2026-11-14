package fr.dd.jarvis.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fin de phrase de l'écoute JARVIS avec une télé allumée (trames de 20 ms). */
class PorteParoleTest {
    private val rng = java.util.Random(3)
    /** Télé à 3 m : 150 à 450 (PCM 16 bits), avec ses creux. */
    private fun tele(s: Double) = List((s * 50).toInt()) { 150.0 + rng.nextDouble() * 300 }
    /** Ta voix près du téléphone : syllabes fortes et creux entre les mots. */
    private fun voix(s: Double) = List((s * 50).toInt()) { i -> if (i % 12 < 9) 2000.0 + rng.nextDouble() * 2500 else 500.0 }

    private fun finApres(p: PorteParole, niveaux: List<Double>): Int { niveaux.forEachIndexed { i, r -> if (p.trame(r)) return i }; return -1 }

    @Test fun laTeleNEmpechePlusLaFinDeLaPhrase() {
        val p = PorteParole(silenceFin = 100)            // 2 s
        val n = finApres(p, tele(1.5) + voix(2.5) + tele(10.0))
        assertTrue(p.parle)
        // fin ≈ 2 s après la fin de ta voix (trame 200), et pas 25 à 30 s plus tard
        assertTrue("fin à la trame $n", n in 290..320)
    }

    @Test fun laTeleSeuleNeDeclenchePas() {
        val p = PorteParole(silenceFin = 100)
        assertTrue(finApres(p, tele(8.0)) == -1)
        assertFalse(p.parle)
    }

    @Test fun unePauseAuMilieuNeCoupePas() {
        val p = PorteParole(silenceFin = 100)
        val n = finApres(p, tele(1.0) + voix(1.5) + tele(1.2) + voix(1.5) + tele(5.0))
        assertTrue("fin à la trame $n", n in 340..370)
    }

    @Test fun leDebutGardeEstJusteAvantLaVoix() {
        val p = PorteParole(silenceFin = 100)
        (tele(3.0) + voix(1.0)).forEach { p.trame(it) }
        assertTrue("début ${p.debut}", p.debut in 125..150)   // la voix commence à la trame 150
    }
}
