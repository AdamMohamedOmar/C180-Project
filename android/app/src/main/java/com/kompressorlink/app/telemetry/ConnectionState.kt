package com.kompressorlink.app.telemetry

// What the top-bar chip renders (spec §4.5) and what drives the foreground
// service notification text.
sealed interface ConnectionState {
    /** Real-BLE source selected but no CDM association exists yet. */
    data object NotAssociated : ConnectionState
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    /** Streaming. demo/klineConnected come from the frame's flag bits. */
    data class Ready(val demo: Boolean, val klineConnected: Boolean) : ConnectionState
    /** Frames arrive but with an unknown protocol_version — firmware/app mismatch. */
    data object ProtocolMismatch : ConnectionState
    /** A fake source is active (day-to-day development mode). */
    data class Simulated(val scenarioName: String) : ConnectionState
}
