# Phase 4.5: App Intelligence & UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Android app gains memory (Room session history), an honest failure-early-warning system (live severity + personal baseline + drift — never claimed as ML fault prediction), a preventive-maintenance tracker with service log and background reminders, a three-layer friendly DTC explanation system, and a full Material-3 dark redesign with four tabs — all software-only, phone-only, zero hardware.

**Architecture:** See `docs/superpowers/specs/2026-07-13-phase45-app-intelligence-design.md` (normative) and `docs/superpowers/specs/2026-07-13-phase45-ui-mockup.html` (directional visual target). Summary: a `SessionRecorder` observes the existing `TelemetrySource` seam and writes per-session aggregates to Room; pure-Kotlin engines (`health/`, `maintenance/`) compute severity/baseline/drift/due-state from those rows; every row carries its source tag and **REAL_BLE-only queries** feed all real verdicts (sim data is quarantined behind an explicit Demo mode); a `DtcRepository` answers every code via curated → generic → structural layers; four Compose screens consume it all.

**Tech Stack:** Kotlin 2.0.20 + Jetpack Compose (Material 3, BOM 2024.09.00), Room 2.6.1 (KSP), WorkManager 2.9.1, kotlinx-serialization, DataStore, JUnit4 + kotlinx-coroutines-test + Robolectric 4.14.1 (DAO tests only). No firmware, no pipeline, no new transport.

---

## Execution notes (read first — hard-won facts, do not rediscover)

- **NEVER run `git commit`.** The user commits via the VS Code Source Control GUI. Every task ends "leave for review" with a clean, reviewable working tree.
- **Full replacement files** when changing an existing file — modules are sized to regenerate whole.
- **Android commands** run from `D:\C180 Project\android\` in PowerShell, ALWAYS with the bundled JDK:
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  cd "D:\C180 Project\android"
  .\gradlew.bat :app:testDebugUnitTest
  ```
  Filter one suite with `--tests "com.kompressorlink.app.health.*"`. `adb` (user steps only) is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.
- **First Robolectric run downloads `android-all` jars (~100 MB, needs network) — it is slow ONCE, then cached.** Do not interpret the download pause as a hang.
- **All Robolectric tests use `@Config(sdk = [34], application = Application::class)`** — plain `Application`, NOT `KompressorLinkApp`, so tests never construct the real DI container (BLE session, DataStore, WorkManager scheduling).
- **Version pins in Task 0 are [Likely] starting points** (chosen for AGP 8.7.2 / Kotlin 2.0.20 compatibility). If Gradle resolution fails, bump to the nearest resolvable version, record what changed in the task report, and keep the toml as the single place versions live. KSP MUST stay in lockstep with Kotlin (`2.0.20-1.0.25` ↔ Kotlin `2.0.20`).
- **Firmware and pipeline are untouched this phase.** Do not edit anything under `firmware/` or `pipeline/`. Their tests are not part of this phase's verification loop.
- Confidence tags (`[Confirmed]`/`[Likely]`/`[Best estimate]`/`[Guessing]`) in code comments are load-bearing project policy (PLAN.md §0.4) — keep every one that appears in the code below.
- Piping to `tee` masks exit codes — read the actual Gradle output before claiming success.
- Session-limit economics: tasks are fully specified so an interrupted subagent is cheap to resume — verify what exists on disk, continue from the first missing step.
- `HealthTuning` (Task 5) is the single home for every tunable threshold. Tests reference those constants, never magic numbers.

## File map

**Created:**
- `android/app/src/main/java/com/kompressorlink/app/data/SessionSource.kt`, `data/Repositories.kt`, `data/RoomRepositories.kt`, `data/db/SessionEntities.kt`, `data/db/WarningEntity.kt`, `data/db/MaintenanceEntities.kt`, `data/db/SessionDao.kt`, `data/db/WarningDao.kt`, `data/db/MaintenanceDao.kt`, `data/db/OdometerDao.kt`, `data/db/KlDatabase.kt`
- `health/HealthTuning.kt`, `health/SnapshotContexts.kt`, `health/StreamingStats.kt`, `health/SessionAggregator.kt`, `health/SessionRecorder.kt`, `health/HysteresisFilter.kt`, `health/Subsystem.kt`, `health/DtcSubsystemMap.kt`, `health/Metrics.kt`, `health/Baseline.kt`, `health/TheilSen.kt`, `health/Drift.kt`, `health/HealthRollup.kt`, `health/LiveWarningMonitor.kt`, `health/PostSessionEvaluator.kt`, `health/HealthViewModel.kt`, `health/HealthScreen.kt`
- `maintenance/BuiltinSchedule.kt`, `maintenance/OdometerEstimator.kt`, `maintenance/DueCalculator.kt`, `maintenance/ReminderPolicy.kt`, `maintenance/MaintenanceCheckWorker.kt`, `maintenance/MaintenanceViewModel.kt`, `maintenance/MaintenanceScreen.kt`
- `dtc/DtcModels.kt`, `dtc/DtcFamilyDecoder.kt`, `dtc/DtcRepository.kt`
- `ui/theme/Theme.kt`, `ui/components/Components.kt`
- assets: `android/app/src/main/assets/generic_dtc.json`
- tests: mirrors under `android/app/src/test/java/com/kompressorlink/app/` for every module above, plus `data/FakeRepositories.kt` (shared test doubles) and `RobolectricSmokeTest.kt`

**Modified (full replacement):** `android/gradle/libs.versions.toml`, `android/build.gradle.kts`, `android/app/build.gradle.kts`, `KompressorLinkApp.kt`, `MainActivity.kt`, `telemetry/FakeTelemetrySource.kt` (fault scenario adds pending P1570), `reference/ReferenceRepository.kt` (bands only — DTC parsing moves to `dtc/`), `dashboard/DashboardLogic.kt`, `dashboard/DashboardViewModel.kt`, `dashboard/DashboardScreen.kt`, `dtc/DtcViewModel.kt`, `dtc/DtcScreen.kt`, assets `w203_bands.json` + `w203_dtc.json`, tests `ReferenceRepositoryTest.kt`, `DashboardLogicTest.kt`, `DashboardViewModelTest.kt`, `DtcViewModelTest.kt`, `FakeTelemetrySourceTest.kt`

**Untouched:** everything under `firmware/`, `pipeline/`, `hardware/`, `docs/ble_protocol.md`; `telemetry/ble/*`, `connection/*`, `telemetry/Signal.kt`, `TelemetrySnapshot.kt`, `TelemetrySource.kt`, `SourceSwitcher.kt`, `ConnectionState.kt`, `DtcReport.kt`.

---

## Task 0: New dependencies + Robolectric walking skeleton

Adds Room (KSP), WorkManager, and the Robolectric test stack, and proves the toolchain with one trivial Robolectric test before anything depends on it.

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/build.gradle.kts`
- Modify: `android/app/build.gradle.kts`
- Test: `android/app/src/test/java/com/kompressorlink/app/RobolectricSmokeTest.kt`

- [ ] **Step 1: Full replacement of `android/gradle/libs.versions.toml`:**

```toml
[versions]
agp = "8.7.2"
kotlin = "2.0.20"
# KSP must stay in lockstep with the Kotlin version (KSP releases are named
# <kotlin>-<ksp>). [Likely — bump both together if resolution fails.]
ksp = "2.0.20-1.0.25"
coreKtx = "1.13.1"
lifecycleRuntimeKtx = "2.8.6"
activityCompose = "1.9.2"
composeBom = "2024.09.00"
navigationCompose = "2.8.2"
datastore = "1.1.1"
kotlinxSerializationJson = "1.7.3"
kotlinxCoroutines = "1.9.0"
room = "2.6.1"
work = "2.9.1"
junit = "4.13.2"
robolectric = "4.14.1"
androidxTestCore = "1.6.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-service = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core-ktx", version.ref = "androidxTestCore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 2: Full replacement of `android/build.gradle.kts`:**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 3: Full replacement of `android/app/build.gradle.kts`:**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kompressorlink.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kompressorlink.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0-phase4.5"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged Android resources on the JVM
            // test classpath (DAO tests, worker smoke checks).
            isIncludeAndroidResources = true
        }
    }
}

// Room: write the schema JSON into version control so future phases can
// write honest migrations against it (spec §2).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
```

- [ ] **Step 4: Write the walking-skeleton test** at `android/app/src/test/java/com/kompressorlink/app/RobolectricSmokeTest.kt`:

```kotlin
package com.kompressorlink.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Walking skeleton for the Robolectric stack added in Phase 4.5 Task 0.
// application = Application::class keeps the real KompressorLinkApp DI
// container (BLE, DataStore, WorkManager) out of every Robolectric test.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RobolectricSmokeTest {
    @Test
    fun applicationContextAvailable() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull(app)
    }
}
```

- [ ] **Step 5: Run the full unit-test suite** (first run downloads Robolectric jars — slow once):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\C180 Project\android"
.\gradlew.bat :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, all pre-existing 53 tests + `RobolectricSmokeTest` pass. If dependency resolution fails, adjust versions per the execution note and record what changed.

- [ ] **Step 6: Verify the APK still assembles:**

```powershell
.\gradlew.bat :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Leave for review** (clean working tree, no commit — user commits via GUI).

---

## Task 1: Room database core — sessions + session_stats

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/data/SessionSource.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/data/db/SessionEntities.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/data/db/SessionDao.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/data/db/KlDatabase.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/data/db/SessionDaoTest.kt`

- [ ] **Step 1: Write `data/SessionSource.kt`** (pure Kotlin — the quarantine tag):

```kotlin
package com.kompressorlink.app.data

import com.kompressorlink.app.telemetry.SourceChoice

// The quarantine rule's atom (spec §1): every telemetry-derived row carries
// its origin. Health verdicts, baselines, drift, odometer estimation, and
// reminders read REAL_BLE rows ONLY; sim rows exist solely behind the Health
// screen's explicit Demo mode.
enum class SessionSource {
    SIM_HEALTHY, SIM_FAULT, SIM_SPARSE, REAL_BLE;

    val isReal: Boolean get() = this == REAL_BLE

    companion object {
        fun from(choice: SourceChoice): SessionSource = when (choice) {
            SourceChoice.SIMULATED_HEALTHY -> SIM_HEALTHY
            SourceChoice.SIMULATED_FAULT -> SIM_FAULT
            SourceChoice.SIMULATED_SPARSE -> SIM_SPARSE
            SourceChoice.REAL_BLE -> REAL_BLE
        }

        val REAL: Set<SessionSource> = setOf(REAL_BLE)
        val SIM: Set<SessionSource> = setOf(SIM_HEALTHY, SIM_FAULT, SIM_SPARSE)
    }
}

// Warning rows collapse the tag to two buckets (spec §2 warnings.source).
enum class WarningSource { SIM, REAL;
    companion object {
        fun from(source: SessionSource): WarningSource =
            if (source.isReal) REAL else SIM
    }
}
```

- [ ] **Step 2: Write `data/db/SessionEntities.kt`:**

```kotlin
package com.kompressorlink.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// One row per drive/sim run that passed the persist gate (spec §3).
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val source: String,          // SessionSource enum name
    val snapshotCount: Int,
    val warmIdleSeconds: Float,
    val distanceKm: Float?,      // non-null ONLY for REAL_BLE sessions with SPEED data
    val hasStoredDtc: Boolean,
)

