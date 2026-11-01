package fr.dd.jarvis.data.graph

import androidx.room.withTransaction
import fr.dd.jarvis.device.ObsidianWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GraphIndexReport(
    val indexedNotes: Int,
    val unresolvedReferences: Int,
    val indexedAt: Long
)

/** Reconstruit l'index dérivé des notes Markdown sans modifier les fichiers du coffre. */
class GraphIndexer(
    private val database: GraphDatabase,
    private val obsidian: ObsidianWriter
) {
    suspend fun rebuild(maxNotes: Int = 400): GraphIndexReport = withContext(Dispatchers.IO) {
        val indexedAt = System.currentTimeMillis()
        val graph = obsidian.graph(limiteNotes = maxNotes)
        val realNotes = graph.nodes.filterNot { it.ghost }

        val entities = realNotes.map { node ->
            GraphNodeEntity(
                id = "markdown:${node.id}",
                type = "NOTE",
                title = node.label,
                folder = node.folder,
                sourceId = "obsidian-markdown",
                sourcePath = node.id + ".md",
                indexedAt = indexedAt,
                availability = "PRESENT"
            )
        }

        // Remplacement atomique de l'index ; aucune écriture dans le coffre Markdown.
        database.withTransaction {
            database.graphDao().clearNodes()
            if (entities.isNotEmpty()) database.graphDao().insertNodes(entities)
        }

        GraphIndexReport(
            indexedNotes = entities.size,
            unresolvedReferences = graph.nodes.count { it.ghost },
            indexedAt = indexedAt
        )
    }
}
