package com.jarvis.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class ConversationSummary(val id: Long, val title: String, val updatedAt: Long)

/**
 * Historique persistant des conversations (SQLite embarqué, zéro dépendance
 * externe). Chaque conversation est sauvegardée avec un titre dérivé du
 * premier message, pour être listée dans la barre latérale.
 */
class ConversationDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "jarvis_conversations.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                messages_json TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS conversations")
        onCreate(db)
    }

    companion object {
        @Volatile private var instance: ConversationDatabase? = null

        fun get(context: Context): ConversationDatabase =
            instance ?: synchronized(this) {
                instance ?: ConversationDatabase(context).also { instance = it }
            }
    }
}

object ConversationHistoryManager {

    private fun messagesToJson(messages: List<Message>): String {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(
                JSONObject()
                    .put("text", m.text)
                    .put("isUser", m.isUser)
                    .put("imageBase64", m.imageBase64 ?: JSONObject.NULL)
                    .put("imageMimeType", m.imageMimeType ?: JSONObject.NULL)
            )
        }
        return arr.toString()
    }

    private fun jsonToMessages(json: String): MutableList<Message> {
        val result = mutableListOf<Message>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    Message(
                        text = o.optString("text", ""),
                        isUser = o.optBoolean("isUser", false),
                        imageBase64 = if (o.isNull("imageBase64")) null else o.optString("imageBase64"),
                        imageMimeType = if (o.isNull("imageMimeType")) null else o.optString("imageMimeType")
                    )
                )
            }
        } catch (_: Exception) { }
        return result
    }

    private fun deriveTitle(messages: List<Message>): String {
        val firstUserMsg = messages.firstOrNull { it.isUser }?.text?.trim()
        if (firstUserMsg.isNullOrBlank()) return "Nouvelle conversation"
        return if (firstUserMsg.length > 40) firstUserMsg.take(40) + "…" else firstUserMsg
    }

    /** Sauvegarde (création ou mise à jour) la conversation courante. Renvoie son ID. */
    fun save(context: Context, conversationId: Long?, messages: List<Message>): Long? {
        if (messages.isEmpty()) return conversationId

        val db = ConversationDatabase.get(context).writableDatabase
        val values = ContentValues().apply {
            put("title", deriveTitle(messages))
            put("updated_at", System.currentTimeMillis())
            put("messages_json", messagesToJson(messages))
        }

        return if (conversationId != null) {
            db.update("conversations", values, "id = ?", arrayOf(conversationId.toString()))
            conversationId
        } else {
            db.insert("conversations", null, values)
        }
    }

    fun load(context: Context, conversationId: Long): MutableList<Message> {
        val db = ConversationDatabase.get(context).readableDatabase
        db.query(
            "conversations", arrayOf("messages_json"), "id = ?",
            arrayOf(conversationId.toString()), null, null, null
        ).use { c ->
            if (c.moveToFirst()) return jsonToMessages(c.getString(0))
        }
        return mutableListOf()
    }

    fun listAll(context: Context): List<ConversationSummary> {
        val db = ConversationDatabase.get(context).readableDatabase
        val result = mutableListOf<ConversationSummary>()
        db.query(
            "conversations", arrayOf("id", "title", "updated_at"),
            null, null, null, null, "updated_at DESC"
        ).use { c ->
            while (c.moveToNext()) {
                result.add(ConversationSummary(c.getLong(0), c.getString(1), c.getLong(2)))
            }
        }
        return result
    }

    fun delete(context: Context, conversationId: Long) {
        val db = ConversationDatabase.get(context).writableDatabase
        db.delete("conversations", "id = ?", arrayOf(conversationId.toString()))
    }
}
