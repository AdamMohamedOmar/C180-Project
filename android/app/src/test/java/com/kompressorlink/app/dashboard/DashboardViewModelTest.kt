package com.kompressorlink.app.dashboard

import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import java.io.File
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

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

    private class ScriptedSource : TelemetrySource {
        val telemetryFlow = MutableSharedFlow<TelemetrySnapshot>()
        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Simulated("healthy"))
        override val telemetry: Flow<TelemetrySnapshot> = telemetryFlow
        override val dtcReport: StateFlow<DtcReport?> = MutableStateFlow(null)
        override suspend fun sendTimeSync() {}
        override suspend fun requestWifiSync(): Boolean = false
    }

    private fun snap(vararg pairs: Pair<Signal, Float>): TelemetrySnapshot {
        val v = FloatArray(SIGNAL_COUNT)
        var mask = 0
        pairs.forEach { (s, value) -> v[s.ordinal] = value; mask = mask or (1 shl s.ordinal) }
        return TelemetrySnapshot(v, mask, flags = 0, seq = 0, uptimeMs = 0)
    }

    @Test
    fun gauges_useMonitorLevels_andWarmIdleFlag() = runTest(dispatcher) {
        val src = ScriptedSource()
        val levels = MutableStateFlow(mapOf(Signal.LTFT1 to GaugeLevel.RED))
        val vm = DashboardViewModel(src, refs, levels)
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        src.telemetryFlow.emit(snap(
            Signal.RPM to 750f, Signal.SPEED to 0f, Signal.ECT to 90f,
            Signal.LTFT1 to 4f,  // raw would be OK — displayed RED comes from the monitor
        ))
        runCurrent()

        val state = vm.uiState.first { it.gauges.isNotEmpty() }
        assertTrue(state.warmIdle)
        val ltft = state.gauges.first { it.signal == Signal.LTFT1 }
        assertEquals(GaugeLevel.RED, ltft.level)
        val rpm = state.gauges.first { it.signal == Signal.RPM }
        assertEquals(GaugeLevel.NEUTRAL, rpm.level)  // no band, not in monitor map
        collector.cancel()
    }

    @Test
    fun histories_accumulateOncePerEmission() = runTest(dispatcher) {
        val src = ScriptedSource()
        val vm = DashboardViewModel(src, refs, MutableStateFlow(emptyMap()))
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        repeat(3) {
            src.telemetryFlow.emit(snap(Signal.RPM to (700f + it * 10)))
            runCurrent()
        }
        val state = vm.uiState.first { it.gauges.isNotEmpty() }
        val rpm = state.gauges.first { it.signal == Signal.RPM }
        assertEquals(listOf(700f, 710f, 720f), rpm.history)
        collector.cancel()
    }

    @Test
    fun unavailableGauge_staysHonest() = runTest(dispatcher) {
        val src = ScriptedSource()
        val vm = DashboardViewModel(src, refs, MutableStateFlow(emptyMap()))
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        src.telemetryFlow.emit(snap(Signal.RPM to 750f))  // battery masked out
        runCurrent()
        val state = vm.uiState.first { it.gauges.isNotEmpty() }
        val batt = state.gauges.first { it.signal == Signal.BATT_V_ADC }
        assertEquals(GaugeLevel.UNAVAILABLE, batt.level)
        assertEquals("—", batt.valueText)
        collector.cancel()
    }
}
