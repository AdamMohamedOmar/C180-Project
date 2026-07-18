package com.kompressorlink.app.health

import com.kompressorlink.app.reference.Band
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Spec §5.3: the honest "predict before it happens" — time-to-threshold
// extrapolation with explicit "~" and "at this rate" phrasing. Never a
// probability. Drift uses ALL points (eligible or not — it must see
// deterioration). Already-beyond-the-edge metrics stay silent here: live
// severity and baseline own that story.
object Drift {
    sealed interface Result {
        data class Gated(val points: Int, val needed: Int) : Result
        data object Stable : Result
        data class Drifting(
            val slopePerWeek: Float,
            val weeksToEdge: Int,
            val rising: Boolean,
            val message: String,
        ) : Result
    }

    fun evaluate(metric: MetricId, points: List<MetricPoint>, band: Band): Result {
        val window = points.takeLast(HealthTuning.DRIFT_WINDOW)
        if (window.size < HealthTuning.DRIFT_MIN_POINTS) {
            return Result.Gated(window.size, HealthTuning.DRIFT_MIN_POINTS)
        }
        val spanDays = (window.last().endedAtEpochMs - window.first().endedAtEpochMs) / Baseline.DAY_MS.toFloat()
        if (spanDays < HealthTuning.DRIFT_MIN_SPAN_DAYS) {
            return Result.Gated(window.size, HealthTuning.DRIFT_MIN_POINTS)
        }
        val slopePerDay = TheilSen.slopePerDay(window) ?: return Result.Stable
        if (slopePerDay == 0f) return Result.Stable
        val current = Baseline.median(window.takeLast(3).map { it.value })
        if (current < band.lo || current > band.hi) return Result.Stable
        val slopePerWeek = slopePerDay * 7f
        val edge = if (slopePerDay > 0f) band.hi else band.lo
        val weeks = (edge - current) / slopePerWeek
        if (weeks < HealthTuning.DRIFT_MIN_WEEKS || weeks > HealthTuning.DRIFT_MAX_WEEKS) {
            return Result.Stable
        }
        val rising = slopePerDay > 0f
        val weeksRounded = weeks.roundToInt().coerceAtLeast(1)
        // Normative wording template (spec §5.3).
        val message = String.format(
            Locale.US,
            "%s is %s ≈ %.1f %s/week — at this rate it crosses %.0f %s in ~%d weeks. %s",
            metric.displayName, if (rising) "rising" else "falling",
            abs(slopePerWeek), metric.unit, edge, metric.unit, weeksRounded, band.hint,
        )
        return Result.Drifting(slopePerWeek, weeksRounded, rising, message)
    }
}
