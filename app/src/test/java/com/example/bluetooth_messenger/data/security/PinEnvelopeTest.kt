package com.example.bluetooth_messenger.data.security

import org.junit.Assert.*
import org.junit.Test

class PinEnvelopeTest {
    private val envelopes = PinEnvelope()
    private val secret = ByteArray(32) { 7 }
    @Test fun secretRequiresCorrectPinAndIsNeverStoredAsPlaintext() {
        val wrapped = envelopes.wrap(secret, "123456".toCharArray())
        assertFalse(secret.contentEquals(wrapped.ciphertext))
        assertArrayEquals(secret, envelopes.unwrap(wrapped, "123456".toCharArray()))
        assertThrows(Exception::class.java) { envelopes.unwrap(wrapped, "654321".toCharArray()) }
    }
    @Test fun eachEnvelopeUsesIndependentSaltAndNonce() {
        val first = envelopes.wrap(secret, "123456".toCharArray())
        val second = envelopes.wrap(secret, "123456".toCharArray())
        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }
    @Test fun onlySixAsciiDigitsAreAccepted() {
        listOf("12345", "1234567", "abcdef", "１２３４５６").forEach { pin ->
            assertThrows(IllegalArgumentException::class.java) { envelopes.wrap(secret, pin.toCharArray()) }
        }
    }
    @Test fun tamperingCannotProduceASecret() {
        val wrapped = envelopes.wrap(secret, "123456".toCharArray())
        wrapped.ciphertext[0] = (wrapped.ciphertext[0].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { envelopes.unwrap(wrapped, "123456".toCharArray()) }
    }
}
