package com.kompressorlink.app.health

import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MetricsTest {

    private fun session(id: Long, endedAt: Long, hasDtc: Boolean = false) = SessionEntity(
        id = id, startedAtEpochMs = endedAt - 600_000, endedAtEpochMs = endedAt,
        source = "REAL_BLE", snapshotCount = 1200, warmIdleSeconds = 120f,
        distanceKm = 5f, hasStoredDtc = hasDtc,
    )

    private fun ltftStat(sessionId: Long, warmIdleMean: Float?, warmIdleCount: Int, worst: String = "OK") =
        SessionStatEntity(
            sessionId = sessionId, signal = "LTFT1", sampleCount = 500,
            mean = warmIdleMean ?: 0f, min = -2f, max = 20f, stdDev = 1f,
            secondsOutOfBand = 0f, worstLevel = worst,
            warmIdleMean = warmIdleMean, warmIdleCount = warmIdleCount,
            engineRunningMean = null, engineRunningCount = 0,
            engineOffMean = null, engineOffCount = 0,
        )

    @Test
    fun build_ordersAscending_appliesSampleFloor_andEligibility() {
        val sessions = listOf(
            session(1, endedAt = 3_000),
            session(2, endedAt = 1_000),
            session(3, endedAt = 2_000, hasDtc = true),
            session(4, endedAt = 4_000),
        )
        val stats = listOf(
            ltftStat(1, warmIdleMean = 5f, warmIdleCount = 100),
            ltftStat(2, warmIdleMean = 4f, warmIdleCount = 100, worst = "RED"),
            ltftStat(3, warmIdleMean = 6f, warmIdleCount = 100),
            ltftStat(4, warmIdleMean = 7f, warmIdleCount = HealthTuning.CONTEXT_MIN_SAMPLES - 1),
        )
        val points = MetricSeries.build(MetricId.LTFT_WARM_IDLE, sessions, stats)
        // session 4 dropped (sample floor); order is 2, 3, 1 by endedAt.
        assertEquals(listOf(2L, 3L, 1L), points.map { it.sessionId })
        assertEquals(listOf(false, false, true), points.map { it.eligible })
        // session 2 ineligible (worst RED), session 3 ineligible (stored DTC)
    }

    @Test
    fun build_skipsSessionsMissingTheContext() {
        val sessions = listOf(session(1, endedAt = 1_000))
        val stats = listOf(ltftStat(1, warmIdleMean = null, warmIdleCount = 0))
        assertEquals(0, MetricSeries.build(MetricId.LTFT_WARM_IDLE, sessions, stats).size)
    }
}
