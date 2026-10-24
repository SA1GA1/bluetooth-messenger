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
}
