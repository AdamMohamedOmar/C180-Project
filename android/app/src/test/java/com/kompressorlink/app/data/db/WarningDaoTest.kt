package com.kompressorlink.app.data.db

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class WarningDaoTest {
    private lateinit var db: KlDatabase
    private lateinit var dao: WarningDao

    @Before
    fun setUp() {
        db = KlDatabase.inMemory(ApplicationProvider.getApplicationContext())
        dao = db.warningDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun warning(key: String, acknowledged: Boolean = false, source: String = "REAL") =
        WarningEntity(
            createdAtEpochMs = 1_000, lastSeenAtEpochMs = 1_000, sessionId = null,
            subsystem = "FUELING", signal = "LTFT1", level = "WATCH",
            kind = "LIVE_OUT_OF_BAND", title = "LTFT out of range",
            detail = "detail", acknowledged = acknowledged, source = source, dedupeKey = key,
        )

    @Test
    fun openByKey_ignoresAcknowledged() = runTest {
        dao.insert(warning("k1", acknowledged = true))
        assertNull(dao.openByKey("k1"))
        dao.insert(warning("k1", acknowledged = false))
        assertNotNull(dao.openByKey("k1"))
    }

    @Test
    fun acknowledge_flipsFlag() = runTest {
        val id = dao.insert(warning("k2"))
        dao.acknowledge(id)
        assertNull(dao.openByKey("k2"))
        assertEquals(0, dao.openBySource("REAL").size)
    }

    @Test
    fun observeBySource_filtersAndOrders() = runTest {
        dao.insert(warning("real-1", source = "REAL").copy(lastSeenAtEpochMs = 1_000))
        dao.insert(warning("sim-1", source = "SIM"))
        dao.insert(warning("real-2", acknowledged = true, source = "REAL"))
        dao.insert(warning("real-3", source = "REAL").copy(lastSeenAtEpochMs = 2_000))
        val real = dao.observeBySource("REAL", 10).first()
        assertEquals(3, real.size)
        assertEquals("real-3", real[0].dedupeKey)  // unacknowledged + newest first
        assertEquals("real-1", real[1].dedupeKey)  // unacknowledged, older
        assertEquals("real-2", real[2].dedupeKey)  // acknowledged, sorts last regardless of recency
        assertEquals(1, dao.observeBySource("SIM", 10).first().size)
    }
}
