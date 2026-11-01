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

        val relations = graph.references.map { reference ->
            GraphRelationEntity(
                fromNodeId = "markdown:${reference.from}",
                toNodeId = if (reference.resolved) {
                    "markdown:${reference.to}"
                } else {
                    "unresolved:${reference.rawTarget.lowercase()}"
                },
                type = "WIKILINK",
                origin = "EXPLICIT",
                sourcePath = reference.from + ".md",
                updatedAt = indexedAt
            )
        }.distinctBy { Triple(it.fromNodeId, it.toNodeId, it.type) }

        // Remplacement atomique de l'index : notes et liens correspondent au même instantané.
        // Une cible absente est conservée comme référence unresolved:* sans créer de nœud artificiel.
        database.withTransaction {
            database.graphDao().clearRelations()
            database.graphDao().clearNodes()
            if (entities.isNotEmpty()) database.graphDao().insertNodes(entities)
            if (relations.isNotEmpty()) database.graphDao().insertRelations(relations)
        }

        GraphIndexReport(
            indexedNotes = entities.size,
            unresolvedReferences = relations.count { it.toNodeId.startsWith("unresolved:") },
            indexedAt = indexedAt
        )
    }
}
