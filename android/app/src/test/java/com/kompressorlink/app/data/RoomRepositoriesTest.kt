package com.kompressorlink.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kompressorlink.app.data.db.KlDatabase
import com.kompressorlink.app.data.db.WarningEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomRepositoriesTest {
    private lateinit var db: KlDatabase

    @Before
    fun setUp() {
        db = KlDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun warning(key: String, detail: String, at: Long) = WarningEntity(
        createdAtEpochMs = at, lastSeenAtEpochMs = at, sessionId = null,
        subsystem = "FUELING", signal = "LTFT1", level = "WATCH", kind = "LIVE_OUT_OF_BAND",
        title = "t", detail = detail, acknowledged = false, source = "REAL", dedupeKey = key,
    )

    @Test
    fun raise_dedupes_openWarnings_butNotAcknowledged() = runTest {
        val repo = RoomWarningRepository(db)
        repo.raise(warning("k", "first", at = 1_000))
        repo.raise(warning("k", "second", at = 2_000))
        var all = repo.open(WarningSource.REAL)
        assertEquals(1, all.size)
        assertEquals("second", all[0].detail)
        assertEquals(2_000, all[0].lastSeenAtEpochMs)
        assertEquals(1_000, all[0].createdAtEpochMs)  // creation time preserved

        repo.acknowledge(all[0].id)
        repo.raise(warning("k", "third", at = 3_000))  // recurrence after ack = NEW row
        all = repo.open(WarningSource.REAL)
        assertEquals(1, all.size)
        assertEquals("third", all[0].detail)
    }

    @Test
    fun odometer_monotonicValidation() = runTest {
        val repo = RoomOdometerRepository(db)
        assertTrue(repo.addAnchor(186_900, atEpochMs = 1_000).isSuccess)
        val rejected = repo.addAnchor(150_000, atEpochMs = 2_000)
        assertTrue(rejected.isFailure)
        assertTrue(rejected.exceptionOrNull()!!.message!!.contains("186900 km"))
        assertEquals(186_900, repo.latestAnchor()!!.km)
    }

    @Test
    fun maintenance_seeding_isIdempotent() = runTest {
        val repo = RoomMaintenanceRepository(db)
        repo.ensureSeeded()
        repo.ensureSeeded()
        assertEquals(11, repo.items().count { it.builtin })
    }

    @Test
    fun maintenance_updateRejectsNoInterval() = runTest {
        val repo = RoomMaintenanceRepository(db)
        repo.ensureSeeded()
        val oil = repo.items().first { it.name == "Engine oil + filter" }
        assertTrue(repo.updateItem(oil.copy(intervalKm = null, intervalMonths = null)).isFailure)
        assertTrue(repo.updateItem(oil.copy(intervalKm = 12_000)).isSuccess)
    }

    @Test
    fun maintenance_logService_clearsNotifiedStamp() = runTest {
        val repo = RoomMaintenanceRepository(db)
        repo.ensureSeeded()
        val oil = repo.items().first { it.name == "Engine oil + filter" }
        repo.stampNotified(oil.id, atEpochMs = 5_000)
        assertEquals(5_000L, repo.itemById(oil.id)!!.lastNotifiedAtEpochMs)
        repo.logService(oil.id, epochMs = 6_000, km = 187_000, note = null)
        assertEquals(null, repo.itemById(oil.id)!!.lastNotifiedAtEpochMs)
        assertEquals(187_000, repo.latestLogFor(oil.id)!!.km)
    }
}
