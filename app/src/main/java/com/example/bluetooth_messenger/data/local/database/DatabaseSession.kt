package com.example.bluetooth_messenger.data.local.database

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseSession @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private val mutableDatabase = MutableStateFlow<OffGridDatabase?>(null)
    val database = mutableDatabase.asStateFlow()

    suspend fun open(secret: ByteArray) = withContext(Dispatchers.IO) {
        require(secret.size == 32)
        mutex.withLock {
            check(mutableDatabase.value == null) { "База уже открыта" }
            System.loadLibrary("sqlcipher")
            val database = Room.databaseBuilder(context, OffGridDatabase::class.java, "offgrid.db")
                .openHelperFactory(SupportOpenHelperFactory(secret.copyOf())).build()
            try {
                database.openHelper.writableDatabase
                mutableDatabase.value = database
            } catch (failure: Exception) { database.close(); throw failure }
        }
    }

    suspend fun <T> use(block: suspend (OffGridDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(checkNotNull(mutableDatabase.value) { "Приложение заблокировано" }) }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            val database = mutableDatabase.value
            mutableDatabase.value = null
            database?.close()
        }
    }
}
