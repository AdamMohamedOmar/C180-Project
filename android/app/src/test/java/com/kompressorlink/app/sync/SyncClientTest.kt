package com.kompressorlink.app.sync

import com.kompressorlink.app.data.db.RideFileDao
import com.kompressorlink.app.data.db.RideFileEntity
import com.kompressorlink.app.data.db.RideFileStatus
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.zip.CRC32
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeRideFileDao : RideFileDao {
    val rows = HashMap<String, RideFileEntity>()
    override suspend fun upsert(file: RideFileEntity) { rows[file.name] = file }
    override suspend fun byName(name: String): RideFileEntity? = rows[name]
    override fun observeAll(): Flow<List<RideFileEntity>> = MutableStateFlow(rows.values.toList())
    override suspend fun all(): List<RideFileEntity> = rows.values.toList()
}

class SyncClientTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val body = ByteArray(10_000) { (it % 251).toByte() }
    private var dieAfterBytes = -1   // simulate the AP dropping mid-transfer
    private var served = 0
    // Appended verbatim inside the /rides JSON array's brackets, after the
    // default ride_00001.csv entry — lets a test add extra manifest
    // entries (e.g. ",{\"name\":\"ride_00002.csv\",...}") without needing a
    // second content fixture, since /data below serves purely by
    // offset/length and ignores which name was requested.
    private var extraManifestEntries = ""

    private fun crcHex(b: ByteArray): String {
        val c = CRC32(); c.update(b); return String.format("%08X", c.value)
    }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/rides") { x ->
            val json = """{"rides":[{"name":"ride_00001.csv","size":${body.size},"crc32":"${crcHex(body)}"}$extraManifestEntries]}"""
            x.sendResponseHeaders(200, json.length.toLong())
            x.responseBody.use { it.write(json.toByteArray()) }
        }
        server.createContext("/data") { x ->
            val q = x.requestURI.query.split('&').associate {
                val (k, v) = it.split('='); k to v
            }
            val offset = q["offset"]!!.toInt()
            val length = q["length"]!!.toInt()
            if (dieAfterBytes in 0..served) { x.close(); return@createContext }
            val n = minOf(length, body.size - offset).coerceAtLeast(0)
            x.sendResponseHeaders(200, n.toLong())
            x.responseBody.use { it.write(body, offset, n) }
            served += n
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() = server.stop(0)

    private fun client(dao: FakeRideFileDao) = SyncClient(
        baseUrl = base,
        open = { url: URL -> url.openConnection() as HttpURLConnection },
        dao = dao,
        ridesDir = tmp.newFolder("rides"),
        nowEpochMs = { 1_000L },
    )

    @Test fun `happy path downloads verifies and matches bytes`() = runTest {
        val dao = FakeRideFileDao()
        val c = client(dao)
        c.reconcile(c.fetchManifest())
        val f = c.download("ride_00001.csv")
        assertNotNull(f)
        assertArrayEquals(body, f!!.readBytes())
        assertEquals(RideFileStatus.VERIFIED.name, dao.rows["ride_00001.csv"]!!.status)
    }

    @Test fun `interrupted transfer resumes from the persisted offset`() = runTest {
        val dao = FakeRideFileDao()
        val c = client(dao)
        c.reconcile(c.fetchManifest())
        dieAfterBytes = 4096                       // connection dies after the first chunk
        assertNull(c.download("ride_00001.csv"))
        val partial = dao.rows["ride_00001.csv"]!!
        assertEquals(RideFileStatus.DOWNLOADING.name, partial.status)
        assertEquals(4096L, partial.downloadedBytes)

        dieAfterBytes = -1                         // AP back
        served = 0
        val f = c.download("ride_00001.csv")       // resumes at 4096, not 0
        assertNotNull(f)
        assertArrayEquals(body, f!!.readBytes())
        assertEquals(RideFileStatus.VERIFIED.name, dao.rows["ride_00001.csv"]!!.status)
    }

    @Test fun `crc mismatch wipes once then fails`() = runTest {
        val dao = FakeRideFileDao()
        val c = client(dao)
        c.reconcile(c.fetchManifest())
        // Poison the expected CRC so verification can never pass:
        dao.rows["ride_00001.csv"] = dao.rows["ride_00001.csv"]!!.copy(crc32 = "00000000")
        assertNull(c.download("ride_00001.csv"))
        assertEquals(RideFileStatus.PENDING.name, dao.rows["ride_00001.csv"]!!.status)  // wiped, one retry allowed
        assertNull(c.download("ride_00001.csv"))
        assertEquals(RideFileStatus.FAILED.name, dao.rows["ride_00001.csv"]!!.status)
    }

    // Not in the original spec's 3 tests — added during self-review. Pins
    // down a scenario the given suite never exercises: disk holds MORE
    // bytes than the DB's confirmed downloadedBytes AND that DB value is
    // still positive (a crash landed a second chunk on disk right after a
    // first chunk's checkpoint was already confirmed). The resume path
    // must truncate the torn tail while keeping the confirmed prefix
    // intact, then resume from the DB's offset — never from zero, and
    // never by discarding bytes disk+DB both already agree on.
    @Test fun `torn append ahead of the DB offset keeps the confirmed prefix and resumes`() = runTest {
        val dao = FakeRideFileDao()
        val ridesDir = tmp.newFolder("rides")
        val c = SyncClient(
            baseUrl = base,
            open = { url: URL -> url.openConnection() as HttpURLConnection },
            dao = dao,
            ridesDir = ridesDir,
            nowEpochMs = { 1_000L },
        )
        c.reconcile(c.fetchManifest())

        // DB's last confirmed checkpoint is 4096 bytes, but disk has 6000:
        // the next chunk's write landed on disk before the process died,
        // before its downloadedBytes update reached Room.
        dao.rows["ride_00001.csv"] = dao.rows["ride_00001.csv"]!!.copy(downloadedBytes = 4096)
        File(ridesDir, "ride_00001.csv").writeBytes(body.copyOfRange(0, 6000))

        served = 0
        val f = c.download("ride_00001.csv")
        assertNotNull(f)
        assertArrayEquals(body, f!!.readBytes())
        assertEquals(RideFileStatus.VERIFIED.name, dao.rows["ride_00001.csv"]!!.status)
    }

    // Added in response to code review. Mirrors the torn-append test above
    // but in the opposite direction: the DB's downloadedBytes is AHEAD of
    // what's actually on disk (a shorter or missing local file). Resume
    // must clamp down to the disk length and fetch from there — trusting
    // the higher DB number would skip real bytes the server still has to
    // send and produce a corrupt file.
    @Test fun `DB offset ahead of a shorter disk file resumes from the disk length`() = runTest {
        val dao = FakeRideFileDao()
        val ridesDir = tmp.newFolder("rides")
        val c = SyncClient(
            baseUrl = base,
            open = { url: URL -> url.openConnection() as HttpURLConnection },
            dao = dao,
            ridesDir = ridesDir,
            nowEpochMs = { 1_000L },
        )
        c.reconcile(c.fetchManifest())

        // DB claims 8000 confirmed bytes, but the file actually on disk
        // only has 4000 (e.g. the DB write raced ahead, or the file was
        // otherwise shortened after the DB was updated).
        dao.rows["ride_00001.csv"] = dao.rows["ride_00001.csv"]!!.copy(downloadedBytes = 8000)
        File(ridesDir, "ride_00001.csv").writeBytes(body.copyOfRange(0, 4000))

        served = 0
        val f = c.download("ride_00001.csv")
        assertNotNull(f)
        assertArrayEquals(body, f!!.readBytes())
        assertEquals(RideFileStatus.VERIFIED.name, dao.rows["ride_00001.csv"]!!.status)
        // Proves resume actually started at 4000, not 8000: the server
        // must have served all 6000 missing bytes (10000 - 4000). If the
        // higher DB value (8000) had been trusted instead, only 2000 bytes
        // would have been requested and the file would be short/wrong.
        assertEquals(6000, served)
    }

    // Added in response to code review. Every other test starts reconcile()
    // from an empty DAO, so `existing` is always null and the "skip a
    // finished file" branch is never actually exercised. This is the core
    // safety property: a later manifest re-fetch must never revert a file
    // that's already past DOWNLOADING.
    @Test fun `reconcile does not touch an already-verified file`() = runTest {
        val dao = FakeRideFileDao()
        val c = client(dao)
        val manifest = c.fetchManifest()
        c.reconcile(manifest)
        val verifiedRow = dao.rows["ride_00001.csv"]!!.copy(
            downloadedBytes = body.size.toLong(),
            status = RideFileStatus.VERIFIED.name,
            sessionId = 42L,
            updatedAtEpochMs = 999L,
        )
        dao.rows["ride_00001.csv"] = verifiedRow

        c.reconcile(manifest)   // a later sync session re-fetches the same manifest

        assertEquals(verifiedRow, dao.rows["ride_00001.csv"])
    }

    // Added in response to code review. The /rides handler always returned
    // exactly one entry, so the reconcile() loop and per-name wasRetried
    // keying were only ever exercised with a single name.
    @Test fun `manifest with multiple files reconciles and downloads each independently`() = runTest {
        val dao = FakeRideFileDao()
        val c = client(dao)
        extraManifestEntries = """,{"name":"ride_00002.csv","size":${body.size},"crc32":"${crcHex(body)}"}"""

        val manifest = c.fetchManifest()
        assertEquals(2, manifest.size)
        c.reconcile(manifest)
        assertEquals(RideFileStatus.PENDING.name, dao.rows["ride_00001.csv"]!!.status)
        assertEquals(RideFileStatus.PENDING.name, dao.rows["ride_00002.csv"]!!.status)

        val f1 = c.download("ride_00001.csv")
        val f2 = c.download("ride_00002.csv")
        assertNotNull(f1)
        assertNotNull(f2)
        assertArrayEquals(body, f1!!.readBytes())
        assertArrayEquals(body, f2!!.readBytes())
        assertEquals(RideFileStatus.VERIFIED.name, dao.rows["ride_00001.csv"]!!.status)
        assertEquals(RideFileStatus.VERIFIED.name, dao.rows["ride_00002.csv"]!!.status)
    }

    // Added in response to code review (doc-contract gap: wifi_sync_protocol.md's
    // Client resume rules say a changed size/crc32 under a known name is a
    // fresh file, not a continuation). Only reachable for a DOWNLOADING row
    // in practice, but cheap to lock down directly.
    @Test fun `reconcile resets progress when the manifest's size or crc changed for an in-progress file`() = runTest {
        val dao = FakeRideFileDao()
        val c = client(dao)
        c.reconcile(c.fetchManifest())
        dao.rows["ride_00001.csv"] = dao.rows["ride_00001.csv"]!!.copy(
            downloadedBytes = 4096, status = RideFileStatus.DOWNLOADING.name,
        )

        // The device reports a different crc32/size under the same name on
        // the next manifest fetch (e.g. the ride file was overwritten) —
        // this must NOT be treated as a continuation of the old bytes.
        val changedManifest = listOf(SyncClient.ManifestEntry("ride_00001.csv", 12_345L, "DEADBEEF"))
        c.reconcile(changedManifest)

        val row = dao.rows["ride_00001.csv"]!!
        assertEquals(0L, row.downloadedBytes)
        assertEquals(RideFileStatus.PENDING.name, row.status)
        assertEquals("DEADBEEF", row.crc32)
        assertEquals(12_345L, row.sizeBytes)
    }

    // Added in response to code review (path-traversal guard on
    // device-supplied manifest names — cheap defense-in-depth even though
    // real-world risk is low for this single-user tool).
    @Test fun `reconcile rejects manifest names that could escape ridesDir`() = runTest {
        val dao = FakeRideFileDao()
        val c = client(dao)
        c.reconcile(listOf(
            SyncClient.ManifestEntry("../evil.csv", 10L, "00000000"),
            SyncClient.ManifestEntry("a/b.csv", 10L, "00000000"),
            SyncClient.ManifestEntry("a\\b.csv", 10L, "00000000"),
            SyncClient.ManifestEntry("ride_00001.csv", body.size.toLong(), crcHex(body)),
        ))
        assertEquals(setOf("ride_00001.csv"), dao.rows.keys)
    }
}
