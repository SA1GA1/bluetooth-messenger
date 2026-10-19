package com.example.bluetooth_messenger.data.protocol

import com.example.bluetooth_messenger.domain.model.PeerIdentity
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class IdentityCodecTest {
    private val codec = IdentityCodec()
    private fun publicKey(curve: String = "secp256r1") = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(curve))
    }.generateKeyPair().public.encoded
    private fun identity() = PeerIdentity("ea01bde3-462c-4793-a532-54c02cc94699", publicKey(), publicKey())

    @Test fun roundTripPreservesBothPublicKeysAndFullUuid() {
        val source = identity()
        val encoded = codec.encode(source)
        assertTrue(encoded.startsWith("offgrid:1:"))
        val decoded = codec.decode(encoded)
        assertEquals(source.userId, decoded.userId)
        assertArrayEquals(source.signingKey, decoded.signingKey)
        assertArrayEquals(source.agreementKey, decoded.agreementKey)
    }
    @Test fun refusesOtherAppsAndOversizedScans() {
        assertThrows(IllegalArgumentException::class.java) { codec.decode("https://example.com") }
        assertThrows(IllegalArgumentException::class.java) { codec.decode("offgrid:1:" + "A".repeat(2000)) }
    }
    @Test fun rejectsUnsupportedCurve() {
        val source = identity().copy(agreementKey = publicKey("secp384r1"))
        assertThrows(IllegalArgumentException::class.java) { codec.encode(source) }
    }
    @Test fun rejectsShortIdAndInvalidKey() {
        assertThrows(IllegalArgumentException::class.java) { codec.encode(identity().copy(userId = "short")) }
        assertThrows(Exception::class.java) { codec.encode(identity().copy(signingKey = byteArrayOf(1))) }
    }
}
