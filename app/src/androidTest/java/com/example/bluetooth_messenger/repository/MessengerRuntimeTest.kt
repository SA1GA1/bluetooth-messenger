package com.example.bluetooth_messenger.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluetooth_messenger.core.coroutines.SessionTasks
import com.example.bluetooth_messenger.data.files.EncryptedFileStore
import com.example.bluetooth_messenger.data.local.database.*
import com.example.bluetooth_messenger.data.protocol.*
import com.example.bluetooth_messenger.data.repository.DefaultMessengerRepository
import com.example.bluetooth_messenger.data.security.*
import com.example.bluetooth_messenger.data.session.AndroidSessionController
import com.example.bluetooth_messenger.domain.model.*
import com.example.bluetooth_messenger.domain.session.SessionState
import com.example.bluetooth_messenger.domain.transport.*
import com.example.bluetooth_messenger.support.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class MessengerRuntimeTest {
    private val network = ConcurrentHashMap<String, Node>()
    private class PrivateContext(base: Context) : ContextWrapper(ScopedContext(base)) {
        private val folder = File(base.cacheDir, "runtime-${UUID.randomUUID()}")
        override fun getApplicationContext() = this
        override fun getFilesDir() = folder.also { check(it.isDirectory || it.mkdirs()) }
        fun removeFiles() { folder.deleteRecursively() }
    }

    private inner class Node {
        val context = PrivateContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val identity = TestIdentity()
        val database = DatabaseSession(context)
        val vault = PinVault(context)
        val settings = TestSettings()
        val clock = TestClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val tasks = SessionTasks(scope)
        var interruptNextSend = false
        var sendStarted: CompletableDeferred<Unit>? = null
        inner class Radio : DiscoveryService, PeerTransport {
            override val peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
            override val events = MutableSharedFlow<TransportEvent>()
            override suspend fun start(userId: String) { network[userId] = this@Node }
            override fun stop() { peers.value = emptyList() }
            override suspend fun send(peerId: String, packet: ByteArray) {
                sendStarted?.let { it.complete(Unit); awaitCancellation() }
                if (interruptNextSend) {
                    interruptNextSend = false
                    throw CancellationException("Simulated GATT reconnect")
                }
                val receiver = network[peerId] ?: throw PeerUnavailableException("offline")
                receiver.radio.events.emit(TransportEvent.Packet(identity.identity().userId, packet))
            }
        }
        val radio: Radio = Radio()
        val controller = AndroidSessionController(context, vault, identity, identity, database, radio, radio,
            LocalDataResetter(context, vault), settings, clock, tasks, scope)
        val queue = PacketQueue(database, radio, clock)
        val directory = PeerDirectory(database, identity)
        val secure = SecurePackets(identity, clock)
        val direct = DirectMessages(database, identity, directory, secure, queue, settings, clock)
        val secrets = LocalSecrets(vault)
        val membership = GroupMembership(database, identity, directory, secure, queue, direct, secrets, clock)
        val groups = GroupMessages(database, identity, directory, secure, queue, direct, membership, clock)
        val files = FileProtocol(database, identity, directory, secure, queue, membership, direct, secrets,
            EncryptedFileStore(context), object : FileLink {
                override suspend fun becomeOwner(userId: String) = error("No native file transfer in this test")
                override suspend fun connectTo(userId: String) = error("No native file transfer in this test")
                override suspend fun stop() = Unit
            }, tasks, clock)
        val repository = DefaultMessengerRepository(database, identity, controller, settings, radio, radio, tasks, scope,
            PairingCoordinator(database, identity, secure, queue, clock), direct, membership, groups, queue,
            PublicBroadcast(database, identity, radio, radio, settings, clock), files,
            GroupDeliveryNotices(database, identity, radio, clock), clock)
        suspend fun id() = identity.identity().userId
        suspend fun ready() {
            withTimeout(5000) { controller.state.first { it == SessionState.Onboarding } }
            withContext(Dispatchers.Main) { controller.setup("123456".toCharArray()) }
            awaitRuntime()
        }
        suspend fun awaitRuntime() {
            withTimeout(5000) {
                repository.ownQr.first { it != null }
                radio.events.subscriptionCount.first { it > 0 }
            }
        }
        suspend fun close() {
            withContext(Dispatchers.Main) { controller.background() }
            withTimeout(5000) { database.database.first { it == null } }
            files.release()
            scope.cancel()
            vault.lock()
            context.getSharedPreferences("vault", 0).edit().clear().commit()
            context.deleteDatabase("offgrid.db")
            context.removeFiles()
        }
    }
    private lateinit var a: Node
    private lateinit var b: Node
    private lateinit var chat: String

    @Before fun open() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { a = Node(); b = Node() }
        a.ready(); b.ready()
        for ((node, other) in listOf(a to b, b to a)) {
            val peer = other.identity.identity()
            node.database.use { it.contacts().insert(ContactEntity(peer.userId, "Контакт", peer.signingKey, peer.agreementKey, true)) }
            node.radio.peers.value = listOf(DiscoveredPeer(peer.userId, "test", 0))
        }
        chat = a.repository.openContact(b.id())
    }
    @After fun close() = runBlocking<Unit> { try { a.close() } finally { b.close() } }

    @Test fun realRepositoryRoutesEncryptedDeliveryAndClearsObservedDataOnLock() = runBlocking<Unit> {
        a.repository.sendText(chat, "Реальная доставка")
        val delivered = withTimeout(8000) { a.repository.messages(chat).first { it.any { row -> row.status == DeliveryStatus.Delivered } } }.single()
        assertEquals("Реальная доставка", b.repository.messages(chat).first { it.isNotEmpty() }.single().text)
        assertEquals(1, delivered.deliveredCount)
        val contactState = a.repository.contacts.stateIn(this, SharingStarted.Eagerly, emptyList())
        withTimeout(5000) { contactState.first { it.isNotEmpty() } }
        withContext(Dispatchers.Main) { a.controller.background() }
        withTimeout(5000) {
            a.database.database.first { it == null }
            a.repository.ownQr.first { it == null }
            contactState.first { it.isEmpty() }
        }
        assertNull(a.repository.ownId.value)
        assertThrows(IllegalStateException::class.java) { runBlocking { a.repository.sendText(chat, "Заблокировано") } }
        coroutineContext.cancelChildren()
    }

    @Test fun cancelledGattWriteDoesNotKillRuntimeOrDurableQueue() = runBlocking<Unit> {
        a.interruptNextSend = true
        a.repository.sendText(chat, "После переподключения")
        withTimeout(8000) { a.repository.messages(chat).first { it.any { row -> row.status == DeliveryStatus.Delivered } } }
        assertEquals(1, b.repository.messages(chat).first { it.isNotEmpty() }.size)
        assertNotNull(a.repository.ownQr.value)
    }

    @Test fun backgroundCancelsInFlightWriteAndUnlockRestoresPendingDelivery() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        a.sendStarted = started
        a.repository.sendText(chat, "Сохранено до фона")
        withTimeout(5000) { started.await() }
        withContext(Dispatchers.Main) { a.controller.background() }
        withTimeout(5000) { a.database.database.first { it == null } }
        assertTrue(b.repository.messages(chat).first().isEmpty())
        a.sendStarted = null
        withContext(Dispatchers.Main) { a.controller.foreground(); a.controller.unlock("123456".toCharArray()) }
        a.awaitRuntime()
        a.radio.peers.value = listOf(DiscoveredPeer(b.id(), "test", 0))
        withTimeout(8000) { a.repository.messages(chat).first { it.any { row -> row.status == DeliveryStatus.Delivered } } }
        assertEquals(1, b.repository.messages(chat).first { it.isNotEmpty() }.size)
    }

    @Test fun unlockRecoversOrphansBeforeAcceptingNewAttachments() = runBlocking<Unit> {
        a.radio.peers.value = emptyList()
        val contents = byteArrayOf(7, 8, 9)
        fun source() = AttachmentSource("test.bin", "application/octet-stream", 3) { contents.inputStream() }
        a.repository.sendAttachment(chat, source(), false)
        val retained = a.database.use { it.attachments().forConversation(chat) }.single().id
        withContext(Dispatchers.Main) { a.controller.background() }
        withTimeout(5000) { a.database.database.first { it == null } }
        val folder = File(a.context.filesDir, "attachments")
        val orphan = File(folder, UUID.randomUUID().toString()).apply { writeBytes(byteArrayOf(1)) }
        val pending = File(folder, ".pending").apply { mkdirs() }
        File(pending, UUID.randomUUID().toString()).writeBytes(byteArrayOf(2))
        withContext(Dispatchers.Main) { a.controller.foreground(); a.controller.unlock("123456".toCharArray()) }
        a.repository.sendAttachment(chat, source(), false)
        a.awaitRuntime()
        assertFalse(orphan.exists())
        assertTrue(pending.listFiles().orEmpty().isEmpty())
        assertTrue(File(folder, retained).isFile)
        val attachments = a.database.use { it.attachments().forConversation(chat) }
        assertEquals(2, attachments.size)
        for (attachment in attachments) {
            val exported = java.io.ByteArrayOutputStream()
            a.repository.exportFile(attachment.id) { exported }
            assertArrayEquals(contents, exported.toByteArray())
        }
    }

    @Test fun groupRuntimeExplainsDeferredDeliveryAndDoesNotRepeatAcknowledgementsOnUnlock() = runBlocking<Unit> {
        val group = a.repository.createGroup("Поход", listOf(b.id()))
        withTimeout(8000) { b.repository.conversations.first { rows -> rows.any { it.id == group } } }
        a.radio.peers.value = emptyList()
        a.repository.sendText(group, "Дождётся связи")
        val deferred = a.repository.messages(group).first().filter { it.kind == ContentType.System }
        assertTrue(deferred.any { it.text == "Контакт вне зоны. Доставка отложена." })
        a.radio.peers.value = listOf(DiscoveredPeer(b.id(), "test", 0))
        withTimeout(10000) {
            a.repository.messages(group).first { rows -> rows.any { it.text == "Контакт получил 1 сообщение." } }
        }
        withContext(Dispatchers.Main) { a.controller.background() }
        withTimeout(5000) { a.database.database.first { it == null } }
        withContext(Dispatchers.Main) { a.controller.foreground(); a.controller.unlock("123456".toCharArray()) }
        a.awaitRuntime()
        assertEquals(1, a.repository.messages(group).first().count { it.text == "Контакт получил 1 сообщение." })
        assertTrue(b.repository.messages(group).first().none { it.kind == ContentType.System })
    }
}
