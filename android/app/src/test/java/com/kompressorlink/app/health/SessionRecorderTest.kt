package com.kompressorlink.app.health

import com.kompressorlink.app.data.FakeSessionRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.FakeScenario
import com.kompressorlink.app.telemetry.FakeTelemetrySource
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecorderTest {

    private val refs = ReferenceRepository { name -> File("src/main/assets/$name").readText() }

    private class ScriptedSource : TelemetrySource {
        val telemetryFlow = MutableSharedFlow<TelemetrySnapshot>()
        val dtcFlow = MutableStateFlow<DtcReport?>(null)
        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Simulated("scripted"))
        override val telemetry: Flow<TelemetrySnapshot> = telemetryFlow
        override val dtcReport: StateFlow<DtcReport?> = dtcFlow
        override suspend fun sendTimeSync() {}
        override suspend fun requestWifiSync(): Boolean = false
    }

    private fun speedSnap(speedKmh: Float) : TelemetrySnapshot {
        val v = FloatArray(SIGNAL_COUNT)
        var mask = 0
        listOf(Signal.RPM to 2000f, Signal.SPEED to speedKmh).forEach { (s, value) ->
            v[s.ordinal] = value
            mask = mask or (1 shl s.ordinal)
        }
        return TelemetrySnapshot(v, mask, flags = 0, seq = 0, uptimeMs = 0)
    }

    @Test
    fun realSession_persistsAfterGap_withStatsAndDistance() = runTest {
        val repo = FakeSessionRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        var closedSource: SessionSource? = null
        SessionRecorder(
            backgroundScope, src, choice, repo, refs,
            now = { testScheduler.currentTime },  // see note below
            onSessionClosed = { _, s -> closedSource = s },
        ).start()
        runCurrent()

        repeat(150) {  // 150 snaps, 500 ms apart = 74.5 s span
            src.telemetryFlow.emit(speedSnap(60f))
            advanceTimeBy(500)
        }
        advanceTimeBy(HealthTuning.SESSION_CLOSE_GAP_MS + 1_000)
        runCurrent()

        assertEquals(1, repo.sessions.value.size)
        val session = repo.sessions.value[0]
        assertEquals("REAL_BLE", session.source)
        assertEquals(150, session.snapshotCount)
        assertTrue(session.distanceKm!! > 1.0f)   // ~1.24 km at 60 km/h over 74.5 s
        assertTrue(repo.stats.value.any { it.signal == "SPEED" })
        assertEquals(SessionSource.REAL_BLE, closedSource)
    }

    @Test
    fun shortBlip_isDiscarded() = runTest {
        val repo = FakeSessionRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        SessionRecorder(backgroundScope, src, choice, repo, refs,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        repeat(20) {  // 9.5 s, 20 snaps — under both gates
            src.telemetryFlow.emit(speedSnap(0f))
            advanceTimeBy(500)
        }
        advanceTimeBy(HealthTuning.SESSION_CLOSE_GAP_MS + 1_000)
        runCurrent()
        assertEquals(0, repo.sessions.value.size)
    }

    @Test
    fun sourceSwitch_closesAndRetags() = runTest {
        val repo = FakeSessionRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        SessionRecorder(backgroundScope, src, choice, repo, refs,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        repeat(150) {
            src.telemetryFlow.emit(speedSnap(60f))
            advanceTimeBy(500)
        }
        choice.value = SourceChoice.SIMULATED_HEALTHY   // switch closes the REAL session
        runCurrent()
        assertEquals(1, repo.sessions.value.size)
        assertEquals("REAL_BLE", repo.sessions.value[0].source)

        repeat(150) {  // second session under the sim tag
            src.telemetryFlow.emit(FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, (it * 500).toLong(), it))
            advanceTimeBy(500)
        }
        advanceTimeBy(HealthTuning.SESSION_CLOSE_GAP_MS + 1_000)
        runCurrent()
        assertEquals(2, repo.sessions.value.size)
        val sim = repo.sessions.value[1]
        assertEquals("SIM_HEALTHY", sim.source)
        assertNull(sim.distanceKm)  // quarantine: sim never reports distance
    }

    @Test
    fun dtcReport_latchesIntoSession() = runTest {
        val repo = FakeSessionRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        SessionRecorder(backgroundScope, src, choice, repo, refs,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        repeat(75) {
            src.telemetryFlow.emit(speedSnap(60f))
            advanceTimeBy(500)
        }
        src.dtcFlow.value = DtcReport(stored = listOf("P0171"), pending = emptyList())
        runCurrent()
        repeat(75) {
            src.telemetryFlow.emit(speedSnap(60f))
            advanceTimeBy(500)
        }
        advanceTimeBy(HealthTuning.SESSION_CLOSE_GAP_MS + 1_000)
        runCurrent()
        assertEquals(true, repo.sessions.value[0].hasStoredDtc)
    }

    @Test
    fun preExistingDtc_seedsNewSession() = runTest {
        val repo = FakeSessionRepository()
        val src = ScriptedSource()
        // Already latched BEFORE the recorder even starts -- e.g. an app restart
        // with the check-engine light already on from a previous drive. No DTC
        // *change* event will ever fire during this session; the only way the
        // session gets flagged is by seeding from the StateFlow's current value
        // at session-open time (review fix).
        src.dtcFlow.value = DtcReport(stored = listOf("P0171"), pending = emptyList())
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        SessionRecorder(backgroundScope, src, choice, repo, refs,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        repeat(150) {
            src.telemetryFlow.emit(speedSnap(60f))
            advanceTimeBy(500)
        }
        advanceTimeBy(HealthTuning.SESSION_CLOSE_GAP_MS + 1_000)
        runCurrent()

        assertEquals(1, repo.sessions.value.size)
        assertEquals(true, repo.sessions.value[0].hasStoredDtc)
    }

    @Test
    fun duplicateSourceChoiceEmission_doesNotSplitSession() = runTest {
        val repo = FakeSessionRepository()
        val src = ScriptedSource()
        // MutableStateFlow conflates equal consecutive values -- re-setting
        // `.value` to its current value produces NO emission at all, so it can't
        // regression-test the `current != event.to` de-dupe guard in
        // SessionRecorder. MutableSharedFlow does NOT conflate: emitting the
        // same SourceChoice twice in a row produces two real events, just like a
        // real Preferences DataStore re-emitting on any write to the same file
        // even when the stored value is unchanged.
        val choice = MutableSharedFlow<SourceChoice>()
        SessionRecorder(backgroundScope, src, choice, repo, refs,
            now = { testScheduler.currentTime }).start()
        runCurrent()  // let the recorder's collectors subscribe before emitting
        choice.emit(SourceChoice.REAL_BLE)
        runCurrent()

        repeat(75) {
            src.telemetryFlow.emit(speedSnap(60f))
            advanceTimeBy(500)
        }
        choice.emit(SourceChoice.REAL_BLE)  // duplicate emission of the SAME choice, mid-session
        runCurrent()
        repeat(75) {  // snapshots keep arriving on both sides of the duplicate emission
            src.telemetryFlow.emit(speedSnap(60f))
            advanceTimeBy(500)
        }
        advanceTimeBy(HealthTuning.SESSION_CLOSE_GAP_MS + 1_000)
        runCurrent()

        // One continuous session, not spuriously split by the duplicate emission.
        assertEquals(1, repo.sessions.value.size)
        assertEquals(150, repo.sessions.value[0].snapshotCount)
    }
}
