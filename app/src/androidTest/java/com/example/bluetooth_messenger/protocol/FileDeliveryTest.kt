package com.example.bluetooth_messenger.protocol

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluetooth_messenger.core.coroutines.SessionTasks
import com.example.bluetooth_messenger.data.files.EncryptedFileStore
import com.example.bluetooth_messenger.data.local.database.*
import com.example.bluetooth_messenger.data.protocol.*
import com.example.bluetooth_messenger.data.security.*
import com.example.bluetooth_messenger.domain.model.*
import com.example.bluetooth_messenger.domain.transport.*
import com.example.bluetooth_messenger.support.*
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Protocol/database tests with fake native links; socket tests use a local interface, never Wi-Fi
 * Direct APIs.
 */
@RunWith(AndroidJUnit4::class)
class FileDeliveryTest {
    private class FileContext(base: Context) : ContextWrapper(ScopedContext(base)) {
        private val folder = File(base.cacheDir, "file-delivery-${UUID.randomUUID()}")

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = folder.also { check(it.isDirectory || it.mkdirs()) }

        fun removeFiles() {
            folder.deleteRecursively()
        }
    }

    private class Node {
        val context = FileContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val identity = TestIdentity()
        val database = DatabaseSession(context)
        val vault = PinVault(context)
        val secrets = LocalSecrets(vault)
        val clock = TestClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tasks = SessionTasks(scope)
        val sent: MutableList<WirePacket> = Collections.synchronizedList(mutableListOf())
        var onSend: (suspend (WirePacket) -> Unit)? = null
        var linkAddress: InetAddress? = null
        val transport =
            object : PeerTransport {
                override val events = MutableSharedFlow<TransportEvent>()

                override fun stop() {}

                override suspend fun send(peerId: String, packet: ByteArray) {
                    val decoded = PacketCodec().decode(packet)
                    sent += decoded
                    onSend?.invoke(decoded)
                }
            }
        val connects = AtomicInteger()
        val stops = AtomicInteger()
        var onStop: (suspend () -> Unit)? = null
        val wifi =
            object : FileLink {
                override suspend fun becomeOwner(userId: String): InetAddress =
                    linkAddress ?: error("Unexpected owner request")

                override suspend fun connectTo(userId: String): InetAddress {
                    connects.incrementAndGet()
                    linkAddress?.let {
                        return it
                    }
                    throw IOException("Simulated interrupted Wi-Fi Direct connection")
                }

                override suspend fun stop() {
                    stops.incrementAndGet()
                    onStop?.invoke()
                }
            }
        val queue = PacketQueue(database, transport, clock)
        val directory = PeerDirectory(database, identity)
        val secure = SecurePackets(identity, clock)
        val settings = TestSettings()
        val direct = DirectMessages(database, identity, directory, secure, queue, settings, clock)
        val groups =
            GroupMembership(database, identity, directory, secure, queue, direct, secrets, clock)
        val store = EncryptedFileStore(context)
        val files =
            FileProtocol(
                database,
                identity,
                directory,
                secure,
                queue,
                groups,
                direct,
                secrets,
                store,
                wifi,
                tasks,
                clock,
            )

        suspend fun id() = identity.identity().userId

        suspend fun open() {
            val key = vault.setup("123456".toCharArray())
            try {
                database.open(key)
            } finally {
                key.fill(0)
            }
            tasks.start()
        }

        suspend fun trust(other: Node) {
            val peer = other.identity.identity()
            database.use {
                it.contacts()
                    .insert(
                        ContactEntity(
                            peer.userId,
                            "Контакт",
                            peer.signingKey,
                            peer.agreementKey,
                            true,
                        )
                    )
            }
        }

        suspend fun close() {
            try {
                files.release()
                tasks.stop()
            } finally {
                scope.cancel()
                database.close()
                vault.lock()
                context.getSharedPreferences("vault", 0).edit().clear().commit()
                context.deleteDatabase("offgrid.db")
                context.removeFiles()
            }
        }
    }

