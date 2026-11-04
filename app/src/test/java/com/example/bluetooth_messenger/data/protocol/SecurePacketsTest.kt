package com.example.bluetooth_messenger.data.protocol

import com.example.bluetooth_messenger.data.security.MessageCrypto
import com.example.bluetooth_messenger.domain.model.PeerIdentity
import com.example.bluetooth_messenger.domain.model.newId
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.time.AppClock
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SecurePacketsTest {
    private val clock =
        object : AppClock {
            override fun elapsedMillis() = 0L

            override fun wallMillis() = 1000L
        }

    private class SoftwareIdentity : IdentityCrypto {
        private val crypto = MessageCrypto()

        private fun pair() =
            KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()

        private val signing = pair()
        private val agreement = pair()
        val value = PeerIdentity(newId(), signing.public.encoded, agreement.public.encoded)

        override suspend fun identity() = value

        override suspend fun sign(bytes: ByteArray) = crypto.sign(bytes, signing.private)

        override suspend fun decrypt(bytes: ByteArray, associatedData: ByteArray) =
            crypto.decrypt(bytes, agreement.private, associatedData)
    }

    @Test
    fun onlyIntendedRecipientCanOpenAuthenticatedMessage() =
        runBlocking<Unit> {
            val a = SoftwareIdentity()
            val b = SoftwareIdentity()
            val packet =
                SecurePackets(a, clock)
                    .direct(PacketKind.Direct, b.value, newId(), "secret".toByteArray())
            assertArrayEquals("secret".toByteArray(), SecurePackets(b, clock).open(packet, a.value))
            assertThrows(Exception::class.java) {
                runBlocking { SecurePackets(SoftwareIdentity(), clock).open(packet, a.value) }
            }
        }

    @Test
    fun groupKeyAloneCannotImpersonateAnotherMember() =
        runBlocking<Unit> {
            val author = SoftwareIdentity()
            val attacker = SoftwareIdentity()
            val receiver = SoftwareIdentity()
            val group = newId()
            val key = ByteArray(32) { 7 }
            val codec = PacketCodec()
            val header =
                WirePacket(
                    PacketKind.GroupMessage,
                    newId(),
                    author.value.userId,
                    group,
                    group,
                    epoch = 1,
                    timestamp = 1000,
                    body = byteArrayOf(),
                )
            val encrypted =
                header.copy(
                    body = MessageCrypto().seal("forged".toByteArray(), key, codec.unsigned(header))
                )
            val forged = encrypted.copy(signature = attacker.sign(codec.unsigned(encrypted)))
            assertThrows(IllegalArgumentException::class.java) {
                SecurePackets(receiver, clock).openGroup(forged, author.value, key)
            }
        }

    @Test
    fun publicRecipientKeyAloneCannotImpersonateTrustedContact() =
        runBlocking<Unit> {
            val author = SoftwareIdentity()
            val attacker = SoftwareIdentity()
            val receiver = SoftwareIdentity()
            val codec = PacketCodec()
            val header =
                WirePacket(
                    PacketKind.Direct,
                    newId(),
                    author.value.userId,
                    receiver.value.userId,
                    newId(),
                    timestamp = 1000,
                    body = byteArrayOf(),
                )
            val crypto = MessageCrypto()
            val encrypted =
                header.copy(
                    body =
                        crypto.encrypt(
                            "forged".toByteArray(),
                            crypto.decodeKey(receiver.value.agreementKey),
                            codec.unsigned(header),
                        )
                )
            val forged = encrypted.copy(signature = attacker.sign(codec.unsigned(encrypted)))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { SecurePackets(receiver, clock).open(forged, author.value) }
            }
        }

    @Test
    fun validGroupMessageOpensOnlyWithItsEpochKey() =
        runBlocking<Unit> {
            val a = SoftwareIdentity()
            val b = SoftwareIdentity()
            val key = ByteArray(32) { 7 }
            val packet =
                SecurePackets(a, clock).group(newId(), 1, key, "hello".toByteArray(), newId())
            assertArrayEquals(
                "hello".toByteArray(),
                SecurePackets(b, clock).openGroup(packet, a.value, key),
            )
            assertThrows(Exception::class.java) {
                SecurePackets(b, clock).openGroup(packet, a.value, ByteArray(32) { 8 })
            }
        }
}
