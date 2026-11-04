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

    suspend fun suggestRelation(fromTitle: String, toTitle: String, type: String = "RELATED_TO", confidence: Float = 0.65f): String {
        val allowed = setOf("RELATED_TO", "PROJECT_CONTEXT", "SUPPORTS", "PREFERENCE_FOR", "PART_OF")
        if (type !in allowed) return "Type de relation refusé."
        val from = searchNodes(fromTitle.trim(), 10).filter { it.title.equals(fromTitle.trim(), ignoreCase = true) }
        val to = searchNodes(toTitle.trim(), 10).filter { it.title.equals(toTitle.trim(), ignoreCase = true) }
        if (from.size != 1 || to.size != 1) return "Lien non créé : chaque note doit correspondre à un titre exact et unique (source " + from.size + ", cible " + to.size + ")."
        if (from.first().id == to.first().id) return "Lien ignoré : une note ne peut pas être liée à elle-même."
        val relation = GraphRelationEntity(
            fromNodeId = from.first().id,
            toNodeId = to.first().id,
            type = type,
            origin = "SUGGESTED",
            confidence = confidence.coerceIn(0.50f, 0.95f),
            sourcePath = "jarvis-learning",
            updatedAt = System.currentTimeMillis()
        )
        database.graphDao().insertRelations(listOf(relation))
        return "Suggestion de lien ajoutée au graphe : « " + from.first().title + " » → « " + to.first().title +
            " » (" + type + ", confiance " + String.format("%.2f", confidence) + "). Elle reste identifiée comme suggestion, pas comme lien confirmé."
    }

    suspend fun rebuildMarkdownIndex(maxNotes: Int = 400): GraphIndexReport =
        indexer.rebuild(maxNotes)
}
