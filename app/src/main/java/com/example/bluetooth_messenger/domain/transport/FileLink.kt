package com.example.bluetooth_messenger.domain.transport

import java.net.InetAddress

interface FileLink {
    suspend fun becomeOwner(userId: String): InetAddress
    suspend fun connectTo(userId: String): InetAddress
    suspend fun stop()
}
