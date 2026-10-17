package com.example.bluetooth_messenger.data.transport

import org.junit.Assert.*
import org.junit.Test

class BleFramesTest {
    @Test fun defaultMtuRoundTripIsBoundedAndReassemblesOnlyOnce() {
        val codec = BleFrames()
        val packet = ByteArray(16384) { (it % 251).toByte() }
        val frames = codec.split(packet, 23, 42)
        assertTrue(frames.all { it.size <= 20 })
        frames.dropLast(1).forEach { assertNull(codec.accept("a", it, 0)) }
        assertArrayEquals(packet, codec.accept("a", frames.last(), 1))
        assertNull(codec.accept("a", frames.last(), 2))
    }
    @Test fun interleavedSourcesDoNotMixPackets() {
        val codec = BleFrames()
        val a = codec.split(ByteArray(10) { 1 }, 23, 1)
        val b = codec.split(ByteArray(10) { 2 }, 23, 1)
        assertNull(codec.accept("a", a.first(), 0))
        assertNull(codec.accept("b", b.first(), 0))
        assertArrayEquals(ByteArray(10) { 1 }, codec.accept("a", a.last(), 1))
        assertArrayEquals(ByteArray(10) { 2 }, codec.accept("b", b.last(), 1))
    }
    @Test fun rejectsOversizePacketBeforeSplitting() {
        assertThrows(IllegalArgumentException::class.java) { BleFrames().split(ByteArray(65537), 23, 0) }
    }
    @Test fun rejectsTruncatedFrameWithoutAllocatingTransfer() {
        assertNull(BleFrames().accept("a", byteArrayOf(1), 0))
    }
    @Test fun expiredTransferCannotBeCompleted() {
        val codec = BleFrames()
        val frames = codec.split(ByteArray(10), 23, 3)
        assertNull(codec.accept("a", frames.first(), 0))
        assertNull(codec.accept("a", frames.last(), 60_001))
    }
    @Test fun outOfOrderFrameDoesNotProducePartialMessage() {
        val codec = BleFrames()
        val frames = codec.split(ByteArray(10), 23, 3)
        assertNull(codec.accept("a", frames.last(), 0))
    }
}
