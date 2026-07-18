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
    val sessionId: Long?,        // null for live/DTC warnings; set for baseline/drift verdicts computed from a completed session
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
