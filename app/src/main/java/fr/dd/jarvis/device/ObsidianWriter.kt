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

    // ------------------------------------------------------------------ lecture / recherche dans le coffre

    private fun vaultRoot(): DocumentFile? = store.obsidianUri.value.takeIf { it.isNotBlank() }?.let {
        runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(it)) }.getOrNull()
    }?.takeIf { it.canRead() }

    private fun localRoot(): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    private fun walk(dir: DocumentFile, prefix: String, out: MutableList<Pair<String, DocumentFile>>, limit: Int) {
        for (f in dir.listFiles()) {
            if (out.size >= limit) return
            val n = f.name ?: continue
            if (f.isDirectory) { if (!n.startsWith(".")) walk(f, "$prefix$n/", out, limit) }
            else if (n.endsWith(".md", true)) out += (prefix + n) to f
        }
    }

    private fun allNotes(limit: Int = 2500): List<Pair<String, DocumentFile>> {
        val r = vaultRoot() ?: return emptyList()
        return mutableListOf<Pair<String, DocumentFile>>().also { walk(r, "", it, limit) }
    }

    /** Notes du coffre (chemins relatifs), éventuellement dans un dossier. */
    fun listNotes(folder: String = ""): List<String> {
        val f = folder.trim().trim('/')
        val v = vaultRoot()
        if (v != null) return allNotes().map { it.first }.filter { f.isEmpty() || it.startsWith("$f/") }.sorted()
        return File(localRoot(), f).walkTopDown().filter { it.isFile && it.name.endsWith(".md", true) }.map { it.relativeTo(localRoot()).path }.sorted().toList()
    }

    /** Recherche texte (nom + contenu). Retourne (chemin, extrait). */
    fun searchNotes(query: String, limit: Int = 12): List<Pair<String, String>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        fun extrait(txt: String): String {
            val i = txt.lowercase().indexOf(q)
            return if (i < 0) "" else txt.substring(maxOf(0, i - 60), minOf(txt.length, i + 140)).replace(Regex("\\s+"), " ")
        }
        if (vaultRoot() != null) {
            val notes = allNotes()
            for ((path, f) in notes) if (path.lowercase().contains(q) && out.size < limit) out += path to ""
            for ((path, f) in notes.take(600)) {
                if (out.size >= limit) break
                if (out.any { it.first == path }) continue
                val t = runCatching { ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes().take(80_000).toByteArray().toString(Charsets.UTF_8) } }.getOrNull() ?: continue
                if (t.lowercase().contains(q)) out += path to extrait(t)
            }
        } else {
            localRoot().walkTopDown().filter { it.isFile && it.name.endsWith(".md", true) }.forEach { f ->
                if (out.size >= limit) return@forEach
                val t = runCatching { f.readText() }.getOrDefault("")
                if (f.name.lowercase().contains(q) || t.lowercase().contains(q)) out += f.relativeTo(localRoot()).path to extrait(t)
            }
        }
        return out
    }

    private fun withMd(path: String) = path.trim().trim('/').let { if (it.endsWith(".md", true)) it else "$it.md" }

    fun readNote(path: String): String? {
        val p = withMd(path)
        val v = vaultRoot()
        if (v != null) {
            var cur: DocumentFile? = v
            for (part in p.split('/').filter { it.isNotBlank() }) cur = cur?.findFile(part)
            val f = cur?.takeIf { it.isFile } ?: return null
            return runCatching { ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        }
        val f = File(localRoot(), p)
        return if (f.isFile) f.readText() else null
    }

    /** Écrit (ou ajoute à la fin de) une note. Retourne l'emplacement. */
    fun writeNote(path: String, content: String, append: Boolean): String {
        val p = withMd(path)
        val folder = p.substringBeforeLast('/', "")
        val name = p.substringAfterLast('/')
        val body = if (append) (readNote(p)?.trimEnd()?.plus("\n\n") ?: "") + content else content
        return saveBytes(folder, name, body.toByteArray(), "text/markdown")
    }
}
