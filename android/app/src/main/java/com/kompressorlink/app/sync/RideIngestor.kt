package com.kompressorlink.app.sync

import com.kompressorlink.app.data.SessionRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.health.HealthTuning
import com.kompressorlink.app.health.SessionAggregator
import com.kompressorlink.app.reference.ReferenceRepository

// Turns a verified ride file into a quarantine-tagged session, or refuses
// with an honest reason. NEVER throws on malformed content — a bad file is
// a status, not a crash.
//
// Depends on the SessionRepository interface (not the raw SessionDao) to
// match this codebase's established convention — every other business-logic
// class touching sessions (HealthViewModel, PostSessionEvaluator) depends on
// the repository interface, never the DAO directly.
class RideIngestor(
    private val refs: ReferenceRepository,
    private val sessions: SessionRepository,
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
        //
        // Only run when a #time_sync marker exists. Without one, endedAt
        // above is anchored to the SYNC moment (nowEpochMs), not the ride's
        // true occurrence time — an inaccurate anchor could spuriously
        // overlap an unrelated, much-later REAL_BLE session and silently
        // discard a genuinely distinct drive (the primary WiFi-sync use
        // case is exactly "device logs for days disconnected, synced later
        // in a batch"). A time_sync-less ride also structurally CANNOT be a
        // live/device duplicate of a REAL_BLE session in the first place:
        // BleTelemetrySource.sendTimeSync() fires on every BLE
        // connect-ready transition, so if BLE was ever connected during
        // this ride — the only way it could also exist as a REAL_BLE
        // session — the marker would be present.
        if (source == SessionSource.REAL_RIDE && parsed.timeSync != null) {
            val recent = sessions.recent(setOf(SessionSource.REAL_BLE), 50)
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

        val id = sessions.record(
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
