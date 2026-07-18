package com.kompressorlink.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

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
