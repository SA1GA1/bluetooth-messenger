package com.example.bluetooth_messenger.data.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Native, foreground-only Wi-Fi Direct session. The caller authenticates the socket peer: DNS-SD
 * TXT identity selects a candidate and is not itself proof of identity.
 */
@Singleton
@SuppressLint("MissingPermission")
class AndroidWifiDirect @Inject constructor(@ApplicationContext private val context: Context) :
    com.example.bluetooth_messenger.domain.transport.FileLink {
    private val lifecycle = Mutex()
    private var generation = 0L
    private var session: Session? = null

    private class Session(val generation: Long, val manager: WifiP2pManager) {
        lateinit var channel: WifiP2pManager.Channel
        var receiver: BroadcastReceiver? = null
        var operation: Deferred<InetAddress>? = null
        var service: WifiP2pDnsSdServiceInfo? = null
        var request: WifiP2pDnsSdServiceRequest? = null
        var discoveryStarted = false
        var createAccepted = false
        var connectIssued = false
        var targetAddress: String? = null
        var closed = false
        val pending = mutableListOf<CompletableDeferred<Unit>>()
    }

    override suspend fun becomeOwner(userId: String): InetAddress =
        start(userId) { s ->
            action(s, "create group", { s.createAccepted = true }) {
                s.manager.createGroup(s.channel, it)
            }
            val address = awaitAddress(s, owner = true)
            s.service =
                WifiP2pDnsSdServiceInfo.newInstance(
                    INSTANCE,
                    SERVICE,
                    mapOf("id" to userId, "v" to "1"),
                )
            action(s, "publish service") { s.manager.addLocalService(s.channel, s.service, it) }
            address
        }

    override suspend fun connectTo(userId: String): InetAddress =
        start(userId) { s ->
            val found = CompletableDeferred<String>()
            s.manager.setDnsSdResponseListeners(
                s.channel,
                { _, _, _ -> },
                { domain, txt, device ->
                    if (
                        isCurrent(s) &&
                            !found.isCompleted &&
                            domain
                                .trimEnd('.')
                                .equals("$INSTANCE.$SERVICE.local", ignoreCase = true) &&
                            txt["id"] == userId &&
                            txt["v"] == "1"
                    ) {
                        found.complete(device.deviceAddress)
                    }
                },
            )
            s.request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE)
            action(s, "add discovery request") {
                s.manager.addServiceRequest(s.channel, s.request, it)
            }
            // Mark before issuing: cancellation must also stop discovery whose callback is late.
            s.discoveryStarted = true
            action(s, "discover services") { s.manager.discoverServices(s.channel, it) }
            val target = withTimeout(DISCOVERY_TIMEOUT) { found.await() }
            s.targetAddress = target
            action(s, "stop discovery") { s.manager.stopPeerDiscovery(s.channel, it) }
            s.discoveryStarted = false
            val config =
                WifiP2pConfig().apply {
                    deviceAddress = target
                    wps.setup = WpsInfo.PBC
                    groupOwnerIntent = 0
                }
            // Refuse a group that appeared while discovery was in progress, too.
            check(group(s) == null) { "Wi-Fi Direct is busy with another group" }
            s.connectIssued = true
            action(s, "connect") { s.manager.connect(s.channel, config, it) }
            awaitAddress(s, owner = false)
        }

    /** Cancels discovery/connection waits before taking the lifecycle lock. */
    override suspend fun stop() =
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            session?.operation?.cancel(CancellationException("Wi-Fi Direct stopped"))
            lifecycle.withLock { session?.let { cleanup(it) } }
            Unit
        }

    private suspend fun start(
        userId: String,
        block: suspend (Session) -> InetAddress,
    ): InetAddress {
        var started: Session? = null
        try {
            return withContext(Dispatchers.Main.immediate) {
                lifecycle.withLock {
                    check(session == null) { "Wi-Fi Direct session is already active" }
                    require(UUID.fromString(userId).toString() == userId) {
                        "Expected a canonical full user UUID"
                    }
                    checkPermissions()
                    val manager =
                        checkNotNull(context.getSystemService(WifiP2pManager::class.java)) {
                            "Wi-Fi Direct is not supported"
                        }
                    val s = Session(++generation, manager)
                    s.channel =
                        checkNotNull(
                            manager.initialize(context, Looper.getMainLooper()) {
                                if (isCurrent(s))
                                    s.operation?.cancel(
                                        CancellationException("Wi-Fi Direct channel lost")
                                    )
                            }
                        ) {
                            "Cannot initialize Wi-Fi Direct"
                        }
                    session = s
                    started = s
                    try {
                        register(s)
                        coroutineScope {
                            val worker =
                                async(start = CoroutineStart.LAZY) {
                                    withTimeout(SESSION_TIMEOUT) {
                                        check(group(s) == null) {
                                            "Wi-Fi Direct is busy with another group"
                                        }
                                        block(s)
                                    }
                                }
                            s.operation = worker
                            worker.await()
                        }
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) { cleanup(s) }
                        throw failure
                    }
                }
            }
        } catch (failure: Throwable) {
            // withContext can discard a successful result when the caller is cancelled
            // while dispatching it back. That path must also release the created session.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                lifecycle.withLock { started?.let { cleanup(it) } }
            }
            throw failure
        }
    }

    private fun checkPermissions() {
        val permission =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            "Wi-Fi Direct permission is required"
        }
        if (Build.VERSION.SDK_INT < 33) {
            check(
                context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
            ) {
                "Enable location services for Wi-Fi Direct discovery"
            }
        }
    }

    private fun register(s: Session) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (
                        isCurrent(s) &&
                            intent.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION &&
                            intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                                WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    ) {
                        s.operation?.cancel(CancellationException("Wi-Fi Direct disabled"))
                    }
                }
            }
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        s.receiver = receiver
    }

    private fun isCurrent(s: Session) = session === s && generation == s.generation && !s.closed

    private suspend fun action(
        s: Session,
        name: String,
        accepted: () -> Unit = {},
        issue: (WifiP2pManager.ActionListener) -> Unit,
    ) {
        val result = CompletableDeferred<Unit>()
        s.pending += result
        issue(
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isCurrent(s)) return
                    accepted()
                    result.complete(Unit)
                }

                override fun onFailure(reason: Int) {
                    if (isCurrent(s))
                        result.completeExceptionally(
                            IllegalStateException("Wi-Fi Direct $name failed ($reason)")
                        )
                }
            }
        )
        try {
            withTimeout(ACTION_TIMEOUT) { result.await() }
        } finally {
            if (result.isCompleted) s.pending.remove(result)
        }
    }

    private suspend fun group(s: Session): WifiP2pGroup? =
        withTimeout(ACTION_TIMEOUT) {
            val result = CompletableDeferred<WifiP2pGroup?>()
            try {
                s.manager.requestGroupInfo(s.channel) { if (isCurrent(s)) result.complete(it) }
                result.await()
            } finally {
                result.cancel()
            }
        }

    private suspend fun info(s: Session): WifiP2pInfo =
        withTimeout(ACTION_TIMEOUT) {
            val result = CompletableDeferred<WifiP2pInfo>()
            try {
                s.manager.requestConnectionInfo(s.channel) { if (isCurrent(s)) result.complete(it) }
                result.await()
            } finally {
                result.cancel()
            }
        }

    private suspend fun awaitAddress(s: Session, owner: Boolean): InetAddress =
        withTimeout(CONNECTION_TIMEOUT) {
            while (true) {
                val connection = info(s)
                val address = connection.groupOwnerAddress
                if (
                    connection.groupFormed &&
                        connection.isGroupOwner == owner &&
                        address is Inet4Address &&
                        !address.isAnyLocalAddress &&
                        !address.isLoopbackAddress
                ) {
                    val currentGroup = group(s)
                    if (
                        currentGroup != null &&
                            (owner ||
                                currentGroup.owner
                                    ?.deviceAddress
                                    .equals(s.targetAddress, ignoreCase = true))
                    ) {
                        return@withTimeout address
                    }
                }
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") error("No Wi-Fi Direct address")
        }

    private suspend fun cleanup(s: Session) {
        if (!isCurrent(s)) return
        // Drain accepted platform commands before removing their resources. This lock also
        // prevents an old cleanup from removing the next transfer's group or service.
        withTimeoutOrNull(ACTION_TIMEOUT) {
            s.pending.toList().forEach { runCatching { it.await() } }
        }
        if (s.connectIssued) cleanupAction(s) { s.manager.cancelConnect(s.channel, it) }
        s.request?.let { request ->
            cleanupAction(s) { s.manager.removeServiceRequest(s.channel, request, it) }
        }
        s.service?.let { service ->
            cleanupAction(s) { s.manager.removeLocalService(s.channel, service, it) }
        }
        if (s.discoveryStarted) cleanupAction(s) { s.manager.stopPeerDiscovery(s.channel, it) }
        val currentGroup = runCatching { group(s) }.getOrNull()
        val ours =
            currentGroup != null &&
                ((s.createAccepted && currentGroup.isGroupOwner) ||
                    (s.connectIssued &&
                        currentGroup.owner
                            ?.deviceAddress
                            .equals(s.targetAddress, ignoreCase = true)))
        if (ours) cleanupAction(s) { s.manager.removeGroup(s.channel, it) }
        s.closed = true
        s.pending.forEach { it.cancel() }
        s.pending.clear()
        s.receiver?.let { runCatching { context.unregisterReceiver(it) } }
        s.receiver = null
        runCatching { s.channel.close() }
        if (session === s) {
            session = null
            generation++
        }
    }

    private suspend fun cleanupAction(s: Session, issue: (WifiP2pManager.ActionListener) -> Unit) {
        runCatching { action(s, "cleanup", issue = issue) }
    }

    private companion object {
        const val INSTANCE = "OffGrid"
        const val SERVICE = "_offgrid._tcp"
        const val ACTION_TIMEOUT = 5_000L
        const val DISCOVERY_TIMEOUT = 35_000L
        const val CONNECTION_TIMEOUT = 40_000L
        const val SESSION_TIMEOUT = 90_000L
    }
}
