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
    // Dynamic contexts (schema v2, 2026-07-17 enhancement plan):
    val highLoadMean: Float? = null,    // LTFT1, MAF_GS only
    val highLoadCount: Int = 0,
    val warmupRatePerMin: Float? = null, // ECT only — °C/min over a completed cold-start warm-up
    val o2OnsetS: Float? = null,         // O2_B1S1_V only — s to first full swing, cold starts only
)
