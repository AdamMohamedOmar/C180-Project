package com.kompressorlink.app.telemetry

// One decoded telemetry frame. `value()` is the Untested contract's
// enforcement point in the app: a masked-out signal has NO reading, ever —
// UI layers render "not provided", never 0.
//
// `values` is defensively copied at construction: instances flow through
// StateFlow/Flow to multiple collectors, and this class is meant to be an
// immutable value once built -- the constructor's caller is free to keep
// mutating its own buffer (e.g. a reused scratch array) after handing it
// off without corrupting a snapshot a collector already holds.
class TelemetrySnapshot(
    values: FloatArray,
    val availMask: Int,
    val flags: Int,
    val seq: Int,
    val uptimeMs: Long,
) {
    val values: FloatArray = values.copyOf()

    init {
        require(this.values.size == SIGNAL_COUNT) { "expected $SIGNAL_COUNT values, got ${this.values.size}" }
    }

    fun isAvailable(signal: Signal): Boolean = (availMask shr signal.ordinal) and 1 == 1

    fun value(signal: Signal): Float? =
        if (isAvailable(signal)) values[signal.ordinal] else null

    val isDemo: Boolean get() = flags and FLAG_DEMO_MODE != 0
    val klineConnected: Boolean get() = flags and FLAG_KLINE_CONNECTED != 0

    companion object {
        // Mirror-verbatim of docs/ble_protocol.md flag bits.
        const val FLAG_DEMO_MODE = 0x01
        const val FLAG_KLINE_CONNECTED = 0x02
    }
}
