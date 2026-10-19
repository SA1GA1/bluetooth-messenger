package com.example.bluetooth_messenger.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluetooth_messenger.data.local.database.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MessageStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "storage-test-${UUID.randomUUID()}.db"
    private lateinit var database: OffGridDatabase
    private val key = ByteArray(32) { 42 }
    private val conversation = "conversation"
    private fun message() = MessageEntity("message", conversation, "sender", "Text", "secret marker", 100, 100,
        incoming = false, status = "Pending")
    private fun delivery(target: String = "recipient") = OutboxEntity("packet", target, "message", byteArrayOf(1, 2))

    @Before fun open() {
        System.loadLibrary("sqlcipher")
        database = Room.databaseBuilder(context, OffGridDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf())).build()
        runBlocking { database.contacts().insertConversation(ConversationEntity(conversation, "Direct", "Contact")) }
    }
    @After fun close() { database.close(); context.deleteDatabase(name) }

    @Test fun duplicateDoesNotOverwriteHistoryOrCreateAnotherDelivery() = runBlocking<Unit> {
        val dao = database.messages()
        assertTrue(dao.enqueue(message(), listOf(delivery())))
        assertFalse(dao.enqueue(message().copy(text = "replaced"), listOf(delivery("other"))))
        assertEquals("secret marker", dao.find("message")!!.text)
        assertEquals(1, dao.pending("recipient").size)
        assertTrue(dao.pending("other").isEmpty())
    }
    @Test fun clearHistoryCancelsQueueAndResetsConversationTime() = runBlocking<Unit> {
        val dao = database.messages()
        dao.enqueue(message(), listOf(delivery()))
        dao.clearHistory(conversation)
        assertNull(dao.find("message"))
        assertTrue(dao.pending("recipient").isEmpty())
        assertEquals(0L, database.contacts().conversation(conversation)!!.updatedAt)
    }
    @Test fun onlyMatchingRecipientCanAcknowledgeAndReleasePacket() = runBlocking<Unit> {
        val dao = database.messages()
        dao.enqueue(message(), listOf(delivery()))
        dao.acknowledge("packet", "other")
        assertEquals(1, dao.pending("recipient").size)
        dao.acknowledge("packet", "recipient")
        val result = dao.deliveries("message").first().single()
        assertEquals("Delivered", result.status)
        assertTrue(result.packet.isEmpty())
    }
    @Test fun timeoutRequiresManualRetryAndSurvivesDatabaseReopen() = runBlocking<Unit> {
        val dao = database.messages()
        dao.enqueue(message(), listOf(delivery()))
        dao.sent("packet", "recipient", 100)
        dao.expire(101)
        assertTrue(dao.pending("recipient").isEmpty())
        database.close()
        database = Room.databaseBuilder(context, OffGridDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf())).build()
        database.messages().retry("message")
        assertEquals(1, database.messages().pending("recipient").size)
    }
    @Test fun databaseHeaderIsEncryptedAndWrongPasswordCannotOpenHistory() = runBlocking<Unit> {
        database.messages().enqueue(message(), listOf(delivery()))
        database.close()
        val header = context.getDatabasePath(name).inputStream().use { input -> ByteArray(16).also { input.read(it) } }
        assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
        val wrong = Room.databaseBuilder(context, OffGridDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(ByteArray(32) { 7 })).build()
        try { assertThrows(Exception::class.java) { runBlocking { wrong.messages().find("message") } } }
        finally { wrong.close() }
    }
}
