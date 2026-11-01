package fr.dd.jarvis.data.graph

import kotlinx.coroutines.flow.Flow

/** API unique consommée par l'interface et, plus tard, par le moteur 3D. */
class KnowledgeRepository(
    private val database: GraphDatabase,
    private val indexer: GraphIndexer
) {
    fun observeNodes(): Flow<List<GraphNodeEntity>> = database.graphDao().observeNodes()

    fun observeRelations(): Flow<List<GraphRelationEntity>> = database.graphDao().observeRelations()

    fun observeRelationsForNode(nodeId: String): Flow<List<GraphRelationEntity>> =
        database.graphDao().observeRelationsForNode(nodeId)

    suspend fun getNode(id: String): GraphNodeEntity? =
        database.graphDao().getNode(id)

    suspend fun getRelationsForNode(id: String): List<GraphRelationEntity> =
        database.graphDao().getRelationsForNode(id)

    suspend fun searchNodes(query: String, limit: Int = 100): List<GraphNodeEntity> =
        if (query.isBlank()) emptyList() else database.graphDao().searchNodes(query.trim(), limit.coerceIn(1, 500))

    suspend fun rebuildMarkdownIndex(maxNotes: Int = 400): GraphIndexReport =
        indexer.rebuild(maxNotes)
}
