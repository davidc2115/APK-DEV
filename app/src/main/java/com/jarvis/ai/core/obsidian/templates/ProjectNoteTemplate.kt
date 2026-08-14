package com.jarvis.ai.core.obsidian.templates

/** Note de projet (ex: suivi d'un dépôt GitHub créé par le module codage). */
data class ProjectInfo(
    val title: String,
    val githubUrl: String? = null,
    val status: String = "En cours",
    val description: String = ""
)

object ProjectNoteTemplate {
    fun render(info: ProjectInfo): String = buildString {
        appendLine("---")
        appendLine("type: projet")
        appendLine("status: ${info.status}")
        info.githubUrl?.let { appendLine("github: \"$it\"") }
        appendLine("---")
        appendLine()
        appendLine("# ${info.title}")
        appendLine()
        if (info.description.isNotBlank()) appendLine(info.description)
        info.githubUrl?.let { appendLine("\n[Voir sur GitHub]($it)") }
    }
}
