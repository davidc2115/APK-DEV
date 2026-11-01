package fr.dd.jarvis.data.graph

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [GraphNodeEntity::class, GraphRelationEntity::class], version = 1, exportSchema = false)
abstract class GraphDatabase : RoomDatabase() {
    abstract fun graphDao(): GraphDao

    companion object {
        @Volatile private var instance: GraphDatabase? = null

        fun getInstance(context: Context): GraphDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GraphDatabase::class.java,
                    "jarvis_knowledge_graph.db"
                ).build().also { instance = it }
            }
    }
}
