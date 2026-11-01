package fr.dd.jarvis.data.graph

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Relation typée ; EXPLICIT et SUGGESTED ne doivent jamais être confondues. */
@Entity(tableName = "graph_relations", indices = [Index(value = ["fromNodeId"]), Index(value = ["toNodeId"]), Index(value = ["fromNodeId", "toNodeId", "type", "origin"], unique = true)])
data class GraphRelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromNodeId: String,
    val toNodeId: String,
    val type: String,
    val origin: String = "EXPLICIT",
    val confidence: Float? = null,
    val sourcePath: String? = null,
    val updatedAt: Long
)
