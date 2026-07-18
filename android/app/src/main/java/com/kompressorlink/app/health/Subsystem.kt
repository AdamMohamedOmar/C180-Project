package com.kompressorlink.app.health

import com.kompressorlink.app.telemetry.Signal

// Spec §5.4's fixed subsystem set. OTHER renders as a card only when a DTC
// lands there.
enum class Subsystem(val displayName: String) {
    FUELING("Fueling"),
    AIR_INTAKE("Air / Intake"),
    COOLING("Cooling"),
    IGNITION("Ignition"),
    EXHAUST_CAT("Exhaust / Cat"),
    BATTERY("Battery"),
    OTHER("Other");

    companion object {
        // Live-signal ownership (spec §5.4 table). Signals not listed
        // (SPEED, THROTTLE partners…) influence no subsystem directly.
        val SIGNAL_SUBSYSTEMS: Map<Signal, Subsystem> = mapOf(
            Signal.STFT1 to FUELING,
            Signal.LTFT1 to FUELING,
            Signal.FUEL_STATUS to FUELING,
            Signal.O2_B1S1_V to FUELING,
            Signal.MAF_GS to AIR_INTAKE,
            Signal.MAP to AIR_INTAKE,
            Signal.THROTTLE to AIR_INTAKE,
            Signal.ECT to COOLING,
            Signal.TIMING_ADV to IGNITION,
            Signal.O2_B1S2_V to EXHAUST_CAT,
            Signal.BATT_V_ADC to BATTERY,
        )

        // Scope honesty rendered on the Ignition card (spec §5.4/§8.3).
        const val IGNITION_FOOTNOTE = "DTC-based — this ECU exposes no live misfire counters"
    }
}

enum class HealthStatus(val label: String) {
    OK("Looks good"),
    WATCH("Worth watching"),
    ATTENTION("Needs a look"),
}