// Per-signal streaming stats for one session. secondsOutOfBand/worstLevel are
// RAW band comparisons (pre-hysteresis) — stored stats record what the car
// did; hysteresis smooths UI only (spec §3).
@Entity(
    tableName = "session_stats",
    primaryKeys = ["sessionId", "signal"],
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class SessionStatEntity(
    val sessionId: Long,
    val signal: String,          // Signal enum name
    val sampleCount: Int,
    val mean: Float,
    val min: Float,
    val max: Float,
    val stdDev: Float,
    val secondsOutOfBand: Float,
    val worstLevel: String,      // "OK" | "AMBER" | "RED" (raw)
    val warmIdleMean: Float?,    // MAF_GS, STFT1, LTFT1, ECT only
    val warmIdleCount: Int,
    val engineRunningMean: Float?, // BATT_V_ADC only
    val engineRunningCount: Int,
    val engineOffMean: Float?,     // BATT_V_ADC only
    val engineOffCount: Int,
)
```

- [ ] **Step 3: Write `data/db/SessionDao.kt`:**

```kotlin
package com.kompressorlink.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertStats(stats: List<SessionStatEntity>)

    // stats(id) is a factory so the FK is only known after the session insert
    // returns its rowid; @Transaction keeps session+stats atomic.
    @Transaction
    suspend fun insertSessionWithStats(
        session: SessionEntity,
        stats: (sessionId: Long) -> List<SessionStatEntity>,
    ): Long {
        val id = insertSession(session)
        insertStats(stats(id))
        return id
    }

    @Query("SELECT * FROM sessions WHERE source IN (:sources) ORDER BY endedAtEpochMs DESC LIMIT :limit")
    suspend fun recentBySources(sources: List<String>, limit: Int): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE source IN (:sources) ORDER BY endedAtEpochMs DESC LIMIT :limit")
    fun observeRecentBySources(sources: List<String>, limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session_stats WHERE sessionId IN (:sessionIds)")
    suspend fun statsForSessions(sessionIds: List<Long>): List<SessionStatEntity>

    @Query("SELECT COUNT(*) FROM sessions WHERE source IN (:sources)")
    suspend fun countBySources(sources: List<String>): Int

    @Query("SELECT SUM(distanceKm) FROM sessions WHERE source = 'REAL_BLE' AND endedAtEpochMs > :sinceEpochMs")
    suspend fun realDistanceSince(sinceEpochMs: Long): Float?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}
```

- [ ] **Step 4: Write `data/db/KlDatabase.kt`** (v1 of this file — Tasks 2–3 replace it wholesale as entities land):

```kotlin
package com.kompressorlink.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Phase 4.5 database. version stays 1 for the whole phase (fresh install —
// no shipped users, no migrations yet); exportSchema writes the schema JSON
// into android/app/schemas/ so Phase 5+ migrations start honest (spec §2).
@Database(
    entities = [SessionEntity::class, SessionStatEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class KlDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        fun build(context: Context): KlDatabase =
            Room.databaseBuilder(context, KlDatabase::class.java, "kl.db").build()

        fun inMemory(context: Context): KlDatabase =
            Room.inMemoryDatabaseBuilder(context, KlDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
```

- [ ] **Step 5: Write the failing DAO test** at `android/app/src/test/java/com/kompressorlink/app/data/db/SessionDaoTest.kt`:

```kotlin
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
```

Note: the SIM_HEALTHY row in the last test carries a non-null `distanceKm` **deliberately** — the recorder never writes that in production, but the DAO-level quarantine (`source = 'REAL_BLE'` in the SQL) must hold even against bad rows.

- [ ] **Step 6: Run the new suite — expect FAIL** (classes don't compile / test discovery fails) **before Step 2–4 files exist; PASS after.** With all files written:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.data.db.SessionDaoTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests pass. Also verify the schema JSON appeared at `android/app/schemas/com.kompressorlink.app.data.db.KlDatabase/1.json`.

- [ ] **Step 7: Run the full suite** (`.\gradlew.bat :app:testDebugUnitTest`) — all green. Leave for review.

---
## Task 2: Warnings table

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/data/db/WarningEntity.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/data/db/WarningDao.kt`
- Modify: `android/app/src/main/java/com/kompressorlink/app/data/db/KlDatabase.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/data/db/WarningDaoTest.kt`

- [ ] **Step 1: Write `data/db/WarningEntity.kt`:**

```kotlin
package com.kompressorlink.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// One warning event (spec §2/§5.5). Dedupe rule lives in WarningRepository:
// an UNACKNOWLEDGED row with the same dedupeKey is updated (lastSeenAt,
// detail) instead of inserting; acknowledged rows are history — a recurrence
// inserts a NEW row. dedupeKey = "kind:subsystem:signalOrCode".
@Entity(tableName = "warnings", indices = [Index("dedupeKey"), Index("source")])
data class WarningEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
    val sessionId: Long?,        // null for cross-session warnings (drift/baseline)
    val subsystem: String,       // Subsystem enum name
    val signal: String?,         // Signal enum name, null for DTC warnings
    val level: String,           // "WATCH" | "ATTENTION"
    val kind: String,            // "LIVE_OUT_OF_BAND" | "BASELINE_DEVIATION" | "DRIFT" | "DTC"
    val title: String,
    val detail: String,
    val acknowledged: Boolean,
    val source: String,          // WarningSource enum name: "SIM" | "REAL"
    val dedupeKey: String,
)
```

- [ ] **Step 2: Write `data/db/WarningDao.kt`:**

```kotlin
package com.kompressorlink.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WarningDao {
    @Insert
    suspend fun insert(warning: WarningEntity): Long

    @Update
    suspend fun update(warning: WarningEntity)

    @Query("SELECT * FROM warnings WHERE dedupeKey = :key AND acknowledged = 0 LIMIT 1")
    suspend fun openByKey(key: String): WarningEntity?

    @Query("UPDATE warnings SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    // Feed order (spec §8.3): unacknowledged first, then newest.
    @Query("SELECT * FROM warnings WHERE source = :source ORDER BY acknowledged ASC, lastSeenAtEpochMs DESC LIMIT :limit")
    fun observeBySource(source: String, limit: Int): Flow<List<WarningEntity>>

    @Query("SELECT * FROM warnings WHERE source = :source AND acknowledged = 0")
    suspend fun openBySource(source: String): List<WarningEntity>
}
```

- [ ] **Step 3: Full replacement of `data/db/KlDatabase.kt`** (adds the entity + DAO; version stays 1 — fresh install policy for this phase):

```kotlin
package com.kompressorlink.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Phase 4.5 database. version stays 1 for the whole phase (fresh install —
// no shipped users, no migrations yet); exportSchema writes the schema JSON
// into android/app/schemas/ so Phase 5+ migrations start honest (spec §2).
@Database(
    entities = [SessionEntity::class, SessionStatEntity::class, WarningEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class KlDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun warningDao(): WarningDao

    companion object {
        fun build(context: Context): KlDatabase =
            Room.databaseBuilder(context, KlDatabase::class.java, "kl.db").build()

        fun inMemory(context: Context): KlDatabase =
            Room.inMemoryDatabaseBuilder(context, KlDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
```

- [ ] **Step 4: Write the failing test** at `android/app/src/test/java/com/kompressorlink/app/data/db/WarningDaoTest.kt`:

```kotlin
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
        dao.insert(warning("real-1", source = "REAL"))
        dao.insert(warning("sim-1", source = "SIM"))
        dao.insert(warning("real-2", acknowledged = true, source = "REAL"))
        val real = dao.observeBySource("REAL", 10).first()
        assertEquals(2, real.size)
        assertEquals("real-1", real[0].dedupeKey)  // unacknowledged first
        assertEquals(1, dao.observeBySource("SIM", 10).first().size)
    }
}
```

- [ ] **Step 5: Run the suite** — FAIL before the entity/DAO exist, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.data.db.WarningDaoTest"
```
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 6: Full suite green** (`.\gradlew.bat :app:testDebugUnitTest`). Leave for review.

---

## Task 3: Maintenance + odometer tables and the builtin schedule

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/data/db/MaintenanceEntities.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/data/db/MaintenanceDao.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/data/db/OdometerDao.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/maintenance/BuiltinSchedule.kt`
- Modify: `android/app/src/main/java/com/kompressorlink/app/data/db/KlDatabase.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/data/db/MaintenanceDaoTest.kt`

- [ ] **Step 1: Write `data/db/MaintenanceEntities.kt`:**

```kotlin
package com.kompressorlink.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// A maintenance item (spec §2/§6.2). At least one of intervalKm /
// intervalMonths is non-null — enforced by MaintenanceRepository, not SQL.
// builtin items are editable but not deletable; custom items are deletable.
@Entity(tableName = "maintenance_items")
data class MaintenanceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,        // "ENGINE"|"TRANSMISSION"|"BRAKES"|"FLUIDS"|"INSPECTION"
    val intervalKm: Int?,
    val intervalMonths: Int?,
    val note: String,            // the "why this matters on this engine" line
    val confidence: String,      // CLAUDE.md confidence tag
    val builtin: Boolean,
    val enabled: Boolean,
    val lastNotifiedAtEpochMs: Long?,
)

@Entity(
    tableName = "service_log",
    foreignKeys = [ForeignKey(
        entity = MaintenanceItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("itemId")],
)
data class ServiceLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val epochMs: Long,           // when the service was done
    val km: Int?,                // odometer at service time, if known
    val note: String?,
)

// Manual odometer anchors ONLY (spec §2) — estimates are derived at read
// time from anchor + real-session distances, never stored back as anchors.
@Entity(tableName = "odometer_entries")
data class OdometerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMs: Long,
    val km: Int,
)
```

- [ ] **Step 2: Write `maintenance/BuiltinSchedule.kt`** — the seeded M271/W203 schedule, verbatim from spec §6.2:

```kotlin
package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.MaintenanceItemEntity

// Spec §6.2's seeded schedule. Service-conservative defaults for a 22-year-
// old M271 — reference-guide-grade, NOT WIS gospel (the Maintenance screen
// carries the same confirm-against-WIS caveat as docs/w203_reference.md).
// All intervals editable in-app; confidence tags per CLAUDE.md rule 4.
object BuiltinSchedule {
    private fun item(
        name: String, category: String, km: Int?, months: Int?,
        note: String, confidence: String,
    ) = MaintenanceItemEntity(
        name = name, category = category, intervalKm = km, intervalMonths = months,
        note = note, confidence = confidence, builtin = true, enabled = true,
        lastNotifiedAtEpochMs = null,
    )

    val ITEMS: List<MaintenanceItemEntity> = listOf(
        item("Engine oil + filter", "ENGINE", 10_000, 12,
            "Chain-driven engine — fresh oil protects the timing chain and cam adjusters",
            "Best estimate"),
        item("Breather-hose inspection", "INSPECTION", 20_000, 24,
            "THE M271 failure: cracked partial-load breather hoses cause lean codes (P0171)",
            "Best estimate"),
        item("Cam-magnet oil-ingress check", "INSPECTION", 10_000, 12,
            "Known M271 issue — oil wicks into the connector/harness; check at each oil change",
            "Likely"),
        item("Spark plugs", "ENGINE", 60_000, 48,
            "Correct plug: FR6MPP332 or NGK ILFR6A [Confirmed type]",
            "Best estimate"),
        item("Air filter", "ENGINE", 40_000, 48,
            "Supercharged intake — a clean filter matters more than on NA engines",
            "Best estimate"),
        item("Cabin filter", "ENGINE", 20_000, 24,
            "Comfort item; cheap",
            "Best estimate"),
        item("Fuel filter", "ENGINE", 60_000, null,
            "Often forgotten on this era of MB",
            "Best estimate"),
        item("Coolant renewal", "FLUIDS", 100_000, 60,
            "Use MB 325.0-approved coolant",
            "Best estimate"),
        item("Brake fluid", "BRAKES", null, 24,
            "Standard MB two-year interval — hygroscopic fluid",
            "Likely"),
        item("Transmission service (722.6)", "TRANSMISSION", 60_000, 60,
            "'Lifetime fill' is a myth — fluid + filter + pan gasket",
            "Best estimate"),
        item("Poly-V belt + tensioner inspection", "INSPECTION", 60_000, 48,
            "Listen for tensioner rattle; check for cracking",
            "Best estimate"),
    )
}
```

- [ ] **Step 3: Write `data/db/MaintenanceDao.kt`:**

```kotlin
package com.kompressorlink.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MaintenanceDao {
    @Insert
    suspend fun insertItem(item: MaintenanceItemEntity): Long

    @Insert
    suspend fun insertItems(items: List<MaintenanceItemEntity>)

    @Update
    suspend fun updateItem(item: MaintenanceItemEntity)

    // builtin = 0 guard: builtins are never deletable (spec §2).
    @Query("DELETE FROM maintenance_items WHERE id = :id AND builtin = 0")
    suspend fun deleteCustomItem(id: Long)

    @Query("SELECT * FROM maintenance_items ORDER BY name")
    fun observeItems(): Flow<List<MaintenanceItemEntity>>

    @Query("SELECT * FROM maintenance_items")
    suspend fun items(): List<MaintenanceItemEntity>

    @Query("SELECT * FROM maintenance_items WHERE id = :id")
    suspend fun itemById(id: Long): MaintenanceItemEntity?

    @Query("SELECT COUNT(*) FROM maintenance_items WHERE builtin = 1")
    suspend fun builtinCount(): Int

    @Query("UPDATE maintenance_items SET lastNotifiedAtEpochMs = :atMs WHERE id = :id")
    suspend fun stampNotified(id: Long, atMs: Long)

    @Query("UPDATE maintenance_items SET lastNotifiedAtEpochMs = NULL WHERE id = :id")
    suspend fun clearNotified(id: Long)

    @Insert
    suspend fun insertLog(log: ServiceLogEntity): Long

    @Query("SELECT * FROM service_log WHERE itemId = :itemId ORDER BY epochMs DESC")
    fun observeLogsFor(itemId: Long): Flow<List<ServiceLogEntity>>

    @Query("SELECT * FROM service_log WHERE itemId = :itemId ORDER BY epochMs DESC LIMIT 1")
    suspend fun latestLogFor(itemId: Long): ServiceLogEntity?

    @Query("SELECT * FROM service_log ORDER BY epochMs DESC")
    suspend fun allLogs(): List<ServiceLogEntity>
}
```

- [ ] **Step 4: Write `data/db/OdometerDao.kt`:**

```kotlin
package com.kompressorlink.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OdometerDao {
    @Insert
    suspend fun insert(entry: OdometerEntryEntity): Long

    @Query("SELECT * FROM odometer_entries ORDER BY epochMs DESC LIMIT 1")
    suspend fun latest(): OdometerEntryEntity?

    @Query("SELECT * FROM odometer_entries ORDER BY epochMs DESC LIMIT 1")
    fun observeLatest(): Flow<OdometerEntryEntity?>

    @Query("SELECT MAX(km) FROM odometer_entries")
    suspend fun maxKm(): Int?
}
```

- [ ] **Step 5: Full replacement of `data/db/KlDatabase.kt`** (all six entities — final form for this phase):

```kotlin
package com.kompressorlink.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Phase 4.5 database. version stays 1 for the whole phase (fresh install —
// no shipped users, no migrations yet); exportSchema writes the schema JSON
// into android/app/schemas/ so Phase 5+ migrations start honest (spec §2).
@Database(
    entities = [
        SessionEntity::class, SessionStatEntity::class, WarningEntity::class,
        MaintenanceItemEntity::class, ServiceLogEntity::class, OdometerEntryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KlDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun warningDao(): WarningDao
    abstract fun maintenanceDao(): MaintenanceDao
    abstract fun odometerDao(): OdometerDao

    companion object {
        fun build(context: Context): KlDatabase =
            Room.databaseBuilder(context, KlDatabase::class.java, "kl.db").build()

        fun inMemory(context: Context): KlDatabase =
            Room.inMemoryDatabaseBuilder(context, KlDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
```

- [ ] **Step 6: Write the failing test** at `android/app/src/test/java/com/kompressorlink/app/data/db/MaintenanceDaoTest.kt`:

```kotlin
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
        dao.deleteCustomItem(builtinId)
        assertNotNull(dao.itemById(builtinId))  // still there

        val customId = dao.insertItem(
            MaintenanceItemEntity(
                name = "Custom wax", category = "INSPECTION", intervalKm = null,
                intervalMonths = 6, note = "", confidence = "Best estimate",
                builtin = false, enabled = true, lastNotifiedAtEpochMs = null,
            )
        )
        dao.deleteCustomItem(customId)
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
```

- [ ] **Step 7: Run it** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.data.db.MaintenanceDaoTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 8: Full suite green.** Leave for review.

---

## Task 4: Repository layer — interfaces, Room implementations, shared fakes

Engines and ViewModels only ever see these interfaces; JVM tests use the fakes, Robolectric proves the Room implementations (dedupe upsert, monotonic odometer, idempotent seeding).

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/data/Repositories.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/data/RoomRepositories.kt`
- Create: `android/app/src/test/java/com/kompressorlink/app/data/FakeRepositories.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/data/RoomRepositoriesTest.kt`

- [ ] **Step 1: Write `data/Repositories.kt`:**

```kotlin
package com.kompressorlink.app.data

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.OdometerEntryEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.data.db.WarningEntity
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    suspend fun record(session: SessionEntity, stats: (sessionId: Long) -> List<SessionStatEntity>): Long
    suspend fun recent(sources: Set<SessionSource>, limit: Int): List<SessionEntity>
    fun observeRecent(sources: Set<SessionSource>, limit: Int): Flow<List<SessionEntity>>
    suspend fun statsForSessions(sessionIds: List<Long>): List<SessionStatEntity>
    suspend fun count(sources: Set<SessionSource>): Int
    /** Sum of REAL_BLE session distances ended after the given time; 0 if none. */
    suspend fun realDistanceSince(epochMs: Long): Float
}

interface WarningRepository {
    /**
     * Dedupe upsert (spec §2): if an unacknowledged warning with the same
     * dedupeKey exists, update lastSeenAt + detail + level; else insert.
     */
    suspend fun raise(candidate: WarningEntity)
    fun observe(source: WarningSource, limit: Int = 50): Flow<List<WarningEntity>>
    suspend fun open(source: WarningSource): List<WarningEntity>
    suspend fun acknowledge(id: Long)
}

interface OdometerRepository {
    /**
     * Odometers don't run backwards: rejects km below the highest existing
     * anchor with a human-readable message in the failure.
     */
    suspend fun addAnchor(km: Int, atEpochMs: Long): Result<Unit>
    suspend fun latestAnchor(): OdometerEntryEntity?
    fun observeLatestAnchor(): Flow<OdometerEntryEntity?>
}

interface MaintenanceRepository {
    /** Inserts BuiltinSchedule.ITEMS exactly once (idempotent). */
    suspend fun ensureSeeded()
    fun observeItems(): Flow<List<MaintenanceItemEntity>>
    suspend fun items(): List<MaintenanceItemEntity>
    suspend fun itemById(id: Long): MaintenanceItemEntity?
    suspend fun latestLogFor(itemId: Long): ServiceLogEntity?
    fun observeLogsFor(itemId: Long): Flow<List<ServiceLogEntity>>
    /** Also clears the item's lastNotifiedAtEpochMs (spec §6.3). */
    suspend fun logService(itemId: Long, epochMs: Long, km: Int?, note: String?)
    /** Enforces: at least one of intervalKm/intervalMonths non-null. */
    suspend fun updateItem(item: MaintenanceItemEntity): Result<Unit>
    suspend fun addCustomItem(item: MaintenanceItemEntity): Result<Long>
    suspend fun deleteCustomItem(id: Long)
    suspend fun stampNotified(id: Long, atEpochMs: Long)
}
```

- [ ] **Step 2: Write `data/RoomRepositories.kt`:**

```kotlin
package com.kompressorlink.app.data

import com.kompressorlink.app.data.db.KlDatabase
import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.OdometerEntryEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.maintenance.BuiltinSchedule
import kotlinx.coroutines.flow.Flow

class RoomSessionRepository(private val db: KlDatabase) : SessionRepository {
    override suspend fun record(
        session: SessionEntity,
        stats: (sessionId: Long) -> List<SessionStatEntity>,
    ): Long = db.sessionDao().insertSessionWithStats(session, stats)

    override suspend fun recent(sources: Set<SessionSource>, limit: Int): List<SessionEntity> =
        db.sessionDao().recentBySources(sources.map { it.name }, limit)

    override fun observeRecent(sources: Set<SessionSource>, limit: Int): Flow<List<SessionEntity>> =
        db.sessionDao().observeRecentBySources(sources.map { it.name }, limit)

    override suspend fun statsForSessions(sessionIds: List<Long>): List<SessionStatEntity> =
        if (sessionIds.isEmpty()) emptyList() else db.sessionDao().statsForSessions(sessionIds)

    override suspend fun count(sources: Set<SessionSource>): Int =
        db.sessionDao().countBySources(sources.map { it.name })

    override suspend fun realDistanceSince(epochMs: Long): Float =
        db.sessionDao().realDistanceSince(epochMs) ?: 0f
}

class RoomWarningRepository(private val db: KlDatabase) : WarningRepository {
    override suspend fun raise(candidate: WarningEntity) {
        val open = db.warningDao().openByKey(candidate.dedupeKey)
        if (open == null) {
            db.warningDao().insert(candidate)
        } else {
            db.warningDao().update(
                open.copy(
                    lastSeenAtEpochMs = candidate.lastSeenAtEpochMs,
                    detail = candidate.detail,
                    level = candidate.level,
                )
            )
        }
    }

    override fun observe(source: WarningSource, limit: Int): Flow<List<WarningEntity>> =
        db.warningDao().observeBySource(source.name, limit)

    override suspend fun open(source: WarningSource): List<WarningEntity> =
        db.warningDao().openBySource(source.name)

    override suspend fun acknowledge(id: Long) = db.warningDao().acknowledge(id)
}

class RoomOdometerRepository(private val db: KlDatabase) : OdometerRepository {
    override suspend fun addAnchor(km: Int, atEpochMs: Long): Result<Unit> {
        val maxSoFar = db.odometerDao().maxKm()
        if (maxSoFar != null && km < maxSoFar) {
            return Result.failure(
                IllegalArgumentException(
                    "Odometer can't go backwards: last entered $maxSoFar km"
                )
            )
        }
        db.odometerDao().insert(OdometerEntryEntity(epochMs = atEpochMs, km = km))
        return Result.success(Unit)
    }

    override suspend fun latestAnchor(): OdometerEntryEntity? = db.odometerDao().latest()

    override fun observeLatestAnchor(): Flow<OdometerEntryEntity?> = db.odometerDao().observeLatest()
}

class RoomMaintenanceRepository(private val db: KlDatabase) : MaintenanceRepository {
    override suspend fun ensureSeeded() {
        if (db.maintenanceDao().builtinCount() == 0) {
            db.maintenanceDao().insertItems(BuiltinSchedule.ITEMS)
        }
    }

    override fun observeItems(): Flow<List<MaintenanceItemEntity>> = db.maintenanceDao().observeItems()
    override suspend fun items(): List<MaintenanceItemEntity> = db.maintenanceDao().items()
    override suspend fun itemById(id: Long): MaintenanceItemEntity? = db.maintenanceDao().itemById(id)
    override suspend fun latestLogFor(itemId: Long): ServiceLogEntity? = db.maintenanceDao().latestLogFor(itemId)
    override fun observeLogsFor(itemId: Long): Flow<List<ServiceLogEntity>> = db.maintenanceDao().observeLogsFor(itemId)

    override suspend fun logService(itemId: Long, epochMs: Long, km: Int?, note: String?) {
        db.maintenanceDao().insertLog(ServiceLogEntity(itemId = itemId, epochMs = epochMs, km = km, note = note))
        db.maintenanceDao().clearNotified(itemId)
    }

    private fun validate(item: MaintenanceItemEntity): Result<Unit> =
        if (item.intervalKm == null && item.intervalMonths == null) {
            Result.failure(IllegalArgumentException("Set a km interval, a month interval, or both"))
        } else Result.success(Unit)

    override suspend fun updateItem(item: MaintenanceItemEntity): Result<Unit> =
        validate(item).onSuccess { db.maintenanceDao().updateItem(item) }

    override suspend fun addCustomItem(item: MaintenanceItemEntity): Result<Long> {
        validate(item).onFailure { return Result.failure(it) }
        return Result.success(db.maintenanceDao().insertItem(item.copy(builtin = false)))
    }

    override suspend fun deleteCustomItem(id: Long) = db.maintenanceDao().deleteCustomItem(id)
    override suspend fun stampNotified(id: Long, atEpochMs: Long) = db.maintenanceDao().stampNotified(id, atEpochMs)
}
```

- [ ] **Step 3: Write the shared fakes** at `android/app/src/test/java/com/kompressorlink/app/data/FakeRepositories.kt` (used by every engine/ViewModel JVM test from Task 5 on):

```kotlin
package com.kompressorlink.app.data

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.OdometerEntryEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.data.db.WarningEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeSessionRepository : SessionRepository {
    val sessions = MutableStateFlow<List<SessionEntity>>(emptyList())
    val stats = MutableStateFlow<List<SessionStatEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun record(
        session: SessionEntity,
        stats: (sessionId: Long) -> List<SessionStatEntity>,
    ): Long {
        val id = nextId++
        sessions.value = sessions.value + session.copy(id = id)
        this.stats.value = this.stats.value + stats(id)
        return id
    }

    private fun filtered(sources: Set<SessionSource>, limit: Int) =
        sessions.value.filter { s -> sources.any { it.name == s.source } }
            .sortedByDescending { it.endedAtEpochMs }.take(limit)

    override suspend fun recent(sources: Set<SessionSource>, limit: Int) = filtered(sources, limit)

    override fun observeRecent(sources: Set<SessionSource>, limit: Int): Flow<List<SessionEntity>> =
        sessions.map { list ->
            list.filter { s -> sources.any { it.name == s.source } }
                .sortedByDescending { it.endedAtEpochMs }.take(limit)
        }

    override suspend fun statsForSessions(sessionIds: List<Long>) =
        stats.value.filter { it.sessionId in sessionIds }

    override suspend fun count(sources: Set<SessionSource>) = filtered(sources, Int.MAX_VALUE).size

    override suspend fun realDistanceSince(epochMs: Long): Float =
        sessions.value.filter { it.source == SessionSource.REAL_BLE.name && it.endedAtEpochMs > epochMs }
            .mapNotNull { it.distanceKm }.sum()
}

class FakeWarningRepository : WarningRepository {
    val warnings = MutableStateFlow<List<WarningEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun raise(candidate: WarningEntity) {
        val open = warnings.value.firstOrNull { !it.acknowledged && it.dedupeKey == candidate.dedupeKey }
        warnings.value = if (open == null) {
            warnings.value + candidate.copy(id = nextId++)
        } else {
            warnings.value.map {
                if (it.id == open.id) it.copy(
                    lastSeenAtEpochMs = candidate.lastSeenAtEpochMs,
                    detail = candidate.detail, level = candidate.level,
                ) else it
            }
        }
    }

    override fun observe(source: WarningSource, limit: Int): Flow<List<WarningEntity>> =
        warnings.map { list ->
            list.filter { it.source == source.name }
                .sortedWith(compareBy<WarningEntity> { it.acknowledged }.thenByDescending { it.lastSeenAtEpochMs })
                .take(limit)
        }

    override suspend fun open(source: WarningSource) =
        warnings.value.filter { it.source == source.name && !it.acknowledged }

    override suspend fun acknowledge(id: Long) {
        warnings.value = warnings.value.map { if (it.id == id) it.copy(acknowledged = true) else it }
    }
}

class FakeOdometerRepository : OdometerRepository {
    val anchors = MutableStateFlow<List<OdometerEntryEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun addAnchor(km: Int, atEpochMs: Long): Result<Unit> {
        val maxSoFar = anchors.value.maxOfOrNull { it.km }
        if (maxSoFar != null && km < maxSoFar) {
            return Result.failure(IllegalArgumentException("Odometer can't go backwards: last entered $maxSoFar km"))
        }
        anchors.value = anchors.value + OdometerEntryEntity(id = nextId++, epochMs = atEpochMs, km = km)
        return Result.success(Unit)
    }

    override suspend fun latestAnchor() = anchors.value.maxByOrNull { it.epochMs }

    override fun observeLatestAnchor(): Flow<OdometerEntryEntity?> =
        anchors.map { list -> list.maxByOrNull { it.epochMs } }
}

class FakeMaintenanceRepository : MaintenanceRepository {
    val items = MutableStateFlow<List<MaintenanceItemEntity>>(emptyList())
    val logs = MutableStateFlow<List<ServiceLogEntity>>(emptyList())
    private var nextItemId = 1L
    private var nextLogId = 1L

    fun seedWith(list: List<MaintenanceItemEntity>) {
        items.value = list.map { it.copy(id = nextItemId++) }
    }

    override suspend fun ensureSeeded() {
        if (items.value.none { it.builtin }) {
            seedWith(com.kompressorlink.app.maintenance.BuiltinSchedule.ITEMS)
        }
    }

    override fun observeItems(): Flow<List<MaintenanceItemEntity>> = items
    override suspend fun items() = items.value
    override suspend fun itemById(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun latestLogFor(itemId: Long) =
        logs.value.filter { it.itemId == itemId }.maxByOrNull { it.epochMs }

    override fun observeLogsFor(itemId: Long): Flow<List<ServiceLogEntity>> =
        logs.map { list -> list.filter { it.itemId == itemId }.sortedByDescending { it.epochMs } }

    override suspend fun logService(itemId: Long, epochMs: Long, km: Int?, note: String?) {
        logs.value = logs.value + ServiceLogEntity(id = nextLogId++, itemId = itemId, epochMs = epochMs, km = km, note = note)
        items.value = items.value.map { if (it.id == itemId) it.copy(lastNotifiedAtEpochMs = null) else it }
    }

    override suspend fun updateItem(item: MaintenanceItemEntity): Result<Unit> {
        if (item.intervalKm == null && item.intervalMonths == null) {
            return Result.failure(IllegalArgumentException("Set a km interval, a month interval, or both"))
        }
        items.value = items.value.map { if (it.id == item.id) item else it }
        return Result.success(Unit)
    }

    override suspend fun addCustomItem(item: MaintenanceItemEntity): Result<Long> {
        if (item.intervalKm == null && item.intervalMonths == null) {
            return Result.failure(IllegalArgumentException("Set a km interval, a month interval, or both"))
        }
        val id = nextItemId++
        items.value = items.value + item.copy(id = id, builtin = false)
        return Result.success(id)
    }

    override suspend fun deleteCustomItem(id: Long) {
        items.value = items.value.filterNot { it.id == id && !it.builtin }
        logs.value = logs.value.filterNot { it.itemId == id }
    }

    override suspend fun stampNotified(id: Long, atEpochMs: Long) {
        items.value = items.value.map { if (it.id == id) it.copy(lastNotifiedAtEpochMs = atEpochMs) else it }
    }
}
```

- [ ] **Step 4: Write the failing Robolectric test** at `android/app/src/test/java/com/kompressorlink/app/data/RoomRepositoriesTest.kt`:

```kotlin
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
```

Note the monotonic-rejection message check: `"Odometer can't go backwards: last entered 186900 km"` — Kotlin's `$maxSoFar` interpolation renders `186900` without a thousands separator; the test matches that exactly.

- [ ] **Step 5: Run it** — FAIL before Steps 1–2 exist, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.data.RoomRepositoriesTest"
```
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 6: Full suite green.** Leave for review.

---
## Task 5: HealthTuning, contexts, streaming stats, SessionAggregator

Pure Kotlin, no `android.*` imports anywhere in this task (spec §1 purity rule).

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/HealthTuning.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/health/SnapshotContexts.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/health/StreamingStats.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/health/SessionAggregator.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/StreamingStatsTest.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/SessionAggregatorTest.kt`

- [ ] **Step 1: Write `health/HealthTuning.kt`** — the single home of every tunable (spec §5.6):

```kotlin
package com.kompressorlink.app.health

// Every tunable threshold in the health/maintenance stack lives HERE and
// nowhere else. All values [Best estimate] defaults per spec §5.6 — tune in
// one place; tests reference these constants, never magic numbers.
object HealthTuning {
    // Session recording (spec §3)
    const val SESSION_CLOSE_GAP_MS = 30_000L
    const val SESSION_MIN_DURATION_MS = 60_000L
    const val SESSION_MIN_SNAPSHOTS = 30
    const val DISTANCE_MAX_GAP_MS = 5_000L

    // Engine-state context (spec §3/§4)
    const val ENGINE_RUNNING_MIN_RPM = 400f

    // UI hysteresis (spec §4)
    const val ESCALATE_MS = 3_000L
    const val DEESCALATE_MS = 10_000L

    // Metric extraction (spec §5.1)
    const val CONTEXT_MIN_SAMPLES = 30

    // Personal baseline (spec §5.2)
    const val BASELINE_WINDOW = 20
    const val BASELINE_MAD_K = 3f
    const val BASELINE_MIN_SESSIONS = 8
    const val BASELINE_MIN_SPAN_DAYS = 14
    const val BASELINE_DEGENERATE_MAD_FRACTION = 0.01f   // MAD < 1% of band width…
    const val BASELINE_DEGENERATE_WIDEN_FRACTION = 0.05f // …widen to ±5% of band width

    // Drift (spec §5.3)
    const val DRIFT_WINDOW = 20
    const val DRIFT_MIN_POINTS = 8
    const val DRIFT_MIN_SPAN_DAYS = 14
    const val DRIFT_MIN_WEEKS = 1f
    const val DRIFT_MAX_WEEKS = 26f

    // Live warnings (spec §5.5)
    const val AMBER_WARN_DWELL_MS = 60_000L

    // Maintenance (spec §6.3/§6.4)
    const val DUE_SOON_KM = 500
    const val DUE_SOON_DAYS = 30L
    const val RENOTIFY_DAYS = 7L
}
```

- [ ] **Step 2: Write `health/SnapshotContexts.kt`:**

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot

object SnapshotContexts {
    /**
     * true = engine running (RPM >= 400), false = engine off (RPM present
     * but below), null = RPM unavailable — never guess engine state from
     * missing data (Untested contract, spec §3).
     */
    fun engineRunning(s: TelemetrySnapshot): Boolean? =
        s.value(Signal.RPM)?.let { it >= HealthTuning.ENGINE_RUNNING_MIN_RPM }
}
```

- [ ] **Step 3: Write `health/StreamingStats.kt`** (Welford — no per-sample storage, spec §3):

```kotlin
package com.kompressorlink.app.health

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Welford's online algorithm: numerically stable mean/stddev in O(1) memory.
class StreamingStats {
    var count: Int = 0
        private set
    var min: Float = Float.POSITIVE_INFINITY
        private set
    var max: Float = Float.NEGATIVE_INFINITY
        private set
    private var mean = 0.0
    private var m2 = 0.0

    fun add(v: Float) {
        count++
        min = min(min, v)
        max = max(max, v)
        val delta = v - mean
        mean += delta / count
        m2 += delta * (v - mean)
    }

    val meanValue: Float get() = if (count == 0) 0f else mean.toFloat()

    /** Population stddev; 0 for fewer than 2 samples. */
    val stdDev: Float get() = if (count < 2) 0f else sqrt(m2 / count).toFloat()
}
```

- [ ] **Step 4: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/health/StreamingStatsTest.kt`:

```kotlin
package com.kompressorlink.app.health

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingStatsTest {
    @Test
    fun matchesKnownValues() {
        val s = StreamingStats()
        listOf(3f, 4f, 5f).forEach(s::add)
        assertEquals(3, s.count)
        assertEquals(4f, s.meanValue, 1e-5f)
        assertEquals(3f, s.min, 0f)
        assertEquals(5f, s.max, 0f)
        // population stddev of [3,4,5] = sqrt(2/3)
        assertEquals(0.8165f, s.stdDev, 1e-3f)
    }

    @Test
    fun emptyAndSingleAreSafe() {
        val s = StreamingStats()
        assertEquals(0f, s.meanValue, 0f)
        assertEquals(0f, s.stdDev, 0f)
        s.add(7f)
        assertEquals(7f, s.meanValue, 0f)
        assertEquals(0f, s.stdDev, 0f)
    }
}
```

- [ ] **Step 5: Write `health/SessionAggregator.kt`:**

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.DashboardLogic
import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot

// Streaming per-session aggregation (spec §3): Welford stats per signal,
// contextual means, RAW out-of-band seconds (pre-hysteresis — stats record
// what the car did; hysteresis smooths UI only), and REAL-only distance
// integration with the 5 s gap guard.
class SessionAggregator(
    private val refs: ReferenceRepository,
    private val isReal: Boolean,
) {
    private val overall = LinkedHashMap<Signal, StreamingStats>()
    private val warmIdle = HashMap<Signal, StreamingStats>()
    private val battRunning = StreamingStats()
    private val battOff = StreamingStats()
    private val outOfBandSec = HashMap<Signal, Float>()
    private val worst = HashMap<Signal, GaugeLevel>()

    var snapshotCount = 0
        private set
    var warmIdleSeconds = 0f
        private set
    var hasStoredDtc = false
        private set

    private var distance = 0f
    private var speedSamples = 0
    private var prevAtMs: Long? = null
    private var prevSpeedAvailable = false

    fun onDtcReport(report: DtcReport?) {
        if (report != null && report.stored.isNotEmpty()) hasStoredDtc = true
    }

    fun add(snapshot: TelemetrySnapshot, atMs: Long) {
        val dtMs = prevAtMs?.let { atMs - it } ?: 0L
        val dtSec = dtMs / 1000f
        snapshotCount++

        for (signal in Signal.entries) {
            val v = snapshot.value(signal) ?: continue
            overall.getOrPut(signal) { StreamingStats() }.add(v)
            val band = DashboardLogic.applicableBand(signal, snapshot, refs) ?: continue
            val level = DashboardLogic.levelFor(v, band)
            if (level != GaugeLevel.OK) {
                outOfBandSec[signal] = (outOfBandSec[signal] ?: 0f) + dtSec
            }
            if (rank(level) > rank(worst[signal] ?: GaugeLevel.OK)) worst[signal] = level
        }

        if (DashboardLogic.isWarmIdle(snapshot)) {
            warmIdleSeconds += dtSec
            for (signal in WARM_IDLE_SIGNALS) {
                snapshot.value(signal)?.let { warmIdle.getOrPut(signal) { StreamingStats() }.add(it) }
            }
        }

        when (SnapshotContexts.engineRunning(snapshot)) {
            true -> snapshot.value(Signal.BATT_V_ADC)?.let(battRunning::add)
            false -> snapshot.value(Signal.BATT_V_ADC)?.let(battOff::add)
            null -> Unit  // RPM unavailable: accumulate neither context
        }

        val speed = snapshot.value(Signal.SPEED)
        if (isReal && speed != null && prevSpeedAvailable &&
            dtMs in 1..HealthTuning.DISTANCE_MAX_GAP_MS
        ) {
            distance += speed * dtMs / 3_600_000f  // km/h * ms -> km
        }
        if (speed != null) speedSamples++
        prevSpeedAvailable = speed != null
        prevAtMs = atMs
    }

    /** Non-null only for real sessions that ever saw SPEED (spec §2/§3). */
    val distanceKm: Float?
        get() = if (isReal && speedSamples > 0) distance else null

    fun buildStats(sessionId: Long): List<SessionStatEntity> =
        overall.map { (signal, stats) ->
            val wi = warmIdle[signal]
            val running = if (signal == Signal.BATT_V_ADC) battRunning else null
            val off = if (signal == Signal.BATT_V_ADC) battOff else null
            SessionStatEntity(
                sessionId = sessionId,
                signal = signal.name,
                sampleCount = stats.count,
                mean = stats.meanValue,
                min = stats.min,
                max = stats.max,
                stdDev = stats.stdDev,
                secondsOutOfBand = outOfBandSec[signal] ?: 0f,
                worstLevel = (worst[signal] ?: GaugeLevel.OK).name,
                warmIdleMean = wi?.takeIf { it.count > 0 }?.meanValue,
                warmIdleCount = wi?.count ?: 0,
                engineRunningMean = running?.takeIf { it.count > 0 }?.meanValue,
                engineRunningCount = running?.count ?: 0,
                engineOffMean = off?.takeIf { it.count > 0 }?.meanValue,
                engineOffCount = off?.count ?: 0,
            )
        }

    private fun rank(level: GaugeLevel): Int = when (level) {
        GaugeLevel.RED -> 2
        GaugeLevel.AMBER -> 1
        else -> 0
    }

    companion object {
        // Signals with warm_idle contextual means in session_stats (spec §2).
        val WARM_IDLE_SIGNALS = setOf(Signal.MAF_GS, Signal.STFT1, Signal.LTFT1, Signal.ECT)
    }
}
```

- [ ] **Step 6: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/health/SessionAggregatorTest.kt`:

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAggregatorTest {

    // Same asset-loading trick as ReferenceRepositoryTest: JVM tests read
    // the real assets straight off the filesystem.
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

    // Warm idle per DashboardLogic.isWarmIdle: RPM 600-900, SPEED 0, ECT > 80.
    private fun warmIdleSnap(ltft: Float, maf: Float = 4f) = snap(
        Signal.RPM to 750f, Signal.SPEED to 0f, Signal.ECT to 90f,
        Signal.LTFT1 to ltft, Signal.MAF_GS to maf,
    )

    @Test
    fun welfordStats_andWarmIdleMean() {
        val agg = SessionAggregator(refs, isReal = true)
        var t = 0L
        listOf(3f, 4f, 5f).forEach { maf ->
            agg.add(warmIdleSnap(ltft = 4f, maf = maf), t)
            t += 500
        }
        val stats = agg.buildStats(sessionId = 1).first { it.signal == "MAF_GS" }
        assertEquals(3, stats.sampleCount)
        assertEquals(4f, stats.mean, 1e-5f)
        assertEquals(4f, stats.warmIdleMean!!, 1e-5f)
        assertEquals(3, stats.warmIdleCount)
        assertEquals("OK", stats.worstLevel)
        assertEquals(1f, agg.warmIdleSeconds, 1e-3f)  // 2 gaps x 0.5 s
    }

    @Test
    fun outOfBandSeconds_useRawBands_andWorstLevel() {
        val agg = SessionAggregator(refs, isReal = true)
        var t = 0L
        // LTFT band is ±10 always; halfWidth 10 => 15 is AMBER (RED beyond 20).
        repeat(10) {
            agg.add(warmIdleSnap(ltft = 15f), t)
            t += 500
        }
        val ltft = agg.buildStats(1).first { it.signal == "LTFT1" }
        assertEquals("AMBER", ltft.worstLevel)
        assertEquals(4.5f, ltft.secondsOutOfBand, 1e-3f)  // 9 gaps x 0.5 s (first dt = 0)
    }

    @Test
    fun batteryContexts_splitByEngineState_andUnavailableRpmAccumulatesNeither() {
        val agg = SessionAggregator(refs, isReal = true)
        agg.add(snap(Signal.RPM to 750f, Signal.BATT_V_ADC to 14.2f), 0)      // running
        agg.add(snap(Signal.RPM to 0f, Signal.BATT_V_ADC to 12.6f), 500)      // off
        agg.add(snap(Signal.BATT_V_ADC to 13.0f), 1_000)                      // RPM masked
        val batt = agg.buildStats(1).first { it.signal == "BATT_V_ADC" }
        assertEquals(3, batt.sampleCount)
        assertEquals(1, batt.engineRunningCount)
        assertEquals(14.2f, batt.engineRunningMean!!, 1e-4f)
        assertEquals(1, batt.engineOffCount)
        assertEquals(12.6f, batt.engineOffMean!!, 1e-4f)
    }

    @Test
    fun distance_integratesRealSpeed_withGapGuard() {
        val agg = SessionAggregator(refs, isReal = true)
        var t = 0L
        // 61 samples at 60 km/h, 1 s apart -> 60 gaps x (60/3600) km = 1.0 km
        repeat(61) {
            agg.add(snap(Signal.SPEED to 60f, Signal.RPM to 2000f), t)
            t += 1_000
        }
        // one 10 s gap: contributes nothing (gap guard)
        t += 9_000
        agg.add(snap(Signal.SPEED to 60f, Signal.RPM to 2000f), t)
        assertEquals(1.0f, agg.distanceKm!!, 1e-3f)
    }

    @Test
    fun simSession_neverReportsDistance() {
        val agg = SessionAggregator(refs, isReal = false)
        agg.add(snap(Signal.SPEED to 60f, Signal.RPM to 2000f), 0)
        agg.add(snap(Signal.SPEED to 60f, Signal.RPM to 2000f), 1_000)
        assertNull(agg.distanceKm)
    }

    @Test
    fun maskedSignal_getsNoStatsRow() {
        val agg = SessionAggregator(refs, isReal = true)
        agg.add(snap(Signal.RPM to 750f), 0)
        assertTrue(agg.buildStats(1).none { it.signal == "LTFT1" })
    }

    @Test
    fun dtcLatch_sticksOnceStoredCodeSeen() {
        val agg = SessionAggregator(refs, isReal = true)
        agg.onDtcReport(DtcReport(stored = emptyList(), pending = listOf("P1570")))
        assertEquals(false, agg.hasStoredDtc)
        agg.onDtcReport(DtcReport(stored = listOf("P0171"), pending = emptyList()))
        agg.onDtcReport(null)
        assertEquals(true, agg.hasStoredDtc)
    }
}
```

- [ ] **Step 7: Run both suites** — FAIL before implementations, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.health.*"
```
Expected: `BUILD SUCCESSFUL`, 9 tests pass.

- [ ] **Step 8: Full suite green.** Leave for review.

---

## Task 6: SessionRecorder

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/SessionRecorder.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/SessionRecorderTest.kt`

- [ ] **Step 1: Write `health/SessionRecorder.kt`:**

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.data.SessionRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Turns the active TelemetrySource's streams into persisted session rows
// (spec §3). Close triggers: SESSION_CLOSE_GAP_MS without any event, a
// snapshot arriving after a gap, or a source switch. Persist gate:
// >= SESSION_MIN_DURATION_MS AND >= SESSION_MIN_SNAPSHOTS, else discarded.
// endedAt = last snapshot's receive time — a disconnect can't inflate
// duration. Time is injected (`now`) so tests run on virtual time.
class SessionRecorder(
    private val scope: CoroutineScope,
    private val source: TelemetrySource,
    private val choice: Flow<SourceChoice>,
    private val sessions: SessionRepository,
    private val refs: ReferenceRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val onSessionClosed: suspend (sessionId: Long, source: SessionSource) -> Unit = { _, _ -> },
) {
    private sealed interface Event {
        data class Snap(val snapshot: TelemetrySnapshot, val atMs: Long) : Event
        data class Dtc(val report: DtcReport?) : Event
        data class Switched(val to: SessionSource) : Event
    }

    private class OpenSession(val source: SessionSource, val startedAtMs: Long, isReal: Boolean, refs: ReferenceRepository) {
        val aggregator = SessionAggregator(refs, isReal)
        var lastAtMs: Long = startedAtMs
    }

    fun start(): Job = scope.launch {
        val events = Channel<Event>(Channel.BUFFERED)
        launch { source.telemetry.collect { events.send(Event.Snap(it, now())) } }
        launch { source.dtcReport.collect { events.send(Event.Dtc(it)) } }
        launch { choice.collect { events.send(Event.Switched(SessionSource.from(it))) } }

        var current: SessionSource? = null
        var open: OpenSession? = null

        suspend fun close() {
            val o = open ?: return
            open = null
            val duration = o.lastAtMs - o.startedAtMs
            if (duration < HealthTuning.SESSION_MIN_DURATION_MS ||
                o.aggregator.snapshotCount < HealthTuning.SESSION_MIN_SNAPSHOTS
            ) return  // blip — discarded, never pollutes history (spec §3)
            val id = sessions.record(
                SessionEntity(
                    startedAtEpochMs = o.startedAtMs,
                    endedAtEpochMs = o.lastAtMs,
                    source = o.source.name,
                    snapshotCount = o.aggregator.snapshotCount,
                    warmIdleSeconds = o.aggregator.warmIdleSeconds,
                    distanceKm = o.aggregator.distanceKm,
                    hasStoredDtc = o.aggregator.hasStoredDtc,
                )
            ) { sessionId -> o.aggregator.buildStats(sessionId) }
            onSessionClosed(id, o.source)
        }

        while (isActive) {
            val event = withTimeoutOrNull(HealthTuning.SESSION_CLOSE_GAP_MS) { events.receive() }
            when (event) {
                null -> close()  // nothing at all for the gap window
                is Event.Switched -> {
                    if (current != event.to) {
                        close()
                        current = event.to
                    }
                }
                is Event.Dtc -> open?.aggregator?.onDtcReport(event.report)
                is Event.Snap -> {
                    val src = current
                    if (src == null) {
                        // No SourceChoice emission seen yet — DataStore emits
                        // promptly on collect, so this is a startup race of a
                        // few ms at most; dropping the frame is harmless.
                        continue
                    }
                    val o = open
                    if (o != null && event.atMs - o.lastAtMs > HealthTuning.SESSION_CLOSE_GAP_MS) {
                        close()  // stale session: gap detected on arrival
                    }
                    val target = open ?: OpenSession(src, event.atMs, src.isReal, refs).also { open = it }
                    target.aggregator.add(event.snapshot, event.atMs)
                    target.lastAtMs = event.atMs
                }
            }
        }
    }
}
```

- [ ] **Step 2: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/health/SessionRecorderTest.kt`:

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.data.FakeSessionRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.FakeScenario
import com.kompressorlink.app.telemetry.FakeTelemetrySource
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecorderTest {

    private val refs = ReferenceRepository { name -> File("src/main/assets/$name").readText() }

    private class ScriptedSource : TelemetrySource {
        val telemetryFlow = MutableSharedFlow<TelemetrySnapshot>()
        val dtcFlow = MutableStateFlow<DtcReport?>(null)
        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Simulated("scripted"))
        override val telemetry: Flow<TelemetrySnapshot> = telemetryFlow
        override val dtcReport: StateFlow<DtcReport?> = dtcFlow
        override suspend fun sendTimeSync() {}
    }

    private fun speedSnap(speedKmh: Float) : TelemetrySnapshot {
        val v = FloatArray(SIGNAL_COUNT)
        var mask = 0
        listOf(Signal.RPM to 2000f, Signal.SPEED to speedKmh).forEach { (s, value) ->
            v[s.ordinal] = value
            mask = mask or (1 shl s.ordinal)
        }
        return TelemetrySnapshot(v, mask, flags = 0, seq = 0, uptimeMs = 0)
    }

    @Test
    fun realSession_persistsAfterGap_withStatsAndDistance() = runTest {
        val repo = FakeSessionRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        var closedSource: SessionSource? = null
        SessionRecorder(
            backgroundScope, src, choice, repo, refs,
            now = { testScheduler.currentTime },  // see note below
            onSessionClosed = { _, s -> closedSource = s },
        ).start()
        runCurrent()

        repeat(150) {  // 150 snaps, 500 ms apart = 74.5 s span
            src.telemetryFlow.emit(speedSnap(60f))
            advanceTimeBy(500)
        }
        advanceTimeBy(HealthTuning.SESSION_CLOSE_GAP_MS + 1_000)
        runCurrent()

        assertEquals(1, repo.sessions.value.size)
        val session = repo.sessions.value[0]
        assertEquals("REAL_BLE", session.source)
        assertEquals(150, session.snapshotCount)
        assertTrue(session.distanceKm!! > 1.0f)   // ~1.24 km at 60 km/h over 74.5 s
        assertTrue(repo.stats.value.any { it.signal == "SPEED" })
        assertEquals(SessionSource.REAL_BLE, closedSource)
    }

    @Test
    fun shortBlip_isDiscarded() = runTest {
        val repo = FakeSessionRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        SessionRecorder(backgroundScope, src, choice, repo, refs,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        repeat(20) {  // 9.5 s, 20 snaps — under both gates
            src.telemetryFlow.emit(speedSnap(0f))
            advanceTimeBy(500)
        }
        advanceTimeBy(HealthTuning.SESSION_CLOSE_GAP_MS + 1_000)
        runCurrent()
        assertEquals(0, repo.sessions.value.size)
    }

    @Test
    fun sourceSwitch_closesAndRetags() = runTest {
        val repo = FakeSessionRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        SessionRecorder(backgroundScope, src, choice, repo, refs,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        repeat(150) {
            src.telemetryFlow.emit(speedSnap(60f))
            advanceTimeBy(500)
        }
        choice.value = SourceChoice.SIMULATED_HEALTHY   // switch closes the REAL session
        runCurrent()
        assertEquals(1, repo.sessions.value.size)
        assertEquals("REAL_BLE", repo.sessions.value[0].source)

        repeat(150) {  // second session under the sim tag
            src.telemetryFlow.emit(FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, (it * 500).toLong(), it))
            advanceTimeBy(500)
        }
        advanceTimeBy(HealthTuning.SESSION_CLOSE_GAP_MS + 1_000)
        runCurrent()
        assertEquals(2, repo.sessions.value.size)
        val sim = repo.sessions.value[1]
        assertEquals("SIM_HEALTHY", sim.source)
        assertNull(sim.distanceKm)  // quarantine: sim never reports distance
    }

    @Test
    fun dtcReport_latchesIntoSession() = runTest {
        val repo = FakeSessionRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        SessionRecorder(backgroundScope, src, choice, repo, refs,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        repeat(75) {
            src.telemetryFlow.emit(speedSnap(60f))
            advanceTimeBy(500)
        }
        src.dtcFlow.value = DtcReport(stored = listOf("P0171"), pending = emptyList())
        runCurrent()
        repeat(75) {
            src.telemetryFlow.emit(speedSnap(60f))
            advanceTimeBy(500)
        }
        advanceTimeBy(HealthTuning.SESSION_CLOSE_GAP_MS + 1_000)
        runCurrent()
        assertEquals(true, repo.sessions.value[0].hasStoredDtc)
    }
}
```


- [ ] **Step 3: Run it** — FAIL before Step 1, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.health.SessionRecorderTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 4: Full suite green.** Leave for review.

---

## Task 7: Wire the data layer into the app container

**Files:**
- Modify: `android/app/src/main/java/com/kompressorlink/app/KompressorLinkApp.kt`

- [ ] **Step 1: Full replacement of `KompressorLinkApp.kt`:**

```kotlin
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
import com.kompressorlink.app.health.SessionRecorder
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.FakeScenario
import com.kompressorlink.app.telemetry.FakeTelemetrySource
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.SourceChoiceStore
import com.kompressorlink.app.telemetry.SourceSwitcher
import com.kompressorlink.app.telemetry.ble.BleSession
import com.kompressorlink.app.telemetry.ble.GattClient
import java.io.IOException
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

    val sessionRecorder = SessionRecorder(
        scope = scope,
        source = telemetrySource,
        choice = choiceStore.choice,
        sessions = sessionRepository,
        refs = referenceRepository,
    )

    init {
        scope.launch { maintenanceRepository.ensureSeeded() }
        sessionRecorder.start()
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
```

- [ ] **Step 2: Full suite + APK:**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
Expected: both `BUILD SUCCESSFUL` — the container compiles, no existing test regresses (nothing constructs `AppContainer` in unit tests).

- [ ] **Step 3: Leave for review.**

---
## Task 8: Band contexts v2 — engine_running / engine_off (the battery fix)

**Files:**
- Modify: `android/app/src/main/assets/w203_bands.json`
- Modify: `android/app/src/main/java/com/kompressorlink/app/dashboard/DashboardLogic.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/dashboard/BandContextTest.kt` (new)
- Possibly touch: `android/app/src/test/java/com/kompressorlink/app/reference/ReferenceRepositoryTest.kt` (see Step 4)

- [ ] **Step 1: Full replacement of `android/app/src/main/assets/w203_bands.json`** — battery's old `always` row becomes `engine_running`, and a new `engine_off` row lands (spec §4):

```json
[
  {
    "signal": "MAF_GS", "context": "warm_idle", "lo": 3.0, "hi": 5.0,
    "unit": "g/s", "confidence": "Confirmed",
    "hint": "Warm-idle MAF outside 3–5 g/s: check intake/breather leaks first (the classic M271 fault)"
  },
  {
    "signal": "STFT1", "context": "always", "lo": -10.0, "hi": 10.0,
    "unit": "%", "confidence": "Confirmed",
    "hint": "Short-term trim beyond ±10%: active mixture correction — watch LTFT"
  },
  {
    "signal": "LTFT1", "context": "always", "lo": -10.0, "hi": 10.0,
    "unit": "%", "confidence": "Confirmed",
    "hint": "LTFT high at warm idle: classic M271 partial-load breather-hose leak — see guide §2 tree"
  },
  {
    "signal": "ECT", "context": "warm_idle", "lo": 87.0, "hi": 95.0,
    "unit": "°C", "confidence": "Best estimate",
    "hint": "Warm idle should settle 87–95 °C (thermostat opens at 87 °C)"
  },
  {
    "signal": "BATT_V_ADC", "context": "engine_running", "lo": 13.8, "hi": 14.5,
    "unit": "V", "confidence": "Confirmed",
    "hint": "Engine-running voltage outside 13.8–14.5 V: charging-system check"
  },
  {
    "signal": "BATT_V_ADC", "context": "engine_off", "lo": 12.5, "hi": 13.2,
    "unit": "V", "confidence": "Best estimate",
    "hint": "Resting below 12.5 V suggests a weak or draining battery"
  }
]
```

- [ ] **Step 2: Full replacement of `dashboard/DashboardLogic.kt`** — identical to today's file except `applicableBand` gains the two engine contexts via a new `contextHolds`; everything else byte-for-byte the same:

```kotlin
package com.kompressorlink.app.dashboard

import com.kompressorlink.app.health.SnapshotContexts
import com.kompressorlink.app.reference.Band
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.util.Locale

enum class GaugeLevel { NEUTRAL, OK, AMBER, RED, UNAVAILABLE }

data class GaugeUiState(
    val signal: Signal,
    val title: String,
    val valueText: String,   // "—" when unavailable
    val unit: String,
    val level: GaugeLevel,
    val bandText: String?,   // e.g. "3–5 g/s", null when no band applies
    val hint: String?,       // reference hint, only when AMBER/RED
    val history: List<Float>,
)

// Pure functions — everything the DashboardLogicTest covers lives here,
// framework-free.
object DashboardLogic {

    // Spec §4.5's gauge list, in display order.
    val DASHBOARD_SIGNALS = listOf(
        Signal.RPM, Signal.MAF_GS, Signal.STFT1, Signal.LTFT1, Signal.ECT,
        Signal.MAP, Signal.O2_B1S1_V, Signal.O2_B1S2_V, Signal.BATT_V_ADC,
        Signal.TIMING_ADV,
    )

    private val TITLES = mapOf(
        Signal.RPM to "RPM", Signal.MAF_GS to "MAF", Signal.STFT1 to "STFT",
        Signal.LTFT1 to "LTFT", Signal.ECT to "Coolant", Signal.MAP to "MAP",
        Signal.O2_B1S1_V to "O2 pre-cat", Signal.O2_B1S2_V to "O2 post-cat",
        Signal.BATT_V_ADC to "Battery", Signal.TIMING_ADV to "Timing",
    )

    private val UNITS = mapOf(
        Signal.RPM to "rpm", Signal.MAF_GS to "g/s", Signal.STFT1 to "%",
        Signal.LTFT1 to "%", Signal.ECT to "°C", Signal.MAP to "kPa",
        Signal.O2_B1S1_V to "V", Signal.O2_B1S2_V to "V",
        Signal.BATT_V_ADC to "V", Signal.TIMING_ADV to "°",
    )

    /** PLAN.md §7's warm-idle definition, computed from the snapshot itself.
     *  Any gating signal being unavailable means "not warm idle" — never
     *  guess a context from missing data (Untested contract). */
    fun isWarmIdle(s: TelemetrySnapshot): Boolean {
        val rpm = s.value(Signal.RPM) ?: return false
        val speed = s.value(Signal.SPEED) ?: return false
        val ect = s.value(Signal.ECT) ?: return false
        return rpm in 600f..900f && speed == 0f && ect > 80f
    }

    /** Plan-fixed thresholds: in-band OK; ≤ half-width beyond AMBER; else RED. */
    fun levelFor(value: Float, band: Band): GaugeLevel {
        val halfWidth = (band.hi - band.lo) / 2f
        return when {
            value >= band.lo && value <= band.hi -> GaugeLevel.OK
            value < band.lo - halfWidth || value > band.hi + halfWidth -> GaugeLevel.RED
            else -> GaugeLevel.AMBER
        }
    }

    /** Spec §4 (Phase 4.5): band contexts. `always` and `warm_idle` as in
     *  Phase 4; `engine_running`/`engine_off` gate on RPM ≥ 400 with
     *  RPM-unavailable meaning NEITHER applies (never guess engine state
     *  from missing data). Unknown context strings fail closed. */
    fun contextHolds(context: String, snapshot: TelemetrySnapshot): Boolean = when (context) {
        "always" -> true
        "warm_idle" -> isWarmIdle(snapshot)
        "engine_running" -> SnapshotContexts.engineRunning(snapshot) == true
        "engine_off" -> SnapshotContexts.engineRunning(snapshot) == false
        else -> false
    }

    fun applicableBand(signal: Signal, snapshot: TelemetrySnapshot, refs: ReferenceRepository): Band? =
        refs.bandsFor(signal).firstOrNull { band -> contextHolds(band.context, snapshot) }

    fun gaugeFor(
        signal: Signal,
        snapshot: TelemetrySnapshot,
        refs: ReferenceRepository,
        history: List<Float>,
    ): GaugeUiState {
        val title = TITLES.getValue(signal)
        val unit = UNITS.getValue(signal)
        val value = snapshot.value(signal)
            ?: return GaugeUiState(signal, title, "—", unit, GaugeLevel.UNAVAILABLE,
                                   bandText = null, hint = null, history = emptyList())
        val band = applicableBand(signal, snapshot, refs)
        val level = band?.let { levelFor(value, it) } ?: GaugeLevel.NEUTRAL
        val decimals = if (signal == Signal.O2_B1S1_V || signal == Signal.O2_B1S2_V) 2 else 1
        return GaugeUiState(
            signal = signal,
            title = title,
            valueText = String.format(Locale.US, "%.${decimals}f", value),
            unit = unit,
            level = level,
            bandText = band?.let {
                String.format(Locale.US, "%.1f–%.1f %s (%s)", it.lo, it.hi, it.unit, it.confidence)
            },
            hint = if (level == GaugeLevel.AMBER || level == GaugeLevel.RED) band?.hint else null,
            history = history,
        )
    }
}
```

- [ ] **Step 3: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/dashboard/BandContextTest.kt`:

```kotlin
package com.kompressorlink.app.dashboard

import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandContextTest {

    private val refs = ReferenceRepository { name -> File("src/main/assets/$name").readText() }

    private fun snap(vararg pairs: Pair<Signal, Float>): TelemetrySnapshot {
        val v = FloatArray(SIGNAL_COUNT)
        var mask = 0
        pairs.forEach { (s, value) ->
            v[s.ordinal] = value
            mask = mask or (1 shl s.ordinal)
        }
        return TelemetrySnapshot(v, mask, flags = 0, seq = 0, uptimeMs = 0)
    }

    @Test
    fun battery_chargingBand_whenEngineRunning() {
        val s = snap(Signal.RPM to 2000f, Signal.BATT_V_ADC to 14.2f)
        val band = DashboardLogic.applicableBand(Signal.BATT_V_ADC, s, refs)!!
        assertEquals("engine_running", band.context)
        assertEquals(GaugeLevel.OK, DashboardLogic.levelFor(14.2f, band))
    }

    @Test
    fun battery_restingBand_whenEngineOff_healthyRestIsOk() {
        // The audit bug this fixes: a healthy parked car at 12.6 V used to
        // show RED against the charging band.
        val s = snap(Signal.RPM to 0f, Signal.BATT_V_ADC to 12.6f)
        val band = DashboardLogic.applicableBand(Signal.BATT_V_ADC, s, refs)!!
        assertEquals("engine_off", band.context)
        assertEquals(GaugeLevel.OK, DashboardLogic.levelFor(12.6f, band))
    }

    @Test
    fun battery_noBand_whenRpmUnavailable_rendersNeutral() {
        val s = snap(Signal.BATT_V_ADC to 12.6f)  // RPM masked out
        assertNull(DashboardLogic.applicableBand(Signal.BATT_V_ADC, s, refs))
        val gauge = DashboardLogic.gaugeFor(Signal.BATT_V_ADC, s, refs, emptyList())
        assertEquals(GaugeLevel.NEUTRAL, gauge.level)
    }

    @Test
    fun unknownContext_failsClosed() {
        val custom = ReferenceRepository { name ->
            when (name) {
                "w203_bands.json" -> """[
                    {"signal":"RPM","context":"moon_phase","lo":0.0,"hi":1.0,
                     "unit":"rpm","confidence":"Guessing","hint":"nope"}
                ]"""
                else -> File("src/main/assets/$name").readText()
            }
        }
        val s = snap(Signal.RPM to 750f)
        assertNull(DashboardLogic.applicableBand(Signal.RPM, s, custom))
    }

    @Test
    fun bandsJson_hasSixEntries_batteryHasBothContexts() {
        assertEquals(6, refs.bands.size)
        val battery = refs.bandsFor(Signal.BATT_V_ADC)
        assertEquals(setOf("engine_running", "engine_off"), battery.map { it.context }.toSet())
    }
}
```

- [ ] **Step 4: Run the FULL suite, not just the new file:**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```
Expected: `BandContextTest` passes (5 tests). **Known possible casualty:** if the existing `ReferenceRepositoryTest` or `DashboardLogicTest` asserts the old band count (5) or asserts the battery band applies without RPM context, update those specific assertions to the new truth (6 bands; battery band requires an engine-state context). Change nothing else in those files.

- [ ] **Step 5: Leave for review.**

---

## Task 9: HysteresisFilter — no more color flicker

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/HysteresisFilter.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/HysteresisFilterTest.kt`

- [ ] **Step 1: Write `health/HysteresisFilter.kt`:**

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel

// UI/alerting smoothing (spec §4): escalation to a worse level requires the
// raw level to hold CONTINUOUSLY for ESCALATE_MS; de-escalation to a better
// level requires DEESCALATE_MS. A raw change to a different pending level
// restarts the timer (strict continuity). NEUTRAL/UNAVAILABLE pass through
// immediately and reset pending state — absence of data is never smoothed
// into a verdict. Raw session stats (SessionAggregator) bypass this class.
class HysteresisFilter(
    private val escalateMs: Long = HealthTuning.ESCALATE_MS,
    private val deescalateMs: Long = HealthTuning.DEESCALATE_MS,
) {
    private var displayed: GaugeLevel? = null
    private var pending: GaugeLevel? = null
    private var pendingSinceMs = 0L

    fun update(raw: GaugeLevel, nowMs: Long): GaugeLevel {
        val current = displayed
        val bypass = raw == GaugeLevel.NEUTRAL || raw == GaugeLevel.UNAVAILABLE ||
            current == null || current == GaugeLevel.NEUTRAL || current == GaugeLevel.UNAVAILABLE
        if (bypass) {
            displayed = raw
            pending = null
            return raw
        }
        if (raw == current) {
            pending = null
            return current
        }
        if (pending != raw) {
            pending = raw
            pendingSinceMs = nowMs
        }
        val windowMs = if (rank(raw) > rank(current)) escalateMs else deescalateMs
        return if (nowMs - pendingSinceMs >= windowMs) {
            displayed = raw
            pending = null
            raw
        } else current
    }

    private fun rank(level: GaugeLevel): Int = when (level) {
        GaugeLevel.RED -> 3
        GaugeLevel.AMBER -> 2
        else -> 1
    }
}
```

- [ ] **Step 2: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/health/HysteresisFilterTest.kt`:

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class HysteresisFilterTest {

    @Test
    fun firstSample_displaysImmediately() {
        val f = HysteresisFilter()
        assertEquals(GaugeLevel.AMBER, f.update(GaugeLevel.AMBER, 0))
    }

    @Test
    fun escalation_requiresSustainedWorseLevel() {
        val f = HysteresisFilter()
        f.update(GaugeLevel.OK, 0)
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.AMBER, 500))       // pending starts at 500
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.AMBER, 3_000))     // 2.5 s < 3 s
        assertEquals(GaugeLevel.AMBER, f.update(GaugeLevel.AMBER, 3_500))  // 3.0 s -> escalate
    }

    @Test
    fun flicker_atBandEdge_isSuppressed() {
        val f = HysteresisFilter()
        f.update(GaugeLevel.OK, 0)
        var t = 500L
        repeat(20) {  // OK/AMBER alternating every 500 ms — never sustained
            assertEquals(GaugeLevel.OK, f.update(GaugeLevel.AMBER, t)); t += 500
            assertEquals(GaugeLevel.OK, f.update(GaugeLevel.OK, t)); t += 500
        }
    }

    @Test
    fun deescalation_takesLonger() {
        val f = HysteresisFilter()
        f.update(GaugeLevel.RED, 0)
        assertEquals(GaugeLevel.RED, f.update(GaugeLevel.OK, 1_000))   // pending from 1 000
        assertEquals(GaugeLevel.RED, f.update(GaugeLevel.OK, 10_500))  // 9.5 s < 10 s
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.OK, 11_000))   // 10 s -> de-escalate
    }

    @Test
    fun pendingLevelChange_restartsTimer() {
        val f = HysteresisFilter()
        f.update(GaugeLevel.OK, 0)
        f.update(GaugeLevel.AMBER, 500)                                 // pending AMBER
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.RED, 2_500))    // pending restarts as RED
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.RED, 5_000))    // 2.5 s < 3 s
        assertEquals(GaugeLevel.RED, f.update(GaugeLevel.RED, 5_500))   // 3 s of RED
    }

    @Test
    fun unavailable_passesThrough_andResets() {
        val f = HysteresisFilter()
        f.update(GaugeLevel.RED, 0)
        assertEquals(GaugeLevel.UNAVAILABLE, f.update(GaugeLevel.UNAVAILABLE, 500))
        // Coming back: first sample after UNAVAILABLE displays immediately.
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.OK, 1_000))
    }
}
```

- [ ] **Step 3: Run it** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.health.HysteresisFilterTest"
```
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 4: Full suite green.** Leave for review.

