package fr.dd.jarvis.device

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fr.dd.jarvis.data.Store
import java.io.File

/**
 * Écrit les fiches Markdown dans le dossier Obsidian choisi (sélecteur Android),
 * sinon dans le dossier propre à l'application (Android/data/fr.dd.jarvis/files/Contacts).
 */
class ObsidianWriter(private val ctx: Context, private val store: Store) {

    fun save(fileBaseName: String, markdown: String): String {
        val safe = fileBaseName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Contact" }
        val fileName = "$safe.md"
        val uriStr = store.obsidianUri.value
        if (uriStr.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
                if (tree != null && tree.canWrite()) {
                    val folder = tree.findFile("Contacts")?.takeIf { it.isDirectory }
                        ?: tree.createDirectory("Contacts") ?: tree
                    val existing = folder.findFile(fileName)
                    val target = existing ?: folder.createFile("text/markdown", fileName)
                    if (target != null) {
                        ctx.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(markdown.toByteArray()) }
                        return "dossier Obsidian › Contacts › $fileName"
                    }
                }
            } catch (_: Exception) {
                // on retombe sur le dossier de l'application
            }
        }
        val dir = File(ctx.getExternalFilesDir(null), "Contacts").apply { mkdirs() }
        File(dir, fileName).writeText(markdown)
        return "${dir.absolutePath}/$fileName (choisis ton dossier Obsidian dans les Réglages pour y écrire directement)"
    }
}
