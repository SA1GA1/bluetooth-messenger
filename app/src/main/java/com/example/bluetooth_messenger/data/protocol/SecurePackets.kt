package com.example.bluetooth_messenger.data.protocol

import com.example.bluetooth_messenger.data.security.MessageCrypto
import com.example.bluetooth_messenger.domain.model.PeerIdentity
import com.example.bluetooth_messenger.domain.model.newId
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.time.AppClock
import javax.inject.Inject

class SecurePackets @Inject constructor(private val identity: IdentityCrypto, private val clock: AppClock) {
    val codec = PacketCodec()
    private val crypto = MessageCrypto()

    private fun context(packet: WirePacket) = codec.unsigned(packet.copy(body = byteArrayOf(), signature = byteArrayOf()))

    suspend fun direct(kind: PacketKind, recipient: PeerIdentity, conversation: String, plaintext: ByteArray,
        id: String = newId(), epoch: Int = 0): WirePacket {
        require(kind != PacketKind.Public && kind != PacketKind.GroupMessage)
        val packet = WirePacket(kind, id, identity.identity().userId, recipient.userId, conversation,
            epoch, clock.wallMillis(), byteArrayOf())
        val sealed = packet.copy(body = crypto.encrypt(plaintext, crypto.decodeKey(recipient.agreementKey), context(packet)))
        return sealed.copy(signature = identity.sign(codec.unsigned(sealed)))
    }

    suspend fun open(packet: WirePacket, sender: PeerIdentity): ByteArray {
        require(packet.target == identity.identity().userId)
        verify(packet, sender)
        return identity.decrypt(packet.body, context(packet))
    }

    suspend fun openPairRequest(packet: WirePacket): ByteArray {
        require(packet.kind == PacketKind.PairRequest && packet.target == identity.identity().userId)
        return identity.decrypt(packet.body, context(packet))
    }

    fun verify(packet: WirePacket, sender: PeerIdentity) {
        require(sender.userId == packet.sender && crypto.verify(codec.unsigned(packet), packet.signature,
            crypto.decodeKey(sender.signingKey))) { "Сообщение отклонено: подпись не совпадает" }
    }

    suspend fun group(conversation: String, epoch: Int, key: ByteArray, plaintext: ByteArray, id: String): WirePacket {
        require(epoch > 0)
        val packet = WirePacket(PacketKind.GroupMessage, id, identity.identity().userId, conversation,
            conversation, epoch, clock.wallMillis(), byteArrayOf())
        val sealed = packet.copy(body = crypto.seal(plaintext, key, context(packet)))
        return sealed.copy(signature = identity.sign(codec.unsigned(sealed)))
    }

    fun openGroup(packet: WirePacket, sender: PeerIdentity, key: ByteArray): ByteArray {
        require(packet.kind == PacketKind.GroupMessage && packet.target == packet.conversation)
        verify(packet, sender)
        return crypto.open(packet.body, key, context(packet))
    }
}
