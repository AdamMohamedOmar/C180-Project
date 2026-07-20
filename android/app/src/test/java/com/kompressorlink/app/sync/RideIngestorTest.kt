package com.kompressorlink.app.sync

import com.kompressorlink.app.data.FakeSessionRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.reference.ReferenceRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideIngestorTest {

    private val refs = ReferenceRepository { name ->
        File("src/main/assets/$name").readText()
    }

    private fun rideCsv(initMode: String, minutes: Int = 3, withTimeSync: Boolean = true): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("#fw_version=0.4.0")
        sb.appendLine("#init_mode=$initMode")
        sb.appendLine("t_ms,signal,value")
        if (withTimeSync) sb.appendLine("#time_sync=0:1752700000000")
        var t = 0L
        while (t < minutes * 60_000L) {
            sb.appendLine("$t,RPM,800.000")
            sb.appendLine("${t + 100},ECT,90.000")
            sb.appendLine("${t + 200},SPEED,0.000")
            t += 500
        }
        return sb.toString().toByteArray()
    }

    @Test
    fun `logical init quarantines as SIM_RIDE`() = runTest {
        val repo = FakeSessionRepository()
        val r = RideIngestor(refs, repo).ingest(rideCsv("logical-init"), nowEpochMs = 2_000_000_000_000)
        val ingested = r as RideIngestor.Result.Ingested
        assertEquals(SessionSource.SIM_RIDE, ingested.source)
    }

    @Test
    fun `fast init ingests as REAL_RIDE with marker epoch`() = runTest {
        val repo = FakeSessionRepository()
        val r = RideIngestor(refs, repo).ingest(rideCsv("fast-init"), nowEpochMs = 2_000_000_000_000)
        assertTrue(r is RideIngestor.Result.Ingested)
        val session = repo.sessions.value.single()
        assertEquals(1752700000000L, session.startedAtEpochMs)  // anchored at the marker
        assertEquals(SessionSource.REAL_RIDE.name, session.source)
    }

    @Test
    fun `overlapping live session makes a REAL ride a duplicate`() = runTest {
        val repo = FakeSessionRepository()
        repo.sessions.value = listOf(
            SessionEntity(id = 1, startedAtEpochMs = 1752700000000L,
                endedAtEpochMs = 1752700000000L + 180_000, source = "REAL_BLE",
                snapshotCount = 100, warmIdleSeconds = 0f, distanceKm = null, hasStoredDtc = false),
        )
        val r = RideIngestor(refs, repo).ingest(rideCsv("fast-init"), nowEpochMs = 2_000_000_000_000)
        assertTrue(r is RideIngestor.Result.Duplicate)
        assertEquals(0, repo.sessions.value.count { it.source == "REAL_RIDE" })
    }

    @Test
    fun `too-short ride is rejected`() = runTest {
        val repo = FakeSessionRepository()
        // rideCsv(minutes = 0) produces ZERO data rows (the generator loop
        // never executes), so it would exercise the "no data rows" gate,
        // not the duration gate its name claims. Build a fixture with a
        // handful of real rows spanning ~10 s -- non-empty, but under the
        // 60 s HealthTuning.SESSION_MIN_DURATION_MS floor -- to hit the
        // intended branch specifically.
        val sb = StringBuilder()
        sb.appendLine("#fw_version=0.4.0")
        sb.appendLine("#init_mode=fast-init")
        sb.appendLine("t_ms,signal,value")
        var t = 0L
        while (t <= 10_000L) {
            sb.appendLine("$t,RPM,800.000")
            t += 2_000
        }
        val tiny = sb.toString().toByteArray()
        val r = RideIngestor(refs, repo).ingest(tiny, nowEpochMs = 2_000_000_000_000)
        assertTrue(r is RideIngestor.Result.Rejected)
        assertEquals("shorter than the session persist gate", (r as RideIngestor.Result.Rejected).reason)
    }

    @Test
    fun `distanceKm stays null when SPEED is never logged`() = runTest {
        val repo = FakeSessionRepository()
        // Same shape as rideCsv() but SPEED is never written -- proves the
        // documented distanceKm contract (SessionAggregator: "non-null only
        // for real sessions that ever saw SPEED") end-to-end through
        // ingestion, not just inside the aggregator.
        val sb = StringBuilder()
        sb.appendLine("#fw_version=0.4.0")
        sb.appendLine("#init_mode=fast-init")
        sb.appendLine("t_ms,signal,value")
        sb.appendLine("#time_sync=0:1752700000000")
        var t = 0L
        while (t < 3 * 60_000L) {
            sb.appendLine("$t,RPM,800.000")
            sb.appendLine("${t + 100},ECT,90.000")
            t += 500
        }
        val bytes = sb.toString().toByteArray()
        val r = RideIngestor(refs, repo).ingest(bytes, nowEpochMs = 2_000_000_000_000)
        assertTrue(r is RideIngestor.Result.Ingested)
        assertEquals(null, repo.sessions.value.single().distanceKm)
    }
}
