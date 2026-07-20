package com.kompressorlink.app.sync

import com.kompressorlink.app.health.SessionAggregator
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideReplayerTest {

    private val refs = ReferenceRepository { name ->
        File("src/main/assets/$name").readText()
    }

    private fun agg() = SessionAggregator(refs, isReal = true)

    @Test
    fun `continuous rows produce one bucket per 500ms span`() {
        // Two signals every 500 ms for 5 s of real reporting -> exactly
        // 10 buckets, no gap involved (regression guard for normal replay).
        val rows = ArrayList<RideCsv.Row>()
        var t = 0L
        while (t < 5_000L) {
            rows.add(RideCsv.Row(t, Signal.RPM, 800f))
            rows.add(RideCsv.Row(t + 100, Signal.ECT, 90f))
            t += 500
        }
        val fed = RideReplayer.replay(rows, agg())
        assertEquals(10, fed)
    }

    @Test
    fun `a long gap does not tick thousands of frozen carry-forward buckets`() {
        val rows = ArrayList<RideCsv.Row>()
        // 4 s of real reporting before a multi-hour gap.
        var t = 0L
        while (t < 4_000L) {
            rows.add(RideCsv.Row(t, Signal.RPM, 800f))
            t += 500
        }
        // Ignition-off stretch: 5 hours of silence, no CSV rows at all --
        // ride_logger.cpp skips unavailable readings, and main.cpp's
        // ride_active is never reset across an ignition cycle, so a ride
        // file can genuinely span this.
        val gapEndT = t + 5 * 60 * 60 * 1000L
        rows.add(RideCsv.Row(gapEndT, Signal.RPM, 820f))
        rows.add(RideCsv.Row(gapEndT + 500, Signal.RPM, 830f))

        val fed = RideReplayer.replay(rows, agg())

        // Real reporting density is ~8 buckets before the gap (4000/500)
        // plus ~2 after -- NOT ~36,000 buckets (5 h / 500 ms), which is
        // what ticking through the gap bucket-by-bucket would produce.
        assertTrue("expected a small bucket count from real density, got $fed", fed < 20)
    }

    @Test
    fun `warm idle seconds do not inflate across a gap following a warm-idle-looking snapshot`() {
        // The exact danger case the reviewer flagged: the last snapshot
        // before a long silence looks like warm idle (RPM 600-900, SPEED 0,
        // ECT>80 per DashboardLogic.isWarmIdle) -- precisely what a car
        // looks like right before shutoff. Without the gap guard this dwell
        // would be silently charged for the entire dead stretch.
        val rows = ArrayList<RideCsv.Row>()
        var t = 0L
        // 2 s of genuine warm idle immediately before the car is shut off.
        while (t < 2_000L) {
            rows.add(RideCsv.Row(t, Signal.RPM, 750f))
            rows.add(RideCsv.Row(t + 100, Signal.SPEED, 0f))
            rows.add(RideCsv.Row(t + 200, Signal.ECT, 90f))
            t += 500
        }
        // 3-hour parked gap, then the car resumes actually driving.
        val resumeT = t + 3 * 60 * 60 * 1000L
        rows.add(RideCsv.Row(resumeT, Signal.RPM, 2500f))
        rows.add(RideCsv.Row(resumeT + 100, Signal.SPEED, 60f))
        rows.add(RideCsv.Row(resumeT + 200, Signal.ECT, 92f))

        val aggregator = agg()
        RideReplayer.replay(rows, aggregator)

        // Bounded by the real ~2 s of pre-gap warm idle (plus at most one
        // bucket of rounding) -- not by the 3-hour gap.
        assertTrue(
            "warmIdleSeconds blew up to ${aggregator.warmIdleSeconds}, expected it bounded by real dwell",
            aggregator.warmIdleSeconds < 5f,
        )
    }

    @Test
    fun `warm idle seconds do not spike when the FIRST post-gap reading is also warm-idle-shaped`() {
        // Narrower than the test above: there, the post-gap resumption was
        // driving-shaped, which the mask reset alone handles fine (the
        // fresh reading just isn't warm-idle, so dtSec never gets gated
        // into warmIdleSeconds regardless of its size). HERE the very
        // first post-gap reading is ALSO warm-idle-shaped (engine
        // restarted and immediately idling) -- the case that survives the
        // mask reset alone, because SessionAggregator.add()'s dtSec comes
        // from `atMs - prevAtMs` with no gap-awareness of its own.
        // resetTimingAfterGap() closes this by also clearing prevAtMs.
        val rows = ArrayList<RideCsv.Row>()
        var t = 0L
        while (t < 2_000L) {
            rows.add(RideCsv.Row(t, Signal.RPM, 750f))
            rows.add(RideCsv.Row(t + 100, Signal.SPEED, 0f))
            rows.add(RideCsv.Row(t + 200, Signal.ECT, 90f))
            t += 500
        }
        val resumeT = t + 3 * 60 * 60 * 1000L
        rows.add(RideCsv.Row(resumeT, Signal.RPM, 750f))
        rows.add(RideCsv.Row(resumeT + 100, Signal.SPEED, 0f))
        rows.add(RideCsv.Row(resumeT + 200, Signal.ECT, 90f))

        val aggregator = agg()
        RideReplayer.replay(rows, aggregator)

        assertTrue(
            "warmIdleSeconds blew up to ${aggregator.warmIdleSeconds}",
            aggregator.warmIdleSeconds < 5f,
        )
    }

    @Test
    fun `out-of-band seconds do not spike on the first post-gap reading`() {
        // outOfBandSec has an even lower bar than warm idle to demonstrate
        // the same spike on: ONE signal outside its "always" band is
        // enough, no three-signal AND required. LTFT1's always-context
        // band is +-10 (SessionAggregatorTest's outOfBandSeconds test);
        // 15 is AMBER.
        val rows = ArrayList<RideCsv.Row>()
        var t = 0L
        while (t < 2_000L) {
            rows.add(RideCsv.Row(t, Signal.LTFT1, 15f))
            t += 500
        }
        val resumeT = t + 3 * 60 * 60 * 1000L
        rows.add(RideCsv.Row(resumeT, Signal.LTFT1, 15f))

        val aggregator = agg()
        RideReplayer.replay(rows, aggregator)

        val ltft = aggregator.buildStats(1).first { it.signal == "LTFT1" }
        assertTrue(
            "secondsOutOfBand blew up to ${ltft.secondsOutOfBand}",
            ltft.secondsOutOfBand < 5f,
        )
    }

    @Test
    fun `a gap mid-warmup abandons the warmup rate instead of reporting a gap-skewed slope`() {
        // Cold start begins normally, then a gap hits WHILE warmup is still
        // ACTIVE (well short of WARMUP_COMPLETE_ECT). Resuming and crossing
        // 80 degC shortly after must NOT silently produce a warmupRatePerMin
        // -- the true degC/min across an untracked pause can't be known (the
        // engine could have kept warming or cooled while parked).
        val rows = ArrayList<RideCsv.Row>()
        var t = 0L
        while (t < 4_000L) {
            rows.add(RideCsv.Row(t, Signal.ECT, 20f + t / 200f))  // slow, realistic climb, well under 80
            t += 500
        }
        val resumeT = t + 3 * 60 * 60 * 1000L
        // Resumes already past the completion threshold -- without the
        // abandon-on-gap fix this flips warmupState to DONE using a slope
        // wildly distorted by the untracked gap.
        rows.add(RideCsv.Row(resumeT, Signal.ECT, 85f))
        rows.add(RideCsv.Row(resumeT + 500, Signal.ECT, 86f))

        val aggregator = agg()
        RideReplayer.replay(rows, aggregator)

        val ect = aggregator.buildStats(1).first { it.signal == "ECT" }
        assertEquals(null, ect.warmupRatePerMin)
    }
}
