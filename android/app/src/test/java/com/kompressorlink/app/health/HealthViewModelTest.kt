package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.FakeSessionRepository
import com.kompressorlink.app.data.FakeWarningRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {

    private val refs = ReferenceRepository { name -> File("src/main/assets/$name").readText() }
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class StubSource(dtc: DtcReport? = null) : TelemetrySource {
        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Simulated("stub"))
        override val telemetry: Flow<TelemetrySnapshot> = MutableSharedFlow()
        override val dtcReport: StateFlow<DtcReport?> = MutableStateFlow(dtc)
        override suspend fun sendTimeSync() {}
        override suspend fun requestWifiSync(): Boolean = false
    }

    private suspend fun addSimSession(repo: FakeSessionRepository, day: Int, ltft: Float) {
        val endedAt = day * Baseline.DAY_MS
        repo.record(
            SessionEntity(
                startedAtEpochMs = endedAt - 600_000, endedAtEpochMs = endedAt,
                source = "SIM_HEALTHY", snapshotCount = 1200, warmIdleSeconds = 300f,
                distanceKm = null, hasStoredDtc = false,
            )
        ) { id ->
            listOf(SessionStatEntity(
                sessionId = id, signal = "LTFT1", sampleCount = 1000,
                mean = ltft, min = ltft - 1, max = ltft + 1, stdDev = 0.5f,
                secondsOutOfBand = 0f, worstLevel = "OK",
                warmIdleMean = ltft, warmIdleCount = 200,
                engineRunningMean = null, engineRunningCount = 0,
                engineOffMean = null, engineOffCount = 0,
            ))
        }
    }

    private fun vm(
        sessions: FakeSessionRepository = FakeSessionRepository(),
        warnings: FakeWarningRepository = FakeWarningRepository(),
        levels: MutableStateFlow<Map<Signal, GaugeLevel>> = MutableStateFlow(emptyMap()),
        source: TelemetrySource = StubSource(),
    ) = HealthViewModel(sessions, warnings, levels, source, refs, ZoneId.of("UTC"))

    @Test
    fun emptyRealHistory_showsHonestEmptyState() = runTest(dispatcher) {
        val viewModel = vm()
        val collector = launch { viewModel.uiState.collect {} }
        runCurrent()
        val state = viewModel.uiState.first { it.cards.isNotEmpty() }
        assertNotNull(state.emptyState)
        assertTrue(state.emptyState!!.contains("Demo mode"))
        assertEquals(HealthStatus.OK, state.verdict.status)
        collector.cancel()
    }

    @Test
    fun demoMode_switchesToSimRows() = runTest(dispatcher) {
        val sessions = FakeSessionRepository()
        (0 until 6).forEach { addSimSession(sessions, day = it * 3, ltft = 4f) }
        val viewModel = vm(sessions = sessions)
        val collector = launch { viewModel.uiState.collect {} }
        runCurrent()

        var state = viewModel.uiState.first { it.cards.isNotEmpty() }
        assertNotNull(state.emptyState)  // real mode: nothing recorded

        viewModel.setDemoMode(true)
        runCurrent()
        state = viewModel.uiState.first { it.demoMode }
        assertEquals(null, state.emptyState)
        // 6 eligible sim sessions -> baseline still gated, footer explains drift gate
        val fueling = state.cards.first { it.subsystem == Subsystem.FUELING }
        assertEquals("Collecting baseline · 6 of 8 drives", fueling.baselineGate)
        assertNotNull(state.driftGateFooter)
        collector.cancel()
    }

    @Test
    fun storedDtc_drivesVerdictThroughRollup() = runTest(dispatcher) {
        val viewModel = vm(source = StubSource(DtcReport(stored = listOf("P0171"), pending = emptyList())))
        val collector = launch { viewModel.uiState.collect {} }
        runCurrent()
        val state = viewModel.uiState.first { it.verdict.status == HealthStatus.ATTENTION }
        assertEquals("Needs a look", state.verdict.headline)
        assertEquals("1 system needs attention — Fueling", state.verdict.subline)
        collector.cancel()
    }

    @Test
    fun acknowledge_flowsToRepository_andFeedDims() = runTest(dispatcher) {
        val warnings = FakeWarningRepository()
        warnings.raise(WarningEntity(
            createdAtEpochMs = 1_000, lastSeenAtEpochMs = 1_000, sessionId = null,
            subsystem = "FUELING", signal = "LTFT1", level = "WATCH", kind = "LIVE_OUT_OF_BAND",
            title = "LTFT outside its band", detail = "d", acknowledged = false,
            source = "REAL", dedupeKey = "k",
        ))
        val viewModel = vm(warnings = warnings)
        val collector = launch { viewModel.uiState.collect {} }
        runCurrent()

        var state = viewModel.uiState.first { it.warnings.isNotEmpty() }
        assertEquals(false, state.warnings[0].acknowledged)

        viewModel.acknowledge(state.warnings[0].id)
        runCurrent()
        state = viewModel.uiState.first { it.warnings.isNotEmpty() && it.warnings[0].acknowledged }
        assertEquals(true, state.warnings[0].acknowledged)
        collector.cancel()
    }

    private suspend fun addO2OnsetSession(repo: FakeSessionRepository, day: Int, onsetS: Float): Long {
        val endedAt = day * Baseline.DAY_MS
        return repo.record(
            SessionEntity(
                startedAtEpochMs = endedAt - 600_000, endedAtEpochMs = endedAt,
                source = "REAL_BLE", snapshotCount = 1200, warmIdleSeconds = 300f,
                distanceKm = 10f, hasStoredDtc = false,
            )
        ) { id ->
            listOf(SessionStatEntity(
                sessionId = id, signal = "O2_B1S1_V", sampleCount = 100,
                mean = onsetS, min = onsetS, max = onsetS, stdDev = 0f,
                secondsOutOfBand = 0f, worstLevel = "OK",
                warmIdleMean = null, warmIdleCount = 0,
                engineRunningMean = null, engineRunningCount = 0,
                engineOffMean = null, engineOffCount = 0,
                o2OnsetS = onsetS,
            ))
        }
    }

    // Task 6 (2026-07-17 enhancement plan): the three band-less new metrics
    // (ECT_WARMUP_RATE, MAF_HIGH_LOAD, O2_ACTIVITY_ONSET) have no per-metric
    // UI row anywhere in HealthScreen -- SubsystemCard carries one winning
    // `reason` string per subsystem, never a per-metric breakdown. Their
    // ONLY route to the user is a real PostSessionEvaluator
    // BASELINE_DEVIATION WarningEntity flowing back through
    // HealthRollup.cards()'s openWarnings contribution. Unlike
    // HealthRollupTest's hand-typed-string version of this proof, this runs
    // the REAL PostSessionEvaluator (not a mirrored string) against real
    // session history, then feeds the SAME fake repos into HealthViewModel
    // and reads the actual rendered FUELING card -- end to end, exactly the
    // fake-repo + runTest fixture style 4.5 established in this file.
    //
    // The first half (through the `raised[0]` assertions) re-covers the same
    // generic band-null branch PostSessionEvaluatorTest's
    // bandlessMetric_deviationStillRaisesWatch_withoutClaimingAbsoluteLimits
    // already proves for ECT_WARMUP_RATE -- kept here as necessary scaffolding
    // (this test needs a real warning to feed HealthViewModel, and using a
    // second band-less metric cheaply reinforces that the mechanism is
    // metric-agnostic). The genuinely new part is everything from `vm(...)`
    // on: the ViewModel-render/card assertion nothing else covers.
    @Test
    fun bandlessNewMetric_realBaselineDeviationWarning_surfacesAsFuelingCardReason_neverHeaterClaim() = runTest(dispatcher) {
        val sessions = FakeSessionRepository()
        val warnings = FakeWarningRepository()
        val evaluator = PostSessionEvaluator(sessions, warnings, refs, now = { 0L })

        // 8 healthy cold-start O2 activity onsets (~30-34 s) over 14 days,
        // then one session where the sensor took much longer to show
        // activity (90 s) -- a real personal-envelope deviation, no band
        // needed (O2_ACTIVITY_ONSET is baseline-only).
        (0 until 8).forEach { addO2OnsetSession(sessions, day = it * 2 + 1, onsetS = 30f + (it % 2) * 4f) }
        val currentId = addO2OnsetSession(sessions, day = 18, onsetS = 90f)

        evaluator.onSessionClosed(currentId, SessionSource.REAL_BLE)

        val raised = warnings.warnings.value.filter { it.kind == "BASELINE_DEVIATION" }
        assertEquals(1, raised.size)
        val warning = raised[0]
        assertEquals("WATCH", warning.level)   // baseline/drift never raises ATTENTION
        assertEquals("FUELING", warning.subsystem)
        assertTrue(warning.detail.contains("outside your car's usual range"))
        assertFalse(warning.detail.contains("absolute limits"))  // band-less: no such claim
        assertFalse(warning.title.contains("heater", ignoreCase = true))
        assertFalse(warning.detail.contains("heater", ignoreCase = true))

        val viewModel = vm(sessions = sessions, warnings = warnings)
        val collector = launch { viewModel.uiState.collect {} }
        runCurrent()
        val state = viewModel.uiState.first { it.cards.isNotEmpty() }

        val fueling = state.cards.first { it.subsystem == Subsystem.FUELING }
        assertEquals(HealthStatus.WATCH, fueling.status)
        assertEquals(warning.title, fueling.reason)
        assertFalse(fueling.reason.contains("heater", ignoreCase = true))
        collector.cancel()
    }
}
