package com.kompressorlink.app.telemetry.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

// The ~250-line raw wrapper the design chose over a BLE library (spec,
// decision 6). Every known sharp edge is explicit and tagged:
//  - TRANSPORT_LE on connect: without it some stacks (Samsung included)
//    may try a BR/EDR connection and fail with status 133. [Likely —
//    community-established; unverifiable before the final session]
//  - Status-133 retries live in BleTelemetrySource's backoff (every
//    Disconnected event, whatever its status, schedules a retry).
//  - Operation serialization is the state machine's job (Task 18 note).
// BLUETOOTH_CONNECT is requested by MainActivity before any of this runs;
// SecurityException here means the user denied it — surfaced as
// OperationFailed, not a crash.
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val macAddress: String,
) : GattLink {

    companion object {
        // Real Android GATT status codes are non-negative (GATT_SUCCESS = 0,
        // GATT_FAILURE = 257, error codes from BluetoothGatt, etc.). -1 can't
        // collide with any of them, so it clearly reads as "synthetic, no
        // real GATT status" wherever it shows up in logs.
        private const val NO_GATT_STATUS = -1
    }

    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<GattEvent> = _events

    @Volatile private var gatt: BluetoothGatt? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _events.tryEmit(GattEvent.Connected)
            } else {
                g.close()
                gatt = null
                _events.tryEmit(GattEvent.Disconnected(status))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            _events.tryEmit(GattEvent.MtuChanged(mtu))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _events.tryEmit(GattEvent.ServicesDiscovered)
            } else {
                _events.tryEmit(GattEvent.OperationFailed("discoverServices status=$status"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _events.tryEmit(GattEvent.NotifyEnabled(d.characteristic.uuid.toString()))
            } else {
                _events.tryEmit(GattEvent.OperationFailed("writeDescriptor ${d.characteristic.uuid} status=$status"))
            }
        }

        // API 33+ overload (value passed directly).
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            _events.tryEmit(GattEvent.CharacteristicChanged(ch.uuid.toString(), value))
        }

        // Pre-33 overload — reads ch.value. Both overrides kept because
        // minSdk 26 still compiles this path.
        @Deprecated("pre-API33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                _events.tryEmit(
                    GattEvent.CharacteristicChanged(ch.uuid.toString(), ch.value ?: ByteArray(0))
                )
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            _events.tryEmit(GattEvent.WriteCompleted)
        }
    }

    override fun connect() {
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = manager.adapter.getRemoteDevice(macAddress)
            gatt = device.connectGatt(context, /*autoConnect=*/false, callback,
                                      BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _events.tryEmit(GattEvent.OperationFailed("connect: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            _events.tryEmit(GattEvent.OperationFailed("bad address: ${e.message}"))
        }
    }

    override fun disconnect() {
        val g = gatt
        if (g == null) {
            // Nothing was ever connected (e.g. connect() failed before gatt
            // was assigned) -- there is no pending onConnectionStateChange
            // callback to wait for, so synthesize the Disconnected event
            // ourselves. Without this, BleTelemetrySource's retry-on-
            // Disconnected logic would never fire and the state machine
            // would get stuck in Connecting forever.
            _events.tryEmit(GattEvent.Disconnected(NO_GATT_STATUS))
            return
        }
        // Let onConnectionStateChange do the close() + event emission once
        // Android confirms the disconnect asynchronously -- calling close()
        // immediately here is a known anti-pattern that can suppress/delay
        // that callback.
        g.disconnect()
    }

    override fun requestMtu(mtu: Int) {
        if (gatt?.requestMtu(mtu) != true) {
            _events.tryEmit(GattEvent.OperationFailed("requestMtu"))
        }
    }

    override fun discoverServices() {
        if (gatt?.discoverServices() != true) {
            _events.tryEmit(GattEvent.OperationFailed("discoverServices"))
        }
    }

    override fun enableNotifications(charUuid: String) {
        val g = gatt ?: run {
            _events.tryEmit(GattEvent.OperationFailed("no gatt"))
            return
        }
        val ch = g.getService(UUID.fromString(KlUuids.SERVICE))
            ?.getCharacteristic(UUID.fromString(charUuid))
            ?: run {
                _events.tryEmit(GattEvent.OperationFailed("char $charUuid missing"))
                return
            }
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID.fromString(KlUuids.CCCD))
            ?: run {
                _events.tryEmit(GattEvent.OperationFailed("CCCD missing on $charUuid"))
                return
            }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }
        if (!ok) _events.tryEmit(GattEvent.OperationFailed("writeDescriptor $charUuid"))
    }

    override fun writeControl(value: ByteArray) {
        val g = gatt ?: run {
            _events.tryEmit(GattEvent.OperationFailed("no gatt"))
            return
        }
        val ch = g.getService(UUID.fromString(KlUuids.SERVICE))
            ?.getCharacteristic(UUID.fromString(KlUuids.CONTROL))
            ?: run {
                _events.tryEmit(GattEvent.OperationFailed("control char missing"))
                return
            }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.value = value
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) _events.tryEmit(GattEvent.OperationFailed("writeControl"))
    }
}
