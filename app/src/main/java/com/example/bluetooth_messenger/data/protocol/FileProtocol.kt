package com.example.bluetooth_messenger.data.protocol

import androidx.room.withTransaction
import com.example.bluetooth_messenger.core.coroutines.SessionTasks
import com.example.bluetooth_messenger.data.files.EncryptedFileStore
import com.example.bluetooth_messenger.data.local.database.*
import com.example.bluetooth_messenger.data.security.LocalSecrets
import com.example.bluetooth_messenger.data.security.MessageCrypto
import com.example.bluetooth_messenger.domain.transport.FileLink
import com.example.bluetooth_messenger.domain.model.*
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.time.AppClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileProtocol @Inject constructor(
    private val database: DatabaseSession,
    private val identity: IdentityCrypto,
    private val directory: PeerDirectory,
    private val secure: SecurePackets,
    private val queue: PacketQueue,
    private val membership: GroupMembership,
    private val direct: DirectMessages,
    private val secrets: LocalSecrets,
    private val store: EncryptedFileStore,
    private val wifi: FileLink,
    private val tasks: SessionTasks,
    private val clock: AppClock,
) {
    private val crypto = MessageCrypto()
    private val changing = Mutex()
    private val releasing = Mutex()
    private var closing = false
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private var reservation: Pair<String, String>? = null
    private var job: Job? = null
    private var reservedAt = 0L

    suspend fun send(conversation: String, source: AttachmentSource, photo: Boolean) {
        val chat = database.use { checkNotNull(it.contacts().conversation(conversation)) }
        require(chat.active)
        val own = identity.identity().userId
        val members = if (chat.type == "Group") database.use { it.protocol().members(chat.id, chat.epoch) }.map { it.memberId }
            else listOf(checkNotNull(chat.peerId), own)
        require(own in members && members.size > 1)
        val id = newId()
        val file = newId()
        val name = validatedName(source.name.substringAfterLast('/').substringAfterLast('\\'), 255)
        val mime = source.mimeType.takeIf { it.length in 1..128 && it.none(Char::isISOControl) } ?: "application/octet-stream"
        val saved = store.`import`(file, source, if (photo) Limits.PHOTO_BYTES.toLong() else Limits.FILE_BYTES.toLong())
        try {
            val token = ByteArray(32).also(SecureRandom()::nextBytes)
            val metadata = JSONObject().put("file", file).put("name", name).put("mime", mime).put("photo", photo)
                .put("size", saved.plainSize).put("encryptedSize", saved.encryptedSize).put("key", encodeBytes(saved.key))
                .put("token", encodeBytes(token)).put("hash", encodeBytes(saved.hash)).bytes()
            val body = if (chat.type == "Group") {
                val key = membership.key(chat.id, chat.epoch)
                try { crypto.seal(metadata, key, context(file, chat.id, chat.epoch)) } finally { key.fill(0) }
            } else metadata
            val deliveries = members.filterNot { it == own }.map { member ->
                val peer = directory.conversation(member, conversation, chat.epoch)
                queue.entry(secure.direct(PacketKind.FileOffer, peer, conversation,
                    JSONObject().put("file", file).put("sealed", chat.type == "Group").put("data", encodeBytes(body)).bytes(), id, chat.epoch), member, id)
            }
            val now = clock.wallMillis()
            database.use { db -> db.withTransaction {
                db.messages().enqueue(MessageEntity(id, conversation, own, if (photo) "Photo" else "File", name, now, now,
                    false, "Pending", fileId = file, fileName = name, fileSize = saved.plainSize, mimeType = mime, epoch = chat.epoch), deliveries)
                db.attachments().insert(AttachmentEntity(file, id, own, secrets.wrap(saved.key, "file:$file"), token,
                    saved.hash, saved.plainSize, saved.encryptedSize, true))
            } }
        } catch (failure: Throwable) { store.delete(file); throw failure }
        finally { saved.key.fill(0) }
    }

    suspend fun canSend(packet: WirePacket, target: String): Boolean {
        if (packet.kind != PacketKind.FileOffer) return true
        return changing.withLock {
            if (closing) return@withLock false
            val current = reservation
            if (current != null) return@withLock current == (packet.id to target)
            val file = database.use { it.attachments().forMessage(packet.id) } ?: return@withLock false
            check(file.complete && store.exists(file.id)) { "Локальный файл недоступен" }
            reservation = packet.id to target
            reservedAt = clock.elapsedMillis()
            mutableBusy.value = true
            try {
                val address = withTimeout(45_000) { wifi.becomeOwner(identity.identity().userId) }
                require(!address.isAnyLocalAddress && !address.isLoopbackAddress)
                val server = ServerSocketChannel.open()
                try { server.bind(InetSocketAddress(address, PORT)) } catch (failure: Throwable) { server.close(); throw failure }
                job = tasks.launch {
                    try {
                        withTimeout(120_000) {
                            runInterruptible(Dispatchers.IO) {
                                server.use { listener ->
                                    listener.accept().use { client ->
                                        val input = DataInputStream(Channels.newInputStream(client))
                                        require(input.readInt() == MAGIC)
                                        val requested = UUID(input.readLong(), input.readLong()).toString()
                                        val token = ByteArray(32).also(input::readFully)
                                        require(requested == file.id && MessageDigest.isEqual(token, file.token))
                                        val output = DataOutputStream(Channels.newOutputStream(client))
                                        output.writeLong(file.encryptedSize)
                                        store.input(file.id).use { it.copyTo(output, 65536) }
                                        output.flush()
                                    }
                                }
                            }
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) {
                        database.use { it.messages().deliveryError(packet.id, target) }
                    } finally { server.close() }
                }
                true
            } catch (failure: Throwable) {
                reservation = null; mutableBusy.value = false
                withContext(NonCancellable) { wifi.stop() }
                throw failure
            }
        }
    }

    suspend fun receiveOffer(packet: WirePacket) {
        val sender = directory.conversation(packet.sender, packet.conversation, packet.epoch)
        val wrapper = json(secure.open(packet, sender))
        val fileId = uuid(wrapper.getString("file"))
        val chat = database.use { it.contacts().conversation(packet.conversation) }
        val group = chat?.type == "Group"
        require(wrapper.getBoolean("sealed") == group)
        if (group) {
            require(chat!!.active && packet.epoch <= chat.epoch)
            require(database.use { it.protocol().members(chat.id, chat.epoch) }.any { it.memberId == packet.sender })
        }
        val encoded = java.util.Base64.getDecoder().decode(wrapper.getString("data").also { require(it.length <= 32_768) })
        val raw = if (group) {
            val key = membership.key(packet.conversation, packet.epoch)
            try { crypto.open(encoded, key, context(fileId, packet.conversation, packet.epoch)) } finally { key.fill(0) }
        } else encoded
        val body = json(raw)
        require(uuid(body.getString("file")) == fileId)
        val photo = body.getBoolean("photo")
        val size = body.getLong("size")
        require(size in 0..(if (photo) Limits.PHOTO_BYTES.toLong() else Limits.FILE_BYTES.toLong()))
        val encryptedSize = body.getLong("encryptedSize")
        require(encryptedSize in 1..(51L * 1024 * 1024))
        val name = validatedName(body.getString("name"), 255)
        require('/' !in name && '\\' !in name)
        val mime = body.getString("mime")
        require(mime.length in 1..128 && mime.none(Char::isISOControl))
        val key = decodeBytes(body.getString("key"), 32)
        val token = decodeBytes(body.getString("token"), 32)
        val hash = decodeBytes(body.getString("hash"), 32)
        try {
            val existing = database.use { it.attachments().find(fileId) }
            if (existing != null) require(existing.ownerId == packet.sender && existing.messageId == packet.id &&
                existing.hash.contentEquals(hash) && existing.token.contentEquals(token))
            if (existing?.complete == true) { acknowledgeFile(packet, sender); return }
            val seen = database.use { it.messages().received(packet.id) }
            if (seen != null && existing == null) { acknowledgeFile(packet, sender); return }
            if (existing == null) database.use { db -> db.withTransaction {
                if (chat == null) {
                    val contact = checkNotNull(db.contacts().find(sender.userId))
                    db.contacts().insertConversation(ConversationEntity(packet.conversation, "Direct", contact.name, peerId = sender.userId))
                }
                db.messages().enqueue(MessageEntity(packet.id, packet.conversation, packet.sender, if (photo) "Photo" else "File",
                    name, packet.timestamp, clock.wallMillis(), true, "Pending", unread = true, fileId = fileId, fileName = name,
                    fileSize = size, mimeType = mime, epoch = packet.epoch), emptyList())
                db.attachments().insert(AttachmentEntity(fileId, packet.id, sender.userId, secrets.wrap(key, "file:$fileId"),
                    token, hash, size, encryptedSize, false))
                db.messages().rememberReceived(ReceivedMessageEntity(packet.id, packet.sender, packet.conversation))
            } }
            changing.withLock {
                if (reservation != null || closing) {
                    queue.ephemeral(secure.direct(PacketKind.FileReady, sender, packet.conversation,
                        JSONObject().put("ref", packet.id).put("action", "busy").bytes(), epoch = packet.epoch))
                    database.use { it.messages().fileProgress(packet.id, 0, "Error", "Передача занята. Повторите позже") }
                    return
                }
                reservation = packet.id to sender.userId; reservedAt = clock.elapsedMillis(); mutableBusy.value = true
                val receiveKey = key.copyOf()
                job = tasks.launch {
                    try {
                        database.use { it.messages().fileProgress(packet.id, 0, "Pending") }
                        withTimeout(120_000) {
                            val address = wifi.connectTo(sender.userId)
                            val channel = SocketChannel.open()
                            try {
                                runInterruptible(Dispatchers.IO) {
                                    channel.connect(InetSocketAddress(address, PORT))
                                    DataOutputStream(Channels.newOutputStream(channel)).apply {
                                        writeInt(MAGIC); val id = UUID.fromString(fileId); writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits)
                                        write(token); flush()
                                    }
                                }
                                val input = DataInputStream(Channels.newInputStream(channel))
                                val count = runInterruptible(Dispatchers.IO) { input.readLong() }
                                require(count == encryptedSize)
                                store.receive(fileId, count, hash, input) { progress -> database.use { it.messages().fileProgress(packet.id, progress, "Pending") } }
                            } finally { channel.close() }
                            var plainCount = 0L
                            store.export(fileId, receiveKey, object : OutputStream() {
                                override fun write(value: Int) { require(++plainCount <= size) }
                                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                    plainCount += length
                                    require(plainCount <= size)
                                }
                            })
                            require(plainCount == size)
                            database.use { db -> db.withTransaction {
                                db.attachments().complete(fileId); db.messages().fileProgress(packet.id, 100, "Delivered")
                            } }
                            acknowledgeFile(packet, sender)
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) {
                        store.delete(fileId)
                        database.use { it.messages().fileProgress(packet.id, 0, "Error", "Не удалось получить файл. Повторите передачу") }
                    } finally {
                        receiveKey.fill(0)
                        withContext(NonCancellable) { wifi.stop(); changing.withLock { reservation = null; mutableBusy.value = false } }
                    }
                }
                job?.invokeOnCompletion { receiveKey.fill(0) }
            }
        } finally {
            key.fill(0)
        }
    }

    suspend fun receiveControl(packet: WirePacket) {
        val sender = directory.conversation(packet.sender, packet.conversation, packet.epoch)
        val body = json(secure.open(packet, sender))
        val ref = uuid(body.getString("ref"))
        val delivery = database.use { it.messages().delivery(ref, sender.userId) } ?: return
        if (delivery.packet.isNotEmpty()) {
            val original = secure.codec.decode(delivery.packet)
            require(original.kind == PacketKind.FileOffer && original.conversation == packet.conversation && original.epoch == packet.epoch)
        }
        if (packet.kind == PacketKind.FileAck) {
            database.use { it.messages().acknowledge(ref, sender.userId) }
            release(ref to sender.userId)
        } else when (body.getString("action")) {
            "busy" -> { database.use { it.messages().deliveryError(ref, sender.userId) }; release(ref to sender.userId) }
            "retry" -> database.use { it.messages().retry(ref) }
            else -> error("Некорректное подтверждение файла")
        }
    }

    suspend fun retryIncoming(message: MessageEntity) {
        val file = database.use { checkNotNull(it.attachments().forMessage(message.id)) }
        require(!file.complete)
        val sender = directory.conversation(file.ownerId, message.conversationId, message.epoch)
        queue.ephemeral(secure.direct(PacketKind.FileReady, sender, message.conversationId,
            JSONObject().put("ref", message.id).put("action", "retry").bytes(), epoch = message.epoch))
    }

    suspend fun photo(file: String): ByteArray {
        val row = database.use { checkNotNull(it.attachments().find(file)) }
        require(row.complete && row.plainSize <= Limits.PHOTO_BYTES)
        val key = secrets.open(row.wrappedKey, "file:$file")
        return try { store.bytes(file, key) } finally { key.fill(0) }
    }

    suspend fun export(file: String, open: () -> OutputStream) {
        val row = database.use { checkNotNull(it.attachments().find(file)) }
        require(row.complete)
        val key = secrets.open(row.wrappedKey, "file:$file")
        try { open().use { store.export(file, key, it) } } finally { key.fill(0) }
    }

    suspend fun maintenance() {
        val expired = changing.withLock { reservation?.takeIf { clock.elapsedMillis() - reservedAt > 125_000 } }
        if (expired != null) release(expired)
    }

    suspend fun release(expected: Pair<String, String>? = null) = withContext(NonCancellable) {
        releasing.withLock releaseLock@ {
            val current = changing.withLock {
                if (expected != null && reservation != expected) return@releaseLock
                closing = true
                val active = job; job = null; active?.cancel(); active
            }
            try { current?.join(); wifi.stop() }
            finally { changing.withLock { reservation = null; mutableBusy.value = false; closing = false } }
        }
    }

    fun delete(file: String) = store.delete(file)
    private suspend fun acknowledgeFile(packet: WirePacket, sender: PeerIdentity) = queue.ephemeral(secure.direct(PacketKind.FileAck,
        sender, packet.conversation, JSONObject().put("ref", packet.id).bytes(), epoch = packet.epoch))
    private fun uuid(value: String): String = value.also { require(it.length == 36 && UUID.fromString(it).toString() == it) }
    private fun context(file: String, conversation: String, epoch: Int) = "offgrid-file:$file:$conversation:$epoch".toByteArray()
    private companion object { const val PORT = 42187; const val MAGIC = 0x4f464631 }
}
