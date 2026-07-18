package com.kompressorlink.app.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// First real Room migration in this codebase (schema v1 -> v2, 2026-07-17
// enhancement plan): 4 new nullable session_stats columns + the ride_files
// sync-state table. runMigrationsAndValidate diff-checks the migrated
// schema against the KSP-exported v2 schema JSON (validateDroppedTables =
// true) -- that proves the SHAPE is right. The row-level assertions below
// prove the migration is right for existing DATA too: session_stats is the
// one table this migration ALTER TABLEs, and a real Phase 4.5 install
// already has rows in it — this is exactly the scenario that could
// silently corrupt on the one real device that matters.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KlDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate 1 to 2 preserves sessions and session_stats rows and adds new shapes`() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL(
                "INSERT INTO sessions (startedAtEpochMs, endedAtEpochMs, source, " +
                    "snapshotCount, warmIdleSeconds, distanceKm, hasStoredDtc) " +
                    "VALUES (1000, 61000, 'REAL_BLE', 240, 60.0, 4.2, 0)"
            )
            // A pre-existing session_stats row using ONLY the v1 column set
            // (android/app/schemas/.../1.json) — the row shape a real
            // Phase 4.5 install already has on disk before this migration
            // ever runs on it.
            execSQL(
                "INSERT INTO session_stats (sessionId, signal, sampleCount, mean, " +
                    "min, max, stdDev, secondsOutOfBand, worstLevel, warmIdleMean, " +
                    "warmIdleCount, engineRunningMean, engineRunningCount, " +
                    "engineOffMean, engineOffCount) VALUES " +
                    "(1, 'LTFT1', 500, 4.2, -2.0, 9.0, 1.1, 12.0, 'OK', 4.0, 200, NULL, 0, NULL, 0)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test", 2, true, MIGRATION_1_2)
        db.query("SELECT COUNT(*) FROM sessions").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }

        // The pre-existing session_stats row: old columns must survive
        // untouched, and the 4 new columns must come back as the honest
        // "not measured this session" migrated defaults (Untested contract,
        // CLAUDE.md rule 4) — never a guessed or zeroed-out old value.
        db.query("SELECT * FROM session_stats WHERE sessionId = 1 AND signal = 'LTFT1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(500, c.getInt(c.getColumnIndexOrThrow("sampleCount")))
            assertEquals(4.2f, c.getFloat(c.getColumnIndexOrThrow("mean")), 1e-4f)
            assertEquals(-2.0f, c.getFloat(c.getColumnIndexOrThrow("min")), 1e-4f)
            assertEquals(9.0f, c.getFloat(c.getColumnIndexOrThrow("max")), 1e-4f)
            assertEquals("OK", c.getString(c.getColumnIndexOrThrow("worstLevel")))
            assertEquals(4.0f, c.getFloat(c.getColumnIndexOrThrow("warmIdleMean")), 1e-4f)
            assertEquals(200, c.getInt(c.getColumnIndexOrThrow("warmIdleCount")))
            assertTrue(c.isNull(c.getColumnIndexOrThrow("engineRunningMean")))

            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("highLoadCount")))
            assertTrue(c.isNull(c.getColumnIndexOrThrow("highLoadMean")))
            assertTrue(c.isNull(c.getColumnIndexOrThrow("warmupRatePerMin")))
            assertTrue(c.isNull(c.getColumnIndexOrThrow("o2OnsetS")))
        }

        db.query("SELECT COUNT(*) FROM ride_files").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
    }
}
