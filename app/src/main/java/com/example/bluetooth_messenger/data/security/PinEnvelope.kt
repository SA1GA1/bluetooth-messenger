package com.example.bluetooth_messenger.data.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class WrappedPinSecret(val salt: ByteArray, val ciphertext: ByteArray)

class PinEnvelope {
    private val crypto = MessageCrypto()
    private val context = "offgrid-pin-v1".toByteArray()

    fun wrap(secret: ByteArray, pin: CharArray): WrappedPinSecret {
        require(secret.size == 32)
        val salt = ByteArray(32).also(SecureRandom()::nextBytes)
        val key = derive(pin, salt)
        return try { WrappedPinSecret(salt, crypto.seal(secret, key, context)) }
        finally { key.fill(0) }
    }

    fun unwrap(envelope: WrappedPinSecret, pin: CharArray): ByteArray {
        require(envelope.salt.size == 32 && envelope.ciphertext.size == 60)
        val key = derive(pin, envelope.salt)
        return try { crypto.open(envelope.ciphertext, key, context) }
        finally { key.fill(0) }
    }

    private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        require(pin.size == 6 && pin.all { it in '0'..'9' }) { "PIN должен содержать 6 цифр" }
        val spec = PBEKeySpec(pin, salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
