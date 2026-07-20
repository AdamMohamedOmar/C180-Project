package com.kompressorlink.app.health

import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricsDynamicTest {

    private fun stat(
        signal: String,
        warmIdleMean: Float? = null, warmIdleCount: Int = 0,
        highLoadMean: Float? = null, highLoadCount: Int = 0,
        warmupRatePerMin: Float? = null, o2OnsetS: Float? = null,
    ) = SessionStatEntity(
        sessionId = 1, signal = signal, sampleCount = 100, mean = 0f, min = 0f,
        max = 0f, stdDev = 0f, secondsOutOfBand = 0f, worstLevel = "OK",
        warmIdleMean = warmIdleMean, warmIdleCount = warmIdleCount,
        engineRunningMean = null, engineRunningCount = 0,
        engineOffMean = null, engineOffCount = 0,
        highLoadMean = highLoadMean, highLoadCount = highLoadCount,
        warmupRatePerMin = warmupRatePerMin, o2OnsetS = o2OnsetS,
    )

    private fun session(id: Long, endedAt: Long) = SessionEntity(
        id = id, startedAtEpochMs = endedAt - 600_000, endedAtEpochMs = endedAt,
        source = "REAL_BLE", snapshotCount = 1200, warmIdleSeconds = 120f,
        distanceKm = 5f, hasStoredDtc = false,
    )

    @Test
    fun `load sensitivity is load minus idle and needs both contexts`() {
        val ok = stat("LTFT1", warmIdleMean = 12f, warmIdleCount = 60,
            highLoadMean = 2f, highLoadCount = 20)
        assertEquals(-10f to 20, MetricId.LTFT_LOAD_SENSITIVITY.contextValue(ok))

        val idleTooThin = stat("LTFT1", warmIdleMean = 12f, warmIdleCount = 10,
            highLoadMean = 2f, highLoadCount = 20)
        assertNull(MetricId.LTFT_LOAD_SENSITIVITY.contextValue(idleTooThin))

        val noLoad = stat("LTFT1", warmIdleMean = 12f, warmIdleCount = 60)
        assertNull(MetricId.LTFT_LOAD_SENSITIVITY.contextValue(noLoad))
    }

    @Test
    fun `event metrics use presence as validity`() {
        assertEquals(9.5f to 1,
            MetricId.ECT_WARMUP_RATE.contextValue(stat("ECT", warmupRatePerMin = 9.5f)))
        assertNull(MetricId.ECT_WARMUP_RATE.contextValue(stat("ECT")))
        assertEquals(38f to 1,
            MetricId.O2_ACTIVITY_ONSET.contextValue(stat("O2_B1S1_V", o2OnsetS = 38f)))
    }

    @Test
    fun `per-metric floors gate the series`() {
        // MAF_HIGH_LOAD floor is HIGH_LOAD_MIN_SAMPLES (15), not 30.
        assertEquals(HealthTuning.HIGH_LOAD_MIN_SAMPLES, MetricId.MAF_HIGH_LOAD.minSamples)
        assertEquals(HealthTuning.CONTEXT_MIN_SAMPLES, MetricId.LTFT_WARM_IDLE.minSamples)
        assertEquals(1, MetricId.ECT_WARMUP_RATE.minSamples)
    }

    // The three tests below exercise MetricSeries.build()'s floor line
    // itself (`if (count < metric.minSamples) return@mapNotNull null`), not
    // just the minSamples property. Each uses a count that only clears the
    // metric's OWN floor, not the old hardcoded HealthTuning.CONTEXT_MIN_
    // SAMPLES (30) the line used to read — so if that line were ever
    // reverted to the global constant, these would catch it by going empty.

    @Test
    fun `build honors MAF_HIGH_LOAD's own floor of 15, not the generic 30`() {
        val sessions = listOf(session(1, endedAt = 1_000))
        // 20 >= HIGH_LOAD_MIN_SAMPLES(15) but < CONTEXT_MIN_SAMPLES(30): the
        // old global-constant check would have dropped this session.
        val stats = listOf(stat("MAF_GS", highLoadMean = 3.5f, highLoadCount = 20))
        val points = MetricSeries.build(MetricId.MAF_HIGH_LOAD, sessions, stats)
        assertEquals(1, points.size)
        assertEquals(3.5f, points[0].value, 1e-4f)
    }

    @Test
    fun `build honors LTFT_LOAD_SENSITIVITY's dual idle-30 load-15 floor`() {
        val sessions = listOf(session(1, endedAt = 1_000))
        // Idle count (60) clears the generic 30 floor gated inside
        // contextValue itself; load count (20) clears this metric's own 15
        // floor gated by build() -- but would be wrongly dropped by the old
        // hardcoded-30 check on that same line.
        val ok = listOf(stat("LTFT1", warmIdleMean = 12f, warmIdleCount = 60,
            highLoadMean = 2f, highLoadCount = 20))
        val points = MetricSeries.build(MetricId.LTFT_LOAD_SENSITIVITY, sessions, ok)
        assertEquals(1, points.size)
        assertEquals(-10f, points[0].value, 1e-4f)

        // Load count below even its OWN floor (15) still correctly drops it.
        val thinLoad = listOf(stat("LTFT1", warmIdleMean = 12f, warmIdleCount = 60,
            highLoadMean = 2f, highLoadCount = 10))
        assertEquals(0, MetricSeries.build(MetricId.LTFT_LOAD_SENSITIVITY, sessions, thinLoad).size)
    }

    @Test
    fun `build honors ECT_WARMUP_RATE's floor of 1, not the generic 30`() {
        val sessions = listOf(session(1, endedAt = 1_000))
        // contextValue always reports count=1 for this event-shaped metric;
        // the old hardcoded-30 check would have silently dropped every
        // single warm-up-rate point ever produced.
        val stats = listOf(stat("ECT", warmupRatePerMin = 9.5f))
        val points = MetricSeries.build(MetricId.ECT_WARMUP_RATE, sessions, stats)
        assertEquals(1, points.size)
        assertEquals(9.5f, points[0].value, 1e-4f)
    }
}
