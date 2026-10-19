package com.example.bluetooth_messenger.data.protocol

import com.example.bluetooth_messenger.domain.model.Limits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

enum class PacketKind { PairRequest, PairAccept, PairConfirm, PairDone, Direct, Ack, Read, GroupRoster, GroupKey, GroupMessage, FileOffer, FileReady, FileAck, Public }

data class WirePacket(
    val kind: PacketKind,
    val id: String,
    val sender: String,
    val target: String,
    val conversation: String,
    val epoch: Int = 0,
    val timestamp: Long,
    val body: ByteArray,
    val signature: ByteArray = byteArrayOf(),
)

class PacketCodec {
    fun unsigned(packet: WirePacket): ByteArray {
        require(packet.body.size <= Limits.PACKET_BYTES - 256 && packet.epoch >= 0 && packet.timestamp >= 0)
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeInt(0x4f464731)
                it.writeByte(1)
                it.writeByte(packet.kind.ordinal)
                listOf(packet.id, packet.sender, packet.target, packet.conversation).forEach { id ->
                    require(id.length == 36 && UUID.fromString(id).toString() == id)
                    val uuid = UUID.fromString(id)
                    it.writeLong(uuid.mostSignificantBits)
                    it.writeLong(uuid.leastSignificantBits)
                }
                it.writeInt(packet.epoch)
                it.writeLong(packet.timestamp)
                it.writeInt(packet.body.size)
                it.write(packet.body)
            }
        }.toByteArray()
    }

    fun encode(packet: WirePacket): ByteArray {
        require(if (packet.kind == PacketKind.Public) packet.signature.isEmpty() else packet.signature.size in 8..80)
        return unsigned(packet) + byteArrayOf(packet.signature.size.toByte()) + packet.signature
    }

    fun decode(bytes: ByteArray): WirePacket {
        require(bytes.size in 87..Limits.PACKET_BYTES)
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == 0x4f464731 && input.readUnsignedByte() == 1)
            val kind = PacketKind.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown packet")
            fun id() = UUID(input.readLong(), input.readLong()).toString()
            val messageId = id()
            val sender = id()
            val target = id()
            val conversation = id()
            val epoch = input.readInt()
            val timestamp = input.readLong()
            require(epoch >= 0 && timestamp >= 0)
            val size = input.readInt()
            require(size in 0..Limits.PACKET_BYTES - 256 && size < input.available())
            val body = ByteArray(size).also(input::readFully)
            val signatureSize = input.readUnsignedByte()
            require(signatureSize == input.available())
            require(if (kind == PacketKind.Public) signatureSize == 0 else signatureSize in 8..80)
            return WirePacket(kind, messageId, sender, target, conversation, epoch, timestamp,
                body, ByteArray(signatureSize).also(input::readFully))
        } catch (failure: Exception) {
            throw IllegalArgumentException("Некорректный пакет OffGrid", failure)
        }
    }
}
