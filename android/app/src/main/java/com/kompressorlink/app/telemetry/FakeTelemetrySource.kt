package com.kompressorlink.app.telemetry

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

// The three simulated scenarios (spec §4.2). SPARSE rehearses a stingy
// SIM4LKE hiding MAP + both O2 sensors — exactly what Phase 3's probe
// might reveal (PLAN.md §10 risk 1).
enum class FakeScenario(val displayName: String) {
    HEALTHY("healthy"),
    FAULT("fault: P0171 + LTFT +18%"),
    SPARSE("sparse ECU"),
}

class FakeTelemetrySource(
    private val scenario: FakeScenario,
    private val tickMs: Long = 500L,  // matches the real device's 2 Hz notify cadence
) : TelemetrySource {

    override val connectionState: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.Simulated(scenario.displayName))

    override val dtcReport: StateFlow<DtcReport?> = MutableStateFlow(
        when (scenario) {
            // P1570 is deliberately in NEITHER json: it proves the
            // family-decode layer on a real screen (spec §8.4/§10).
            FakeScenario.FAULT -> DtcReport(stored = listOf("P0171"), pending = listOf("P1570"))
            else -> DtcReport(stored = emptyList(), pending = emptyList())
        }
    )

    override val telemetry: Flow<TelemetrySnapshot> = flow {
        var seq = 0
        while (true) {
            emit(snapshotAt(scenario, tMs = seq * tickMs, seq = seq))
            seq = (seq + 1) and 0xFFFF
            delay(tickMs)
        }
    }

    override suspend fun sendTimeSync() {
        // No device clock to sync — deliberate no-op on fakes.
    }

    companion object {
        // Mirrors firmware demo_feed's kDemoAvailMask reasoning: all F/M/S
        // signals (bits 0-13) + BATT_V_ADC (18); Legacy bits stay clear.
        const val HEALTHY_MASK = 0x0004_3FFF
        val SPARSE_MASK = HEALTHY_MASK and
            (1 shl Signal.MAP.ordinal).inv() and
            (1 shl Signal.O2_B1S1_V.ordinal).inv() and
            (1 shl Signal.O2_B1S2_V.ordinal).inv()

        /** Deterministic warm-idle waveform — pure function of (scenario, t). */
        fun snapshotAt(scenario: FakeScenario, tMs: Long, seq: Int): TelemetrySnapshot {
            val v = FloatArray(SIGNAL_COUNT)
            v[Signal.RPM.ordinal] = if ((tMs / 1000) % 2 == 0L) 750f else 780f
            v[Signal.SPEED.ordinal] = 0f
            v[Signal.MAF_GS.ordinal] = 4f
            v[Signal.THROTTLE.ordinal] = 11f
            v[Signal.LOAD.ordinal] = 22f
            v[Signal.STFT1.ordinal] = if (scenario == FakeScenario.FAULT) 8f else -1.5f
            // FAULT: the classic M271 breather-leak signature — LTFT +18%
            // at warm idle (docs/w203_reference.md) — drives the dashboard's
            // out-of-band tinting + hint end-to-end.
            v[Signal.LTFT1.ordinal] = if (scenario == FakeScenario.FAULT) 18f else 4.7f
            v[Signal.MAP.ordinal] = 32f
            v[Signal.TIMING_ADV.ordinal] = 12f
            v[Signal.O2_B1S1_V.ordinal] = if ((tMs / 500) % 2 == 0L) 0.2f else 0.7f
            v[Signal.O2_B1S2_V.ordinal] = 0.65f
            v[Signal.ECT.ordinal] = 90f
            v[Signal.IAT.ordinal] = 35f
            v[Signal.FUEL_STATUS.ordinal] = 2f
            v[Signal.BATT_V_ADC.ordinal] = 14.25f

            val mask = if (scenario == FakeScenario.SPARSE) SPARSE_MASK else HEALTHY_MASK
            // Contract parity with the firmware packer: masked-out slots
            // carry exactly 0.0f.
            for (s in Signal.entries) {
                if ((mask shr s.ordinal) and 1 == 0) v[s.ordinal] = 0f
            }
            // flags = 0: this is an in-app simulation, not the device's demo
            // build — the connection chip gets its truth from
            // ConnectionState.Simulated, not from frame flags.
            return TelemetrySnapshot(v, mask, flags = 0, seq = seq, uptimeMs = tMs)
        }
    }
}
