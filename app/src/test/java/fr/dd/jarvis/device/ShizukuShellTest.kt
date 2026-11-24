package fr.dd.jarvis.device

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuShellTest {
    @Test
    fun lectureCompleteDuFlux() {
        val texte = ShizukuShell.lireFluxLimite(ByteArrayInputStream("sortie normale".toByteArray()), 100)
        assertEquals("sortie normale", texte)
    }

    @Test
    fun sortieLimiteeMaisFluxEntierementConsomme() {
        val entree = "x".repeat(10000)
        val texte = ShizukuShell.lireFluxLimite(ByteArrayInputStream(entree.toByteArray()), 100)
        assertTrue(texte.startsWith("x".repeat(100)))
        assertTrue(texte.contains("sortie tronquée"))
        assertTrue(texte.length < 150)
    }
}