    private val a = Node()
    private val b = Node()
    private lateinit var chat: String
    private val contents = "private attachment payload".toByteArray()
    private val name = "secret-itinerary.txt"

    private fun source() =
        AttachmentSource(name, "text/plain", contents.size.toLong()) { contents.inputStream() }

    private fun encoded(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Before
    fun open() =
        runBlocking<Unit> {
            a.open()
            b.open()
            a.trust(b)
            b.trust(a)
            chat = directConversationId(a.id(), b.id())
            a.database.use {
                it.contacts()
                    .insertConversation(
                        ConversationEntity(chat, "Direct", "Контакт", peerId = b.id())
                    )
            }
        }

    @After
    fun close() =
        runBlocking<Unit> {
            try {
                a.close()
            } finally {
                b.close()
            }
        }

    private suspend fun outgoing(): WirePacket {
        a.files.send(chat, source(), false)
        return a.database.use {
            PacketCodec().decode(it.messages().pending(b.id()).single().packet)
        }
    }

    private suspend fun awaitError(packet: WirePacket) {
        withTimeout(5_000) {
            while (b.database.use { it.messages().find(packet.id) }?.status != "Error") delay(10)
            b.files.busy.first { !it }
        }
    }

    private suspend fun assertReceiverUnchanged(packet: WirePacket, file: String? = null) {
        b.database.use {
            assertNull(it.contacts().conversation(chat))
            assertNull(it.messages().find(packet.id))
            assertNull(it.messages().received(packet.id))
            assertTrue(it.attachments().incomplete().isEmpty())
            if (file != null) assertNull(it.attachments().find(file))
        }
        assertEquals(0, b.connects.get())
        assertTrue(b.sent.isEmpty())
    }

    @Test
    fun importQueuesEncryptedOfferAndCiphertextWhileRetainingExportableSource() =
        runBlocking<Unit> {
            val packet = outgoing()
            assertEquals(PacketKind.FileOffer, packet.kind)
            assertFalse(packet.body.toString(Charsets.ISO_8859_1).contains(name))
            val wrapper =
                JSONObject(b.secure.open(packet, a.identity.identity()).toString(Charsets.UTF_8))
            assertFalse(wrapper.getBoolean("sealed"))
            val metadata =
                JSONObject(
                    String(Base64.getDecoder().decode(wrapper.getString("data")), Charsets.UTF_8)
                )
            assertEquals(name, metadata.getString("name"))
            assertEquals(contents.size.toLong(), metadata.getLong("size"))
            val file = a.database.use { it.attachments().forMessage(packet.id) }!!
            assertTrue(file.complete)
            assertEquals(
                "Pending",
                a.database.use { it.messages().delivery(packet.id, b.id()) }!!.status,
            )
            assertFalse(
                a.store
                    .input(file.id)
                    .use { it.readBytes() }
                    .toString(Charsets.ISO_8859_1)
                    .contains(String(contents))
            )
            val key = a.secrets.open(file.wrappedKey, "file:${file.id}")
            try {
                assertArrayEquals(contents, a.store.bytes(file.id, key))
            } finally {
                key.fill(0)
            }
            assertEquals(0, a.connects.get())
        }

    @Test
    fun malformedSignedOfferDoesNotCreateChatMessageOrAttachment() =
        runBlocking<Unit> {
            val malformed =
                JSONObject().put("file", "not-a-uuid").put("sealed", false).put("data", "")
            val packet =
                a.secure.direct(
                    PacketKind.FileOffer,
                    b.identity.identity(),
                    chat,
                    malformed.toString().toByteArray(),
                )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { b.files.receiveOffer(packet) }
            }
            assertReceiverUnchanged(packet)
        }

