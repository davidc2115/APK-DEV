package fr.dd.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class EcrituresTest {
    @Test fun derniereVersionEcriteSansFichierTemporaireVisible() {
        val d = Files.createTempDirectory("ecr").toFile()
        val f = java.io.File(d, "conv.json")
        // Bloque le fil d'écriture le temps d'empiler plusieurs versions du même fichier.
        val verrou = CountDownLatch(1)
        Ecritures.ecrire(java.io.File(d, "autre.json")) { verrou.await(); "x" }
        val calculs = AtomicInteger()
        for (i in 1..5) Ecritures.ecrire(f) { calculs.incrementAndGet(); "v$i" }
        verrou.countDown()
        Ecritures.attendre()
        assertEquals("v5", f.readText())
        assertEquals("versions intermédiaires sautées", 1, calculs.get())
        assertTrue(d.listFiles()!!.none { it.isFile && it.name.endsWith(".tmp") })
    }

    @Test fun ecritureAnnuleeAvantSuppression() {
        val d = Files.createTempDirectory("ecr").toFile()
        val f = java.io.File(d, "a.json")
        val verrou = CountDownLatch(1)
        Ecritures.ecrire(java.io.File(d, "bloque.json")) { verrou.await(); "x" }
        Ecritures.ecrire(f) { "contenu" }
        Ecritures.oublierDossier(d)
        verrou.countDown()
        Ecritures.attendre()
        assertFalse(f.exists())
    }
}
