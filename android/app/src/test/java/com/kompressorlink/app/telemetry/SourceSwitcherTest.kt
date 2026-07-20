package com.kompressorlink.app.telemetry

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceSwitcherTest {

    private class FakeStore : SourceChoiceStore {
        val flow = MutableStateFlow(SourceChoice.SIMULATED_HEALTHY)
        override val choice: Flow<SourceChoice> = flow
        override suspend fun set(choice: SourceChoice) { flow.value = choice }
    }

    private class StubSource(
        name: String,
        initialDtcReport: DtcReport? = null,
        private val wifiSyncResult: Boolean = true,
    ) : TelemetrySource {
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Simulated(name))
        override val telemetry: Flow<TelemetrySnapshot> = emptyFlow()
        override val dtcReport = MutableStateFlow(initialDtcReport)
        var timeSyncCount = 0
        override suspend fun sendTimeSync() { timeSyncCount++ }
        var wifiSyncCount = 0
        override suspend fun requestWifiSync(): Boolean { wifiSyncCount++; return wifiSyncResult }
    }

    @Test
    fun `starts on the store's choice and switches when it changes`() = runTest {
        val store = FakeStore()
        val healthy = StubSource("healthy")
        val fault = StubSource("fault")
        val switcher = SourceSwitcher(backgroundScope, store) { choice ->
            if (choice == SourceChoice.SIMULATED_FAULT) fault else healthy
        }
        runCurrent()
        assertEquals(ConnectionState.Simulated("healthy"), switcher.connectionState.value)

        store.set(SourceChoice.SIMULATED_FAULT)
        runCurrent()
        assertEquals(ConnectionState.Simulated("fault"), switcher.connectionState.value)
    }

    @Test
    fun `routes sendTimeSync to the currently active source`() = runTest {
        val store = FakeStore()
        val healthy = StubSource("healthy")
        val fault = StubSource("fault")
        val switcher = SourceSwitcher(backgroundScope, store) { choice ->
            if (choice == SourceChoice.SIMULATED_FAULT) fault else healthy
        }
        runCurrent()
        switcher.sendTimeSync()
        store.set(SourceChoice.SIMULATED_FAULT)
        runCurrent()
        switcher.sendTimeSync()
        assertEquals(1, healthy.timeSyncCount)
        assertEquals(1, fault.timeSyncCount)
    }

    @Test
    fun `routes requestWifiSync to the currently active source`() = runTest {
        val store = FakeStore()
        // healthy and fault deliberately return DIFFERENT values -- proves
        // SourceSwitcher threads through the active source's actual result
        // rather than a value hardcoded in SourceSwitcher itself.
        val healthy = StubSource("healthy", wifiSyncResult = true)
        val fault = StubSource("fault", wifiSyncResult = false)
        val switcher = SourceSwitcher(backgroundScope, store) { choice ->
            if (choice == SourceChoice.SIMULATED_FAULT) fault else healthy
        }
        runCurrent()
        assertEquals(true, switcher.requestWifiSync())
        store.set(SourceChoice.SIMULATED_FAULT)
        runCurrent()
        assertEquals(false, switcher.requestWifiSync())
        assertEquals(1, healthy.wifiSyncCount)
        assertEquals(1, fault.wifiSyncCount)
    }

    @Test
    fun `seeds connectionState and dtcReport from initialChoice before the store's real value is collected`() = runTest {
        val store = FakeStore()
        // Store's persisted choice is HEALTHY; initialChoice below is FAULT. Using
        // two different choices means the assertions can only pass if the
        // synchronous initialChoice seed was genuinely used — if SourceSwitcher
        // regressed to reading store.choice's value instead, these would observe
        // healthy's data (empty DTC report) and fail.
        store.flow.value = SourceChoice.SIMULATED_HEALTHY
        val healthyReport = DtcReport(stored = emptyList(), pending = emptyList())
        val faultReport = DtcReport(stored = listOf("P0171"), pending = emptyList())
        val healthy = StubSource("healthy", healthyReport)
        val fault = StubSource("fault", faultReport)
        val switcher = SourceSwitcher(
            backgroundScope,
            store,
            initialChoice = SourceChoice.SIMULATED_FAULT,
        ) { choice ->
            if (choice == SourceChoice.SIMULATED_FAULT) fault else healthy
        }
        // No runCurrent() here: this asserts the *synchronous* seed value, before
        // the async collection of store.choice has had a chance to run.
        assertEquals(ConnectionState.Simulated("fault"), switcher.connectionState.value)
        assertEquals(faultReport, switcher.dtcReport.value)
    }
}
