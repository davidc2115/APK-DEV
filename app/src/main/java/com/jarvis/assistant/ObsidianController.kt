package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ObsidianController {

    private const val TAG = "ObsidianController"
    private val dateFormat   = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat   = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val displayFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // ─────────────────────────────────────────────────────────────────────────
    // Vault root
    // ─────────────────────────────────────────────────────────────────────────

    fun getVaultRoot(context: Context): File {
        val saved = Prefs.getObsidianVaultPath(context)
        return if (saved.isNotBlank()) File(saved)
        else File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "JARVIS-Vault"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Init vault structure
    // ─────────────────────────────────────────────────────────────────────────

    fun initVault(context: Context): String {
        return try {
            val root = getVaultRoot(context)
            val folders = listOf("Daily Notes", "Notes Rapides", "Contacts", "Tâches", "Emails", "Réflexions", ".obsidian")
            folders.forEach { File(root, it).mkdirs() }

            // README
            val readme = File(root, "README.md")
            if (!readme.exists()) {
                readme.writeText("""
# 🧠 JARVIS Second Brain

Ce vault est géré par **JARVIS Assistant**.

## Dossiers
- 📅 **Daily Notes** — Notes journalières automatiques
- 📋 **Notes Rapides** — Notes créées par commande vocale
- 📞 **Contacts** — Fiches contacts
- ✅ **Tâches** — Listes de tâches
- 📧 **Emails** — Résumés d'emails importants
- 💭 **Réflexions** — Pensées et idées

---
*Vault créé par JARVIS le ${displayFormat.format(Date())}*
""".trimIndent())
            }

            Prefs.saveObsidianVaultPath(context, root.absolutePath)
            "✅ Vault initialisé :\n${root.absolutePath}\n\n${folders.dropLast(1).joinToString("\n") { "📁 $it" }}"
        } catch (e: Exception) {
            "❌ Erreur lors de l'initialisation : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create note
    // ─────────────────────────────────────────────────────────────────────────

    fun createNote(
        context: Context,
        title: String,
        content: String,
        folder: String = "Notes Rapides",
        tags: List<String> = listOf("jarvis")
    ): String {
        return try {
            val root     = getVaultRoot(context)
            val dir      = File(root, folder).also { it.mkdirs() }
            val safeTitle = title.replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()
            val file     = File(dir, "$safeTitle.md")
            val now      = Date()
            val tagsStr  = tags.joinToString(", ") { "\"$it\"" }

            val body = """
---
date: ${dateFormat.format(now)}
tags: [$tagsStr]
source: JARVIS Assistant
---

# $title

$content

---
*Créé par JARVIS le ${displayFormat.format(now)} à ${timeFormat.format(now)}*
""".trimIndent()

            file.writeText(body)
            Log.d(TAG, "Note created: ${file.absolutePath}")
            "✅ Note créée : **$safeTitle**\n📁 Dossier : $folder\n📄 Chemin : ${file.absolutePath}"
        } catch (e: Exception) {
            "❌ Erreur création note : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create daily note
    // ─────────────────────────────────────────────────────────────────────────

    fun createDailyNote(context: Context, content: String = ""): String {
        val today     = dateFormat.format(Date())
        val todayDisp = displayFormat.format(Date())
        val root      = getVaultRoot(context)
        val dir       = File(root, "Daily Notes").also { it.mkdirs() }
        val file      = File(dir, "$today.md")

        return try {
            if (file.exists()) {
                // Append to existing
                if (content.isNotBlank()) {
                    file.appendText("\n\n${timeFormat.format(Date())} — $content")
                    "📅 Ajouté à la note du jour ($todayDisp) :\n\"$content\""
                } else {
                    "📅 Note du jour ($todayDisp) existe déjà.\n\n${file.readText().take(500)}…"
                }
            } else {
                val body = """
---
date: $today
tags: ["daily", "jarvis"]
source: JARVIS Assistant
---

# Journal du $todayDisp

${if (content.isNotBlank()) content else "— Notes du jour —"}

---
*Créé par JARVIS*
""".trimIndent()
                file.writeText(body)
                "📅 Note journalière créée pour $todayDisp !\n📄 ${file.absolutePath}"
            }
        } catch (e: Exception) {
            "❌ Erreur note journalière : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read note
    // ─────────────────────────────────────────────────────────────────────────

    fun readNote(context: Context, query: String): String {
        val file = findNote(context, query)
            ?: return "❌ Aucune note trouvée pour \"$query\"."
        return try {
            val content = file.readText()
            "📄 **${file.nameWithoutExtension}**\n\n$content"
        } catch (e: Exception) {
            "❌ Impossible de lire la note : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search notes (filename + content)
    // ─────────────────────────────────────────────────────────────────────────

    fun searchNotes(context: Context, query: String): String {
        val root    = getVaultRoot(context)
        val results = mutableListOf<Pair<File, String>>()
        val lower   = query.lowercase()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
            .forEach { file ->
                val nameMatch    = file.nameWithoutExtension.lowercase().contains(lower)
                var contentMatch = ""
                try {
                    val text = file.readText()
                    val idx  = text.lowercase().indexOf(lower)
                    if (idx >= 0) {
                        val start   = maxOf(0, idx - 40)
                        val end     = minOf(text.length, idx + query.length + 80)
                        contentMatch = "…${text.substring(start, end).replace("\n", " ")}…"
                    }
                } catch (_: Exception) {}

                if (nameMatch || contentMatch.isNotBlank()) {
                    val preview = if (nameMatch && contentMatch.isBlank()) "(titre)" else contentMatch
                    results.add(file to preview)
                }
            }

        if (results.isEmpty()) return "🔍 Aucun résultat pour \"$query\" dans le vault."

        val sb = StringBuilder("🔍 **${results.size} résultat(s) pour \"$query\"** :\n\n")
        results.take(10).forEach { (file, preview) ->
            sb.append("📄 **${file.nameWithoutExtension}**\n")
            sb.append("   📁 ${file.parentFile?.name}\n")
            if (preview != "(titre)") sb.append("   › $preview\n")
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Append to note
    // ─────────────────────────────────────────────────────────────────────────

    fun appendToNote(context: Context, query: String, text: String): String {
        val file = findNote(context, query)
            ?: return "❌ Note \"$query\" introuvable. Créez-la d'abord."
        return try {
            file.appendText("\n\n${timeFormat.format(Date())} — $text")
            "✅ Ajouté à **${file.nameWithoutExtension}** :\n\"$text\""
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // List notes
    // ─────────────────────────────────────────────────────────────────────────

    fun listNotes(context: Context, folder: String = ""): String {
        val root    = getVaultRoot(context)
        val baseDir = if (folder.isBlank()) root else File(root, folder)

        if (!baseDir.exists()) return "📁 Le dossier \"$folder\" n'existe pas dans le vault."

        val files = baseDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
            .sortedByDescending { it.lastModified() }
            .toList()

        if (files.isEmpty()) return "📋 Aucune note dans ${if (folder.isBlank()) "le vault" else "\"$folder\""}."

        val sb = StringBuilder("📋 **${files.size} note(s)** ${if (folder.isBlank()) "dans le vault" else "dans \"$folder\""}:\n\n")
        files.take(30).forEach { file ->
            val rel  = file.relativeTo(root).parent ?: ""
            val date = displayFormat.format(Date(file.lastModified()))
            sb.append("📄 **${file.nameWithoutExtension}**")
            if (rel.isNotBlank()) sb.append(" (📁 $rel)")
            sb.append(" — $date\n")
        }
        if (files.size > 30) sb.append("\n… et ${files.size - 30} autres.")
        return sb.toString().trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delete note
    // ─────────────────────────────────────────────────────────────────────────

    fun deleteNote(context: Context, query: String): String {
        val file = findNote(context, query)
            ?: return "❌ Note \"$query\" introuvable."
        return try {
            val name = file.nameWithoutExtension
            file.delete()
            "🗑 Note **$name** supprimée."
        } catch (e: Exception) {
            "❌ Erreur suppression : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Open in Obsidian app
    // ─────────────────────────────────────────────────────────────────────────

    fun openInObsidian(context: Context, query: String): String {
        val root     = getVaultRoot(context)
        val vaultName = root.name
        val file     = if (query.isBlank()) null else findNote(context, query)

        return try {
            val uriStr = if (file != null) {
                val rel = file.relativeTo(root).path.replace(File.separator, "/").removeSuffix(".md")
                "obsidian://open?vault=${Uri.encode(vaultName)}&file=${Uri.encode(rel)}"
            } else {
                "obsidian://open?vault=${Uri.encode(vaultName)}"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "🟣 Ouverture dans Obsidian : ${file?.nameWithoutExtension ?: vaultName}"
        } catch (e: Exception) {
            "⚠️ Obsidian n'est pas installé sur ce téléphone.\n\nTéléchargez-le sur le Play Store : 'Obsidian — Connected Notes'"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vault stats
    // ─────────────────────────────────────────────────────────────────────────

    fun getVaultStats(context: Context): String {
        return try {
            val root    = getVaultRoot(context)
            if (!root.exists()) return "📊 Le vault n'existe pas encore. Initialisez-le d'abord."

            val allFiles = root.walkTopDown()
                .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
                .toList()

            val totalSize  = allFiles.sumOf { it.length() }
            val sizeKb     = totalSize / 1024
            val folders    = allFiles.mapNotNull { it.parentFile?.name }.toSet()
            val todayStr   = dateFormat.format(Date())
            val todayNotes = allFiles.count { it.name.startsWith(todayStr) }

            """
📊 **Statistiques du Vault** :

📄 Notes totales   : ${allFiles.size}
📁 Dossiers        : ${folders.size}
💾 Taille totale   : ${sizeKb} Ko
📅 Notes du jour   : $todayNotes
📂 Chemin          : ${root.absolutePath}
            """.trimIndent()
        } catch (e: Exception) {
            "❌ Erreur stats : ${e.message}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helper — find note by partial name
    // ─────────────────────────────────────────────────────────────────────────

    private fun findNote(context: Context, query: String): File? {
        val root  = getVaultRoot(context)
        val lower = query.lowercase()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "md" && !it.path.contains(".obsidian") }
            .firstOrNull { it.nameWithoutExtension.lowercase().contains(lower) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parse voice command and dispatch
    // ─────────────────────────────────────────────────────────────────────────

    fun handleVoiceCommand(context: Context, input: String): String? {
        val text  = input.trim()
        val lower = text.lowercase()
        return when {
            lower.startsWith("note que ")       -> createNote(context, title = "Note rapide ${timeFormat.format(Date())}", content = text.substring(9))
            lower.startsWith("note :")          -> createNote(context, title = "Note rapide ${timeFormat.format(Date())}", content = text.substring(6))
            lower.startsWith("écris dans mon journal") || lower.startsWith("journal ") -> {
                val content = text.substringAfter(" ").substringAfter(":").trim()
                createDailyNote(context, content)
            }
            lower == "note du jour" || lower == "daily note" || lower == "journal du jour" ->
                createDailyNote(context)
            lower.startsWith("cherche dans mes notes ") ->
                searchNotes(context, text.substring(23))
            lower.startsWith("lis ma note sur ") ->
                readNote(context, text.substring(16))
            lower.startsWith("ajoute à ") && lower.contains(" : ") -> {
                val parts = text.substring(9).split(" : ", limit = 2)
                if (parts.size == 2) appendToNote(context, parts[0].trim(), parts[1].trim())
                else null
            }
            lower == "mes notes" || lower == "liste mes notes" || lower == "voir mes notes" ->
                listNotes(context)
            lower == "stats vault" || lower == "combien de notes" ->
                getVaultStats(context)
            lower == "ouvrir obsidian" ->
                openInObsidian(context, "")
            else -> null
        }
    }
}
