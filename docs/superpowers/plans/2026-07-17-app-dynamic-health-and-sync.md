# App Dynamic M271 Health Features & Resumable Ride Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the Phase 4.5 health engine with dynamic M271-specific metrics (warm-up rate, LTFT load sensitivity, MAF under load, O2 activity onset) computed as streaming session statistics, and add a resumable, CRC-verified WiFi ride-sync client (chunked download → verify → ingest ride CSVs as quarantine-tagged sessions) with a new Rides tab.

**Architecture:** Everything rides on Phase 4.5's machinery: new contexts feed new nullable columns on `session_stats` (schema v2), the metric registry gains four entries that flow through the existing personal-baseline (median ± 3×MAD) and Theil–Sen drift layers unchanged, and ride ingestion replays parsed CSV rows through the same `SessionAggregator` used live — one aggregation path, no fork. The sync client follows `docs/wifi_sync_protocol.md` v1 (manifest + 4 KiB ranged chunks), persists per-file progress in Room so any disconnection resumes where it stopped, and CRC-verifies with `java.util.zip.CRC32` (bit-identical to the firmware's CRC-32/ISO-HDLC).

**Tech Stack:** Kotlin + Jetpack Compose (Material 3), Room 2.6.1 (+ `room-testing` for the migration test), `HttpURLConnection` (**no new networking dependency**), `WifiNetworkSpecifier` for the SoftAP join, JUnit/Robolectric with the JDK's `com.sun.net.httpserver` as a real local HTTP fixture.

---

## HARD PREREQUISITE

**Phase 4.5 (`docs/superpowers/plans/2026-07-14-phase45-app-intelligence.md`, 27 tasks) must be executed and merged to master before this plan starts.** This plan modifies files 4.5 creates (`HealthTuning.kt`, `SnapshotContexts.kt`, `SessionAggregator.kt`, `Metrics.kt`, `SessionEntities.kt`, `KlDatabase.kt`, `MainActivity.kt` 4-tab scaffold). Task 0 verifies this and ABORTS if it isn't true. Where this plan quotes 4.5 code, the 4.5 **plan text is normative for interfaces** — if the as-built code diverged, reconcile the touchpoints during Task 0 and adapt names, keeping this plan's intent.

The firmware counterpart (`docs/superpowers/plans/2026-07-17-firmware-nonblocking-sync.md`) defines the device side of the sync protocol; it has no ordering dependency with this plan (the dev server in Task 13 stands in for the device).

## Validation context (what was accepted/rejected from the external proposal)

This plan is the validated app-side subset of an external "Enhancement Proposal" document (2026-07-17):

- **Proposal 2 (LSTM RUL forecasting on-device): REJECTED — do not implement, do not partially implement.** It violates CLAUDE.md ground rule 2 ("never claim or build self-labeling fault learning"; supervised models train offline on PC): its training labels — "rides until a known maintenance event," treated as Remaining Useful Life — do not exist (zero recorded failure events, one car, and routine maintenance is not failure). It cannot even *run* the persistence-baseline backtest that PLAN.md §2/§7 makes a hard shipping gate, and it would ship a dead TensorFlow Lite dependency plus UI copy ("Predicted time to next service") claiming ML the data cannot support. The honest path stays: 4.5's Theil–Sen drift now; PC-side `kl trend` (Phase 6) gated on beating persistence; revisit richer models only if a real labeled dataset ever exists.
- **Proposal 4 (dynamic features): ACCEPTED 3 of 4, with corrections.** `ECT_WARMUP_RATE` (slope over minutes — 0.1 Hz ECT is ample), `LTFT_LOAD_SENSITIVITY` (models THE M271 breather-leak signature: trims high at idle, normalizing under load — PLAN.md §8), and O2 onset **renamed** from "O2_HEATER_PERFORMANCE" to `O2_ACTIVITY_ONSET` (at 0.5 Hz polling we detect the first full rich↔lean swing to ±2–4 s resolution — honest against tens-of-seconds heater degradation, but it is *onset*, not a heater measurement). **`MAF_THROTTLE_RESPONSE` REJECTED**: a dMAF/dt ÷ dTHROTTLE/dt ratio sampled at 2 Hz against ~300 ms throttle transients is noise (scope-honesty rule); substituted with steady-state `MAF_HIGH_LOAD`. The proposal's overrun/decel context is dropped (its only consumer was the rejected feature). All features are **streaming accumulators** — 4.5's "per-sample data is never stored" rule holds; and they compute from the live BLE session path AND the ride-ingestion path identically.
- **Proposal 1 (Android half — resumable sync): ACCEPTED with corrections.** 4 KiB chunks (the firmware's `kSyncMaxChunk`), name-keyed resume state in Room, CRC verify before ingest, and two corrections the proposal missed: **source quarantine** (a ride recorded against `kline_sim.py` must not feed REAL baselines — the `#init_mode=` header decides) and **live/synced dedup** (the same physical drive can be recorded twice: live via BLE and on the device's storage — overlapping REAL rides are marked DUPLICATE, not double-ingested).

## Execution notes (read first)

- Execute in the **MAIN tree on master**.
- **Never run `git commit`** — the user commits via the VS Code GUI. End each task with a clean, reviewable tree.
- Test command (from `android/`):
  `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest`
  First Robolectric run downloads ~100 MB of android-all jars — slow once, then cached. Scope a single class with `--tests "com.kompressorlink.app.health.DynamicFeaturesTest"` etc.
- 4.5 conventions carried forward: pure-Kotlin health logic (no `android.*` imports in `health/` logic files), every tunable lives in `HealthTuning` with a `[Best estimate]` comment, Untested contract (null = unavailable, never guessed), quarantine rule (REAL-only baselines/drift), full-replacement files, confidence tags.
- The BLE stream repeats slower-tier values at the 2 Hz notify cadence (latest-value semantics). Contextual counts therefore count *snapshots*, not fresh ECU samples — 4.5 already accepts this; the new metrics keep the same convention, and ride ingestion replays at the same 500 ms cadence so counts stay comparable (Task 10).

## File map

```
android/app/src/main/java/com/kompressorlink/app/
  health/HealthTuning.kt          MOD  new tunables block
  health/SnapshotContexts.kt      MOD  highLoad() predicate
  health/DynamicFeatures.kt       NEW  StreamingSlope, DwellGate, O2ActivityOnsetDetector
  health/SessionAggregator.kt     MOD  high-load stats, warm-up slope, O2 onset
  health/Metrics.kt               MOD  4 new MetricIds + per-metric sample floor
  data/SessionSource.kt           MOD  REAL_RIDE, SIM_RIDE
  data/db/SessionEntities.kt      MOD  4 new nullable session_stats columns
  data/db/RideFiles.kt            NEW  RideFileEntity + RideFileStatus + RideFileDao
  data/db/KlDatabase.kt           MOD  version 2 + MIGRATION_1_2 + ride_files
  telemetry/ble/FrameCodec.kt     MOD  OP_START_WIFI_SYNC + builder
  sync/RideCsv.kt                 NEW  parser (headers, rows, torn tail)
  sync/RideReplayer.kt            NEW  rows -> 500 ms carry-forward snapshots -> SessionAggregator
  sync/RideIngestor.kt            NEW  source tagging, epoch mapping, dedup, persistence
  sync/SyncClient.kt              NEW  manifest + resumable chunk download + CRC verify
  sync/WifiSyncConnector.kt       NEW  WifiNetworkSpecifier join -> Network
  rides/RidesViewModel.kt         NEW
  rides/RidesScreen.kt            NEW
  MainActivity.kt                 MOD  5th tab "Rides"
android/app/src/main/assets/w203_bands.json  MOD  LTFT1 "load_delta" band
android/app/src/main/AndroidManifest.xml     MOD  network + nearby-wifi permissions
android/app/build.gradle.kts                 MOD  room-testing (if absent)
android/app/src/test/java/com/kompressorlink/app/
  health/DynamicFeaturesTest.kt   NEW
  health/SessionAggregatorDynamicTest.kt NEW
  health/MetricsDynamicTest.kt    NEW
  data/db/MigrationTest.kt        NEW
  sync/RideCsvTest.kt             NEW
  sync/RideIngestorTest.kt        NEW
  sync/SyncClientTest.kt          NEW
  rides/RidesViewModelTest.kt     NEW
firmware/tools/kl_sync_dev_server.py         NEW  PC stand-in for the device (Task 13)
```

---

### Task 0: Preflight gate — verify Phase 4.5 as-built

**Files:** none (verification only)

- [ ] **Step 1: Verify 4.5 landed.** All of these must exist on master:

```powershell
Test-Path android/app/src/main/java/com/kompressorlink/app/health/HealthTuning.kt
Test-Path android/app/src/main/java/com/kompressorlink/app/health/SessionAggregator.kt
Test-Path android/app/src/main/java/com/kompressorlink/app/health/Metrics.kt
Test-Path android/app/src/main/java/com/kompressorlink/app/data/db/KlDatabase.kt
Test-Path android/app/schemas
```
Expected: all `True`. **If ANY is `False`, ABORT this plan and report: Phase 4.5 has not been executed — it must run first.**

- [ ] **Step 2: Reconcile interfaces.** Read the as-built `Metrics.kt`, `SessionAggregator.kt`, `SessionEntities.kt`, `KlDatabase.kt`, `MainActivity.kt`. Confirm they match the shapes this plan quotes (MetricId enum with `contextValue(stat)`; `SessionStatEntity` with `warmIdleMean/warmIdleCount/engineRunningMean/...`; `KlDatabase` `version = 1` with `exportSchema` writing `android/app/schemas/`; a `NavigationBar` with 4 items). Note any naming drift and adapt the later tasks' code accordingly — intent over verbatim.

- [ ] **Step 3: Green baseline.**

