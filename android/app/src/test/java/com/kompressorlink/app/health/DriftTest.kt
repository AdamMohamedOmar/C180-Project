package com.kompressorlink.app.health

import com.kompressorlink.app.reference.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftTest {

    private val ltftBand = Band(
        signal = "LTFT1", context = "always", lo = -10f, hi = 10f,
        unit = "%", confidence = "Confirmed", hint = "Breather hoses first.",
    )

    private fun point(day: Int, value: Float) = MetricPoint(
        sessionId = day.toLong(), endedAtEpochMs = day * Baseline.DAY_MS,
        value = value, eligible = true,
    )

    // 11 sessions over 20 days rising 0.1 %/day: 6.0 -> 8.0.
    private fun risingSeries() = (0..10).map { point(it * 2, 6f + it * 0.2f) }

    @Test
    fun cleanRisingDrift_reportsWeeksToEdge() {
        val result = Drift.evaluate(MetricId.LTFT_WARM_IDLE, risingSeries(), ltftBand)
        assertTrue(result is Drift.Result.Drifting)
        val d = result as Drift.Result.Drifting
        assertTrue(d.rising)
        assertEquals(0.7f, d.slopePerWeek, 0.01f)          // 0.1 %/day
        assertEquals(3, d.weeksToEdge)                     // (10 - ~7.8) / 0.7 ≈ 3.1
        assertTrue(d.message.contains("rising"))
        assertTrue(d.message.contains("~3 weeks"))
        assertTrue(d.message.contains("Breather hoses first."))
    }

    @Test
    fun outlierSession_doesNotBreakTheSlope() {
        // Outlier placed at an ENDPOINT (index 10, the most recent session),
        // not the temporal midpoint: a midpoint outlier sits at x̄ and has
        // zero leverage on an OLS/least-squares slope (its deviation term
        // (x_i - x̄) is exactly 0, so a naive mean-based estimator would pass
        // this test too). An endpoint outlier has maximum leverage on OLS —
        // only Theil-Sen's median-of-pairwise-slopes stays put, since a
        // single point only ever corrupts (n-1) of the C(n,2) pairwise
        // slopes regardless of where it sits on the time axis.
        val withOutlier = risingSeries().toMutableList()
        withOutlier[10] = point(20, 25f)  // one wild session, at the endpoint
        val clean = Drift.evaluate(MetricId.LTFT_WARM_IDLE, risingSeries(), ltftBand) as Drift.Result.Drifting
        val noisy = Drift.evaluate(MetricId.LTFT_WARM_IDLE, withOutlier, ltftBand) as Drift.Result.Drifting
        assertTrue(kotlin.math.abs(clean.weeksToEdge - noisy.weeksToEdge) <= 1)
    }

    @Test
    fun gated_belowMinPoints_orSpan() {
        val few = (0..5).map { point(it * 3, 6f + it * 0.2f) }  // 6 points
        assertTrue(Drift.evaluate(MetricId.LTFT_WARM_IDLE, few, ltftBand) is Drift.Result.Gated)

        val shortSpan = (0..10).map { point(it, 6f + it * 0.2f) }  // 10 days < 14
        assertTrue(Drift.evaluate(MetricId.LTFT_WARM_IDLE, shortSpan, ltftBand) is Drift.Result.Gated)
    }

    @Test
    fun stable_whenFlat_orTooSlow() {
        val flat = (0..10).map { point(it * 2, 4f) }
        assertTrue(Drift.evaluate(MetricId.LTFT_WARM_IDLE, flat, ltftBand) is Drift.Result.Stable)

        // 0.01 %/day => 0.07 %/week => ~85 weeks to edge: beyond 26 -> Stable.
        val slow = (0..10).map { point(it * 2, 4f + it * 0.02f) }
        assertTrue(Drift.evaluate(MetricId.LTFT_WARM_IDLE, slow, ltftBand) is Drift.Result.Stable)
    }

    @Test
    fun alreadyBeyondEdge_staysSilent() {
        val beyond = (0..10).map { point(it * 2, 11f + it * 0.2f) }  // current > 10
        assertTrue(Drift.evaluate(MetricId.LTFT_WARM_IDLE, beyond, ltftBand) is Drift.Result.Stable)
    }

    @Test
    fun fallingDrift_targetsLowerEdge() {
        // MAF sagging toward 3 g/s: 4.2 -> 3.4 over 20 days.
        val mafBand = Band("MAF_GS", "warm_idle", 3f, 5f, "g/s", "Confirmed", "Intake leaks first.")
        val falling = (0..10).map { point(it * 2, 4.2f - it * 0.08f) }
        val d = Drift.evaluate(MetricId.MAF_WARM_IDLE, falling, mafBand) as Drift.Result.Drifting
        assertTrue(!d.rising)
        assertTrue(d.message.contains("falling"))
        assertTrue(d.message.contains("crosses 3"))
    }
}
