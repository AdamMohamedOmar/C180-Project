package com.kompressorlink.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

// Phase 4.5 shipped version 1 with no users and no migrations. v2
// (2026-07-17 enhancement plan) adds dynamic-context session_stats columns
// and the ride_files sync-state table via MIGRATION_1_2 — this codebase's
// first real migration. exportSchema writes the schema JSON into
// android/app/schemas/ so future migrations start honest (spec §2).
@Database(
    entities = [
        SessionEntity::class, SessionStatEntity::class, WarningEntity::class,
        MaintenanceItemEntity::class, ServiceLogEntity::class, OdometerEntryEntity::class,
        RideFileEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class KlDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun warningDao(): WarningDao
    abstract fun maintenanceDao(): MaintenanceDao
    abstract fun odometerDao(): OdometerDao
    abstract fun rideFileDao(): RideFileDao

    companion object {
        fun build(context: Context): KlDatabase =
            Room.databaseBuilder(context, KlDatabase::class.java, "kl.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        fun inMemory(context: Context): KlDatabase =
            Room.inMemoryDatabaseBuilder(context, KlDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
