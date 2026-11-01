package fr.dd.jarvis.data.graph

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Élément indexé depuis une source locale. Le chemin d'origine reste la référence canonique. */
@Entity(tableName = "graph_nodes", indices = [Index(value = ["type"]), Index(value = ["folder"]), Index(value = ["sourcePath"], unique = true)])
data class GraphNodeEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val folder: String,
    val sourceId: String,
    val sourcePath: String,
    val description: String? = null,
    val mimeType: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val indexedAt: Long,
    val availability: String = "PRESENT"
)
