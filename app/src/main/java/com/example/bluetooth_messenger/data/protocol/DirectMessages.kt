package com.example.bluetooth_messenger.data.protocol

import androidx.room.withTransaction
import com.example.bluetooth_messenger.data.local.database.*
import com.example.bluetooth_messenger.domain.model.*
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.repository.SettingsRepository
import com.example.bluetooth_messenger.domain.time.AppClock
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

class DirectMessages @Inject constructor(
    private val database: DatabaseSession,
    private val identity: IdentityCrypto,
    private val directory: PeerDirectory,
    private val secure: SecurePackets,
    private val queue: PacketQueue,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) {
    suspend fun openContact(peer: String): String {
        directory.contact(peer)
        val own = identity.identity().userId
        val id = directConversationId(own, peer)
        database.use { db ->
            val contact = checkNotNull(db.contacts().find(peer))
            db.contacts().insertConversation(ConversationEntity(id, "Direct", contact.name, peerId = peer))
        }
        return id
    }

    suspend fun send(conversation: String, content: JSONObject) {
        val chat = database.use { checkNotNull(it.contacts().conversation(conversation)) }
        require(chat.type == "Direct" && chat.active)
        val peer = directory.contact(checkNotNull(chat.peerId))
        val id = newId()
        val packet = secure.direct(PacketKind.Direct, peer, conversation, content.bytes(), id)
        val message = entity(packet, content, incoming = false)
        database.use { it.messages().enqueue(message, listOf(queue.entry(packet, message = id))) }
    }

    suspend fun receive(packet: WirePacket) {
        val sender = directory.conversation(packet.sender, packet.conversation, packet.epoch)
        val content = json(secure.open(packet, sender))
        require(content.getString("kind") in setOf("Text", "Location"))
        store(packet, content)
        acknowledge(packet, sender)
    }

    suspend fun store(packet: WirePacket, content: JSONObject): Boolean {
        val incoming = entity(packet, content, incoming = true)
        return database.use { db ->
            db.withTransaction {
                val seen = db.messages().received(packet.id)
                if (seen != null) {
                    require(seen.senderId == packet.sender && seen.conversationId == packet.conversation)
                    return@withTransaction false
                }
                val existing = db.messages().find(packet.id)
                if (existing != null) {
                    require(existing.senderId == packet.sender && existing.conversationId == packet.conversation)
                    db.messages().rememberReceived(ReceivedMessageEntity(packet.id, packet.sender, packet.conversation))
                    false
                } else {
                    if (db.contacts().conversation(packet.conversation) == null) {
                        val peer = checkNotNull(db.contacts().find(packet.sender))
                        require(peer.verified)
                        db.contacts().insertConversation(ConversationEntity(packet.conversation, "Direct", peer.name, peerId = peer.id))
                    }
                    db.messages().enqueue(incoming, emptyList())
                    db.messages().rememberReceived(ReceivedMessageEntity(packet.id, packet.sender, packet.conversation))
                    true
                }
            }
        }
    }

    internal fun entity(packet: WirePacket, content: JSONObject, incoming: Boolean): MessageEntity {
        val kind = ContentType.valueOf(content.getString("kind"))
        require(kind == ContentType.Text || kind == ContentType.Location)
        val text = if (kind == ContentType.Location) {
            val latitude = content.getDouble("latitude")
            val longitude = content.getDouble("longitude")
            require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
            String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)
        } else content.getString("text")
        validateText(text)
        return MessageEntity(packet.id, packet.conversation, packet.sender, kind.name, text, packet.timestamp,
            clock.wallMillis(), incoming, if (incoming) "Delivered" else "Pending", unread = incoming, epoch = packet.epoch)
    }

    suspend fun acknowledge(packet: WirePacket, sender: PeerIdentity) {
        queue.ephemeral(secure.direct(PacketKind.Ack, sender, packet.conversation,
            JSONObject().put("ref", packet.id).bytes(), epoch = packet.epoch))
    }

    suspend fun receipt(packet: WirePacket) {
        val sender = directory.conversation(packet.sender, packet.conversation, packet.epoch)
        val payload = json(secure.open(packet, sender))
        if (packet.kind == PacketKind.Ack) {
            val reference = payload.getString("ref")
            require(reference.length == 36 && UUID.fromString(reference).toString() == reference)
            database.use { db ->
                val delivery = db.messages().delivery(reference, packet.sender) ?: return@use
                if (delivery.packet.isNotEmpty()) {
                    val original = secure.codec.decode(delivery.packet)
                    require(original.conversation == packet.conversation && original.epoch == packet.epoch)
                }
                db.messages().acknowledge(reference, packet.sender)
            }
        } else {
            require(packet.kind == PacketKind.Read)
            val ids = payload.getJSONArray("ids")
            require(ids.length() in 1..50)
            database.use { db ->
                db.withTransaction {
                    repeat(ids.length()) { index ->
                        val id = ids.getString(index)
                        require(id.length == 36)
                        val message = db.messages().find(id)
                        if (message != null && !message.incoming && message.conversationId == packet.conversation && message.epoch == packet.epoch) {
                            db.messages().readDelivery(id, packet.sender)
                        }
                    }
                }
            }
            acknowledge(packet, sender)
        }
    }

    suspend fun markRead(conversation: String) {
        val unread = database.use { db ->
            db.withTransaction {
                val messages = db.messages().unreadIds(conversation).mapNotNull { db.messages().find(it) }
                db.messages().readConversation(conversation)
                messages
            }
        }
        if (!settings.settings.first().readReceipts) return
        for ((author, messages) in unread.groupBy { it.senderId to it.epoch }) {
            val recipient = directory.conversation(author.first, conversation, author.second)
            for (batch in messages.chunked(50)) {
                if (!settings.settings.first().readReceipts) return
                val packet = secure.direct(PacketKind.Read, recipient, conversation,
                    JSONObject().put("ids", JSONArray(batch.map { it.id })).bytes(), epoch = author.second)
                queue.add(packet)
            }
        }
    }
}
