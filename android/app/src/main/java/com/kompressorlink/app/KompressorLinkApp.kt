package com.kompressorlink.app

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kompressorlink.app.data.RoomMaintenanceRepository
import com.kompressorlink.app.data.RoomOdometerRepository
import com.kompressorlink.app.data.RoomSessionRepository
import com.kompressorlink.app.data.RoomWarningRepository
import com.kompressorlink.app.data.db.KlDatabase
import com.kompressorlink.app.dtc.DtcRepository
import com.kompressorlink.app.health.LiveWarningMonitor
import com.kompressorlink.app.health.PostSessionEvaluator
import com.kompressorlink.app.health.SessionRecorder
import com.kompressorlink.app.maintenance.MaintenanceCheckWorker
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.sync.RealWifiSyncConnector
import com.kompressorlink.app.sync.RideIngestor
import com.kompressorlink.app.sync.SyncClient
import com.kompressorlink.app.sync.WifiSyncConnector
import com.kompressorlink.app.telemetry.FakeScenario
import com.kompressorlink.app.telemetry.FakeTelemetrySource
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.SourceChoiceStore
import com.kompressorlink.app.telemetry.SourceSwitcher
import com.kompressorlink.app.telemetry.ble.BleSession
import com.kompressorlink.app.telemetry.ble.GattClient
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.klDataStore: DataStore<Preferences> by preferencesDataStore(name = "kompressorlink")

class KompressorLinkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MaintenanceCheckWorker.ensureChannel(this)
        MaintenanceCheckWorker.schedule(this)
    }
}

class DataStoreSourceChoiceStore(
    private val dataStore: DataStore<Preferences>,
) : SourceChoiceStore {
    private val key = stringPreferencesKey("source_choice")

    override val choice: Flow<SourceChoice> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            prefs[key]?.let { stored ->
                runCatching { SourceChoice.valueOf(stored) }.getOrNull()
            } ?: SourceChoice.SIMULATED_HEALTHY
        }

    override suspend fun set(choice: SourceChoice) {
        dataStore.edit { it[key] = choice.name }
    }
}

interface AssociationStore {
    val mac: Flow<String?>
    suspend fun set(mac: String)
}

class DataStoreAssociationStore(
    private val dataStore: DataStore<Preferences>,
) : AssociationStore {
    private val key = stringPreferencesKey("associated_mac")
    override val mac: Flow<String?> = dataStore.data.map { it[key] }
    override suspend fun set(mac: String) {
        dataStore.edit { it[key] = mac }
    }
}

// Manual DI for a personal tool — no Hilt (Phase 4 spec §4; Phase 4.5 keeps
// the pattern and adds the data layer + recorder).
class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val choiceStore: SourceChoiceStore = DataStoreSourceChoiceStore(app.klDataStore)
    val associationStore: AssociationStore = DataStoreAssociationStore(app.klDataStore)

    val referenceRepository = ReferenceRepository { name ->
        app.assets.open(name).bufferedReader().readText()
    }

    val dtcRepository = DtcRepository { name ->
        app.assets.open(name).bufferedReader().readText()
    }

    val bleSession = BleSession(scope, associationStore.mac, choiceStore.choice) { mac ->
        GattClient(app, mac)
    }

    private val fakes = mapOf(
        SourceChoice.SIMULATED_HEALTHY to FakeTelemetrySource(FakeScenario.HEALTHY),
        SourceChoice.SIMULATED_FAULT to FakeTelemetrySource(FakeScenario.FAULT),
        SourceChoice.SIMULATED_SPARSE to FakeTelemetrySource(FakeScenario.SPARSE),
    )

    val telemetrySource: SourceSwitcher = SourceSwitcher(
        scope,
        choiceStore,
        // Synchronously seed SourceSwitcher with the REAL persisted choice, instead
        // of a hardcoded SIMULATED_HEALTHY default, so the app's first-rendered
        // connectionState/dtcReport reflect the user's last-selected source rather
        // than a stale/wrong one for the first frame after a cold start.
        //
        // This runs on the main thread: AppContainer is constructed inside
        // KompressorLinkApp.onCreate(), so runBlocking here blocks app startup
        // until DataStore emits. For this app's single small preferences file
        // that's typically well under a millisecond, but it is a genuine
        // trade-off, not a risk-free one — Android's own guidance calls out
        // blocking the main thread on DataStore reads as a real (if usually
        // low-probability) ANR contributor. If this ever becomes a measured
        // problem (e.g. ANRs reported), fix it by constructing AppContainer off
        // the main thread, or by accepting a brief "unknown" initial state here
        // instead of blocking for the real one.
        initialChoice = runBlocking { choiceStore.choice.first() },
    ) { choice ->
        fakes[choice] ?: bleSession
    }

    // ── Phase 4.5 data layer ────────────────────────────────────────────
    private val db = KlDatabase.build(app)
    val sessionRepository = RoomSessionRepository(db)
    val warningRepository = RoomWarningRepository(db)
    val odometerRepository = RoomOdometerRepository(db)
    val maintenanceRepository = RoomMaintenanceRepository(db)

    val postSessionEvaluator = PostSessionEvaluator(
        sessions = sessionRepository,
        warnings = warningRepository,
        refs = referenceRepository,
    )

    val sessionRecorder = SessionRecorder(
        scope = scope,
        source = telemetrySource,
        choice = choiceStore.choice,
        sessions = sessionRepository,
        refs = referenceRepository,
        onSessionClosed = postSessionEvaluator::onSessionClosed,
    )

    val liveWarningMonitor = LiveWarningMonitor(
        scope = scope,
        source = telemetrySource,
        choice = choiceStore.choice,
        refs = referenceRepository,
        warnings = warningRepository,
    )

    // ── Phase 5 WiFi ride-sync (2026-07-17 enhancement plan, Task 12) ──
    // docs/wifi_sync_protocol.md. rideFileDao/ridesDir/rideIngestor only
    // depend on fields declared above (db, referenceRepository,
    // sessionRepository) and app itself, so declaration order here is safe;
    // none of this is touched by the init{} block below.
    val rideFileDao = db.rideFileDao()
    val ridesDir = File(app.filesDir, "rides")
    val rideIngestor = RideIngestor(referenceRepository, sessionRepository)
    val wifiSyncConnector: WifiSyncConnector = RealWifiSyncConnector(app)
    val syncClientFactory: (String, (URL) -> HttpURLConnection) -> SyncClient = { baseUrl, opener ->
        SyncClient(
            baseUrl = baseUrl, open = opener, dao = rideFileDao, ridesDir = ridesDir,
            nowEpochMs = System::currentTimeMillis,
        )
    }

    init {
        scope.launch { maintenanceRepository.ensureSeeded() }
        sessionRecorder.start()
        liveWarningMonitor.start()
    }

    // Launched on this container's process-scoped `scope` rather than any
    // Activity's lifecycleScope, so a CDM pairing callback that fires after
    // the initiating Activity has been destroyed/recreated (e.g. a screen
    // rotation while the system Bluetooth chooser is showing) still gets
    // its MAC persisted instead of silently dropping it.
    fun persistAssociation(mac: String) {
        scope.launch {
            associationStore.set(mac)
            choiceStore.set(SourceChoice.REAL_BLE)
        }
    }
}
