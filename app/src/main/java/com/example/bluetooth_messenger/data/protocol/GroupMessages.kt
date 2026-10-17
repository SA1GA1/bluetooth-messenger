package com.example.bluetooth_messenger.data.protocol

import com.example.bluetooth_messenger.data.local.database.DatabaseSession
import com.example.bluetooth_messenger.domain.model.newId
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.time.AppClock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class GroupMessages
@Inject
constructor(
    private val database: DatabaseSession,
    private val identity: IdentityCrypto,
    private val directory: PeerDirectory,
    private val secure: SecurePackets,
    private val queue: PacketQueue,
    private val direct: DirectMessages,
    private val membership: GroupMembership,
    private val clock: AppClock,
) {
    private val ready = ConcurrentHashMap<Triple<String, String, Int>, Long>()

    suspend fun send(group: String, content: JSONObject) {
        val chat = database.use { checkNotNull(it.contacts().conversation(group)) }
        require(chat.type == "Group" && chat.active)
        val own = identity.identity().userId
        val members = database.use { it.protocol().members(group, chat.epoch) }
        require(members.any { it.memberId == own })
        val key = membership.key(group, chat.epoch)
        try {
            val packet = secure.group(group, chat.epoch, key, content.bytes(), newId())
            val deliveries =
                members
                    .filterNot { it.memberId == own }
                    .map { queue.entry(packet, it.memberId, packet.id) }
            require(deliveries.isNotEmpty()) { "В группе нет других участников" }
            database.use {
                it.messages().enqueue(direct.entity(packet, content, false), deliveries)
            }
        } finally {
            key.fill(0)
        }
    }

    suspend fun receive(packet: WirePacket) {
        val chat =
            database.use { it.contacts().conversation(packet.conversation) }
                ?: throw AwaitingGroupKey()
        require(chat.type == "Group" && chat.active && packet.epoch <= chat.epoch)
        val own = identity.identity().userId
        val current = database.use { it.protocol().members(chat.id, chat.epoch) }
        require(current.any { it.memberId == packet.sender } && current.any { it.memberId == own })
        val sender = directory.conversation(packet.sender, chat.id, packet.epoch)
        val key = membership.key(chat.id, packet.epoch)
        try {
            direct.store(packet, json(secure.openGroup(packet, sender, key)))
        } finally {
            key.fill(0)
        }
        direct.acknowledge(packet, sender)
    }

    suspend fun canSend(packet: WirePacket, target: String): Boolean {
        if (packet.kind != PacketKind.GroupMessage && packet.kind != PacketKind.FileOffer)
            return true
        val group = database.use { it.contacts().conversation(packet.conversation) } ?: return false
        if (group.type != "Group") return true
        if (!group.active) return false
        val token = Triple(group.id, target, packet.epoch)
        val now = clock.elapsedMillis()
        if (ready[token]?.let { now - it < 10_000 } == true) return true
        val peer = directory.conversation(target, group.id, packet.epoch)
        queue.ephemeral(
            secure.direct(
                PacketKind.GroupReady,
                peer,
                group.id,
                JSONObject().put("action", "query").bytes(),
                epoch = packet.epoch,
            )
        )
        return ready[token]?.let { now - it < 10_000 } == true
    }

    suspend fun receiveReady(packet: WirePacket) {
        val chat = database.use { checkNotNull(it.contacts().conversation(packet.conversation)) }
        require(chat.type == "Group" && chat.active)
        val current = database.use { it.protocol().members(chat.id, chat.epoch) }
        require(current.any { it.memberId == packet.sender })
        database.use { it.protocol().groupKey(chat.id, packet.epoch) } ?: return
        val peer = directory.conversation(packet.sender, chat.id, packet.epoch)
        when (json(secure.open(packet, peer)).getString("action")) {
            "query" ->
                queue.ephemeral(
                    secure.direct(
                        PacketKind.GroupReady,
                        peer,
                        chat.id,
                        JSONObject().put("action", "ready").bytes(),
                        epoch = packet.epoch,
                    )
                )
            "ready" -> ready[Triple(chat.id, packet.sender, packet.epoch)] = clock.elapsedMillis()
            else -> error("Некорректное подтверждение ключа")
        }
    }

    fun clearReady() {
        ready.clear()
    }
}
