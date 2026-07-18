package com.kompressorlink.app.health

import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot

object SnapshotContexts {
    /**
     * true = engine running (RPM >= 400), false = engine off (RPM present
     * but below), null = RPM unavailable — never guess engine state from
     * missing data (Untested contract, spec §3).
     */
    fun engineRunning(s: TelemetrySnapshot): Boolean? =
        s.value(Signal.RPM)?.let { it >= HealthTuning.ENGINE_RUNNING_MIN_RPM }

    /**
     * true = sustained-power context candidate (THROTTLE >= 70 % AND
     * RPM >= 3000), false = not, null = either gating signal unavailable —
     * never guess a context from missing data (Untested contract). The
     * >= HIGH_LOAD_DWELL_SNAPSHOTS debounce lives in DwellGate, not here.
     */
    fun highLoad(s: TelemetrySnapshot): Boolean? {
        val throttle = s.value(Signal.THROTTLE) ?: return null
        val rpm = s.value(Signal.RPM) ?: return null
        return throttle >= HealthTuning.HIGH_LOAD_THROTTLE_MIN &&
            rpm >= HealthTuning.HIGH_LOAD_RPM_MIN
    }
}
