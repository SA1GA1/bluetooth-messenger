package com.example.bluetooth_messenger.support

import com.example.bluetooth_messenger.data.security.MessageCrypto
import com.example.bluetooth_messenger.domain.model.PeerIdentity
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.repository.IdentityKeyRepository
import kotlinx.coroutines.CompletableDeferred
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class TestIdentity(userId: String = UUID.randomUUID().toString()) : IdentityCrypto, IdentityKeyRepository {
    private val crypto = MessageCrypto()
    private fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val signing = pair()
    private val agreement = pair()
    private val value = PeerIdentity(userId, signing.public.encoded, agreement.public.encoded)
    var generationGate: CompletableDeferred<Unit>? = null
    override suspend fun identity(): PeerIdentity = value
    override suspend fun hasIdentityKeys(): Boolean = true
    override suspend fun generateIdentityKeys() { generationGate?.await() }
    override suspend fun sign(bytes: ByteArray) = crypto.sign(bytes, signing.private)
    override suspend fun decrypt(bytes: ByteArray, associatedData: ByteArray) = crypto.decrypt(bytes, agreement.private, associatedData)
}
