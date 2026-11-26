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

    @Test
    fun codeRetourAdbDistingueSuccesEtEchec() {
        assertEquals("(commande exécutée)", ShellResult.fromAdbOutput("__JARVIS_EXIT_CODE__=0\n"))
        assertEquals("Erreur shell (code 1) : Permission denied", ShellResult.fromAdbOutput("Permission denied\n__JARVIS_EXIT_CODE__=1\n"))
    }

    @Test
    fun sortieAdbSansCodeRetourNonVerifiable() {
        assertEquals(null, ShellResult.fromAdbOutput("sortie sans marqueur"))
    }

    @Test
    fun enveloppeShellCaptureLeCodeRetour() {
        val command = ShellResult.withExitCode("settings put global low_power 1")
        assertTrue(command.contains("__jarvis_status=" + '$' + "?"))
        assertTrue(command.contains("__JARVIS_EXIT_CODE__=%s"))
    }
}
