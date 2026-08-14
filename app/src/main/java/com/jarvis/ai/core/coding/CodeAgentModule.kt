package com.jarvis.ai.core.coding

import com.jarvis.ai.core.ai.AIMessage
import com.jarvis.ai.core.ai.AIRouter
import com.jarvis.ai.core.ai.TaskKind
import java.io.File
import javax.inject.Inject

/**
 * Équivalent mobile d'un Claude Code / Antigravity : décrit une tâche de code en langage
 * naturel, l'IA "codeur" (routée via AIRouter avec TaskKind.CODE) répond avec un ou
 * plusieurs fichiers, écrits dans un dossier de projet local puis versionnés (GitManager)
 * et publiés sur GitHub (GitHubPublisher).
 *
 * Le parsing de la réponse IA en fichiers suppose un format convenu dans le prompt système
 * (ex: blocs ```chemin/fichier.ext ... ``` ) — à affiner Phase 6 selon le fournisseur choisi.
 */
class CodeAgentModule @Inject constructor(
    private val aiRouter: AIRouter,
    private val gitManager: GitManager
) {
    suspend fun generateProject(prompt: String, projectDir: File): List<File> {
        val systemPrompt = AIMessage(
            role = "system",
            content = "Tu es un agent de codage. Réponds uniquement avec des blocs de code " +
                "précédés du chemin de fichier relatif, ex: ```chemin/fichier.kt\\n<code>\\n```"
        )
        val response = aiRouter.route(TaskKind.CODE, listOf(systemPrompt, AIMessage("user", prompt)))
        val files = parseFilesFromResponse(response.text, projectDir)
        gitManager.initIfNeeded(projectDir)
        gitManager.commitAll(projectDir, message = "Jarvis: $prompt")
        return files
    }

    private fun parseFilesFromResponse(text: String, projectDir: File): List<File> {
        val fileBlockRegex = Regex("```([^\\n`]+)\\n([\\s\\S]*?)```")
        return fileBlockRegex.findAll(text).map { match ->
            val relativePath = match.groupValues[1].trim()
            val content = match.groupValues[2]
            val file = File(projectDir, relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            file
        }.toList()
    }
}
