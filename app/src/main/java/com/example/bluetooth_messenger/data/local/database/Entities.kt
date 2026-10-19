package com.example.bluetooth_messenger.data.local.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val name: String,
    val signingKey: ByteArray,
    val agreementKey: ByteArray,
    val verified: Boolean = false,
    val lastSeen: Long = 0,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val ownerId: String? = null,
    val epoch: Int = 0,
    val updatedAt: Long = 0,
    val active: Boolean = true,
)

@Entity(tableName = "messages", indices = [Index(value = ["conversationId", "receivedAt"])],
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val kind: String,
    val text: String,
    val timestamp: Long,
    val receivedAt: Long,
    val incoming: Boolean,
    val status: String,
    val unread: Boolean = false,
    val fileId: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val mimeType: String? = null,
    val progress: Int = 0,
    val error: String? = null,
)

@Entity(tableName = "pending_queue", primaryKeys = ["packetId", "targetId"], indices = [Index("messageId")])
data class OutboxEntity(
    val packetId: String,
    val targetId: String,
    val messageId: String?,
    val packet: ByteArray,
    val status: String = "Pending",
    val sentAt: Long = 0,
    val attempts: Int = 0,
)
