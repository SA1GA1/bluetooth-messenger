package com.example.bluetooth_messenger.data.local.database

import androidx.room.*

@Entity(tableName = "attachments", indices = [Index("messageId")], foreignKeys = [ForeignKey(
    entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val ownerId: String,
    val wrappedKey: ByteArray,
    val token: ByteArray,
    val hash: ByteArray,
    val plainSize: Long,
    val encryptedSize: Long,
    val complete: Boolean,
)

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE id=:id") suspend fun find(id: String): AttachmentEntity?
    @Query("SELECT * FROM attachments WHERE messageId=:message") suspend fun forMessage(message: String): AttachmentEntity?
    @Query("SELECT a.* FROM attachments a JOIN messages m ON a.messageId=m.id WHERE m.conversationId=:conversation") suspend fun forConversation(conversation: String): List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE complete=0") suspend fun incomplete(): List<AttachmentEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(file: AttachmentEntity)
    @Query("UPDATE attachments SET complete=1 WHERE id=:id") suspend fun complete(id: String)
}
