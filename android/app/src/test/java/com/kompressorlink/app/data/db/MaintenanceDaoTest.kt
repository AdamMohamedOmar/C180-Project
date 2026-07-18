package com.kompressorlink.app.data.db

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kompressorlink.app.maintenance.BuiltinSchedule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MaintenanceDaoTest {
    private lateinit var db: KlDatabase

    @Before
    fun setUp() {
        db = KlDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun builtinSchedule_hasElevenItems_allWithAnInterval() {
        assertEquals(11, BuiltinSchedule.ITEMS.size)
        BuiltinSchedule.ITEMS.forEach {
            assertNotNull("${it.name} needs km or months", it.intervalKm ?: it.intervalMonths)
        }
    }

    @Test
    fun builtinDelete_isRefused_customDelete_works() = runTest {
        val dao = db.maintenanceDao()
        dao.insertItems(BuiltinSchedule.ITEMS)
        val builtinId = dao.items().first { it.builtin }.id
        assertEquals(0, dao.deleteCustomItem(builtinId))  // guard refuses, 0 rows affected
        assertNotNull(dao.itemById(builtinId))  // still there

        val customId = dao.insertItem(
            MaintenanceItemEntity(
                name = "Custom wax", category = "INSPECTION", intervalKm = null,
                intervalMonths = 6, note = "", confidence = "Best estimate",
                builtin = false, enabled = true, lastNotifiedAtEpochMs = null,
            )
        )
        assertEquals(1, dao.deleteCustomItem(customId))  // succeeds, 1 row affected
        assertNull(dao.itemById(customId))
    }

    @Test
    fun serviceLog_latestWins_cascadeOnItemDelete() = runTest {
        val dao = db.maintenanceDao()
        val id = dao.insertItem(
            MaintenanceItemEntity(
                name = "Oil", category = "ENGINE", intervalKm = 10_000, intervalMonths = 12,
                note = "", confidence = "Best estimate", builtin = false, enabled = true,
                lastNotifiedAtEpochMs = null,
            )
        )
        dao.insertLog(ServiceLogEntity(itemId = id, epochMs = 1_000, km = 180_000, note = null))
        dao.insertLog(ServiceLogEntity(itemId = id, epochMs = 2_000, km = 190_000, note = "with filter"))
        assertEquals(190_000, dao.latestLogFor(id)!!.km)
        dao.deleteCustomItem(id)
        assertNull(dao.latestLogFor(id))
    }

    @Test
    fun odometer_latestByTime() = runTest {
        val dao = db.odometerDao()
        dao.insert(OdometerEntryEntity(epochMs = 1_000, km = 186_000))
        dao.insert(OdometerEntryEntity(epochMs = 2_000, km = 186_900))
        assertEquals(186_900, dao.latest()!!.km)
        assertEquals(186_900, dao.maxKm())
    }
}
