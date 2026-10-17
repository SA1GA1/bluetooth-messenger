package com.example.bluetooth_messenger.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT c.*, (SELECT m.text FROM messages m WHERE m.conversationId=c.id ORDER BY m.receivedAt DESC,m.id DESC LIMIT 1) AS lastText, (SELECT COUNT(*) FROM messages m WHERE m.conversationId=c.id AND m.unread=1) AS unreadCount FROM conversations c ORDER BY c.updatedAt DESC,c.id ASC")
    fun summaries(): Flow<List<ConversationRow>>

    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE")
    fun observe(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun find(id: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(contact: ContactEntity): Long

    @Query("UPDATE contacts SET verified = 1 WHERE id = :id")
    suspend fun verify(id: String)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun conversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: String): ConversationEntity?

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity): Long
}
