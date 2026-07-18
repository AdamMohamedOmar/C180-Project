package com.kompressorlink.app.health

import com.kompressorlink.app.data.FakeSessionRepository
import com.kompressorlink.app.data.FakeWarningRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.reference.ReferenceRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostSessionEvaluatorTest {

    private val refs = ReferenceRepository { name -> File("src/main/assets/$name").readText() }

    private suspend fun addSession(
        repo: FakeSessionRepository,
        day: Int,
        ltftWarmIdle: Float,
        source: String = "REAL_BLE",
    ): Long {
        val endedAt = day * Baseline.DAY_MS
        return repo.record(
            SessionEntity(
                startedAtEpochMs = endedAt - 600_000, endedAtEpochMs = endedAt,
                source = source, snapshotCount = 1200, warmIdleSeconds = 300f,
                distanceKm = if (source == "REAL_BLE") 10f else null, hasStoredDtc = false,
            )
        ) { id ->
            listOf(SessionStatEntity(
                sessionId = id, signal = "LTFT1", sampleCount = 1000,
                mean = ltftWarmIdle, min = ltftWarmIdle - 1, max = ltftWarmIdle + 1, stdDev = 0.5f,
                secondsOutOfBand = 0f, worstLevel = "OK",
                warmIdleMean = ltftWarmIdle, warmIdleCount = 200,
                engineRunningMean = null, engineRunningCount = 0,
                engineOffMean = null, engineOffCount = 0,
            ))
        }
    }

    @Test
    fun deviation_raisesWatch_withTemplateWording() = runTest {
        val sessions = FakeSessionRepository()
        val warnings = FakeWarningRepository()
        val evaluator = PostSessionEvaluator(sessions, warnings, refs, now = { 0L })
        // 8 healthy sessions around 4% over 16 days, then one at 8% (inside
        // the ±10 band, outside the personal envelope).
        (0 until 8).forEach { addSession(sessions, day = it * 2 + 1, ltftWarmIdle = 4f + (it % 2) * 0.2f) }
        val currentId = addSession(sessions, day = 18, ltftWarmIdle = 8f)

        evaluator.onSessionClosed(currentId, SessionSource.REAL_BLE)

        val raised = warnings.warnings.value.filter { it.kind == "BASELINE_DEVIATION" }
        assertEquals(1, raised.size)
        assertEquals("WATCH", raised[0].level)
        assertEquals("REAL", raised[0].source)
        assertTrue(raised[0].detail.contains("outside your car's usual range"))
        assertTrue(raised[0].detail.contains("worth watching"))
    }

    @Test
    fun drift_raisesWatch_withMessage() = runTest {
        val sessions = FakeSessionRepository()
        val warnings = FakeWarningRepository()
        val evaluator = PostSessionEvaluator(sessions, warnings, refs, now = { 0L })
        // Rising LTFT 5.0 -> 7.0 over 20 days (0.1 %/day): drift verdict fires.
        var lastId = 0L
        (0..10).forEach { lastId = addSession(sessions, day = it * 2 + 1, ltftWarmIdle = 5f + it * 0.2f) }

        evaluator.onSessionClosed(lastId, SessionSource.REAL_BLE)

        val raised = warnings.warnings.value.filter { it.kind == "DRIFT" }
        assertEquals(1, raised.size)
        assertTrue(raised[0].detail.contains("at this rate"))
        assertTrue(raised[0].title.contains("drifting up"))
    }

    @Test
    fun tooLittleHistory_raisesNothing() = runTest {
        val sessions = FakeSessionRepository()
        val warnings = FakeWarningRepository()
        val evaluator = PostSessionEvaluator(sessions, warnings, refs, now = { 0L })
        (0 until 4).forEach { addSession(sessions, day = it * 2, ltftWarmIdle = 4f) }
        val id = addSession(sessions, day = 10, ltftWarmIdle = 8f)
        evaluator.onSessionClosed(id, SessionSource.REAL_BLE)
        assertEquals(0, warnings.warnings.value.size)
    }

    // Regression test for the SIM/REAL dedupeKey collision bug: dedupeKey
    // used to omit source, so a SIM-tagged BASELINE_DEVIATION warning and a
    // later REAL BASELINE_DEVIATION warning for the SAME metric/subsystem
    // would collide on `raise()`'s dedupe-upsert — the REAL one would
    // silently merge into (and never surface past) the existing SIM row,
    // permanently hiding a genuine real-car warning behind the demo-mode
    // quarantine filter. The fix makes dedupeKey source-inclusive.
    @Test
    fun sameMetricDeviation_underSimThenReal_raisesTwoSeparateWarnings() = runTest {
        val sessions = FakeSessionRepository()
        val warnings = FakeWarningRepository()
        val evaluator = PostSessionEvaluator(sessions, warnings, refs, now = { 0L })

        // Sim history + sim deviation for FUELING/LTFT_WARM_IDLE.
        (0 until 8).forEach { addSession(sessions, day = it * 2 + 1, ltftWarmIdle = 4f + (it % 2) * 0.2f, source = "SIM_HEALTHY") }
        val simId = addSession(sessions, day = 18, ltftWarmIdle = 8f, source = "SIM_FAULT")
        evaluator.onSessionClosed(simId, SessionSource.SIM_FAULT)

        // Real history + real deviation for the SAME metric/subsystem.
        (0 until 8).forEach { addSession(sessions, day = it * 2 + 1, ltftWarmIdle = 4f + (it % 2) * 0.2f, source = "REAL_BLE") }
        val realId = addSession(sessions, day = 18, ltftWarmIdle = 8f, source = "REAL_BLE")
        evaluator.onSessionClosed(realId, SessionSource.REAL_BLE)

        val raised = warnings.warnings.value.filter { it.kind == "BASELINE_DEVIATION" }
        assertEquals(2, raised.size)  // NOT merged into one row
        assertEquals(setOf("SIM", "REAL"), raised.map { it.source }.toSet())
    }

    @Test
    fun simSession_evaluatesAgainstSimHistory_raisesSimTagged() = runTest {
        val sessions = FakeSessionRepository()
        val warnings = FakeWarningRepository()
        val evaluator = PostSessionEvaluator(sessions, warnings, refs, now = { 0L })
        (0 until 8).forEach { addSession(sessions, day = it * 2 + 1, ltftWarmIdle = 4f + (it % 2) * 0.2f, source = "SIM_HEALTHY") }
        val id = addSession(sessions, day = 18, ltftWarmIdle = 8f, source = "SIM_FAULT")
        evaluator.onSessionClosed(id, SessionSource.SIM_FAULT)
        val raised = warnings.warnings.value.filter { it.kind == "BASELINE_DEVIATION" }
        assertEquals(1, raised.size)
        assertEquals("SIM", raised[0].source)
    }
}
