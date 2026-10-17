package com.example.bluetooth_messenger.data.protocol

import androidx.room.withTransaction
import com.example.bluetooth_messenger.data.local.database.*
import com.example.bluetooth_messenger.domain.model.*
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.time.AppClock
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.json.JSONObject

class PairingCoordinator
@Inject
constructor(
    private val database: DatabaseSession,
    private val identity: IdentityCrypto,
    private val secure: SecurePackets,
    private val queue: PacketQueue,
    private val clock: AppClock,
) {
    private val identities = IdentityCodec()
    private val random = SecureRandom()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun prompts(): Flow<List<PairingPrompt>> =
        database.database.flatMapLatest { db ->
            if (db == null) flowOf(emptyList())
            else
                db.protocol().pairings().map { rows ->
                    rows
                        .filter {
                            !it.completed && !it.rejected && it.expiresAt > clock.wallMillis()
                        }
                        .map { prompt(it) }
                }
        }

    suspend fun add(qr: String, name: String) {
        val peer = identities.decode(qr)
        val own = identity.identity()
        require(peer.userId != own.userId) { "Это ваш QR" }
        val existing = database.use { it.contacts().find(peer.userId) }
        if (existing != null) {
            checkKeys(existing, peer)
            require(!existing.verified) { "Контакт уже добавлен" }
        }
        require(database.use { it.protocol().pairingCount() } < 16) {
            "Слишком много запросов знакомства"
        }
        val row =
            PairingEntity(
                peer.userId,
                newId(),
                qr,
                validatedName(name),
                ByteArray(32).also(random::nextBytes),
                null,
                true,
                expiresAt = clock.wallMillis() + 120_000,
            )
        save(
            row,
            secure.direct(
                PacketKind.PairRequest,
                peer,
                row.pairingId,
                JSONObject()
                    .put("identity", identities.encode(own))
                    .put("a", encodeBytes(row.nonceA))
                    .bytes(),
                row.pairingId,
            ),
        )
    }

    suspend fun prompt(row: PairingEntity): PairingPrompt {
        val b = row.nonceB ?: return PairingPrompt(row.peerId, row.name, null, row.localConfirmed)
        val own = identities.encode(identity.identity())
        val canonical =
            ByteArrayOutputStream()
                .also { output ->
                    DataOutputStream(output).use {
                        it.writeUTF("offgrid-pairing-v1")
                        it.writeUTF(row.pairingId)
                        it.writeUTF(if (row.initiator) own else row.identity)
                        it.writeUTF(if (row.initiator) row.identity else own)
                        it.write(row.nonceA)
                        it.write(b)
                    }
                }
                .toByteArray()
        val number =
            (ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(canonical))
                .int
                .toLong() and 0xffffffffL) % 1_000_000
        return PairingPrompt(
            row.peerId,
            row.name,
            String.format(Locale.ROOT, "%06d", number),
            row.localConfirmed,
        )
    }

    suspend fun confirm(peer: String, name: String) {
        val old = checkNotNull(database.use { it.protocol().pairing(peer) })
        require(old.expiresAt > clock.wallMillis() && old.nonceB != null && !old.rejected) {
            "Запрос знакомства истёк"
        }
        val row = old.copy(name = validatedName(name), localConfirmed = true)
        save(row, control(row, PacketKind.PairConfirm))
        if (row.remoteConfirmed) finalize(row)
    }

    suspend fun reject(peer: String) = database.use { database ->
        val row = database.protocol().pairing(peer) ?: return@use
        database.withTransaction {
            database.messages().cancelScope(row.pairingId)
            database.protocol().savePairing(row.copy(rejected = true))
        }
    }

    suspend fun receive(packet: WirePacket) {
        if (packet.kind == PacketKind.PairRequest) {
            receiveRequest(packet)
            return
        }
        val row = database.use { it.protocol().pairing(packet.sender) }
        val known = database.use { it.contacts().find(packet.sender) }
        val peer =
            row?.let { identities.decode(it.identity) }
                ?: known
                    ?.takeIf { it.verified }
                    ?.let {
                        PeerIdentity(it.id, it.signingKey, it.agreementKey)
                    }
                ?: error("Контакт не подтверждён")
        val payload = json(secure.open(packet, peer))
        val a = decodeBytes(payload.getString("a"), 32)
        val b = decodeBytes(payload.getString("b"), 32)
        if (row == null) {
            if (packet.kind == PacketKind.PairConfirm && known?.verified == true) {
                queue.ephemeral(
                    secure.direct(PacketKind.PairDone, peer, packet.conversation, payload.bytes())
                )
            }
            return
        }
        require(
            !row.rejected &&
                row.expiresAt > clock.wallMillis() &&
                row.pairingId == packet.conversation &&
                row.nonceA.contentEquals(a)
        )
        when (packet.kind) {
            PacketKind.PairAccept -> {
                require(row.initiator)
                val provided = identities.decode(payload.getString("identity"))
                require(same(peer, provided))
                require(row.nonceB == null || row.nonceB.contentEquals(b))
                database.use {
                    it.protocol().savePairing(row.copy(nonceB = b))
                    it.messages().cancelControl(row.pairingId)
                }
            }
            PacketKind.PairConfirm,
            PacketKind.PairDone -> {
                require(row.nonceB?.contentEquals(b) == true)
                val updated = row.copy(remoteConfirmed = true)
                database.use { it.protocol().savePairing(updated) }
                if (updated.localConfirmed) {
                    finalize(updated)
                    if (packet.kind == PacketKind.PairDone)
                        database.use { db ->
                            db.withTransaction {
                                db.messages().cancelScope(row.pairingId)
                                db.protocol().deletePairing(row.peerId)
                            }
                        }
                }
            }
            else -> error("Некорректный запрос знакомства")
        }
    }

    private suspend fun receiveRequest(packet: WirePacket) {
        require(packet.id == packet.conversation)
        val payload = json(secure.openPairRequest(packet))
        val peer = identities.decode(payload.getString("identity"))
        secure.verify(packet, peer)
        val a = decodeBytes(payload.getString("a"), 32)
        val known = database.use { it.contacts().find(peer.userId) }
        if (known != null) checkKeys(known, peer)
        val previous = database.use { it.protocol().pairing(peer.userId) }
        if (previous?.rejected == true && previous.expiresAt > clock.wallMillis()) return
        if (
            previous != null &&
                previous.pairingId != packet.conversation &&
                previous.expiresAt > clock.wallMillis()
        ) {
            if (previous.pairingId < packet.conversation) return
            database.use { it.messages().cancelScope(previous.pairingId) }
        }
        val row =
            if (previous?.pairingId == packet.conversation) {
                require(previous.nonceA.contentEquals(a))
                previous
            } else {
                require(database.use { it.protocol().pairingCount() } < 16)
                PairingEntity(
                    peer.userId,
                    packet.conversation,
                    identities.encode(peer),
                    known?.name ?: "",
                    a,
                    ByteArray(32).also(random::nextBytes),
                    false,
                    localConfirmed = known?.verified == true,
                    expiresAt = clock.wallMillis() + 120_000,
                )
            }
        val response =
            JSONObject()
                .put("identity", identities.encode(identity.identity()))
                .put("a", encodeBytes(row.nonceA))
                .put("b", encodeBytes(checkNotNull(row.nonceB)))
        save(
            row,
            secure.direct(
                PacketKind.PairAccept,
                peer,
                row.pairingId,
                response.bytes(),
                controlId(row, PacketKind.PairAccept),
            ),
        )
    }

    private suspend fun control(row: PairingEntity, kind: PacketKind): WirePacket =
        secure.direct(
            kind,
            identities.decode(row.identity),
            row.pairingId,
            JSONObject()
                .put("a", encodeBytes(row.nonceA))
                .put("b", encodeBytes(checkNotNull(row.nonceB)))
                .bytes(),
            controlId(row, kind),
        )

    private suspend fun controlId(row: PairingEntity, kind: PacketKind): String =
        UUID.nameUUIDFromBytes(
                "${row.pairingId}:${kind.name}:${identity.identity().userId}".toByteArray()
            )
            .toString()

    private suspend fun save(row: PairingEntity, packet: WirePacket) = database.use { db ->
        db.withTransaction {
            db.protocol().savePairing(row)
            db.messages().queue(listOf(queue.entry(packet)))
        }
    }

    private suspend fun finalize(row: PairingEntity) {
        require(row.localConfirmed && row.remoteConfirmed && row.nonceB != null)
        val peer = identities.decode(row.identity)
        val own = identity.identity()
        val conversation = directConversationId(own.userId, peer.userId)
        if (!row.completed)
            database.use { db ->
                db.withTransaction {
                    val existing = db.contacts().find(peer.userId)
                    if (existing != null) checkKeys(existing, peer)
                    db.contacts()
                        .insert(
                            ContactEntity(
                                peer.userId,
                                validatedName(row.name),
                                peer.signingKey,
                                peer.agreementKey,
                                verified = true,
                            )
                        )
                    db.contacts().verify(peer.userId)
                    db.contacts()
                        .insertConversation(
                            ConversationEntity(
                                conversation,
                                "Direct",
                                existing?.name ?: row.name,
                                peerId = peer.userId,
                                updatedAt = clock.wallMillis(),
                            )
                        )
                    val systemId =
                        UUID.nameUUIDFromBytes("keys:$conversation".toByteArray()).toString()
                    db.messages()
                        .insert(
                            MessageEntity(
                                systemId,
                                conversation,
                                own.userId,
                                "System",
                                "Ключи обменяны. E2E шифрование активно.",
                                clock.wallMillis(),
                                clock.wallMillis(),
                                false,
                                "Delivered",
                            )
                        )
                    db.protocol().savePairing(row.copy(completed = true))
                }
            }
        if (!row.completed) queue.ephemeral(control(row, PacketKind.PairDone))
    }

    suspend fun maintenance() = database.use { db ->
        db.withTransaction {
            db.protocol().expiredPairings(clock.wallMillis()).forEach {
                db.messages().cancelScope(it.pairingId)
            }
            db.protocol().expirePairings(clock.wallMillis())
        }
    }

    private fun checkKeys(existing: ContactEntity, peer: PeerIdentity) {
        require(
            existing.signingKey.contentEquals(peer.signingKey) &&
                existing.agreementKey.contentEquals(peer.agreementKey)
        ) {
            "Ключ контакта изменился. Автоматическая замена запрещена."
        }
    }

    private fun same(a: PeerIdentity, b: PeerIdentity): Boolean =
        a.userId == b.userId &&
            a.signingKey.contentEquals(b.signingKey) &&
            a.agreementKey.contentEquals(b.agreementKey)
}
