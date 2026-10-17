package com.example.bluetooth_messenger.data.repository

import androidx.room.withTransaction
import com.example.bluetooth_messenger.core.coroutines.*
import com.example.bluetooth_messenger.data.local.database.*
import com.example.bluetooth_messenger.data.protocol.*
import com.example.bluetooth_messenger.domain.model.*
import com.example.bluetooth_messenger.domain.repository.*
import com.example.bluetooth_messenger.domain.session.*
import com.example.bluetooth_messenger.domain.time.AppClock
import com.example.bluetooth_messenger.domain.transport.*
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMessengerRepository
@Inject
constructor(
    private val database: DatabaseSession,
    private val identity: IdentityCrypto,
    private val session: SessionController,
    private val settings: SettingsRepository,
    private val discovery: DiscoveryService,
    private val transport: PeerTransport,
    private val tasks: SessionTasks,
    @ApplicationScope private val scope: CoroutineScope,
    private val pairing: PairingCoordinator,
    private val direct: DirectMessages,
    private val membership: GroupMembership,
    private val groups: GroupMessages,
    private val queue: PacketQueue,
    private val public: PublicBroadcast,
    private val files: FileProtocol,
    private val notices: GroupDeliveryNotices,
    private val clock: AppClock,
) : MessengerRepository {
    private val mutableOwnId = MutableStateFlow<String?>(null)
    override val ownId = mutableOwnId.asStateFlow()
    private val mutableQr = MutableStateFlow<String?>(null)
    override val ownQr = mutableQr.asStateFlow()
    private val mutableErrors = MutableStateFlow<String?>(null)
    override val errors = mutableErrors.asStateFlow()
    private val mutablePairings = MutableStateFlow<List<PairingPrompt>>(emptyList())
    override val pairings = mutablePairings.asStateFlow()
    override val transferBusy = files.busy
    private val heartbeat = MutableStateFlow(0L)
    private val commands = Mutex()

    private data class Initialization(val database: OffGridDatabase, val failure: Exception? = null)

    private val initialization = MutableStateFlow<Initialization?>(null)

    override val contacts = observe { db ->
        db.contacts().observe().map { rows -> rows.map { Contact(it.id, it.name, it.verified) } }
    }
    override val conversations = observe { db ->
        db.contacts().summaries().map { rows ->
            rows.map { row ->
                val chat = row.conversation
                Conversation(
                    chat.id,
                    chat.name,
                    ConversationType.valueOf(chat.type),
                    row.lastText.orEmpty(),
                    chat.updatedAt,
                    row.unreadCount,
                    chat.active,
                    chat.ownerId,
                )
            }
        }
    }
    override val publicMessages = heartbeat.flatMapLatest {
        observe { db ->
            db.protocol().publicMessages(clock.wallMillis()).map { rows ->
                rows.map { PublicMessage(it.id, it.text, it.timestamp, it.mine) }
            }
        }
    }

    init {
        scope.launch {
            session.state.collectLatest { state ->
                if (state != SessionState.Ready) {
                    initialization.value = null
                    mutableOwnId.value = null
                    mutableQr.value = null
                    mutablePairings.value = emptyList()
                    mutableErrors.value = null
                    groups.clearReady()
                    files.release()
                    return@collectLatest
                }
                val runtime = tasks.launch { runSession() }
                try {
                    runtime.join()
                } finally {
                    runtime.cancelAndJoin()
                }
            }
        }
    }

    private suspend fun runSession(): Nothing = supervisorScope {
        val opened = checkNotNull(database.database.value)
        val own =
            try {
                identity.identity().also {
                    database.use { db -> db.messages().interruptSent() }
                    files.recover()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mutableErrors.value =
                    "Не удалось восстановить локальные данные. Повторите разблокировку"
                initialization.value = Initialization(opened, failure)
                awaitCancellation()
            }
        launch { pairing.prompts().collect { mutablePairings.value = it } }
        launch {
            settings.settings.collect {
                if (!it.readReceipts) database.use { db -> db.messages().cancelReadReceipts() }
            }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.Packet -> {
                        try {
                            receive(PacketCodec().decode(event.bytes))
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            mutableErrors.value =
                                "Пакет отклонён: данные или подпись не прошли проверку"
                        }
                    }
                    is TransportEvent.Failure -> mutableErrors.value = event.description
                }
            }
        }
        launch {
            while (isActive) {
                try {
                    pairing.maintenance()
                    files.maintenance()
                    notices.delivered()
                    database.use { db ->
                        db.messages().expire(clock.elapsedMillis() - 120_000)
                        db.protocol().expirePublic(clock.wallMillis())
                    }
                    heartbeat.value++
                    flush()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    mutableErrors.value = "Не удалось обновить очередь обмена"
                }
                delay(2000)
            }
        }
        initialization.value = Initialization(opened)
        mutableOwnId.value = own.userId
        mutableQr.value = IdentityCodec().encode(own)
        awaitCancellation()
    }

    private fun flush() {
        if (session.state.value != SessionState.Ready) return
        tasks.launch {
            try {
                queue.flush(discovery.peers.value.map { it.userId }) { packet, peer ->
                    groups.canSend(packet, peer) && files.canSend(packet, peer)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                mutableErrors.value = "Не удалось отправить очередь сообщений"
            }
        }
    }

    private suspend fun receive(packet: WirePacket) {
        when (packet.kind) {
            PacketKind.PairRequest,
            PacketKind.PairAccept,
            PacketKind.PairConfirm,
            PacketKind.PairDone -> pairing.receive(packet)
            PacketKind.Direct -> direct.receive(packet)
            PacketKind.Ack,
            PacketKind.Read -> direct.receipt(packet)
            PacketKind.GroupRoster -> membership.receiveRoster(packet)
            PacketKind.GroupMessage -> groups.receive(packet)
            PacketKind.GroupReady -> groups.receiveReady(packet)
            PacketKind.GroupLeave -> if (membership.receiveLeave(packet)) files.release()
            PacketKind.FileOffer -> files.receiveOffer(packet)
            PacketKind.FileReady,
            PacketKind.FileAck -> files.receiveControl(packet)
            PacketKind.Public -> public.receive(packet)
        }
        flush()
    }

    override fun messages(conversation: String) = observe {
        it.messages().observe(conversation).map { rows -> rows.map { it.toDomain() } }
    }

    override fun recipients(message: String) = observe { db ->
        combine(db.messages().deliveries(message), db.contacts().observe()) { rows, contacts ->
            rows.map {
                RecipientDelivery(
                    contacts.firstOrNull { contact -> contact.id == it.targetId }?.name
                        ?: "Участник",
                    DeliveryStatus.valueOf(it.status),
                )
            }
        }
    }

    override fun groupMembers(conversation: String) = observe { db ->
        combine(db.protocol().observeMembers(conversation), db.contacts().observe()) {
            rows,
            contacts ->
            rows.map {
                GroupMember(
                    it.memberId,
                    contacts.firstOrNull { contact -> contact.id == it.memberId }?.name
                        ?: if (it.memberId == ownId.value) "Вы" else "Участник",
                    true,
                )
            }
        }
    }

    override suspend fun contactDetails(peer: String): ContactDetails = command {
        val row = database.use { checkNotNull(it.contacts().find(peer)) }
        require(row.verified)
        val encoded = IdentityCodec().encode(PeerIdentity(row.id, row.signingKey, row.agreementKey))
        val fingerprint =
            MessageDigest.getInstance("SHA-256")
                .digest(row.signingKey + row.agreementKey)
                .joinToString("") { "%02X".format(it.toInt() and 255) }
                .chunked(4)
                .joinToString(" ")
        ContactDetails(
            row.name,
            fingerprint,
            encoded.replaceFirst("offgrid:1:", "offgrid-check:1:"),
        )
    }

    override suspend fun photoBytes(fileId: String) = command { files.photo(fileId) }

    override suspend fun exportFile(fileId: String, open: () -> OutputStream) = command {
        files.export(fileId, open)
    }

    override suspend fun addFromQr(value: String, name: String) = command {
        pairing.add(value, name)
    }

    override suspend fun confirmPairing(peer: String, name: String) = command {
        pairing.confirm(peer, name)
    }

    override suspend fun rejectPairing(peer: String) = command { pairing.reject(peer) }

    override suspend fun openContact(peer: String) = command { direct.openContact(peer) }

    override suspend fun sendText(conversation: String, text: String) = command {
        validateText(text)
        sendContent(conversation, JSONObject().put("kind", "Text").put("text", text))
    }

    override suspend fun sendLocation(conversation: String, latitude: Double, longitude: Double) =
        command {
            sendContent(
                conversation,
                JSONObject()
                    .put("kind", "Location")
                    .put("latitude", latitude)
                    .put("longitude", longitude),
            )
        }

    private suspend fun sendContent(conversation: String, body: JSONObject) {
        val chat = database.use { checkNotNull(it.contacts().conversation(conversation)) }
        if (chat.type == "Group") {
            groups.send(conversation, body)
            notices.deferred(conversation)
        } else direct.send(conversation, body)
    }

    override suspend fun sendPublic(text: String) = command { public.send(text) }

    override suspend fun sendAttachment(
        conversation: String,
        source: AttachmentSource,
        photo: Boolean,
    ) = command {
        files.send(conversation, source, photo)
        notices.deferred(conversation)
    }

    override suspend fun createGroup(name: String, members: List<String>) = command {
        membership.create(name, members)
    }

    override suspend fun addGroupMember(group: String, member: String) = command {
        files.release()
        membership.add(group, member)
    }

    override suspend fun removeGroupMember(group: String, member: String) = command {
        files.release()
        membership.remove(group, member)
    }

    override suspend fun leaveGroup(group: String) = command {
        files.release()
        membership.leave(group)
    }

    override suspend fun retry(message: String) = command {
        val row = database.use { checkNotNull(it.messages().find(message)) }
        val chat = database.use { checkNotNull(it.contacts().conversation(row.conversationId)) }
        require(chat.active) { "Вы больше не участвуете в группе" }
        if (row.incoming) files.retryIncoming(row)
        else
            database.use { db ->
                db.messages().retry(message)
                db.messages().status(message, "Pending")
            }
    }

    override suspend fun markRead(conversation: String) = command { direct.markRead(conversation) }

    override suspend fun deleteMessage(message: String) = command {
        files.release()
        val file = database.use { it.attachments().forMessage(message) }
        database.use { it.messages().deleteLocal(message) }
        file?.let { files.delete(it.id) }
        Unit
    }

    override suspend fun clearHistory(conversation: String) = command { clear(conversation, false) }

    override suspend fun deleteConversation(conversation: String) = command {
        require(database.use { it.contacts().conversation(conversation) }?.type == "Direct")
        clear(conversation, true)
    }

    private suspend fun clear(conversation: String, delete: Boolean) {
        files.release()
        val attachments = database.use { it.attachments().forConversation(conversation) }
        database.use { db ->
            db.withTransaction {
                db.messages().clearHistory(conversation)
                if (delete) db.contacts().deleteConversation(conversation)
            }
        }
        attachments.forEach { files.delete(it.id) }
    }

    override fun dismissError() {
        mutableErrors.value = null
    }

    private fun <T> observe(block: (OffGridDatabase) -> Flow<List<T>>): Flow<List<T>> =
        database.database.flatMapLatest {
            if (it == null) flowOf(emptyList()) else block(it)
        }

    private suspend fun <T> command(block: suspend () -> T): T {
        check(session.state.value == SessionState.Ready) { "Сначала разблокируйте приложение" }
        return tasks.run {
            val opened = checkNotNull(database.database.value) { "Приложение заблокировано" }
            initialization.first { it?.database === opened }?.failure?.let { throw it }
            commands.withLock { block().also { flush() } }
        }
    }
}
