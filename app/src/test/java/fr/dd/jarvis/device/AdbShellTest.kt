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
        assertTrue(resultat.contains("\n… (sortie tronquée)"))
        assertTrue(resultat.length < 150)
    }

    @Test
    fun conserveMarqueurFinalQuandLaSortieEstLongue() {
        val entree = "z".repeat(10000) + "\n__JARVIS_EXIT_CODE__=7\n"
        val resultat = AdbShell.lireSortieLimitee(ByteArrayInputStream(entree.toByteArray()), 4000, conserverFin = true)
        assertTrue(resultat.startsWith("z"))
        assertTrue(resultat.contains("__JARVIS_EXIT_CODE__=7"))
        assertTrue(ShellResult.fromAdbOutput(resultat)?.startsWith("Erreur shell (code 7)") == true)
    }
}
