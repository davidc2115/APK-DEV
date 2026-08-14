package com.jarvis.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.jarvis.ai.data.db.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE syncedToObsidian = 0")
    suspend fun getUnsynced(): List<MessageEntity>

    @Update
    suspend fun update(message: MessageEntity)
}
