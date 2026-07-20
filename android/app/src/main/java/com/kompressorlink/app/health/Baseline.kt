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
     *
     * band: null for baseline-only metrics (2026-07-17 enhancement plan —
     * e.g. ECT_WARMUP_RATE, MAF_HIGH_LOAD, O2_ACTIVITY_ONSET have no absolute
     * band in w203_bands.json; MetricSeries.bandFor returns null for them).
     * The personal envelope still learns for these — only the degenerate-MAD
     * widen fraction needs a width reference to fall back to.
     */
    fun evaluate(points: List<MetricPoint>, band: Band?): Result {
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
        // Band-less (baseline-only) metrics fall back to the median's own
        // scale: |median|, floored at 1 unit so a metric whose median sits
        // near zero still gets a usable envelope. [Best estimate]
        val widthRef = band?.let { it.hi - it.lo } ?: maxOf(abs(med), 1f)
        // Degenerate-MAD guard (spec §5.2): a car this consistent would
        // otherwise alarm on noise.
        val delta = if (mad < HealthTuning.BASELINE_DEGENERATE_MAD_FRACTION * widthRef) {
            HealthTuning.BASELINE_DEGENERATE_WIDEN_FRACTION * widthRef
        } else {
            HealthTuning.BASELINE_MAD_K * mad
        }
        return Result.Active(Envelope(med, mad, med - delta, med + delta, eligible.size))
    }

    /** Spec §5.2: deviation = outside the personal envelope but INSIDE the
     *  absolute band (beyond the band, live severity owns the story).
     *  band == null (baseline-only metric, no absolute band at all): no live
     *  severity system watches this signal either, so the carve-out doesn't
     *  apply — deviation is simply "outside the personal envelope". */
    fun isDeviation(current: Float, envelope: Envelope, band: Band?): Boolean =
        (current < envelope.lo || current > envelope.hi) &&
            (band == null || (current >= band.lo && current <= band.hi))
}