---

## Task 10: Subsystems, DTC→subsystem map, metric registry

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/Subsystem.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/health/DtcSubsystemMap.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/health/Metrics.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/DtcSubsystemMapTest.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/MetricsTest.kt`

- [ ] **Step 1: Write `health/Subsystem.kt`:**

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.telemetry.Signal

// Spec §5.4's fixed subsystem set. OTHER renders as a card only when a DTC
// lands there.
enum class Subsystem(val displayName: String) {
    FUELING("Fueling"),
    AIR_INTAKE("Air / Intake"),
    COOLING("Cooling"),
    IGNITION("Ignition"),
    EXHAUST_CAT("Exhaust / Cat"),
    BATTERY("Battery"),
    OTHER("Other");

    companion object {
        // Live-signal ownership (spec §5.4 table). Signals not listed
        // (SPEED, THROTTLE partners…) influence no subsystem directly.
        val SIGNAL_SUBSYSTEMS: Map<Signal, Subsystem> = mapOf(
            Signal.STFT1 to FUELING,
            Signal.LTFT1 to FUELING,
            Signal.FUEL_STATUS to FUELING,
            Signal.O2_B1S1_V to FUELING,
            Signal.MAF_GS to AIR_INTAKE,
            Signal.MAP to AIR_INTAKE,
            Signal.THROTTLE to AIR_INTAKE,
            Signal.ECT to COOLING,
            Signal.TIMING_ADV to IGNITION,
            Signal.O2_B1S2_V to EXHAUST_CAT,
            Signal.BATT_V_ADC to BATTERY,
        )

        // Scope honesty rendered on the Ignition card (spec §5.4/§8.3).
        const val IGNITION_FOOTNOTE = "DTC-based — this ECU exposes no live misfire counters"
    }
}

enum class HealthStatus(val label: String) {
    OK("Looks good"),
    WATCH("Worth watching"),
    ATTENTION("Needs a look"),
}
```

- [ ] **Step 2: Write `health/DtcSubsystemMap.kt`:**

```kotlin
package com.kompressorlink.app.health

// Spec §5.4's DTC-family table — the ranges are DISJOINT by design (fixed
// during the spec self-review). Generic P0 codes route by number; every
// other shape (P1xxx manufacturer, C/B/U, malformed) is OTHER.
object DtcSubsystemMap {
    fun subsystemFor(code: String): Subsystem {
        if (code.length != 5 || !code.startsWith("P0")) return Subsystem.OTHER
        val n = code.substring(2).toIntOrNull() ?: return Subsystem.OTHER
        return when (n) {
            in 130..135, in 170..175, in 200..209, in 440..457 -> Subsystem.FUELING
            68, in 100..114, in 120..124, in 235..238, in 505..507 -> Subsystem.AIR_INTAKE
            in 115..119, in 125..128, in 480..485 -> Subsystem.COOLING
            in 10..17, in 300..312, in 325..328, in 335..349, in 350..362 -> Subsystem.IGNITION
            in 136..141, in 410..419, in 420..439 -> Subsystem.EXHAUST_CAT
            in 560..563 -> Subsystem.BATTERY
            else -> Subsystem.OTHER
        }
    }
}
```

- [ ] **Step 3: Write `health/Metrics.kt`:**

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.reference.Band
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal

// Spec §5.1: cross-session intelligence operates on metrics — one scalar per
// session, extracted from contextual session_stats columns. A session
// carries a metric only when its contextual count >= CONTEXT_MIN_SAMPLES.
enum class MetricId(
    val displayName: String,
    val unit: String,
    val signal: Signal,
    val subsystem: Subsystem,
    val bandContext: String,
) {
    LTFT_WARM_IDLE("LTFT at warm idle", "%", Signal.LTFT1, Subsystem.FUELING, "always"),
    STFT_WARM_IDLE("STFT at warm idle", "%", Signal.STFT1, Subsystem.FUELING, "always"),
    MAF_WARM_IDLE("MAF at warm idle", "g/s", Signal.MAF_GS, Subsystem.AIR_INTAKE, "warm_idle"),
    ECT_WARM_IDLE("Coolant at warm idle", "°C", Signal.ECT, Subsystem.COOLING, "warm_idle"),
    BATT_CHARGING("Charging voltage", "V", Signal.BATT_V_ADC, Subsystem.BATTERY, "engine_running"),
    BATT_REST("Resting voltage", "V", Signal.BATT_V_ADC, Subsystem.BATTERY, "engine_off");

    fun contextValue(stat: SessionStatEntity): Pair<Float, Int>? = when (this) {
        LTFT_WARM_IDLE, STFT_WARM_IDLE, MAF_WARM_IDLE, ECT_WARM_IDLE ->
            stat.warmIdleMean?.let { it to stat.warmIdleCount }
        BATT_CHARGING -> stat.engineRunningMean?.let { it to stat.engineRunningCount }
        BATT_REST -> stat.engineOffMean?.let { it to stat.engineOffCount }
    }
}

// One session's contribution to a metric series. eligible = usable for the
// personal baseline (spec §5.2: signal never went raw-RED in the session AND
// the session carried no stored DTC — don't learn "normal" from a visibly
// faulting car). Drift uses ALL points, eligible or not (spec §5.3).
data class MetricPoint(
    val sessionId: Long,
    val endedAtEpochMs: Long,
    val value: Float,
    val eligible: Boolean,
)

object MetricSeries {
    /** Points in ascending session-end order. */
    fun build(
        metric: MetricId,
        sessions: List<SessionEntity>,
        stats: List<SessionStatEntity>,
    ): List<MetricPoint> {
        val bySession = stats.filter { it.signal == metric.signal.name }.associateBy { it.sessionId }
        return sessions.sortedBy { it.endedAtEpochMs }.mapNotNull { session ->
            val stat = bySession[session.id] ?: return@mapNotNull null
            val (value, count) = metric.contextValue(stat) ?: return@mapNotNull null
            if (count < HealthTuning.CONTEXT_MIN_SAMPLES) return@mapNotNull null
            MetricPoint(
                sessionId = session.id,
                endedAtEpochMs = session.endedAtEpochMs,
                value = value,
                eligible = stat.worstLevel != "RED" && !session.hasStoredDtc,
            )
        }
    }

    /** The absolute band this metric is judged against — same parsed JSON the
     *  dashboard uses; one source of truth (spec §5.1). */
    fun bandFor(metric: MetricId, refs: ReferenceRepository): Band? =
        refs.bandsFor(metric.signal).firstOrNull { it.context == metric.bandContext }
}
```

- [ ] **Step 4: Write the failing tests.** `android/app/src/test/java/com/kompressorlink/app/health/DtcSubsystemMapTest.kt`:

```kotlin
package com.kompressorlink.app.health

import org.junit.Assert.assertEquals
import org.junit.Test

class DtcSubsystemMapTest {
    @Test
    fun specTable_spotChecks() {
        assertEquals(Subsystem.FUELING, DtcSubsystemMap.subsystemFor("P0171"))
        assertEquals(Subsystem.FUELING, DtcSubsystemMap.subsystemFor("P0455"))   // EVAP -> fueling
        assertEquals(Subsystem.FUELING, DtcSubsystemMap.subsystemFor("P0130"))   // pre-cat O2
        assertEquals(Subsystem.AIR_INTAKE, DtcSubsystemMap.subsystemFor("P0101"))
        assertEquals(Subsystem.AIR_INTAKE, DtcSubsystemMap.subsystemFor("P0506"))
        assertEquals(Subsystem.COOLING, DtcSubsystemMap.subsystemFor("P0117"))
        assertEquals(Subsystem.IGNITION, DtcSubsystemMap.subsystemFor("P0016"))  // cam adjusters
        assertEquals(Subsystem.IGNITION, DtcSubsystemMap.subsystemFor("P0301"))
        assertEquals(Subsystem.EXHAUST_CAT, DtcSubsystemMap.subsystemFor("P0420"))
        assertEquals(Subsystem.EXHAUST_CAT, DtcSubsystemMap.subsystemFor("P0136"))  // post-cat O2
        assertEquals(Subsystem.BATTERY, DtcSubsystemMap.subsystemFor("P0562"))
    }

    @Test
    fun everythingElse_isOther() {
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("P1570"))  // manufacturer
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("P0700"))  // transmission group
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("C1234"))
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("U0100"))
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("garbage"))
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("P0XYZ"))
    }
}
```

`android/app/src/test/java/com/kompressorlink/app/health/MetricsTest.kt`:

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MetricsTest {

    private fun session(id: Long, endedAt: Long, hasDtc: Boolean = false) = SessionEntity(
        id = id, startedAtEpochMs = endedAt - 600_000, endedAtEpochMs = endedAt,
        source = "REAL_BLE", snapshotCount = 1200, warmIdleSeconds = 120f,
        distanceKm = 5f, hasStoredDtc = hasDtc,
    )

    private fun ltftStat(sessionId: Long, warmIdleMean: Float?, warmIdleCount: Int, worst: String = "OK") =
        SessionStatEntity(
            sessionId = sessionId, signal = "LTFT1", sampleCount = 500,
            mean = warmIdleMean ?: 0f, min = -2f, max = 20f, stdDev = 1f,
            secondsOutOfBand = 0f, worstLevel = worst,
            warmIdleMean = warmIdleMean, warmIdleCount = warmIdleCount,
            engineRunningMean = null, engineRunningCount = 0,
            engineOffMean = null, engineOffCount = 0,
        )

    @Test
    fun build_ordersAscending_appliesSampleFloor_andEligibility() {
        val sessions = listOf(
            session(1, endedAt = 3_000),
            session(2, endedAt = 1_000),
            session(3, endedAt = 2_000, hasDtc = true),
            session(4, endedAt = 4_000),
        )
        val stats = listOf(
            ltftStat(1, warmIdleMean = 5f, warmIdleCount = 100),
            ltftStat(2, warmIdleMean = 4f, warmIdleCount = 100, worst = "RED"),
            ltftStat(3, warmIdleMean = 6f, warmIdleCount = 100),
            ltftStat(4, warmIdleMean = 7f, warmIdleCount = HealthTuning.CONTEXT_MIN_SAMPLES - 1),
        )
        val points = MetricSeries.build(MetricId.LTFT_WARM_IDLE, sessions, stats)
        // session 4 dropped (sample floor); order is 2, 3, 1 by endedAt.
        assertEquals(listOf(2L, 3L, 1L), points.map { it.sessionId })
        assertEquals(listOf(false, false, true), points.map { it.eligible })
        // session 2 ineligible (worst RED), session 3 ineligible (stored DTC)
    }

    @Test
    fun build_skipsSessionsMissingTheContext() {
        val sessions = listOf(session(1, endedAt = 1_000))
        val stats = listOf(ltftStat(1, warmIdleMean = null, warmIdleCount = 0))
        assertEquals(0, MetricSeries.build(MetricId.LTFT_WARM_IDLE, sessions, stats).size)
    }
}
```

- [ ] **Step 5: Run them** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.health.DtcSubsystemMapTest" --tests "com.kompressorlink.app.health.MetricsTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 6: Full suite green.** Leave for review.

---

## Task 11: Personal baseline — median ± 3×MAD with gates

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/Baseline.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/BaselineTest.kt`

- [ ] **Step 1: Write `health/Baseline.kt`:**

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.reference.Band
import kotlin.math.abs

// Spec §5.2: the sanctioned "learning" — per-metric personal-normal
// envelopes from the user's own healthy history. No fault labels, no model.
object Baseline {
    const val DAY_MS = 86_400_000L

    data class Envelope(
        val median: Float,
        val mad: Float,
        val lo: Float,
        val hi: Float,
        val eligibleCount: Int,
    )

    sealed interface Result {
        /** Not enough eligible history yet — UI shows "Collecting baseline · N of 8". */
        data class Gated(val eligibleCount: Int, val needed: Int) : Result
        data class Active(val envelope: Envelope) : Result
    }

    fun median(values: List<Float>): Float {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /**
     * points: ascending metric series (MetricSeries.build). Only eligible
     * points are learned from; window = last BASELINE_WINDOW of them.
     * Gates: >= BASELINE_MIN_SESSIONS eligible spanning >= 14 days.
     */
    fun evaluate(points: List<MetricPoint>, band: Band): Result {
        val eligible = points.filter { it.eligible }.takeLast(HealthTuning.BASELINE_WINDOW)
        if (eligible.size < HealthTuning.BASELINE_MIN_SESSIONS) {
            return Result.Gated(eligible.size, HealthTuning.BASELINE_MIN_SESSIONS)
        }
        val spanDays = (eligible.last().endedAtEpochMs - eligible.first().endedAtEpochMs) / DAY_MS.toFloat()
        if (spanDays < HealthTuning.BASELINE_MIN_SPAN_DAYS) {
            return Result.Gated(eligible.size, HealthTuning.BASELINE_MIN_SESSIONS)
        }
        val values = eligible.map { it.value }
        val med = median(values)
        val mad = median(values.map { abs(it - med) })
        val bandWidth = band.hi - band.lo
        // Degenerate-MAD guard (spec §5.2): a car this consistent would
        // otherwise alarm on noise.
        val delta = if (mad < HealthTuning.BASELINE_DEGENERATE_MAD_FRACTION * bandWidth) {
            HealthTuning.BASELINE_DEGENERATE_WIDEN_FRACTION * bandWidth
        } else {
            HealthTuning.BASELINE_MAD_K * mad
        }
        return Result.Active(Envelope(med, mad, med - delta, med + delta, eligible.size))
    }

    /** Spec §5.2: deviation = outside the personal envelope but INSIDE the
     *  absolute band (beyond the band, live severity owns the story). */
    fun isDeviation(current: Float, envelope: Envelope, band: Band): Boolean =
        (current < envelope.lo || current > envelope.hi) &&
            current >= band.lo && current <= band.hi
}
```

- [ ] **Step 2: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/health/BaselineTest.kt`:

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.reference.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineTest {

    private val ltftBand = Band(
        signal = "LTFT1", context = "always", lo = -10f, hi = 10f,
        unit = "%", confidence = "Confirmed", hint = "hint",
    )

    private fun point(day: Int, value: Float, eligible: Boolean = true) = MetricPoint(
        sessionId = day.toLong(), endedAtEpochMs = day * Baseline.DAY_MS,
        value = value, eligible = eligible,
    )

    @Test
    fun gated_untilEightEligibleSessions() {
        val points = (0 until 7).map { point(it * 3, 4f) }
        val result = Baseline.evaluate(points, ltftBand)
        assertTrue(result is Baseline.Result.Gated)
        assertEquals(7, (result as Baseline.Result.Gated).eligibleCount)
    }

    @Test
    fun gated_ineligiblePointsDontCount() {
        val points = (0 until 12).map { point(it * 3, 4f, eligible = it % 2 == 0) }  // 6 eligible
        assertTrue(Baseline.evaluate(points, ltftBand) is Baseline.Result.Gated)
    }

    @Test
    fun gated_whenSpanTooShort() {
        val points = (0 until 10).map { point(it, 4f) }  // 10 sessions in 9 days < 14
        assertTrue(Baseline.evaluate(points, ltftBand) is Baseline.Result.Gated)
    }

    @Test
    fun active_medianAndMad_robustToOutlier() {
        // 9 sessions around 4% with one wild 9% — median/MAD shrug it off.
        // MAD here is 0.3, safely above the degenerate threshold (1% of the
        // 20-unit band = 0.2), so the ±3×MAD path is exercised.
        val values = listOf(3.4f, 3.7f, 4.0f, 4.3f, 4.6f, 4.0f, 9.0f, 3.7f, 4.3f)
        val points = values.mapIndexed { i, v -> point(i * 2, v) }  // spans 16 days
        val result = Baseline.evaluate(points, ltftBand) as Baseline.Result.Active
        assertEquals(4.0f, result.envelope.median, 1e-4f)
        assertEquals(0.3f, result.envelope.mad, 1e-4f)
        assertEquals(4.0f - 0.9f, result.envelope.lo, 1e-4f)   // median ± 3×MAD
        assertEquals(4.0f + 0.9f, result.envelope.hi, 1e-4f)
    }

    @Test
    fun degenerateMad_widensToBandFraction() {
        // Perfectly constant history: MAD 0 < 1% of the 20-unit band width,
        // so the envelope widens to ±5% of band width = ±1.0.
        val points = (0 until 10).map { point(it * 2, 4f) }
        val result = Baseline.evaluate(points, ltftBand) as Baseline.Result.Active
        assertEquals(3f, result.envelope.lo, 1e-4f)
        assertEquals(5f, result.envelope.hi, 1e-4f)
    }

    @Test
    fun deviation_onlyInsideAbsoluteBand() {
        val env = Baseline.Envelope(median = 4f, mad = 0.1f, lo = 3.7f, hi = 4.3f, eligibleCount = 10)
        assertTrue(Baseline.isDeviation(8f, env, ltftBand))    // outside envelope, inside band
        assertFalse(Baseline.isDeviation(4.1f, env, ltftBand)) // inside envelope
        assertFalse(Baseline.isDeviation(12f, env, ltftBand))  // beyond band: live severity owns it
    }
}
```

- [ ] **Step 3: Run it** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.health.BaselineTest"
```
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 4: Full suite green.** Leave for review.

---

## Task 12: Theil–Sen slope + drift verdicts

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/TheilSen.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/health/Drift.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/DriftTest.kt`

- [ ] **Step 1: Write `health/TheilSen.kt`:**

```kotlin
package com.kompressorlink.app.health

// Median of all pairwise slopes — robust to outlier sessions in a way
// least-squares is not (spec §5.3).
object TheilSen {
    /** Slope in value-units per DAY; null when < 2 distinct-time points. */
    fun slopePerDay(points: List<MetricPoint>): Float? {
        val slopes = ArrayList<Float>()
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val dtDays = (points[j].endedAtEpochMs - points[i].endedAtEpochMs) / Baseline.DAY_MS.toFloat()
                if (dtDays != 0f) {
                    slopes.add((points[j].value - points[i].value) / dtDays)
                }
            }
        }
        return if (slopes.isEmpty()) null else Baseline.median(slopes)
    }
}
```

- [ ] **Step 2: Write `health/Drift.kt`:**

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.reference.Band
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Spec §5.3: the honest "predict before it happens" — time-to-threshold
// extrapolation with explicit "~" and "at this rate" phrasing. Never a
// probability. Drift uses ALL points (eligible or not — it must see
// deterioration). Already-beyond-the-edge metrics stay silent here: live
// severity and baseline own that story.
object Drift {
    sealed interface Result {
        data class Gated(val points: Int, val needed: Int) : Result
        data object Stable : Result
        data class Drifting(
            val slopePerWeek: Float,
            val weeksToEdge: Int,
            val rising: Boolean,
            val message: String,
        ) : Result
    }

