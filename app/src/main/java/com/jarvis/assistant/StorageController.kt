package com.jarvis.assistant

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Contrôleur complet du système de fichiers Android (Stockage total).
 * Supporte : Résolution intelligente de chemins (Download, Documents, DCIM, SDCard),
 * Lecture, Écriture, Renommage, Déplacement, Copie, Suppression, Création de dossiers.
 */
object StorageController {

    private fun resolvePath(inputPath: String): File {
        val clean = inputPath.trim()
        if (clean.isBlank()) return Environment.getExternalStorageDirectory()

        val lower = clean.lowercase()
        return when {
            lower == "downloads" || lower == "download" || lower == "téléchargements" || lower == "telechargements" ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            lower == "documents" || lower == "document" ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            lower == "dcim" || lower == "photos" || lower == "pictures" || lower == "images" ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            lower == "music" || lower == "musique" ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            lower == "sdcard" || lower == "internal" || lower == "stockage" ->
                Environment.getExternalStorageDirectory()
            clean.startsWith("/") -> File(clean)
            else -> File(Environment.getExternalStorageDirectory(), clean)
        }
    }

    fun listFiles(context: Context, path: String = "/sdcard"): String {
        val dir = resolvePath(path)
        if (!dir.exists() || !dir.isDirectory) {
            return "❌ Le dossier « ${dir.absolutePath} » n'existe pas ou n'est pas un répertoire valide."
        }

        val files = dir.listFiles()
            ?: return "❌ Accès au dossier « ${dir.absolutePath} » refusé. Accordez la permission 'Accès total au stockage'."

        if (files.isEmpty()) return "📁 Le dossier « ${dir.absolutePath} » est vide."

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)
        val sb = StringBuilder("📁 **Contenu de « ${dir.absolutePath} » (${minOf(25, files.size)} élément(s))** :\n\n")

