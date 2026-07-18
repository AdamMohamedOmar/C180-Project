package com.kompressorlink.app.health

import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAggregatorTest {

    // Same asset-loading trick as ReferenceRepositoryTest: JVM tests read
    // the real assets straight off the filesystem.
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

    // Warm idle per DashboardLogic.isWarmIdle: RPM 600-900, SPEED 0, ECT > 80.
    private fun warmIdleSnap(ltft: Float, maf: Float = 4f) = snap(
        Signal.RPM to 750f, Signal.SPEED to 0f, Signal.ECT to 90f,
        Signal.LTFT1 to ltft, Signal.MAF_GS to maf,
    )

    @Test
    fun welfordStats_andWarmIdleMean() {
        val agg = SessionAggregator(refs, isReal = true)
        var t = 0L
        listOf(3f, 4f, 5f).forEach { maf ->
            agg.add(warmIdleSnap(ltft = 4f, maf = maf), t)
            t += 500
        }
        val stats = agg.buildStats(sessionId = 1).first { it.signal == "MAF_GS" }
        assertEquals(3, stats.sampleCount)
        assertEquals(4f, stats.mean, 1e-5f)
        assertEquals(4f, stats.warmIdleMean!!, 1e-5f)
        assertEquals(3, stats.warmIdleCount)
        assertEquals("OK", stats.worstLevel)
        assertEquals(1f, agg.warmIdleSeconds, 1e-3f)  // 2 gaps x 0.5 s
    }

    @Test
    fun outOfBandSeconds_useRawBands_andWorstLevel() {
        val agg = SessionAggregator(refs, isReal = true)
        var t = 0L
        // LTFT band is ±10 always; halfWidth 10 => 15 is AMBER (RED beyond 20).
        repeat(10) {
            agg.add(warmIdleSnap(ltft = 15f), t)
            t += 500
        }
        val ltft = agg.buildStats(1).first { it.signal == "LTFT1" }
        assertEquals("AMBER", ltft.worstLevel)
        assertEquals(4.5f, ltft.secondsOutOfBand, 1e-3f)  // 9 gaps x 0.5 s (first dt = 0)
    }

    @Test
    fun batteryContexts_splitByEngineState_andUnavailableRpmAccumulatesNeither() {
        val agg = SessionAggregator(refs, isReal = true)
        agg.add(snap(Signal.RPM to 750f, Signal.BATT_V_ADC to 14.2f), 0)      // running
        agg.add(snap(Signal.RPM to 0f, Signal.BATT_V_ADC to 12.6f), 500)      // off
        agg.add(snap(Signal.BATT_V_ADC to 13.0f), 1_000)                      // RPM masked
        val batt = agg.buildStats(1).first { it.signal == "BATT_V_ADC" }
        assertEquals(3, batt.sampleCount)
        assertEquals(1, batt.engineRunningCount)
        assertEquals(14.2f, batt.engineRunningMean!!, 1e-4f)
        assertEquals(1, batt.engineOffCount)
        assertEquals(12.6f, batt.engineOffMean!!, 1e-4f)
    }

    @Test
    fun distance_integratesRealSpeed_withGapGuard() {
        val agg = SessionAggregator(refs, isReal = true)
        var t = 0L
        // 61 samples at 60 km/h, 1 s apart -> 60 gaps x (60/3600) km = 1.0 km
        repeat(61) {
            agg.add(snap(Signal.SPEED to 60f, Signal.RPM to 2000f), t)
            t += 1_000
        }
        // one 10 s gap: contributes nothing (gap guard)
        t += 9_000
        agg.add(snap(Signal.SPEED to 60f, Signal.RPM to 2000f), t)
        assertEquals(1.0f, agg.distanceKm!!, 1e-3f)
    }

    @Test
    fun simSession_neverReportsDistance() {
        val agg = SessionAggregator(refs, isReal = false)
        agg.add(snap(Signal.SPEED to 60f, Signal.RPM to 2000f), 0)
        agg.add(snap(Signal.SPEED to 60f, Signal.RPM to 2000f), 1_000)
        assertNull(agg.distanceKm)
    }

    @Test
    fun maskedSignal_getsNoStatsRow() {
        val agg = SessionAggregator(refs, isReal = true)
        agg.add(snap(Signal.RPM to 750f), 0)
        assertTrue(agg.buildStats(1).none { it.signal == "LTFT1" })
    }

    @Test
    fun dtcLatch_sticksOnceStoredCodeSeen() {
        val agg = SessionAggregator(refs, isReal = true)
        agg.onDtcReport(DtcReport(stored = emptyList(), pending = listOf("P1570")))
        assertEquals(false, agg.hasStoredDtc)
        agg.onDtcReport(DtcReport(stored = listOf("P0171"), pending = emptyList()))
        agg.onDtcReport(null)
        assertEquals(true, agg.hasStoredDtc)
    }
}
