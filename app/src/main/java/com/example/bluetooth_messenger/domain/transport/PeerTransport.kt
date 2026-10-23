package com.example.bluetooth_messenger.domain.transport

import com.example.bluetooth_messenger.domain.model.DiscoveredPeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface TransportEvent {
    data class Packet(val peerId: String, val bytes: ByteArray) : TransportEvent
    data class Failure(val peerId: String?, val description: String) : TransportEvent
}

interface DiscoveryService {
    val peers: StateFlow<List<DiscoveredPeer>>
    suspend fun start(userId: String)
    fun stop()
}

interface PeerTransport {
    val events: Flow<TransportEvent>
    suspend fun send(peerId: String, packet: ByteArray)
    fun stop()
}

class PeerUnavailableException(message: String) : IllegalStateException(message)
