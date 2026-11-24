package fr.dd.jarvis.device

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbShellTest {
    @Test
    fun lectureSortieComplete() {
        assertEquals("commande terminée", AdbShell.lireSortieLimitee(ByteArrayInputStream("commande terminée".toByteArray()), 100))
    }

    @Test
    fun lectureSortieLimiteeSansAbandonnerLeFlux() {
        val entree = "z".repeat(10000)
        val resultat = AdbShell.lireSortieLimitee(ByteArrayInputStream(entree.toByteArray()), 100)
        assertTrue(resultat.startsWith("z".repeat(100)))
        assertTrue(resultat.contains("sortie tronquée"))
        assertTrue(resultat.length < 150)
    }
}
