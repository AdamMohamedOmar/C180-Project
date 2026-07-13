package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// The app's one long-lived REAL_BLE TelemetrySource. Creates/starts a
// BleTelemetrySource only while (choice == REAL_BLE && a MAC is stored);
// otherwise idles at NotAssociated. SourceSwitcher maps REAL_BLE to this.
@OptIn(ExperimentalCoroutinesApi::class)
class BleSession(
    scope: CoroutineScope,
    macFlow: Flow<String?>,
    choiceFlow: Flow<SourceChoice>,
    private val linkFactory: (String) -> GattLink,
) : TelemetrySource {

    private object Idle : TelemetrySource {
        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.NotAssociated)
        override val telemetry: Flow<TelemetrySnapshot> = emptyFlow()
        override val dtcReport: StateFlow<DtcReport?> = MutableStateFlow(null)
        override suspend fun sendTimeSync() {}
    }

    private val inner = MutableStateFlow<TelemetrySource>(Idle)

    init {
        scope.launch {
            combine(macFlow, choiceFlow) { mac, choice ->
                if (choice == SourceChoice.REAL_BLE) mac else null
            }.distinctUntilChanged().collect { mac ->
                (inner.value as? BleTelemetrySource)?.stop()
                inner.value = if (mac == null) {
                    Idle
                } else {
                    BleTelemetrySource(scope, linkFactory(mac)).also { it.start() }
                }
            }
        }
    }

    override val connectionState: StateFlow<ConnectionState> = inner
        .flatMapLatest { it.connectionState }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.NotAssociated)

    override val telemetry: Flow<TelemetrySnapshot> = inner.flatMapLatest { it.telemetry }

    override val dtcReport: StateFlow<DtcReport?> = inner
        .flatMapLatest { it.dtcReport }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun sendTimeSync() = inner.value.sendTimeSync()
}
