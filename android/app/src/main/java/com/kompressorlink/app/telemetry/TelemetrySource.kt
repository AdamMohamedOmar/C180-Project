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

    /** False/no-op on fake sources. On BLE, returns true only if the write
     *  was issued while the connection was Ready — this is NOT an
     *  acknowledgment from the device; the GATT write completes
     *  asynchronously and its result is not surfaced by this call. */
    suspend fun requestWifiSync(): Boolean
}
