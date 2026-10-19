package com.example.bluetooth_messenger.data.protocol

import org.junit.Assert.*
import org.junit.Test

class PacketCodecTest {
    private val codec = PacketCodec()
    private val id = "ea01bde3-462c-4793-a532-54c02cc94699"
    private fun packet() = WirePacket(PacketKind.Direct, id, id, id, id,
        timestamp = 10, body = byteArrayOf(1, 2), signature = ByteArray(64) { 1 })

    @Test fun roundTripPreservesRoutingAndSignature() {
        val source = packet()
        val decoded = codec.decode(codec.encode(source))
        assertEquals(source.kind, decoded.kind)
        assertEquals(source.id, decoded.id)
        assertEquals(source.sender, decoded.sender)
        assertEquals(source.target, decoded.target)
        assertEquals(source.conversation, decoded.conversation)
        assertEquals(source.timestamp, decoded.timestamp)
        assertArrayEquals(source.body, decoded.body)
        assertArrayEquals(source.signature, decoded.signature)
    }
    @Test fun signatureContextChangesWithRecipientAndEpoch() {
        val source = packet()
        assertFalse(codec.unsigned(source).contentEquals(codec.unsigned(source.copy(target = "6475ec0b-2c4c-4f1b-a951-84d215eb2bb5"))))
        assertFalse(codec.unsigned(source).contentEquals(codec.unsigned(source.copy(epoch = 2))))
    }
    @Test fun rejectsTruncatedOversizeAndTrailingPackets() {
        assertThrows(IllegalArgumentException::class.java) { codec.decode(byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(ByteArray(65537)) }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(codec.encode(packet()) + byteArrayOf(0)) }
    }
    @Test fun refusesUnsignedPrivatePacket() {
        assertThrows(IllegalArgumentException::class.java) { codec.encode(packet().copy(signature = byteArrayOf())) }
    }
    @Test fun permitsUnsignedPublicPacket() {
        val source = packet().copy(kind = PacketKind.Public, signature = byteArrayOf())
        assertEquals(PacketKind.Public, codec.decode(codec.encode(source)).kind)
    }
}
