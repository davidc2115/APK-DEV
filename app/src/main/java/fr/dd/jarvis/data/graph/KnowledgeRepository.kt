package fr.dd.jarvis.data.graph

import kotlinx.coroutines.flow.Flow

/** API unique consommée par l'interface et, plus tard, par le moteur 3D. */
class KnowledgeRepository(
    private val database: GraphDatabase,
    private val indexer: GraphIndexer
) {
    fun observeNodes(): Flow<List<GraphNodeEntity>> = database.graphDao().observeNodes()

    fun observeRelations(): Flow<List<GraphRelationEntity>> = database.graphDao().observeRelations()

    suspend fun rebuildMarkdownIndex(maxNotes: Int = 400): GraphIndexReport =
        indexer.rebuild(maxNotes)
}
