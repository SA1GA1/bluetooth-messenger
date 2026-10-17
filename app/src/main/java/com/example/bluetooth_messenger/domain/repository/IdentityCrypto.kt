package com.example.bluetooth_messenger.domain.repository

import com.example.bluetooth_messenger.domain.model.PeerIdentity

interface IdentityCrypto {
    suspend fun identity(): PeerIdentity
    suspend fun sign(bytes: ByteArray): ByteArray
    suspend fun decrypt(bytes: ByteArray, associatedData: ByteArray): ByteArray
}