    fun evaluate(metric: MetricId, points: List<MetricPoint>, band: Band): Result {
        val window = points.takeLast(HealthTuning.DRIFT_WINDOW)
        if (window.size < HealthTuning.DRIFT_MIN_POINTS) {
            return Result.Gated(window.size, HealthTuning.DRIFT_MIN_POINTS)
        }
        val spanDays = (window.last().endedAtEpochMs - window.first().endedAtEpochMs) / Baseline.DAY_MS.toFloat()
        if (spanDays < HealthTuning.DRIFT_MIN_SPAN_DAYS) {
            return Result.Gated(window.size, HealthTuning.DRIFT_MIN_POINTS)
        }
        val slopePerDay = TheilSen.slopePerDay(window) ?: return Result.Stable
        if (slopePerDay == 0f) return Result.Stable
        val current = Baseline.median(window.takeLast(3).map { it.value })
        if (current < band.lo || current > band.hi) return Result.Stable
        val slopePerWeek = slopePerDay * 7f
        val edge = if (slopePerDay > 0f) band.hi else band.lo
        val weeks = (edge - current) / slopePerWeek
        if (weeks < HealthTuning.DRIFT_MIN_WEEKS || weeks > HealthTuning.DRIFT_MAX_WEEKS) {
            return Result.Stable
        }
        val rising = slopePerDay > 0f
        val weeksRounded = weeks.roundToInt().coerceAtLeast(1)
        // Normative wording template (spec §5.3).
        val message = String.format(
            Locale.US,
            "%s is %s ≈ %.1f %s/week — at this rate it crosses %.0f %s in ~%d weeks. %s",
            metric.displayName, if (rising) "rising" else "falling",
            abs(slopePerWeek), metric.unit, edge, metric.unit, weeksRounded, band.hint,
        )
        return Result.Drifting(slopePerWeek, weeksRounded, rising, message)
    }
}
```

- [ ] **Step 3: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/health/DriftTest.kt`:

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.reference.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftTest {

    private val ltftBand = Band(
        signal = "LTFT1", context = "always", lo = -10f, hi = 10f,
        unit = "%", confidence = "Confirmed", hint = "Breather hoses first.",
    )

    private fun point(day: Int, value: Float) = MetricPoint(
        sessionId = day.toLong(), endedAtEpochMs = day * Baseline.DAY_MS,
        value = value, eligible = true,
    )

    // 11 sessions over 20 days rising 0.1 %/day: 6.0 -> 8.0.
    private fun risingSeries() = (0..10).map { point(it * 2, 6f + it * 0.2f) }

    @Test
    fun cleanRisingDrift_reportsWeeksToEdge() {
        val result = Drift.evaluate(MetricId.LTFT_WARM_IDLE, risingSeries(), ltftBand)
        assertTrue(result is Drift.Result.Drifting)
        val d = result as Drift.Result.Drifting
        assertTrue(d.rising)
        assertEquals(0.7f, d.slopePerWeek, 0.01f)          // 0.1 %/day
        assertEquals(3, d.weeksToEdge)                     // (10 - ~7.8) / 0.7 ≈ 3.1
        assertTrue(d.message.contains("rising"))
        assertTrue(d.message.contains("~3 weeks"))
        assertTrue(d.message.contains("Breather hoses first."))
    }

    @Test
    fun outlierSession_doesNotBreakTheSlope() {
        val withOutlier = risingSeries().toMutableList()
        withOutlier[5] = point(10, 25f)  // one wild session
        val clean = Drift.evaluate(MetricId.LTFT_WARM_IDLE, risingSeries(), ltftBand) as Drift.Result.Drifting
        val noisy = Drift.evaluate(MetricId.LTFT_WARM_IDLE, withOutlier, ltftBand) as Drift.Result.Drifting
        assertTrue(kotlin.math.abs(clean.weeksToEdge - noisy.weeksToEdge) <= 1)
    }

    @Test
    fun gated_belowMinPoints_orSpan() {
        val few = (0..5).map { point(it * 3, 6f + it * 0.2f) }  // 6 points
        assertTrue(Drift.evaluate(MetricId.LTFT_WARM_IDLE, few, ltftBand) is Drift.Result.Gated)

        val shortSpan = (0..10).map { point(it, 6f + it * 0.2f) }  // 10 days < 14
        assertTrue(Drift.evaluate(MetricId.LTFT_WARM_IDLE, shortSpan, ltftBand) is Drift.Result.Gated)
    }

    @Test
    fun stable_whenFlat_orTooSlow() {
        val flat = (0..10).map { point(it * 2, 4f) }
        assertTrue(Drift.evaluate(MetricId.LTFT_WARM_IDLE, flat, ltftBand) is Drift.Result.Stable)

        // 0.01 %/day => 0.07 %/week => ~85 weeks to edge: beyond 26 -> Stable.
        val slow = (0..10).map { point(it * 2, 4f + it * 0.02f) }
        assertTrue(Drift.evaluate(MetricId.LTFT_WARM_IDLE, slow, ltftBand) is Drift.Result.Stable)
    }

    @Test
    fun alreadyBeyondEdge_staysSilent() {
        val beyond = (0..10).map { point(it * 2, 11f + it * 0.2f) }  // current > 10
        assertTrue(Drift.evaluate(MetricId.LTFT_WARM_IDLE, beyond, ltftBand) is Drift.Result.Stable)
    }

    @Test
    fun fallingDrift_targetsLowerEdge() {
        // MAF sagging toward 3 g/s: 4.2 -> 3.4 over 20 days.
        val mafBand = Band("MAF_GS", "warm_idle", 3f, 5f, "g/s", "Confirmed", "Intake leaks first.")
        val falling = (0..10).map { point(it * 2, 4.2f - it * 0.08f) }
        val d = Drift.evaluate(MetricId.MAF_WARM_IDLE, falling, mafBand) as Drift.Result.Drifting
        assertTrue(!d.rising)
        assertTrue(d.message.contains("falling"))
        assertTrue(d.message.contains("crosses 3"))
    }
}
```

- [ ] **Step 4: Run it** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.health.DriftTest"
```
Expected: `BUILD SUCCESSFUL`, 6 tests pass. If `cleanRisingDrift_reportsWeeksToEdge` disagrees by exactly 1 week on `weeksToEdge`, print the computed `current` (median of last 3 = 7.8) and `weeks = (10−7.8)/0.7 = 3.14 → 3`; the assertion is correct — fix the implementation, not the test.

- [ ] **Step 5: Full suite green.** Leave for review.

---
## Task 13: Subsystem rollup — cards + overall verdict

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/HealthRollup.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/HealthRollupTest.kt`

- [ ] **Step 1: Write `health/HealthRollup.kt`:**

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.Signal

data class SubsystemCard(
    val subsystem: Subsystem,
    val status: HealthStatus,
    val reason: String,
    val driftArrow: Char?,      // '↗' / '↘' while a drift verdict is active
    val baselineGate: String?,  // "Collecting baseline · N of 8 drives" while gated
    val footnote: String?,      // IGNITION scope-honesty footnote
)

data class OverallVerdict(val status: HealthStatus, val headline: String, val subline: String)

// Spec §5.4: subsystem status = worst of (a) live post-hysteresis levels
// (AMBER⇒WATCH, RED⇒ATTENTION, no-data⇒OK — absence of data is never a
// fault), (b) open warnings targeting it, (c) DTCs (stored⇒ATTENTION,
// pending⇒WATCH). Overall = worst subsystem, never an average.
object HealthRollup {

    private fun signalTitle(signal: Signal): String = when (signal) {
        Signal.RPM -> "RPM"; Signal.MAF_GS -> "MAF"; Signal.STFT1 -> "STFT"
        Signal.LTFT1 -> "LTFT"; Signal.ECT -> "Coolant"; Signal.MAP -> "MAP"
        Signal.O2_B1S1_V -> "O2 pre-cat"; Signal.O2_B1S2_V -> "O2 post-cat"
        Signal.BATT_V_ADC -> "Battery"; Signal.TIMING_ADV -> "Timing"
        else -> signal.name
    }

    private fun rank(status: HealthStatus): Int = when (status) {
        HealthStatus.ATTENTION -> 2
        HealthStatus.WATCH -> 1
        HealthStatus.OK -> 0
    }

    fun cards(
        liveLevels: Map<Signal, GaugeLevel>,
        openWarnings: List<WarningEntity>,
        dtcReport: DtcReport?,
        drift: Map<MetricId, Drift.Result>,
        baseline: Map<MetricId, Baseline.Result>,
    ): List<SubsystemCard> = Subsystem.entries.mapNotNull { subsystem ->
        // Ordered contributions: highest-priority reason wins at equal status.
        val contributions = mutableListOf<Pair<HealthStatus, String>>()

        dtcReport?.stored.orEmpty()
            .filter { DtcSubsystemMap.subsystemFor(it) == subsystem }
            .forEach { contributions += HealthStatus.ATTENTION to "Stored code $it — see DTCs tab" }

        liveLevels.forEach { (signal, level) ->
            if (Subsystem.SIGNAL_SUBSYSTEMS[signal] == subsystem) {
                when (level) {
                    GaugeLevel.RED ->
                        contributions += HealthStatus.ATTENTION to "${signalTitle(signal)} out of range right now"
                    GaugeLevel.AMBER ->
                        contributions += HealthStatus.WATCH to "${signalTitle(signal)} outside its band"
                    else -> Unit
                }
            }
        }

        openWarnings.filter { it.subsystem == subsystem.name }.forEach { w ->
            val status = if (w.level == "ATTENTION") HealthStatus.ATTENTION else HealthStatus.WATCH
            contributions += status to w.title
        }

        var arrow: Char? = null
        MetricId.entries.filter { it.subsystem == subsystem }.forEach { metric ->
            val d = drift[metric]
            if (d is Drift.Result.Drifting) {
                arrow = if (d.rising) '↗' else '↘'
                contributions += HealthStatus.WATCH to
                    "${metric.displayName} drifting ${if (d.rising) "up" else "down"} — ~${d.weeksToEdge} weeks to the limit"
            }
        }

        dtcReport?.pending.orEmpty()
            .filter { DtcSubsystemMap.subsystemFor(it) == subsystem }
            .forEach { contributions += HealthStatus.WATCH to "Pending code $it — not confirmed yet" }

        val gated = MetricId.entries
            .filter { it.subsystem == subsystem }
            .mapNotNull { baseline[it] as? Baseline.Result.Gated }
            .maxByOrNull { it.eligibleCount }
        val baselineGate = gated?.let { "Collecting baseline · ${it.eligibleCount} of ${it.needed} drives" }

        val worst = contributions.maxByOrNull { rank(it.first) }
        val status = worst?.first ?: HealthStatus.OK
        val reason = contributions.filter { it.first == status }.firstOrNull()?.second ?: "Looks normal"

        if (subsystem == Subsystem.OTHER && contributions.isEmpty()) {
            null  // OTHER renders only when a DTC lands there (spec §5.4)
        } else {
            SubsystemCard(
                subsystem = subsystem,
                status = status,
                reason = reason,
                driftArrow = arrow,
                baselineGate = baselineGate,
                footnote = if (subsystem == Subsystem.IGNITION) Subsystem.IGNITION_FOOTNOTE else null,
            )
        }
    }

    fun overall(cards: List<SubsystemCard>): OverallVerdict {
        val worst = cards.maxByOrNull { rank(it.status) }?.status ?: HealthStatus.OK
        val offenders = cards.filter { it.status == worst }.map { it.subsystem.displayName }
        return when (worst) {
            HealthStatus.OK -> OverallVerdict(worst, "All systems look normal",
                "No warnings from your car's data")
            HealthStatus.WATCH -> OverallVerdict(worst, "Worth watching",
                plural(offenders, "worth watching"))
            HealthStatus.ATTENTION -> OverallVerdict(worst, "Needs a look",
                plural(offenders, if (offenders.size == 1) "needs attention" else "need attention"))
        }
    }

    private fun plural(names: List<String>, phrase: String): String {
        val n = names.size
        val noun = if (n == 1) "system" else "systems"
        return "$n $noun $phrase — ${names.joinToString(", ")}"
    }
}
```

- [ ] **Step 2: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/health/HealthRollupTest.kt`:

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRollupTest {

    private fun cards(
        levels: Map<Signal, GaugeLevel> = emptyMap(),
        warnings: List<WarningEntity> = emptyList(),
        dtc: DtcReport? = null,
        drift: Map<MetricId, Drift.Result> = emptyMap(),
        baseline: Map<MetricId, Baseline.Result> = emptyMap(),
    ) = HealthRollup.cards(levels, warnings, dtc, drift, baseline)

    private fun card(cards: List<SubsystemCard>, s: Subsystem) = cards.first { it.subsystem == s }

    @Test
    fun allQuiet_everyCardOk_otherHidden() {
        val result = cards()
        assertEquals(6, result.size)  // OTHER hidden when empty
        assertTrue(result.all { it.status == HealthStatus.OK && it.reason == "Looks normal" })
        assertEquals(Subsystem.IGNITION_FOOTNOTE, card(result, Subsystem.IGNITION).footnote)
        assertNull(card(result, Subsystem.FUELING).footnote)
    }

    @Test
    fun storedDtc_forcesAttention_onItsSubsystem() {
        val result = cards(dtc = DtcReport(stored = listOf("P0171"), pending = emptyList()))
        val fueling = card(result, Subsystem.FUELING)
        assertEquals(HealthStatus.ATTENTION, fueling.status)
        assertTrue(fueling.reason.contains("P0171"))
    }

    @Test
    fun unmappedDtc_createsOtherCard() {
        val result = cards(dtc = DtcReport(stored = emptyList(), pending = listOf("P1570")))
        val other = card(result, Subsystem.OTHER)
        assertEquals(HealthStatus.WATCH, other.status)
        assertTrue(other.reason.contains("P1570"))
    }

    @Test
    fun liveLevels_mapToStatuses_noDataIsNeverAFault() {
        val result = cards(levels = mapOf(
            Signal.LTFT1 to GaugeLevel.RED,
            Signal.MAF_GS to GaugeLevel.AMBER,
            Signal.ECT to GaugeLevel.UNAVAILABLE,
            Signal.BATT_V_ADC to GaugeLevel.NEUTRAL,
        ))
        assertEquals(HealthStatus.ATTENTION, card(result, Subsystem.FUELING).status)
        assertEquals(HealthStatus.WATCH, card(result, Subsystem.AIR_INTAKE).status)
        assertEquals(HealthStatus.OK, card(result, Subsystem.COOLING).status)
        assertEquals(HealthStatus.OK, card(result, Subsystem.BATTERY).status)
    }

    @Test
    fun drift_setsArrowAndWatch() {
        val drifting = Drift.Result.Drifting(
            slopePerWeek = 0.7f, weeksToEdge = 3, rising = true, message = "msg",
        )
        val result = cards(drift = mapOf(MetricId.LTFT_WARM_IDLE to drifting))
        val fueling = card(result, Subsystem.FUELING)
        assertEquals(HealthStatus.WATCH, fueling.status)
        assertEquals('↗', fueling.driftArrow)
        assertTrue(fueling.reason.contains("~3 weeks"))
    }

    @Test
    fun baselineGate_rendersCollectingText() {
        val result = cards(baseline = mapOf(
            MetricId.MAF_WARM_IDLE to Baseline.Result.Gated(eligibleCount = 5, needed = 8),
        ))
        assertEquals("Collecting baseline · 5 of 8 drives",
            card(result, Subsystem.AIR_INTAKE).baselineGate)
        assertNull(card(result, Subsystem.COOLING).baselineGate)
    }

    @Test
    fun overall_isWorstOf_neverAnAverage() {
        val quiet = cards()
        assertEquals(HealthStatus.OK, HealthRollup.overall(quiet).status)
        assertEquals("All systems look normal", HealthRollup.overall(quiet).headline)

        val oneBad = cards(dtc = DtcReport(stored = listOf("P0171"), pending = emptyList()))
        val verdict = HealthRollup.overall(oneBad)
        assertEquals(HealthStatus.ATTENTION, verdict.status)
        assertEquals("Needs a look", verdict.headline)
        assertEquals("1 system needs attention — Fueling", verdict.subline)
    }
}
```

- [ ] **Step 3: Run it** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.health.HealthRollupTest"
```
Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [ ] **Step 4: Full suite green.** Leave for review.

---

## Task 14: LiveWarningMonitor — one source of level truth + live/DTC warnings

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/LiveWarningMonitor.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/LiveWarningMonitorTest.kt`

- [ ] **Step 1: Write `health/LiveWarningMonitor.kt`:**

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.DashboardLogic
import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.WarningRepository
import com.kompressorlink.app.data.WarningSource
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.TelemetrySource
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// App-scoped: owns the post-hysteresis level truth every screen shares
// (spec §5.5) and raises LIVE_OUT_OF_BAND + DTC warnings through the
// deduping WarningRepository. RED raises immediately (post-hysteresis);
// AMBER raises after a sustained dwell. All warnings carry the active
// source's SIM/REAL tag — the quarantine holds in the warnings table too.
class LiveWarningMonitor(
    private val scope: CoroutineScope,
    private val source: TelemetrySource,
    private val choice: Flow<SourceChoice>,
    private val refs: ReferenceRepository,
    private val warnings: WarningRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _levels = MutableStateFlow<Map<Signal, GaugeLevel>>(emptyMap())
    val levels: StateFlow<Map<Signal, GaugeLevel>> = _levels

    fun start(): Job = scope.launch {
        val filters = Signal.entries.associateWith { HysteresisFilter() }
        val amberSince = HashMap<Signal, Long>()
        var warningSource = WarningSource.SIM
        var prevStored = emptySet<String>()
        var prevPending = emptySet<String>()

        launch {
            choice.collect { warningSource = WarningSource.from(SessionSource.from(it)) }
        }

        launch {
            source.telemetry.collect { snapshot ->
                val atMs = now()
                val newLevels = HashMap<Signal, GaugeLevel>()
                for (signal in DashboardLogic.DASHBOARD_SIGNALS) {
                    val value = snapshot.value(signal)
                    val band = DashboardLogic.applicableBand(signal, snapshot, refs)
                    val raw = when {
                        value == null -> GaugeLevel.UNAVAILABLE
                        band == null -> GaugeLevel.NEUTRAL
                        else -> DashboardLogic.levelFor(value, band)
                    }
                    val level = filters.getValue(signal).update(raw, atMs)
                    newLevels[signal] = level

                    val subsystem = Subsystem.SIGNAL_SUBSYSTEMS[signal]
                    if (subsystem != null && value != null && band != null) {
                        when (level) {
                            GaugeLevel.RED -> {
                                amberSince.remove(signal)
                                warnings.raise(liveWarning(
                                    signal, subsystem, "ATTENTION",
                                    title = "${title(signal)} out of range",
                                    value = value, band = band, atMs = atMs,
                                    source = warningSource,
                                ))
                            }
                            GaugeLevel.AMBER -> {
                                val since = amberSince.getOrPut(signal) { atMs }
                                if (atMs - since >= HealthTuning.AMBER_WARN_DWELL_MS) {
                                    warnings.raise(liveWarning(
                                        signal, subsystem, "WATCH",
                                        title = "${title(signal)} outside its band",
                                        value = value, band = band, atMs = atMs,
                                        source = warningSource,
                                    ))
                                }
                            }
                            else -> amberSince.remove(signal)
                        }
                    }
                }
                _levels.value = newLevels
            }
        }

        launch {
            source.dtcReport.collect { report ->
                val atMs = now()
                val stored = report?.stored?.toSet() ?: emptySet()
                val pending = report?.pending?.toSet() ?: emptySet()
                (stored - prevStored).forEach { code ->
                    warnings.raise(dtcWarning(code, "ATTENTION",
                        "Stored code $code",
                        "The ECU has confirmed and stored $code. See the DTCs tab for what it means and what to check first.",
                        atMs, warningSource))
                }
                (pending - prevPending).forEach { code ->
                    warnings.raise(dtcWarning(code, "WATCH",
                        "Pending code $code",
                        "The ECU has seen $code but not confirmed it yet. If it recurs it becomes a stored code.",
                        atMs, warningSource))
                }
                prevStored = stored
                prevPending = pending
            }
        }
    }

    private fun title(signal: Signal): String = when (signal) {
        Signal.MAF_GS -> "MAF"; Signal.STFT1 -> "STFT"; Signal.LTFT1 -> "LTFT"
        Signal.ECT -> "Coolant"; Signal.BATT_V_ADC -> "Battery"
        Signal.O2_B1S1_V -> "O2 pre-cat"; Signal.O2_B1S2_V -> "O2 post-cat"
        else -> signal.name
    }

    private fun liveWarning(
        signal: Signal, subsystem: Subsystem, level: String, title: String,
        value: Float, band: com.kompressorlink.app.reference.Band,
        atMs: Long, source: WarningSource,
    ) = WarningEntity(
        createdAtEpochMs = atMs, lastSeenAtEpochMs = atMs, sessionId = null,
        subsystem = subsystem.name, signal = signal.name, level = level,
        kind = "LIVE_OUT_OF_BAND", title = title,
        detail = String.format(Locale.US, "%.1f %s vs healthy %.1f–%.1f %s. %s",
            value, band.unit, band.lo, band.hi, band.unit, band.hint),
        acknowledged = false, source = source.name,
        dedupeKey = "LIVE_OUT_OF_BAND:${subsystem.name}:${signal.name}",
    )

    private fun dtcWarning(
        code: String, level: String, title: String, detail: String,
        atMs: Long, source: WarningSource,
    ) = WarningEntity(
        createdAtEpochMs = atMs, lastSeenAtEpochMs = atMs, sessionId = null,
        subsystem = DtcSubsystemMap.subsystemFor(code).name, signal = null,
        level = level, kind = "DTC", title = title, detail = detail,
        acknowledged = false, source = source.name,
        dedupeKey = "DTC:${DtcSubsystemMap.subsystemFor(code).name}:$code",
    )
}
```

- [ ] **Step 2: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/health/LiveWarningMonitorTest.kt`:

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.FakeWarningRepository
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWarningMonitorTest {

    private val refs = ReferenceRepository { name -> File("src/main/assets/$name").readText() }

    private class ScriptedSource : TelemetrySource {
        val telemetryFlow = MutableSharedFlow<TelemetrySnapshot>()
        val dtcFlow = MutableStateFlow<DtcReport?>(null)
        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Simulated("scripted"))
        override val telemetry: Flow<TelemetrySnapshot> = telemetryFlow
        override val dtcReport: StateFlow<DtcReport?> = dtcFlow
        override suspend fun sendTimeSync() {}
    }

    private fun snap(ltft: Float): TelemetrySnapshot {
        val v = FloatArray(SIGNAL_COUNT)
        var mask = 0
        listOf(Signal.RPM to 750f, Signal.SPEED to 0f, Signal.ECT to 90f, Signal.LTFT1 to ltft)
            .forEach { (s, value) -> v[s.ordinal] = value; mask = mask or (1 shl s.ordinal) }
        return TelemetrySnapshot(v, mask, flags = 0, seq = 0, uptimeMs = 0)
    }

    @Test
    fun sustainedRed_raisesAttention_once_dedup() = runTest {
        val repo = FakeWarningRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        val monitor = LiveWarningMonitor(backgroundScope, src, choice, refs, repo,
            now = { testScheduler.currentTime })
        monitor.start()
        runCurrent()

        // LTFT 25% is raw RED (beyond ±10 + half-width 10). Hysteresis needs
        // 3 s sustained; frames every 500 ms.
        repeat(10) { src.telemetryFlow.emit(snap(25f)); advanceTimeBy(500) }
        runCurrent()

        assertEquals(GaugeLevel.RED, monitor.levels.value[Signal.LTFT1])
        val open = repo.warnings.value.filter { !it.acknowledged && it.kind == "LIVE_OUT_OF_BAND" }
        assertEquals(1, open.size)  // deduped despite repeated raises
        assertEquals("ATTENTION", open[0].level)
        assertEquals("FUELING", open[0].subsystem)
        assertEquals("REAL", open[0].source)
        assertTrue(open[0].detail.contains("25.0 %"))
    }

    @Test
    fun amber_needsSixtySecondDwell() = runTest {
        val repo = FakeWarningRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.SIMULATED_FAULT)
        LiveWarningMonitor(backgroundScope, src, choice, refs, repo,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        // LTFT 15% = raw AMBER. 30 s: level escalates (3 s) but no warning yet.
        repeat(60) { src.telemetryFlow.emit(snap(15f)); advanceTimeBy(500) }
        runCurrent()
        assertEquals(0, repo.warnings.value.size)

        // Another 40 s of AMBER crosses the 60 s dwell.
        repeat(80) { src.telemetryFlow.emit(snap(15f)); advanceTimeBy(500) }
        runCurrent()
        val open = repo.warnings.value
        assertEquals(1, open.size)
        assertEquals("WATCH", open[0].level)
        assertEquals("SIM", open[0].source)  // quarantine tag from the sim choice
    }

    @Test
    fun healthyStream_raisesNothing() = runTest {
        val repo = FakeWarningRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        LiveWarningMonitor(backgroundScope, src, choice, refs, repo,
            now = { testScheduler.currentTime }).start()
        runCurrent()
        repeat(20) { src.telemetryFlow.emit(snap(4.5f)); advanceTimeBy(500) }
        runCurrent()
        assertEquals(0, repo.warnings.value.size)
    }

    @Test
    fun dtcTransitions_raiseOncePerCode() = runTest {
        val repo = FakeWarningRepository()
        val src = ScriptedSource()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        LiveWarningMonitor(backgroundScope, src, choice, refs, repo,
            now = { testScheduler.currentTime }).start()
        runCurrent()

        src.dtcFlow.value = DtcReport(stored = listOf("P0171"), pending = listOf("P1570"))
        runCurrent()
        src.dtcFlow.value = DtcReport(stored = listOf("P0171"), pending = listOf("P1570"))
        runCurrent()

        val open = repo.warnings.value
        assertEquals(2, open.size)
        val storedWarning = open.first { it.title.contains("P0171") }
        assertEquals("ATTENTION", storedWarning.level)
        assertEquals("FUELING", storedWarning.subsystem)
        val pendingWarning = open.first { it.title.contains("P1570") }
        assertEquals("WATCH", pendingWarning.level)
        assertEquals("OTHER", pendingWarning.subsystem)
    }
}
```

- [ ] **Step 3: Run it** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.health.LiveWarningMonitorTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 4: Full suite green.** Leave for review.

---

## Task 15: PostSessionEvaluator + container wiring v2

Baseline-deviation and drift warnings are raised when a session closes (the recorder's `onSessionClosed` hook); the container gains the monitor + evaluator and hands the hook to the recorder.

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/PostSessionEvaluator.kt`
- Modify: `android/app/src/main/java/com/kompressorlink/app/KompressorLinkApp.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/PostSessionEvaluatorTest.kt`

- [ ] **Step 1: Write `health/PostSessionEvaluator.kt`:**

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.data.SessionRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.WarningRepository
import com.kompressorlink.app.data.WarningSource
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.reference.ReferenceRepository
import java.util.Locale

// Runs after every persisted session (spec §5.2/§5.3): re-evaluates each
// metric's personal baseline (envelope from PRIOR history, judging the
// just-closed session) and drift, raising WATCH warnings through the
// deduping repository. Source honesty: a sim session is evaluated against
// sim history and raises SIM-tagged warnings — visible only in Demo mode.
class PostSessionEvaluator(
    private val sessions: SessionRepository,
    private val warnings: WarningRepository,
    private val refs: ReferenceRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun onSessionClosed(sessionId: Long, source: SessionSource) {
        val sourceSet = if (source.isReal) SessionSource.REAL else SessionSource.SIM
        val warningSource = WarningSource.from(source)
        val history = sessions
            .recent(sourceSet, HealthTuning.BASELINE_WINDOW + 10)
            .sortedBy { it.endedAtEpochMs }
        if (history.isEmpty()) return
        val stats = sessions.statsForSessions(history.map { it.id })
        val atMs = now()

        for (metric in MetricId.entries) {
            val band = MetricSeries.bandFor(metric, refs) ?: continue
            val points = MetricSeries.build(metric, history, stats)
            if (points.isEmpty()) continue

            val currentPoint = points.lastOrNull { it.sessionId == sessionId }
            if (currentPoint != null) {
                val prior = points.filterNot { it.sessionId == sessionId }
                val baseline = Baseline.evaluate(prior, band)
                if (baseline is Baseline.Result.Active &&
                    Baseline.isDeviation(currentPoint.value, baseline.envelope, band)
                ) {
                    val env = baseline.envelope
                    val spread = env.hi - env.median
                    warnings.raise(WarningEntity(
                        createdAtEpochMs = atMs, lastSeenAtEpochMs = atMs, sessionId = sessionId,
                        subsystem = metric.subsystem.name, signal = metric.signal.name,
                        level = "WATCH", kind = "BASELINE_DEVIATION",
                        title = "${metric.displayName} ${if (currentPoint.value > env.median) "above" else "below"} your car's usual",
                        // Normative template (spec §5.2).
                        detail = String.format(Locale.US,
                            "%s is outside your car's usual range (%.1f %s vs typical %.1f ± %.1f %s) — inside absolute limits, worth watching.",
                            metric.displayName, currentPoint.value, metric.unit,
                            env.median, spread, metric.unit),
                        acknowledged = false, source = warningSource.name,
                        dedupeKey = "BASELINE_DEVIATION:${metric.subsystem.name}:${metric.name}",
                    ))
                }
            }

            val drift = Drift.evaluate(metric, points, band)
            if (drift is Drift.Result.Drifting) {
                warnings.raise(WarningEntity(
                    createdAtEpochMs = atMs, lastSeenAtEpochMs = atMs, sessionId = sessionId,
                    subsystem = metric.subsystem.name, signal = metric.signal.name,
                    level = "WATCH", kind = "DRIFT",
                    title = "${metric.displayName} drifting ${if (drift.rising) "up" else "down"}",
                    detail = drift.message,
                    acknowledged = false, source = warningSource.name,
                    dedupeKey = "DRIFT:${metric.subsystem.name}:${metric.name}",
                ))
            }
        }
    }
}
```

- [ ] **Step 2: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/health/PostSessionEvaluatorTest.kt`:

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.data.FakeSessionRepository
import com.kompressorlink.app.data.FakeWarningRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.reference.ReferenceRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostSessionEvaluatorTest {

    private val refs = ReferenceRepository { name -> File("src/main/assets/$name").readText() }

    private suspend fun addSession(
        repo: FakeSessionRepository,
        day: Int,
        ltftWarmIdle: Float,
        source: String = "REAL_BLE",
    ): Long {
        val endedAt = day * Baseline.DAY_MS
        return repo.record(
            SessionEntity(
                startedAtEpochMs = endedAt - 600_000, endedAtEpochMs = endedAt,
                source = source, snapshotCount = 1200, warmIdleSeconds = 300f,
                distanceKm = if (source == "REAL_BLE") 10f else null, hasStoredDtc = false,
            )
        ) { id ->
            listOf(SessionStatEntity(
                sessionId = id, signal = "LTFT1", sampleCount = 1000,
                mean = ltftWarmIdle, min = ltftWarmIdle - 1, max = ltftWarmIdle + 1, stdDev = 0.5f,
                secondsOutOfBand = 0f, worstLevel = "OK",
                warmIdleMean = ltftWarmIdle, warmIdleCount = 200,
                engineRunningMean = null, engineRunningCount = 0,
                engineOffMean = null, engineOffCount = 0,
            ))
        }
    }

    @Test
    fun deviation_raisesWatch_withTemplateWording() = runTest {
        val sessions = FakeSessionRepository()
        val warnings = FakeWarningRepository()
        val evaluator = PostSessionEvaluator(sessions, warnings, refs, now = { 0L })
        // 8 healthy sessions around 4% over 16 days, then one at 8% (inside
        // the ±10 band, outside the personal envelope).
        (0 until 8).forEach { addSession(sessions, day = it * 2 + 1, ltftWarmIdle = 4f + (it % 2) * 0.2f) }
        val currentId = addSession(sessions, day = 18, ltftWarmIdle = 8f)

        evaluator.onSessionClosed(currentId, SessionSource.REAL_BLE)

        val raised = warnings.warnings.value.filter { it.kind == "BASELINE_DEVIATION" }
        assertEquals(1, raised.size)
        assertEquals("WATCH", raised[0].level)
        assertEquals("REAL", raised[0].source)
        assertTrue(raised[0].detail.contains("outside your car's usual range"))
        assertTrue(raised[0].detail.contains("worth watching"))
    }

    @Test
    fun drift_raisesWatch_withMessage() = runTest {
        val sessions = FakeSessionRepository()
        val warnings = FakeWarningRepository()
        val evaluator = PostSessionEvaluator(sessions, warnings, refs, now = { 0L })
        // Rising LTFT 5.0 -> 7.0 over 20 days (0.1 %/day): drift verdict fires.
        var lastId = 0L
        (0..10).forEach { lastId = addSession(sessions, day = it * 2 + 1, ltftWarmIdle = 5f + it * 0.2f) }

        evaluator.onSessionClosed(lastId, SessionSource.REAL_BLE)

        val raised = warnings.warnings.value.filter { it.kind == "DRIFT" }
        assertEquals(1, raised.size)
        assertTrue(raised[0].detail.contains("at this rate"))
        assertTrue(raised[0].title.contains("drifting up"))
    }

    @Test
    fun tooLittleHistory_raisesNothing() = runTest {
        val sessions = FakeSessionRepository()
        val warnings = FakeWarningRepository()
        val evaluator = PostSessionEvaluator(sessions, warnings, refs, now = { 0L })
        (0 until 4).forEach { addSession(sessions, day = it * 2, ltftWarmIdle = 4f) }
        val id = addSession(sessions, day = 10, ltftWarmIdle = 8f)
        evaluator.onSessionClosed(id, SessionSource.REAL_BLE)
        assertEquals(0, warnings.warnings.value.size)
    }

    @Test
    fun simSession_evaluatesAgainstSimHistory_raisesSimTagged() = runTest {
        val sessions = FakeSessionRepository()
        val warnings = FakeWarningRepository()
        val evaluator = PostSessionEvaluator(sessions, warnings, refs, now = { 0L })
        (0 until 8).forEach { addSession(sessions, day = it * 2 + 1, ltftWarmIdle = 4f + (it % 2) * 0.2f, source = "SIM_HEALTHY") }
        val id = addSession(sessions, day = 18, ltftWarmIdle = 8f, source = "SIM_FAULT")
        evaluator.onSessionClosed(id, SessionSource.SIM_FAULT)
        val raised = warnings.warnings.value.filter { it.kind == "BASELINE_DEVIATION" }
        assertEquals(1, raised.size)
        assertEquals("SIM", raised[0].source)
    }
}
```

- [ ] **Step 3: Run it** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.health.PostSessionEvaluatorTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 4: Full replacement of `KompressorLinkApp.kt`** — identical to Task 7's version except the Phase 4.5 block at the bottom of `AppContainer` becomes:

```kotlin
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

    init {
        scope.launch { maintenanceRepository.ensureSeeded() }
        sessionRecorder.start()
        liveWarningMonitor.start()
    }
```

with these imports added to the file's import list: `com.kompressorlink.app.health.LiveWarningMonitor`, `com.kompressorlink.app.health.PostSessionEvaluator`. Everything else in the file stays exactly as Task 7 wrote it.

- [ ] **Step 5: Full suite + APK:**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 6: Leave for review.**

---
## Task 16: OdometerEstimator + DueCalculator

Pure maintenance math (java.time is fine on minSdk 26).

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/maintenance/OdometerEstimator.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/maintenance/DueCalculator.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/maintenance/OdometerEstimatorTest.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/maintenance/DueCalculatorTest.kt`

- [ ] **Step 1: Write `maintenance/OdometerEstimator.kt`:**

