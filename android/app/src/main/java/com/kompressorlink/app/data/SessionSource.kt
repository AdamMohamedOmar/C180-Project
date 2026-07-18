package com.kompressorlink.app.data

import com.kompressorlink.app.telemetry.SourceChoice

// The quarantine rule's atom (spec §1): every telemetry-derived row carries
// its origin. Health verdicts, baselines, drift, odometer estimation, and
// reminders read REAL_BLE rows ONLY; sim rows exist solely behind the Health
// screen's explicit Demo mode.
enum class SessionSource {
    SIM_HEALTHY, SIM_FAULT, SIM_SPARSE, REAL_BLE;

    val isReal: Boolean get() = this == REAL_BLE

    companion object {
        fun from(choice: SourceChoice): SessionSource = when (choice) {
            SourceChoice.SIMULATED_HEALTHY -> SIM_HEALTHY
            SourceChoice.SIMULATED_FAULT -> SIM_FAULT
            SourceChoice.SIMULATED_SPARSE -> SIM_SPARSE
            SourceChoice.REAL_BLE -> REAL_BLE
        }

        val REAL: Set<SessionSource> = setOf(REAL_BLE)
        val SIM: Set<SessionSource> = setOf(SIM_HEALTHY, SIM_FAULT, SIM_SPARSE)
    }
}

// Warning rows collapse the tag to two buckets (spec §2 warnings.source).
enum class WarningSource { SIM, REAL;
    companion object {
        fun from(source: SessionSource): WarningSource =
            if (source.isReal) REAL else SIM
    }
}
