package com.jarvis.ai.core.websitegen

import com.jarvis.ai.core.ai.AIMessage
import com.jarvis.ai.core.ai.AIRouter
import com.jarvis.ai.core.ai.TaskKind
import java.io.File
import javax.inject.Inject

/**
 * Génère un site statique (HTML/CSS/JS) à partir d'une description, en réutilisant
 * l'agent de codage (AIRouter en mode CODE). Le résultat est un dossier de fichiers,
 * prévisualisable et exportable en .zip via ZipGenerator, ou publiable en GitHub Pages
 * via le module codage (GitHubPublisher).
 */
class WebsiteGenerator @Inject constructor(
    private val aiRouter: AIRouter
) {
    suspend fun generateSite(description: String, outputDir: File): List<File> {
        val systemPrompt = AIMessage(
            role = "system",
            content = "Génère un site web statique complet (index.html, style.css, script.js) " +
                "à partir de la description utilisateur. Réponds en blocs ```fichier\\n<code>```."
        )
        val response = aiRouter.route(TaskKind.CODE, listOf(systemPrompt, AIMessage("user", description)))
        val fileBlockRegex = Regex("```([^\\n`]+)\\n([\\s\\S]*?)```")
        return fileBlockRegex.findAll(response.text).map { match ->
            val file = File(outputDir, match.groupValues[1].trim())
            file.parentFile?.mkdirs()
            file.writeText(match.groupValues[2])
            file
        }.toList()
    }
}
