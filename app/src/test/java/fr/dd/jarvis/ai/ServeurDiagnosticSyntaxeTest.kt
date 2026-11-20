package fr.dd.jarvis.ai

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Le script de diagnostic envoyé par SSH doit être un script bash valide pour chaque cible (bash -n). */
class ServeurDiagnosticSyntaxeTest {
    @Test fun scriptsValides() {
        assumeTrue(File("/bin/bash").exists())
        for (cible in listOf("complet", "debian", "ssh", "plex", "home assistant", "discord", "bagbot")) for (sudo in listOf(false, true)) {
            val f = File.createTempFile("diag", ".sh")
            f.writeText(ServeurDiagnostic.commande(cible, "jeton", 80, sudo))
            val p = ProcessBuilder("/bin/bash", "-n", f.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertEquals("cible=$cible sudo=$sudo : $sortie", 0, p.waitFor())
            f.delete()
        }
    }
}
