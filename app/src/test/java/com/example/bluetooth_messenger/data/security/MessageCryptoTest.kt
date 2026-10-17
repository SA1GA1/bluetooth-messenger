package com.example.bluetooth_messenger.data.security

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class MessageCryptoTest {
    private val crypto = MessageCrypto()
    private fun key() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val context = "offgrid:v1:sender:recipient:message".toByteArray()

    @Test fun roundTripHidesPlaintextAndUsesFreshMaterial() {
        val recipient = key()
        val text = "секрет".toByteArray()
        val first = crypto.encrypt(text, recipient.public, context)
        val second = crypto.encrypt(text, recipient.public, context)
        assertFalse(first.contentEquals(text))
        assertFalse(first.contentEquals(second))
        assertArrayEquals(text, crypto.decrypt(first, recipient.private, context))
    }
    @Test fun rejectsWrongRecipient() {
        val packet = crypto.encrypt("hello".toByteArray(), key().public, context)
        assertThrows(Exception::class.java) { crypto.decrypt(packet, key().private, context) }
    }
    @Test fun rejectsChangedMetadata() {
        val recipient = key()
        val packet = crypto.encrypt("hello".toByteArray(), recipient.public, context)
        assertThrows(Exception::class.java) { crypto.decrypt(packet, recipient.private, "forged".toByteArray()) }
    }
    @Test fun rejectsModifiedCiphertext() {
        val recipient = key()
        val packet = crypto.encrypt("hello".toByteArray(), recipient.public, context)
        packet[packet.lastIndex] = (packet.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { crypto.decrypt(packet, recipient.private, context) }
    }
    @Test fun signatureBindsSenderAndContent() {
        val sender = key()
        val signature = crypto.sign(context, sender.private)
        assertTrue(crypto.verify(context, signature, sender.public))
        assertFalse(crypto.verify(context, signature, key().public))
        assertFalse(crypto.verify("changed".toByteArray(), signature, sender.public))
    }
    @Test fun hkdfMatchesRfc5869FirstBlock() {
        val output = crypto.hkdf(
            ByteArray(22) { 0x0b },
            "000102030405060708090a0b0c".chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            "f0f1f2f3f4f5f6f7f8f9".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf",
            output.joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    @Test fun rejectsTruncatedEnvelope() {
        assertThrows(Exception::class.java) { crypto.decrypt(byteArrayOf(1, 2, 3), key().private, context) }
    }
}
