package com.example.bluetooth_messenger.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluetooth_messenger.data.local.database.*
import com.example.bluetooth_messenger.data.protocol.*
import com.example.bluetooth_messenger.domain.model.directConversationId
import com.example.bluetooth_messenger.domain.model.newId
import com.example.bluetooth_messenger.domain.transport.*
import com.example.bluetooth_messenger.support.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectDeliveryTest {
    private class Link : PeerTransport {
        override val events = MutableSharedFlow<TransportEvent>()
        var onSend: (suspend (ByteArray) -> Unit)? = null
        val packets = mutableListOf<ByteArray>()

        override suspend fun send(peerId: String, packet: ByteArray) {
            packets.add(packet)
            onSend?.invoke(packet)
        }

        override fun stop() {}
    }

    private class Node(val keyByte: Byte) {
        val context = ScopedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val identity = TestIdentity()
        val database = DatabaseSession(context)
        val clock = TestClock()
        val link = Link()
        val settings = TestSettings()
        val queue = PacketQueue(database, link, clock)
        val protocol =
            DirectMessages(
                database,
                identity,
                PeerDirectory(database, identity),
                SecurePackets(identity, clock),
                queue,
                settings,
                clock,
            )

        suspend fun id() = identity.identity().userId

        suspend fun open() = database.open(ByteArray(32) { keyByte })
    }

    private val a = Node(1)
    private val b = Node(2)
    private lateinit var conversation: String

    @Before
    fun open() =
        runBlocking<Unit> {
            a.open()
            b.open()
            for ((node, peer, name) in listOf(Triple(a, b, "Борис"), Triple(b, a, "Алексей"))) {
                val identity = peer.identity.identity()
                node.database.use {
                    it.contacts()
                        .insert(
                            ContactEntity(
                                identity.userId,
                                name,
                                identity.signingKey,
                                identity.agreementKey,
                                true,
                            )
                        )
                }
                node.protocol.openContact(identity.userId)
            }
            conversation = directConversationId(a.id(), b.id())
        }

    @After
    fun close() =
        runBlocking<Unit> {
            for (node in listOf(a, b)) {
                node.database.close()
                node.context.deleteDatabase("offgrid.db")
            }
        }

    private suspend fun send(text: String = "hello"): WirePacket {
        val before =
            a.database.use {
                it.messages().pending(b.id()).map { delivery -> delivery.packetId }.toSet()
            }
        a.protocol.send(conversation, JSONObject().put("kind", "Text").put("text", text))
        return a.database.use {
            PacketCodec()
                .decode(
                    it.messages()
                        .pending(b.id())
                        .first { delivery -> delivery.packetId !in before }
                        .packet
                )
        }
    }

    @Test
    fun transportFailureRequiresExplicitRetry() =
        runBlocking<Unit> {
            val packet = send()
            a.link.onSend = { throw IllegalStateException("test connection lost") }
            a.queue.flush(listOf(b.id()))
            assertEquals(
                "Error",
                a.database.use { it.messages().delivery(packet.id, b.id()) }!!.status,
            )
            assertTrue(a.database.use { it.messages().pending(b.id()) }.isEmpty())
        }

    @Test
    fun deliveryTimeoutIgnoresWallClockCorrections() =
        runBlocking<Unit> {
            val packet = send()
            a.queue.flush(listOf(b.id()))
            a.clock.elapsed = 119_999
            a.clock.wallShift = 365L * 24 * 60 * 60 * 1000
            a.database.use { it.messages().expire(a.clock.elapsedMillis() - 120_000) }
            assertEquals(
                "Sent",
                a.database.use { it.messages().delivery(packet.id, b.id()) }!!.status,
            )
            a.clock.elapsed = 120_001
            a.clock.wallShift = -365L * 24 * 60 * 60 * 1000
            a.database.use { it.messages().expire(a.clock.elapsedMillis() - 120_000) }
            assertEquals(
                "Error",
                a.database.use { it.messages().delivery(packet.id, b.id()) }!!.status,
            )
        }

    @Test
    fun earlyAckCannotRegressDeliveredBackToSent() =
        runBlocking<Unit> {
            val packet = send()
            a.link.onSend = { b.protocol.receive(PacketCodec().decode(it)) }
            b.link.onSend = { a.protocol.receipt(PacketCodec().decode(it)) }
            a.queue.flush(listOf(b.id()))
            assertEquals(
                "Delivered",
                a.database.use { it.messages().delivery(packet.id, b.id()) }!!.status,
            )
            val rows = a.database.use { it.messages().observe(conversation).first() }
            assertEquals(1, rows.single().deliveredCount)
            assertEquals("hello", b.database.use { it.messages().find(packet.id) }!!.text)
        }

    @Test
    fun lostAckAndSenderRestartDoNotDuplicateMessage() =
        runBlocking<Unit> {
            val packet = send()
            a.database.close()
            a.open()
            a.queue.flush(listOf(b.id()))
            b.protocol.receive(packet)
            a.clock.elapsed = 31_000
            a.database.use {
                it.messages().expire(a.clock.wallMillis() - 30_000)
                it.messages().retry(packet.id)
            }
            a.queue.flush(listOf(b.id()))
            b.protocol.receive(PacketCodec().decode(a.link.packets.last()))
            a.protocol.receipt(PacketCodec().decode(b.link.packets.last()))
            assertEquals(1, b.database.use { it.messages().observe(conversation).first() }.size)
            assertEquals(
                "Delivered",
                a.database.use { it.messages().delivery(packet.id, b.id()) }!!.status,
            )
        }

    @Test
    fun replayAfterLocalDeletionDoesNotRecreateHistoryOrChat() =
        runBlocking<Unit> {
            val packet = send()
            b.protocol.receive(packet)
            b.database.use {
                it.messages().clearHistory(conversation)
                it.contacts().deleteConversation(conversation)
            }
            b.protocol.receive(packet)
            assertNull(b.database.use { it.contacts().conversation(conversation) })
            assertNull(b.database.use { it.messages().find(packet.id) })
            val fresh = send("new message")
            b.protocol.receive(fresh)
            assertEquals("new message", b.database.use { it.messages().find(fresh.id) }!!.text)
        }

    @Test
    fun readReceiptsAreOptInAndBoundToOriginalRecipient() =
        runBlocking<Unit> {
            val first = send()
            b.protocol.receive(first)
            b.protocol.markRead(conversation)
            assertTrue(b.database.use { it.messages().pending(a.id()) }.isEmpty())
            b.settings.setReadReceipts(true)
            val second = send("second")
            b.protocol.receive(second)
            b.protocol.markRead(conversation)
            val receipt =
                b.database.use {
                    PacketCodec().decode(it.messages().pending(a.id()).single().packet)
                }
            a.protocol.receipt(receipt)
            assertEquals(
                "Read",
                a.database.use { it.messages().delivery(second.id, b.id()) }!!.status,
            )
            assertFalse(b.database.use { it.messages().find(second.id) }!!.unread)
        }

    @Test
    fun clearHistoryRemovesQueuedReadReceiptsAndPreservesOtherGroupControls() =
        runBlocking<Unit> {
            b.settings.setReadReceipts(true)
            val incoming = send("read before local history deletion")
            b.protocol.receive(incoming)
            b.protocol.markRead(conversation)
            val read =
                b.database.use { db ->
                    db.messages().pending(a.id()).single {
                        PacketCodec().decode(it.packet).kind == PacketKind.Read
                    }
                }
            assertEquals(conversation, PacketCodec().decode(read.packet).conversation)
            val group = newId()
            val ready =
                SecurePackets(b.identity, b.clock)
                    .direct(
                        PacketKind.GroupReady,
                        a.identity.identity(),
                        group,
                        JSONObject().put("ref", newId()).toString().toByteArray(),
                        epoch = 1,
                    )
            b.queue.add(ready)
            val beforeGroup = b.database.use { it.messages().delivery(ready.id, a.id()) }!!
            assertTrue(beforeGroup.packet.isNotEmpty())

            b.database.use { it.messages().clearHistory(conversation) }

            assertNull(b.database.use { it.messages().find(incoming.id) })
            assertNull(
                "Queued Read controls for deleted history must be removed",
                b.database.use { it.messages().delivery(read.packetId, a.id()) },
            )
            val afterGroup = b.database.use { it.messages().delivery(ready.id, a.id()) }!!
            assertEquals("Pending", afterGroup.status)
            assertArrayEquals(beforeGroup.packet, afterGroup.packet)
        }
}
