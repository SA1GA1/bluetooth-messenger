package com.example.bluetooth_messenger.data.protocol

import com.example.bluetooth_messenger.data.security.MessageCrypto
import com.example.bluetooth_messenger.domain.model.PeerIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID

class IdentityCodec {
    private val crypto = MessageCrypto()

    fun encode(identity: PeerIdentity): String {
        validateId(identity.userId)
        crypto.decodeKey(identity.signingKey)
        crypto.decodeKey(identity.agreementKey)
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeUTF(identity.userId)
                it.writeShort(identity.signingKey.size)
                it.write(identity.signingKey)
                it.writeShort(identity.agreementKey.size)
                it.write(identity.agreementKey)
            }
        }.toByteArray()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(value: String): PeerIdentity {
        require(value.length <= 1024 && value.startsWith(PREFIX)) { "Неверный QR OffGrid" }
        try {
            val bytes = Base64.getUrlDecoder().decode(value.removePrefix(PREFIX))
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val userId = input.readUTF().also(::validateId)
            fun key(): ByteArray {
                val size = input.readUnsignedShort()
                require(size in 80..128 && size <= input.available())
                return ByteArray(size).also(input::readFully).also(crypto::decodeKey)
            }
            val identity = PeerIdentity(userId, key(), key())
            require(input.available() == 0)
            return identity
        } catch (failure: Exception) {
            throw IllegalArgumentException("Неверный QR OffGrid", failure)
        }
    }

    private fun validateId(value: String) {
        require(value.length == 36 && UUID.fromString(value).toString() == value)
    }

    private companion object { const val PREFIX = "offgrid:1:" }
}
