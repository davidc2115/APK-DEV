package com.jarvis.ai.core.obsidian

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.jarvis.ai.data.settings.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accès au vault Obsidian de l'utilisateur via Storage Access Framework (SAF) :
 * l'utilisateur choisit son dossier de vault une fois (ACTION_OPEN_DOCUMENT_TREE) depuis
 * les réglages ; l'URI est persistée (takePersistableUriPermission) et réutilisée ici.
 *
 * C'est la source de vérité de la mémoire long terme de Jarvis : fiches contact, notes de
 * projet, historique de conversation miroité — tout est du Markdown lisible/éditable
 * directement dans Obsidian desktop/mobile, donc versionnable avec un simple repo Git si
 * l'utilisateur le souhaite.
 */
@Singleton
class ObsidianVaultManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsDataStore
) {
    private fun vaultRoot(): DocumentFile? {
        val uri = settings.getVaultUri() ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    /** Crée (ou récupère) un sous-dossier du vault, ex: "Contacts", "Jarvis/Conversations". */
    fun ensureFolder(path: String): DocumentFile? {
        var current = vaultRoot() ?: return null
        for (segment in path.split("/").filter { it.isNotBlank() }) {
            current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
        }
        return current
    }

    /** Écrit (ou remplace) un fichier Markdown à `folderPath/fileName.md`. */
    fun writeNote(folderPath: String, fileName: String, markdownContent: String): Boolean {
        val folder = ensureFolder(folderPath) ?: return false
        val safeName = if (fileName.endsWith(".md")) fileName else "$fileName.md"
        val existing = folder.findFile(safeName)
        existing?.delete()
        val file = folder.createFile("text/markdown", safeName) ?: return false
        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            out.write(markdownContent.toByteArray(Charsets.UTF_8))
        }
        return true
    }

    /** Lit le contenu d'une note existante, ou null si absente. */
    fun readNote(folderPath: String, fileName: String): String? {
        val folder = ensureFolder(folderPath) ?: return null
        val safeName = if (fileName.endsWith(".md")) fileName else "$fileName.md"
        val file = folder.findFile(safeName) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.readText()
    }
}
