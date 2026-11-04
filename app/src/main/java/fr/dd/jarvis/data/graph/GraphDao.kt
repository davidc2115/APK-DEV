package fr.dd.jarvis.data.graph

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GraphDao {
    @Query("SELECT * FROM graph_nodes ORDER BY folder COLLATE NOCASE, title COLLATE NOCASE")
    fun observeNodes(): Flow<List<GraphNodeEntity>>

    @Query("SELECT * FROM graph_nodes WHERE id = :id LIMIT 1")
    suspend fun getNode(id: String): GraphNodeEntity?

    @Query("""
        SELECT * FROM graph_nodes
        WHERE title LIKE '%' || :query || '%' COLLATE NOCASE
           OR folder LIKE '%' || :query || '%' COLLATE NOCASE
           OR sourcePath LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY folder COLLATE NOCASE, title COLLATE NOCASE
        LIMIT :limit
    """)
    suspend fun searchNodes(query: String, limit: Int = 100): List<GraphNodeEntity>

    @Query("SELECT COUNT(*) FROM graph_nodes")
    suspend fun countNodes(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<GraphNodeEntity>)

    @Query("DELETE FROM graph_nodes")
    suspend fun clearNodes()

    @Query("SELECT * FROM graph_relations ORDER BY fromNodeId, toNodeId")
    fun observeRelations(): Flow<List<GraphRelationEntity>>

    /** Liens entrants et sortants, sans inventer de relation inverse. */
    @Query("""
        SELECT * FROM graph_relations
        WHERE fromNodeId = :nodeId OR toNodeId = :nodeId
        ORDER BY type, fromNodeId, toNodeId
    """)
    fun observeRelationsForNode(nodeId: String): Flow<List<GraphRelationEntity>>

    @Query("""
        SELECT * FROM graph_relations
        WHERE fromNodeId = :nodeId OR toNodeId = :nodeId
        ORDER BY type, fromNodeId, toNodeId
    """)
    suspend fun getRelationsForNode(nodeId: String): List<GraphRelationEntity>

    @Query("SELECT * FROM graph_relations WHERE origin = 'SUGGESTED'")
    suspend fun getSuggestedRelations(): List<GraphRelationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelations(relations: List<GraphRelationEntity>)

    @Query("DELETE FROM graph_relations")
    suspend fun clearRelations()
}
