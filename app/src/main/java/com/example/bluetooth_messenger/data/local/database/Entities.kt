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
    val peerId: String? = null,
    val epoch: Int = 0,
    val updatedAt: Long = 0,
    val active: Boolean = true,
    val leftAtEpoch: Int = 0,
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
    val epoch: Int = 0,
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
    val controlScope: String? = null,
    val wireKind: String = "Direct",
    val deliveryNotified: Boolean = false,
)

@Entity(tableName = "pairings")
data class PairingEntity(
    @PrimaryKey val peerId: String,
    val pairingId: String,
    val identity: String,
    val name: String,
    val nonceA: ByteArray,
    val nonceB: ByteArray?,
    val initiator: Boolean,
    val localConfirmed: Boolean = false,
    val remoteConfirmed: Boolean = false,
    val completed: Boolean = false,
    val rejected: Boolean = false,
    val expiresAt: Long,
)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "epoch", "memberId"], indices = [Index("groupId")],
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)])
data class GroupMemberEntity(val groupId: String, val epoch: Int, val memberId: String, val identity: String, val joinedEpoch: Int = 1)

@Entity(tableName = "group_keys", primaryKeys = ["groupId", "epoch"], indices = [Index("groupId")],
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)])
data class GroupKeyEntity(val groupId: String, val epoch: Int, val wrappedKey: ByteArray, val rosterHash: ByteArray)

@Entity(tableName = "public_messages")
data class PublicMessageEntity(@PrimaryKey val id: String, val senderId: String, val text: String, val timestamp: Long, val expiresAt: Long, val mine: Boolean = false)

data class MessageRow(
    @androidx.room.Embedded val message: MessageEntity,
    val deliveredCount: Int,
    val targetCount: Int,
    val readCount: Int,
    val errorCount: Int,
    val sentCount: Int,
)

data class ConversationRow(
    @androidx.room.Embedded val conversation: ConversationEntity,
    val lastText: String?,
    val unreadCount: Int,
)

@Entity(tableName = "received_ids")
data class ReceivedMessageEntity(@PrimaryKey val id: String, val senderId: String, val conversationId: String)
