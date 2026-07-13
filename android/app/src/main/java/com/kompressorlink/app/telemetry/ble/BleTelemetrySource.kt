package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// The tested half of the raw-GATT decision: a strictly linear state machine
// over GattLink events (which is also the op serializer — Task 18 note).
// `now` injected for testable time-sync payloads.
class BleTelemetrySource(
    private val scope: CoroutineScope,
    private val link: GattLink,
    private val now: () -> Long = { System.currentTimeMillis() },
) : TelemetrySource {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableSharedFlow<TelemetrySnapshot>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val telemetry: Flow<TelemetrySnapshot> = _telemetry.asSharedFlow()

    private val _dtcReport = MutableStateFlow<DtcReport?>(null)
    override val dtcReport: StateFlow<DtcReport?> = _dtcReport.asStateFlow()

    private var retryAttempt = 0
    @Volatile private var stopped = false
    private var collectorJob: Job? = null
    private var retryJob: Job? = null

    fun start() {
        stopped = false
        collectorJob?.cancel()
        collectorJob = scope.launch { link.events.collect { onEvent(it) } }
        beginConnect()
    }

    fun stop() {
        stopped = true
        retryJob?.cancel()
        retryJob = null
        collectorJob?.cancel()
        collectorJob = null
        link.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun beginConnect() {
        _connectionState.value = ConnectionState.Connecting
        link.connect()
    }

    private suspend fun onEvent(event: GattEvent) {
        when (event) {
            GattEvent.Connected -> link.requestMtu(REQUESTED_MTU)

            is GattEvent.MtuChanged ->
                if (event.mtu >= MIN_MTU) {
                    link.discoverServices()
                } else {
                    // Contract: never stream through an MTU that would
                    // truncate the 88-byte frame (docs/ble_protocol.md).
                    link.disconnect()
                }

            GattEvent.ServicesDiscovered -> link.enableNotifications(KlUuids.TELEMETRY)

            is GattEvent.NotifyEnabled -> when (event.charUuid) {
                KlUuids.TELEMETRY -> link.enableNotifications(KlUuids.DTC)
                KlUuids.DTC -> {
                    retryAttempt = 0
                    _connectionState.value =
                        ConnectionState.Ready(demo = false, klineConnected = false)
                    sendTimeSync()
                }
            }

            is GattEvent.CharacteristicChanged -> when (event.charUuid) {
                KlUuids.TELEMETRY -> {
                    val snapshot = FrameCodec.parseTelemetry(event.value)
                    if (snapshot == null) {
                        _connectionState.value = ConnectionState.ProtocolMismatch
                    } else {
                        _connectionState.value =
                            ConnectionState.Ready(snapshot.isDemo, snapshot.klineConnected)
                        _telemetry.emit(snapshot)
                    }
                }
                KlUuids.DTC -> FrameCodec.parseDtcReport(event.value)?.let { _dtcReport.value = it }
            }

            is GattEvent.Disconnected -> {
                _connectionState.value = ConnectionState.Disconnected
                if (!stopped) scheduleRetry()
            }

            GattEvent.WriteCompleted -> Unit

            is GattEvent.OperationFailed -> link.disconnect()  // funnel into the retry path
        }
    }

    private fun scheduleRetry() {
        val delayMs = RETRY_BACKOFF_MS[minOf(retryAttempt, RETRY_BACKOFF_MS.lastIndex)]
        retryAttempt++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (!stopped) beginConnect()
        }
    }

    override suspend fun sendTimeSync() {
        if (_connectionState.value is ConnectionState.Ready) {
            link.writeControl(FrameCodec.buildTimeSync(now()))
        }
    }

    private companion object {
        const val REQUESTED_MTU = 517
        const val MIN_MTU = 91  // 88-byte frame + 3-byte ATT header
        // 1 s, 2 s, 5 s, then steady 15 s [Best estimate — covers the
        // status-133 flake pattern without hammering the radio].
        val RETRY_BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 15_000)
    }
}
