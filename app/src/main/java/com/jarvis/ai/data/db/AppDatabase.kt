package com.jarvis.ai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.jarvis.ai.data.db.entities.MessageEntity

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
}
