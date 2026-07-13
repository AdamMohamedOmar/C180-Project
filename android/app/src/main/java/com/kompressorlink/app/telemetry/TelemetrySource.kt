package com.kompressorlink.app.telemetry

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// The load-bearing seam (spec §4.1): ViewModels only ever see this.
// Implementations: FakeTelemetrySource (a later task), BleTelemetrySource
// (a later task), SourceSwitcher (a later task, delegates to whichever is active).
interface TelemetrySource {
    val connectionState: StateFlow<ConnectionState>
    val telemetry: Flow<TelemetrySnapshot>
    val dtcReport: StateFlow<DtcReport?>

    /** No-op on fake sources; writes the control frame on BLE. */
    suspend fun sendTimeSync()
}