```kotlin
package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.OdometerEntryEntity
import kotlin.math.roundToInt

// Spec §6.1: estimated km = latest manual anchor + Σ REAL session distances
// since that anchor. No anchor -> null -> km-based due math stays inactive
// ("Enter your odometer to activate"). With no hardware yet the delta is
// simply 0 — correct and honest.
object OdometerEstimator {
    data class Estimate(
        val km: Int,
        val anchorKm: Int,
        val anchorEpochMs: Long,
        val estimatedDeltaKm: Int,
    )

    fun estimate(anchor: OdometerEntryEntity?, realDistanceSinceAnchorKm: Float): Estimate? {
        if (anchor == null) return null
        val delta = realDistanceSinceAnchorKm.roundToInt()
        return Estimate(
            km = anchor.km + delta,
            anchorKm = anchor.km,
            anchorEpochMs = anchor.epochMs,
            estimatedDeltaKm = delta,
        )
    }
}
```

- [ ] **Step 2: Write `maintenance/DueCalculator.kt`:**

```kotlin
package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import com.kompressorlink.app.health.HealthTuning
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Spec §6.3's due states, in exact precedence order.
enum class DueState { NEVER_LOGGED, OVERDUE, DUE_SOON, KM_UNTRACKED, OK }

data class DueInfo(
    val state: DueState,
    val remainingKm: Int?,     // null when the km side is incomputable
    val remainingDays: Long?,  // null when no month interval / never logged
    val kmUntracked: Boolean,  // a km interval exists but can't be computed
)

object DueCalculator {
    /**
     * Month math is calendar-correct (LocalDate.plusMonths), not day
     * approximations (spec §6.3). Dual-interval items are due at whichever
     * dimension comes first; a dual item with an incomputable km side falls
     * back to its date side and flags kmUntracked for the small UI hint.
     */
    fun evaluate(
        item: MaintenanceItemEntity,
        lastLog: ServiceLogEntity?,
        estimatedKm: Int?,
        nowMs: Long,
        zone: ZoneId,
    ): DueInfo {
        if (lastLog == null) {
            return DueInfo(DueState.NEVER_LOGGED, null, null, item.intervalKm != null)
        }
        val remainingKm = if (item.intervalKm != null && lastLog.km != null && estimatedKm != null) {
            lastLog.km + item.intervalKm - estimatedKm
        } else null
        val kmUntracked = item.intervalKm != null && remainingKm == null

        val remainingDays = if (item.intervalMonths != null) {
            val lastDate = Instant.ofEpochMilli(lastLog.epochMs).atZone(zone).toLocalDate()
            val dueDate = lastDate.plusMonths(item.intervalMonths.toLong())
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            ChronoUnit.DAYS.between(today, dueDate)
        } else null

        val state = when {
            (remainingKm != null && remainingKm < 0) ||
                (remainingDays != null && remainingDays < 0) -> DueState.OVERDUE
            (remainingKm != null && remainingKm <= HealthTuning.DUE_SOON_KM) ||
                (remainingDays != null && remainingDays <= HealthTuning.DUE_SOON_DAYS) -> DueState.DUE_SOON
            kmUntracked && remainingDays == null -> DueState.KM_UNTRACKED
            else -> DueState.OK
        }
        return DueInfo(state, remainingKm, remainingDays, kmUntracked)
    }
}
```

- [ ] **Step 3: Write the failing tests.** `android/app/src/test/java/com/kompressorlink/app/maintenance/OdometerEstimatorTest.kt`:

```kotlin
package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.OdometerEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OdometerEstimatorTest {
    @Test
    fun noAnchor_noEstimate() {
        assertNull(OdometerEstimator.estimate(null, 100f))
    }

    @Test
    fun anchorPlusRoundedRealDistance() {
        val anchor = OdometerEntryEntity(id = 1, epochMs = 1_000, km = 186_900)
        val e = OdometerEstimator.estimate(anchor, 550.4f)!!
        assertEquals(187_450, e.km)
        assertEquals(186_900, e.anchorKm)
        assertEquals(550, e.estimatedDeltaKm)
    }

    @Test
    fun zeroDistance_isJustTheAnchor() {
        val anchor = OdometerEntryEntity(id = 1, epochMs = 1_000, km = 186_900)
        assertEquals(186_900, OdometerEstimator.estimate(anchor, 0f)!!.km)
    }
}
```

`android/app/src/test/java/com/kompressorlink/app/maintenance/DueCalculatorTest.kt`:

```kotlin
package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DueCalculatorTest {

    private val zone = ZoneId.of("UTC")

    private fun item(km: Int?, months: Int?) = MaintenanceItemEntity(
        id = 1, name = "Oil", category = "ENGINE", intervalKm = km, intervalMonths = months,
        note = "", confidence = "Best estimate", builtin = true, enabled = true,
        lastNotifiedAtEpochMs = null,
    )

    private fun log(date: LocalDate, km: Int?) = ServiceLogEntity(
        id = 1, itemId = 1, epochMs = date.atStartOfDay(zone).toInstant().toEpochMilli(), km = km, note = null,
    )

    private fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun neverLogged() {
        val info = DueCalculator.evaluate(item(10_000, 12), null, 187_000, ms(LocalDate.of(2026, 7, 13)), zone)
        assertEquals(DueState.NEVER_LOGGED, info.state)
    }

    @Test
    fun overdueByKm() {
        val info = DueCalculator.evaluate(
            item(10_000, 12), log(LocalDate.of(2026, 5, 1), 176_000),
            estimatedKm = 187_000, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        assertEquals(DueState.OVERDUE, info.state)
        assertEquals(-1_000, info.remainingKm)
    }

    @Test
    fun overdueByCalendarMonths_endOfMonthEdge() {
        // Jan 31 + 1 month = Feb 28 (2026 not a leap year); Mar 1 is 1 day over.
        val info = DueCalculator.evaluate(
            item(null, 1), log(LocalDate.of(2026, 1, 31), null),
            estimatedKm = null, nowMs = ms(LocalDate.of(2026, 3, 1)), zone = zone,
        )
        assertEquals(DueState.OVERDUE, info.state)
        assertEquals(-1L, info.remainingDays)
    }

    @Test
    fun dueSoon_byKmOrDays() {
        val byKm = DueCalculator.evaluate(
            item(10_000, null), log(LocalDate.of(2026, 1, 1), 177_450),
            estimatedKm = 187_000, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        assertEquals(DueState.DUE_SOON, byKm.state)
        assertEquals(450, byKm.remainingKm)

        val byDays = DueCalculator.evaluate(
            item(null, 12), log(LocalDate.of(2025, 8, 1), null),
            estimatedKm = null, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        assertEquals(DueState.DUE_SOON, byDays.state)
        assertEquals(19L, byDays.remainingDays)
    }

    @Test
    fun kmOnlyItem_withoutOdometer_isKmUntracked() {
        val info = DueCalculator.evaluate(
            item(60_000, null), log(LocalDate.of(2026, 1, 1), 180_000),
            estimatedKm = null, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        assertEquals(DueState.KM_UNTRACKED, info.state)
        assertEquals(true, info.kmUntracked)
    }

    @Test
    fun dualItem_withoutOdometer_fallsBackToDateSide_withHint() {
        val info = DueCalculator.evaluate(
            item(10_000, 12), log(LocalDate.of(2026, 6, 1), null),
            estimatedKm = 187_000, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        // km side incomputable (log has no km) -> date side says OK (11 months left)
        assertEquals(DueState.OK, info.state)
        assertEquals(true, info.kmUntracked)
        assertEquals(null, info.remainingKm)
    }

    @Test
    fun healthy_ok() {
        val info = DueCalculator.evaluate(
            item(10_000, 12), log(LocalDate.of(2026, 6, 1), 182_500),
            estimatedKm = 187_000, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        assertEquals(DueState.OK, info.state)
        assertEquals(5_500, info.remainingKm)
    }
}
```

- [ ] **Step 4: Run them** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.maintenance.*"
```
Expected: `BUILD SUCCESSFUL`, 10 tests pass.

- [ ] **Step 5: Full suite green.** Leave for review.

---

## Task 17: ReminderPolicy + MaintenanceCheckWorker + scheduling

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/maintenance/ReminderPolicy.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/maintenance/MaintenanceCheckWorker.kt`
- Modify: `android/app/src/main/java/com/kompressorlink/app/KompressorLinkApp.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/maintenance/ReminderPolicyTest.kt`

- [ ] **Step 1: Write `maintenance/ReminderPolicy.kt`** (pure — the Worker stays a thin shell):

```kotlin
package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.health.HealthTuning

// Spec §6.4: notify when DUE_SOON/OVERDUE and (never notified OR last
// notified >= RENOTIFY_DAYS ago). Disabled items never notify. Marking a
// service done clears lastNotifiedAtEpochMs (repository), so the next
// due cycle notifies fresh.
object ReminderPolicy {
    data class Decision(val item: MaintenanceItemEntity, val info: DueInfo)

    fun itemsToNotify(evaluations: List<Decision>, nowMs: Long): List<Decision> =
        evaluations.filter { (item, info) ->
            item.enabled &&
                (info.state == DueState.DUE_SOON || info.state == DueState.OVERDUE) &&
                (item.lastNotifiedAtEpochMs == null ||
                    nowMs - item.lastNotifiedAtEpochMs >= HealthTuning.RENOTIFY_DAYS * DAY_MS)
        }

    const val DAY_MS = 86_400_000L
}
```

- [ ] **Step 2: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/maintenance/ReminderPolicyTest.kt`:

```kotlin
package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderPolicyTest {

    private fun item(id: Long, enabled: Boolean = true, lastNotified: Long? = null) =
        MaintenanceItemEntity(
            id = id, name = "Item $id", category = "ENGINE", intervalKm = 10_000,
            intervalMonths = null, note = "", confidence = "Best estimate",
            builtin = true, enabled = enabled, lastNotifiedAtEpochMs = lastNotified,
        )

    private fun info(state: DueState) = DueInfo(state, remainingKm = 0, remainingDays = null, kmUntracked = false)

    @Test
    fun notifiesDueSoonAndOverdue_skipsOkAndDisabled() {
        val decisions = listOf(
            ReminderPolicy.Decision(item(1), info(DueState.DUE_SOON)),
            ReminderPolicy.Decision(item(2), info(DueState.OVERDUE)),
            ReminderPolicy.Decision(item(3), info(DueState.OK)),
            ReminderPolicy.Decision(item(4, enabled = false), info(DueState.OVERDUE)),
            ReminderPolicy.Decision(item(5), info(DueState.NEVER_LOGGED)),
            ReminderPolicy.Decision(item(6), info(DueState.KM_UNTRACKED)),
        )
        val notify = ReminderPolicy.itemsToNotify(decisions, nowMs = 0)
        assertEquals(listOf(1L, 2L), notify.map { it.item.id })
    }

    @Test
    fun renotify_gate_isSevenDays() {
        val now = 100L * ReminderPolicy.DAY_MS
        val sixDaysAgo = now - 6 * ReminderPolicy.DAY_MS
        val sevenDaysAgo = now - 7 * ReminderPolicy.DAY_MS
        val decisions = listOf(
            ReminderPolicy.Decision(item(1, lastNotified = sixDaysAgo), info(DueState.OVERDUE)),
            ReminderPolicy.Decision(item(2, lastNotified = sevenDaysAgo), info(DueState.OVERDUE)),
            ReminderPolicy.Decision(item(3, lastNotified = null), info(DueState.OVERDUE)),
        )
        val notify = ReminderPolicy.itemsToNotify(decisions, nowMs = now)
        assertEquals(listOf(2L, 3L), notify.map { it.item.id })
    }
}
```

- [ ] **Step 3: Run it** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.maintenance.ReminderPolicyTest"
```
Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 4: Write `maintenance/MaintenanceCheckWorker.kt`:**

```kotlin
package com.kompressorlink.app.maintenance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kompressorlink.app.AppContainer
import com.kompressorlink.app.KompressorLinkApp
import com.kompressorlink.app.MainActivity
import java.time.ZoneId
import java.util.concurrent.TimeUnit

// Thin shell around the pure ReminderPolicy (spec §6.4). Daily periodic
// work; also invocable on demand via the overflow menu's debug
// "Run reminder check now" (the acceptance-test hook — One UI may throttle
// periodic work [Likely], and the tab never lies regardless).
class MaintenanceCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? KompressorLinkApp ?: return Result.success()
        runCheck(app.container, applicationContext)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "kl-maintenance-check"
        const val CHANNEL_ID = "maintenance_reminders"
        const val EXTRA_START_TAB = "kl.start_tab"
        const val TAB_MAINTENANCE = "maintenance"

        suspend fun runCheck(container: AppContainer, context: Context) {
            val nowMs = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val anchor = container.odometerRepository.latestAnchor()
            val distance = anchor?.let { container.sessionRepository.realDistanceSince(it.epochMs) } ?: 0f
            val estimatedKm = OdometerEstimator.estimate(anchor, distance)?.km

            val decisions = container.maintenanceRepository.items().map { item ->
                ReminderPolicy.Decision(
                    item,
                    DueCalculator.evaluate(
                        item, container.maintenanceRepository.latestLogFor(item.id),
                        estimatedKm, nowMs, zone,
                    ),
                )
            }

            val manager = NotificationManagerCompat.from(context)
            for (decision in ReminderPolicy.itemsToNotify(decisions, nowMs)) {
                if (manager.areNotificationsEnabled()) {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        putExtra(EXTRA_START_TAB, TAB_MAINTENANCE)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    val pending = PendingIntent.getActivity(
                        context, decision.item.id.toInt(), intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    val text = when (decision.info.state) {
                        DueState.OVERDUE -> "${decision.item.name} is overdue"
                        else -> "${decision.item.name} is due soon"
                    }
                    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle("Maintenance reminder")
                        .setContentText(text)
                        .setContentIntent(pending)
                        .setAutoCancel(true)
                        .build()
                    manager.notify(decision.item.id.toInt(), notification)
                }
                // Stamp even if notifications are disabled: the state was
                // surfaced as well as this device allows; don't queue a
                // burst for the day the user flips notifications on.
                container.maintenanceRepository.stampNotified(decision.item.id, nowMs)
            }
        }

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Maintenance reminders", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MaintenanceCheckWorker>(24, TimeUnit.HOURS).build(),
            )
        }
    }
}
```

- [ ] **Step 5: Full replacement of `KompressorLinkApp.kt`** — identical to Task 15's version except the `KompressorLinkApp` class becomes:

```kotlin
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
```

with `com.kompressorlink.app.maintenance.MaintenanceCheckWorker` added to the imports. Everything else stays exactly as Task 15 wrote it.

**Honesty note (goes in the task report):** the Worker's notification path is verified by compilation + the manual acceptance hook (Task 26), not by unit test — WorkManager scheduling and One UI delivery are not JVM-testable. The decision logic IS unit-tested (Step 2).

- [ ] **Step 6: Full suite + APK:**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 7: Leave for review.**

---

## Task 18: MaintenanceViewModel

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/maintenance/MaintenanceViewModel.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/maintenance/MaintenanceViewModelTest.kt`

- [ ] **Step 1: Write `maintenance/MaintenanceViewModel.kt`:**

```kotlin
package com.kompressorlink.app.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.data.MaintenanceRepository
import com.kompressorlink.app.data.OdometerRepository
import com.kompressorlink.app.data.SessionRepository
import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OdometerUi(
    val estimatedKm: Int?,        // null -> "Enter your odometer"
    val anchorKm: Int?,
    val anchorEpochMs: Long?,
    val estimatedDeltaKm: Int,
)

data class MaintenanceItemUi(
    val item: MaintenanceItemEntity,
    val info: DueInfo,
    val lastLog: ServiceLogEntity?,
)

data class MaintenanceUiState(
    val odometer: OdometerUi,
    val items: List<MaintenanceItemUi>,
    val message: String?,          // validation errors surfaced to the UI
)

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceViewModel(
    private val maintenance: MaintenanceRepository,
    private val odometer: OdometerRepository,
    private val sessions: SessionRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val refresh = MutableStateFlow(0)  // bumped after writes that flows don't carry

    val uiState: StateFlow<MaintenanceUiState> =
        combine(maintenance.observeItems(), odometer.observeLatestAnchor(), message, refresh) {
                items, anchor, msg, _ ->
            Triple(items, anchor, msg)
        }.mapLatest { (items, anchor, msg) ->
            val distance = anchor?.let { sessions.realDistanceSince(it.epochMs) } ?: 0f
            val estimate = OdometerEstimator.estimate(anchor, distance)
            val nowMs = now()
            val rows = items.map { item ->
                val lastLog = maintenance.latestLogFor(item.id)
                MaintenanceItemUi(item, DueCalculator.evaluate(item, lastLog, estimate?.km, nowMs, zone), lastLog)
            }.sortedWith(
                compareBy<MaintenanceItemUi> { stateOrder(it.info.state) }.thenBy { it.item.name }
            )
            MaintenanceUiState(
                odometer = OdometerUi(
                    estimatedKm = estimate?.km,
                    anchorKm = estimate?.anchorKm,
                    anchorEpochMs = estimate?.anchorEpochMs,
                    estimatedDeltaKm = estimate?.estimatedDeltaKm ?: 0,
                ),
                items = rows,
                message = msg,
            )
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            MaintenanceUiState(OdometerUi(null, null, null, 0), emptyList(), null),
        )

    // Spec §8.5 display order.
    private fun stateOrder(state: DueState): Int = when (state) {
        DueState.OVERDUE -> 0
        DueState.DUE_SOON -> 1
        DueState.NEVER_LOGGED -> 2
        DueState.KM_UNTRACKED -> 3
        DueState.OK -> 4
    }

    fun updateOdometer(km: Int) {
        viewModelScope.launch {
            odometer.addAnchor(km, now())
                .onSuccess { message.value = null }
                .onFailure { message.value = it.message }
        }
    }

    fun logService(itemId: Long, epochMs: Long, km: Int?, note: String?) {
        viewModelScope.launch {
            maintenance.logService(itemId, epochMs, km, note)
            refresh.value++
        }
    }

    fun updateItem(item: MaintenanceItemEntity) {
        viewModelScope.launch {
            maintenance.updateItem(item).onFailure { message.value = it.message }
        }
    }

    fun addCustomItem(name: String, category: String, intervalKm: Int?, intervalMonths: Int?, note: String) {
        viewModelScope.launch {
            maintenance.addCustomItem(
                MaintenanceItemEntity(
                    name = name, category = category, intervalKm = intervalKm,
                    intervalMonths = intervalMonths, note = note, confidence = "Best estimate",
                    builtin = false, enabled = true, lastNotifiedAtEpochMs = null,
                )
            ).onFailure { message.value = it.message }
        }
    }

    fun deleteCustomItem(id: Long) {
        viewModelScope.launch { maintenance.deleteCustomItem(id) }
    }

    fun clearMessage() {
        message.value = null
    }
}
```

- [ ] **Step 2: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/maintenance/MaintenanceViewModelTest.kt`:

```kotlin
package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.FakeMaintenanceRepository
import com.kompressorlink.app.data.FakeOdometerRepository
import com.kompressorlink.app.data.FakeSessionRepository
import com.kompressorlink.app.data.db.SessionEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceViewModelTest {

    private val zone = ZoneId.of("UTC")
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun buildVm(
        maintenance: FakeMaintenanceRepository,
        odometer: FakeOdometerRepository = FakeOdometerRepository(),
        sessions: FakeSessionRepository = FakeSessionRepository(),
        nowDate: LocalDate = LocalDate.of(2026, 7, 13),
    ) = MaintenanceViewModel(maintenance, odometer, sessions, now = { ms(nowDate) }, zone = zone)

    @Test
    fun seededItems_sortByUrgency_thenName() = runTest(dispatcher) {
        val maintenance = FakeMaintenanceRepository()
        maintenance.ensureSeeded()
        val odometer = FakeOdometerRepository()
        odometer.addAnchor(187_000, ms(LocalDate.of(2026, 7, 1)))
        val vm = buildVm(maintenance, odometer)
        val collector = launch { vm.uiState.collect {} }  // keep WhileSubscribed alive
        runCurrent()

        // Overdue brake fluid (26 months ago), due-soon oil (11.5 months ago),
        // everything else never logged.
        val brake = maintenance.items().first { it.name == "Brake fluid" }
        val oil = maintenance.items().first { it.name == "Engine oil + filter" }
        maintenance.logService(brake.id, ms(LocalDate.of(2024, 5, 1)), km = null, note = null)
        maintenance.logService(oil.id, ms(LocalDate.of(2025, 8, 1)), km = 182_500, note = null)
        runCurrent()

        val state = vm.uiState.first { it.items.isNotEmpty() }
        assertEquals("Brake fluid", state.items[0].item.name)          // OVERDUE first
        assertEquals(DueState.OVERDUE, state.items[0].info.state)
        assertEquals("Engine oil + filter", state.items[1].item.name)  // DUE_SOON (19 days left)
        assertEquals(DueState.DUE_SOON, state.items[1].info.state)
        assertTrue(state.items.drop(2).all { it.info.state == DueState.NEVER_LOGGED })
        collector.cancel()
    }

    @Test
    fun odometerEstimate_combinesAnchorAndRealSessions() = runTest(dispatcher) {
        val maintenance = FakeMaintenanceRepository()
        maintenance.ensureSeeded()
        val odometer = FakeOdometerRepository()
        val anchorAt = ms(LocalDate.of(2026, 7, 1))
        odometer.addAnchor(186_900, anchorAt)
        val sessions = FakeSessionRepository()
        sessions.record(
            SessionEntity(
                startedAtEpochMs = anchorAt + 1_000, endedAtEpochMs = anchorAt + 2_000,
                source = "REAL_BLE", snapshotCount = 200, warmIdleSeconds = 0f,
                distanceKm = 550.4f, hasStoredDtc = false,
            )
        ) { emptyList() }
        val vm = buildVm(maintenance, odometer, sessions)
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        val state = vm.uiState.first { it.odometer.estimatedKm != null }
        assertEquals(187_450, state.odometer.estimatedKm)
        assertEquals(186_900, state.odometer.anchorKm)
        assertEquals(550, state.odometer.estimatedDeltaKm)
        collector.cancel()
    }

    @Test
    fun backwardsOdometer_surfacesMessage() = runTest(dispatcher) {
        val maintenance = FakeMaintenanceRepository()
        maintenance.ensureSeeded()
        val odometer = FakeOdometerRepository()
        odometer.addAnchor(186_900, ms(LocalDate.of(2026, 7, 1)))
        val vm = buildVm(maintenance, odometer)
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        vm.updateOdometer(150_000)
        runCurrent()
        val state = vm.uiState.first { it.message != null }
        assertTrue(state.message!!.contains("can't go backwards"))
        collector.cancel()
    }

    @Test
    fun loggingService_movesItemOutOfOverdue() = runTest(dispatcher) {
        val maintenance = FakeMaintenanceRepository()
        maintenance.ensureSeeded()
        val vm = buildVm(maintenance)
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        val brake = maintenance.items().first { it.name == "Brake fluid" }
        maintenance.logService(brake.id, ms(LocalDate.of(2024, 5, 1)), km = null, note = null)
        runCurrent()
        var state = vm.uiState.first { s -> s.items.any { it.info.state == DueState.OVERDUE } }
        assertEquals(DueState.OVERDUE, state.items[0].info.state)

        vm.logService(brake.id, ms(LocalDate.of(2026, 7, 13)), km = null, note = "flushed")
        runCurrent()
        state = vm.uiState.first { s -> s.items.none { it.info.state == DueState.OVERDUE } }
        val brakeRow = state.items.first { it.item.name == "Brake fluid" }
        assertEquals(DueState.OK, brakeRow.info.state)
        assertEquals("flushed", brakeRow.lastLog!!.note)
        collector.cancel()
    }
}
```

- [ ] **Step 3: Run it** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.maintenance.MaintenanceViewModelTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 4: Full suite green.** Leave for review.

---

## Task 19: HealthViewModel

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/HealthViewModel.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/health/HealthViewModelTest.kt`

- [ ] **Step 1: Write `health/HealthViewModel.kt`:**

```kotlin
package com.kompressorlink.app.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.SessionRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.WarningRepository
import com.kompressorlink.app.data.WarningSource
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WarningUi(
    val id: Long,
    val title: String,
    val detail: String,
    val level: String,        // "WATCH" | "ATTENTION"
    val timeText: String,     // "Jul 13 18:42"
    val acknowledged: Boolean,
)

data class HealthUiState(
    val demoMode: Boolean,
    val verdict: OverallVerdict,
    val cards: List<SubsystemCard>,
    val warnings: List<WarningUi>,
    val driftGateFooter: String?,  // spec §8.3 footer while drift is globally gated
    val emptyState: String?,       // zero sessions in the selected mode
)

// Health screen state (spec §8.3). Demo mode swaps every DB query to
// SIM-sourced rows under the loud ribbon; the live layer (levels + DTC
// report) always reflects the active source. Not persisted — defaults off.
@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModel(
    private val sessions: SessionRepository,
    private val warningRepo: WarningRepository,
    liveLevels: StateFlow<Map<Signal, GaugeLevel>>,
    source: TelemetrySource,
    private val refs: ReferenceRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val demo = MutableStateFlow(false)

    fun setDemoMode(on: Boolean) {
        demo.value = on
    }

    fun acknowledge(id: Long) {
        viewModelScope.launch { warningRepo.acknowledge(id) }
    }

    val uiState: StateFlow<HealthUiState> = demo.flatMapLatest { demoOn ->
        val sourceSet = if (demoOn) SessionSource.SIM else SessionSource.REAL
        val warningSource = if (demoOn) WarningSource.SIM else WarningSource.REAL
        combine(
            sessions.observeRecent(sourceSet, HealthTuning.BASELINE_WINDOW + 10),
            warningRepo.observe(warningSource, 50),
            liveLevels,
            source.dtcReport,
        ) { sessionRows, warningRows, levels, dtc ->
            Inputs(demoOn, sessionRows, warningRows, levels, dtc)
        }
    }.mapLatest { inputs ->
        build(inputs)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        HealthUiState(
            demoMode = false,
            verdict = OverallVerdict(HealthStatus.OK, "All systems look normal", "No warnings from your car's data"),
            cards = emptyList(), warnings = emptyList(),
            driftGateFooter = null, emptyState = null,
        ),
    )

    private data class Inputs(
        val demo: Boolean,
        val sessions: List<com.kompressorlink.app.data.db.SessionEntity>,
        val warnings: List<WarningEntity>,
        val levels: Map<Signal, GaugeLevel>,
        val dtc: com.kompressorlink.app.telemetry.DtcReport?,
    )

    private suspend fun build(inputs: Inputs): HealthUiState {
        val ordered = inputs.sessions.sortedBy { it.endedAtEpochMs }
        val stats = sessions.statsForSessions(ordered.map { it.id })

        val driftMap = HashMap<MetricId, Drift.Result>()
        val baselineMap = HashMap<MetricId, Baseline.Result>()
        for (metric in MetricId.entries) {
            val band = MetricSeries.bandFor(metric, refs) ?: continue
            val points = MetricSeries.build(metric, ordered, stats)
            baselineMap[metric] = Baseline.evaluate(points, band)
            driftMap[metric] = Drift.evaluate(metric, points, band)
        }

        val openWarnings = inputs.warnings.filter { !it.acknowledged }
        val cards = HealthRollup.cards(inputs.levels, openWarnings, inputs.dtc, driftMap, baselineMap)
        val verdict = HealthRollup.overall(cards)

        val anyDrifting = driftMap.values.any { it is Drift.Result.Drifting }
        val anyGated = driftMap.values.any { it is Drift.Result.Gated }
        val footer = if (!anyDrifting && anyGated) {
            "Drift forecasts unlock after ${HealthTuning.DRIFT_MIN_POINTS} drives across 2 weeks."
        } else null

        val formatter = DateTimeFormatter.ofPattern("MMM d HH:mm")
        val warningUis = inputs.warnings.map { w ->
            WarningUi(
                id = w.id, title = w.title, detail = w.detail, level = w.level,
                timeText = Instant.ofEpochMilli(w.lastSeenAtEpochMs).atZone(zone).format(formatter),
                acknowledged = w.acknowledged,
            )
        }

        val empty = if (inputs.sessions.isEmpty()) {
            if (inputs.demo) {
                "No simulated sessions yet — pick a simulated source on the Dashboard and let it run for a minute or two."
            } else {
                "No real drives recorded yet. Health fills in once the device streams from the car — until then, Demo mode previews everything with simulated data."
            }
        } else null

        return HealthUiState(inputs.demo, verdict, cards, warningUis, footer, empty)
    }
}
```

- [ ] **Step 2: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/health/HealthViewModelTest.kt`:

```kotlin
package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.FakeSessionRepository
import com.kompressorlink.app.data.FakeWarningRepository
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {

    private val refs = ReferenceRepository { name -> File("src/main/assets/$name").readText() }
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class StubSource(dtc: DtcReport? = null) : TelemetrySource {
        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Simulated("stub"))
        override val telemetry: Flow<TelemetrySnapshot> = MutableSharedFlow()
        override val dtcReport: StateFlow<DtcReport?> = MutableStateFlow(dtc)
        override suspend fun sendTimeSync() {}
    }

    private suspend fun addSimSession(repo: FakeSessionRepository, day: Int, ltft: Float) {
        val endedAt = day * Baseline.DAY_MS
        repo.record(
            SessionEntity(
                startedAtEpochMs = endedAt - 600_000, endedAtEpochMs = endedAt,
                source = "SIM_HEALTHY", snapshotCount = 1200, warmIdleSeconds = 300f,
                distanceKm = null, hasStoredDtc = false,
            )
        ) { id ->
            listOf(SessionStatEntity(
                sessionId = id, signal = "LTFT1", sampleCount = 1000,
                mean = ltft, min = ltft - 1, max = ltft + 1, stdDev = 0.5f,
                secondsOutOfBand = 0f, worstLevel = "OK",
                warmIdleMean = ltft, warmIdleCount = 200,
                engineRunningMean = null, engineRunningCount = 0,
                engineOffMean = null, engineOffCount = 0,
            ))
        }
    }

    private fun vm(
        sessions: FakeSessionRepository = FakeSessionRepository(),
        warnings: FakeWarningRepository = FakeWarningRepository(),
        levels: MutableStateFlow<Map<Signal, GaugeLevel>> = MutableStateFlow(emptyMap()),
        source: TelemetrySource = StubSource(),
    ) = HealthViewModel(sessions, warnings, levels, source, refs, ZoneId.of("UTC"))

    @Test
    fun emptyRealHistory_showsHonestEmptyState() = runTest(dispatcher) {
        val viewModel = vm()
        val collector = launch { viewModel.uiState.collect {} }
        runCurrent()
        val state = viewModel.uiState.first { it.cards.isNotEmpty() }
        assertNotNull(state.emptyState)
        assertTrue(state.emptyState!!.contains("Demo mode"))
        assertEquals(HealthStatus.OK, state.verdict.status)
        collector.cancel()
    }

    @Test
    fun demoMode_switchesToSimRows() = runTest(dispatcher) {
        val sessions = FakeSessionRepository()
        (0 until 6).forEach { addSimSession(sessions, day = it * 3, ltft = 4f) }
        val viewModel = vm(sessions = sessions)
        val collector = launch { viewModel.uiState.collect {} }
        runCurrent()

        var state = viewModel.uiState.first { it.cards.isNotEmpty() }
        assertNotNull(state.emptyState)  // real mode: nothing recorded

        viewModel.setDemoMode(true)
        runCurrent()
        state = viewModel.uiState.first { it.demoMode }
        assertEquals(null, state.emptyState)
        // 6 eligible sim sessions -> baseline still gated, footer explains drift gate
        val fueling = state.cards.first { it.subsystem == Subsystem.FUELING }
        assertEquals("Collecting baseline · 6 of 8 drives", fueling.baselineGate)
        assertNotNull(state.driftGateFooter)
        collector.cancel()
    }

    @Test
    fun storedDtc_drivesVerdictThroughRollup() = runTest(dispatcher) {
        val viewModel = vm(source = StubSource(DtcReport(stored = listOf("P0171"), pending = emptyList())))
        val collector = launch { viewModel.uiState.collect {} }
        runCurrent()
        val state = viewModel.uiState.first { it.verdict.status == HealthStatus.ATTENTION }
        assertEquals("Needs a look", state.verdict.headline)
        assertEquals("1 system needs attention — Fueling", state.verdict.subline)
        collector.cancel()
    }

    @Test
    fun acknowledge_flowsToRepository_andFeedDims() = runTest(dispatcher) {
        val warnings = FakeWarningRepository()
        warnings.raise(WarningEntity(
            createdAtEpochMs = 1_000, lastSeenAtEpochMs = 1_000, sessionId = null,
            subsystem = "FUELING", signal = "LTFT1", level = "WATCH", kind = "LIVE_OUT_OF_BAND",
            title = "LTFT outside its band", detail = "d", acknowledged = false,
            source = "REAL", dedupeKey = "k",
        ))
        val viewModel = vm(warnings = warnings)
        val collector = launch { viewModel.uiState.collect {} }
        runCurrent()

        var state = viewModel.uiState.first { it.warnings.isNotEmpty() }
        assertEquals(false, state.warnings[0].acknowledged)

        viewModel.acknowledge(state.warnings[0].id)
        runCurrent()
        state = viewModel.uiState.first { it.warnings.isNotEmpty() && it.warnings[0].acknowledged }
        assertEquals(true, state.warnings[0].acknowledged)
        collector.cancel()
    }
}
```

- [ ] **Step 3: Run it** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.health.HealthViewModelTest"
```
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 4: Full suite green.** Leave for review.

---
## Task 20: Theme + shared UI components

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/ui/theme/Theme.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/ui/components/Components.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/ui/components/ComponentsLogicTest.kt`

- [ ] **Step 1: Write `ui/theme/Theme.kt`:**

