package com.example.bluetooth_messenger.data.transport

import com.example.bluetooth_messenger.domain.model.Limits
import java.nio.ByteBuffer

class BleFrames {
    private data class Transfer(
        val bytes: ByteArray,
        val count: Int,
        val started: Long,
        var index: Int = 0,
        var offset: Int = 0,
    )

    private val incoming = mutableMapOf<Pair<String, Int>, Transfer>()

    fun split(packet: ByteArray, mtu: Int, transferId: Int): List<ByteArray> {
        require(packet.isNotEmpty() && packet.size <= Limits.PACKET_BYTES)
        require(mtu in 23..517)
        val capacity = mtu - 3 - HEADER_SIZE
        val count = (packet.size + capacity - 1) / capacity
        return (0 until count).map { index ->
            val start = index * capacity
            val end = minOf(start + capacity, packet.size)
            ByteBuffer.allocate(HEADER_SIZE + end - start)
                .apply {
                    putInt(transferId)
                    putShort(index.toShort())
                    putShort(count.toShort())
                    putInt(packet.size)
                    put(packet, start, end - start)
                }
                .array()
        }
    }

    @Synchronized
    fun accept(source: String, frame: ByteArray, nowMillis: Long): ByteArray? {
        incoming.entries.removeAll { nowMillis - it.value.started >= 30_000L }
        if (frame.size !in HEADER_SIZE + 1..514) return null
        val buffer = ByteBuffer.wrap(frame)
        val token = source to buffer.int
        val index = buffer.short.toInt() and 0xffff
        val count = buffer.short.toInt() and 0xffff
        val length = buffer.int
        if (length !in 1..Limits.PACKET_BYTES || count !in 1..8192 || index >= count) return null
        if (index == 0) {
            if (incoming.size >= 32 && token !in incoming) return null
            val chunk = buffer.remaining()
            if ((length + chunk - 1) / chunk != count) return null
            incoming[token] = Transfer(ByteArray(length), count, nowMillis)
        }
        val transfer = incoming[token] ?: return null
        if (
            transfer.index != index ||
                transfer.count != count ||
                transfer.bytes.size != length ||
                transfer.offset + buffer.remaining() > length
        ) {
            incoming.remove(token)
            return null
        }
        val chunk = buffer.remaining()
        buffer.get(transfer.bytes, transfer.offset, chunk)
        transfer.offset += chunk
        transfer.index++
        if (transfer.index < transfer.count) return null
        incoming.remove(token)
        return transfer.bytes.takeIf { transfer.offset == length }
    }

    @Synchronized
    fun clear() {
        incoming.clear()
    }

    private companion object {
        const val HEADER_SIZE = 12
    }
}
