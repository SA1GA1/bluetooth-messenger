package com.example.bluetooth_messenger.data.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import com.example.bluetooth_messenger.domain.model.Limits
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

@SuppressLint("MissingPermission")
class GattPacketSender(private val context: Context) {
    private val mutex = Mutex()
    private val random = SecureRandom()
    private var active: BluetoothGatt? = null
    private var activeJob: Job? = null

    suspend fun send(device: BluetoothDevice, bytes: ByteArray) = mutex.withLock {
        require(bytes.isNotEmpty() && bytes.size <= Limits.PACKET_BYTES)
        coroutineScope {
            activeJob = currentCoroutineContext().job
            val ready = CompletableDeferred<BluetoothGattCharacteristic>()
            var write = CompletableDeferred<Unit>()
            var mtu = 23
            val callback =
                object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        state: Int,
                    ) {
                        if (
                            status == BluetoothGatt.GATT_SUCCESS &&
                                state == BluetoothProfile.STATE_CONNECTED
                        ) {
                            runCatching {
                                    if (!gatt.requestMtu(517)) check(gatt.discoverServices())
                                }
                                .onFailure { ready.completeExceptionally(it) }
                        } else if (
                            state == BluetoothProfile.STATE_DISCONNECTED ||
                                status != BluetoothGatt.GATT_SUCCESS
                        ) {
                            val failure = IllegalStateException("BLE-соединение прервано ($status)")
                            ready.completeExceptionally(failure)
                            write.completeExceptionally(failure)
                        }
                    }

                    override fun onMtuChanged(gatt: BluetoothGatt, value: Int, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) mtu = value.coerceIn(23, 517)
                        runCatching { check(gatt.discoverServices()) { "Сервис BLE недоступен" } }
                            .onFailure { ready.completeExceptionally(it) }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        val characteristic = gatt.getService(SERVICE)?.getCharacteristic(RECEIVE)
                        if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null)
                            ready.complete(characteristic)
                        else
                            ready.completeExceptionally(
                                IllegalStateException("Сервис OffGrid не найден")
                            )
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS) write.complete(Unit)
                        else
                            write.completeExceptionally(
                                IllegalStateException("Ошибка передачи BLE ($status)")
                            )
                    }
                }
            val gatt =
                checkNotNull(
                    device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                )
            active = gatt
            try {
                withTimeout(60_000) {
                    val characteristic = ready.await()
                    for (frame in BleFrames().split(bytes, mtu, random.nextInt())) {
                        write = CompletableDeferred()
                        @Suppress("DEPRECATION")
                        val accepted =
                            if (Build.VERSION.SDK_INT >= 33) {
                                gatt.writeCharacteristic(
                                    characteristic,
                                    frame,
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                                ) == BluetoothStatusCodes.SUCCESS
                            } else {
                                characteristic.writeType =
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                characteristic.value = frame
                                gatt.writeCharacteristic(characteristic)
                            }
                        check(accepted) { "BLE занят" }
                        withTimeout(5_000) { write.await() }
                    }
                }
            } finally {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                if (active === gatt) {
                    active = null
                    activeJob = null
                }
            }
        }
    }

    fun stop() {
        activeJob?.cancel()
        runCatching { active?.disconnect() }
        runCatching { active?.close() }
        active = null
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("910a7dd0-1ea9-4dc8-a718-9ead69b44128")
        val RECEIVE: UUID = UUID.fromString("910a7dd1-1ea9-4dc8-a718-9ead69b44128")
    }
}