```kotlin
package com.kompressorlink.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.health.HealthStatus

// Phase 4.5 dark automotive theme (spec §8; the mockup
// docs/superpowers/specs/2026-07-13-phase45-ui-mockup.html is the
// directional target). One semantic color set everywhere: gauges,
// subsystem cards, maintenance rows, DTC urgency.
object KlColors {
    val Ok = Color(0xFF4CAF7D)
    val Watch = Color(0xFFE0A83C)
    val Attention = Color(0xFFE05B4B)
    val Neutral = Color(0xFF6E8291)

    fun forLevel(level: GaugeLevel): Color = when (level) {
        GaugeLevel.OK -> Ok
        GaugeLevel.AMBER -> Watch
        GaugeLevel.RED -> Attention
        GaugeLevel.NEUTRAL, GaugeLevel.UNAVAILABLE -> Neutral
    }

    fun forStatus(status: HealthStatus): Color = when (status) {
        HealthStatus.OK -> Ok
        HealthStatus.WATCH -> Watch
        HealthStatus.ATTENTION -> Attention
    }
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FB6C9),
    secondary = KlColors.Neutral,
    background = Color(0xFF101416),
    surface = Color(0xFF1A1F22),
    surfaceVariant = Color(0xFF22282C),
    onBackground = Color(0xFFE8ECEE),
    onSurface = Color(0xFFE8ECEE),
    onSurfaceVariant = Color(0xFF9AA4AA),
    error = KlColors.Attention,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF33667E),
    secondary = KlColors.Neutral,
    error = KlColors.Attention,
)

// Big legible numerals for gauge values (night-driving legibility).
private val KlTypography = Typography().let {
    it.copy(headlineMedium = it.headlineMedium.copy(fontSize = 30.sp, fontWeight = FontWeight.Medium))
}

// Dark is the design target; follows the system setting (spec §8).
@Composable
fun KompressorLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = KlTypography,
        content = content,
    )
}
```

- [ ] **Step 2: Write `ui/components/Components.kt`** (the pure fractions/label functions exist so the drawing math is JVM-testable):

```kotlin
package com.kompressorlink.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.ui.theme.KlColors

// ── Pure logic (JVM-tested) ────────────────────────────────────────────

/** BandBar geometry (spec §8.2): the track spans [lo−w, hi+w] where
 *  w = band half-width — exactly the AMBER envelope. Returns the healthy
 *  zone's start/end and the clamped marker position as 0..1 fractions. */
data class BandBarFractions(val zoneStart: Float, val zoneEnd: Float, val marker: Float)

fun bandBarFractions(value: Float, lo: Float, hi: Float): BandBarFractions {
    val w = (hi - lo) / 2f
    val trackLo = lo - w
    val trackHi = hi + w
    val span = trackHi - trackLo
    fun frac(x: Float) = ((x - trackLo) / span).coerceIn(0f, 1f)
    return BandBarFractions(zoneStart = frac(lo), zoneEnd = frac(hi), marker = frac(value))
}

/** Connection banner wording (spec §8.1). */
fun connectionBannerText(state: ConnectionState): String = when (state) {
    is ConnectionState.Simulated -> "Simulated · ${state.scenarioName}"
    is ConnectionState.Ready -> when {
        state.demo -> "Connected · demo device"
        state.klineConnected -> "Connected · K-line up"
        else -> "Connected · K-line down"
    }
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.NotAssociated -> "No device paired yet"
    ConnectionState.ProtocolMismatch -> "Protocol mismatch — update app/firmware"
}

fun connectionBannerColor(state: ConnectionState): Color = when (state) {
    is ConnectionState.Ready -> KlColors.Ok
    is ConnectionState.Simulated -> KlColors.Neutral
    ConnectionState.Connecting -> KlColors.Watch
    else -> KlColors.Attention
}

// ── Composables (compile-verified; no Compose UI tests this phase) ─────

@Composable
fun ConnectionBanner(state: ConnectionState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(connectionBannerColor(state), CircleShape))
            Text(
                connectionBannerText(state),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
fun BandBar(value: Float, lo: Float, hi: Float, markerColor: Color) {
    val fractions = bandBarFractions(value, lo, hi)
    Box(
        Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
    ) {
        Box(
            Modifier.fillMaxWidth(fractions.zoneEnd).height(6.dp)
                .background(Color.Transparent),
        ) {
            Box(
                Modifier.align(Alignment.CenterEnd)
                    .fillMaxWidth((fractions.zoneEnd - fractions.zoneStart) / fractions.zoneEnd.coerceAtLeast(0.0001f))
                    .height(6.dp)
                    .background(KlColors.Ok.copy(alpha = 0.35f), RoundedCornerShape(3.dp)),
            )
        }
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .padding(start = 0.dp)
                    .fillMaxWidth(fractions.marker.coerceAtLeast(0.02f)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.size(10.dp).background(markerColor, CircleShape))
            }
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun Sparkline(history: List<Float>) {
    if (history.size < 2) return
    val min = history.min()
    val max = history.max()
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(24.dp).padding(top = 4.dp)) {
        val stepX = size.width / (history.size - 1)
        val path = Path()
        history.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height * (1f - (v - min) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 3f))
    }
}
```

- [ ] **Step 3: Write the failing tests** at `android/app/src/test/java/com/kompressorlink/app/ui/components/ComponentsLogicTest.kt`:

```kotlin
package com.kompressorlink.app.ui.components

import com.kompressorlink.app.telemetry.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentsLogicTest {
    @Test
    fun bandBar_zoneOccupiesMiddleHalf() {
        // Band 3..5 (w=1): track 2..6; zone = 0.25..0.75.
        val f = bandBarFractions(value = 4f, lo = 3f, hi = 5f)
        assertEquals(0.25f, f.zoneStart, 1e-4f)
        assertEquals(0.75f, f.zoneEnd, 1e-4f)
        assertEquals(0.5f, f.marker, 1e-4f)
    }

    @Test
    fun bandBar_markerClampsAtTrackEnds() {
        assertEquals(1f, bandBarFractions(99f, 3f, 5f).marker, 0f)
        assertEquals(0f, bandBarFractions(-99f, 3f, 5f).marker, 0f)
    }

    @Test
    fun bannerText_coversEveryState() {
        assertEquals("Simulated · healthy",
            connectionBannerText(ConnectionState.Simulated("healthy")))
        assertEquals("Connected · K-line up",
            connectionBannerText(ConnectionState.Ready(demo = false, klineConnected = true)))
        assertEquals("Connected · demo device",
            connectionBannerText(ConnectionState.Ready(demo = true, klineConnected = false)))
        assertEquals("Disconnected", connectionBannerText(ConnectionState.Disconnected))
        assertEquals("No device paired yet", connectionBannerText(ConnectionState.NotAssociated))
        assertEquals("Connecting…", connectionBannerText(ConnectionState.Connecting))
    }
}
```

- [ ] **Step 4: Run it** — FAIL before, PASS after:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.ui.components.ComponentsLogicTest"
```
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Full suite green** (theme/components compile; nothing consumes them yet). Leave for review.

---

## Task 21: DTC explanation system end-to-end — three layers, content, screen

The biggest single task: models + decoder + repository + BOTH content files + the rewritten DTC screen + the P1570 fault-scenario proof. Content wording rules are normative (spec §7.1): plain language, no scare tactics, no invented statistics, `check_first` ordered by W203 likelihood, meanings ≤ 2 sentences, every entry confidence-tagged, and nothing may contradict `docs/w203_reference.md`.

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/dtc/DtcModels.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/dtc/DtcFamilyDecoder.kt`
- Create: `android/app/src/main/java/com/kompressorlink/app/dtc/DtcRepository.kt`
- Modify: `android/app/src/main/assets/w203_dtc.json` (full replacement, new schema, 76 codes)
- Create: `android/app/src/main/assets/generic_dtc.json`
- Modify: `android/app/src/main/java/com/kompressorlink/app/reference/ReferenceRepository.kt` (bands only)
- Modify: `android/app/src/main/java/com/kompressorlink/app/dtc/DtcViewModel.kt` (rewrite)
- Modify: `android/app/src/main/java/com/kompressorlink/app/dtc/DtcScreen.kt` (rewrite)
- Modify: `android/app/src/main/java/com/kompressorlink/app/telemetry/FakeTelemetrySource.kt` (fault adds pending P1570)
- Modify: `android/app/src/main/java/com/kompressorlink/app/KompressorLinkApp.kt` + `MainActivity.kt` (two surgical lines, Step 9)
- Tests: `DtcFamilyDecoderTest.kt`, `DtcRepositoryTest.kt` (new); `DtcViewModelTest.kt` (rewrite); existing `ReferenceRepositoryTest.kt`/`FakeTelemetrySourceTest.kt` assertions updated

- [ ] **Step 1: Write `dtc/DtcModels.kt`:**

```kotlin
package com.kompressorlink.app.dtc

// Spec §7.1's urgency taxonomy with normative UI labels.
enum class DtcUrgency(val label: String) {
    INFO("Info"),
    CHECK_SOON("Check soon"),
    URGENT("Urgent — drive gently"),
    STOP("Stop driving"),
}

// Which layer answered — rendered as a chip so friendliness never blurs
// provenance (spec §7.3).
enum class DtcLayer(val chip: String) {
    W203("W203-specific"),
    GENERIC("Generic OBD-II"),
    STRUCTURAL("Code family only"),
}

data class DtcExplanation(
    val code: String,
    val title: String,
    val meaning: String,
    val urgency: DtcUrgency?,     // null only on the structural layer ("Unknown urgency")
    val canIDrive: String?,
    val checkFirst: List<String>,
    val note: String?,
    val confidence: String?,
    val layer: DtcLayer,
)
```

- [ ] **Step 2: Write `dtc/DtcFamilyDecoder.kt` + its failing test:**

```kotlin
package com.kompressorlink.app.dtc

// Layer 3 (spec §7.2): decodes the code's structure, never fabricates a
// meaning. "P1863 — manufacturer-specific powertrain code, transmission
// group."
object DtcFamilyDecoder {
    fun decode(code: String): DtcExplanation {
        val system = when (code.firstOrNull()) {
            'P' -> "powertrain"
            'C' -> "chassis"
            'B' -> "body"
            'U' -> "network"
            else -> null
        }
        val wellFormed = system != null && code.length == 5 &&
            code.drop(1).all { it.isDigit() || it in 'A'..'F' }
        if (!wellFormed) {
            return DtcExplanation(
                code = code, title = "Unrecognized code",
                meaning = "This doesn't look like a standard OBD-II code. It may be garbled — try reading it again.",
                urgency = null, canIDrive = null, checkFirst = emptyList(),
                note = null, confidence = null, layer = DtcLayer.STRUCTURAL,
            )
        }
        val generic = code[1] == '0'
        val family = if (code[0] == 'P') when (code[2]) {
            '0', '1' -> "fuel & air metering"
            '2' -> "fuel & air metering (injector circuit)"
            '3' -> "ignition system / misfire"
            '4' -> "auxiliary emission controls"
            '5' -> "vehicle speed & idle control"
            '6' -> "computer & output circuits"
            '7', '8', '9' -> "transmission"
            else -> "hybrid / propulsion"
        } else null
        val title = buildString {
            append(if (generic) "Generic " else "Manufacturer-specific ")
            append(system)
            append(" code")
            if (family != null) {
                append(", ")
                append(family)
                append(" group")
            }
        }
        return DtcExplanation(
            code = code, title = title,
            meaning = "No detailed entry for this code — have it read with an MB-capable scanner for the exact meaning.",
            urgency = null, canIDrive = null, checkFirst = emptyList(),
            note = null, confidence = null, layer = DtcLayer.STRUCTURAL,
        )
    }
}
```

Test at `android/app/src/test/java/com/kompressorlink/app/dtc/DtcFamilyDecoderTest.kt`:

```kotlin
package com.kompressorlink.app.dtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtcFamilyDecoderTest {
    @Test
    fun manufacturerTransmissionCode() {
        val d = DtcFamilyDecoder.decode("P1863")
        assertEquals("Manufacturer-specific powertrain code, transmission group", d.title)
        assertEquals(DtcLayer.STRUCTURAL, d.layer)
        assertNull(d.urgency)
    }

    @Test
    fun genericFamilies() {
        assertEquals("Generic powertrain code, ignition system / misfire group",
            DtcFamilyDecoder.decode("P0399").title)
        assertEquals("Generic powertrain code, vehicle speed & idle control group",
            DtcFamilyDecoder.decode("P0599").title)
        assertEquals("Generic chassis code", DtcFamilyDecoder.decode("C0035").title)
        assertEquals("Manufacturer-specific network code", DtcFamilyDecoder.decode("U1100").title)
    }

    @Test
    fun garbage_isCalledOut() {
        assertEquals("Unrecognized code", DtcFamilyDecoder.decode("XX!!").title)
        assertEquals("Unrecognized code", DtcFamilyDecoder.decode("P01").title)
    }
}
```

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.dtc.DtcFamilyDecoderTest"` → FAIL before the decoder exists, PASS after (3 tests).

- [ ] **Step 3: Full replacement of `android/app/src/main/assets/w203_dtc.json`** — the curated layer, 76 codes, new schema. Every entry follows the spec §7.1 wording rules; existing w203_reference.md interpretations are preserved (breather hoses stay FIRST for lean/trim codes). This is the complete file:

```json
[
  { "code": "P0010", "title": "Intake camshaft adjuster circuit", "urgency": "CHECK_SOON", "confidence": "Confirmed",
    "meaning": "The computer can't properly control the intake camshaft adjuster solenoid.",
    "can_i_drive": "Yes — expect slightly rough running and higher fuel use until fixed.",
    "check_first": ["Cam adjuster magnet — oil in the connector (the classic M271 cause)", "Wiring harness contamination from wicked oil", "Engine oil level and condition"],
    "note": "On the M271, oil wicking into the cam-magnet connector is by far the most common cause — check there before replacing parts." },
  { "code": "P0011", "title": "Intake camshaft timing over-advanced", "urgency": "CHECK_SOON", "confidence": "Confirmed",
    "meaning": "The intake camshaft is ahead of where the computer commanded it.",
    "can_i_drive": "Yes, gently — prolonged mistiming hurts economy and can stress the chain.",
    "check_first": ["Cam adjuster magnet — oil ingress", "Engine oil level and condition (adjusters are oil-pressure driven)", "Timing chain stretch if high mileage"],
    "note": "Shares the M271 cam-magnet oil-ingress root cause with P0010–P0014." },
  { "code": "P0012", "title": "Intake camshaft timing over-retarded", "urgency": "CHECK_SOON", "confidence": "Confirmed",
    "meaning": "The intake camshaft is behind where the computer commanded it.",
    "can_i_drive": "Yes, gently — same caution as P0011.",
    "check_first": ["Cam adjuster magnet — oil ingress", "Engine oil level and condition", "Timing chain stretch if high mileage"],
    "note": "Shares the M271 cam-magnet oil-ingress root cause with P0010–P0014." },
  { "code": "P0013", "title": "Exhaust camshaft adjuster circuit", "urgency": "CHECK_SOON", "confidence": "Confirmed",
    "meaning": "The computer can't properly control the exhaust camshaft adjuster solenoid.",
    "can_i_drive": "Yes — expect slightly rough running until fixed.",
    "check_first": ["Cam adjuster magnet — oil in the connector", "Wiring harness contamination", "Engine oil level and condition"],
    "note": "Same M271 cam-magnet family as P0010." },
  { "code": "P0014", "title": "Exhaust camshaft timing over-advanced", "urgency": "CHECK_SOON", "confidence": "Confirmed",
    "meaning": "The exhaust camshaft is ahead of where the computer commanded it.",
    "can_i_drive": "Yes, gently.",
    "check_first": ["Cam adjuster magnet — oil ingress", "Engine oil level and condition", "Timing chain stretch if high mileage"],
    "note": "Same M271 cam-magnet family as P0010." },
  { "code": "P0016", "title": "Crank/cam position correlation (intake)", "urgency": "URGENT", "confidence": "Confirmed",
    "meaning": "The crankshaft and intake camshaft are out of step with each other — the engine's mechanical timing looks wrong to the computer.",
    "can_i_drive": "Short, gentle trips only — if you hear a rattle at cold start, stop driving and have the chain inspected.",
    "check_first": ["Timing chain stretch (listen for cold-start rattle)", "Cam adjuster magnet", "Crank/cam sensor plausibility"],
    "note": "Chain stretch is a known M271 aging issue — this code deserves prompt attention, not a cleared-and-forgotten." },
  { "code": "P0017", "title": "Crank/cam position correlation (exhaust)", "urgency": "URGENT", "confidence": "Confirmed",
    "meaning": "The crankshaft and exhaust camshaft are out of step with each other.",
    "can_i_drive": "Short, gentle trips only — same caution as P0016.",
    "check_first": ["Timing chain stretch (listen for cold-start rattle)", "Cam adjuster magnet", "Crank/cam sensor plausibility"],
    "note": "Chain stretch is a known M271 aging issue." },
  { "code": "P0068", "title": "MAF/MAP vs throttle disagreement", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The airflow, pressure, and throttle sensors are telling the computer stories that don't add up.",
    "can_i_drive": "Yes — power may feel off while the computer distrusts its sensors.",
    "check_first": ["Intake/breather leaks between MAF and manifold", "MAF sensor readings", "Throttle body condition"] },
  { "code": "P0100", "title": "MAF sensor circuit", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The mass-airflow sensor's signal is faulty or missing.",
    "can_i_drive": "Yes — the computer falls back to estimates, so expect duller response and worse economy.",
    "check_first": ["MAF connector and wiring", "MAF sensor itself", "Intake leaks skewing readings"] },
  { "code": "P0101", "title": "MAF reading implausible", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The airflow reading doesn't match what the engine should be breathing.",
    "can_i_drive": "Yes — watch fuel trims on the dashboard; they usually drift with this code.",
    "check_first": ["Breather/intake leaks after the MAF (the M271 classic)", "MAF sensor contamination", "Air filter condition"] },
  { "code": "P0102", "title": "MAF signal too low", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The airflow sensor reports less air than physically plausible.",
    "can_i_drive": "Yes, with duller response.",
    "check_first": ["MAF connector and wiring", "Intake leaks before the sensor", "MAF sensor itself"] },
  { "code": "P0103", "title": "MAF signal too high", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The airflow sensor reports more air than physically plausible.",
    "can_i_drive": "Yes, with duller response.",
    "check_first": ["MAF connector and wiring", "MAF sensor itself"] },
  { "code": "P0110", "title": "Intake air temperature circuit", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The intake air temperature signal is faulty or missing.",
    "can_i_drive": "Yes — mixture may be slightly off in extreme weather.",
    "check_first": ["IAT connector and wiring (part of the MAF on this engine)", "Sensor itself"] },
  { "code": "P0111", "title": "Intake air temperature implausible", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The intake air temperature reading doesn't behave the way real air does.",
    "can_i_drive": "Yes.",
    "check_first": ["IAT/MAF connector", "Sensor drift"] },
  { "code": "P0112", "title": "Intake air temperature reads too hot", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The IAT signal is stuck at the hot end of its range (usually a short).",
    "can_i_drive": "Yes.",
    "check_first": ["Wiring short to ground", "Sensor itself"] },
  { "code": "P0113", "title": "Intake air temperature reads too cold", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The IAT signal is stuck at the cold end of its range (usually an open circuit).",
    "can_i_drive": "Yes.",
    "check_first": ["Unplugged/damaged connector", "Broken wiring", "Sensor itself"] },
  { "code": "P0115", "title": "Coolant temperature circuit", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The coolant temperature signal is faulty or missing — the computer is guessing engine temperature.",
    "can_i_drive": "Yes, but watch the temperature gauge yourself — the computer can't.",
    "check_first": ["ECT sensor connector", "Sensor itself", "Wiring"] },
  { "code": "P0116", "title": "Coolant temperature implausible", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "Coolant temperature isn't behaving the way a warming engine should.",
    "can_i_drive": "Yes — often this is the sensor, occasionally the thermostat.",
    "check_first": ["Thermostat stuck open (check warm-up time on the Health tab)", "ECT sensor drift"] },
  { "code": "P0117", "title": "Coolant temperature reads too hot", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The ECT signal is stuck at the hot end (usually a short), not necessarily a real overheat.",
    "can_i_drive": "Yes IF the actual temperature gauge is normal — if the gauge is also hot, stop and check coolant.",
    "check_first": ["Wiring short", "Sensor itself"] },
  { "code": "P0118", "title": "Coolant temperature reads too cold", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The ECT signal is stuck at the cold end (usually an open circuit).",
    "can_i_drive": "Yes — cold-start enrichment may linger, hurting economy.",
    "check_first": ["Unplugged/damaged connector", "Broken wiring", "Sensor itself"] },
  { "code": "P0120", "title": "Throttle position sensor circuit", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The throttle position signal is faulty or missing.",
    "can_i_drive": "Carefully — the computer may limit power (limp mode) if it distrusts the throttle.",
    "check_first": ["Throttle body connector", "Wiring", "Throttle body itself"] },
  { "code": "P0121", "title": "Throttle position implausible", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The throttle position doesn't match airflow and engine behavior.",
    "can_i_drive": "Carefully — limp mode is possible.",
    "check_first": ["Throttle body condition/carbon", "Connector and wiring"] },
  { "code": "P0122", "title": "Throttle position signal too low", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The TPS signal is stuck low (usually wiring or the sensor).",
    "can_i_drive": "Carefully — limp mode is possible.",
    "check_first": ["Connector and wiring", "Throttle body"] },
  { "code": "P0123", "title": "Throttle position signal too high", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The TPS signal is stuck high (usually wiring or the sensor).",
    "can_i_drive": "Carefully — limp mode is possible.",
    "check_first": ["Connector and wiring", "Throttle body"] },
  { "code": "P0125", "title": "Engine slow to reach operating temperature", "urgency": "CHECK_SOON", "confidence": "Confirmed",
    "meaning": "The engine took too long to warm up for the fuel system to run in its efficient closed-loop mode.",
    "can_i_drive": "Yes — the cost is fuel and emissions, not damage.",
    "check_first": ["Thermostat stuck open (the usual cause — opens at 87 °C on this engine)", "ECT sensor drift", "Low coolant level"] },
  { "code": "P0128", "title": "Thermostat — coolant below regulating temperature", "urgency": "CHECK_SOON", "confidence": "Confirmed",
    "meaning": "Coolant never reached the temperature the thermostat should hold — it's almost always the thermostat aging open.",
    "can_i_drive": "Yes — replace the thermostat at your convenience; economy suffers meanwhile.",
    "check_first": ["Thermostat (87 °C on this engine)", "ECT sensor as a distant second"] },
  { "code": "P0130", "title": "Front O2 sensor circuit", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The pre-catalyst oxygen sensor's signal is faulty — mixture control degrades to estimates.",
    "can_i_drive": "Yes — expect worse economy; long-term the catalyst suffers from poor mixture control.",
    "check_first": ["Sensor connector under the car (heat-damaged wiring is common)", "The sensor itself", "Exhaust leaks upstream"] },
  { "code": "P0131", "title": "Front O2 sensor stuck lean", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The front oxygen sensor reads persistently lean (low voltage).",
    "can_i_drive": "Yes — but if trims are also high, treat it as a real lean condition (breather hoses first).",
    "check_first": ["Real lean condition — breather hoses/intake leaks", "Sensor wiring", "Sensor aging"] },
  { "code": "P0132", "title": "Front O2 sensor stuck rich", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The front oxygen sensor reads persistently rich (high voltage).",
    "can_i_drive": "Yes — watch fuel trims for confirmation of a real rich condition.",
    "check_first": ["Real rich condition — MAF over-read, fuel pressure", "Sensor wiring", "Sensor aging"] },
  { "code": "P0133", "title": "Front O2 sensor responding slowly", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The front oxygen sensor still works but switches too slowly — a classic aging sensor.",
    "can_i_drive": "Yes — plan a sensor replacement; economy and emissions suffer meanwhile.",
    "check_first": ["Sensor age (they slow with mileage)", "Exhaust leaks", "Contamination from oil/coolant burning"] },
  { "code": "P0134", "title": "Front O2 sensor inactive", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The front oxygen sensor's signal isn't moving at all — the computer is running open-loop.",
    "can_i_drive": "Yes, with worse economy — don't leave it long, poor mixture control ages the catalyst.",
    "check_first": ["Heater circuit fuse/wiring (an unheated sensor never wakes)", "Sensor connector", "The sensor itself"] },
  { "code": "P0135", "title": "Front O2 sensor heater circuit", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The front oxygen sensor's heater isn't working, so the sensor stays asleep after cold starts.",
    "can_i_drive": "Yes — mixture control just takes much longer to come online each drive.",
    "check_first": ["Heater fuse", "Sensor connector and wiring", "The sensor itself"] },
  { "code": "P0136", "title": "Rear O2 sensor circuit", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The post-catalyst oxygen sensor's signal is faulty — this sensor mostly watches the catalyst.",
    "can_i_drive": "Yes — the engine runs normally; you just lose catalyst monitoring.",
    "check_first": ["Sensor connector and wiring", "The sensor itself"] },
  { "code": "P0137", "title": "Rear O2 sensor stuck lean", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The rear oxygen sensor reads persistently low.",
    "can_i_drive": "Yes.",
    "check_first": ["Exhaust leaks near the sensor", "Sensor wiring", "Sensor aging"] },
  { "code": "P0138", "title": "Rear O2 sensor stuck rich", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The rear oxygen sensor reads persistently high.",
    "can_i_drive": "Yes.",
    "check_first": ["Sensor wiring (check for chafing)", "A genuinely rich mixture — see fuel trims", "Sensor aging"] },
  { "code": "P0140", "title": "Rear O2 sensor inactive", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The rear oxygen sensor's signal isn't moving at all.",
    "can_i_drive": "Yes — engine behavior is unaffected; catalyst monitoring is blind.",
    "check_first": ["Heater circuit", "Connector", "The sensor itself"] },
  { "code": "P0141", "title": "Rear O2 sensor heater circuit", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The rear oxygen sensor's heater isn't working.",
    "can_i_drive": "Yes.",
    "check_first": ["Heater fuse", "Connector and wiring", "The sensor itself"] },
  { "code": "P0170", "title": "Fuel trim out of range (bank 1)", "urgency": "CHECK_SOON", "confidence": "Confirmed",
    "meaning": "The computer is having to add or remove more fuel than it should to keep the mixture right.",
    "can_i_drive": "Yes, for now — treat it like P0171/P0172 depending on which way the trims lean.",
    "check_first": ["Partial-load breather hoses (THE M271 fault)", "Intake leaks after the MAF", "MAF sensor readings", "Fuel pressure"],
    "note": "On this engine, cracked breather hoses cause trim codes far more often than a failing MAF." },
  { "code": "P0171", "title": "Engine running too lean (bank 1)", "urgency": "CHECK_SOON", "confidence": "Confirmed",
    "meaning": "The engine is getting more air than the fuel math expects, so the computer keeps adding fuel to compensate.",
    "can_i_drive": "Yes, for now — sustained lean running can slowly damage the catalyst.",
    "check_first": ["Partial-load breather hoses (THE M271 weak spot)", "Intake leaks after the MAF", "MAF sensor readings", "Fuel pressure"],
    "note": "On this engine, cracked breather hoses cause this code far more often than a failing MAF." },
  { "code": "P0172", "title": "Engine running too rich (bank 1)", "urgency": "CHECK_SOON", "confidence": "Confirmed",
    "meaning": "The engine is getting more fuel than the air justifies, so the computer keeps pulling fuel out.",
    "can_i_drive": "Yes, for now — a rich engine washes oil off cylinder walls and soots the catalyst over time.",
    "check_first": ["MAF over-reading (contamination)", "Fuel pressure regulator", "Leaking injector", "Stuck-open purge valve"] },
  { "code": "P0201", "title": "Injector circuit — cylinder 1", "urgency": "URGENT", "confidence": "Likely",
    "meaning": "The computer can't drive cylinder 1's injector properly — that cylinder may not be fueling.",
    "can_i_drive": "Gently and briefly — a dead cylinder means unburned mixture reaching the catalyst.",
    "check_first": ["Injector connector", "Wiring to the injector", "Injector coil resistance (12–16 Ω)"],
    "note": "Injector resistance spec from the owner's guide [Best estimate]." },
  { "code": "P0202", "title": "Injector circuit — cylinder 2", "urgency": "URGENT", "confidence": "Likely",
    "meaning": "The computer can't drive cylinder 2's injector properly.",
    "can_i_drive": "Gently and briefly — same catalyst caution as P0201.",
    "check_first": ["Injector connector", "Wiring", "Injector coil resistance (12–16 Ω)"] },
  { "code": "P0203", "title": "Injector circuit — cylinder 3", "urgency": "URGENT", "confidence": "Likely",
    "meaning": "The computer can't drive cylinder 3's injector properly.",
    "can_i_drive": "Gently and briefly.",
    "check_first": ["Injector connector", "Wiring", "Injector coil resistance (12–16 Ω)"] },
  { "code": "P0204", "title": "Injector circuit — cylinder 4", "urgency": "URGENT", "confidence": "Likely",
    "meaning": "The computer can't drive cylinder 4's injector properly.",
    "can_i_drive": "Gently and briefly.",
    "check_first": ["Injector connector", "Wiring", "Injector coil resistance (12–16 Ω)"] },
  { "code": "P0300", "title": "Random / multiple misfire", "urgency": "URGENT", "confidence": "Confirmed",
    "meaning": "More than one cylinder is intermittently failing to burn its mixture.",
    "can_i_drive": "Gently, short distances — if the engine light FLASHES, stop: raw fuel is actively damaging the catalyst.",
    "check_first": ["Ignition coils (very common on the M271)", "Spark plugs — correct type only", "Breather-hose vacuum leak causing a lean misfire", "Fuel delivery"],
    "note": "Correct plug: FR6MPP332 or NGK ILFR6A." },
  { "code": "P0301", "title": "Misfire — cylinder 1", "urgency": "URGENT", "confidence": "Confirmed",
    "meaning": "Cylinder 1 intermittently fails to burn its mixture.",
    "can_i_drive": "Gently, short distances — a flashing engine light means stop.",
    "check_first": ["Ignition coil (swap with another cylinder to test)", "Spark plug", "Injector", "Compression as a last resort"],
    "note": "Correct plug: FR6MPP332 or NGK ILFR6A." },
  { "code": "P0302", "title": "Misfire — cylinder 2", "urgency": "URGENT", "confidence": "Confirmed",
    "meaning": "Cylinder 2 intermittently fails to burn its mixture.",
    "can_i_drive": "Gently, short distances — a flashing engine light means stop.",
    "check_first": ["Ignition coil (swap test)", "Spark plug", "Injector", "Compression as a last resort"],
    "note": "Correct plug: FR6MPP332 or NGK ILFR6A." },
  { "code": "P0303", "title": "Misfire — cylinder 3", "urgency": "URGENT", "confidence": "Confirmed",
    "meaning": "Cylinder 3 intermittently fails to burn its mixture.",
    "can_i_drive": "Gently, short distances — a flashing engine light means stop.",
    "check_first": ["Ignition coil (swap test)", "Spark plug", "Injector", "Compression as a last resort"],
    "note": "Correct plug: FR6MPP332 or NGK ILFR6A." },
  { "code": "P0304", "title": "Misfire — cylinder 4", "urgency": "URGENT", "confidence": "Confirmed",
    "meaning": "Cylinder 4 intermittently fails to burn its mixture.",
    "can_i_drive": "Gently, short distances — a flashing engine light means stop.",
    "check_first": ["Ignition coil (swap test)", "Spark plug", "Injector", "Compression as a last resort"],
    "note": "Correct plug: FR6MPP332 or NGK ILFR6A." },
  { "code": "P0325", "title": "Knock sensor circuit", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The knock sensor's signal is faulty — the computer protects the engine by retarding ignition timing.",
    "can_i_drive": "Yes — expect slightly less power and economy while timing is conservative.",
    "check_first": ["Sensor connector and wiring", "Sensor torque (over/under-tightened sensors misread)", "The sensor itself"] },
  { "code": "P0327", "title": "Knock sensor signal low", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The knock sensor's output is too quiet to be believable.",
    "can_i_drive": "Yes — same conservative-timing effect as P0325.",
    "check_first": ["Connector and wiring", "Sensor mounting torque", "The sensor itself"] },
  { "code": "P0335", "title": "Crankshaft position sensor circuit", "urgency": "URGENT", "confidence": "Likely",
    "meaning": "The crank sensor's signal is faulty — this sensor is essential; without it the engine can stall or refuse to start.",
    "can_i_drive": "Only if it currently runs — expect possible stalling; fix promptly.",
    "check_first": ["Sensor connector", "Sensor itself (heat-related failure is typical)", "Reluctor/wiring"] },
  { "code": "P0340", "title": "Camshaft position sensor circuit", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The cam sensor's signal is faulty — starts may take longer while the computer works out engine position from the crank alone.",
    "can_i_drive": "Usually yes — longer cranking and possible roughness.",
    "check_first": ["Sensor connector (oil ingress — same M271 pattern as the cam magnets)", "Sensor itself", "Wiring"] },
  { "code": "P0341", "title": "Camshaft position implausible", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The cam sensor works but its signal doesn't line up with the crank's.",
    "can_i_drive": "Yes, gently — if paired with P0016/P0017, treat it as the chain-stretch case.",
    "check_first": ["Timing chain stretch if P0016/P0017 also present", "Sensor and connector", "Cam adjuster behavior"] },
  { "code": "P0350", "title": "Ignition coil circuit (unspecified)", "urgency": "URGENT", "confidence": "Likely",
    "meaning": "A coil's primary circuit is faulty — expect misfires on the affected cylinder.",
    "can_i_drive": "Gently, short distances — misfire cautions apply.",
    "check_first": ["Coil connectors", "Coil primary resistance (0.4–1.0 Ω)", "Wiring to the coil"],
    "note": "Coil resistance spec from the owner's guide [Best estimate]." },
  { "code": "P0351", "title": "Ignition coil circuit — cylinder 1", "urgency": "URGENT", "confidence": "Likely",
    "meaning": "Cylinder 1's coil primary circuit is faulty.",
    "can_i_drive": "Gently, short distances.",
    "check_first": ["Coil connector", "Coil primary resistance (0.4–1.0 Ω)", "Wiring"] },
  { "code": "P0352", "title": "Ignition coil circuit — cylinder 2", "urgency": "URGENT", "confidence": "Likely",
    "meaning": "Cylinder 2's coil primary circuit is faulty.",
    "can_i_drive": "Gently, short distances.",
    "check_first": ["Coil connector", "Coil primary resistance (0.4–1.0 Ω)", "Wiring"] },
  { "code": "P0353", "title": "Ignition coil circuit — cylinder 3", "urgency": "URGENT", "confidence": "Likely",
    "meaning": "Cylinder 3's coil primary circuit is faulty.",
    "can_i_drive": "Gently, short distances.",
    "check_first": ["Coil connector", "Coil primary resistance (0.4–1.0 Ω)", "Wiring"] },
  { "code": "P0354", "title": "Ignition coil circuit — cylinder 4", "urgency": "URGENT", "confidence": "Likely",
    "meaning": "Cylinder 4's coil primary circuit is faulty.",
    "can_i_drive": "Gently, short distances.",
    "check_first": ["Coil connector", "Coil primary resistance (0.4–1.0 Ω)", "Wiring"] },
  { "code": "P0410", "title": "Secondary air system", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The pump that feeds extra air into the exhaust during cold starts isn't doing its job.",
    "can_i_drive": "Yes — this is an emissions device; the engine itself is unaffected.",
    "check_first": ["Secondary air pump fuse and relay", "Pump itself (listen for it in the first ~90 s of a cold start)", "Combi/check valve stuck"] },
  { "code": "P0413", "title": "Secondary air valve circuit open", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The secondary air system's switching valve circuit is broken.",
    "can_i_drive": "Yes.",
    "check_first": ["Valve connector and wiring", "The valve itself"] },
  { "code": "P0418", "title": "Secondary air pump relay circuit", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The relay that powers the secondary air pump isn't switching properly.",
    "can_i_drive": "Yes.",
    "check_first": ["Relay", "Fuse", "Wiring to the pump"] },
  { "code": "P0420", "title": "Catalyst efficiency below threshold", "urgency": "CHECK_SOON", "confidence": "Confirmed",
    "meaning": "The rear oxygen sensor says the catalyst isn't cleaning the exhaust as well as it should.",
    "can_i_drive": "Yes — but find out WHY before spending money: a lean/misfire problem can make a good catalyst look bad.",
    "check_first": ["Verify with the O2 post-cat steadiness on the Dashboard before condemning the catalyst", "Exhaust leaks near the sensors", "Rear O2 sensor aging", "Only then: the catalyst itself"],
    "note": "The dedicated cat-health snapshot test arrives with the guided-tests phase; until then use the post-cat O2 gauge's steadiness as the cheap first check." },
  { "code": "P0440", "title": "EVAP system fault", "urgency": "INFO", "confidence": "Likely",
    "meaning": "The fuel-vapor containment system has a fault — often as simple as the fuel cap.",
    "can_i_drive": "Yes — this is about vapor containment, not engine operation.",
    "check_first": ["Fuel cap seal (remove, inspect, refit firmly)", "Purge valve", "Vapor hoses"] },
  { "code": "P0442", "title": "EVAP small leak detected", "urgency": "INFO", "confidence": "Likely",
    "meaning": "The fuel-vapor system loses a little pressure — a small leak somewhere in the vapor plumbing.",
    "can_i_drive": "Yes.",
    "check_first": ["Fuel cap seal", "Vapor hose connections", "Purge valve seating"] },
  { "code": "P0446", "title": "EVAP vent circuit", "urgency": "INFO", "confidence": "Likely",
    "meaning": "The vapor system's vent path isn't behaving — usually the vent valve.",
    "can_i_drive": "Yes.",
    "check_first": ["Vent valve", "Blocked vent line", "Wiring"] },
  { "code": "P0455", "title": "EVAP large leak detected", "urgency": "INFO", "confidence": "Likely",
    "meaning": "The fuel-vapor system is losing pressure fast — most often a loose or worn fuel cap.",
    "can_i_drive": "Yes.",
    "check_first": ["Fuel cap — refit firmly, replace if the seal is cracked", "Disconnected vapor hose", "Filler neck seal"] },
  { "code": "P0505", "title": "Idle control system", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The computer can't hold the idle speed it wants.",
    "can_i_drive": "Yes — expect hunting or stalling at idle until fixed.",
    "check_first": ["Breather/intake leaks (the M271 usual suspect)", "Throttle body carbon", "Base idle relearn after cleaning"] },
  { "code": "P0506", "title": "Idle speed too low", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "Idle keeps sagging below target.",
    "can_i_drive": "Yes.",
    "check_first": ["Throttle body carbon", "Vacuum leaks", "Engine mounts loading the idle (feel for shudder)"] },
  { "code": "P0507", "title": "Idle speed too high", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "Idle keeps floating above target — classic unmetered-air symptom.",
    "can_i_drive": "Yes.",
    "check_first": ["Breather/intake leaks after the MAF", "Throttle body not closing fully", "Purge valve stuck open"] },
  { "code": "P0560", "title": "System voltage implausible", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The voltage the computer sees doesn't make sense.",
    "can_i_drive": "Yes — but check the battery gauge on the Dashboard; electrics on a 20-year-old car deserve attention.",
    "check_first": ["Battery terminals (clean and tight)", "Ground straps", "Charging voltage 13.8–14.5 V on the Dashboard"] },
  { "code": "P0561", "title": "System voltage unstable", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "Supply voltage is jumping around more than it should.",
    "can_i_drive": "Yes, with the same electrics caution.",
    "check_first": ["Battery terminals and grounds", "Alternator output ripple", "Aging battery"] },
  { "code": "P0562", "title": "System voltage low", "urgency": "URGENT", "confidence": "Likely",
    "meaning": "Supply voltage is too low — the charging system may be failing, and a car that stops charging eventually stops driving.",
    "can_i_drive": "Short trips only until charging is confirmed — watch the battery gauge (should be 13.8–14.5 V with the engine running).",
    "check_first": ["Alternator output", "Drive belt condition/tension", "Battery terminals and grounds", "Battery age"] },
  { "code": "P0600", "title": "ECU communication link", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The engine computer's internal communication check failed.",
    "can_i_drive": "Usually yes — if it recurs, have the ECU and its wiring checked professionally.",
    "check_first": ["Battery voltage stability first (low voltage fakes ECU faults)", "ECU connectors", "Professional diagnosis"] },
  { "code": "P0601", "title": "ECU memory checksum error", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The engine computer found an error in its own memory.",
    "can_i_drive": "Usually yes — recurring P0601 points at the ECU itself.",
    "check_first": ["Clear and see if it returns", "Battery/ground stability", "Professional ECU diagnosis"] },
  { "code": "P0606", "title": "ECU processor fault", "urgency": "CHECK_SOON", "confidence": "Likely",
    "meaning": "The engine computer's self-test of its own processor failed.",
    "can_i_drive": "Usually yes — same recurrence rule as P0601.",
    "check_first": ["Clear and see if it returns", "Supply voltage and grounds", "Professional ECU diagnosis"] }
]
```

