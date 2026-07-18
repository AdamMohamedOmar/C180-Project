package com.kompressorlink.app.data.db

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionDaoTest {
    private lateinit var db: KlDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setUp() {
        db = KlDatabase.inMemory(ApplicationProvider.getApplicationContext())
        dao = db.sessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun session(source: String, endedAt: Long, distanceKm: Float? = null) = SessionEntity(
        startedAtEpochMs = endedAt - 120_000, endedAtEpochMs = endedAt, source = source,
        snapshotCount = 240, warmIdleSeconds = 60f, distanceKm = distanceKm, hasStoredDtc = false,
    )

    private fun stat(sessionId: Long, signal: String) = SessionStatEntity(
        sessionId = sessionId, signal = signal, sampleCount = 240,
        mean = 4.2f, min = 3.8f, max = 4.6f, stdDev = 0.2f,
        secondsOutOfBand = 0f, worstLevel = "OK",
        warmIdleMean = 4.2f, warmIdleCount = 120,
        engineRunningMean = null, engineRunningCount = 0,
        engineOffMean = null, engineOffCount = 0,
    )

    @Test
    fun insertWithStats_readBack() = runTest {
        val id = dao.insertSessionWithStats(session("REAL_BLE", endedAt = 1_000_000)) {
            listOf(stat(it, "MAF_GS"), stat(it, "LTFT1"))
        }
        val sessions = dao.recentBySources(listOf("REAL_BLE"), limit = 10)
        assertEquals(1, sessions.size)
        assertEquals(id, sessions[0].id)
        assertEquals(2, dao.statsForSessions(listOf(id)).size)
    }

    @Test
    fun sourceFilter_quarantines() = runTest {
        dao.insertSessionWithStats(session("SIM_HEALTHY", endedAt = 1_000)) { emptyList() }
        dao.insertSessionWithStats(session("REAL_BLE", endedAt = 2_000)) { emptyList() }
        assertEquals(1, dao.recentBySources(listOf("REAL_BLE"), 10).size)
        assertEquals(1, dao.countBySources(listOf("SIM_HEALTHY", "SIM_FAULT", "SIM_SPARSE")))
    }

    @Test
    fun cascadeDelete_removesStats() = runTest {
        val id = dao.insertSessionWithStats(session("REAL_BLE", endedAt = 5_000)) {
            listOf(stat(it, "RPM"))
        }
        dao.deleteSession(id)
        assertEquals(0, dao.statsForSessions(listOf(id)).size)
    }

    @Test
    fun realDistanceSince_sumsOnlyRealAfterCutoff() = runTest {
        dao.insertSessionWithStats(session("REAL_BLE", endedAt = 1_000, distanceKm = 10f)) { emptyList() }
        dao.insertSessionWithStats(session("REAL_BLE", endedAt = 3_000, distanceKm = 5f)) { emptyList() }
        dao.insertSessionWithStats(session("SIM_HEALTHY", endedAt = 4_000, distanceKm = 99f)) { emptyList() }
        assertEquals(5f, dao.realDistanceSince(2_000)!!, 0.001f)
        assertNull(dao.realDistanceSince(10_000))
    }
}
