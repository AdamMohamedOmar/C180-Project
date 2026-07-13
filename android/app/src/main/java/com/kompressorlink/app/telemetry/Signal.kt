package com.kompressorlink.app.telemetry

// Mirror-verbatim of firmware/src/pid_schema.h's Signal enum — ordinal ==
// avail_mask bit == value slot in the telemetry frame (docs/ble_protocol.md).
// A change on either side must update both + the protocol doc + the golden
// vectors, or SignalSchemaTest / the golden tests go red.
enum class Signal {
    RPM, SPEED, MAF_GS, THROTTLE, LOAD, STFT1, LTFT1, MAP, TIMING_ADV,
    O2_B1S1_V, O2_B1S2_V, ECT, IAT, FUEL_STATUS,
    PEDAL_D, PEDAL_E, CMD_THROTTLE, CTRL_MODULE_V, BATT_V_ADC,
}

const val SIGNAL_COUNT: Int = 19
