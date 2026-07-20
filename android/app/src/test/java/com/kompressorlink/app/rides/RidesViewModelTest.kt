package com.kompressorlink.app.rides

import com.kompressorlink.app.data.FakeSessionRepository
import com.kompressorlink.app.data.db.RideFileEntity
import com.kompressorlink.app.data.db.RideFileStatus
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.sync.FakeRideFileDao
import com.kompressorlink.app.sync.RideIngestor
import com.kompressorlink.app.sync.SyncClient
import com.kompressorlink.app.sync.SyncNetwork
import com.kompressorlink.app.sync.WifiSyncConnector
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RidesViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val refs = ReferenceRepository { name -> File("src/main/assets/$name").readText() }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun rideCsv(minutes: Int = 3): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("#fw_version=0.4.0")
        sb.appendLine("#init_mode=fast-init")
        sb.appendLine("t_ms,signal,value")
        sb.appendLine("#time_sync=0:1752700000000")
        var t = 0L
        while (t < minutes * 60_000L) {
            sb.appendLine("$t,RPM,800.000")
            sb.appendLine("${t + 100},ECT,90.000")
            sb.appendLine("${t + 200},SPEED,0.000")
            t += 500
        }
        return sb.toString().toByteArray()
    }

    private fun crcHex(b: ByteArray): String {
        val c = CRC32(); c.update(b); return String.format("%08X", c.value)
    }

    @Test fun `sync surfaces BLE-not-connected as an error, not a crash`() = runTest(dispatcher) {
        val vm = RidesViewModel(
            dao = FakeRideFileDao(),
            requestWifiSync = { false },
            connector = object : WifiSyncConnector { override suspend fun connect() = null },
            makeClient = { _, _ -> error("unreachable") },
            ingestor = RideIngestor(refs, FakeSessionRepository()),
            ridesDir = tmp.newFolder(),
        )
        vm.sync()
        advanceUntilIdle()
        assertEquals("Logger not connected over BLE", vm.state.value.error)
    }

    // Distinct from the test above: BLE 0x03 succeeds (requestWifiSync
    // returns true), but joining the SoftAP itself fails or times out
    // (connector.connect() returns null per its documented contract). Before
    // this test, no test exercised this branch at all -- makeClient is wired
    // to throw if ever reached, so the test also proves sync() bails out
    // before touching a client, not just that the error text is right.
    @Test fun `sync surfaces a failed WiFi join as an error, not a crash`() = runTest(dispatcher) {
        val vm = RidesViewModel(
            dao = FakeRideFileDao(),
            requestWifiSync = { true },
            connector = object : WifiSyncConnector { override suspend fun connect() = null },
            makeClient = { _, _ -> error("unreachable") },
            ingestor = RideIngestor(refs, FakeSessionRepository()),
            ridesDir = tmp.newFolder(),
        )
        vm.sync()
        advanceUntilIdle()
        assertEquals("Couldn't join the logger's WiFi", vm.state.value.error)
    }

    // Regression test for a real leak found in spec review: sync() used to
    // extract net.opener from connector.connect()'s result and let the `net`
    // reference itself -- the only thing that can call close(), which
    // unregisters the ConnectivityManager.NetworkCallback requestNetwork()
    // registered -- fall out of scope with no finally and no
    // RidesViewModel.onCleared(). Every real (non-debug-override) sync
    // attempt registered another callback that was never released.
    //
    // Exercises the failure path specifically (makeClient throws immediately,
    // right after connector.connect() has already succeeded and sync() has
    // captured `net`) rather than a full successful real-WiFi sync: the real
    // path's base URL is hardcoded to the device's fixed AP address
    // (192.168.4.1), so a genuine end-to-end success run isn't something a
    // unit test can reach without either a listener bound to that literal
    // address or refactoring the base URL to be injectable -- out of scope
    // for this fix. Proving close() fires on the throwing path is sufficient
    // to prove the finally is wired correctly: try/finally runs identically
    // regardless of which way the try block exits, so a finally proven to
    // fire on the exceptional exit fires equally on the normal one.
    @Test fun `sync closes the joined network once done, even when it fails partway through`() = runTest(dispatcher) {
        var closed = false
        val fakeNet = object : SyncNetwork {
            override val opener: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection }
            override fun close() { closed = true }
        }
        val vm = RidesViewModel(
            dao = FakeRideFileDao(),
            requestWifiSync = { true },
            connector = object : WifiSyncConnector { override suspend fun connect(): SyncNetwork = fakeNet },
            makeClient = { _, _ -> error("simulated failure right after the WiFi join") },
            ingestor = RideIngestor(refs, FakeSessionRepository()),
            ridesDir = tmp.newFolder(),
        )
        vm.sync()
        advanceUntilIdle()
        assertEquals("simulated failure right after the WiFi join", vm.state.value.error)
        assertTrue(
            "SyncNetwork.close() must be called once a sync attempt that joined WiFi is " +
                "done, or the registered ConnectivityManager.NetworkCallback leaks",
            closed,
        )
    }

    // The plan's single most valuable integration test: BLE and WiFi are
    // faked away (both wired to throw if ever called, so the test itself
    // proves the debug override actually bypasses them), but everything
    // downstream is real -- a genuine com.sun.net.httpserver.HttpServer
    // (same fixture style as sync/SyncClientTest.kt, Task 11) standing in
    // for the logger's SoftAP HTTP server, real SyncClient socket IO, and a
    // real RideIngestor/SessionAggregator run against a valid fast-init ride
    // CSV (same shape as sync/RideIngestorTest.kt's rideCsv() helper).
    //
    // Deliberately NOT the class's StandardTestDispatcher+runTest(dispatcher)
    // pairing used above: SyncClient does its HTTP calls inside
    // withContext(Dispatchers.IO), a genuine escape from the virtual
    // scheduler onto a real background thread. advanceUntilIdle() only
    // drains work already queued on the test scheduler -- it can't wait for
    // a real IO thread to hop back with a result that hasn't arrived yet, so
    // pairing it with real network IO started via a separately-launched
    // viewModelScope coroutine is a known coroutines-test race (and, absent
    // any pump at all, a deadlock risk). UnconfinedTestDispatcher runs
    // continuations immediately on whichever thread resumes them (including
    // the real IO thread), so the sync() coroutine makes genuine progress
    // without needing to be pumped, and plain runBlocking + directly
    // awaiting vm.state.first { ... } lets the test thread wait for it for
    // real, the same way SyncClientTest's direct (un-launched) suspend calls
    // do.
    @Test fun `debug override skips BLE and reaches the client`() {
        val body = rideCsv()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/rides") { x ->
            val json = """{"rides":[{"name":"ride_00001.csv","size":${body.size},"crc32":"${crcHex(body)}"}]}"""
            x.sendResponseHeaders(200, json.length.toLong())
            x.responseBody.use { it.write(json.toByteArray()) }
        }
        server.createContext("/data") { x ->
            val q = x.requestURI.query.split('&').associate {
                val (k, v) = it.split('='); k to v
            }
            val offset = q["offset"]!!.toInt()
            val length = q["length"]!!.toInt()
            val n = minOf(length, body.size - offset).coerceAtLeast(0)
            x.sendResponseHeaders(200, n.toLong())
            x.responseBody.use { it.write(body, offset, n) }
        }
        server.start()

        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val dao = FakeRideFileDao()
            val ridesDir = tmp.newFolder()
            val vm = RidesViewModel(
                dao = dao,
                requestWifiSync = { error("debug override must bypass BLE 0x03") },
                connector = object : WifiSyncConnector {
                    override suspend fun connect(): Nothing = error("debug override must bypass the WiFi join")
                },
                makeClient = { baseUrl, opener ->
                    SyncClient(baseUrl = baseUrl, open = opener, dao = dao, ridesDir = ridesDir,
                               nowEpochMs = { 2_000_000_000_000L })
                },
                ingestor = RideIngestor(refs, FakeSessionRepository()),
                ridesDir = ridesDir,
                debugServerOverride = base,
                nowEpochMs = { 2_000_000_000_000L },
            )

            runBlocking {
                vm.sync()
                val final = vm.state.first { !it.syncing }
                assertNull(final.error)
            }

            val row = dao.rows.getValue("ride_00001.csv")
            assertEquals(RideFileStatus.INGESTED.name, row.status)
            assertNotNull(row.sessionId)
        } finally {
            server.stop(0)
        }
    }

    // Regression test for spec review Important #1: the ingest loop used to
    // have no per-row failure isolation, unlike SyncClient.download() one
    // layer down. Any exception reading/ingesting/persisting ONE row
    // propagated into sync()'s outer catch and aborted the entire remaining
    // batch -- and since dao.all() re-selects the same VERIFIED rows in the
    // same order on every future sync() call, that one bad row would
    // permanently re-block every row ordered after it on EVERY subsequent
    // attempt, with its own chip still reading "Verified" (no signal
    // anything was wrong).
    //
    // Pre-seeds the dao directly with two already-VERIFIED rows rather than
    // driving them there through a real manifest+download: the debug
    // server's /rides here returns an empty manifest, so reconcile() and the
    // download loop are both no-ops that never touch these rows, and the run
    // goes straight to the ingest loop. Only "ride_good.csv" has a backing
    // file actually written to ridesDir -- "ride_missing.csv" deliberately
    // does not, so its File(...).readBytes() throws FileNotFoundException
    // inside the ingest loop, deterministically, with no real network/timing
    // involved.
    @Test fun `one row's ingest failure does not abort the rest of the batch`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/rides") { x ->
            val json = """{"rides":[]}"""
            x.sendResponseHeaders(200, json.length.toLong())
            x.responseBody.use { it.write(json.toByteArray()) }
        }
        server.start()

        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val dao = FakeRideFileDao()
            val ridesDir = tmp.newFolder()
            val now = 2_000_000_000_000L
            val goodBytes = rideCsv()
            File(ridesDir, "ride_good.csv").writeBytes(goodBytes)
            // "ride_missing.csv" is deliberately never written to ridesDir.
            dao.rows["ride_good.csv"] = RideFileEntity(
                name = "ride_good.csv", sizeBytes = goodBytes.size.toLong(), crc32 = "00000000",
                downloadedBytes = goodBytes.size.toLong(), status = RideFileStatus.VERIFIED.name,
                sessionId = null, updatedAtEpochMs = now,
            )
            dao.rows["ride_missing.csv"] = RideFileEntity(
                name = "ride_missing.csv", sizeBytes = 1L, crc32 = "00000000",
                downloadedBytes = 1L, status = RideFileStatus.VERIFIED.name,
                sessionId = null, updatedAtEpochMs = now,
            )

            val vm = RidesViewModel(
                dao = dao,
                requestWifiSync = { error("debug override must bypass BLE 0x03") },
                connector = object : WifiSyncConnector {
                    override suspend fun connect(): Nothing = error("debug override must bypass the WiFi join")
                },
                makeClient = { baseUrl, opener ->
                    SyncClient(baseUrl = baseUrl, open = opener, dao = dao, ridesDir = ridesDir,
                               nowEpochMs = { now })
                },
                ingestor = RideIngestor(refs, FakeSessionRepository()),
                ridesDir = ridesDir,
                debugServerOverride = base,
                nowEpochMs = { now },
            )

            runBlocking {
                vm.sync()
                val final = vm.state.first { !it.syncing }
                // The batch as a whole must still complete cleanly -- one
                // bad row is not a sync failure.
                assertNull(final.error)
            }

            assertEquals(RideFileStatus.INGESTED.name, dao.rows.getValue("ride_good.csv").status)
            assertEquals(RideFileStatus.FAILED.name, dao.rows.getValue("ride_missing.csv").status)
        } finally {
            server.stop(0)
        }
    }
}
