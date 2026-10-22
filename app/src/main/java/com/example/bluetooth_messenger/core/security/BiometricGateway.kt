package com.example.bluetooth_messenger.core.security

import javax.crypto.Cipher

interface BiometricGateway {
    suspend fun prepare(enrollment: Boolean): Cipher
    suspend fun complete(cipher: Cipher, enrollment: Boolean)
}
