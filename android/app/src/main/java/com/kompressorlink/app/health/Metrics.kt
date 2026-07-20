package com.kompressorlink.app.health

import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.reference.Band
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal

// Spec §5.1: cross-session intelligence operates on metrics — one scalar per
// session, extracted from contextual session_stats columns. A session
// carries a metric only when its contextual count >= its minSamples floor
// (the generic CONTEXT_MIN_SAMPLES by default; some dynamic metrics use a
// tighter, context-specific floor — see each entry below).
enum class MetricId(
    val displayName: String,
    val unit: String,
    val signal: Signal,
    val subsystem: Subsystem,
    val bandContext: String,
    val minSamples: Int = HealthTuning.CONTEXT_MIN_SAMPLES,
) {
    LTFT_WARM_IDLE("LTFT at warm idle", "%", Signal.LTFT1, Subsystem.FUELING, "always"),
    STFT_WARM_IDLE("STFT at warm idle", "%", Signal.STFT1, Subsystem.FUELING, "always"),
    MAF_WARM_IDLE("MAF at warm idle", "g/s", Signal.MAF_GS, Subsystem.AIR_INTAKE, "warm_idle"),
    ECT_WARM_IDLE("Coolant at warm idle", "°C", Signal.ECT, Subsystem.COOLING, "warm_idle"),
    BATT_CHARGING("Charging voltage", "V", Signal.BATT_V_ADC, Subsystem.BATTERY, "engine_running"),
    BATT_REST("Resting voltage", "V", Signal.BATT_V_ADC, Subsystem.BATTERY, "engine_off"),

    // Dynamic metrics (2026-07-17 enhancement plan). LTFT_LOAD_SENSITIVITY
    // models THE M271 signature fault: a breather/vacuum leak inflates trims
    // at idle and fades under load, so (load mean − idle mean) goes strongly
    // negative (PLAN.md §8, P0170/P0171 tree).
    LTFT_LOAD_SENSITIVITY("LTFT load response", "%", Signal.LTFT1, Subsystem.FUELING,
        "load_delta", HealthTuning.HIGH_LOAD_MIN_SAMPLES),
    MAF_HIGH_LOAD("MAF under load", "g/s", Signal.MAF_GS, Subsystem.AIR_INTAKE,
        "high_load", HealthTuning.HIGH_LOAD_MIN_SAMPLES),
    ECT_WARMUP_RATE("Warm-up rate", "°C/min", Signal.ECT, Subsystem.COOLING,
        "warmup", 1),
    O2_ACTIVITY_ONSET("O2 activity onset", "s", Signal.O2_B1S1_V, Subsystem.FUELING,
        "cold_start", 1);

    fun contextValue(stat: SessionStatEntity): Pair<Float, Int>? = when (this) {
        LTFT_WARM_IDLE, STFT_WARM_IDLE, MAF_WARM_IDLE, ECT_WARM_IDLE ->
            stat.warmIdleMean?.let { it to stat.warmIdleCount }
        BATT_CHARGING -> stat.engineRunningMean?.let { it to stat.engineRunningCount }
        BATT_REST -> stat.engineOffMean?.let { it to stat.engineOffCount }
        LTFT_LOAD_SENSITIVITY -> {
            val hi = stat.highLoadMean
            val wi = stat.warmIdleMean
            // Needs BOTH contexts healthy in the same session; the idle side
            // keeps the generic 30-sample floor, the load side uses this
            // metric's own minSamples via MetricSeries.
            if (hi != null && wi != null &&
                stat.warmIdleCount >= HealthTuning.CONTEXT_MIN_SAMPLES
            ) (hi - wi) to stat.highLoadCount else null
        }
        MAF_HIGH_LOAD -> stat.highLoadMean?.let { it to stat.highLoadCount }
        // For the two event-shaped metrics the aggregator already applied
        // every validity gate — presence IS eligibility (count 1 vs floor 1).
        ECT_WARMUP_RATE -> stat.warmupRatePerMin?.let { it to 1 }
        O2_ACTIVITY_ONSET -> stat.o2OnsetS?.let { it to 1 }
    }
}

// One session's contribution to a metric series. eligible = usable for the
// personal baseline (spec §5.2: signal never went raw-RED in the session AND
// the session carried no stored DTC — don't learn "normal" from a visibly
// faulting car). Drift uses ALL points, eligible or not (spec §5.3).
data class MetricPoint(
    val sessionId: Long,
    val endedAtEpochMs: Long,
    val value: Float,
    val eligible: Boolean,
)

object MetricSeries {
    /** Points in ascending session-end order. */
    fun build(
        metric: MetricId,
        sessions: List<SessionEntity>,
        stats: List<SessionStatEntity>,
    ): List<MetricPoint> {
        val bySession = stats.filter { it.signal == metric.signal.name }.associateBy { it.sessionId }
        return sessions.sortedBy { it.endedAtEpochMs }.mapNotNull { session ->
            val stat = bySession[session.id] ?: return@mapNotNull null
            val (value, count) = metric.contextValue(stat) ?: return@mapNotNull null
            if (count < metric.minSamples) return@mapNotNull null
            MetricPoint(
                sessionId = session.id,
                endedAtEpochMs = session.endedAtEpochMs,
                value = value,
                eligible = stat.worstLevel != "RED" && !session.hasStoredDtc,
            )
        }
    }

    /** The absolute band this metric is judged against — same parsed JSON the
     *  dashboard uses; one source of truth (spec §5.1). Baseline-only metrics
     *  (no matching context in w203_bands.json) return null here — that's
     *  deliberate scope honesty, not a bug (spec 2026-07-17 enhancement §5.1). */
    fun bandFor(metric: MetricId, refs: ReferenceRepository): Band? =
        refs.bandsFor(metric.signal).firstOrNull { it.context == metric.bandContext }
}
