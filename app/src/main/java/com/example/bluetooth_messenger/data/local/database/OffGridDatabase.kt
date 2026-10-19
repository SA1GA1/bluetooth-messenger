package com.example.bluetooth_messenger.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ContactEntity::class, ConversationEntity::class, MessageEntity::class, OutboxEntity::class],
    version = 1, exportSchema = true)
abstract class OffGridDatabase : RoomDatabase() {
    abstract fun contacts(): ContactDao
    abstract fun messages(): MessageDao
}
