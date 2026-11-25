package fr.dd.jarvis.device

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RegistreApprentissageMigrationTest {
    @Test
    fun anciennesHabitudesSontImporteesSansInventerLeContexteEtSansDoublon() {
        val dir = Files.createTempDirectory("registre-migration-legacy").toFile()
        try {
            val distant = mutableListOf<String?>(null)
            val registre = RegistreApprentissage(
                File(dir, "index.json"),
                { false },
                { distant.last() },
                { distant.add(it) }
            )
            val anciennes = mapOf(
                "maison|Réponse vocale concise" to setOf("a1b2c3d4e5f678901234", "b1c2d3e4f5a678901234")
            )

            assertEquals(1, registre.importerAnciennesHabitudes(anciennes))
            assertEquals(0, registre.importerAnciennesHabitudes(anciennes))

            val entry = JSONObject(registre.snapshot()).getJSONArray("entries").getJSONObject(0)
            assertEquals("habit", entry.getString("type"))
            assertEquals("Réponse vocale concise", entry.getString("title"))
            assertEquals(RegistreApprentissage.ETAT_CANDIDATE, entry.getString("state"))
            assertTrue(entry.getBoolean("legacyOpaque"))
            val evidence = entry.getJSONArray("evidence")
            assertEquals(2, evidence.length())
            for (i in 0 until evidence.length()) {
                val proof = evidence.getJSONObject(i)
                assertTrue(proof.getBoolean("legacyOpaque"))
                assertTrue(proof.getString("context").contains("non conservé"))
                assertTrue(proof.getString("content").startsWith("Empreinte héritée : "))
                assertTrue(proof.getString("legacyHash").isNotBlank())
            }
            assertEquals(1, JSONObject(registre.snapshot()).getJSONArray("entries").length())
        } finally {
            dir.deleteRecursively()
        }
    }
}
