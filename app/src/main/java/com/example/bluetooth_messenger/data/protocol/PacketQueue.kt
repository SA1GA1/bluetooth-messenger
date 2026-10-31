package com.example.bluetooth_messenger.data.protocol

import com.example.bluetooth_messenger.data.local.database.DatabaseSession
import com.example.bluetooth_messenger.data.local.database.OutboxEntity
import com.example.bluetooth_messenger.domain.time.AppClock
import com.example.bluetooth_messenger.domain.transport.PeerTransport
import com.example.bluetooth_messenger.domain.transport.PeerUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PacketQueue @Inject constructor(private val database: DatabaseSession, private val transport: PeerTransport, private val clock: AppClock) {
    private val codec = PacketCodec()
    private val sending = Mutex()

    fun entry(packet: WirePacket, target: String = packet.target, message: String? = null) =
        OutboxEntity(packet.id, target, message, codec.encode(packet), controlScope = if (message == null) packet.conversation else null, wireKind = packet.kind.name)

    suspend fun add(packet: WirePacket, target: String = packet.target, message: String? = null) {
        database.use { it.messages().queue(listOf(entry(packet, target, message))) }
    }

    suspend fun ephemeral(packet: WirePacket) {
        try { transport.send(packet.target, codec.encode(packet)) }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { }
    }

    suspend fun flush(peers: List<String>, canSend: suspend (WirePacket, String) -> Boolean = { _, _ -> true }) {
        if (!sending.tryLock()) return
        try {
            for (peer in peers) {
                database.use { it.messages().retryControl(peer) }
                val deliveries = database.use { it.messages().pending(peer) }
                for (delivery in deliveries) {
                    try {
                        if (!canSend(codec.decode(delivery.packet), peer)) continue
                        transport.send(peer, delivery.packet)
                        database.use { it.messages().sent(delivery.packetId, peer, clock.elapsedMillis()) }
                    } catch (failure: CancellationException) { throw failure }
                    catch (_: PeerUnavailableException) { break }
                    catch (_: Exception) {
                        database.use { it.messages().deliveryError(delivery.packetId, peer) }
                        break
                    }
                }
            }
        } finally { sending.unlock() }
    }
}