```powershell
cd android
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, all tests pass. Record the test count — the final task reports the delta. Do not build on a red baseline.

- [ ] **Step 4: Confirm the v1 Room schema JSON exists** (`android/app/schemas/com.kompressorlink.app.data.db.KlDatabase/1.json`). If missing, the 4.5 build hasn't generated it — run `.\gradlew.bat kspDebugKotlin` and check again; if still missing, verify `room.schemaLocation` is configured in `app/build.gradle.kts` (4.5 Task 0) and fix per that plan before proceeding. The migration test (Task 3) depends on it.

---

### Task 1: New tunables + the high-load context predicate

**Files:**
- Modify: `android/app/src/main/java/com/kompressorlink/app/health/HealthTuning.kt`
- Modify: `android/app/src/main/java/com/kompressorlink/app/health/SnapshotContexts.kt`
- Test: extend `android/app/src/test/java/com/kompressorlink/app/health/SnapshotContextsTest.kt` (create if 4.5 didn't)

- [ ] **Step 1: Append to the `HealthTuning` object** (inside the existing `object HealthTuning { ... }`, after the Maintenance block):

```kotlin
    // Dynamic M271 contexts/features (2026-07-17 enhancement plan).
    // All [Best estimate] unless noted — tune here only.
    const val HIGH_LOAD_THROTTLE_MIN = 70f     // %
    const val HIGH_LOAD_RPM_MIN = 3000f        // rpm
    const val HIGH_LOAD_DWELL_SNAPSHOTS = 4    // ~2 s at the 2 Hz snapshot cadence
    const val HIGH_LOAD_MIN_SAMPLES = 15       // ~7.5 s cumulative high-load dwell per
                                               // session — far below the generic 30
                                               // floor because sustained WOT is rare
    const val WARMUP_START_MAX_ECT = 40f       // °C — first ECT below this = cold start
    const val WARMUP_COMPLETE_ECT = 80f        // °C — approach to the 87 °C stat [Confirmed
                                               // thermostat spec; 80 keeps partial-opening
                                               // noise out of the slope]
    const val WARMUP_MIN_SPAN_MS = 120_000L    // slope over < 2 min is idle-vs-drive noise
    const val O2_SWING_LOW_V = 0.3f            // narrowband rich/lean rails [Confirmed
    const val O2_SWING_HIGH_V = 0.6f           //  generic narrowband behavior; SIM4LKE
                                               //  support itself is [Likely] — the
                                               //  availability mask absorbs absence]
    const val O2_ONSET_MAX_S = 300f            // past 5 min = no meaningful onset
```

- [ ] **Step 2: Add to `SnapshotContexts`** (below `engineRunning`):

```kotlin
    /**
     * true = sustained-power context candidate (THROTTLE >= 70 % AND
     * RPM >= 3000), false = not, null = either gating signal unavailable —
     * never guess a context from missing data (Untested contract). The
     * >= HIGH_LOAD_DWELL_SNAPSHOTS debounce lives in DwellGate, not here.
     */
    fun highLoad(s: TelemetrySnapshot): Boolean? {
        val throttle = s.value(Signal.THROTTLE) ?: return null
        val rpm = s.value(Signal.RPM) ?: return null
        return throttle >= HealthTuning.HIGH_LOAD_THROTTLE_MIN &&
            rpm >= HealthTuning.HIGH_LOAD_RPM_MIN
    }
```

- [ ] **Step 3: Tests** (append to `SnapshotContextsTest.kt`, or create the class with 4.5's snapshot-builder helper — the `snap(vararg pairs)` pattern from `SessionAggregatorTest`):

```kotlin
    @Test fun `highLoad true only when both gates met`() {
        assertEquals(true, SnapshotContexts.highLoad(snap(Signal.THROTTLE to 80f, Signal.RPM to 3500f)))
        assertEquals(false, SnapshotContexts.highLoad(snap(Signal.THROTTLE to 80f, Signal.RPM to 2000f)))
        assertEquals(false, SnapshotContexts.highLoad(snap(Signal.THROTTLE to 30f, Signal.RPM to 3500f)))
    }

    @Test fun `highLoad null when a gating signal is unavailable`() {
        assertNull(SnapshotContexts.highLoad(snap(Signal.THROTTLE to 80f)))   // RPM missing
        assertNull(SnapshotContexts.highLoad(snap(Signal.RPM to 3500f)))      // THROTTLE missing
    }
```

- [ ] **Step 4: Run** `.\gradlew.bat testDebugUnitTest --tests "*SnapshotContexts*"` — expect the new tests to fail before Step 2's code lands and pass after. (If the test class is new, run once before implementing to see the compile failure, then implement, then green.)

---

### Task 2: Streaming accumulators — `DynamicFeatures.kt`

Pure Kotlin, no `android.*` imports. These three tiny classes are the entire math of proposal 4 — everything else is plumbing.

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/DynamicFeatures.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/DynamicFeaturesTest.kt`

- [ ] **Step 1: Write the failing tests:**

```kotlin
package com.kompressorlink.app.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicFeaturesTest {

    @Test
    fun `slope recovers a clean linear ramp`() {
        val s = StreamingSlope()
        // ECT 20 -> 80 °C over 10 min = 6 °C/min
        for (i in 0..600) s.add(xMinutes = i / 60f, y = 20f + i * 0.1f)
        assertEquals(6f, s.slopePerX!!, 0.01f)
    }

    @Test
    fun `slope survives step-wise repeated values`() {
        // The BLE stream repeats a 0.1 Hz-polled ECT at 2 Hz: 20 identical
        // snapshots per fresh sample. Least squares over the steps must
        // still recover the underlying warm-up rate.
        val s = StreamingSlope()
        var t = 0f
        for (step in 0 until 30) {
            val ect = 20f + step * 2f       // +2 °C per 10 s = 12 °C/min
            repeat(20) { s.add(t / 60f, ect); t += 0.5f }
        }
        assertEquals(12f, s.slopePerX!!, 0.5f)
    }

    @Test
    fun `slope null below two points or zero x variance`() {
        val s = StreamingSlope()
        assertNull(s.slopePerX)
        s.add(1f, 10f)
        assertNull(s.slopePerX)
        s.add(1f, 12f)  // same x
        assertNull(s.slopePerX)
    }

    @Test
    fun `dwell gate opens after N consecutive and resets on false or null`() {
        val g = DwellGate(3)
        assertFalse(g.update(true)); assertFalse(g.update(true))
        assertTrue(g.update(true))
        assertTrue(g.update(true))          // stays open while held
        assertFalse(g.update(false))        // reset
        assertFalse(g.update(true)); assertFalse(g.update(true))
        assertFalse(g.update(null))         // unknown also resets (Untested contract)
        assertFalse(g.update(true))
    }

    @Test
    fun `o2 onset fires at first completed rich-lean pair`() {
        val d = O2ActivityOnsetDetector()
        d.add(2f, 0.45f)    // mid-band: nothing
        d.add(4f, 0.75f)    // rich rail seen
        assertNull(d.onsetS)
        d.add(6f, 0.15f)    // lean rail seen -> full swing complete
        assertEquals(6f, d.onsetS!!, 1e-3f)
        d.add(8f, 0.8f)     // latched — later swings don't move it
        assertEquals(6f, d.onsetS!!, 1e-3f)
    }

    @Test
    fun `o2 onset order-independent (lean first also counts)`() {
        val d = O2ActivityOnsetDetector()
        d.add(3f, 0.1f)
        d.add(9f, 0.7f)
        assertEquals(9f, d.onsetS!!, 1e-3f)
    }
}
```

- [ ] **Step 2: Run to verify failure** (`--tests "*DynamicFeatures*"`): compile error, classes missing.

- [ ] **Step 3: Implement `health/DynamicFeatures.kt`:**

```kotlin
package com.kompressorlink.app.health

// Streaming building blocks for the dynamic M271 features (2026-07-17
// enhancement plan). All O(1) memory — session raw samples are never stored
// (4.5 design rule).

/** Ordinary least-squares slope, accumulated one (x, y) at a time. */
class StreamingSlope {
    var count = 0
        private set
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumXX = 0.0
    private var sumXY = 0.0

    fun add(xMinutes: Float, y: Float) {
        val x = xMinutes.toDouble()
        count++
        sumX += x
        sumY += y
        sumXX += x * x
        sumXY += x * y.toDouble()
    }

    /** Slope in y-units per x-unit; null under 2 points or degenerate x. */
    val slopePerX: Float?
        get() {
            if (count < 2) return null
            val n = count.toDouble()
            val denom = n * sumXX - sumX * sumX
            if (denom <= 1e-9) return null
            return ((n * sumXY - sumX * sumY) / denom).toFloat()
        }
}

/**
 * Debounce for snapshot contexts: active only after [minConsecutive]
 * consecutive `true` updates. `false` AND `null` (gating signal
 * unavailable) both reset — a context is never inferred through missing
 * data.
 */
class DwellGate(private val minConsecutive: Int) {
    private var run = 0

    fun update(active: Boolean?): Boolean {
        run = if (active == true) run + 1 else 0
        return run >= minConsecutive
    }
}

/**
 * Time from session start until the pre-cat narrowband O2 has visited BOTH
 * rails (>= O2_SWING_HIGH_V and <= O2_SWING_LOW_V, either order) — the
 * first completed rich/lean swing, i.e. the sensor is demonstrably active.
 * Honest resolution at the 0.5 Hz poll / 2 Hz repeat cadence is ±2–4 s,
 * which is fine against the tens-of-seconds drift of a degrading heater
 * circuit. This is ONSET, not a heater measurement — copy must never claim
 * a heater diagnosis.
 */
class O2ActivityOnsetDetector {
    private var seenLow = false
    private var seenHigh = false

    var onsetS: Float? = null
        private set

    fun add(tSinceStartS: Float, o2Volts: Float) {
        if (onsetS != null) return
        if (o2Volts <= HealthTuning.O2_SWING_LOW_V) seenLow = true
        if (o2Volts >= HealthTuning.O2_SWING_HIGH_V) seenHigh = true
        if (seenLow && seenHigh) onsetS = tSinceStartS
    }
}
```

- [ ] **Step 4: Run to verify pass** (`--tests "*DynamicFeatures*"`): 6 tests PASS.

---

### Task 3: Schema v2 — new stat columns + `ride_files` table + migration

One migration covers both features and sync state (single release unit). 4.5 shipped `version = 1` with `exportSchema = true`, so this is the project's first real migration — with a real migration test.

**Files:**
- Modify: `android/app/src/main/java/com/kompressorlink/app/data/db/SessionEntities.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/data/db/RideFiles.kt`
- Modify: `android/app/src/main/java/com/kompressorlink/app/data/db/KlDatabase.kt`
- Modify: `android/app/build.gradle.kts` (add `testImplementation(libs.androidx.room.testing)` if not already present — the version catalog already has the entry)
- Test: `android/app/src/test/java/com/kompressorlink/app/data/db/MigrationTest.kt`

- [ ] **Step 1: Extend `SessionStatEntity`** — append after `engineOffCount`:

```kotlin
    // Dynamic contexts (schema v2, 2026-07-17 enhancement plan):
    val highLoadMean: Float? = null,    // LTFT1, MAF_GS only
    val highLoadCount: Int = 0,
    val warmupRatePerMin: Float? = null, // ECT only — °C/min over a completed cold-start warm-up
    val o2OnsetS: Float? = null,         // O2_B1S1_V only — s to first full swing, cold starts only
```

(Defaults keep every existing constructor call compiling — 4.5 test fixtures construct these entities positionally in places; if any call site breaks, add the four arguments explicitly there.)

- [ ] **Step 2: Create `data/db/RideFiles.kt`:**

