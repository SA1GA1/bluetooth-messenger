package com.example.bluetooth_messenger.data.protocol

import androidx.room.withTransaction
import com.example.bluetooth_messenger.data.local.database.*
import com.example.bluetooth_messenger.data.security.LocalSecrets
import com.example.bluetooth_messenger.domain.model.*
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.time.AppClock
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

class AwaitingGroupKey : IllegalStateException("Ожидание ключа группы")

@Singleton
class GroupMembership @Inject constructor(
    private val database: DatabaseSession,
    private val identity: IdentityCrypto,
    private val directory: PeerDirectory,
    private val secure: SecurePackets,
    private val queue: PacketQueue,
    private val direct: DirectMessages,
    private val secrets: LocalSecrets,
    private val clock: AppClock,
) {
    private val identities = IdentityCodec()
    private val mutation = Mutex()

    suspend fun create(name: String, participants: List<String>): String = mutation.withLock {
        val own = identity.identity()
        val selected = participants.distinct()
        require(selected.isNotEmpty() && selected.size < Limits.GROUP_MEMBERS && own.userId !in selected)
        val id = newId()
        val members = (selected.map { directory.contact(it) } + own).map { GroupMemberEntity(id, 1, it.userId, identities.encode(it)) }
        val chat = ConversationEntity(id, "Group", validatedName(name, 80), ownerId = own.userId, epoch = 1, updatedAt = clock.wallMillis())
        publish(chat, members)
        id
    }

    suspend fun add(group: String, member: String) = mutation.withLock {
        val chat = owned(group)
        val members = database.use { it.protocol().members(group, chat.epoch) }
        require(members.none { it.memberId == member } && members.size < Limits.GROUP_MEMBERS)
        val next = chat.epoch + 1
        val peer = directory.contact(member)
        publish(chat.copy(epoch = next), members.map { it.copy(epoch = next) } + GroupMemberEntity(group, next, member, identities.encode(peer), next))
    }

    suspend fun remove(group: String, member: String) = mutation.withLock {
        removeMember(group, member)
    }

    private suspend fun removeMember(group: String, member: String) {
        val chat = owned(group)
        require(member != chat.ownerId)
        val members = database.use { it.protocol().members(group, chat.epoch) }
        val removed = members.first { it.memberId == member }
        publish(chat.copy(epoch = chat.epoch + 1), members.filterNot { it.memberId == member }.map { it.copy(epoch = chat.epoch + 1) })
        val notice = secure.direct(PacketKind.GroupLeave, identities.decode(removed.identity), group,
            JSONObject().put("action", "removed").put("epoch", chat.epoch + 1).bytes(), epoch = chat.epoch)
        queue.add(notice)
    }

    private suspend fun owned(group: String): ConversationEntity {
        val chat = database.use { checkNotNull(it.contacts().conversation(group)) }
        require(chat.type == "Group" && chat.active && chat.ownerId == identity.identity().userId && chat.epoch < Int.MAX_VALUE)
        return chat
    }

    private suspend fun publish(chat: ConversationEntity, members: List<GroupMemberEntity>) {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            val list = JSONArray(members.sortedBy { it.memberId }.map { JSONObject().put("identity", it.identity).put("joined", it.joinedEpoch) })
            val body = JSONObject().put("name", chat.name).put("owner", chat.ownerId).put("epoch", chat.epoch)
                .put("members", list).put("key", encodeBytes(key)).bytes()
            val roster = GroupKeyEntity(chat.id, chat.epoch, secrets.wrap(key, keyContext(chat.id, chat.epoch)), MessageDigest.getInstance("SHA-256").digest(body))
            val own = identity.identity().userId
            val packets = members.filterNot { it.memberId == own }.map {
                queue.entry(secure.direct(PacketKind.GroupRoster, identities.decode(it.identity), chat.id, body, epoch = chat.epoch))
            }
            database.use { db ->
                db.withTransaction {
                    db.contacts().insertConversation(chat)
                    db.protocol().saveMembers(members)
                    db.protocol().saveGroupKey(roster)
                    db.protocol().updateGroup(chat.id, chat.epoch, true)
                    db.messages().cancelExcluded(chat.id, members.map { it.memberId })
                    db.messages().cancelExcludedControl(chat.id, members.map { it.memberId })
                    db.messages().queue(packets)
                }
            }
        } finally { key.fill(0) }
    }

    suspend fun receiveRoster(packet: WirePacket) = mutation.withLock {
        val owner = directory.contact(packet.sender)
        val raw = secure.open(packet, owner)
        val body = json(raw)
        val epoch = body.getInt("epoch")
        require(epoch > 0 && epoch == packet.epoch && body.getString("owner") == packet.sender)
        val name = validatedName(body.getString("name"), 80)
        val entries = body.getJSONArray("members")
        require(entries.length() in 1..Limits.GROUP_MEMBERS)
        val members = (0 until entries.length()).map { index ->
            val row = entries.getJSONObject(index)
            val peer = identities.decode(row.getString("identity"))
            val joined = row.getInt("joined")
            require(joined in 1..epoch)
            val known = database.use { it.contacts().find(peer.userId) }
            if (known?.verified == true) require(known.signingKey.contentEquals(peer.signingKey) && known.agreementKey.contentEquals(peer.agreementKey))
            GroupMemberEntity(packet.conversation, epoch, peer.userId, identities.encode(peer), joined)
        }
        val own = identity.identity()
        require(members.map { it.memberId }.distinct().size == members.size && members.any { it.memberId == packet.sender })
        val self = members.first { it.memberId == own.userId }
        val supplied = identities.decode(self.identity)
        require(supplied.signingKey.contentEquals(own.signingKey) && supplied.agreementKey.contentEquals(own.agreementKey))
        val previous = database.use { it.contacts().conversation(packet.conversation) }
        require(previous == null || previous.type == "Group" && previous.ownerId == packet.sender)
        val hash = MessageDigest.getInstance("SHA-256").digest(raw)
        val existing = database.use { it.protocol().groupKey(packet.conversation, epoch) }
        if (existing != null) require(existing.rosterHash.contentEquals(hash))
        val key = decodeBytes(body.getString("key"), 32)
        try {
            val wrapped = secrets.wrap(key, keyContext(packet.conversation, epoch))
            database.use { db ->
                db.withTransaction {
                    db.contacts().insertConversation(ConversationEntity(packet.conversation, "Group", name, ownerId = packet.sender, epoch = epoch, updatedAt = clock.wallMillis()))
                    db.protocol().saveMembers(members)
                    db.protocol().saveGroupKey(GroupKeyEntity(packet.conversation, epoch, wrapped, hash))
                    if (previous == null || epoch > previous.epoch) {
                        db.protocol().updateGroup(packet.conversation, epoch, previous == null || self.joinedEpoch > previous.leftAtEpoch)
                        db.messages().cancelExcluded(packet.conversation, members.map { it.memberId })
                        db.messages().cancelExcludedControl(packet.conversation, members.map { it.memberId })
                    }
                }
            }
        } finally { key.fill(0) }
        direct.acknowledge(packet, owner)
    }

    suspend fun key(group: String, epoch: Int): ByteArray {
        val stored = database.use { it.protocol().groupKey(group, epoch) } ?: throw AwaitingGroupKey()
        return secrets.open(stored.wrappedKey, keyContext(group, epoch))
    }

    suspend fun leave(group: String) = mutation.withLock {
        val chat = database.use { checkNotNull(it.contacts().conversation(group)) }
        require(chat.type == "Group" && chat.active)
        val own = identity.identity().userId
        val members = database.use { it.protocol().members(group, chat.epoch) }
        if (chat.ownerId == own) {
            for (member in members.filterNot { it.memberId == own }) queue.add(secure.direct(PacketKind.GroupLeave,
                identities.decode(member.identity), group, JSONObject().put("action", "closed").put("epoch", chat.epoch + 1).bytes(), epoch = chat.epoch))
        } else queue.add(secure.direct(PacketKind.GroupLeave, directory.contact(checkNotNull(chat.ownerId)), group,
            JSONObject().put("action", "leave").bytes(), epoch = chat.epoch))
        database.use { db -> db.withTransaction { db.protocol().leaveLocally(group); db.messages().cancelExcluded(group, emptyList()) } }
    }

    suspend fun receiveLeave(packet: WirePacket): Boolean = mutation.withLock {
        val chat = database.use { checkNotNull(it.contacts().conversation(packet.conversation)) }
        require(chat.type == "Group")
        val sender = if (packet.sender == chat.ownerId) directory.contact(packet.sender)
            else directory.conversation(packet.sender, chat.id, packet.epoch)
        val body = json(secure.open(packet, sender))
        var changed = false
        when (body.getString("action")) {
            "leave" -> {
                require(chat.ownerId == identity.identity().userId && packet.sender != chat.ownerId)
                val member = database.use { it.protocol().members(chat.id, chat.epoch) }.firstOrNull { it.memberId == packet.sender }
                if (chat.active && member != null && packet.epoch >= member.joinedEpoch) {
                    removeMember(chat.id, packet.sender)
                    changed = true
                }
            }
            "removed", "closed" -> {
                require(packet.sender == chat.ownerId)
                val epoch = body.getInt("epoch")
                require(epoch > 0)
                if (epoch >= chat.epoch) {
                    database.use { db -> db.withTransaction {
                        db.protocol().updateGroup(chat.id, epoch, false)
                        db.messages().cancelExcluded(chat.id, emptyList())
                    } }
                    changed = true
                }
            }
            else -> error("Некорректное изменение состава")
        }
        direct.acknowledge(packet, sender)
        changed
    }

    private fun keyContext(group: String, epoch: Int) = "offgrid-group-key:$group:$epoch"
}
