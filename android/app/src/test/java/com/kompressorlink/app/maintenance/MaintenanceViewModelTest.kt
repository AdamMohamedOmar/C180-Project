package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.FakeMaintenanceRepository
import com.kompressorlink.app.data.FakeOdometerRepository
import com.kompressorlink.app.data.FakeSessionRepository
import com.kompressorlink.app.data.db.SessionEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class MaintenanceViewModelTest {

    private val zone = ZoneId.of("UTC")
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun buildVm(
        maintenance: FakeMaintenanceRepository,
        odometer: FakeOdometerRepository = FakeOdometerRepository(),
        sessions: FakeSessionRepository = FakeSessionRepository(),
        nowDate: LocalDate = LocalDate.of(2026, 7, 13),
    ) = MaintenanceViewModel(maintenance, odometer, sessions, now = { ms(nowDate) }, zone = zone)

    @Test
    fun seededItems_sortByUrgency_thenName() = runTest(dispatcher) {
        val maintenance = FakeMaintenanceRepository()
        maintenance.ensureSeeded()
        val odometer = FakeOdometerRepository()
        odometer.addAnchor(187_000, ms(LocalDate.of(2026, 7, 1)))
        val vm = buildVm(maintenance, odometer)
        val collector = launch { vm.uiState.collect {} }  // keep WhileSubscribed alive
        runCurrent()

        // Overdue brake fluid (26 months ago), due-soon oil (11.5 months ago),
        // everything else never logged.
        val brake = maintenance.items().first { it.name == "Brake fluid" }
        val oil = maintenance.items().first { it.name == "Engine oil + filter" }
        maintenance.logService(brake.id, ms(LocalDate.of(2024, 5, 1)), km = null, note = null)
        maintenance.logService(oil.id, ms(LocalDate.of(2025, 8, 1)), km = 182_500, note = null)
        runCurrent()

        val state = vm.uiState.first { it.items.isNotEmpty() }
        assertEquals("Brake fluid", state.items[0].item.name)          // OVERDUE first
        assertEquals(DueState.OVERDUE, state.items[0].info.state)
        assertEquals("Engine oil + filter", state.items[1].item.name)  // DUE_SOON (19 days left)
        assertEquals(DueState.DUE_SOON, state.items[1].info.state)
        assertTrue(state.items.drop(2).all { it.info.state == DueState.NEVER_LOGGED })
        collector.cancel()
    }

    @Test
    fun odometerEstimate_combinesAnchorAndRealSessions() = runTest(dispatcher) {
        val maintenance = FakeMaintenanceRepository()
        maintenance.ensureSeeded()
        val odometer = FakeOdometerRepository()
        val anchorAt = ms(LocalDate.of(2026, 7, 1))
        odometer.addAnchor(186_900, anchorAt)
        val sessions = FakeSessionRepository()
        sessions.record(
            SessionEntity(
                startedAtEpochMs = anchorAt + 1_000, endedAtEpochMs = anchorAt + 2_000,
                source = "REAL_BLE", snapshotCount = 200, warmIdleSeconds = 0f,
                distanceKm = 550.4f, hasStoredDtc = false,
            )
        ) { emptyList() }
        val vm = buildVm(maintenance, odometer, sessions)
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        val state = vm.uiState.first { it.odometer.estimatedKm != null }
        assertEquals(187_450, state.odometer.estimatedKm)
        assertEquals(186_900, state.odometer.anchorKm)
        assertEquals(550, state.odometer.estimatedDeltaKm)
        collector.cancel()
    }

    @Test
    fun backwardsOdometer_surfacesMessage() = runTest(dispatcher) {
        val maintenance = FakeMaintenanceRepository()
        maintenance.ensureSeeded()
        val odometer = FakeOdometerRepository()
        odometer.addAnchor(186_900, ms(LocalDate.of(2026, 7, 1)))
        val vm = buildVm(maintenance, odometer)
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        vm.updateOdometer(150_000)
        runCurrent()
        val state = vm.uiState.first { it.message != null }
        assertTrue(state.message!!.contains("can't go backwards"))
        collector.cancel()
    }

    @Test
    fun loggingService_movesItemOutOfOverdue() = runTest(dispatcher) {
        val maintenance = FakeMaintenanceRepository()
        maintenance.ensureSeeded()
        val vm = buildVm(maintenance)
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        val brake = maintenance.items().first { it.name == "Brake fluid" }
        maintenance.logService(brake.id, ms(LocalDate.of(2024, 5, 1)), km = null, note = null)
        runCurrent()
        var state = vm.uiState.first { s -> s.items.any { it.info.state == DueState.OVERDUE } }
        assertEquals(DueState.OVERDUE, state.items[0].info.state)

        vm.logService(brake.id, ms(LocalDate.of(2026, 7, 13)), km = null, note = "flushed")
        runCurrent()
        state = vm.uiState.first { s -> s.items.none { it.info.state == DueState.OVERDUE } }
        val brakeRow = state.items.first { it.item.name == "Brake fluid" }
        assertEquals(DueState.OK, brakeRow.info.state)
        assertEquals("flushed", brakeRow.lastLog!!.note)
        collector.cancel()
    }
}
