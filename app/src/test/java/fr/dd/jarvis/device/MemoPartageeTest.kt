package fr.dd.jarvis.device

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoPartageeTest {
    @Test
    fun copieHermesEstRemplaceeEntierement() {
        val dossier = Files.createTempDirectory("jarvis-memoire").toFile()
        try {
            val fichier = File(dossier, "memoire-hermes.md")
            fichier.writeText("ancienne mémoire")
            Memo.persisterCopieHermes(fichier, "nouvelle mémoire\navec plusieurs lignes")
            assertEquals("nouvelle mémoire\navec plusieurs lignes", fichier.readText())
            assertFalse(File(dossier, "memoire-hermes.md.tmp").exists())
        } finally {
            dossier.deleteRecursively()
        }
    }

    @Test
    fun echecDePersistanceEstSignaleEtNeValidePasLeFichier() {
        val dossier = Files.createTempDirectory("jarvis-memoire-echec").toFile()
        try {
            val parentBloque = File(dossier, "parent")
            parentBloque.writeText("ceci est un fichier, pas un dossier")
            val cible = File(parentBloque, "memoire-hermes.md")
            assertThrows(java.io.IOException::class.java) {
                Memo.persisterCopieHermes(cible, "mémoire non persistée")
            }
            assertFalse(cible.exists())
        } finally {
            dossier.deleteRecursively()
        }
    }
}
