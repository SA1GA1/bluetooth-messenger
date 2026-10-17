package com.example.bluetooth_messenger.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ContactEntity::class, ConversationEntity::class, MessageEntity::class, OutboxEntity::class,
    PairingEntity::class, GroupMemberEntity::class, GroupKeyEntity::class, PublicMessageEntity::class, ReceivedMessageEntity::class, AttachmentEntity::class],
    version = 1, exportSchema = true)
abstract class OffGridDatabase : RoomDatabase() {
    abstract fun attachments(): AttachmentDao
    abstract fun protocol(): ProtocolDao
    abstract fun contacts(): ContactDao
    abstract fun messages(): MessageDao
}