- [ ] **Step 4: Create `android/app/src/main/assets/generic_dtc.json`** — the generic layer. **Authoring brief (content produced at execution, validated by the Step 6 schema test):**

Shape per entry: `{ "code": "P0031", "title": "<standard SAE J2012 short title>", "meaning": "<one plain-language sentence>", "urgency": "<family default>" }`.

Cover, at minimum, ALL generic P0 codes in these ranges that exist in SAE J2012 and are NOT in the curated file, with the family default urgencies:

| Range | Family | Default urgency |
|---|---|---|
| P0030–P0059 | O2 heater control circuits | CHECK_SOON |
| P0087–P0099 | Fuel pressure/temperature | CHECK_SOON |
| P0105–P0109 | MAP sensor circuit | CHECK_SOON |
| P0119, P0124, P0129 | temperature/TPS stragglers | CHECK_SOON |
| P0142–P0167 | O2 sensors (sensor 3 / bank 2) | CHECK_SOON |
| P0173–P0179 | fuel trim bank 2 / fuel composition | CHECK_SOON |
| P0180–P0199 | fuel temperature/rail pressure sensors | CHECK_SOON |
| P0205–P0212 | injector circuits 5+/general | CHECK_SOON |
| P0215–P0234 | fuel shutoff/turbo-supercharger | CHECK_SOON |
| P0261–P0296 | per-cylinder injector circuit low/high | CHECK_SOON |
| P0305–P0312 | misfires cylinders 5+ | URGENT |
| P0326–P0334 | knock sensor variants | CHECK_SOON |
| P0336–P0349 | crank/cam sensor variants | CHECK_SOON |
| P0355–P0379 | coil variants / timing reference | URGENT for P035x coils, CHECK_SOON for P036x–P037x |
| P0400–P0409 | EGR | CHECK_SOON |
| P0411–P0419 minus curated | secondary air variants | CHECK_SOON |
| P0421–P0439 | catalyst/heated catalyst variants | CHECK_SOON |
| P0441–P0457 minus curated | EVAP variants | INFO |
| P0458–P0469 | EVAP purge/vent electrical | INFO |
| P0470–P0479 | exhaust pressure | CHECK_SOON |
| P0480–P0485 | cooling fan circuits | CHECK_SOON |
| P0500–P0504 | vehicle speed sensor | CHECK_SOON |
| P0508–P0519 | idle control electrical / oil pressure sensor | CHECK_SOON |
| P0520–P0559 | oil pressure/AC/vacuum/voltage refs | CHECK_SOON |
| P0563–P0579 | high voltage / cruise control | CHECK_SOON |
| P0580–P0599 | cruise/thermostat heater circuits | CHECK_SOON |
| P0602–P0605 | ECU programming/memory | CHECK_SOON |
| P0607–P0699 | module performance / sensor reference / output drivers | CHECK_SOON |
| P0700–P0799 | transmission (engine ECU won't set these, but a scan tool might show them) | CHECK_SOON |

These MUST appear verbatim (they're pinned by the Step 6 test):

```json
{ "code": "P0031", "title": "O2 sensor heater control low (bank 1, sensor 1)", "meaning": "The front oxygen sensor's heater circuit is drawing less current than expected.", "urgency": "CHECK_SOON" }
{ "code": "P0105", "title": "MAP sensor circuit", "meaning": "The manifold pressure sensor's signal is faulty or missing.", "urgency": "CHECK_SOON" }
{ "code": "P0148", "title": "Fuel delivery error", "meaning": "Fuel delivery doesn't match what the engine is asking for.", "urgency": "CHECK_SOON" }
{ "code": "P0190", "title": "Fuel rail pressure sensor circuit", "meaning": "The fuel rail pressure sensor's signal is faulty.", "urgency": "CHECK_SOON" }
{ "code": "P0230", "title": "Fuel pump primary circuit", "meaning": "The fuel pump's control circuit is faulty.", "urgency": "CHECK_SOON" }
{ "code": "P0261", "title": "Injector circuit low — cylinder 1", "meaning": "Cylinder 1's injector wiring shows lower voltage than expected.", "urgency": "CHECK_SOON" }
{ "code": "P0305", "title": "Misfire — cylinder 5", "meaning": "Cylinder 5 intermittently fails to burn its mixture (not applicable on this 4-cylinder engine — likely a readout error).", "urgency": "URGENT" }
{ "code": "P0401", "title": "EGR flow insufficient", "meaning": "Less exhaust gas is being recirculated than commanded.", "urgency": "CHECK_SOON" }
{ "code": "P0441", "title": "EVAP purge flow fault", "meaning": "The fuel-vapor purge flow isn't what the computer expects.", "urgency": "INFO" }
{ "code": "P0500", "title": "Vehicle speed sensor", "meaning": "The vehicle speed signal is faulty or missing.", "urgency": "CHECK_SOON" }
{ "code": "P0532", "title": "A/C refrigerant pressure sensor low", "meaning": "The air-conditioning pressure sensor reads too low.", "urgency": "CHECK_SOON" }
{ "code": "P0715", "title": "Transmission input speed sensor", "meaning": "The gearbox input speed signal is faulty (a transmission-side code).", "urgency": "CHECK_SOON" }
```

Total must be ≥ 220 entries, all unique, none overlapping the curated file.

- [ ] **Step 5: Write `dtc/DtcRepository.kt` + slim `reference/ReferenceRepository.kt`.** The repository:

```kotlin
package com.kompressorlink.app.dtc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CuratedDtcEntry(
    val code: String,
    val title: String,
    val meaning: String,
    val urgency: String,
    val can_i_drive: String,
    val check_first: List<String>,
    val note: String? = null,
    val confidence: String,
)

@Serializable
data class GenericDtcEntry(
    val code: String,
    val title: String,
    val meaning: String,
    val urgency: String,
)

// Spec §7.3: lookup precedence curated -> generic -> structural. No code
// ever renders bare.
class DtcRepository(private val loadAsset: (String) -> String) {
    private val json = Json { ignoreUnknownKeys = true }

    val curated: Map<String, CuratedDtcEntry> by lazy {
        val entries = json.decodeFromString<List<CuratedDtcEntry>>(loadAsset("w203_dtc.json"))
        requireUnique(entries.map { it.code }, "w203_dtc.json")
        entries.associateBy { it.code }
    }

    val generic: Map<String, GenericDtcEntry> by lazy {
        val entries = json.decodeFromString<List<GenericDtcEntry>>(loadAsset("generic_dtc.json"))
        requireUnique(entries.map { it.code }, "generic_dtc.json")
        entries.associateBy { it.code }
    }

    fun explain(code: String): DtcExplanation {
        curated[code]?.let {
            return DtcExplanation(
                code = it.code, title = it.title, meaning = it.meaning,
                urgency = DtcUrgency.valueOf(it.urgency), canIDrive = it.can_i_drive,
                checkFirst = it.check_first, note = it.note, confidence = it.confidence,
                layer = DtcLayer.W203,
            )
        }
        generic[code]?.let {
            return DtcExplanation(
                code = it.code, title = it.title, meaning = it.meaning,
                urgency = DtcUrgency.valueOf(it.urgency), canIDrive = null,
                checkFirst = emptyList(), note = null, confidence = "Confirmed",
                layer = DtcLayer.GENERIC,
            )
        }
        return DtcFamilyDecoder.decode(code)
    }

    private fun requireUnique(codes: List<String>, file: String) {
        require(codes.size == codes.distinct().size) {
            "$file has duplicate DTC codes: ${codes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
        }
    }
}
```

Full replacement of `reference/ReferenceRepository.kt` (bands only — DTC parsing now lives above):

```kotlin
package com.kompressorlink.app.reference

import com.kompressorlink.app.telemetry.Signal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Healthy band for one signal in one context (spec §4). Contexts: "always",
// "warm_idle", "engine_running", "engine_off" — DashboardLogic.contextHolds
// decides applicability from the snapshot itself.
@Serializable
data class Band(
    val signal: String,
    val context: String,
    val lo: Float,
    val hi: Float,
    val unit: String,
    val confidence: String,
    val hint: String,
)

object ReferenceParser {
    private val json = Json { ignoreUnknownKeys = true }
    fun parseBands(text: String): List<Band> = json.decodeFromString(text)
}

// loadAsset injected so JVM tests read straight from the filesystem while
// the app passes { context.assets.open(it).bufferedReader().readText() }.
// DTC interpretation moved to dtc/DtcRepository in Phase 4.5 (spec §7).
class ReferenceRepository(private val loadAsset: (String) -> String) {
    val bands: List<Band> by lazy { ReferenceParser.parseBands(loadAsset("w203_bands.json")) }

    fun bandsFor(signal: Signal): List<Band> = bands.filter { it.signal == signal.name }
}
```

**Update `ReferenceRepositoryTest.kt`:** delete only the tests that exercised `dtcFor`/`DtcEntry`/`parseDtcEntries` (that coverage moves to `DtcRepositoryTest` below); keep every band test.

- [ ] **Step 6: Write the failing schema/content tests** at `android/app/src/test/java/com/kompressorlink/app/dtc/DtcRepositoryTest.kt`:

```kotlin
package com.kompressorlink.app.dtc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcRepositoryTest {

    private val repo = DtcRepository { name -> File("src/main/assets/$name").readText() }

    @Test
    fun curated_meetsCountAndShape() {
        val entries = repo.curated.values
        assertTrue("need >= 50 curated codes, have ${entries.size}", entries.size >= 50)
        entries.forEach { e ->
            assertTrue("${e.code} malformed", Regex("^P0\\d{3}$").matches(e.code))
            assertTrue("${e.code} needs a meaning", e.meaning.isNotBlank())
            assertTrue("${e.code} needs check_first", e.check_first.isNotEmpty())
            assertTrue("${e.code} needs can_i_drive", e.can_i_drive.isNotBlank())
            DtcUrgency.valueOf(e.urgency)  // throws on invalid urgency
            assertTrue("${e.code} needs confidence", e.confidence.isNotBlank())
        }
    }

    @Test
    fun leanCodes_keepBreatherHosesFirst() {
        listOf("P0170", "P0171").forEach { code ->
            val first = repo.curated.getValue(code).check_first.first()
            assertTrue("$code first check must stay breather hoses", first.contains("breather hoses", ignoreCase = true))
        }
    }

    @Test
    fun generic_meetsCountAndShape_noOverlapWithCurated() {
        val entries = repo.generic.values
        assertTrue("need >= 220 generic codes, have ${entries.size}", entries.size >= 220)
        entries.forEach { e ->
            assertTrue("${e.code} malformed", Regex("^P0[0-9A-F]{3}$").matches(e.code))
            assertTrue("${e.code} needs a meaning", e.meaning.isNotBlank())
            DtcUrgency.valueOf(e.urgency)
        }
        val overlap = repo.generic.keys intersect repo.curated.keys
        assertTrue("generic must not duplicate curated: $overlap", overlap.isEmpty())
    }

    @Test
    fun generic_pinnedSamplesPresent() {
        val expected = mapOf(
            "P0031" to "O2 sensor heater control low (bank 1, sensor 1)",
            "P0105" to "MAP sensor circuit",
            "P0148" to "Fuel delivery error",
            "P0190" to "Fuel rail pressure sensor circuit",
            "P0230" to "Fuel pump primary circuit",
            "P0261" to "Injector circuit low — cylinder 1",
            "P0305" to "Misfire — cylinder 5",
            "P0401" to "EGR flow insufficient",
            "P0441" to "EVAP purge flow fault",
            "P0500" to "Vehicle speed sensor",
            "P0532" to "A/C refrigerant pressure sensor low",
            "P0715" to "Transmission input speed sensor",
        )
        expected.forEach { (code, title) ->
            assertEquals("pinned entry $code", title, repo.generic.getValue(code).title)
        }
    }

    @Test
    fun explain_precedence_curatedThenGenericThenStructural() {
        assertEquals(DtcLayer.W203, repo.explain("P0171").layer)
        assertEquals(DtcLayer.GENERIC, repo.explain("P0031").layer)
        val structural = repo.explain("P1863")
        assertEquals(DtcLayer.STRUCTURAL, structural.layer)
        assertEquals("Manufacturer-specific powertrain code, transmission group", structural.title)
    }

    @Test
    fun explain_urgencyLabels_matchSpec() {
        assertEquals("Check soon", repo.explain("P0171").urgency!!.label)
        assertEquals("Urgent — drive gently", repo.explain("P0300").urgency!!.label)
        assertEquals("Info", repo.explain("P0455").urgency!!.label)
    }
}
```

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.dtc.DtcRepositoryTest"` → drives the JSON authoring: it FAILS until both content files satisfy every assertion (6 tests).

- [ ] **Step 7: Rewrite `dtc/DtcViewModel.kt`:**

```kotlin
package com.kompressorlink.app.dtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DtcScreenState(
    val stored: List<DtcExplanation>,
    val pending: List<DtcExplanation>,
    val hasReport: Boolean,          // false until the first DTC frame arrives
    val expandedCodes: Set<String>,
)

class DtcViewModel(
    source: TelemetrySource,
    private val repo: DtcRepository,
) : ViewModel() {

    private val expanded = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<DtcScreenState> =
        combine(source.dtcReport, expanded) { report, expandedCodes ->
            build(report, expandedCodes, repo)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            build(null, emptySet(), repo))

    fun toggle(code: String) {
        expanded.value = if (code in expanded.value) expanded.value - code else expanded.value + code
    }

    companion object {
        fun build(report: DtcReport?, expandedCodes: Set<String>, repo: DtcRepository): DtcScreenState {
            if (report == null) return DtcScreenState(emptyList(), emptyList(), hasReport = false, expandedCodes = expandedCodes)
            return DtcScreenState(
                stored = report.stored.map(repo::explain),
                pending = report.pending.map(repo::explain),
                hasReport = true,
                expandedCodes = expandedCodes,
            )
        }
    }
}
```

- [ ] **Step 8: Rewrite `dtc/DtcScreen.kt`** (spec §8.4 + mockup screen 3):

```kotlin
package com.kompressorlink.app.dtc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kompressorlink.app.ui.components.StatusChip
import com.kompressorlink.app.ui.theme.KlColors

@Composable
fun DtcScreen(viewModel: DtcViewModel) {
    val state by viewModel.uiState.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Text(
                if (!state.hasReport) "No DTC data received yet"
                else "${state.stored.size} stored · ${state.pending.size} pending",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        item {
            Text("Stored codes", style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.padding(top = 12.dp))
        }
        if (state.hasReport && state.stored.isEmpty()) {
            item { Text("No stored codes — nothing to worry about here.",
                        Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.stored, key = { "s-${it.code}" }) { dtc ->
            DtcCard(dtc, expanded = dtc.code in state.expandedCodes) { viewModel.toggle(dtc.code) }
        }

        item {
            Text("Pending codes", style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.padding(top = 16.dp))
        }
        if (state.hasReport && state.pending.isEmpty()) {
            item { Text("No pending codes.", Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.pending, key = { "p-${it.code}" }) { dtc ->
            DtcCard(dtc, expanded = dtc.code in state.expandedCodes) { viewModel.toggle(dtc.code) }
        }

        item {
            // Read-only by design: Mode 04 clear-DTC remains deferred
            // (erases evidence, resets readiness monitors).
            Text(
                "Clearing codes is not available in this version.",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

private fun urgencyColor(urgency: DtcUrgency?) = when (urgency) {
    DtcUrgency.INFO -> KlColors.Neutral
    DtcUrgency.CHECK_SOON -> KlColors.Watch
    DtcUrgency.URGENT, DtcUrgency.STOP -> KlColors.Attention
    null -> KlColors.Neutral
}

@Composable
private fun DtcCard(dtc: DtcExplanation, expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onToggle)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(dtc.code, style = MaterialTheme.typography.titleMedium,
                     fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                StatusChip(dtc.urgency?.label ?: "Unknown urgency", urgencyColor(dtc.urgency))
            }
            Text(dtc.title, style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 6.dp)) {
                StatusChip(dtc.layer.chip, KlColors.Neutral)
                dtc.confidence?.let {
                    Spacer(Modifier.width(6.dp))
                    StatusChip("Confidence: $it", KlColors.Neutral)
                }
            }
            if (!expanded) {
                Text(dtc.meaning, style = MaterialTheme.typography.bodySmall,
                     maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                Text("Tap for details ▾", style = MaterialTheme.typography.labelSmall,
                     modifier = Modifier.padding(top = 4.dp))
            } else {
                Section("What's happening", dtc.meaning)
                dtc.canIDrive?.let { Section("Can I keep driving?", it) }
                if (dtc.checkFirst.isNotEmpty()) {
                    Text("Check first", style = MaterialTheme.typography.labelMedium,
                         modifier = Modifier.padding(top = 8.dp))
                    dtc.checkFirst.forEachIndexed { i, step ->
                        Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium,
                             modifier = Modifier.padding(top = 2.dp))
                    }
                }
                dtc.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                         fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(label: String, body: String) {
    Text(label, style = MaterialTheme.typography.labelMedium,
         modifier = Modifier.padding(top = 8.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium,
         modifier = Modifier.padding(top = 2.dp))
}
```

- [ ] **Step 9: Wire it up.** Three surgical edits (exact lines):

1. In `KompressorLinkApp.kt`'s `AppContainer`, directly under the `referenceRepository` declaration, add (plus the `com.kompressorlink.app.dtc.DtcRepository` import):

```kotlin
    val dtcRepository = DtcRepository { name ->
        app.assets.open(name).bufferedReader().readText()
    }
```

2. In `MainActivity.kt`, the `composable("dtc")` initializer changes from `DtcViewModel(container.telemetrySource, container.referenceRepository)` to:

```kotlin
                val vm: DtcViewModel = viewModel(initializer = {
                    DtcViewModel(container.telemetrySource, container.dtcRepository)
                })
```

3. Full replacement of `telemetry/FakeTelemetrySource.kt` — identical to today's file except the `dtcReport` block becomes (spec §8.4's layer-3 on-screen proof):

```kotlin
    override val dtcReport: StateFlow<DtcReport?> = MutableStateFlow(
        when (scenario) {
            // P1570 is deliberately in NEITHER json: it proves the
            // family-decode layer on a real screen (spec §8.4/§10).
            FakeScenario.FAULT -> DtcReport(stored = listOf("P0171"), pending = listOf("P1570"))
            else -> DtcReport(stored = emptyList(), pending = emptyList())
        }
    )
```

- [ ] **Step 10: Rewrite `DtcViewModelTest.kt`** (full replacement — the old one tested the deleted `DtcUi` shape):

```kotlin
package com.kompressorlink.app.dtc

import com.kompressorlink.app.telemetry.DtcReport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcViewModelTest {

    private val repo = DtcRepository { name -> File("src/main/assets/$name").readText() }

    @Test
    fun nullReport_meansNoDataYet() {
        val state = DtcViewModel.build(null, emptySet(), repo)
        assertFalse(state.hasReport)
        assertTrue(state.stored.isEmpty() && state.pending.isEmpty())
    }

    @Test
    fun faultScenarioShape_curatedAndStructural() {
        val state = DtcViewModel.build(
            DtcReport(stored = listOf("P0171"), pending = listOf("P1570")), emptySet(), repo,
        )
        assertTrue(state.hasReport)
        assertEquals(DtcLayer.W203, state.stored[0].layer)
        assertEquals("Check soon", state.stored[0].urgency!!.label)
        assertTrue(state.stored[0].checkFirst.first().contains("breather hoses", ignoreCase = true))
        assertEquals(DtcLayer.STRUCTURAL, state.pending[0].layer)
        // P1570: digit 3 is '5' -> "vehicle speed & idle control" family.
        assertEquals("Manufacturer-specific powertrain code, vehicle speed & idle control group",
            state.pending[0].title)
    }

    @Test
    fun genericCode_getsGenericLayer() {
        val state = DtcViewModel.build(
            DtcReport(stored = listOf("P0031"), pending = emptyList()), emptySet(), repo,
        )
        assertEquals(DtcLayer.GENERIC, state.stored[0].layer)
    }
}
```

**Also update `FakeTelemetrySourceTest.kt`:** any assertion that the FAULT scenario's pending list is empty changes to expect `listOf("P1570")`. Change nothing else there.

- [ ] **Step 11: Run everything:**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
Expected: both `BUILD SUCCESSFUL` — new DTC suites pass, updated Reference/Fake tests pass, APK builds.

- [ ] **Step 12: Leave for review.**

---
## Task 22: Dashboard rewrite — band bars, warm-idle chip, hysteresis levels

**Files:**
- Modify: `android/app/src/main/java/com/kompressorlink/app/dashboard/DashboardLogic.kt` (full replacement)
- Modify: `android/app/src/main/java/com/kompressorlink/app/dashboard/DashboardViewModel.kt` (full replacement)
- Modify: `android/app/src/main/java/com/kompressorlink/app/dashboard/DashboardScreen.kt` (full replacement)
- Modify: `android/app/src/main/java/com/kompressorlink/app/MainActivity.kt` (one surgical edit, Step 4)
- Test: `android/app/src/test/java/com/kompressorlink/app/dashboard/DashboardViewModelTest.kt` (full replacement)
- Test: `android/app/src/test/java/com/kompressorlink/app/dashboard/DashboardLogicTest.kt` (additive — Step 5)

- [ ] **Step 1: Full replacement of `dashboard/DashboardLogic.kt`** — same as Task 8's version plus: `BandRange` on the gauge state (feeds the BandBar), `contextLabel` (battery "charging"/"resting", "warm idle" for MAF/ECT), and an optional `displayedLevel` (the monitor's post-hysteresis level) that overrides the raw level for tint + hint gating:

```kotlin
package com.kompressorlink.app.dashboard

import com.kompressorlink.app.health.SnapshotContexts
import com.kompressorlink.app.reference.Band
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.util.Locale

enum class GaugeLevel { NEUTRAL, OK, AMBER, RED, UNAVAILABLE }

data class BandRange(val lo: Float, val hi: Float)

data class GaugeUiState(
    val signal: Signal,
    val title: String,
    val valueText: String,    // "—" when unavailable
    val unit: String,
    val level: GaugeLevel,    // post-hysteresis when the monitor supplies one
    val band: BandRange?,     // drives the BandBar; null when no band applies
    val bandText: String?,    // e.g. "3.0–5.0 g/s (Confirmed)"
    val contextLabel: String?, // "warm idle" / "charging" / "resting"
    val hint: String?,        // reference hint, only when AMBER/RED
    val history: List<Float>,
)

// Pure functions — everything the DashboardLogicTest covers lives here,
// framework-free.
object DashboardLogic {

    val DASHBOARD_SIGNALS = listOf(
        Signal.RPM, Signal.MAF_GS, Signal.STFT1, Signal.LTFT1, Signal.ECT,
        Signal.MAP, Signal.O2_B1S1_V, Signal.O2_B1S2_V, Signal.BATT_V_ADC,
        Signal.TIMING_ADV,
    )

    private val TITLES = mapOf(
        Signal.RPM to "RPM", Signal.MAF_GS to "MAF", Signal.STFT1 to "STFT",
        Signal.LTFT1 to "LTFT", Signal.ECT to "Coolant", Signal.MAP to "MAP",
        Signal.O2_B1S1_V to "O2 pre-cat", Signal.O2_B1S2_V to "O2 post-cat",
        Signal.BATT_V_ADC to "Battery", Signal.TIMING_ADV to "Timing",
    )

    private val UNITS = mapOf(
        Signal.RPM to "rpm", Signal.MAF_GS to "g/s", Signal.STFT1 to "%",
        Signal.LTFT1 to "%", Signal.ECT to "°C", Signal.MAP to "kPa",
        Signal.O2_B1S1_V to "V", Signal.O2_B1S2_V to "V",
        Signal.BATT_V_ADC to "V", Signal.TIMING_ADV to "°",
    )

    /** PLAN.md §7's warm-idle definition, computed from the snapshot itself.
     *  Any gating signal being unavailable means "not warm idle" — never
     *  guess a context from missing data (Untested contract). */
    fun isWarmIdle(s: TelemetrySnapshot): Boolean {
        val rpm = s.value(Signal.RPM) ?: return false
        val speed = s.value(Signal.SPEED) ?: return false
        val ect = s.value(Signal.ECT) ?: return false
        return rpm in 600f..900f && speed == 0f && ect > 80f
    }

    /** Plan-fixed thresholds: in-band OK; ≤ half-width beyond AMBER; else RED. */
    fun levelFor(value: Float, band: Band): GaugeLevel {
        val halfWidth = (band.hi - band.lo) / 2f
        return when {
            value >= band.lo && value <= band.hi -> GaugeLevel.OK
            value < band.lo - halfWidth || value > band.hi + halfWidth -> GaugeLevel.RED
            else -> GaugeLevel.AMBER
        }
    }

    /** Spec §4: band contexts. Unknown context strings fail closed. */
    fun contextHolds(context: String, snapshot: TelemetrySnapshot): Boolean = when (context) {
        "always" -> true
        "warm_idle" -> isWarmIdle(snapshot)
        "engine_running" -> SnapshotContexts.engineRunning(snapshot) == true
        "engine_off" -> SnapshotContexts.engineRunning(snapshot) == false
        else -> false
    }

    fun applicableBand(signal: Signal, snapshot: TelemetrySnapshot, refs: ReferenceRepository): Band? =
        refs.bandsFor(signal).firstOrNull { band -> contextHolds(band.context, snapshot) }

    /** Spec §8.2: the battery caption names its active context; warm-idle
     *  bands are labeled so contextual judging is self-explanatory. */
    fun contextLabel(band: Band): String? = when (band.context) {
        "engine_running" -> "charging"
        "engine_off" -> "resting"
        "warm_idle" -> "warm idle"
        else -> null
    }

    fun gaugeFor(
        signal: Signal,
        snapshot: TelemetrySnapshot,
        refs: ReferenceRepository,
        history: List<Float>,
        displayedLevel: GaugeLevel? = null,
    ): GaugeUiState {
        val title = TITLES.getValue(signal)
        val unit = UNITS.getValue(signal)
        val value = snapshot.value(signal)
            ?: return GaugeUiState(signal, title, "—", unit, GaugeLevel.UNAVAILABLE,
                                   band = null, bandText = null, contextLabel = null,
                                   hint = null, history = emptyList())
        val band = applicableBand(signal, snapshot, refs)
        val raw = band?.let { levelFor(value, it) } ?: GaugeLevel.NEUTRAL
        // The monitor's post-hysteresis level wins when provided (spec §4);
        // raw is the fallback for the first frames before the monitor emits.
        val level = displayedLevel ?: raw
        val decimals = if (signal == Signal.O2_B1S1_V || signal == Signal.O2_B1S2_V) 2 else 1
        return GaugeUiState(
            signal = signal,
            title = title,
            valueText = String.format(Locale.US, "%.${decimals}f", value),
            unit = unit,
            level = level,
            band = band?.let { BandRange(it.lo, it.hi) },
            bandText = band?.let {
                String.format(Locale.US, "%.1f–%.1f %s (%s)", it.lo, it.hi, it.unit, it.confidence)
            },
            contextLabel = band?.let { contextLabel(it) },
            hint = if (level == GaugeLevel.AMBER || level == GaugeLevel.RED) band?.hint else null,
            history = history,
        )
    }
}
```

- [ ] **Step 2: Full replacement of `dashboard/DashboardViewModel.kt`** — now also consumes the monitor's level map, and exposes the raw `ConnectionState` + warm-idle flag for the new screen:

