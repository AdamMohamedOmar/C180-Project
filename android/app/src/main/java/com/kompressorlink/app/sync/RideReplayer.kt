package com.kompressorlink.app.sync

import com.kompressorlink.app.health.HealthTuning
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
            // Gap close, mirroring SessionRecorder's live-path guard
            // (HealthTuning.SESSION_CLOSE_GAP_MS). A ride file can span a
            // genuinely dead stretch: ride_logger.cpp only writes a row
            // `if (reading.available)` -- a poll failure writes nothing --
            // and main.cpp's `ride_active` is set true once and never reset,
            // so one file can stay open across an entire ignition-off period
            // (no power_mgr sleep-gating yet). Without this branch, `mask`
            // staying nonzero forever would tick a frozen carry-forward
            // snapshot every 500 ms across the WHOLE dead stretch -- each
            // one a real SessionAggregator.add() call with dtSec = 0.5,
            // silently inflating warmIdleSeconds/distanceKm and swamping
            // Welford stats with duplicate points if the frozen values
            // happen to look like warm idle (exactly what a car looks like
            // right before shutoff). Jump straight to the bucket containing
            // the next row and drop all carried-forward "available" state,
            // so post-gap readings aren't reported as still fresh.
            //
            // resetTimingAfterGap() closes a second, narrower hole the mask
            // reset alone doesn't: SessionAggregator's own dtSec is derived
            // from atMs vs. the LAST add() call, with no gap-awareness of
            // its own, so even a single genuinely-fresh post-gap reading
            // would otherwise carry the full gap length into whatever
            // context it happens to land in (one big spike rather than the
            // tick-storm above, but the live path never has this problem at
            // all -- SessionRecorder gets a brand-new aggregator, and thus
            // a clean prevAtMs, after every gap-triggered close()).
            if (i < rows.size && rows[i].tMs - bucketEnd > HealthTuning.SESSION_CLOSE_GAP_MS) {
                bucketEnd = rows[i].tMs + BUCKET_MS
                mask = 0
                into.resetTimingAfterGap()
            } else {
                bucketEnd += BUCKET_MS
            }
        }
        return seq
    }
}
