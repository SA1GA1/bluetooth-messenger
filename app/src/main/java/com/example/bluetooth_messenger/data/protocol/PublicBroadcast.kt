package com.example.bluetooth_messenger.data.protocol

import com.example.bluetooth_messenger.data.local.database.DatabaseSession
import com.example.bluetooth_messenger.data.local.database.PublicMessageEntity
import com.example.bluetooth_messenger.domain.model.Limits
import com.example.bluetooth_messenger.domain.model.newId
import com.example.bluetooth_messenger.domain.model.validateText
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.repository.SettingsRepository
import com.example.bluetooth_messenger.domain.time.AppClock
import com.example.bluetooth_messenger.domain.transport.DiscoveryService
import com.example.bluetooth_messenger.domain.transport.PeerTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject

class PublicBroadcast @Inject constructor(
    private val database: DatabaseSession,
    private val identity: IdentityCrypto,
    private val transport: PeerTransport,
    private val discovery: DiscoveryService,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) {
    suspend fun send(text: String) {
        require(settings.settings.first().publicMode) { "Включите общий эфир" }
        validateText(text, public = true)
        val peers = discovery.peers.value
        require(peers.isNotEmpty()) { "Рядом нет устройств" }
        val now = clock.wallMillis()
        val packet = WirePacket(PacketKind.Public, newId(), identity.identity().userId, PUBLIC_ID,
            PUBLIC_ID, timestamp = now, body = text.toByteArray())
        val encoded = PacketCodec().encode(packet)
        var sent = false
        for (peer in peers) {
            try { transport.send(peer.userId, encoded); sent = true }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { }
        }
        check(sent) { "Не удалось отправить сообщение в эфир" }
        database.use { db ->
            db.protocol().savePublic(PublicMessageEntity(packet.id, packet.sender, text, now, now + Limits.PUBLIC_LIFETIME_MILLIS, mine = true))
            db.protocol().trimPublic()
        }
    }

    suspend fun receive(packet: WirePacket) {
        if (!settings.settings.first().publicMode) return
        require(packet.kind == PacketKind.Public && packet.target == PUBLIC_ID && packet.conversation == PUBLIC_ID && packet.body.size <= Limits.PUBLIC_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(packet.body)).toString()
        validateText(text, public = true)
        val now = clock.wallMillis()
        database.use { db ->
            db.protocol().savePublic(PublicMessageEntity(packet.id, packet.sender, text, now, now + Limits.PUBLIC_LIFETIME_MILLIS))
            db.protocol().trimPublic()
        }
    }

    private companion object { const val PUBLIC_ID = "00000000-0000-0000-0000-000000000000" }
}
