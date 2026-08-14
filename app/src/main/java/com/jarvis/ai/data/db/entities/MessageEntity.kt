package com.jarvis.ai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,           // "user" | "assistant"
    val content: String,
    val providerName: String?,  // quel fournisseur IA a répondu (traçabilité/debug)
    val timestamp: Long = System.currentTimeMillis(),
    val syncedToObsidian: Boolean = false
)
