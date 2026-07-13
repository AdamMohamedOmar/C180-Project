package com.kompressorlink.app.telemetry.ble

import kotlinx.coroutines.flow.SharedFlow

// GATT identity — mirror-verbatim of docs/ble_protocol.md (the firmware
// twin is firmware/src/ble_svc.h).
object KlUuids {
    const val SERVICE = "c1800001-4b4c-4d27-b946-c180c0deba5e"
    const val TELEMETRY = "c1800002-4b4c-4d27-b946-c180c0deba5e"
    const val DTC = "c1800003-4b4c-4d27-b946-c180c0deba5e"
    const val CONTROL = "c1800004-4b4c-4d27-b946-c180c0deba5e"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
}

sealed interface GattEvent {
    data object Connected : GattEvent
    data class Disconnected(val status: Int) : GattEvent
    data object ServicesDiscovered : GattEvent
    data class MtuChanged(val mtu: Int) : GattEvent
    data class NotifyEnabled(val charUuid: String) : GattEvent
    data class CharacteristicChanged(val charUuid: String, val value: ByteArray) : GattEvent
    data object WriteCompleted : GattEvent
    /** A GATT call could not even be issued (missing service/char, etc.). */
    data class OperationFailed(val what: String) : GattEvent
}

// Everything BleTelemetrySource needs from the platform, as events —
// GattClient implements it for real; tests drive a fake (spec §4.1's
// testability seam, one level deeper).
interface GattLink {
    val events: SharedFlow<GattEvent>
    fun connect()
    fun disconnect()
    fun requestMtu(mtu: Int)
    fun discoverServices()
    fun enableNotifications(charUuid: String)
    fun writeControl(value: ByteArray)
}
