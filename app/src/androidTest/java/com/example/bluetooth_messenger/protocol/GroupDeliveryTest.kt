package com.example.bluetooth_messenger.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluetooth_messenger.data.local.database.*
import com.example.bluetooth_messenger.data.protocol.*
import com.example.bluetooth_messenger.data.security.*
import com.example.bluetooth_messenger.domain.model.newId
import com.example.bluetooth_messenger.domain.transport.*
import com.example.bluetooth_messenger.support.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class GroupDeliveryTest {
    private class Node(private val network: MutableMap<String, Node>) {
        val context = ScopedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val identity = TestIdentity()
        val database = DatabaseSession(context)
        val vault = PinVault(context)
        val clock = TestClock()
        var online = true
        val link = object : PeerTransport {
            override val events = MutableSharedFlow<TransportEvent>()
            override fun stop() { }
            override suspend fun send(peerId: String, packet: ByteArray) {
                val recipient = network[peerId]?.takeIf { it.online } ?: throw PeerUnavailableException("offline")
                recipient.receive(PacketCodec().decode(packet))
            }
        }
        val queue = PacketQueue(database, link, clock)
        val directory = PeerDirectory(database, identity)
        val secure = SecurePackets(identity, clock)
        val direct = DirectMessages(database, identity, directory, secure, queue, TestSettings(), clock)
        val groups = GroupMembership(database, identity, directory, secure, queue, direct, LocalSecrets(vault), clock)
        val messages = GroupMessages(database, identity, directory, secure, queue, direct, groups, clock)
        suspend fun id() = identity.identity().userId
        suspend fun open() {
            val key = if (vault.configured) vault.openWithPin("123456".toCharArray()) else vault.setup("123456".toCharArray())
            try { database.open(key) } finally { key.fill(0) }
        }
        suspend fun trust(other: Node) {
            val peer = other.identity.identity()
            database.use { it.contacts().insert(ContactEntity(peer.userId, "Контакт", peer.signingKey, peer.agreementKey, true)) }
        }
        suspend fun receive(packet: WirePacket) {
            when (packet.kind) {
                PacketKind.GroupRoster -> groups.receiveRoster(packet)
                PacketKind.GroupMessage -> messages.receive(packet)
                PacketKind.GroupReady -> messages.receiveReady(packet)
                PacketKind.GroupLeave -> groups.receiveLeave(packet)
                PacketKind.Ack, PacketKind.Read -> direct.receipt(packet)
                else -> error("Unexpected packet")
            }
        }
        suspend fun flush(vararg peers: Node) { queue.flush(peers.map { it.id() }, messages::canSend) }
    }
    private val network = mutableMapOf<String, Node>()
    private val a = Node(network)
    private val b = Node(network)
    private val c = Node(network)
    private lateinit var group: String
    @Before fun open() = runBlocking<Unit> {
        for (node in listOf(a, b, c)) { node.open(); network[node.id()] = node }
        a.trust(b); a.trust(c); b.trust(a); c.trust(a)
        group = a.groups.create("Поход", listOf(b.id(), c.id()))
        a.flush(b, c)
    }
    @After fun close() = runBlocking<Unit> {
        for (node in listOf(a, b, c)) {
            node.database.close(); node.vault.lock()
            node.context.getSharedPreferences("vault", 0).edit().clear().commit()
            node.context.deleteDatabase("offgrid.db")
        }
    }
    private fun text(value: String) = JSONObject().put("kind", "Text").put("text", value)

    @Test fun deliveryNoticesBatchRecipientsAndRemainDeduplicatedAfterReopening() = runBlocking<Unit> {
        val radio = TestRadio()
        radio.peers.value = listOf(com.example.bluetooth_messenger.domain.model.DiscoveredPeer(b.id(), "test", 0))
        val notices = GroupDeliveryNotices(a.database, a.identity, radio, a.clock)
        c.online = false
        a.messages.send(group, text("one"))
        a.messages.send(group, text("two"))
        notices.deferred(group)
        a.flush(b, c)
        notices.delivered()
        fun systems(rows: List<MessageRow>) = rows.filter { it.message.kind == "System" }
        val first = systems(a.database.use { it.messages().observe(group).first() })
        assertEquals(2, first.size)
        assertTrue(first.any { it.message.text == "Контакт вне зоны. Доставка отложена." })
        assertTrue(first.any { it.message.text == "Контакт получил 2 сообщения." })
        notices.delivered()
        a.database.close(); a.vault.lock(); a.open()
        GroupDeliveryNotices(a.database, a.identity, radio, a.clock).delivered()
        assertEquals(2, systems(a.database.use { it.messages().observe(group).first() }).size)
        c.online = true
        a.flush(c)
        notices.delivered()
        assertEquals(3, systems(a.database.use { it.messages().observe(group).first() }).size)
        assertTrue(systems(b.database.use { it.messages().observe(group).first() }).isEmpty())
        assertTrue(systems(c.database.use { it.messages().observe(group).first() }).isEmpty())
    }

    @Test fun rosterAuthorizesGroupOnlyWithoutCreatingPersonalContacts() = runBlocking<Unit> {
        assertNull(b.database.use { it.contacts().find(c.id()) })
        b.messages.send(group, text("hello group"))
        val id = b.database.use { it.messages().pending(c.id()).single().packetId }
        b.flush(a, c)
        assertEquals("hello group", c.database.use { it.messages().find(id) }!!.text)
        assertEquals(2, b.database.use { it.messages().observe(group).first() }.single().deliveredCount)
        assertNull(b.database.use { it.contacts().find(c.id()) })
    }
    @Test fun offlineMemberDeliverySurvivesSenderRestart() = runBlocking<Unit> {
        c.online = false
        a.messages.send(group, text("queued"))
        val id = a.database.use { it.messages().pending(c.id()).single().packetId }
        a.flush(b)
        assertEquals("Delivered", a.database.use { it.messages().delivery(id, b.id()) }!!.status)
        assertEquals("Pending", a.database.use { it.messages().delivery(id, c.id()) }!!.status)
        a.database.close(); a.vault.lock(); a.open()
        c.online = true
        a.flush(c)
        assertEquals("Delivered", a.database.use { it.messages().delivery(id, c.id()) }!!.status)
        assertEquals("queued", c.database.use { it.messages().find(id) }!!.text)
    }
    @Test fun removedMemberCannotDecryptNewEpochOrInjectOldEpochMessages() = runBlocking<Unit> {
        val oldKey = c.groups.key(group, 1)
        a.groups.remove(group, c.id())
        a.flush(b, c)
        assertFalse(c.database.use { it.contacts().conversation(group) }!!.active)
        assertThrows(Exception::class.java) { runBlocking { c.groups.key(group, 2) } }
        val forged = c.secure.group(group, 1, oldKey, text("after removal").toString().toByteArray(), newId())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { b.messages.receive(forged) } }
        a.messages.send(group, text("new epoch"))
        val packet = a.database.use { PacketCodec().decode(it.messages().pending(b.id()).single().packet) }
        val author = a.identity.identity()
        assertThrows(Exception::class.java) { SecurePackets(c.identity, c.clock).openGroup(packet, author, oldKey) }
        oldKey.fill(0)
    }
    @Test fun trustedContactCannotReplaceAnotherCreatorsRoster() = runBlocking<Unit> {
        c.trust(b)
        val members = JSONArray(listOf(a, b, c).map { node -> JSONObject().put("identity", IdentityCodec().encode(node.identity.identity())).put("joined", 1) })
        val body = JSONObject().put("name", "Подмена").put("owner", b.id()).put("epoch", 2)
            .put("members", members).put("key", Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        val packet = b.secure.direct(PacketKind.GroupRoster, c.identity.identity(), group, body.toString().toByteArray(), epoch = 2)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { c.groups.receiveRoster(packet) } }
        assertEquals(1, c.database.use { it.contacts().conversation(group) }!!.epoch)
    }

    @Test fun replayedLeaveCannotRemoveMemberWhoRejoinedInNewEpoch() = runBlocking<Unit> {
        b.groups.leave(group)
        val leave = b.database.use { db ->
            db.messages().pending(a.id()).map { PacketCodec().decode(it.packet) }
                .single { it.kind == PacketKind.GroupLeave }
        }
        assertEquals(1, leave.epoch)
        a.groups.receiveLeave(leave)
        assertEquals(2, a.database.use { it.contacts().conversation(group) }!!.epoch)

        a.groups.add(group, b.id())
        a.flush(b)
        assertEquals(3, a.database.use { it.contacts().conversation(group) }!!.epoch)
        assertTrue(b.database.use { it.contacts().conversation(group) }!!.active)
        assertTrue(a.database.use { it.protocol().members(group, 3) }.any { it.memberId == b.id() })

        a.groups.receiveLeave(leave)

        val current = a.database.use { it.contacts().conversation(group) }!!
        assertEquals(3, current.epoch)
        assertTrue(current.active)
        assertTrue(a.database.use { it.protocol().members(group, current.epoch) }.any { it.memberId == b.id() })
    }

    @Test fun replayedRemovalCannotCancelNewEpochMessagesAfterRejoining() = runBlocking<Unit> {
        a.groups.remove(group, c.id())
        val removed = a.database.use { db ->
            db.messages().pending(c.id()).map { PacketCodec().decode(it.packet) }
                .single { it.kind == PacketKind.GroupLeave }
        }
        c.groups.receiveLeave(removed)
        assertEquals(2, c.database.use { it.contacts().conversation(group) }!!.epoch)
        assertFalse(c.database.use { it.contacts().conversation(group) }!!.active)

        a.groups.add(group, c.id())
        a.flush(c)
        assertEquals(3, c.database.use { it.contacts().conversation(group) }!!.epoch)
        assertTrue(c.database.use { it.contacts().conversation(group) }!!.active)
        c.messages.send(group, text("queued after rejoining"))
        val queued = c.database.use { db ->
            db.messages().pending(a.id()).single { PacketCodec().decode(it.packet).kind == PacketKind.GroupMessage }
        }
        assertEquals(3, PacketCodec().decode(queued.packet).epoch)
        assertTrue(queued.packet.isNotEmpty())

        c.groups.receiveLeave(removed)

        val current = c.database.use { it.contacts().conversation(group) }!!
        assertEquals(3, current.epoch)
        assertTrue(current.active)
        val afterReplay = c.database.use { it.messages().delivery(queued.packetId, a.id()) }!!
        assertEquals("Pending", afterReplay.status)
        assertArrayEquals(queued.packet, afterReplay.packet)
    }
}
