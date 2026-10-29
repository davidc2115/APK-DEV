package fr.dd.jarvis.device

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fr.dd.jarvis.data.Store
import java.io.File

/**
 * Écrit les fichiers dans le dossier Obsidian choisi (sélecteur Android, sous-dossiers créés au besoin),
 * sinon dans le dossier propre à l'application (Android/data/fr.dd.jarvis/files/<dossier>).
 */
class ObsidianWriter(private val ctx: Context, private val store: Store) {

    private fun safe(name: String, def: String) =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { def }

    private fun subDir(root: DocumentFile, path: String): DocumentFile {
        var cur = root
        for (part in path.split('/').filter { it.isNotBlank() }) {
            cur = cur.findFile(part)?.takeIf { it.isDirectory } ?: cur.createDirectory(part) ?: return cur
        }
        return cur
    }

    /** [folder] peut contenir des sous-dossiers : « Société/Devis ». Retourne l'emplacement lisible. */
    fun saveBytes(folder: String, fileName: String, bytes: ByteArray, mime: String): String {
        val name = safe(fileName, "Fichier")
        val uriStr = store.obsidianUri.value
        if (uriStr.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
                if (tree != null && tree.canWrite()) {
                    val dir = subDir(tree, folder)
                    val target = dir.findFile(name) ?: dir.createFile(mime, name)
                    if (target != null) {
                        ctx.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
                        return "coffre Obsidian › ${folder.replace("/", " › ")} › $name"
                    }
                }
            } catch (_: Exception) {
                // repli sur le dossier de l'application
            }
        }
        val dir = File(ctx.getExternalFilesDir(null), folder).apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
        return "${dir.absolutePath}/$name (choisis ton dossier Obsidian dans les Réglages pour y écrire directement)"
    }

    fun saveIn(folder: String, baseName: String, markdown: String): String =
        saveBytes(folder, safe(baseName, "Fiche") + ".md", markdown.toByteArray(), "text/markdown")

    fun save(fileBaseName: String, markdown: String): String = saveIn("Contacts", fileBaseName, markdown)

    /** Relit une note du coffre Obsidian (ou du dossier de l'application) ; null si introuvable. */
    fun read(folder: String, fileName: String): String? {
        val name = safe(fileName, "Fichier")
        val uriStr = store.obsidianUri.value
        try {
            if (uriStr.isNotBlank()) {
                val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
                if (tree != null && tree.canRead()) {
                    var cur: DocumentFile? = tree
                    for (part in folder.split('/').filter { it.isNotBlank() }) cur = cur?.findFile(part)?.takeIf { it.isDirectory }
                    val f = cur?.findFile(name)
                    if (f != null) return ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    return null
                }
            }
            val f = File(File(ctx.getExternalFilesDir(null), folder), name)
            return if (f.exists()) f.readText() else null
        } catch (_: Exception) {
            return null
        }
    }
}
