package com.kompressorlink.app.data

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

// Plain JVM test (no Robolectric/Room needed) guarding a subtle fake-only
// behavior: FakeMaintenanceRepository.deleteCustomItem must be a true no-op
// on a builtin item's id, matching RoomMaintenanceRepository's guarded
// `DELETE ... WHERE id = :id AND builtin = 0` + FK ON DELETE CASCADE (which
// never fires when the guard blocks the delete). An earlier version of this
// fake cleared the item's service logs unconditionally, which would have
// silently diverged from Room for any future test exercising this path.
class FakeRepositoriesTest {
    @Test
    fun deleteCustomItem_onBuiltinItem_isNoOp_itemAndLogsBothSurvive() = runTest {
        val repo = FakeMaintenanceRepository()
        repo.seedWith(
            listOf(
                MaintenanceItemEntity(
                    name = "Engine oil + filter", category = "ENGINE",
                    intervalKm = 10_000, intervalMonths = 12,
                    note = "n", confidence = "Best estimate",
                    builtin = true, enabled = true, lastNotifiedAtEpochMs = null,
                )
            )
        )
        val builtin = repo.items().single()
        repo.logService(builtin.id, epochMs = 1_000, km = 100_000, note = null)

        repo.deleteCustomItem(builtin.id)

        assertNotNull("builtin item must survive a deleteCustomItem attempt", repo.itemById(builtin.id))
        val survivingLog = repo.latestLogFor(builtin.id)
        assertNotNull("builtin item's service log must survive a deleteCustomItem attempt", survivingLog)
        assertEquals(100_000, survivingLog!!.km)
    }

    // Guards against a real bug: ensureSeeded() previously routed through
    // seedWith(), which REPLACES items.value wholesale. A custom item added
    // before ensureSeeded() ran (e.g. a lazy-init ordering where the caller
    // adds a custom item before the seed check fires) would silently vanish,
    // diverging from RoomMaintenanceRepository.ensureSeeded() (purely
    // additive insertItems, guarded by builtinCount() == 0, never touches
    // non-builtin rows) and from the interface doc's own contract: "Inserts
    // BuiltinSchedule.ITEMS exactly once (idempotent)" -- inserts, not replaces.
    @Test
    fun ensureSeeded_isAdditive_preservesExistingCustomItems() = runTest {
        val repo = FakeMaintenanceRepository()
        val customId = repo.addCustomItem(
            MaintenanceItemEntity(
                name = "Custom timing belt check", category = "INSPECTION",
                intervalKm = 30_000, intervalMonths = null,
                note = "custom", confidence = "Guessing",
                builtin = false, enabled = true, lastNotifiedAtEpochMs = null,
            )
        ).getOrThrow()

        repo.ensureSeeded()

        assertNotNull("custom item added before ensureSeeded() must survive", repo.itemById(customId))
        assertEquals(11, repo.items().count { it.builtin })
        assertEquals(12, repo.items().size) // 11 seeded builtins + 1 surviving custom
    }
}
