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

object StorageController {

    fun listFiles(context: Context, path: String = "/sdcard"): String {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            return "❌ Le dossier « $path » n'existe pas ou n'est pas un répertoire valide."
        }

        val files = dir.listFiles()
            ?: return "❌ Impossible de lire le contenu du dossier « $path » (accès refusé ou permission manquante)."

        if (files.isEmpty()) return "📁 Le dossier « $path » est vide."

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)
        val sb = StringBuilder("📁 **Contenu de « $path » (${minOf(20, files.size)} premier(s))** :\n\n")

        files.take(20).forEachIndexed { i, file ->
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
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return "❌ Fichier introuvable : « $path »."
        }

        return try {
            val content = file.readText(Charsets.UTF_8)
            val preview = content.take(5000)
            if (content.length > 5000) {
                "📄 **Contenu de $path** (tronqué à 5000 caractères) :\n\n$preview\n\n[... suite tronquée]"
            } else {
                "📄 **Contenu de $path** :\n\n$content"
            }
        } catch (e: Exception) {
            "❌ Échec de la lecture du fichier : ${e.message}"
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

    fun listImages(context: Context, count: Int = 10): String {
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE
        )

        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "🖼️ Aucune photo trouvée."

                val sb = StringBuilder("🖼️ **Photos récentes (${minOf(count, c.count)})** :\n\n")
                val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)
                var idx = 0

                while (c.moveToNext() && idx < count) {
                    val name = c.getString(0) ?: "Image"
                    val date = c.getLong(1)
                    val size = formatSize(c.getLong(2))
                    val dateStr = if (date > 0) sdf.format(Date(date)) else "Date inconnue"

                    sb.append("${idx + 1}. 🖼️ **$name** ($size) — $dateStr\n")
                    idx++
                }
                sb.toString()
            } ?: "❌ Échec de la lecture des images."
        } catch (e: Exception) {
            "❌ Erreur lors de la lecture des images : ${e.message}"
        }
    }

    fun deleteFile(context: Context, path: String): String {
        val file = File(path)
        if (!file.exists()) return "❌ Le fichier « $path » n'existe pas."

        return try {
            if (file.delete()) {
                "🗑️ Fichier **${file.name}** supprimé avec succès."
            } else {
                "❌ Impossible de supprimer le fichier « $path »."
            }
        } catch (e: Exception) {
            "❌ Erreur lors de la suppression : ${e.message}"
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "Ko", "Mo", "Go", "To")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }
}
