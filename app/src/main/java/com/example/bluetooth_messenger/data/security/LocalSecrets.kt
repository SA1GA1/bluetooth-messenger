package com.example.bluetooth_messenger.data.security

import javax.inject.Inject

class LocalSecrets @Inject constructor(private val vault: PinVault) {
    private val crypto = MessageCrypto()
    fun wrap(secret: ByteArray, context: String): ByteArray = withKey(context) { key -> crypto.seal(secret, key, context.toByteArray()) }
    fun open(wrapped: ByteArray, context: String): ByteArray = withKey(context) { key -> crypto.open(wrapped, key, context.toByteArray()) }
    private fun <T> withKey(context: String, operation: (ByteArray) -> T): T {
        val master = vault.keyCopy()
        val key = try { crypto.hkdf(master, ByteArray(32), context.toByteArray()) } finally { master.fill(0) }
        return try { operation(key) } finally { key.fill(0) }
    }
}
