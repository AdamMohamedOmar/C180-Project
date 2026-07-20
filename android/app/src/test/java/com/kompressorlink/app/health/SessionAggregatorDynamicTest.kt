package com.kompressorlink.app.health

import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAggregatorDynamicTest {

    private val refs = ReferenceRepository { name ->
        File("src/main/assets/$name").readText()
    }

    private fun snap(vararg pairs: Pair<Signal, Float>): TelemetrySnapshot {
        val v = FloatArray(SIGNAL_COUNT)
        var mask = 0
        pairs.forEach { (s, value) ->
            v[s.ordinal] = value
            mask = mask or (1 shl s.ordinal)
        }
        return TelemetrySnapshot(v, mask, flags = 0, seq = 0, uptimeMs = 0)
    }

    private fun agg() = SessionAggregator(refs, isReal = true)

    @Test
    fun `high load stats accumulate only after dwell gate opens`() {
        val a = agg()
        var t = 0L
        // 3 qualifying snapshots: gate (4) never opens -> no high-load stats.
        repeat(3) {
            a.add(snap(Signal.THROTTLE to 85f, Signal.RPM to 3500f, Signal.LTFT1 to 2f), t); t += 500
        }
        a.add(snap(Signal.THROTTLE to 10f, Signal.RPM to 900f, Signal.LTFT1 to 9f), t); t += 500
        var stats = a.buildStats(sessionId = 1)
        assertNull(stats.first { it.signal == "LTFT1" }.highLoadMean)

        // 6 consecutive: samples 4..6 accumulate (gate opens on the 4th).
        val b = agg()
        t = 0
        repeat(6) {
            b.add(snap(Signal.THROTTLE to 85f, Signal.RPM to 3500f, Signal.LTFT1 to 2f), t); t += 500
        }
        stats = b.buildStats(sessionId = 1)
        val ltft = stats.first { it.signal == "LTFT1" }
        assertEquals(2f, ltft.highLoadMean!!, 1e-4f)
        assertEquals(3, ltft.highLoadCount)
    }

    @Test
    fun `warmup rate emitted only for completed cold start with enough span`() {
        val a = agg()
        // Cold start at 20 °C, +0.1 °C per 500 ms snapshot = 12 °C/min;
        // reaches 80 °C after 600 snapshots = 300 s (> 2 min span).
        var t = 0L
        var ect = 20f
        while (ect < 81f) {
            a.add(snap(Signal.ECT to ect, Signal.RPM to 800f), t)
            t += 500; ect += 0.1f
        }
        val rate = a.buildStats(1).first { it.signal == "ECT" }.warmupRatePerMin
        assertEquals(12f, rate!!, 0.5f)
    }

    @Test
    fun `no warmup rate on a warm restart or an incomplete warmup`() {
        val warm = agg()
        warm.add(snap(Signal.ECT to 85f, Signal.RPM to 800f), 0)   // first ECT >= 40
        warm.add(snap(Signal.ECT to 86f, Signal.RPM to 800f), 500)
        assertNull(warm.buildStats(1).firstOrNull { it.signal == "ECT" }?.warmupRatePerMin)

        val incomplete = agg()
        incomplete.add(snap(Signal.ECT to 20f, Signal.RPM to 800f), 0)
        incomplete.add(snap(Signal.ECT to 30f, Signal.RPM to 800f), 60_000)  // never reaches 80
        assertNull(incomplete.buildStats(1).firstOrNull { it.signal == "ECT" }?.warmupRatePerMin)
    }

    @Test
    fun `no warmup rate when the climb completes faster than the minimum span`() {
        val fast = agg()
        fast.add(snap(Signal.ECT to 20f, Signal.RPM to 800f), 0)        // cold start decided
        fast.add(snap(Signal.ECT to 85f, Signal.RPM to 800f), 60_000)   // hits 80 at 60 s (< 120 s floor)
        assertNull(fast.buildStats(1).firstOrNull { it.signal == "ECT" }?.warmupRatePerMin)
    }

    @Test
    fun `o2 onset recorded on cold start only`() {
        val cold = agg()
        cold.add(snap(Signal.ECT to 20f, Signal.O2_B1S1_V to 0.45f, Signal.RPM to 900f), 0)
        cold.add(snap(Signal.ECT to 21f, Signal.O2_B1S1_V to 0.75f, Signal.RPM to 900f), 10_000)
        cold.add(snap(Signal.ECT to 22f, Signal.O2_B1S1_V to 0.12f, Signal.RPM to 900f), 24_000)
        val onset = cold.buildStats(1).first { it.signal == "O2_B1S1_V" }.o2OnsetS
        assertEquals(24f, onset!!, 1e-3f)

        val warm = agg()
        warm.add(snap(Signal.ECT to 85f, Signal.O2_B1S1_V to 0.75f, Signal.RPM to 900f), 0)
        warm.add(snap(Signal.ECT to 85f, Signal.O2_B1S1_V to 0.12f, Signal.RPM to 900f), 4_000)
        assertNull(warm.buildStats(1).firstOrNull { it.signal == "O2_B1S1_V" }?.o2OnsetS)
    }

    @Test
    fun `o2 onset captures a sample that arrives before the first ECT sample on a cold session`() {
        val cold = agg()
        // O2 (tier M) polled before ECT (tier S) ever lands -- rich rail seen
        // at session start, while coldStart is still undecided.
        cold.add(snap(Signal.O2_B1S1_V to 0.75f, Signal.RPM to 900f), 0)
        // First-ever ECT sample: decides cold start (20 < 40). Same snapshot
        // carries a mid-band O2 reading that alone proves nothing.
        cold.add(snap(Signal.ECT to 20f, Signal.O2_B1S1_V to 0.5f, Signal.RPM to 900f), 2_000)
        // Lean rail completes the swing.
        cold.add(snap(Signal.ECT to 21f, Signal.O2_B1S1_V to 0.12f, Signal.RPM to 900f), 5_000)
        val onset = cold.buildStats(1).first { it.signal == "O2_B1S1_V" }.o2OnsetS
        // Onset must be measured from the t=0 sample that already saw the
        // high rail (5 s), not dropped or re-based off the ECT-decision
        // snapshot -- had the t=0 sample been dropped, only the lean rail
        // would ever be seen and onsetS would stay null forever.
        assertEquals(5f, onset!!, 1e-3f)
    }
}