```kotlin
package com.kompressorlink.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Sync-state row per device ride file. `name` is a stable identity: the
// firmware allocates ride numbers monotonically and NEVER reuses them
// (docs/wifi_sync_protocol.md).
@Entity(tableName = "ride_files")
data class RideFileEntity(
    @PrimaryKey val name: String,
    val sizeBytes: Long,
    val crc32: String,          // 8 uppercase hex chars from the manifest
    val downloadedBytes: Long,
    val status: String,         // RideFileStatus enum name
    val sessionId: Long?,       // set once ingested
    val updatedAtEpochMs: Long,
)

enum class RideFileStatus {
    PENDING,      // known from a manifest, no bytes yet
    DOWNLOADING,  // partial bytes on disk — downloadedBytes is the resume point
    DOWNLOADED,   // all bytes present, CRC not yet checked
    VERIFIED,     // CRC matched, not yet ingested
    INGESTED,     // session(s) created
    DUPLICATE,    // overlaps an existing REAL_BLE session — kept, not ingested
    FAILED,       // CRC mismatched twice, or size overrun — needs attention
}

@Dao
interface RideFileDao {
    @Upsert
    suspend fun upsert(file: RideFileEntity)

    @Query("SELECT * FROM ride_files WHERE name = :name")
    suspend fun byName(name: String): RideFileEntity?

    @Query("SELECT * FROM ride_files ORDER BY name DESC")
    fun observeAll(): Flow<List<RideFileEntity>>

    @Query("SELECT * FROM ride_files ORDER BY name DESC")
    suspend fun all(): List<RideFileEntity>
}
```

- [ ] **Step 3: Bump `KlDatabase`.** In `KlDatabase.kt`: add `RideFileEntity::class` to the `entities` array, change `version = 1` to `version = 2`, expose `abstract fun rideFileDao(): RideFileDao`, and add the migration object + wire it into the builder (`.addMigrations(MIGRATION_1_2)`):

```kotlin
// v1 -> v2 (2026-07-17): dynamic-context stat columns + ride-file sync state.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE session_stats ADD COLUMN highLoadMean REAL")
        db.execSQL("ALTER TABLE session_stats ADD COLUMN highLoadCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE session_stats ADD COLUMN warmupRatePerMin REAL")
        db.execSQL("ALTER TABLE session_stats ADD COLUMN o2OnsetS REAL")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ride_files` (" +
                "`name` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, " +
                "`crc32` TEXT NOT NULL, " +
                "`downloadedBytes` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`sessionId` INTEGER, " +
                "`updatedAtEpochMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`name`))"
        )
    }
}
```

(Imports: `androidx.room.migration.Migration`, `androidx.sqlite.db.SupportSQLiteDatabase`.)

- [ ] **Step 4: Migration test** — `data/db/MigrationTest.kt`:

```kotlin
package com.kompressorlink.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KlDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 1 to 2 preserves sessions and adds new shapes`() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL(
                "INSERT INTO sessions (startedAtEpochMs, endedAtEpochMs, source, " +
                    "snapshotCount, warmIdleSeconds, distanceKm, hasStoredDtc) " +
                    "VALUES (1000, 61000, 'REAL_BLE', 240, 60.0, 4.2, 0)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test", 2, true, MIGRATION_1_2)
        db.query("SELECT COUNT(*) FROM sessions").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        // New columns exist and default sanely on old rows:
        db.query("SELECT highLoadCount FROM session_stats LIMIT 1").close()
        db.query("SELECT COUNT(*) FROM ride_files").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
    }
}
```

`runMigrationsAndValidate(..., validateDroppedTables=true, ...)` diff-checks the migrated schema against the KSP-exported v2 schema JSON — the real point of the test. (If the 4.5 as-built `sessions` column set differs from the INSERT above, adapt the column list from `schemas/. ../1.json`.)

