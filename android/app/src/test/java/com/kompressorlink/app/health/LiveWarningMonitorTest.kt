package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.FakeWarningRepository
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
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
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWarningMonitorTest {

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

    private fun snap(ltft: Float): TelemetrySnapshot {
        val v = FloatArray(SIGNAL_COUNT)
        var mask = 0
        listOf(Signal.RPM to 750f, Signal.SPEED to 0f, Signal.ECT to 90f, Signal.LTFT1 to ltft)
            .forEach { (s, value) -> v[s.ordinal] = value; mask = mask or (1 shl s.ordinal) }
        return TelemetrySnapshot(v, mask, flags = 0, seq = 0, uptimeMs = 0)
    }

    @Test
    fun sustainedRed_raisesAttention_once_dedup() = runTest {
        val repo = FakeWarningRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        val monitor = LiveWarningMonitor(backgroundScope, src, choice, refs, repo,
            now = { testScheduler.currentTime })
        monitor.start()
        runCurrent()

        // LTFT 25% is raw RED (beyond ±10 + half-width 10). Hysteresis needs
        // 3 s sustained; frames every 500 ms.
        repeat(10) { src.telemetryFlow.emit(snap(25f)); advanceTimeBy(500) }
        runCurrent()

        assertEquals(GaugeLevel.RED, monitor.levels.value[Signal.LTFT1])
        val open = repo.warnings.value.filter { !it.acknowledged && it.kind == "LIVE_OUT_OF_BAND" }
        assertEquals(1, open.size)  // deduped despite repeated raises
        assertEquals("ATTENTION", open[0].level)
        assertEquals("FUELING", open[0].subsystem)
        assertEquals("REAL", open[0].source)
        assertTrue(open[0].detail.contains("25.0 %"))
    }

    // Regression test for the SIM/REAL dedupeKey collision bug: dedupeKey
    // used to omit source, so a SIM-tagged warning and a later REAL warning
    // for the SAME signal/subsystem would collide on `raise()`'s dedupe-upsert
    // and the REAL one would silently merge into (and never surface past) the
    // existing SIM row — permanently hiding a genuine real-car warning behind
    // the demo-mode quarantine filter. The fix makes dedupeKey source-inclusive.
    @Test
    fun sameSignalRed_underSimThenReal_raisesTwoSeparateWarnings() = runTest {
        val repo = FakeWarningRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.SIMULATED_FAULT)
        val monitor = LiveWarningMonitor(backgroundScope, src, choice, refs, repo,
            now = { testScheduler.currentTime })
        monitor.start()
        runCurrent()

        // LTFT 25% is raw RED under the SIM source.
        repeat(10) { src.telemetryFlow.emit(snap(25f)); advanceTimeBy(500) }
        runCurrent()

        // Switch to the real car while the SAME signal stays RED.
        choice.value = SourceChoice.REAL_BLE
        runCurrent()
        repeat(10) { src.telemetryFlow.emit(snap(25f)); advanceTimeBy(500) }
        runCurrent()

        val open = repo.warnings.value.filter {
            !it.acknowledged && it.kind == "LIVE_OUT_OF_BAND" && it.signal == "LTFT1"
        }
        assertEquals(2, open.size)  // NOT merged into one row
        assertEquals(setOf("SIM", "REAL"), open.map { it.source }.toSet())
    }

    @Test
    fun amber_needsSixtySecondDwell() = runTest {
        val repo = FakeWarningRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.SIMULATED_FAULT)
        LiveWarningMonitor(backgroundScope, src, choice, refs, repo,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        // LTFT 15% = raw AMBER. 30 s: level escalates (3 s) but no warning yet.
        repeat(60) { src.telemetryFlow.emit(snap(15f)); advanceTimeBy(500) }
        runCurrent()
        assertEquals(0, repo.warnings.value.size)

        // Another 40 s of AMBER crosses the 60 s dwell.
        repeat(80) { src.telemetryFlow.emit(snap(15f)); advanceTimeBy(500) }
        runCurrent()
        val open = repo.warnings.value
        assertEquals(1, open.size)
        assertEquals("WATCH", open[0].level)
        assertEquals("SIM", open[0].source)  // quarantine tag from the sim choice
    }

    @Test
    fun healthyStream_raisesNothing() = runTest {
        val repo = FakeWarningRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        LiveWarningMonitor(backgroundScope, src, choice, refs, repo,
            now = { testScheduler.currentTime }).start()
        runCurrent()
        repeat(20) { src.telemetryFlow.emit(snap(4.5f)); advanceTimeBy(500) }
        runCurrent()
        assertEquals(0, repo.warnings.value.size)
    }

    @Test
    fun dtcTransitions_raiseOncePerCode() = runTest {
        val repo = FakeWarningRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        LiveWarningMonitor(backgroundScope, src, choice, refs, repo,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        src.dtcFlow.value = DtcReport(stored = listOf("P0171"), pending = listOf("P1570"))
        runCurrent()
        src.dtcFlow.value = DtcReport(stored = listOf("P0171"), pending = listOf("P1570"))
        runCurrent()

        val open = repo.warnings.value
        assertEquals(2, open.size)
        val storedWarning = open.first { it.title.contains("P0171") }
        assertEquals("ATTENTION", storedWarning.level)
        assertEquals("FUELING", storedWarning.subsystem)
        val pendingWarning = open.first { it.title.contains("P1570") }
        assertEquals("WATCH", pendingWarning.level)
        assertEquals("OTHER", pendingWarning.subsystem)
    }

    // Regression test for the class of bug SessionRecorder's DTC latch had
    // (review fix there: seed from the StateFlow's CURRENT value, not just
    // future changes). Here the DTC diffing lives directly in the
    // dtcReport.collect{} closure with no "session open" gate to race
    // against, and dtcReport is a StateFlow -- collect() replays its current
    // value to a brand-new collector. So a code already latched BEFORE
    // start() is called must still surface as "new" against the initial
    // emptySet() and raise a warning on the very first collection.
    @Test
    fun preExistingDtc_raisesOnFirstCollection() = runTest {
        val repo = FakeWarningRepository()
        val src = ScriptedSource()
        src.dtcFlow.value = DtcReport(stored = listOf("P0171"), pending = emptyList())
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        LiveWarningMonitor(backgroundScope, src, choice, refs, repo,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        val open = repo.warnings.value
        assertEquals(1, open.size)
        assertTrue(open[0].title.contains("P0171"))
        assertEquals("ATTENTION", open[0].level)
        assertEquals("FUELING", open[0].subsystem)
        assertEquals("REAL", open[0].source)
    }
}