        files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).take(25).forEachIndexed { i, file ->
            val icon = if (file.isDirectory) "📁" else "📄"
            val size = if (file.isFile) formatSize(file.length()) else ""
            val date = sdf.format(Date(file.lastModified()))
            sb.append("${i + 1}. $icon **${file.name}** ${if (size.isNotEmpty()) "($size)" else ""} — $date\n")
        }

        return sb.toString().trimEnd()
    }

    fun searchFiles(context: Context, query: String): String {
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE
        )

        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "🔍 Aucun fichier trouvé pour « $query »."

                val sb = StringBuilder("🔍 **Résultats pour « $query » (${minOf(10, c.count)})** :\n\n")
                var idx = 0

                while (c.moveToNext() && idx < 10) {
                    val name = c.getString(0) ?: "Fichier"
                    val fullPath = c.getString(1) ?: ""
                    val size = formatSize(c.getLong(2))

                    sb.append("${idx + 1}. 📄 **$name** ($size)\n   `$fullPath`\n\n")
                    idx++
                }
                sb.toString().trimEnd()
            } ?: "❌ Impossible d'effectuer la recherche de fichiers."
        } catch (e: Exception) {
            "❌ Erreur lors de la recherche de fichiers : ${e.message}"
        }
    }

    fun readTextFile(context: Context, path: String): String {
        val file = resolvePath(path)
        if (!file.exists() || !file.isFile) {
            return "❌ Fichier introuvable : « ${file.absolutePath} »."
        }

        return try {
            val content = file.readText(Charsets.UTF_8)
            val preview = content.take(5000)
            if (content.length > 5000) {
                "📄 **Contenu de ${file.name}** (tronqué) :\n\n$preview\n\n[... suite tronquée]"
            } else {
                "📄 **Contenu de ${file.name}** :\n\n$content"
            }
        } catch (e: Exception) {
            "❌ Échec de la lecture du fichier : ${e.message}"
        }
    }

    fun writeTextFile(context: Context, path: String, content: String): String {
        val file = resolvePath(path)
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            "✍️ Fichier **${file.name}** créé/modifié avec succès (`${file.absolutePath}`)."
        } catch (e: Exception) {
            "❌ Échec de l'écriture dans le fichier : ${e.message}"
        }
    }

    fun renameFile(context: Context, oldPath: String, newNameOrPath: String): String {
        val oldFile = resolvePath(oldPath)
        if (!oldFile.exists()) return "❌ Fichier ou dossier introuvable : « ${oldFile.absolutePath} »."

        val newFile = if (newNameOrPath.contains("/")) {
            resolvePath(newNameOrPath)
        } else {
            File(oldFile.parentFile, newNameOrPath)
        }

        return try {
            if (oldFile.renameTo(newFile)) {
                "✏️ **${oldFile.name}** renommé avec succès en **${newFile.name}** !"
            } else {
                "❌ Échec du renommage de « ${oldFile.name} »."
            }
        } catch (e: Exception) {
            "❌ Erreur lors du renommage : ${e.message}"
        }
    }

    fun copyFile(context: Context, sourcePath: String, destPath: String): String {
        val src = resolvePath(sourcePath)
        if (!src.exists()) return "❌ Fichier source introuvable : « ${src.absolutePath} »."

        val dest = resolvePath(destPath)
        return try {
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            "📋 Fichier **${src.name}** copié vers **${dest.name}** avec succès."
        } catch (e: Exception) {
            "❌ Erreur lors de la copie : ${e.message}"
        }
    }

    fun moveFile(context: Context, sourcePath: String, destPath: String): String {
        val src = resolvePath(sourcePath)
        if (!src.exists()) return "❌ Fichier source introuvable : « ${src.absolutePath} »."

        val dest = resolvePath(destPath)
        return try {
            dest.parentFile?.mkdirs()
            if (src.renameTo(dest)) {
                "📦 Fichier **${src.name}** déplacé vers **${dest.path}** avec succès."
            } else {
                src.copyTo(dest, overwrite = true)
                src.delete()
                "📦 Fichier **${src.name}** déplacé avec succès."
            }
        } catch (e: Exception) {
            "❌ Erreur lors du déplacement : ${e.message}"
        }
    }

    fun createFolder(context: Context, path: String): String {
        val dir = resolvePath(path)
        return try {
            if (dir.exists()) return "📁 Le dossier « ${dir.absolutePath} » existe déjà."
            if (dir.mkdirs()) {
                "📁 Dossier **${dir.name}** créé avec succès (`${dir.absolutePath}`)."
            } else {
                "❌ Échec de la création du dossier « ${dir.absolutePath} »."
            }
        } catch (e: Exception) {
            "❌ Erreur de création du dossier : ${e.message}"
        }
    }

    fun deleteFile(context: Context, path: String): String {
        val file = resolvePath(path)
        if (!file.exists()) return "❌ Le fichier ou dossier « ${file.absolutePath} » n'existe pas."

        return try {
            val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (deleted) {
                "🗑️ **${file.name}** supprimé avec succès."
            } else {
                "❌ Impossible de supprimer « ${file.absolutePath} »."
            }
        } catch (e: Exception) {
            "❌ Erreur lors de la suppression : ${e.message}"
        }
    }

    fun getStorageInfo(context: Context): String {
        return try {
            val internalStat = StatFs(Environment.getDataDirectory().path)
            val totalInternal = internalStat.blockCountLong * internalStat.blockSizeLong
            val freeInternal = internalStat.availableBlocksLong * internalStat.blockSizeLong
            val usedInternal = totalInternal - freeInternal

            val sb = StringBuilder("💾 **Informations de stockage** :\n\n")
            sb.append("• **Espace total** : ${formatSize(totalInternal)}\n")
            sb.append("• **Utilisé** : ${formatSize(usedInternal)} (${(usedInternal * 100 / totalInternal)}%)\n")
            sb.append("• **Libre** : ${formatSize(freeInternal)}\n")

            sb.toString()
        } catch (e: Exception) {
            "❌ Erreur de récupération des informations de stockage : ${e.message}"
        }
    }

    fun listDownloads(context: Context): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return listFiles(context, downloadsDir.absolutePath)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "Ko", "Mo", "Go", "To")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }
}
