package com.example.bluetooth_messenger.data.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.SystemClock
import com.example.bluetooth_messenger.domain.model.DiscoveredPeer
import com.example.bluetooth_messenger.domain.transport.DiscoveryService
import com.example.bluetooth_messenger.domain.transport.PeerTransport
import com.example.bluetooth_messenger.domain.transport.TransportEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SuppressLint("MissingPermission")
class AndroidBleRadio @Inject constructor(@ApplicationContext private val context: Context) : DiscoveryService, PeerTransport {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sender = GattPacketSender(context)
    private val frames = BleFrames()
    private val mutablePeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers = mutablePeers.asStateFlow()
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 128)
    override val events = mutableEvents.asSharedFlow()
    @Volatile private var server: BluetoothGattServer? = null
    private var expiry: Job? = null
    @Volatile private var generation = 0
    @Volatile private var ownId: String? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private fun failure(reason: String) { mutableEvents.tryEmit(TransportEvent.Failure(null, reason)) }

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(0xffff) ?: return
            if (data.size != 18 || data[0] != 0x4f.toByte() || data[1] != 1.toByte()) return
            val buffer = ByteBuffer.wrap(data, 2, 16)
            val id = UUID(buffer.long, buffer.long).toString()
            if (id == ownId || ownId == null) return
            val peer = DiscoveredPeer(id, result.device.address, SystemClock.elapsedRealtime())
            mutablePeers.update { list -> (list.filterNot { it.userId == id } + peer).sortedByDescending { it.lastSeenMillis }.take(64) }
        }
        override fun onScanFailed(errorCode: Int) { failure("Сканирование BLE недоступно ($errorCode)") }
    }

    private val advertising = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { failure("Маяк BLE недоступен ($errorCode)") }
    }

    private val receiving = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val valid = !preparedWrite && offset == 0 && characteristic.uuid == GattPacketSender.RECEIVE && ownId != null
            if (valid) {
                val packet = frames.accept(device.address, value, SystemClock.elapsedRealtime())
                if (packet != null) {
                    val source = peers.value.firstOrNull { it.address == device.address }?.userId ?: device.address
                    mutableEvents.tryEmit(TransportEvent.Packet(source, packet))
                }
            }
            if (responseNeeded) runCatching {
                server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }
    }

    override suspend fun start(userId: String) {
        stop()
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE).forEach {
            check(context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) { "Разрешите доступ к Bluetooth" }
        }
        val adapter = checkNotNull(manager?.adapter) { "Bluetooth не поддерживается" }
        check(adapter.isEnabled) { "Включите Bluetooth" }
        val id = UUID.fromString(userId)
        scanner = checkNotNull(adapter.bluetoothLeScanner) { "Сканирование BLE не поддерживается" }
        advertiser = checkNotNull(adapter.bluetoothLeAdvertiser) { "Маяк BLE не поддерживается" }
        ownId = userId
        try {
            val ready = CompletableDeferred<Unit>()
            val expectedGeneration = generation
            val callback = object : BluetoothGattServerCallback() {
                override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                    if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
                    else ready.completeExceptionally(IllegalStateException("Не удалось открыть сервис BLE ($status)"))
                }
                override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
                    characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
                    responseNeeded: Boolean, offset: Int, value: ByteArray) {
                    if (expectedGeneration == generation) receiving.onCharacteristicWriteRequest(device, requestId,
                        characteristic, preparedWrite, responseNeeded, offset, value)
                }
            }
            server = checkNotNull(manager.openGattServer(context, callback))
            val service = BluetoothGattService(GattPacketSender.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(GattPacketSender.RECEIVE,
                BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            check(server!!.addService(service)) { "Не удалось открыть сервис BLE" }
            withTimeout(5_000) { ready.await() }
            val beacon = ByteBuffer.allocate(18).put(0x4f.toByte()).put(1.toByte())
                .putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
            advertiser!!.startAdvertising(AdvertiseSettings.Builder().setConnectable(true)
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).build(),
                AdvertiseData.Builder().addManufacturerData(0xffff, beacon).build(),
                AdvertiseData.Builder().addServiceUuid(ParcelUuid(GattPacketSender.SERVICE)).build(), advertising)
            scanner!!.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GattPacketSender.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
            expiry = scope.launch {
                while (isActive) {
                    delay(5_000)
                    val now = SystemClock.elapsedRealtime()
                    mutablePeers.update { list -> list.filter { now - it.lastSeenMillis < 30_000 } }
                }
            }
        } catch (failure: Exception) { stop(); throw failure }
    }

    override suspend fun send(peerId: String, packet: ByteArray) {
        check(ownId != null) { "Связь приостановлена" }
        val peer = peers.value.firstOrNull { it.userId == peerId } ?: error("Получатель вне зоны")
        sender.send(manager.adapter.getRemoteDevice(peer.address), packet)
    }

    override fun stop() {
        generation++
        ownId = null
        expiry?.cancel()
        expiry = null
        sender.stop()
        runCatching { scanner?.stopScan(scan) }
        runCatching { advertiser?.stopAdvertising(advertising) }
        runCatching { server?.close() }
        scanner = null
        advertiser = null
        server = null
        frames.clear()
        mutablePeers.value = emptyList()
    }
}
