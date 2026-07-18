package com.kompressorlink.app.health

import com.kompressorlink.app.reference.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineTest {

    private val ltftBand = Band(
        signal = "LTFT1", context = "always", lo = -10f, hi = 10f,
        unit = "%", confidence = "Confirmed", hint = "hint",
    )

    private fun point(day: Int, value: Float, eligible: Boolean = true) = MetricPoint(
        sessionId = day.toLong(), endedAtEpochMs = day * Baseline.DAY_MS,
        value = value, eligible = eligible,
    )

    @Test
    fun gated_untilEightEligibleSessions() {
        val points = (0 until 7).map { point(it * 3, 4f) }
        val result = Baseline.evaluate(points, ltftBand)
        assertTrue(result is Baseline.Result.Gated)
        assertEquals(7, (result as Baseline.Result.Gated).eligibleCount)
    }

    @Test
    fun gated_ineligiblePointsDontCount() {
        val points = (0 until 12).map { point(it * 3, 4f, eligible = it % 2 == 0) }  // 6 eligible
        assertTrue(Baseline.evaluate(points, ltftBand) is Baseline.Result.Gated)
    }

    @Test
    fun gated_whenSpanTooShort() {
        val points = (0 until 10).map { point(it, 4f) }  // 10 sessions in 9 days < 14
        assertTrue(Baseline.evaluate(points, ltftBand) is Baseline.Result.Gated)
    }

    @Test
    fun active_medianAndMad_robustToOutlier() {
        // 9 sessions around 4% with one wild 9% — median/MAD shrug it off.
        // MAD here is 0.3, safely above the degenerate threshold (1% of the
        // 20-unit band = 0.2), so the ±3×MAD path is exercised.
        val values = listOf(3.4f, 3.7f, 4.0f, 4.3f, 4.6f, 4.0f, 9.0f, 3.7f, 4.3f)
        val points = values.mapIndexed { i, v -> point(i * 2, v) }  // spans 16 days
        val result = Baseline.evaluate(points, ltftBand) as Baseline.Result.Active
        assertEquals(4.0f, result.envelope.median, 1e-4f)
        assertEquals(0.3f, result.envelope.mad, 1e-4f)
        assertEquals(4.0f - 0.9f, result.envelope.lo, 1e-4f)   // median ± 3×MAD
        assertEquals(4.0f + 0.9f, result.envelope.hi, 1e-4f)
    }

    @Test
    fun degenerateMad_widensToBandFraction() {
        // Perfectly constant history: MAD 0 < 1% of the 20-unit band width,
        // so the envelope widens to ±5% of band width = ±1.0.
        val points = (0 until 10).map { point(it * 2, 4f) }
        val result = Baseline.evaluate(points, ltftBand) as Baseline.Result.Active
        assertEquals(3f, result.envelope.lo, 1e-4f)
        assertEquals(5f, result.envelope.hi, 1e-4f)
    }

    @Test
    fun deviation_onlyInsideAbsoluteBand() {
        val env = Baseline.Envelope(median = 4f, mad = 0.1f, lo = 3.7f, hi = 4.3f, eligibleCount = 10)
        assertTrue(Baseline.isDeviation(8f, env, ltftBand))    // outside envelope, inside band
        assertFalse(Baseline.isDeviation(4.1f, env, ltftBand)) // inside envelope
        assertFalse(Baseline.isDeviation(12f, env, ltftBand))  // beyond band: live severity owns it
    }
}
