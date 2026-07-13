package com.kompressorlink.app.dashboard

import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Regression coverage for the combine()-driven history double-append bug:
// a connectionState-only emission (e.g. Connecting -> Ready, independent of
// telemetry tick timing) must NOT re-append the latest cached telemetry
// snapshot to the sparkline ring buffers. History size must track genuine
// telemetry emissions 1:1.
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    // A TelemetrySource whose telemetry emissions are driven one at a time
    // by the test (via a Channel), decoupled from connectionState changes
    // driven independently through a StateFlow -- mirrors how the real BLE
    // source's connectionState ticks on its own schedule.
    private class ControllableSource : TelemetrySource {
        val telemetryChannel = Channel<TelemetrySnapshot>(Channel.UNLIMITED)
        override val telemetry: Flow<TelemetrySnapshot> = telemetryChannel.receiveAsFlow()
        override val connectionState: MutableStateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Connecting)
        override val dtcReport: StateFlow<DtcReport?> = MutableStateFlow(null)
        override suspend fun sendTimeSync() {}
    }

    private fun snapshotWithRpm(rpm: Float, seq: Int): TelemetrySnapshot {
        val values = FloatArray(SIGNAL_COUNT)
        values[Signal.RPM.ordinal] = rpm
        val mask = 1 shl Signal.RPM.ordinal
        return TelemetrySnapshot(values, mask, flags = 0, seq = seq, uptimeMs = seq * 500L)
    }

    // Bands/DTC assets are irrelevant to this test -- empty JSON arrays keep
    // ReferenceRepository from touching the filesystem.
    private val refs = ReferenceRepository { "[]" }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `connectionState-only emissions do not double-append telemetry history`() = runTest {
        val source = ControllableSource()
        val viewModel = DashboardViewModel(source, refs)

        val states = mutableListOf<DashboardUiState>()
        backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
        runCurrent()

        fun rpmHistorySize(): Int =
            states.last().gauges.first { it.signal == Signal.RPM }.history.size

        // Three genuine telemetry emissions.
        source.telemetryChannel.send(snapshotWithRpm(750f, 0))
        runCurrent()
        source.telemetryChannel.send(snapshotWithRpm(780f, 1))
        runCurrent()
        source.telemetryChannel.send(snapshotWithRpm(760f, 2))
        runCurrent()
        assertEquals(3, rpmHistorySize())

        // Several connectionState-only transitions, no new telemetry.
        source.connectionState.value = ConnectionState.Ready(demo = false, klineConnected = true)
        runCurrent()
        source.connectionState.value = ConnectionState.Disconnected
        runCurrent()
        source.connectionState.value = ConnectionState.Ready(demo = false, klineConnected = false)
        runCurrent()

        // History must be unchanged -- connectionState churn alone must
        // never mutate the ring buffers.
        assertEquals(3, rpmHistorySize())

        // connectionLabel must still reflect the latest connectionState
        // (the combine() re-derivation itself must keep working).
        assertEquals("Live · K-line down", states.last().connectionLabel)

        // A fourth genuine telemetry emission grows history by exactly one.
        source.telemetryChannel.send(snapshotWithRpm(770f, 3))
        runCurrent()
        assertEquals(4, rpmHistorySize())
    }
}
