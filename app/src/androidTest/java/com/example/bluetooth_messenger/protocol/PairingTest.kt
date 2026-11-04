package com.example.bluetooth_messenger.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluetooth_messenger.data.local.database.DatabaseSession
import com.example.bluetooth_messenger.data.protocol.*
import com.example.bluetooth_messenger.support.*
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingTest {
    private class Node {
        val context = ScopedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val identity = TestIdentity()
        val clock = TestClock()
        val database = DatabaseSession(context)
        val secure = SecurePackets(identity, clock)
        val coordinator =
            PairingCoordinator(
                database,
                identity,
                secure,
                PacketQueue(database, TestRadio(), clock),
                clock,
            )

        suspend fun id() = identity.identity().userId

        suspend fun qr() = IdentityCodec().encode(identity.identity())
    }

    private val a = Node()
    private val b = Node()

    @Before
    fun open() =
        runBlocking<Unit> {
            a.database.open(ByteArray(32) { 1 })
            b.database.open(ByteArray(32) { 2 })
        }

    @After
    fun close() =
        runBlocking<Unit> {
            for (node in listOf(a, b)) {
                node.database.close()
                node.context.deleteDatabase("offgrid.db")
            }
        }

    private suspend fun deliver(from: Node, to: Node, kind: PacketKind): WirePacket {
        val packet =
            from.database.use { db ->
                db.messages()
                    .pending(to.id())
                    .map { PacketCodec().decode(it.packet) }
                    .first { it.kind == kind }
            }
        to.coordinator.receive(packet)
        from.database.use { it.messages().sent(packet.id, to.id(), from.clock.wallMillis()) }
        return packet
    }

    private suspend fun exchangeChallenges() {
        a.coordinator.add(b.qr(), "Борис")
        deliver(a, b, PacketKind.PairRequest)
        deliver(b, a, PacketKind.PairAccept)
    }

    @Test
    fun oneScanRequiresBothConfirmationsAndKeepsNamesLocal() =
        runBlocking<Unit> {
            exchangeChallenges()
            val first = a.coordinator.prompts().first().single()
            val second = b.coordinator.prompts().first().single()
            assertEquals(first.code, second.code)
            assertEquals(6, first.code!!.length)
            assertEquals("", second.name)
            assertNull(a.database.use { it.contacts().find(b.id()) })
            assertNull(b.database.use { it.contacts().find(a.id()) })
            a.coordinator.confirm(b.id(), "Борис")
            deliver(a, b, PacketKind.PairConfirm)
            assertNull(b.database.use { it.contacts().find(a.id()) })
            b.coordinator.confirm(a.id(), "Алексей")
            val confirmation = deliver(b, a, PacketKind.PairConfirm)
            assertEquals("Борис", a.database.use { it.contacts().find(b.id()) }!!.name)
            assertEquals("Алексей", b.database.use { it.contacts().find(a.id()) }!!.name)
            assertTrue(a.database.use { it.contacts().find(b.id()) }!!.verified)
            a.coordinator.receive(confirmation)
            val conversation =
                com.example.bluetooth_messenger.domain.model.directConversationId(a.id(), b.id())
            assertEquals(1, a.database.use { it.messages().observe(conversation).first() }.size)
        }

    @Test
    fun rejectionHidesRequestAndReplayCannotCreateContact() =
        runBlocking<Unit> {
            a.coordinator.add(b.qr(), "Борис")
            val request = deliver(a, b, PacketKind.PairRequest)
            b.coordinator.reject(a.id())
            b.coordinator.receive(request)
            assertTrue(b.coordinator.prompts().first().isEmpty())
            assertNull(b.database.use { it.contacts().find(a.id()) })
        }

    @Test
    fun mismatchedChallengeCannotCompleteExchange() =
        runBlocking<Unit> {
            a.coordinator.add(b.qr(), "Борис")
            deliver(a, b, PacketKind.PairRequest)
            val row = b.database.use { it.protocol().pairing(a.id()) }!!
            val payload =
                JSONObject()
                    .put("identity", b.qr())
                    .put("a", Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
                    .put("b", Base64.getEncoder().encodeToString(row.nonceB))
            val forged =
                b.secure.direct(
                    PacketKind.PairAccept,
                    a.identity.identity(),
                    row.pairingId,
                    payload.toString().toByteArray(),
                )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { a.coordinator.receive(forged) }
            }
            assertNull(a.database.use { it.contacts().find(b.id()) })
        }
}