    @Test
    fun forgedOfferDoesNotCreateChatMessageOrAttachment() =
        runBlocking<Unit> {
            val packet = outgoing()
            val file = a.database.use { it.attachments().forMessage(packet.id) }!!.id
            val forged =
                packet.copy(
                    body =
                        packet.body.copyOf().also {
                            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                        }
                )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { b.files.receiveOffer(forged) }
            }
            assertReceiverUnchanged(packet, file)
        }

    @Test
    fun interruptedReceivePersistsErrorWithoutFileAckOrPartialFile() =
        runBlocking<Unit> {
            val packet = outgoing()
            b.files.receiveOffer(packet)
            awaitError(packet)
            val row = b.database.use { it.messages().find(packet.id) }!!
            val file = b.database.use { it.attachments().forMessage(packet.id) }!!
            assertTrue(row.incoming)
            assertEquals("Error", row.status)
            assertNotNull(row.error)
            assertFalse(file.complete)
            assertFalse(b.store.exists(file.id))
            assertNotNull(b.database.use { it.messages().received(packet.id) })
            assertEquals(1, b.connects.get())
            assertTrue(b.stops.get() > 0)
            assertFalse(b.sent.any { it.kind == PacketKind.FileAck })
        }

    /** Seed a completed import with authenticated matching metadata, avoiding a real socket. */
    private suspend fun completeIncoming(): Pair<WirePacket, String> {
        val file = newId()
        val message = newId()
        val saved = b.store.`import`(file, source(), Limits.FILE_BYTES.toLong())
        try {
            val token = ByteArray(32) { (it + 1).toByte() }
            val metadata =
                JSONObject()
                    .put("file", file)
                    .put("name", name)
                    .put("mime", "text/plain")
                    .put("photo", false)
                    .put("size", saved.plainSize)
                    .put("encryptedSize", saved.encryptedSize)
                    .put("key", encoded(saved.key))
                    .put("token", encoded(token))
                    .put("hash", encoded(saved.hash))
            val wrapper =
                JSONObject()
                    .put("file", file)
                    .put("sealed", false)
                    .put("data", encoded(metadata.toString().toByteArray()))
            val packet =
                a.secure.direct(
                    PacketKind.FileOffer,
                    b.identity.identity(),
                    chat,
                    wrapper.toString().toByteArray(),
                    message,
                )
            b.database.use { db ->
                db.contacts()
                    .insertConversation(
                        ConversationEntity(chat, "Direct", "Контакт", peerId = a.id())
                    )
                db.messages()
                    .insert(
                        MessageEntity(
                            message,
                            chat,
                            a.id(),
                            "File",
                            name,
                            packet.timestamp,
                            b.clock.wallMillis(),
                            true,
                            "Delivered",
                            fileId = file,
                            fileName = name,
                            fileSize = saved.plainSize,
                            mimeType = "text/plain",
                            progress = 100,
                        )
                    )
                db.attachments()
                    .insert(
                        AttachmentEntity(
                            file,
                            message,
                            a.id(),
                            b.secrets.wrap(saved.key, "file:$file"),
                            token,
                            saved.hash,
                            saved.plainSize,
                            saved.encryptedSize,
                            true,
                        )
                    )
                db.messages().rememberReceived(ReceivedMessageEntity(message, a.id(), chat))
            }
            return packet to file
        } finally {
            saved.key.fill(0)
        }
    }

    @Test
    fun duplicateCompleteOfferOnlyAcknowledgesWithoutOverwritingMessageOrCiphertext() =
        runBlocking<Unit> {
            val (packet, file) = completeIncoming()
            val beforeMessage = b.database.use { it.messages().find(packet.id) }!!
            val beforeFile = b.database.use { it.attachments().find(file) }!!
            val beforeBytes = b.store.input(file).use { it.readBytes() }
            repeat(2) { b.files.receiveOffer(packet) }
            assertEquals(beforeMessage, b.database.use { it.messages().find(packet.id) })
            val afterFile = b.database.use { it.attachments().find(file) }!!
            assertArrayEquals(beforeFile.wrappedKey, afterFile.wrappedKey)
            assertArrayEquals(beforeFile.token, afterFile.token)
            assertArrayEquals(beforeFile.hash, afterFile.hash)
            assertTrue(afterFile.complete)
            assertArrayEquals(beforeBytes, b.store.input(file).use { it.readBytes() })
            val key = b.secrets.open(afterFile.wrappedKey, "file:$file")
            try {
                assertArrayEquals(contents, b.store.bytes(file, key))
            } finally {
                key.fill(0)
            }
            assertEquals(0, b.connects.get())
            assertEquals(2, b.sent.size)
            for (ack in b.sent) {
                assertEquals(PacketKind.FileAck, ack.kind)
                assertEquals(
                    packet.id,
                    JSONObject(a.secure.open(ack, b.identity.identity()).toString(Charsets.UTF_8))
                        .getString("ref"),
                )
            }
        }

    @Test
    fun replayAfterLocalConversationDeletionCannotRecreateChatOrFile() =
        runBlocking<Unit> {
            val (packet, file) = completeIncoming()
            b.store.delete(file)
            b.database.use { it.contacts().deleteConversation(chat) }
            assertNotNull(b.database.use { it.messages().received(packet.id) })
            b.files.receiveOffer(packet)
            b.database.use {
                assertNull(it.contacts().conversation(chat))
                assertNull(it.messages().find(packet.id))
                assertNull(it.attachments().find(file))
                assertNotNull(it.messages().received(packet.id))
            }
            assertFalse(b.store.exists(file))
            assertEquals(0, b.connects.get())
            assertEquals(PacketKind.FileAck, b.sent.single().kind)
        }

    @Test
    fun incompleteIncomingFileCannotBeMarkedReadOrEmitReadReceipt() =
        runBlocking<Unit> {
            b.settings.setReadReceipts(true)
            val packet = outgoing()
            b.files.receiveOffer(packet)
            awaitError(packet)
            assertTrue(b.database.use { it.messages().find(packet.id) }!!.unread)
            assertFalse(b.database.use { it.attachments().forMessage(packet.id) }!!.complete)

            b.direct.markRead(chat)

            val controls =
                b.database
                    .use { it.messages().pending(a.id()) }
                    .map { PacketCodec().decode(it.packet) }
            assertFalse(
                "An incomplete attachment must not emit a Read control",
                controls.any { it.kind == PacketKind.Read },
            )
            assertTrue(
                "An incomplete attachment must remain unread",
                b.database.use { it.messages().find(packet.id) }!!.unread,
            )
            assertEquals("Error", b.database.use { it.messages().find(packet.id) }!!.status)
        }

    private fun localSocketAddress(): InetAddress {
        val address =
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull {
                    it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress
                }
        assumeTrue(
            "Socket protocol test requires a local non-loopback IPv4 interface",
            address != null,
        )
        return checkNotNull(address)
    }

    private suspend fun receiverTerminal(packet: WirePacket): MessageEntity =
        withTimeout(10_000) {
            while (true) {
                val row = b.database.use { it.messages().find(packet.id) }
                if (row != null && row.status in setOf("Delivered", "Error")) {
                    b.files.busy.first { !it }
                    return@withTimeout row
                }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") error("No terminal file state")
        }

    private suspend fun enableLocalSockets(transform: suspend (WirePacket) -> WirePacket = { it }) {
        val address = localSocketAddress()
        a.linkAddress = address
        b.linkAddress = address
        a.onSend = { packet ->
            if (packet.kind == PacketKind.FileOffer) b.files.receiveOffer(transform(packet))
        }
        b.onSend = { packet ->
            if (packet.kind == PacketKind.FileAck || packet.kind == PacketKind.FileReady)
                a.files.receiveControl(packet)
        }
    }

    @Test
    fun socketTransferAuthenticatesCiphertextPersistsCompletionAndAcknowledgesSource() =
        runBlocking<Unit> {
            enableLocalSockets()
            val packet = outgoing()
            a.queue.flush(listOf(b.id()), a.files::canSend)

            val received = receiverTerminal(packet)
            assertEquals("Delivered", received.status)
            assertEquals(100, received.progress)
            assertEquals(100, a.database.use { it.messages().find(packet.id) }!!.progress)
            val file = b.database.use { it.attachments().forMessage(packet.id) }!!
            assertTrue(file.complete)
            assertTrue(b.store.exists(file.id))
            val exported = java.io.ByteArrayOutputStream()
            b.files.export(file.id) { exported }
            assertArrayEquals(contents, exported.toByteArray())
            assertEquals(
                "Delivered",
                a.database.use { it.messages().delivery(packet.id, b.id()) }!!.status,
            )
            assertEquals(1, b.sent.count { it.kind == PacketKind.FileAck })
            assertEquals(1, b.connects.get())
            assertFalse(a.files.busy.value)
        }

    @Test
    fun cancelledSessionDuringOwnerStartupDoesNotLeakListeningSocket() =
        runBlocking<Unit> {
            val packet = outgoing()
            val address = localSocketAddress()
            a.linkAddress = address
            a.tasks.cancel()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { a.files.canSend(packet, b.id()) }
            }
            assertFalse(a.files.busy.value)
            java.nio.channels.ServerSocketChannel.open().use {
                it.bind(java.net.InetSocketAddress(address, 42187))
            }
        }

    @Test
    fun ownerCannotRestartUntilPreviousWifiCleanupFinishes() =
        runBlocking<Unit> {
            val packet = outgoing()
            a.linkAddress = localSocketAddress()
            assertTrue(a.files.canSend(packet, b.id()))
            val stopping = CompletableDeferred<Unit>()
            val allowStop = CompletableDeferred<Unit>()
            a.onStop = {
                stopping.complete(Unit)
                allowStop.await()
            }
            val release = async(Dispatchers.IO) { a.files.release() }
            try {
                withTimeout(5000) { stopping.await() }
                assertTrue(a.files.busy.value)
                assertFalse(a.files.canSend(packet, b.id()))
            } finally {
                allowStop.complete(Unit)
                withTimeout(5000) { release.await() }
                a.onStop = null
            }
            assertFalse(a.files.busy.value)
            assertTrue(a.files.canSend(packet, b.id()))
            a.files.release()
        }

    @Test
    fun socketTransferRejectsSignedPlaintextLengthThatDisagreesWithAuthenticatedCiphertext() =
        runBlocking<Unit> {
            enableLocalSockets { packet ->
                val wrapper =
                    JSONObject(
                        b.secure.open(packet, a.identity.identity()).toString(Charsets.UTF_8)
                    )
                val metadata =
                    JSONObject(
                        String(
                            Base64.getDecoder().decode(wrapper.getString("data")),
                            Charsets.UTF_8,
                        )
                    )
                // The valid ciphertext header authenticates contents.size; the signed offer lies.
                metadata.put("size", 1).put("photo", true)
                wrapper.put("data", encoded(metadata.toString().toByteArray()))
                a.secure.direct(
                    PacketKind.FileOffer,
                    b.identity.identity(),
                    chat,
                    wrapper.toString().toByteArray(),
                    packet.id,
                    packet.epoch,
                )
            }
            val packet = outgoing()
            a.queue.flush(listOf(b.id()), a.files::canSend)

            val received = receiverTerminal(packet)
            assertEquals(
                "A signed offer must agree with the decrypted attachment length",
                "Error",
                received.status,
            )
            val file = b.database.use { it.attachments().forMessage(packet.id) }!!
            assertFalse(file.complete)
            assertFalse(b.store.exists(file.id))
            assertEquals(1, b.connects.get())
            assertFalse(b.sent.any { it.kind == PacketKind.FileAck })
            assertNotEquals(
                "Delivered",
                a.database.use { it.messages().delivery(packet.id, b.id()) }!!.status,
            )
        }
}
