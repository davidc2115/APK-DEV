package com.jarvis.assistant

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fiches contacts enrichies (catégorie, téléphone, email, adresse, GPS)
 * stockées directement comme notes Markdown dans le dossier "Contacts" du
 * vault Obsidian de l'utilisateur (voir ObsidianController.getVaultRoot).
 * Contrairement au carnet d'adresses natif du téléphone, ces fiches sont
 * visibles et éditables directement dans l'app Obsidian, pas cachées dans
 * une base de données.
 *
 * Format de chaque fiche (Contacts/Nom.md) :
 * ---
 * category: travail
 * phone: "0612345678"
 * email: pierre@exemple.com
 * address: "12 rue de Paris, 75001 Paris"
 * latitude: 48.8566
 * longitude: 2.3522
 * updated: 2026-08-12 14:30
 * tags: [jarvis, contact]
 * ---
 *
 * # Pierre Dupont
 *
 * Notes libres ici...
 */
object PeopleController {

    private val VALID_CATEGORIES = setOf("travail", "personnel", "famille", "autre")
    private val updatedFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private fun contactsFolder(context: Context): File =
        File(ObsidianController.getVaultRoot(context), "Contacts").also { it.mkdirs() }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "-").trim()

    // ─────────────────────────────────────────────────────────────────────────
    // Lecture / écriture d'une fiche
    // ─────────────────────────────────────────────────────────────────────────

    private data class ContactNote(
        val name: String,
        val category: String,
        val phone: String?,
        val email: String?,
        val address: String?,
        val latitude: Double?,
        val longitude: Double?,
        val notes: String,
        val file: File
    )

    /** Parse une fiche existante (frontmatter simple clé: valeur + corps libre). */
    private fun parseContactFile(file: File): ContactNote? {
        if (!file.exists()) return null
        val text = try { file.readText() } catch (e: Exception) { return null }

        val parts = text.split("---").filter { it.isNotBlank() }
        if (parts.size < 2) return null

        val frontmatter = parts[0]
        val body = parts.drop(1).joinToString("---").trim()

        fun field(key: String): String? {
            val regex = Regex("^$key:\\s*\"?([^\"\\n]*)\"?\\s*$", RegexOption.MULTILINE)
            return regex.find(frontmatter)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        }

        val notesOnly = body.lines().dropWhile { it.startsWith("#") || it.isBlank() }.joinToString("\n").trim()

        return ContactNote(
            name = file.nameWithoutExtension,
            category = field("category") ?: "autre",
            phone = field("phone"),
            email = field("email"),
            address = field("address"),
            latitude = field("latitude")?.toDoubleOrNull(),
            longitude = field("longitude")?.toDoubleOrNull(),
            notes = notesOnly,
            file = file
        )
    }

    fun saveContact(
        context: Context,
        name: String,
        category: String = "autre",
        phone: String? = null,
        email: String? = null,
        address: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        notes: String? = null
    ): String {
        if (name.isBlank()) return "❌ Nom du contact manquant."
        val cat = category.lowercase().trim().let { if (it in VALID_CATEGORIES) it else "autre" }

        return try {
            val folder = contactsFolder(context)
            val file = File(folder, "${safeFileName(name)}.md")
            val existing = parseContactFile(file)
            val isUpdate = existing != null

            val finalPhone = phone ?: existing?.phone
            val finalEmail = email ?: existing?.email
            val finalAddress = address ?: existing?.address
            val finalLat = latitude ?: existing?.latitude
            val finalLng = longitude ?: existing?.longitude
            val finalNotes = notes ?: existing?.notes ?: ""

            val frontmatterLines = mutableListOf("category: $cat")
            finalPhone?.let { frontmatterLines.add("phone: \"$it\"") }
            finalEmail?.let { frontmatterLines.add("email: \"$it\"") }
            finalAddress?.let { frontmatterLines.add("address: \"$it\"") }
            finalLat?.let { frontmatterLines.add("latitude: $it") }
            finalLng?.let { frontmatterLines.add("longitude: $it") }
            frontmatterLines.add("updated: ${updatedFormat.format(Date())}")
            frontmatterLines.add("tags: [jarvis, contact]")

            val content = buildString {
                append("---\n")
                append(frontmatterLines.joinToString("\n"))
                append("\n---\n\n")
                append("# $name\n\n")
                if (finalNotes.isNotBlank()) append(finalNotes) else append("_Aucune note._")
            }

            file.writeText(content)

            if (isUpdate) "✅ Fiche de **$name** mise à jour (catégorie : $cat) dans le vault Obsidian."
            else "✅ **$name** ajouté(e) aux contacts $cat, dans Obsidian → Contacts/${safeFileName(name)}.md"
        } catch (e: Exception) {
            "❌ Erreur lors de l'enregistrement dans Obsidian : ${e.message}"
        }
    }

    fun getContactDetails(context: Context, name: String): String {
        val contact = findContact(context, name) ?: return "❌ Aucune fiche trouvée pour « $name »."
        return formatFullDetails(contact)
    }

    fun searchContacts(context: Context, query: String): String {
        val folder = contactsFolder(context)
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        val q = query.lowercase()

        val matches = files.mapNotNull { parseContactFile(it) }.filter { c ->
            c.name.lowercase().contains(q) ||
                (c.phone?.lowercase()?.contains(q) == true) ||
                (c.email?.lowercase()?.contains(q) == true) ||
                (c.address?.lowercase()?.contains(q) == true) ||
                c.notes.lowercase().contains(q)
        }

        if (matches.isEmpty()) return "🔍 Aucun contact trouvé pour « $query »."
        if (matches.size == 1) return formatFullDetails(matches[0])

        val sb = StringBuilder("🔍 **${matches.size} résultats pour « $query »** :\n\n")
        matches.forEach { appendSummary(sb, it) }
        return sb.toString().trim()
    }

    fun listByCategory(context: Context, category: String): String {
        val folder = contactsFolder(context)
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        val cat = category.lowercase().trim()
        val all = cat.isBlank() || cat == "tous" || cat == "tout"

        val contacts = files.mapNotNull { parseContactFile(it) }
            .filter { all || it.category == cat }
            .sortedBy { it.name }

        if (contacts.isEmpty()) return "Aucun contact${if (!all) " dans la catégorie « $cat »" else ""}."

        val sb = StringBuilder("📇 **Contacts${if (!all) " — $cat" else ""}** :\n\n")
        contacts.forEach { appendSummary(sb, it) }
        return sb.toString().trim()
    }

    fun deleteContact(context: Context, name: String): String {
        val contact = findContact(context, name) ?: return "❌ Aucune fiche trouvée pour « $name »."
        return if (contact.file.delete()) {
            "🗑️ Fiche de **${contact.name}** supprimée du vault Obsidian."
        } else {
            "❌ Échec de la suppression de la fiche."
        }
    }

    /** Trouve la fiche d'un contact et ouvre Maps sur son adresse (ou ses coordonnées GPS si connues). */
    fun navigateToContact(context: Context, name: String): String {
        val contact = findContact(context, name)
            ?: return "❌ Aucune fiche trouvée pour « $name ». Ajoute d'abord son adresse avec save_contact_profile."

        val destination = if (contact.latitude != null && contact.longitude != null) {
            "${contact.latitude},${contact.longitude}"
        } else {
            contact.address
        }

        if (destination.isNullOrBlank()) {
            return "❌ **${contact.name}** n'a pas d'adresse ni de coordonnées GPS enregistrées."
        }

        val mapsResult = LocationController.openMaps(context, destination)
        return "🧭 Direction ${contact.name} — $mapsResult"
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun findContact(context: Context, name: String): ContactNote? {
        val folder = contactsFolder(context)
        val exact = File(folder, "${safeFileName(name)}.md")
        parseContactFile(exact)?.let { return it }

        // Recherche approximative si le nom exact ne correspond à aucun fichier
        val files = folder.listFiles { f -> f.extension == "md" } ?: emptyArray()
        val q = name.lowercase()
        return files.mapNotNull { parseContactFile(it) }
            .firstOrNull { it.name.lowercase().contains(q) }
    }

    private fun formatFullDetails(c: ContactNote): String {
        return buildString {
            append("📇 **${c.name}** (${c.category})\n\n")
            if (!c.phone.isNullOrBlank()) append("📞 Téléphone : ${c.phone}\n")
            if (!c.email.isNullOrBlank()) append("✉️ Email : ${c.email}\n")
            if (!c.address.isNullOrBlank()) append("📍 Adresse : ${c.address}\n")
            if (c.latitude != null && c.longitude != null) append("🌐 GPS : ${c.latitude}, ${c.longitude}\n")
            if (c.notes.isNotBlank()) append("\n📝 ${c.notes}\n")
        }.trim()
    }

    private fun appendSummary(sb: StringBuilder, c: ContactNote) {
        sb.append("• **${c.name}** (${c.category})")
        if (!c.phone.isNullOrBlank()) sb.append(" — 📞 ${c.phone}")
        if (!c.email.isNullOrBlank()) sb.append(" — ✉️ ${c.email}")
        sb.append("\n")
        if (!c.address.isNullOrBlank()) sb.append("   📍 ${c.address}\n")
        sb.append("\n")
    }
}
