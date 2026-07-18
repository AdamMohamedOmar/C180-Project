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
