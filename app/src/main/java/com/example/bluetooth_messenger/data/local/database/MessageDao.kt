package com.example.bluetooth_messenger.data.local.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private const val MESSAGE_SELECTION = "SELECT m.*, " +
    "(SELECT COUNT(*) FROM pending_queue d WHERE d.messageId=m.id AND d.status IN ('Delivered','Read')) AS deliveredCount, " +
    "(SELECT COUNT(*) FROM pending_queue d WHERE d.messageId=m.id) AS targetCount, " +
    "(SELECT COUNT(*) FROM pending_queue d WHERE d.messageId=m.id AND d.status='Read') AS readCount, " +
    "(SELECT COUNT(*) FROM pending_queue d WHERE d.messageId=m.id AND d.status='Error') AS errorCount, " +
    "(SELECT COUNT(*) FROM pending_queue d WHERE d.messageId=m.id AND d.status IN ('Sent','Delivered','Read')) AS sentCount " +
    "FROM messages m WHERE m.conversationId = :conversation ORDER BY m.receivedAt DESC, m.id DESC"

@Dao
abstract class MessageDao {
    @Query(MESSAGE_SELECTION + " LIMIT 200")
    abstract fun observe(conversation: String): Flow<List<MessageRow>>

    @Query(MESSAGE_SELECTION)
    abstract fun page(conversation: String): PagingSource<Int, MessageRow>

    @Query("SELECT * FROM received_ids WHERE id = :id")
    abstract suspend fun received(id: String): ReceivedMessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun rememberReceived(received: ReceivedMessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun find(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun queue(deliveries: List<OutboxEntity>)

    @Query("UPDATE conversations SET updatedAt = MAX(updatedAt, :timestamp) WHERE id = :conversation")
    abstract suspend fun updateConversation(conversation: String, timestamp: Long)

    @Transaction
    open suspend fun enqueue(message: MessageEntity, deliveries: List<OutboxEntity>): Boolean {
        if (insert(message) == -1L) return false
        queue(deliveries)
        updateConversation(message.conversationId, message.receivedAt)
        return true
    }

    @Query("SELECT * FROM pending_queue WHERE packetId = :packet AND targetId = :target")
    abstract suspend fun delivery(packet: String, target: String): OutboxEntity?

    @Query("SELECT * FROM pending_queue WHERE messageId = :message")
    abstract suspend fun deliveryList(message: String): List<OutboxEntity>

    @Query("UPDATE pending_queue SET status = 'Read', packet = X'' WHERE packetId = :packet AND targetId = :target")
    abstract suspend fun readDelivery(packet: String, target: String)

    @Query("UPDATE pending_queue SET status = 'Pending' WHERE targetId = :target AND messageId IS NULL AND status IN ('Sent','Error')")
    abstract suspend fun retryControl(target: String)

    @Query("DELETE FROM pending_queue WHERE controlScope = :scope")
    abstract suspend fun cancelScope(scope: String)

    @Query("DELETE FROM pending_queue WHERE packetId = :packet")
    abstract suspend fun cancelControl(packet: String)

    @Query("SELECT * FROM pending_queue WHERE targetId = :target AND status = 'Pending' ORDER BY COALESCE((SELECT timestamp FROM messages WHERE id=messageId),0), packetId LIMIT 50")
    abstract suspend fun pending(target: String): List<OutboxEntity>

    @Query("SELECT * FROM pending_queue WHERE messageId = :message")
    abstract fun deliveries(message: String): Flow<List<OutboxEntity>>

    @Query("UPDATE pending_queue SET status = 'Sent', sentAt = :now, attempts = attempts + 1 WHERE packetId = :packet AND targetId = :target AND status = 'Pending'")
    abstract suspend fun sent(packet: String, target: String, now: Long)

    @Query("UPDATE pending_queue SET status = 'Delivered', packet = X'' WHERE packetId = :packet AND targetId = :target AND status IN ('Pending', 'Sent', 'Error')")
    abstract suspend fun acknowledge(packet: String, target: String)

    @Query("UPDATE pending_queue SET status = 'Error' WHERE packetId = :packet AND targetId = :target AND status IN ('Pending','Sent')")
    abstract suspend fun deliveryError(packet: String, target: String)

    @Query("UPDATE pending_queue SET status = 'Error' WHERE status = 'Sent' AND sentAt <= :before")
    abstract suspend fun expire(before: Long)

    @Query("UPDATE pending_queue SET status = 'Pending' WHERE messageId = :message AND status IN ('Error', 'Sent')")
    abstract suspend fun retry(message: String)

    @Query("UPDATE messages SET status = :status, error = :error WHERE id = :message AND status NOT IN ('Delivered', 'Read')")
    abstract suspend fun status(message: String, status: String, error: String? = null)

    @Query("UPDATE messages SET progress = :progress, status = :status, error = :error WHERE id = :message")
    abstract suspend fun fileProgress(message: String, progress: Int, status: String, error: String? = null)

    @Query("DELETE FROM pending_queue WHERE wireKind = 'Read' AND status NOT IN ('Delivered','Read')")
    abstract suspend fun cancelReadReceipts()

    @Query("SELECT id FROM messages WHERE conversationId = :conversation AND incoming = 1 AND unread = 1")
    abstract suspend fun unreadIds(conversation: String): List<String>

    @Query("UPDATE messages SET unread = 0 WHERE conversationId = :conversation")
    abstract suspend fun readConversation(conversation: String)

    @Query("UPDATE messages SET status = 'Read' WHERE id = :message AND incoming = 0")
    abstract suspend fun readReceipt(message: String)

    @Query("DELETE FROM pending_queue WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversation)")
    abstract suspend fun clearQueue(conversation: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversation")
    abstract suspend fun deleteHistory(conversation: String)

    @Query("UPDATE conversations SET updatedAt = 0 WHERE id = :conversation")
    abstract suspend fun resetConversationTime(conversation: String)

    @Transaction
    open suspend fun clearHistory(conversation: String) {
        clearQueue(conversation)
        deleteHistory(conversation)
        resetConversationTime(conversation)
    }

    @Query("DELETE FROM pending_queue WHERE messageId = :message")
    abstract suspend fun cancelDelivery(message: String)

    @Query("DELETE FROM messages WHERE id = :message")
    abstract suspend fun deleteMessage(message: String)

    @Transaction
    open suspend fun deleteLocal(message: String) {
        cancelDelivery(message)
        deleteMessage(message)
    }
}
