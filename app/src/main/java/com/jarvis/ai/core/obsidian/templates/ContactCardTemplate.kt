package com.jarvis.ai.core.obsidian.templates

/**
 * Fiche contact Markdown paramétrable : catégories, propriétés (frontmatter YAML) et mise en
 * page librement modifiables par l'utilisateur — ce générateur fournit juste une base, éditable
 * ensuite comme n'importe quelle note Obsidian.
 */
data class ContactInfo(
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val category: String = "Général",
    val tags: List<String> = emptyList(),
    val notes: String = ""
)

object ContactCardTemplate {
    fun render(info: ContactInfo): String = buildString {
        appendLine("---")
        appendLine("type: contact")
        appendLine("category: ${info.category}")
        appendLine("tags: [${info.tags.joinToString(", ")}]")
        info.phone?.let { appendLine("phone: \"$it\"") }
        info.email?.let { appendLine("email: \"$it\"") }
        appendLine("---")
        appendLine()
        appendLine("# ${info.name}")
        appendLine()
        info.phone?.let { appendLine("- **Téléphone** : $it") }
        info.email?.let { appendLine("- **Email** : $it") }
        appendLine("- **Catégorie** : ${info.category}")
        if (info.notes.isNotBlank()) {
            appendLine()
            appendLine("## Notes")
            appendLine(info.notes)
        }
    }
}