- [ ] **Step 5: Run** `.\gradlew.bat testDebugUnitTest --tests "*MigrationTest*"` — expect FAIL before Steps 1–3 (schema mismatch/missing classes), PASS after. Then run the FULL unit suite — entity-constructor call sites in 4.5 tests must all still compile (fix per Step 1's note if not).

---

### Task 4: `SessionAggregator` — accumulate the dynamic features

**Files:**
- Modify: `android/app/src/main/java/com/kompressorlink/app/health/SessionAggregator.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/SessionAggregatorDynamicTest.kt`

- [ ] **Step 1: Write the failing tests** (uses 4.5's `SessionAggregatorTest` helpers — same `refs` asset trick, same `snap(...)` builder; copy those two helpers into this class):

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAggregatorDynamicTest {

    private val refs = ReferenceRepository { name ->
        File("src/main/assets/$name").readText()
    }

    private fun snap(vararg pairs: Pair<Signal, Float>): TelemetrySnapshot {
        val v = FloatArray(SIGNAL_COUNT)
        var mask = 0
        pairs.forEach { (s, value) ->
            v[s.ordinal] = value
            mask = mask or (1 shl s.ordinal)
        }
        return TelemetrySnapshot(v, mask, flags = 0, seq = 0, uptimeMs = 0)
    }

    private fun agg() = SessionAggregator(refs, isReal = true)

    @Test
    fun `high load stats accumulate only after dwell gate opens`() {
        val a = agg()
        var t = 0L
        // 3 qualifying snapshots: gate (4) never opens -> no high-load stats.
        repeat(3) {
            a.add(snap(Signal.THROTTLE to 85f, Signal.RPM to 3500f, Signal.LTFT1 to 2f), t); t += 500
        }
        a.add(snap(Signal.THROTTLE to 10f, Signal.RPM to 900f, Signal.LTFT1 to 9f), t); t += 500
        var stats = a.buildStats(sessionId = 1)
        assertNull(stats.first { it.signal == "LTFT1" }.highLoadMean)

        // 6 consecutive: samples 4..6 accumulate (gate opens on the 4th).
        val b = agg()
        t = 0
        repeat(6) {
            b.add(snap(Signal.THROTTLE to 85f, Signal.RPM to 3500f, Signal.LTFT1 to 2f), t); t += 500
        }
        stats = b.buildStats(sessionId = 1)
        val ltft = stats.first { it.signal == "LTFT1" }
        assertEquals(2f, ltft.highLoadMean!!, 1e-4f)
        assertEquals(3, ltft.highLoadCount)
    }

    @Test
    fun `warmup rate emitted only for completed cold start with enough span`() {
        val a = agg()
        // Cold start at 20 °C, +0.1 °C per 500 ms snapshot = 12 °C/min;
        // reaches 80 °C after 600 snapshots = 300 s (> 2 min span).
        var t = 0L
        var ect = 20f
        while (ect < 81f) {
            a.add(snap(Signal.ECT to ect, Signal.RPM to 800f), t)
            t += 500; ect += 0.1f
        }
        val rate = a.buildStats(1).first { it.signal == "ECT" }.warmupRatePerMin
        assertEquals(12f, rate!!, 0.5f)
    }

    @Test
    fun `no warmup rate on a warm restart or an incomplete warmup`() {
        val warm = agg()
        warm.add(snap(Signal.ECT to 85f, Signal.RPM to 800f), 0)   // first ECT >= 40
        warm.add(snap(Signal.ECT to 86f, Signal.RPM to 800f), 500)
        assertNull(warm.buildStats(1).firstOrNull { it.signal == "ECT" }?.warmupRatePerMin)

        val incomplete = agg()
        incomplete.add(snap(Signal.ECT to 20f, Signal.RPM to 800f), 0)
        incomplete.add(snap(Signal.ECT to 30f, Signal.RPM to 800f), 60_000)  // never reaches 80
        assertNull(incomplete.buildStats(1).firstOrNull { it.signal == "ECT" }?.warmupRatePerMin)
    }

    @Test
    fun `o2 onset recorded on cold start only`() {
        val cold = agg()
        cold.add(snap(Signal.ECT to 20f, Signal.O2_B1S1_V to 0.45f, Signal.RPM to 900f), 0)
        cold.add(snap(Signal.ECT to 21f, Signal.O2_B1S1_V to 0.75f, Signal.RPM to 900f), 10_000)
        cold.add(snap(Signal.ECT to 22f, Signal.O2_B1S1_V to 0.12f, Signal.RPM to 900f), 24_000)
        val onset = cold.buildStats(1).first { it.signal == "O2_B1S1_V" }.o2OnsetS
        assertEquals(24f, onset!!, 1e-3f)

        val warm = agg()
        warm.add(snap(Signal.ECT to 85f, Signal.O2_B1S1_V to 0.75f, Signal.RPM to 900f), 0)
        warm.add(snap(Signal.ECT to 85f, Signal.O2_B1S1_V to 0.12f, Signal.RPM to 900f), 4_000)
        assertNull(warm.buildStats(1).firstOrNull { it.signal == "O2_B1S1_V" }?.o2OnsetS)
    }
}
```

- [ ] **Step 2: Run to verify failure** (`--tests "*SessionAggregatorDynamic*"`): compile errors (new entity fields exist since Task 3, but the aggregator never sets them / helper fields missing).

- [ ] **Step 3: Modify `SessionAggregator`.** Additions (weave into the as-built file; reference implementation below assumes the 4.5-plan text):

New fields next to the existing contextual maps:

```kotlin
    private val highLoad = HashMap<Signal, StreamingStats>()
    private val highLoadGate = DwellGate(HealthTuning.HIGH_LOAD_DWELL_SNAPSHOTS)

    private enum class WarmupState { UNDECIDED, ACTIVE, DONE, NOT_COLD }
    private var warmupState = WarmupState.UNDECIDED
    private val warmupSlope = StreamingSlope()
    private var warmupStartMs: Long? = null
    private var warmupEndMs: Long? = null
    private var coldStart = false
    private var sessionStartMs: Long? = null
    private val o2Onset = O2ActivityOnsetDetector()
```

Inside `add(snapshot, atMs)` — after the existing warm-idle/engine-context blocks, before the distance block:

```kotlin
        if (sessionStartMs == null) sessionStartMs = atMs
        val startMs = sessionStartMs!!

        // Cold-start warm-up slope (ECT vs minutes). Decided once, on the
        // FIRST ECT sample; slope only from a completed 40->80 °C climb.
        snapshot.value(Signal.ECT)?.let { ect ->
            if (warmupState == WarmupState.UNDECIDED) {
                coldStart = ect < HealthTuning.WARMUP_START_MAX_ECT
                warmupState = if (coldStart) WarmupState.ACTIVE else WarmupState.NOT_COLD
                if (coldStart) warmupStartMs = atMs
            }
            if (warmupState == WarmupState.ACTIVE) {
                warmupSlope.add((atMs - startMs) / 60_000f, ect)
                if (ect >= HealthTuning.WARMUP_COMPLETE_ECT) {
                    warmupState = WarmupState.DONE
                    warmupEndMs = atMs
                }
            }
        }

        // Sustained high load: debounced so a 0.5 s throttle blip never
        // counts as "load". Gate resets on false AND on unavailable.
        if (highLoadGate.update(SnapshotContexts.highLoad(snapshot))) {
            for (signal in HIGH_LOAD_SIGNALS) {
                snapshot.value(signal)?.let {
                    highLoad.getOrPut(signal) { StreamingStats() }.add(it)
                }
            }
        }

        // O2 activity onset — meaningful on cold starts only (a warm engine
        // is already in closed loop within seconds).
        if (coldStart) {
            snapshot.value(Signal.O2_B1S1_V)?.let {
                o2Onset.add((atMs - startMs) / 1000f, it)
            }
        }
```

A private helper + companion addition:

```kotlin
    // °C/min, only for a COMPLETED cold-start warm-up spanning >= 2 min —
    // anything less is idle-vs-driving noise, not thermostat signal.
    private val warmupRatePerMin: Float?
        get() {
            if (warmupState != WarmupState.DONE) return null
            val start = warmupStartMs ?: return null
            val end = warmupEndMs ?: return null
            if (end - start < HealthTuning.WARMUP_MIN_SPAN_MS) return null
            return warmupSlope.slopePerX
        }

    private val o2OnsetSOrNull: Float?
        get() = o2Onset.onsetS?.takeIf { coldStart && it <= HealthTuning.O2_ONSET_MAX_S }
```

```kotlin
        // In companion object, next to WARM_IDLE_SIGNALS:
        val HIGH_LOAD_SIGNALS = setOf(Signal.LTFT1, Signal.MAF_GS)
```

And in `buildStats`, extend the `SessionStatEntity(...)` construction:

```kotlin
            val hl = highLoad[signal]
            SessionStatEntity(
                /* ...existing arguments unchanged... */
                highLoadMean = hl?.takeIf { it.count > 0 }?.meanValue,
                highLoadCount = hl?.count ?: 0,
                warmupRatePerMin = if (signal == Signal.ECT) warmupRatePerMin else null,
                o2OnsetS = if (signal == Signal.O2_B1S1_V) o2OnsetSOrNull else null,
            )
```

- [ ] **Step 4: Run to verify pass** (`--tests "*SessionAggregator*"`) — the new suite AND 4.5's existing aggregator tests all green (existing behavior untouched).

---

### Task 5: Metric registry — four new metrics through baseline + drift

**Files:**
- Modify: `android/app/src/main/java/com/kompressorlink/app/health/Metrics.kt`
- Modify: `android/app/src/main/assets/w203_bands.json`
- Modify: the personal-baseline file from 4.5 Task 11 (null-band fallback — see Step 4)
- Test: `android/app/src/test/java/com/kompressorlink/app/health/MetricsDynamicTest.kt`

- [ ] **Step 1: Extend `MetricId`.** Add a `minSamples` property with the old global as default, and the four entries; extend `contextValue` (Kotlin's exhaustive `when` will then FORCE every other `when(metric)` in the codebase to be updated — that compiler pass is Step 5):

```kotlin
enum class MetricId(
    val displayName: String,
    val unit: String,
    val signal: Signal,
    val subsystem: Subsystem,
    val bandContext: String,
    val minSamples: Int = HealthTuning.CONTEXT_MIN_SAMPLES,
) {
    LTFT_WARM_IDLE("LTFT at warm idle", "%", Signal.LTFT1, Subsystem.FUELING, "always"),
    STFT_WARM_IDLE("STFT at warm idle", "%", Signal.STFT1, Subsystem.FUELING, "always"),
    MAF_WARM_IDLE("MAF at warm idle", "g/s", Signal.MAF_GS, Subsystem.AIR_INTAKE, "warm_idle"),
    ECT_WARM_IDLE("Coolant at warm idle", "°C", Signal.ECT, Subsystem.COOLING, "warm_idle"),
    BATT_CHARGING("Charging voltage", "V", Signal.BATT_V_ADC, Subsystem.BATTERY, "engine_running"),
    BATT_REST("Resting voltage", "V", Signal.BATT_V_ADC, Subsystem.BATTERY, "engine_off"),

    // Dynamic metrics (2026-07-17 enhancement plan). LTFT_LOAD_SENSITIVITY
    // models THE M271 signature fault: a breather/vacuum leak inflates trims
    // at idle and fades under load, so (load mean − idle mean) goes strongly
    // negative (PLAN.md §8, P0170/P0171 tree).
    LTFT_LOAD_SENSITIVITY("LTFT load response", "%", Signal.LTFT1, Subsystem.FUELING,
        "load_delta", HealthTuning.HIGH_LOAD_MIN_SAMPLES),
    MAF_HIGH_LOAD("MAF under load", "g/s", Signal.MAF_GS, Subsystem.AIR_INTAKE,
        "high_load", HealthTuning.HIGH_LOAD_MIN_SAMPLES),
    ECT_WARMUP_RATE("Warm-up rate", "°C/min", Signal.ECT, Subsystem.COOLING,
        "warmup", 1),
    O2_ACTIVITY_ONSET("O2 activity onset", "s", Signal.O2_B1S1_V, Subsystem.FUELING,
        "cold_start", 1);

    fun contextValue(stat: SessionStatEntity): Pair<Float, Int>? = when (this) {
        LTFT_WARM_IDLE, STFT_WARM_IDLE, MAF_WARM_IDLE, ECT_WARM_IDLE ->
            stat.warmIdleMean?.let { it to stat.warmIdleCount }
        BATT_CHARGING -> stat.engineRunningMean?.let { it to stat.engineRunningCount }
        BATT_REST -> stat.engineOffMean?.let { it to stat.engineOffCount }
        LTFT_LOAD_SENSITIVITY -> {
            val hi = stat.highLoadMean
            val wi = stat.warmIdleMean
            // Needs BOTH contexts healthy in the same session; the idle side
            // keeps the generic 30-sample floor, the load side uses this
            // metric's own minSamples via MetricSeries.
            if (hi != null && wi != null &&
                stat.warmIdleCount >= HealthTuning.CONTEXT_MIN_SAMPLES
            ) (hi - wi) to stat.highLoadCount else null
        }
        MAF_HIGH_LOAD -> stat.highLoadMean?.let { it to stat.highLoadCount }
        // For the two event-shaped metrics the aggregator already applied
        // every validity gate — presence IS eligibility (count 1 vs floor 1).
        ECT_WARMUP_RATE -> stat.warmupRatePerMin?.let { it to 1 }
        O2_ACTIVITY_ONSET -> stat.o2OnsetS?.let { it to 1 }
    }
}
```

In `MetricSeries.build`, change the floor check from the global constant to the metric's own:

```kotlin
            if (count < metric.minSamples) return@mapNotNull null
```

- [ ] **Step 2: Absolute bands.** Only `LTFT_LOAD_SENSITIVITY` gets one. In `w203_bands.json`, find the `LTFT1` signal's band array and add (mirroring the existing entries' exact field names — read the file first):

```json
{ "context": "load_delta", "min": -8.0, "max": 8.0 }
```

`[Best estimate]` — a healthy M271 holds load and idle trims within a few percent of each other; a breather leak drives the delta strongly negative. The other three new metrics ship **baseline-only** (no JSON entry → `MetricSeries.bandFor` returns null → no absolute verdict, personal baseline + drift only). That's deliberate scope honesty: absolute thresholds for warm-up rate / load MAF / O2 onset would be guesses; the personal envelope learns them per car. Verify the dashboard's live band selection ignores unknown contexts (it matches contexts against live predicates — `load_delta` is never true live; confirm no crash by running the dashboard tests).

- [ ] **Step 3: Write the failing tests** — `health/MetricsDynamicTest.kt`:

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.data.db.SessionStatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricsDynamicTest {

    private fun stat(
        signal: String,
        warmIdleMean: Float? = null, warmIdleCount: Int = 0,
        highLoadMean: Float? = null, highLoadCount: Int = 0,
        warmupRatePerMin: Float? = null, o2OnsetS: Float? = null,
    ) = SessionStatEntity(
        sessionId = 1, signal = signal, sampleCount = 100, mean = 0f, min = 0f,
        max = 0f, stdDev = 0f, secondsOutOfBand = 0f, worstLevel = "OK",
        warmIdleMean = warmIdleMean, warmIdleCount = warmIdleCount,
        engineRunningMean = null, engineRunningCount = 0,
        engineOffMean = null, engineOffCount = 0,
        highLoadMean = highLoadMean, highLoadCount = highLoadCount,
        warmupRatePerMin = warmupRatePerMin, o2OnsetS = o2OnsetS,
    )

    @Test
    fun `load sensitivity is load minus idle and needs both contexts`() {
        val ok = stat("LTFT1", warmIdleMean = 12f, warmIdleCount = 60,
            highLoadMean = 2f, highLoadCount = 20)
        assertEquals(-10f to 20, MetricId.LTFT_LOAD_SENSITIVITY.contextValue(ok))

        val idleTooThin = stat("LTFT1", warmIdleMean = 12f, warmIdleCount = 10,
            highLoadMean = 2f, highLoadCount = 20)
        assertNull(MetricId.LTFT_LOAD_SENSITIVITY.contextValue(idleTooThin))

        val noLoad = stat("LTFT1", warmIdleMean = 12f, warmIdleCount = 60)
        assertNull(MetricId.LTFT_LOAD_SENSITIVITY.contextValue(noLoad))
    }

    @Test
    fun `event metrics use presence as validity`() {
        assertEquals(9.5f to 1,
            MetricId.ECT_WARMUP_RATE.contextValue(stat("ECT", warmupRatePerMin = 9.5f)))
        assertNull(MetricId.ECT_WARMUP_RATE.contextValue(stat("ECT")))
        assertEquals(38f to 1,
            MetricId.O2_ACTIVITY_ONSET.contextValue(stat("O2_B1S1_V", o2OnsetS = 38f)))
    }

    @Test
    fun `per-metric floors gate the series`() {
        // MAF_HIGH_LOAD floor is HIGH_LOAD_MIN_SAMPLES (15), not 30.
        assertEquals(HealthTuning.HIGH_LOAD_MIN_SAMPLES, MetricId.MAF_HIGH_LOAD.minSamples)
        assertEquals(HealthTuning.CONTEXT_MIN_SAMPLES, MetricId.LTFT_WARM_IDLE.minSamples)
        assertEquals(1, MetricId.ECT_WARMUP_RATE.minSamples)
    }
}
```

(If 4.5's `MetricSeries.build` tests construct stats positionally, the four new defaulted fields keep them compiling.)

- [ ] **Step 4: Null-band baseline fallback.** 4.5's personal baseline (Task 11 file, e.g. `health/PersonalBaseline.kt` — locate by `BASELINE_DEGENERATE_MAD_FRACTION` usage) widens a degenerate MAD as a fraction of the **absolute band width**. Baseline-only metrics have no band. Find that degenerate-MAD branch and give it a fallback denominator, preserving existing behavior when a band exists:

```kotlin
        // Band-less (baseline-only) metrics fall back to the median's own
        // scale: |median|, floored at 1 unit so a ~0-centered metric (like
        // LTFT_LOAD_SENSITIVITY) still gets a usable envelope. [Best estimate]
        val widthRef = band?.let { it.max - it.min }
            ?: maxOf(kotlin.math.abs(median), 1f)
```

…and use `widthRef` where the band width was used in both the degenerate test and the widen amount. Add one unit test in the baseline's existing test file: a constant-valued series with `band = null` yields a non-degenerate envelope centered on the median (adapt to the as-built function signature).

- [ ] **Step 5: Compile-driven sweep.** Build the unit tests. Every exhaustive `when (metric)` in HealthViewModel/PostSessionEvaluator/etc. now fails to compile — that's the registry's safety net working. For each site add the four branches with honest copy (do NOT weaken to an `else`):
  - `LTFT_LOAD_SENSITIVITY` — caption: "Trim shift from idle to load. A strongly negative shift is the classic M271 breather-leak signature."
  - `MAF_HIGH_LOAD` — caption: "Airflow under sustained load vs your car's usual — a slow slide can mean a dirtying MAF."
  - `ECT_WARMUP_RATE` — caption: "How fast the engine warms on cold starts. A slowing rate suggests a thermostat failing open."
  - `O2_ACTIVITY_ONSET` — caption: "Seconds until the front O2 sensor becomes active after a cold start. Creeping up over months can mean a tiring sensor." (Never claim "heater diagnosis.")

- [ ] **Step 6: Run** `--tests "*Metrics*"` then the FULL unit suite. Everything green.

---

### Task 6: Health screen surfacing check

The Health screen builds rows by iterating `MetricId.entries` per subsystem (4.5 Tasks 19/23), so the four metrics appear without new UI code — this task only *proves* it and pins wording.

**Files:**
- Modify (only if Step 1 finds gaps): `health/…ViewModel` per-metric copy tables
- Test: extend the existing `HealthViewModelTest` (4.5 Task 19's file)

- [ ] **Step 1:** Read the as-built HealthViewModel/Health screen row construction. Confirm each `MetricId` renders: display name, unit, value formatting, baseline/drift verdict chip. Confirm drift wording template ("… is {drifting}; at the current rate it reaches … in ~N weeks") composes with the new units (°C/min, s).

- [ ] **Step 2:** Add one ViewModel test: given a repo faked with 10 REAL sessions whose LTFT1 stats carry `warmIdleMean=12f, warmIdleCount=60, highLoadMean=2f, highLoadCount=20`, the FUELING subsystem card contains a `LTFT_LOAD_SENSITIVITY` row with value −10.0 and the ATTENTION-capable absolute band verdict (−10 is outside the ±8 `load_delta` band). Follow the exact fixture pattern 4.5's HealthViewModel tests use (fake repositories + `runTest`).

- [ ] **Step 3:** Run `--tests "*HealthViewModel*"` — green.

---

### Task 7: `FrameCodec` — START_WIFI_SYNC control frame

Mirror of firmware Task 7 (`kControlOpStartWifiSync = 0x03`, 9-byte frame, payload zeros). Firmware plan and this plan may land in either order — the byte contract is `docs/ble_protocol.md`.

**Files:**
- Modify: `android/app/src/main/java/com/kompressorlink/app/telemetry/ble/FrameCodec.kt`
- Test: extend the existing FrameCodec test class

- [ ] **Step 1: Failing test** (append to the FrameCodec test file):

```kotlin
    @Test
    fun `start wifi sync frame is opcode 3 with zero payload`() {
        val frame = FrameCodec.buildStartWifiSync()
        assertEquals(9, frame.size)
        assertEquals(0x03.toByte(), frame[0])
        assertTrue(frame.drop(1).all { it == 0.toByte() })
    }
```

- [ ] **Step 2: Implement** — next to `buildTimeSync` / `OP_TIME_SYNC` add:

```kotlin
    const val OP_START_WIFI_SYNC: Byte = 0x03

    /** docs/ble_protocol.md: opcode 0x03, epoch_ms payload ignored (zeros). */
    fun buildStartWifiSync(): ByteArray =
        ByteArray(9).also { it[0] = OP_START_WIFI_SYNC }
```

- [ ] **Step 3:** Expose a send path: find where the app writes the time-sync frame to the control characteristic (`BleSession`/`GattClient` — 4/4.5 as-built) and add an analogous `suspend fun requestWifiSync(): Boolean` that writes `FrameCodec.buildStartWifiSync()` to the same characteristic, surfacing it through the same layer the time-sync write uses (SourceSwitcher/container — adapt to as-built). No-op/false when the REAL BLE source isn't connected.

- [ ] **Step 4:** Run `--tests "*FrameCodec*"` — green.

---

### Task 8: `SessionSource` — the ride-file quarantine tags

**Files:**
- Modify: `android/app/src/main/java/com/kompressorlink/app/data/SessionSource.kt`
- Test: extend its existing test (or add `SessionSourceTest.kt`)

- [ ] **Step 1: Failing tests:**

```kotlin
    @Test
    fun `ride sources quarantine correctly`() {
        assertTrue(SessionSource.REAL_RIDE.isReal)
        assertFalse(SessionSource.SIM_RIDE.isReal)
        assertTrue(SessionSource.REAL.contains(SessionSource.REAL_RIDE))
        assertTrue(SessionSource.SIM.contains(SessionSource.SIM_RIDE))
    }
```

- [ ] **Step 2: Implement** — extend the enum:

```kotlin
enum class SessionSource {
    SIM_HEALTHY, SIM_FAULT, SIM_SPARSE, REAL_BLE, REAL_RIDE, SIM_RIDE;

    val isReal: Boolean get() = this == REAL_BLE || this == REAL_RIDE

    companion object {
        fun from(choice: SourceChoice): SessionSource = when (choice) {
            SourceChoice.SIMULATED_HEALTHY -> SIM_HEALTHY
            SourceChoice.SIMULATED_FAULT -> SIM_FAULT
            SourceChoice.SIMULATED_SPARSE -> SIM_SPARSE
            SourceChoice.REAL_BLE -> REAL_BLE
        }

        // REAL_RIDE: a device-logged drive of the actual car (physical
        // K-line init). SIM_RIDE: a device ride recorded against
        // kline_sim.py (init_mode=logical-init) — quarantined exactly like
        // app-side sim sources; bench data must never teach the baseline.
        val REAL: Set<SessionSource> = setOf(REAL_BLE, REAL_RIDE)
        val SIM: Set<SessionSource> = setOf(SIM_HEALTHY, SIM_FAULT, SIM_SPARSE, SIM_RIDE)
    }
}
```

- [ ] **Step 3: Audit the hardcodes.** `grep -rn "REAL_BLE" android/app/src/main` — for each hit decide with this rule and leave a one-line comment where behavior is deliberately narrow:
  - Baseline/drift/warning session queries that use `SessionSource.REAL` sets: now include REAL_RIDE automatically — correct, no change.
  - `SessionDao.realDistanceSince` (hardcoded `'REAL_BLE'`) and any odometer-estimation query: **keep REAL_BLE-only.** Ride ingestion (Task 10) computes `distanceKm` for information, but the odometer estimator must not sum it — a drive recorded live AND synced from the device would double-count kilometres. Add the comment: `// REAL_BLE only: synced rides may duplicate live sessions (see RideIngestor dedup)`.
  - `SessionRecorder`'s own source tagging: untouched (live recording stays REAL_BLE).

- [ ] **Step 4:** Run the FULL unit suite — `WarningSource.from`/`isReal` call sites all keep working (they derive from `isReal`, no exhaustive `when` over `SessionSource` should exist — if one does, extend it per the quarantine comments above).

---

### Task 9: Ride CSV parsing — `RideCsv.kt`

Format ground truth (from `firmware/src/ride_logger.cpp`, [Confirmed]):
- Header lines start `#`: `#fw_version=…`, `#init_mode=…`, then anywhere mid-file `#dtc_stored=P0170,P0300`, `#dtc_pending=…`, `#time_sync=<t_ms>:<epoch_ms>`, `#dropped=N`.
- One column-header line `t_ms,signal,value`.
- Data rows `t_ms,SIGNAL_NAME,%.3f`. **Unavailable readings are never written** (the firmware skips them), so every row is a real value.
- A power cut can truncate the final line mid-write — the parser must drop an unparseable tail row, never fail the file (PLAN.md §2 CSV-integrity lesson).

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/sync/RideCsv.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/sync/RideCsvTest.kt`

- [ ] **Step 1: Failing tests:**

```kotlin
package com.kompressorlink.app.sync

import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideCsvTest {

    private val ride = """
        #fw_version=0.4.0
        #init_mode=logical-init
        t_ms,signal,value
        1000,RPM,812.000
        1100,ECT,21.000
        #dtc_stored=P0170,P0300
        #dtc_pending=
        #time_sync=2000:1752700000000
        2000,RPM,815.500
    """.trimIndent()

    @Test
    fun `parses headers rows dtcs and time sync`() {
        val r = RideCsv.parse(ride.toByteArray())
        assertEquals("logical-init", r.initMode)
        assertEquals("0.4.0", r.fwVersion)
        assertEquals(3, r.rows.size)
        assertEquals(RideCsv.Row(1000, Signal.RPM, 812.000f), r.rows[0])
        assertEquals(listOf("P0170", "P0300"), r.storedDtcs)
        assertTrue(r.pendingDtcs.isEmpty())
        assertEquals(2000L to 1752700000000L, r.timeSync)
    }

    @Test
    fun `torn tail row is dropped not fatal`() {
        val torn = "$ride\n2100,MAF_G"           // power cut mid-line
        val r = RideCsv.parse(torn.toByteArray())
        assertEquals(3, r.rows.size)             // the torn row vanished
    }

    @Test
    fun `unknown signal names and junk rows are skipped`() {
        val weird = "$ride\n2200,NOT_A_SIGNAL,1.0\ngarbage line\n2300,RPM,820.000"
        val r = RideCsv.parse(weird.toByteArray())
        assertEquals(4, r.rows.size)
        assertEquals(Signal.RPM, r.rows.last().signal)
    }

    @Test
    fun `no time sync marker yields null`() {
        val bare = "#fw_version=x\n#init_mode=logical-init\nt_ms,signal,value\n10,RPM,800.000"
        assertNull(RideCsv.parse(bare.toByteArray()).timeSync)
    }
}
```

- [ ] **Step 2: Run to verify failure**, then **implement `sync/RideCsv.kt`:**

```kotlin
package com.kompressorlink.app.sync

import com.kompressorlink.app.telemetry.Signal

// Parser for the firmware's long-format ride CSV (ride_logger.cpp is the
// writer; this stays tolerant where that is strict). Torn tails and junk
// rows are DROPPED, never fatal — a power cut can end a file mid-line and
// the grad project's malformed-CSV incident is why tolerance is a rule
// (PLAN.md §2).
object RideCsv {

    data class Row(val tMs: Long, val signal: Signal, val value: Float)

    data class Parsed(
        val fwVersion: String?,
        val initMode: String?,
        val storedDtcs: List<String>,
        val pendingDtcs: List<String>,
        /** t_ms-at-marker to epoch_ms-at-marker, if the phone ever synced time. */
        val timeSync: Pair<Long, Long>?,
        val rows: List<Row>,
    )

    fun parse(bytes: ByteArray): Parsed {
        var fw: String? = null
        var init: String? = null
        var stored = emptyList<String>()
        var pending = emptyList<String>()
        var timeSync: Pair<Long, Long>? = null
        val rows = ArrayList<Row>()
        val names = Signal.entries.associateBy { it.name }

        for (line in String(bytes, Charsets.UTF_8).lineSequence()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#")) {
                val body = l.substring(1)
                val eq = body.indexOf('=')
                if (eq <= 0) continue
                val key = body.substring(0, eq)
                val value = body.substring(eq + 1)
                when (key) {
                    "fw_version" -> fw = value
                    "init_mode" -> init = value
                    "dtc_stored" -> stored = splitCodes(value)
                    "dtc_pending" -> pending = splitCodes(value)
                    "time_sync" -> {
                        val parts = value.split(':')
                        if (parts.size == 2) {
                            val t = parts[0].toLongOrNull()
                            val epoch = parts[1].toLongOrNull()
                            if (t != null && epoch != null) timeSync = t to epoch
                        }
                    }
                    // "dropped" and future markers: ignored by design
                }
                continue
            }
            val parts = l.split(',')
            if (parts.size != 3) continue          // column header, junk, torn tail
            val t = parts[0].toLongOrNull() ?: continue
            val signal = names[parts[1]] ?: continue
            val v = parts[2].toFloatOrNull() ?: continue
            rows.add(Row(t, signal, v))
        }
        return Parsed(fw, init, stored, pending, timeSync, rows)
    }

    private fun splitCodes(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
```

- [ ] **Step 3: Run to verify pass** (`--tests "*RideCsv*"`): 4 tests PASS.

---

### Task 10: Replay + ingestion — `RideReplayer.kt`, `RideIngestor.kt`

Rows replay through the SAME `SessionAggregator` the live path uses, at the SAME 500 ms cadence (bucketed carry-forward). This matters: `DwellGate` counts snapshots and contextual counts gate metrics — replaying at raw row rate (~10 Hz) would silently change every context's meaning. Source rule: `#init_mode=logical-init` (today's bench/sim init) ⇒ `SIM_RIDE` (quarantined); any physical init mode (fast-init/5-baud, Phase 2+) ⇒ `REAL_RIDE`. Epoch mapping from the `#time_sync` marker; REAL rides overlapping an existing REAL_BLE session by >50 % are `DUPLICATE` (same physical drive, recorded twice).

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/sync/RideReplayer.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/sync/RideIngestor.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/sync/RideIngestorTest.kt`

- [ ] **Step 1: Write `sync/RideReplayer.kt`:**

```kotlin
package com.kompressorlink.app.sync

import com.kompressorlink.app.health.SessionAggregator
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.TelemetrySnapshot

// Replays parsed ride rows into a SessionAggregator as carry-forward
// snapshots at the live BLE cadence (500 ms buckets). One aggregation
// path for live and synced data — contexts, dwell gates and count floors
// keep identical semantics in both.
object RideReplayer {

    const val BUCKET_MS = 500L

    /** Returns the number of snapshots fed. */
    fun replay(rows: List<RideCsv.Row>, into: SessionAggregator): Int {
        if (rows.isEmpty()) return 0
        val values = FloatArray(SIGNAL_COUNT)
        var mask = 0
        var seq = 0
        var i = 0
        var bucketEnd = rows.first().tMs + BUCKET_MS
        while (i < rows.size) {
            while (i < rows.size && rows[i].tMs < bucketEnd) {
                val row = rows[i]
                values[row.signal.ordinal] = row.value
                mask = mask or (1 shl row.signal.ordinal)
                i++
            }
            if (mask != 0) {
                into.add(
                    TelemetrySnapshot(values.copyOf(), mask, flags = 0,
                        seq = seq++, uptimeMs = bucketEnd),
                    atMs = bucketEnd,
                )
            }
            bucketEnd += BUCKET_MS
        }
        return seq
    }
}
```

(If the as-built `TelemetrySnapshot` constructor differs — e.g. `seq`/`uptimeMs` types — mirror how `SessionAggregatorTest`'s `snap()` builds one.)

- [ ] **Step 2: Write `sync/RideIngestor.kt`:**

```kotlin
package com.kompressorlink.app.sync

import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.db.SessionDao
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.health.HealthTuning
import com.kompressorlink.app.health.SessionAggregator
import com.kompressorlink.app.reference.ReferenceRepository

// Turns a verified ride file into a quarantine-tagged session, or refuses
// with an honest reason. NEVER throws on malformed content — a bad file is
// a status, not a crash.
class RideIngestor(
    private val refs: ReferenceRepository,
    private val dao: SessionDao,
) {

    sealed class Result {
        data class Ingested(val sessionId: Long, val source: SessionSource) : Result()
        data class Duplicate(val overlappingSessionId: Long) : Result()
        data class Rejected(val reason: String) : Result()
    }

    suspend fun ingest(bytes: ByteArray, nowEpochMs: Long): Result {
        val parsed = RideCsv.parse(bytes)
        if (parsed.rows.isEmpty()) return Result.Rejected("no data rows")

        // Quarantine rule: logical-init = bench run against kline_sim.py,
        // never the car. Physical init modes (fast-init/5-baud, Phase 2+)
        // are the real car. Unknown init modes quarantine as SIM — the
        // safe default is to NOT teach the baseline.
        val source = when (parsed.initMode) {
            "fast-init", "5-baud" -> SessionSource.REAL_RIDE
            else -> SessionSource.SIM_RIDE
        }

        val firstT = parsed.rows.first().tMs
        val lastT = parsed.rows.last().tMs
        val durationMs = lastT - firstT

        // Epoch mapping: exact when the phone ever synced the device clock
        // during this ride; otherwise anchor the ride's END at sync time
        // (approximate — baselines only need day-scale ordering).
        val (startedAt, endedAt) = parsed.timeSync?.let { (tAtMarker, epochAtMarker) ->
            val epochAt = { t: Long -> epochAtMarker + (t - tAtMarker) }
            epochAt(firstT) to epochAt(lastT)
        } ?: (nowEpochMs - durationMs) to nowEpochMs

        if (durationMs < HealthTuning.SESSION_MIN_DURATION_MS) {
            return Result.Rejected("shorter than the session persist gate")
        }

        // Same-drive dedup: a REAL ride overlapping an existing REAL_BLE
        // session by > 50 % of its span is the SAME drive recorded twice
        // (phone live + device storage). Keep the live session, refuse the
        // ride — double-ingesting would double-teach the baseline.
        if (source == SessionSource.REAL_RIDE) {
            val recent = dao.recentBySources(listOf(SessionSource.REAL_BLE.name), 50)
            val overlap = recent.firstOrNull { s ->
                val start = maxOf(s.startedAtEpochMs, startedAt)
                val end = minOf(s.endedAtEpochMs, endedAt)
                (end - start) > durationMs / 2
            }
            if (overlap != null) return Result.Duplicate(overlap.id)
        }

        val agg = SessionAggregator(refs, isReal = source.isReal)
        val snapshots = RideReplayer.replay(parsed.rows, agg)
        if (snapshots < HealthTuning.SESSION_MIN_SNAPSHOTS) {
            return Result.Rejected("fewer snapshots than the persist gate")
        }
        if (parsed.storedDtcs.isNotEmpty()) agg.markStoredDtc()

        val id = dao.insertSessionWithStats(
            SessionEntity(
                startedAtEpochMs = startedAt,
                endedAtEpochMs = endedAt,
                source = source.name,
                snapshotCount = snapshots,
                warmIdleSeconds = agg.warmIdleSeconds,
                distanceKm = agg.distanceKm,
                hasStoredDtc = parsed.storedDtcs.isNotEmpty(),
            ),
            stats = { sessionId -> agg.buildStats(sessionId) },
        )
        return Result.Ingested(id, source)
    }
}
```

Supporting change: 4.5's aggregator learns of DTCs via `onDtcReport(report: DtcReport?)`. Rather than construct a `DtcReport` from strings here, add a one-liner to `SessionAggregator`:

```kotlin
    /** Ride ingestion path: the CSV header already knows (see RideIngestor). */
    fun markStoredDtc() { hasStoredDtc = true }
```

- [ ] **Step 3: Failing tests** — `sync/RideIngestorTest.kt` (uses 4.5's fake `SessionDao`/repository test doubles — Task 4 of the 4.5 plan created shared fakes; reuse them. If the shared fake is repository-level rather than DAO-level, adapt `RideIngestor` to take that repository interface instead — same intent):

```kotlin
package com.kompressorlink.app.sync

import com.kompressorlink.app.data.SessionSource
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
        val dao = FakeSessionDao()   // 4.5's shared fake
        val r = RideIngestor(refs, dao).ingest(rideCsv("logical-init"), nowEpochMs = 2_000_000_000_000)
        val ingested = r as RideIngestor.Result.Ingested
        assertEquals(SessionSource.SIM_RIDE, ingested.source)
    }

    @Test
    fun `fast init ingests as REAL_RIDE with marker epoch`() = runTest {
        val dao = FakeSessionDao()
        val r = RideIngestor(refs, dao).ingest(rideCsv("fast-init"), nowEpochMs = 2_000_000_000_000)
        assertTrue(r is RideIngestor.Result.Ingested)
        val session = dao.sessions.single()
        assertEquals(1752700000000L, session.startedAtEpochMs)  // anchored at the marker
        assertEquals(SessionSource.REAL_RIDE.name, session.source)
    }

    @Test
    fun `overlapping live session makes a REAL ride a duplicate`() = runTest {
        val dao = FakeSessionDao()
        dao.seedSession(source = "REAL_BLE", startedAt = 1752700000000L, endedAt = 1752700000000L + 180_000)
        val r = RideIngestor(refs, dao).ingest(rideCsv("fast-init"), nowEpochMs = 2_000_000_000_000)
        assertTrue(r is RideIngestor.Result.Duplicate)
        assertEquals(0, dao.sessions.count { it.source == "REAL_RIDE" })
    }

    @Test
    fun `too-short ride is rejected`() = runTest {
        val dao = FakeSessionDao()
        val tiny = rideCsv("fast-init", minutes = 0)  // < SESSION_MIN_DURATION_MS
        val r = RideIngestor(refs, dao).ingest(tiny, nowEpochMs = 2_000_000_000_000)
        assertTrue(r is RideIngestor.Result.Rejected)
    }
}
```

(`FakeSessionDao`: if 4.5's shared fakes don't already include one with `sessions` list + `seedSession`, write it in this test file implementing just the `SessionDao` members `RideIngestor` touches.)

- [ ] **Step 4: Run** `--tests "*RideIngestor*"` — 4 tests PASS (plus DynamicFeatures/Aggregator suites still green: `markStoredDtc` addition is additive).

---

### Task 11: `SyncClient` — resumable, CRC-verified chunk downloads

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/sync/SyncClient.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/sync/SyncClientTest.kt`

- [ ] **Step 1: Implement `sync/SyncClient.kt`** (written first here because the test exercises it against a REAL local HTTP server — the test is the behavior spec):

```kotlin
package com.kompressorlink.app.sync

import com.kompressorlink.app.data.db.RideFileDao
import com.kompressorlink.app.data.db.RideFileEntity
import com.kompressorlink.app.data.db.RideFileStatus
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// docs/wifi_sync_protocol.md v1 client. Resume-safe by construction: the
// Room row's downloadedBytes advances only after bytes hit disk, so any
// interruption (WiFi drop, app death) resumes at the exact byte. CRC-32
// verification uses java.util.zip.CRC32 — bit-identical to the firmware's
// CRC-32/ISO-HDLC.
class SyncClient(
    private val baseUrl: String,                        // e.g. "http://192.168.4.1"
    private val open: (URL) -> HttpURLConnection,       // prod: network.openConnection
    private val dao: RideFileDao,
    private val ridesDir: File,
    private val nowEpochMs: () -> Long,
) {
    companion object {
        const val CHUNK_BYTES = 4096      // the firmware's kSyncMaxChunk
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 10000
    }

    data class ManifestEntry(val name: String, val size: Long, val crc32: String)

    suspend fun fetchManifest(): List<ManifestEntry> = withContext(Dispatchers.IO) {
        val json = JSONObject(String(get("$baseUrl/rides"), Charsets.UTF_8))
        val rides = json.getJSONArray("rides")
        (0 until rides.length()).map { i ->
            val o = rides.getJSONObject(i)
            ManifestEntry(o.getString("name"), o.getLong("size"), o.getString("crc32"))
        }
    }

    /** Registers manifest entries, skipping names already VERIFIED+. */
    suspend fun reconcile(manifest: List<ManifestEntry>) {
        for (m in manifest) {
            val existing = dao.byName(m.name)
            if (existing != null && existing.status !in listOf(
                    RideFileStatus.PENDING.name, RideFileStatus.DOWNLOADING.name,
                    RideFileStatus.FAILED.name)
            ) continue  // done or in a terminal reviewed state
            dao.upsert(
                RideFileEntity(
                    name = m.name, sizeBytes = m.size, crc32 = m.crc32,
                    downloadedBytes = existing?.downloadedBytes ?: 0,
                    status = if ((existing?.downloadedBytes ?: 0) > 0)
                        RideFileStatus.DOWNLOADING.name else RideFileStatus.PENDING.name,
                    sessionId = null, updatedAtEpochMs = nowEpochMs(),
                )
            )
        }
    }

    /**
     * Downloads one file to completion (resuming) and CRC-verifies it.
     * Returns the local file on success (status VERIFIED), null on failure
     * (status DOWNLOADING for retryable network errors, FAILED for a
     * second CRC mismatch). onProgress receives downloadedBytes.
     */
    suspend fun download(
        name: String,
        onProgress: (Long) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        val row = dao.byName(name) ?: return@withContext null
        ridesDir.mkdirs()
        val local = File(ridesDir, name)
        var downloaded = row.downloadedBytes
        if (local.length() != downloaded) {
            // Disk truth wins over DB truth (an append can land without its
            // DB update on process death) — but only downward:
            downloaded = minOf(local.length(), downloaded)
            if (local.length() > downloaded) {
                // torn append: truncate to the last DB-confirmed byte
                local.outputStream().channel.use { it.truncate(downloaded) }
            }
        }
        try {
            while (downloaded < row.sizeBytes) {
                val want = minOf(CHUNK_BYTES.toLong(), row.sizeBytes - downloaded).toInt()
                val encoded = URLEncoder.encode(name, "UTF-8")
                val chunk = get("$baseUrl/data?name=$encoded&offset=$downloaded&length=$want")
                if (chunk.isEmpty()) {
                    // server says EOF before the manifest size — device-side
                    // anomaly; surface as retryable, next manifest decides
                    dao.upsert(row.copy(downloadedBytes = downloaded,
                        status = RideFileStatus.DOWNLOADING.name,
                        updatedAtEpochMs = nowEpochMs()))
                    return@withContext null
                }
                local.appendBytes(chunk)
                downloaded += chunk.size
                dao.upsert(row.copy(downloadedBytes = downloaded,
                    status = RideFileStatus.DOWNLOADING.name,
                    updatedAtEpochMs = nowEpochMs()))
                onProgress(downloaded)
            }
        } catch (e: Exception) {
            dao.upsert(row.copy(downloadedBytes = downloaded,
                status = RideFileStatus.DOWNLOADING.name,
                updatedAtEpochMs = nowEpochMs()))
            return@withContext null   // resume point persisted; caller retries
        }

        // Complete — verify. One mismatch = wipe and mark PENDING (single
        // automatic retry via the next download call); a mismatch on a
        // file that was already re-downloaded = FAILED.
        val crc = CRC32()
        local.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        val hex = String.format("%08X", crc.value)
        return@withContext if (hex.equals(row.crc32, ignoreCase = true)) {
            dao.upsert(row.copy(downloadedBytes = downloaded,
                status = RideFileStatus.VERIFIED.name, updatedAtEpochMs = nowEpochMs()))
            local
        } else {
            local.delete()
            val second = row.status == RideFileStatus.PENDING.name && row.downloadedBytes == 0L &&
                dao.byName(name)?.updatedAtEpochMs != null && wasRetried.contains(name)
            if (wasRetried.add(name) && !second) {
                dao.upsert(row.copy(downloadedBytes = 0,
                    status = RideFileStatus.PENDING.name, updatedAtEpochMs = nowEpochMs()))
            } else {
                dao.upsert(row.copy(downloadedBytes = 0,
                    status = RideFileStatus.FAILED.name, updatedAtEpochMs = nowEpochMs()))
            }
            null
        }
    }

    private val wasRetried = HashSet<String>()

    private fun get(url: String): ByteArray {
        val conn = open(URL(url))
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
```

Simplify the retry bookkeeping while implementing if a cleaner shape emerges — the CONTRACT the tests pin is: first CRC mismatch → wiped + PENDING; a mismatch on the retry → FAILED.

- [ ] **Step 2: Tests against a real local HTTP server** — `sync/SyncClientTest.kt` (plain JVM test; `com.sun.net.httpserver.HttpServer` ships in the JDK Gradle runs tests on):

```kotlin
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

    private fun crcHex(b: ByteArray): String {
        val c = CRC32(); c.update(b); return String.format("%08X", c.value)
    }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
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
}
```

- [ ] **Step 3: Run** `--tests "*SyncClient*"` — 3 tests PASS. Iterate on the retry bookkeeping until the third test's contract holds exactly.

---

### Task 12: SoftAP join + Rides tab (ViewModel, screen, 5th tab, permissions)

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/sync/WifiSyncConnector.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/rides/RidesViewModel.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/rides/RidesScreen.kt`
- Modify: `android/app/src/main/java/com/kompressorlink/app/MainActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `android/app/src/test/java/com/kompressorlink/app/rides/RidesViewModelTest.kt`

- [ ] **Step 1: Manifest permissions** — add alongside the existing BLE ones:

```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
        android:usesPermissionFlags="neverForLocation" />
```

(`NEARBY_WIFI_DEVICES` is the API-33+ runtime permission `WifiNetworkSpecifier` needs; request it from the Rides tab before the first sync, mirroring how the app already requests the BLE runtime permissions.)

- [ ] **Step 2: `sync/WifiSyncConnector.kt`:**

```kotlin
package com.kompressorlink.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

// Joins the logger's SoftAP as a local-only network (no internet routing —
// Android keeps mobile data up) and yields a Network whose openConnection
// pins sockets to the AP. Interface-first so RidesViewModel tests use a fake.
interface SyncNetwork {
    val opener: (URL) -> HttpURLConnection
    fun close()
}

interface WifiSyncConnector {
    /** Null = join failed/timed out. */
    suspend fun connect(): SyncNetwork?
}

class RealWifiSyncConnector(
    context: Context,
    private val ssid: String = "KompressorLink",
    private val psk: String = "kompressor-link",   // docs/wifi_sync_protocol.md
    private val timeoutMs: Long = 30_000,
) : WifiSyncConnector {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override suspend fun connect(): SyncNetwork? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(
                    WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(psk).build()
                )
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (cont.isActive) cont.resume(object : SyncNetwork {
                        override val opener: (URL) -> HttpURLConnection =
                            { url -> network.openConnection(url) as HttpURLConnection }
                        override fun close() = cm.unregisterNetworkCallback(this@apply())
                        private fun apply() = this@object_marker
                    })
                }
                override fun onUnavailable() {
                    if (cont.isActive) cont.resume(null)
                }
            }
            cm.requestNetwork(request, callback)
            cont.invokeOnCancellation { cm.unregisterNetworkCallback(callback) }
        }
    }
}
```

NOTE for the executor: the inner-class self-reference above (`close()` unregistering its own callback) doesn't transliterate directly — restructure while implementing (hold the callback in a local `lateinit var`, or return a small class holding `network + callback`). Kotlin compiler is the arbiter; the CONTRACT is: `SyncNetwork.close()` unregisters the callback (releasing the network), and `connect()` resumes null on `onUnavailable`/timeout. This file is thin OS glue — no unit test; reviewed + exercised in the Task 13 acceptance.

- [ ] **Step 3: `rides/RidesViewModel.kt`:**

```kotlin
package com.kompressorlink.app.rides

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.data.db.RideFileDao
import com.kompressorlink.app.data.db.RideFileEntity
import com.kompressorlink.app.data.db.RideFileStatus
import com.kompressorlink.app.sync.RideIngestor
import com.kompressorlink.app.sync.SyncClient
import com.kompressorlink.app.sync.WifiSyncConnector
import java.io.File
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
    private val makeClient: (baseUrl: String, opener: (java.net.URL) -> java.net.HttpURLConnection) -> SyncClient,
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
            try {
                val (baseUrl, opener) = if (debugServerOverride != null) {
                    debugServerOverride to { url: java.net.URL ->
                        url.openConnection() as java.net.HttpURLConnection
                    }
                } else {
                    if (!requestWifiSync()) {
                        _state.value = SyncUiState(error = "Logger not connected over BLE")
                        return@launch
                    }
                    _state.value = SyncUiState(syncing = true, phase = "Joining logger WiFi…")
                    val net = connector.connect()
                    if (net == null) {
                        _state.value = SyncUiState(error = "Couldn't join the logger's WiFi")
                        return@launch
                    }
                    "http://192.168.4.1" to net.opener
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
                        val bytes = File(ridesDir, row.name).readBytes()
                        val status = when (val r = ingestor.ingest(bytes, nowEpochMs())) {
                            is RideIngestor.Result.Ingested ->
                                row.copy(status = RideFileStatus.INGESTED.name, sessionId = r.sessionId)
                            is RideIngestor.Result.Duplicate ->
                                row.copy(status = RideFileStatus.DUPLICATE.name, sessionId = r.overlappingSessionId)
                            is RideIngestor.Result.Rejected ->
                                row.copy(status = RideFileStatus.FAILED.name)
                        }
                        dao.upsert(status.copy(updatedAtEpochMs = nowEpochMs()))
                    }
                }
                _state.value = SyncUiState()   // idle, done
            } catch (e: Exception) {
                _state.value = SyncUiState(error = e.message ?: "Sync failed")
            }
        }
    }
}
```

- [ ] **Step 4: `rides/RidesScreen.kt`** — Material 3, matching the 4.5 dark theme components (reuse 4.5 Task 20's shared UI pieces):
  - Top: "Sync now" button (disabled while `state.syncing`), phase text / error banner below it.
  - Below: `LazyColumn` of `files` — each row: ride name, human size (`"%.1f KB"`), status chip (PENDING gray / DOWNLOADING amber+linear progress `downloadedBytes/sizeBytes` / VERIFIED-INGESTED green / DUPLICATE gray "duplicate of a live session" / FAILED red), and for INGESTED a caption "session #<id>".
  - Empty state: "No rides synced yet. Start the engine, then sync when parked." Keep it honest — no fake data. Follow the 4.5 screens' composable structure (ViewModel via the app container; preview omitted like the other screens if they omit it).

- [ ] **Step 5: 5th tab.** In `MainActivity.kt`: add `Rides` to the nav items (icon: `Icons.Filled.CloudDownload` or `DirectionsCar` — match the import style of the existing four), a `composable("rides") { RidesScreen(...) }` route constructing `RidesViewModel` from the app container exactly like the neighbouring tabs construct theirs, and container wiring for `RideFileDao`, `RideIngestor`, `SyncClient` factory, `RealWifiSyncConnector`, `ridesDir = File(context.filesDir, "rides")`, and `debugServerOverride = if (BuildConfig.DEBUG) BuildConfig.KL_SYNC_DEV_SERVER.ifEmpty { null } else null`. Add to `app/build.gradle.kts` `defaultConfig`: `buildConfigField("String", "KL_SYNC_DEV_SERVER", "\"\"")` and in the debug block a value the user can point at the dev server (documented in Task 13).

- [ ] **Step 6: `rides/RidesViewModelTest.kt`** — fakes for everything (FakeRideFileDao from Task 11's test, a fake connector returning null, `requestWifiSync = { false }`):

```kotlin
    @Test fun `sync surfaces BLE-not-connected as an error, not a crash`() = runTest {
        val vm = RidesViewModel(
            dao = FakeRideFileDao(),
            requestWifiSync = { false },
            connector = object : WifiSyncConnector { override suspend fun connect() = null },
            makeClient = { _, _ -> error("unreachable") },
            ingestor = RideIngestor(refs, FakeSessionDao()),
            ridesDir = tmp.newFolder(),
        )
        vm.sync()
        advanceUntilIdle()
        assertEquals("Logger not connected over BLE", vm.state.value.error)
    }

    @Test fun `debug override skips BLE and reaches the client`() = runTest {
        var manifestFetched = false
        // Point the override at the Task-11 HttpServer fixture and assert
        // reconcile+download+ingest run end-to-end (reuse that server setup;
        // a 3-minute fast-init ride CSV as the body makes ingest succeed and
        // the row end INGESTED).
        // ... (build like SyncClientTest, assert final status INGESTED)
    }
```

Write the second test fully (server fixture + ride CSV body from `RideIngestorTest.rideCsv`); it is the plan's single most valuable integration test — BLE and WiFi faked away, everything else real.

- [ ] **Step 7: Run** `--tests "*RidesViewModel*"` then the FULL suite. Green.

---

### Task 13: Dev server + final verification + phone acceptance

**Files:**
- Create: `firmware/tools/kl_sync_dev_server.py`

- [ ] **Step 1: Write `firmware/tools/kl_sync_dev_server.py`** (stdlib only, like kline_sim.py):

```python
#!/usr/bin/env python3
"""PC stand-in for the ESP32 wifi_sync module (docs/wifi_sync_protocol.md v1).

Serves ride CSVs from a directory over the same /rides + /data API the
firmware exposes, so the Android sync client is testable end-to-end with no
device hardware. --die-after-bytes simulates the AP dropping mid-transfer
(resume testing). --generate synthesizes a plausible M271 ride CSV first.

Usage:
  python kl_sync_dev_server.py --dir ./rides --port 8765
  python kl_sync_dev_server.py --dir ./rides --generate 2 --port 8765
  python kl_sync_dev_server.py --dir ./rides --die-after-bytes 6000
"""
import argparse
import json
import math
import random
import zlib
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

MAX_CHUNK = 4096  # firmware kSyncMaxChunk


def generate_ride(path: Path, seq: int, minutes: int = 4) -> None:
    """Cold-start city ride: warms 20->88 degC, idles + a few pulls."""
    rng = random.Random(seq)
    lines = ["#fw_version=devsim", "#init_mode=fast-init", "t_ms,signal,value",
             "#time_sync=0:1752700000000"]
    t = 0
    while t < minutes * 60_000:
        ect = min(88.0, 20.0 + t / 4000.0)          # ~15 degC/min warm-up
        pull = (t // 30_000) % 3 == 2 and (t % 30_000) < 4_000
        rpm = 3400 + rng.uniform(-100, 100) if pull else 820 + rng.uniform(-25, 25)
        throttle = 85.0 if pull else 12.0
        ltft = 6.0 if not pull else 1.5             # mild idle-vs-load trim shift
        maf = 95.0 if pull else 4.2
        o2 = 0.45 if t < 25_000 else (0.75 if (t // 500) % 2 else 0.15)
        for offset, (name, value) in enumerate([
            ("RPM", rpm), ("SPEED", 42.0 if pull else 0.0), ("THROTTLE", throttle),
            ("MAF_GS", maf), ("LTFT1", ltft), ("ECT", ect), ("O2_B1S1_V", o2),
        ]):
            lines.append(f"{t + offset * 60},{name},{value:.3f}")
        t += 500
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


class Handler(BaseHTTPRequestHandler):
    rides_dir: Path
    die_after: int = -1
    served: int = 0

    def do_GET(self):  # noqa: N802 (stdlib naming)
        url = urlparse(self.path)
        if url.path == "/rides":
            rides = []
            for f in sorted(self.rides_dir.glob("ride_*.csv")):
                data = f.read_bytes()
                rides.append({"name": f.name, "size": len(data),
                              "crc32": f"{zlib.crc32(data) & 0xFFFFFFFF:08X}"})
            body = json.dumps({"rides": rides}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if url.path == "/data":
            q = parse_qs(url.query)
            try:
                name, offset, length = q["name"][0], int(q["offset"][0]), int(q["length"][0])
            except (KeyError, ValueError):
                self.send_error(400, "name/offset/length required")
                return
            f = self.rides_dir / name
            if not f.is_file() or "/" in name or "\\" in name:
                self.send_error(404, "no such ride")
                return
            if 0 <= Handler.die_after <= Handler.served:
                self.connection.close()  # simulate the AP vanishing
                return
            data = f.read_bytes()[offset:offset + min(length, MAX_CHUNK)]
            Handler.served += len(data)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_error(404)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dir", default="./rides")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--generate", type=int, default=0, metavar="N")
    ap.add_argument("--die-after-bytes", type=int, default=-1)
    args = ap.parse_args()
    rides = Path(args.dir)
    rides.mkdir(parents=True, exist_ok=True)
    for i in range(args.generate):
        generate_ride(rides / f"ride_{i + 1:05d}.csv", seq=i + 1)
    Handler.rides_dir = rides
    Handler.die_after = args.die_after_bytes
    print(f"kl_sync_dev_server on 0.0.0.0:{args.port}, dir={rides.resolve()}")
    HTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
```

Smoke it: `python firmware/tools/kl_sync_dev_server.py --dir "$env:TEMP/klrides" --generate 1 --port 8765` then `curl http://127.0.0.1:8765/rides` shows one ride with size+crc; stop it.

- [ ] **Step 2: Full unit suite, final.**

```powershell
cd android
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, everything green. Report total vs the Task 0 baseline (this plan adds roughly 25–30 tests).

- [ ] **Step 3 (USER — phone acceptance, documented in the final report; the agent does not run these):**
  1. PC and S23 FE on the same LAN. Run the dev server: `python firmware/tools/kl_sync_dev_server.py --dir rides --generate 2 --port 8765`.
  2. Set the debug base URL (`KL_SYNC_DEV_SERVER` buildConfig field in the debug block) to `http://<PC-LAN-IP>:8765`, install the debug build.
  3. Rides tab → Sync now → both rides download, verify, ingest; statuses land INGESTED; Health tab: after enough synthetic rides (re-run with more `--generate`), the new metrics appear on their subsystem cards. (Generated rides are `fast-init` ⇒ REAL_RIDE — on a debug build with synthetic data that's acceptable for acceptance; wipe app data afterwards so synthetic rides never linger under a REAL tag.)
  4. Resume check: restart the server with `--die-after-bytes 6000`, wipe app data, sync (dies mid-file, status DOWNLOADING with a partial offset), restart the server without the flag, sync again — completes from the offset (watch the dev server log: the second run's first `/data` request starts at the persisted offset, not 0).
  5. Real-device path (optional, needs the firmware plan flashed): no debug override → Rides tab Sync → BLE 0x03 → phone joins `KompressorLink` AP → empty manifest from a fresh board is a PASS (proves the whole chain).
  6. When done testing: **wipe app data** (synthetic REAL_RIDE sessions must not survive into real usage).

- [ ] **Step 4: Clean tree.** `git status --short` shows only this plan's files. No commits — the user commits via the VS Code GUI.

---

## Self-review notes (already applied)

- Proposal 4's `MAF_THROTTLE_RESPONSE` does NOT appear anywhere above — rejected, substituted by `MAF_HIGH_LOAD` (see Validation context).
- Proposal 2 (LSTM/TFLite) contributes NOTHING to this plan — no dependency, no assets, no "prediction" copy. Drift wording stays 4.5's Theil–Sen extrapolation language.
- Replay cadence (500 ms buckets) deliberately mirrors live BLE so `DwellGate`/count-floor semantics are identical across paths (Task 10 preamble).
- Odometer stays REAL_BLE-only (Task 8 Step 3) — the dedup protects baselines, the query scope protects kilometres.
- All four new metrics respect the Untested contract end-to-end: missing signals → null contexts → absent stats → absent metric → no verdict; O2 metrics additionally ride on [Likely] PIDs that Phase 3's car probe may remove — absence must render as "not available on this car," never as a fault.
