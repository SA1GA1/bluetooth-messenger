package com.example.bluetooth_messenger.domain.repository

interface IdentityKeyRepository {
    suspend fun hasIdentityKeys(): Boolean
    suspend fun generateIdentityKeys()
}