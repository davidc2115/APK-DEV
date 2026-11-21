package fr.dd.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les scripts envoyés à Termux sont du bash valide (bash -n) : une erreur de syntaxe = Hermès qui ne démarre jamais. */
class ScriptsHermesTest {
    private fun syntaxe(script: String): String {
        val f = java.io.File.createTempFile("hermes", ".sh").apply { writeText(script); deleteOnExit() }
        val p = ProcessBuilder("bash", "-n", f.absolutePath).redirectErrorStream(true).start()
        val sortie = p.inputStream.bufferedReader().readText()
        return if (p.waitFor() == 0) "" else sortie
    }

    @Test fun diagnosticEtDemarrageValides() {
        val prelude = HermesTermux.scriptPrelude("jeton")
        val diag = prelude + "\n" + HermesTermux.scriptDiagnostic(listOf("Batterie de Termux : sans restriction ✅", "l'apostrophe ne casse rien"))
        assertEquals("", syntaxe(diag))
        assertTrue(diag.contains("timeout 120 proot-distro"))
        assertEquals("", syntaxe(prelude + "\n" + HermesTermux.demarrage))
        assertTrue(HermesTermux.demarrage.contains("arreter_passerelles_hermes"))
    }
}