```kotlin
package com.kompressorlink.app.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val gauges: List<GaugeUiState>,
    val connection: ConnectionState,
    val warmIdle: Boolean,
)

class DashboardViewModel(
    source: TelemetrySource,
    private val refs: ReferenceRepository,
    levels: StateFlow<Map<Signal, GaugeLevel>>,
) : ViewModel() {

    // Sparkline ring buffers: 120 samples at 2 Hz = last 60 s.
    private val histories =
        DashboardLogic.DASHBOARD_SIGNALS.associateWith { ArrayDeque<Float>(HISTORY_CAP) }

    // History mutation runs exactly once per telemetry emission (onEach is
    // upstream of combine), so a connectionState- or levels-only re-combine
    // re-derives gauges without double-appending — same reasoning as Phase 4.
    val uiState: StateFlow<DashboardUiState> =
        combine(
            source.telemetry.onEach { snapshot ->
                for (signal in DashboardLogic.DASHBOARD_SIGNALS) {
                    val history = histories.getValue(signal)
                    snapshot.value(signal)?.let {
                        if (history.size == HISTORY_CAP) history.removeFirst()
                        history.addLast(it)
                    }
                }
            },
            source.connectionState,
            levels,
        ) { snapshot, conn, levelMap ->
            DashboardUiState(
                gauges = DashboardLogic.DASHBOARD_SIGNALS.map { signal ->
                    DashboardLogic.gaugeFor(
                        signal, snapshot, refs,
                        histories.getValue(signal).toList(),
                        displayedLevel = levelMap[signal],
                    )
                },
                connection = conn,
                warmIdle = DashboardLogic.isWarmIdle(snapshot),
            )
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            DashboardUiState(emptyList(), ConnectionState.Disconnected, warmIdle = false),
        )

    private companion object {
        const val HISTORY_CAP = 120
    }
}
```

- [ ] **Step 3: Full replacement of `dashboard/DashboardScreen.kt`** (spec §8.2 + mockup screen 1 — band bars, warm-idle chip, semantic value tinting, shared components):

```kotlin
package com.kompressorlink.app.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kompressorlink.app.ui.components.BandBar
import com.kompressorlink.app.ui.components.Sparkline
import com.kompressorlink.app.ui.components.StatusChip
import com.kompressorlink.app.ui.theme.KlColors

@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val state by viewModel.uiState.collectAsState()
    val trims = state.gauges.filter { it.signal.name == "STFT1" || it.signal.name == "LTFT1" }
    val others = state.gauges.filterNot { it.signal.name == "STFT1" || it.signal.name == "LTFT1" }

    Column(Modifier.fillMaxSize()) {
        if (state.warmIdle) {
            Row(Modifier.padding(start = 8.dp, top = 6.dp)) {
                StatusChip("Warm idle detected", KlColors.Neutral)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (trims.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) { TrimsTile(trims) }
            }
            items(others) { gauge -> GaugeTile(gauge) }
        }
    }
}

@Composable
private fun GaugeValue(gauge: GaugeUiState) {
    val valueColor = when (gauge.level) {
        GaugeLevel.AMBER -> KlColors.Watch
        GaugeLevel.RED -> KlColors.Attention
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text("${gauge.valueText} ${gauge.unit}",
         style = MaterialTheme.typography.headlineMedium, color = valueColor)
}

@Composable
private fun GaugeTile(gauge: GaugeUiState) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(gauge.title, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (gauge.level == GaugeLevel.UNAVAILABLE) {
                // The Untested contract on screen: no value, an honest label.
                Text("—", style = MaterialTheme.typography.headlineMedium)
                Text("Not provided by ECU", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                GaugeValue(gauge)
                gauge.band?.let { band ->
                    BandBar(
                        value = gauge.valueText.toFloatOrNull() ?: band.lo,
                        lo = band.lo, hi = band.hi,
                        markerColor = KlColors.forLevel(gauge.level),
                    )
                }
                val caption = listOfNotNull(gauge.contextLabel, gauge.bandText)
                    .joinToString(" · ")
                if (caption.isNotEmpty()) {
                    Text(caption, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                gauge.hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                         color = KlColors.Attention)
                }
                Sparkline(gauge.history)
            }
        }
    }
}

@Composable
private fun TrimsTile(trims: List<GaugeUiState>) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Fuel trims", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                trims.forEach { g ->
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        Text(g.title, style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (g.level == GaugeLevel.UNAVAILABLE) {
                            Text("—", style = MaterialTheme.typography.headlineSmall)
                        } else {
                            GaugeValue(g)
                            g.band?.let { band ->
                                BandBar(
                                    value = g.valueText.toFloatOrNull() ?: band.lo,
                                    lo = band.lo, hi = band.hi,
                                    markerColor = KlColors.forLevel(g.level),
                                )
                            }
                        }
                    }
                }
            }
            trims.firstNotNullOfOrNull { it.hint }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = KlColors.Attention)
            }
        }
    }
}
```

(The Phase-4 `severityRank` helper is gone deliberately: tinting moved from card backgrounds to per-value colors, so each trim column now carries its own level color and a worst-of pick is no longer needed. The lesson it encoded — a dropped poll must never mask a real fault — lives on in `HealthRollup`, where UNAVAILABLE maps to OK, never to a fault, and RED always outranks it.)

- [ ] **Step 4: Surgical edit in `MainActivity.kt`** — the dashboard initializer gains the monitor's levels:

```kotlin
                val vm: DashboardViewModel = viewModel(initializer = {
                    DashboardViewModel(container.telemetrySource, container.referenceRepository,
                                       container.liveWarningMonitor.levels)
                })
```

(Task 25 replaces MainActivity wholesale; this keeps the build green meanwhile.)

- [ ] **Step 5: Tests.** Full replacement of `DashboardViewModelTest.kt`:

```kotlin
package com.kompressorlink.app.dashboard

import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val refs = ReferenceRepository { name -> File("src/main/assets/$name").readText() }
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class ScriptedSource : TelemetrySource {
        val telemetryFlow = MutableSharedFlow<TelemetrySnapshot>()
        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Simulated("healthy"))
        override val telemetry: Flow<TelemetrySnapshot> = telemetryFlow
        override val dtcReport: StateFlow<DtcReport?> = MutableStateFlow(null)
        override suspend fun sendTimeSync() {}
    }

    private fun snap(vararg pairs: Pair<Signal, Float>): TelemetrySnapshot {
        val v = FloatArray(SIGNAL_COUNT)
        var mask = 0
        pairs.forEach { (s, value) -> v[s.ordinal] = value; mask = mask or (1 shl s.ordinal) }
        return TelemetrySnapshot(v, mask, flags = 0, seq = 0, uptimeMs = 0)
    }

    @Test
    fun gauges_useMonitorLevels_andWarmIdleFlag() = runTest(dispatcher) {
        val src = ScriptedSource()
        val levels = MutableStateFlow(mapOf(Signal.LTFT1 to GaugeLevel.RED))
        val vm = DashboardViewModel(src, refs, levels)
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        src.telemetryFlow.emit(snap(
            Signal.RPM to 750f, Signal.SPEED to 0f, Signal.ECT to 90f,
            Signal.LTFT1 to 4f,  // raw would be OK — displayed RED comes from the monitor
        ))
        runCurrent()

        val state = vm.uiState.first { it.gauges.isNotEmpty() }
        assertTrue(state.warmIdle)
        val ltft = state.gauges.first { it.signal == Signal.LTFT1 }
        assertEquals(GaugeLevel.RED, ltft.level)
        val rpm = state.gauges.first { it.signal == Signal.RPM }
        assertEquals(GaugeLevel.NEUTRAL, rpm.level)  // no band, not in monitor map
        collector.cancel()
    }

    @Test
    fun histories_accumulateOncePerEmission() = runTest(dispatcher) {
        val src = ScriptedSource()
        val vm = DashboardViewModel(src, refs, MutableStateFlow(emptyMap()))
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        repeat(3) {
            src.telemetryFlow.emit(snap(Signal.RPM to (700f + it * 10)))
            runCurrent()
        }
        val state = vm.uiState.first { it.gauges.isNotEmpty() }
        val rpm = state.gauges.first { it.signal == Signal.RPM }
        assertEquals(listOf(700f, 710f, 720f), rpm.history)
        collector.cancel()
    }

    @Test
    fun unavailableGauge_staysHonest() = runTest(dispatcher) {
        val src = ScriptedSource()
        val vm = DashboardViewModel(src, refs, MutableStateFlow(emptyMap()))
        val collector = launch { vm.uiState.collect {} }
        runCurrent()

        src.telemetryFlow.emit(snap(Signal.RPM to 750f))  // battery masked out
        runCurrent()
        val state = vm.uiState.first { it.gauges.isNotEmpty() }
        val batt = state.gauges.first { it.signal == Signal.BATT_V_ADC }
        assertEquals(GaugeLevel.UNAVAILABLE, batt.level)
        assertEquals("—", batt.valueText)
        collector.cancel()
    }
}
```

**`DashboardLogicTest.kt` (additive — keep every existing test, fix only compile breaks from the removed `label()`-era shapes if any):** append:

```kotlin
    @Test
    fun gaugeFor_displayedLevelOverridesRaw_andGatesHint() {
        val s = snap(Signal.RPM to 750f, Signal.SPEED to 0f, Signal.ECT to 90f, Signal.LTFT1 to 4f)
        val overridden = DashboardLogic.gaugeFor(Signal.LTFT1, s, refs, emptyList(),
                                                 displayedLevel = GaugeLevel.AMBER)
        assertEquals(GaugeLevel.AMBER, overridden.level)
        assertNotNull(overridden.hint)   // hint gates on the DISPLAYED level
        assertNotNull(overridden.band)
    }

    @Test
    fun batteryContextLabel_namesTheActiveContext() {
        val running = snap(Signal.RPM to 2000f, Signal.BATT_V_ADC to 14.2f)
        assertEquals("charging",
            DashboardLogic.gaugeFor(Signal.BATT_V_ADC, running, refs, emptyList()).contextLabel)
        val off = snap(Signal.RPM to 0f, Signal.BATT_V_ADC to 12.6f)
        assertEquals("resting",
            DashboardLogic.gaugeFor(Signal.BATT_V_ADC, off, refs, emptyList()).contextLabel)
    }
```

(reusing the file's existing `snap`/`refs` helpers; if it lacks a `snap` helper, copy the one from `BandContextTest`).

- [ ] **Step 6: Run everything:**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
Expected: both `BUILD SUCCESSFUL`. Leave for review.

---

## Task 23: Health screen

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/health/HealthScreen.kt`

No new logic — everything on this screen is HealthViewModel state (Task 19, tested) rendered with Task 20's components. Compile-verified; exercised by the Task 26 phone checkpoints.

- [ ] **Step 1: Write `health/HealthScreen.kt`:**

```kotlin
package com.kompressorlink.app.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.kompressorlink.app.ui.theme.KlColors

@Composable
fun HealthScreen(viewModel: HealthViewModel) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Demo data", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.demoMode, onCheckedChange = viewModel::setDemoMode)
            }
        }
        if (state.demoMode) {
            item {
                // The loud, unmissable quarantine ribbon (spec §8.3).
                Box(
                    Modifier.fillMaxWidth().background(KlColors.Watch)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("DEMO DATA — simulated history",
                         style = MaterialTheme.typography.labelMedium,
                         color = androidx.compose.ui.graphics.Color.Black)
                }
            }
        }

        item { VerdictCard(state.verdict) }

        state.emptyState?.let { empty ->
            item {
                Text(empty, style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.padding(vertical = 12.dp))
            }
        }

        items(state.cards, key = { it.subsystem.name }) { card -> SubsystemRow(card) }

        state.driftGateFooter?.let { footer ->
            item {
                Text(footer, style = MaterialTheme.typography.bodySmall,
                     fontStyle = FontStyle.Italic,
                     modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        item {
            Text("Warnings", style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.padding(top = 16.dp))
        }
        if (state.warnings.isEmpty()) {
            item { Text("No warnings recorded.", Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.warnings, key = { it.id }) { warning ->
            WarningRow(warning, onAcknowledge = { viewModel.acknowledge(warning.id) })
        }
    }
}

@Composable
private fun VerdictCard(verdict: OverallVerdict) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row {
            Box(Modifier.width(6.dp).height(72.dp).background(KlColors.forStatus(verdict.status)))
            Column(Modifier.padding(12.dp)) {
                Text(verdict.headline, style = MaterialTheme.typography.titleLarge)
                Text(verdict.subline, style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SubsystemRow(card: SubsystemCard) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(KlColors.forStatus(card.status), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(card.subsystem.displayName, style = MaterialTheme.typography.titleSmall)
                card.driftArrow?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it.toString(), style = MaterialTheme.typography.titleSmall,
                         color = KlColors.Watch)
                }
                Spacer(Modifier.weight(1f))
                Text(card.status.label, style = MaterialTheme.typography.labelMedium,
                     color = KlColors.forStatus(card.status))
            }
            Text(card.reason, style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.padding(top = 4.dp))
            card.baselineGate?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            card.footnote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WarningRow(warning: WarningUi, onAcknowledge: () -> Unit) {
    val color = if (warning.level == "ATTENTION") KlColors.Attention else KlColors.Watch
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(
                    if (warning.acknowledged) KlColors.Neutral else color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(warning.timeText, style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(warning.title, style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.padding(top = 4.dp))
            Text(warning.detail, style = MaterialTheme.typography.bodySmall)
            if (!warning.acknowledged) {
                TextButton(onClick = onAcknowledge) { Text("Acknowledge") }
            } else {
                Text("acknowledged", style = MaterialTheme.typography.labelSmall,
                     fontStyle = FontStyle.Italic,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
```

- [ ] **Step 2: Compile + full suite:**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
Expected: both `BUILD SUCCESSFUL` (screen is not yet routed — Task 25 wires it). Leave for review.

---

## Task 24: Maintenance screen

**Files:**
- Create: `android/app/src/main/java/com/kompressorlink/app/maintenance/MaintenanceScreen.kt`
- Test: `android/app/src/test/java/com/kompressorlink/app/maintenance/DueLineFormatterTest.kt`

- [ ] **Step 1: Write the due-line formatter + its failing test.** The formatter is pure and lives at the top of `MaintenanceScreen.kt`:

```kotlin
// At the top of MaintenanceScreen.kt, before the composables:

/** Spec §8.5 row wording. Pure — JVM-tested. */
fun formatDueLine(info: DueInfo): String = when (info.state) {
    DueState.NEVER_LOGGED -> "When did you last do this?"
    DueState.KM_UNTRACKED -> "Enter your odometer to activate"
    DueState.OVERDUE -> {
        val parts = mutableListOf<String>()
        info.remainingKm?.takeIf { it < 0 }?.let { parts += "${-it} km" }
        info.remainingDays?.takeIf { it < 0 }?.let { parts += "${-it} days" }
        "Overdue by ${parts.joinToString(" · ")}"
    }
    DueState.DUE_SOON, DueState.OK -> {
        val parts = mutableListOf<String>()
        info.remainingKm?.takeIf { it >= 0 }?.let { parts += "$it km" }
        info.remainingDays?.takeIf { it >= 0 }?.let { parts += "$it days" }
        if (parts.isEmpty()) "Interval not computable" else parts.joinToString(" or ") + " left"
    }
}
```

Test at `android/app/src/test/java/com/kompressorlink/app/maintenance/DueLineFormatterTest.kt`:

```kotlin
package com.kompressorlink.app.maintenance

import org.junit.Assert.assertEquals
import org.junit.Test

class DueLineFormatterTest {
    @Test
    fun formats_everyState() {
        assertEquals("When did you last do this?",
            formatDueLine(DueInfo(DueState.NEVER_LOGGED, null, null, kmUntracked = true)))
        assertEquals("Enter your odometer to activate",
            formatDueLine(DueInfo(DueState.KM_UNTRACKED, null, null, kmUntracked = true)))
        assertEquals("Overdue by 1000 km",
            formatDueLine(DueInfo(DueState.OVERDUE, -1000, null, kmUntracked = false)))
        assertEquals("Overdue by 1000 km · 45 days",
            formatDueLine(DueInfo(DueState.OVERDUE, -1000, -45, kmUntracked = false)))
        assertEquals("450 km or 19 days left",
            formatDueLine(DueInfo(DueState.DUE_SOON, 450, 19, kmUntracked = false)))
        assertEquals("5500 km left",
            formatDueLine(DueInfo(DueState.OK, 5500, null, kmUntracked = false)))
    }

    @Test
    fun mixedSigns_showOnlyTheOverdueDimension() {
        // km overdue but date still fine: state OVERDUE, only km listed.
        assertEquals("Overdue by 200 km",
            formatDueLine(DueInfo(DueState.OVERDUE, -200, 90, kmUntracked = false)))
    }
}
```

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kompressorlink.app.maintenance.DueLineFormatterTest"` → FAIL before, PASS after (2 tests).

- [ ] **Step 2: Write the rest of `maintenance/MaintenanceScreen.kt`** (spec §8.5 + mockup screen 4):

```kotlin
package com.kompressorlink.app.maintenance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.ui.theme.KlColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// formatDueLine from Step 1 sits here (above the composables).

private fun dueColor(state: DueState) = when (state) {
    DueState.OVERDUE -> KlColors.Attention
    DueState.DUE_SOON -> KlColors.Watch
    DueState.NEVER_LOGGED, DueState.KM_UNTRACKED -> KlColors.Neutral
    DueState.OK -> KlColors.Ok
}

private val dateFormat = DateTimeFormatter.ofPattern("MMM yyyy")
private fun formatDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateFormat)

@Composable
fun MaintenanceScreen(viewModel: MaintenanceViewModel) {
    val state by viewModel.uiState.collectAsState()
    var showOdometerDialog by remember { mutableStateOf(false) }
    var logTarget by remember { mutableStateOf<MaintenanceItemEntity?>(null) }
    var detailTarget by remember { mutableStateOf<MaintenanceItemUi?>(null) }
    var showItemPicker by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showItemPicker = true }) {
                Text("+ Log service")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            item {
                OdometerCard(state.odometer, onUpdate = { showOdometerDialog = true })
            }
            state.message?.let { msg ->
                item {
                    Text(msg, color = KlColors.Attention,
                         style = MaterialTheme.typography.bodySmall,
                         modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            items(state.items, key = { it.item.id }) { row ->
                ItemRow(row, onClick = { detailTarget = row })
            }
            item {
                TextButton(onClick = { showAddDialog = true }) { Text("＋ Add custom item") }
            }
            item {
                Text(
                    "Reference-guide intervals, not WIS gospel — confirm safety-critical values against WIS for your VIN.",
                    style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAddDialog) {
        AddItemDialog(
            onConfirm = { name, category, km, months, note ->
                viewModel.addCustomItem(name, category, km, months, note)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
    if (showOdometerDialog) {
        NumberDialog(
            title = "Update odometer",
            label = "Current odometer (km)",
            onConfirm = { km -> viewModel.updateOdometer(km); showOdometerDialog = false },
            onDismiss = { showOdometerDialog = false },
        )
    }
    if (showItemPicker) {
        ItemPickerDialog(
            items = state.items.map { it.item },
            onPick = { item -> showItemPicker = false; logTarget = item },
            onDismiss = { showItemPicker = false },
        )
    }
    logTarget?.let { item ->
        LogServiceDialog(
            item = item,
            defaultKm = state.odometer.estimatedKm,
            onConfirm = { km, note ->
                viewModel.logService(item.id, System.currentTimeMillis(), km, note)
                logTarget = null
            },
            onDismiss = { logTarget = null },
        )
    }
    detailTarget?.let { row ->
        ItemDetailDialog(
            row = row,
            viewModel = viewModel,
            onLog = { detailTarget = null; logTarget = row.item },
            onDismiss = { detailTarget = null },
        )
    }
}

@Composable
private fun OdometerCard(odometer: OdometerUi, onUpdate: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Odometer", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (odometer.estimatedKm == null) {
                Text("—", style = MaterialTheme.typography.headlineMedium)
                Text("Enter your odometer to activate km tracking",
                     style = MaterialTheme.typography.bodySmall)
            } else {
                Text("≈ ${odometer.estimatedKm} km", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Last entered ${odometer.anchorKm} km · ${formatDate(odometer.anchorEpochMs!!)}" +
                        " · +${odometer.estimatedDeltaKm} km estimated from drives",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onUpdate, modifier = Modifier.padding(top = 8.dp)) {
                Text("Update odometer")
            }
        }
    }
}

@Composable
private fun ItemRow(row: MaintenanceItemUi, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(dueColor(row.info.state), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(row.item.name, style = MaterialTheme.typography.titleSmall)
                if (!row.item.enabled) {
                    Spacer(Modifier.width(6.dp))
                    Text("(off)", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(formatDueLine(row.info), style = MaterialTheme.typography.bodySmall,
                     color = dueColor(row.info.state))
            }
            row.lastLog?.let { log ->
                Text(
                    "Last: ${log.km?.let { "$it km · " } ?: ""}${formatDate(log.epochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.info.kmUntracked && row.info.state != DueState.KM_UNTRACKED) {
                Text("km tracking needs an odometer entry",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(row.item.note, style = MaterialTheme.typography.bodySmall,
                 fontStyle = FontStyle.Italic,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NumberDialog(title: String, label: String, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.filter(Char::isDigit) },
                              label = { Text(label) })
        },
        confirmButton = {
            TextButton(
                onClick = { text.toIntOrNull()?.let(onConfirm) },
                enabled = text.toIntOrNull() != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ItemPickerDialog(
    items: List<MaintenanceItemEntity>,
    onPick: (MaintenanceItemEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which service?") },
        text = {
            // heightIn guard: an unbounded LazyColumn inside a dialog can
            // blow up measurement on some Compose versions.
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(items, key = { it.id }) { item ->
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(item) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LogServiceDialog(
    item: MaintenanceItemEntity,
    defaultKm: Int?,
    onConfirm: (km: Int?, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var kmText by remember { mutableStateOf(defaultKm?.toString() ?: "") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log: ${item.name}") },
        text = {
            Column {
                Text("Date: today", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = kmText, onValueChange = { kmText = it.filter(Char::isDigit) },
                                  label = { Text("Odometer at service (km, optional)") })
                OutlinedTextField(value = note, onValueChange = { note = it },
                                  label = { Text("Note (optional)") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(kmText.toIntOrNull(), note.ifBlank { null }) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ItemDetailDialog(
    row: MaintenanceItemUi,
    viewModel: MaintenanceViewModel,
    onLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    val historyFlow = remember(row.item.id) { viewModel.historyFor(row.item.id) }
    val logs by historyFlow.collectAsState(initial = emptyList())
    // Spec §8.5: intervals are editable per item (builtins included — only
    // deletion is builtin-restricted). Emptying BOTH fields is rejected by
    // the repository and surfaces via state.message.
    var kmText by remember(row.item.id) { mutableStateOf(row.item.intervalKm?.toString() ?: "") }
    var monthsText by remember(row.item.id) { mutableStateOf(row.item.intervalMonths?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.item.name) },
        text = {
            Column {
                Row {
                    OutlinedTextField(
                        value = kmText, onValueChange = { kmText = it.filter(Char::isDigit) },
                        label = { Text("Interval (km)") }, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = monthsText, onValueChange = { monthsText = it.filter(Char::isDigit) },
                        label = { Text("Interval (months)") }, modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = {
                    viewModel.updateItem(row.item.copy(
                        intervalKm = kmText.toIntOrNull(),
                        intervalMonths = monthsText.toIntOrNull(),
                    ))
                }) { Text("Save intervals") }
                Text("[${row.item.confidence}] ${row.item.note}",
                     style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)) {
                    Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = row.item.enabled, onCheckedChange = { on ->
                        viewModel.updateItem(row.item.copy(enabled = on))
                    })
                }
                Text("History", style = MaterialTheme.typography.labelMedium,
                     modifier = Modifier.padding(top = 8.dp))
                if (logs.isEmpty()) {
                    Text("No services logged yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    logs.forEach { log ->
                        Text(
                            "• ${formatDate(log.epochMs)}${log.km?.let { " · $it km" } ?: ""}" +
                                (log.note?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (!row.item.builtin) {
                    TextButton(onClick = {
                        viewModel.deleteCustomItem(row.item.id); onDismiss()
                    }) { Text("Delete custom item", color = KlColors.Attention) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onLog) { Text("Mark done…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AddItemDialog(
    onConfirm: (name: String, category: String, km: Int?, months: Int?, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("INSPECTION") }
    var km by remember { mutableStateOf("") }
    var months by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var categoryMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom item") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it },
                                  label = { Text("Name") })
                TextButton(onClick = { categoryMenu = true }) { Text("Category: $category") }
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    listOf("ENGINE", "TRANSMISSION", "BRAKES", "FLUIDS", "INSPECTION").forEach { c ->
                        DropdownMenuItem(text = { Text(c) },
                                         onClick = { category = c; categoryMenu = false })
                    }
                }
                Row {
                    OutlinedTextField(value = km, onValueChange = { km = it.filter(Char::isDigit) },
                                      label = { Text("km") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = months, onValueChange = { months = it.filter(Char::isDigit) },
                                      label = { Text("months") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = note, onValueChange = { note = it },
                                  label = { Text("Why it matters (note)") })
                Text("Set a km interval, a month interval, or both.",
                     style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (km.toIntOrNull() != null || months.toIntOrNull() != null),
                onClick = { onConfirm(name, category, km.toIntOrNull(), months.toIntOrNull(), note) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 3: Add the one ViewModel accessor the detail dialog needs.** In `MaintenanceViewModel`, add:

```kotlin
    fun historyFor(itemId: Long) = maintenance.observeLogsFor(itemId)
```

- [ ] **Step 4: Full suite + APK:**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
Expected: both `BUILD SUCCESSFUL`. Leave for review.

---

## Task 25: MainActivity — four tabs, theme, reminder debug hook, notification intent

**Files:**
- Modify: `android/app/src/main/java/com/kompressorlink/app/MainActivity.kt` (full replacement)

- [ ] **Step 1: Full replacement of `MainActivity.kt`:**

```kotlin
package com.kompressorlink.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kompressorlink.app.connection.CdmAssociator
import com.kompressorlink.app.dashboard.DashboardScreen
import com.kompressorlink.app.dashboard.DashboardViewModel
import com.kompressorlink.app.dtc.DtcScreen
import com.kompressorlink.app.dtc.DtcViewModel
import com.kompressorlink.app.health.HealthScreen
import com.kompressorlink.app.health.HealthViewModel
import com.kompressorlink.app.maintenance.MaintenanceCheckWorker
import com.kompressorlink.app.maintenance.MaintenanceScreen
import com.kompressorlink.app.maintenance.MaintenanceViewModel
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.ui.components.ConnectionBanner
import com.kompressorlink.app.ui.theme.KompressorLinkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var associator: CdmAssociator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KompressorLinkApp).container
        associator = CdmAssociator(this) { mac ->
            container.persistAssociation(mac)
        }
        // A maintenance-reminder tap lands on the Maintenance tab (spec §6.4).
        val startTab = intent.getStringExtra(MaintenanceCheckWorker.EXTRA_START_TAB)
        setContent {
            KompressorLinkTheme {
                AppUi(
                    container = container,
                    startTab = startTab,
                    onPairRequest = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            associator.associate()
                        } else {
                            Toast.makeText(this, "Pairing requires Android 13+",
                                           Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
    }
}

private data class Tab(val route: String, val label: String)

private val TABS = listOf(
    Tab("dashboard", "Dashboard"),
    Tab("health", "Health"),
    Tab("dtc", "DTCs"),
    Tab("maintenance", "Maintenance"),
)

@Composable
private fun tabIcon(route: String) = when (route) {
    "dashboard" -> Icons.Filled.Home
    "health" -> Icons.Filled.Favorite
    "dtc" -> Icons.Filled.Warning
    else -> Icons.Filled.Build
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUi(container: AppContainer, startTab: String?, onPairRequest: () -> Unit) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val choice by container.choiceStore.choice
        .collectAsState(initial = SourceChoice.SIMULATED_HEALTHY)
    val connection by container.telemetrySource.connectionState.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    // Runtime permissions, requested once at startup (unchanged from Phase 4).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* denial surfaces visibly: BLE connect / notifications just fail */ }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    LaunchedEffect(startTab) {
        if (startTab != null && TABS.any { it.route == startTab }) {
            navController.navigate(startTab) { launchSingleTop = true }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KompressorLink") },
                actions = {
                    TextButton(onClick = { menuOpen = true }) {
                        Text(if (choice == SourceChoice.REAL_BLE) "BLE" else "SIM")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Pair with device…") },
                            onClick = { menuOpen = false; onPairRequest() },
                        )
                        DropdownMenuItem(
                            text = { Text("Run reminder check now") },
                            onClick = {
                                menuOpen = false
                                // Debug/acceptance hook (spec §6.4): proves the
                                // notification path without waiting 24 h.
                                scope.launch {
                                    MaintenanceCheckWorker.runCheck(
                                        container, navController.context.applicationContext,
                                    )
                                }
                            },
                        )
                        HorizontalDivider()
                        SourceChoice.entries.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(if (c == choice) "✓ ${c.displayName}" else c.displayName) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { container.choiceStore.set(c) }
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            val backStack by navController.currentBackStackEntryAsState()
            val route = backStack?.destination?.route
            NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = { navController.navigate(tab.route) { launchSingleTop = true } },
                        icon = { Icon(tabIcon(tab.route), contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ConnectionBanner(connection)
            NavHost(
                navController = navController,
                startDestination = "dashboard",
            ) {
                composable("dashboard") {
                    val vm: DashboardViewModel = viewModel(initializer = {
                        DashboardViewModel(container.telemetrySource, container.referenceRepository,
                                           container.liveWarningMonitor.levels)
                    })
                    DashboardScreen(vm)
                }
                composable("health") {
                    val vm: HealthViewModel = viewModel(initializer = {
                        HealthViewModel(
                            container.sessionRepository, container.warningRepository,
                            container.liveWarningMonitor.levels, container.telemetrySource,
                            container.referenceRepository,
                        )
                    })
                    HealthScreen(vm)
                }
                composable("dtc") {
                    val vm: DtcViewModel = viewModel(initializer = {
                        DtcViewModel(container.telemetrySource, container.dtcRepository)
                    })
                    DtcScreen(vm)
                }
                composable("maintenance") {
                    val vm: MaintenanceViewModel = viewModel(initializer = {
                        MaintenanceViewModel(
                            container.maintenanceRepository, container.odometerRepository,
                            container.sessionRepository,
                        )
                    })
                    MaintenanceScreen(vm)
                }
            }
        }
    }
}
```

Note: `Icons.Filled.Home/Favorite/Warning/Build` are in the core `material-icons` set that ships with `androidx.compose.material3` via the BOM — no `material-icons-extended` dependency. If any of the four fails to resolve, substitute another CORE icon rather than adding the extended artifact (it's huge).

- [ ] **Step 2: Full suite + APK:**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
Expected: both `BUILD SUCCESSFUL`. Leave for review.

---

## Task 26: Final verification + phone acceptance (agent Steps 1–2, USER Steps 3–8)

The spec §10 acceptance gate, bench form — simulated sources only, no hardware.

**Files:** none (verification only).

- [ ] **Step 1 (agent): Full verification battery:**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\C180 Project\android"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL` twice; count and record the total test number (all pre-existing 53 + every suite this plan added). Confirm `git status` shows NO changes under `firmware/` or `pipeline/`.

- [ ] **Step 2 (agent): Write the task report** summarizing: total tests, APK path (`android/app/build/outputs/apk/debug/app-debug.apk`), and the honest carry-forward list (everything real-data — drift on real drives, odometer accuracy, reminder delivery with the app force-stopped on One UI, all BLE behavior — validates only after the hardware/car phases).

- [ ] **Step 3 (user): Install the APK on the S23 FE** (USB debugging on): `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r android\app\build\outputs\apk\debug\app-debug.apk`

- [ ] **Step 4 (user): Dashboard checks.** Four tabs navigate. On *Simulated: fault* — LTFT reads +18 % with an amber value and band-bar marker in the amber zone, breather-hose hint shown, **no color flicker** while values wobble; "Warm idle detected" chip visible; battery tile captioned "charging · 13.8–14.5 V (Confirmed)"; MAP/O2 tiles neutral; on *Simulated: sparse* the MAP/O2 tiles show "Not provided by ECU".

- [ ] **Step 5 (user): Build sim history.** Let *Simulated: healthy* run ≥ 90 s, switch to *Simulated: fault* for ≥ 90 s, back to healthy ≥ 90 s (each switch closes a session). Health tab: real mode shows the honest empty state; **Demo mode ON** shows the amber DEMO ribbon, populated subsystem cards, "Collecting baseline · N of 8 drives" with a live count, and at least one warning in the feed (the fault scenario's LTFT sustained-amber or its stored-DTC warning). Acknowledge works and dims the row.

- [ ] **Step 6 (user): Maintenance checks.** Seeded 11-item schedule lists with notes and confidence tags; "Update odometer" → enter your real odometer → km tracking activates ("Enter your odometer" disappears); enter a LOWER value → rejected with the can't-go-backwards message; log "Engine oil + filter" done today at current km → row goes green/OK and history shows the entry; edit an item's interval to something tiny (e.g. 1 month) so it's overdue → overflow "Run reminder check now" → **a real Android notification appears**; tapping it opens the Maintenance tab; restore the interval afterwards.

- [ ] **Step 7 (user): DTC checks.** On *Simulated: fault*: stored P0171 card shows "Check soon" chip + "W203-specific" chip; expanded → What's happening / Can I keep driving? / Check first (breather hoses FIRST) / italic note; pending P1570 card shows "Code family only" + "Manufacturer-specific powertrain code, vehicle speed & idle control group" + "Unknown urgency". On *Simulated: healthy*: "No stored codes — nothing to worry about here."

- [ ] **Step 8 (user): Sign-off.** All of Steps 4–7 pass → Phase 4.5's gate is met. Commit via the VS Code Source Control GUI. Remaining before this app touches reality: the hardware phases (Phase 2 build, Phase 3 car bring-up, Phase 4 Task 21 bench BLE session) — at which point every quarantined REAL path starts filling in with no further app changes.
