package com.example.bluetooth_messenger.protocol

import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluetooth_messenger.data.local.database.DatabaseSession
import com.example.bluetooth_messenger.data.protocol.*
import com.example.bluetooth_messenger.domain.model.newId
import com.example.bluetooth_messenger.support.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PublicBroadcastTest {
    private val context = ScopedContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val database = DatabaseSession(context)
    private val identity = TestIdentity()
    private val clock = TestClock()
    private val settings = TestSettings()
    private val radio = TestRadio()
    private val public = PublicBroadcast(database, identity, radio, radio, settings, clock)
    private val zero = "00000000-0000-0000-0000-000000000000"
    @Before fun open() = runBlocking<Unit> { database.open(ByteArray(32) { 7 }) }
    @After fun close() = runBlocking<Unit> { database.close(); context.deleteDatabase("offgrid.db") }
    private fun packet(sender: String = newId(), text: String = "hello") = WirePacket(PacketKind.Public,
        newId(), sender, zero, zero, timestamp = Long.MAX_VALUE, body = text.toByteArray())

    @Test fun disabledPublicModeDoesNotSaveUnsolicitedMessages() = runBlocking<Unit> {
        public.receive(packet())
        assertTrue(database.use { it.protocol().publicMessages(clock.wallMillis()).first() }.isEmpty())
    }
    @Test fun forgedOwnIdIsNotShownAsOurMessageAndCannotExtendExpiry() = runBlocking<Unit> {
        settings.setPublicMode(true)
        public.receive(packet(identity.identity().userId))
        val row = database.use { it.protocol().publicMessages(clock.wallMillis()).first() }.single()
        assertFalse(row.mine)
        assertEquals(clock.wallMillis(), row.timestamp)
        clock.elapsed += 86_400_000
        assertTrue(database.use { it.protocol().publicMessages(clock.wallMillis()).first() }.isEmpty())
    }
    @Test fun oversizePublicInputIsRejectedBeforeStorage() = runBlocking<Unit> {
        settings.setPublicMode(true)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { public.receive(packet(text = "я".repeat(1025))) } }
        assertTrue(database.use { it.protocol().publicMessages(clock.wallMillis()).first() }.isEmpty())
    }
}
