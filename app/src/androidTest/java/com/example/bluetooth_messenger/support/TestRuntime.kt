package com.example.bluetooth_messenger.support

import com.example.bluetooth_messenger.domain.model.DiscoveredPeer
import com.example.bluetooth_messenger.domain.repository.AppSettings
import com.example.bluetooth_messenger.domain.repository.SettingsRepository
import com.example.bluetooth_messenger.domain.time.AppClock
import com.example.bluetooth_messenger.domain.transport.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class TestClock : AppClock {
    @Volatile var elapsed = 0L
    @Volatile var wallShift = 0L
    override fun elapsedMillis() = elapsed
    override fun wallMillis() = 1_000_000L + elapsed + wallShift
}
class TestSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    override val settings = MutableStateFlow(initial)
    override suspend fun setTimeout(minutes: Int) { settings.value = settings.value.copy(timeoutMinutes = minutes) }
    override suspend fun setReadReceipts(enabled: Boolean) { settings.value = settings.value.copy(readReceipts = enabled) }
    override suspend fun setPublicMode(enabled: Boolean) { settings.value = settings.value.copy(publicMode = enabled) }
}
class TestRadio : DiscoveryService, PeerTransport {
    override val peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val events = MutableSharedFlow<TransportEvent>()
    var startFailure: Exception? = null
    override suspend fun start(userId: String) { startFailure?.let { throw it } }
    override fun stop() { peers.value = emptyList() }
    override suspend fun send(peerId: String, packet: ByteArray): Unit = error("Radio is offline in session tests")
}
