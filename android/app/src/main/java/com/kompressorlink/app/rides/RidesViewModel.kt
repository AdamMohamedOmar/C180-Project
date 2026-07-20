package com.kompressorlink.app.rides

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.data.db.RideFileDao
import com.kompressorlink.app.data.db.RideFileEntity
import com.kompressorlink.app.data.db.RideFileStatus
import com.kompressorlink.app.sync.RideIngestor
import com.kompressorlink.app.sync.SyncClient
import com.kompressorlink.app.sync.SyncNetwork
import com.kompressorlink.app.sync.WifiSyncConnector
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Sync orchestration: BLE 0x03 -> join AP -> manifest -> per-file
// download+verify -> ingest -> statuses stream from Room. A debug-only
// server override skips BLE+AP so the whole path runs against
// firmware/tools/kl_sync_dev_server.py on a LAN (phone acceptance without
// any device hardware).
class RidesViewModel(
    private val dao: RideFileDao,
    private val requestWifiSync: suspend () -> Boolean,   // BLE 0x03 (Task 7 plumbing)
    private val connector: WifiSyncConnector,
    private val makeClient: (baseUrl: String, opener: (URL) -> HttpURLConnection) -> SyncClient,
    private val ingestor: RideIngestor,
    private val ridesDir: File,
    private val debugServerOverride: String? = null,      // e.g. "http://192.168.1.50:8765"
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    data class SyncUiState(
        val syncing: Boolean = false,
        val phase: String? = null,          // human-readable step, or null idle
        val error: String? = null,
    )

    val files: StateFlow<List<RideFileEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state

    fun sync() {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.value = SyncUiState(syncing = true, phase = "Contacting logger…")
            // Held so the finally below can always release the
            // ConnectivityManager.NetworkCallback registered by
            // connector.connect() (SyncNetwork.close()) once this attempt is
            // done. Stays null on the debug-override path (nothing was ever
            // joined) and on any early return before connect() succeeds --
            // net?.close() below is then a safe no-op.
            var net: SyncNetwork? = null
            try {
                val (baseUrl, opener) = if (debugServerOverride != null) {
                    debugServerOverride to { url: URL -> url.openConnection() as HttpURLConnection }
                } else {
                    if (!requestWifiSync()) {
                        _state.value = SyncUiState(error = "Logger not connected over BLE")
                        return@launch
                    }
                    _state.value = SyncUiState(syncing = true, phase = "Joining logger WiFi…")
                    val joined = connector.connect()
                    if (joined == null) {
                        _state.value = SyncUiState(error = "Couldn't join the logger's WiFi")
                        return@launch
                    }
                    net = joined
                    "http://192.168.4.1" to joined.opener
                }

                val client = makeClient(baseUrl, opener)
                _state.value = SyncUiState(syncing = true, phase = "Reading ride list…")
                client.reconcile(client.fetchManifest())

                for (row in dao.all()) {
                    if (row.status in listOf(RideFileStatus.PENDING.name,
                            RideFileStatus.DOWNLOADING.name)) {
                        _state.value = SyncUiState(syncing = true, phase = "Downloading ${row.name}…")
                        client.download(row.name)
                    }
                }
                for (row in dao.all()) {
                    if (row.status == RideFileStatus.VERIFIED.name) {
                        _state.value = SyncUiState(syncing = true, phase = "Processing ${row.name}…")
                        try {
                            val bytes = File(ridesDir, row.name).readBytes()
                            val updatedRow = when (val r = ingestor.ingest(bytes, nowEpochMs())) {
                                is RideIngestor.Result.Ingested ->
                                    row.copy(status = RideFileStatus.INGESTED.name, sessionId = r.sessionId)
                                is RideIngestor.Result.Duplicate ->
                                    row.copy(status = RideFileStatus.DUPLICATE.name, sessionId = r.overlappingSessionId)
                                is RideIngestor.Result.Rejected ->
                                    row.copy(status = RideFileStatus.FAILED.name)
                            }
                            dao.upsert(updatedRow.copy(updatedAtEpochMs = nowEpochMs()))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Per-file isolation, mirroring SyncClient.download()'s
                            // own per-file catch one layer down: a row that
                            // deterministically throws (missing/unreadable local
                            // file, a Room write error, ...) must not abort the
                            // rest of the batch. Without this, since dao.all()
                            // re-selects the same VERIFIED rows in the same order
                            // on every future sync() call, this row would
                            // permanently re-block every row ordered after it on
                            // EVERY subsequent attempt, while its own chip kept
                            // reading "Verified" with no signal anything was
                            // wrong. RideFileEntity has no free-text detail column
                            // to persist the exception into, so it goes to Logcat
                            // instead (CdmAssociator.kt's Log.w(TAG, ...)
                            // convention) rather than being silently lost.
                            //
                            // Both calls below are independently runCatching-wrapped
                            // -- logging is a best-effort side channel that must
                            // never itself prevent the row from being marked
                            // FAILED (confirmed the hard way: android.util.Log is
                            // an unmocked stub under this module's plain-JVM unit
                            // tests -- SyncClientTest.kt and this file both run
                            // without Robolectric, deliberately, to exercise real
                            // socket IO -- so an unguarded Log.w() here threw
                            // *inside* this catch block and skipped the upsert
                            // below it entirely), and a failure persisting the
                            // FAILED status itself must not escape and re-trigger
                            // the outer catch either.
                            runCatching { Log.w(TAG, "ingest failed for ${row.name}", e) }
                            runCatching {
                                dao.upsert(row.copy(status = RideFileStatus.FAILED.name,
                                                     updatedAtEpochMs = nowEpochMs()))
                            }
                        }
                    }
                }
                _state.value = SyncUiState()   // idle, done
            } catch (e: CancellationException) {
                // Must propagate, never be treated as a sync failure —
                // swallowing it here would stop coroutine cancellation from
                // working correctly through viewModelScope's hierarchy (e.g.
                // this ViewModel being cleared mid-sync). Same fix as
                // SyncClient.download() (sync/SyncClient.kt). The finally
                // below still runs during this rethrow — normal try/finally
                // unwind semantics are unaffected by coroutine cancellation —
                // and SyncNetwork.close() is a plain (non-suspend) call, so
                // there's no NonCancellable-during-cleanup hazard either.
                throw e
            } catch (e: Exception) {
                _state.value = SyncUiState(error = e.message ?: "Sync failed")
            } finally {
                // Releases the NetworkCallback registered by connect(),
                // whether this attempt succeeded, failed partway through, or
                // was cancelled. Without this, every real (non-debug-
                // override) sync leaks one registered callback per attempt.
                // runCatching so a failure during cleanup itself (e.g. an
                // already-unregistered callback) can't mask the real outcome
                // set above or crash the coroutine.
                runCatching { net?.close() }
            }
        }
    }

    private companion object {
        const val TAG = "RidesViewModel"
    }
}
