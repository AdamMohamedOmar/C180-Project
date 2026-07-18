package com.kompressorlink.app.health

import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.reference.Band
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal

// Spec §5.1: cross-session intelligence operates on metrics — one scalar per
// session, extracted from contextual session_stats columns. A session
// carries a metric only when its contextual count >= CONTEXT_MIN_SAMPLES.
enum class MetricId(
    val displayName: String,
    val unit: String,
    val signal: Signal,
    val subsystem: Subsystem,
    val bandContext: String,
) {
    LTFT_WARM_IDLE("LTFT at warm idle", "%", Signal.LTFT1, Subsystem.FUELING, "always"),
    STFT_WARM_IDLE("STFT at warm idle", "%", Signal.STFT1, Subsystem.FUELING, "always"),
    MAF_WARM_IDLE("MAF at warm idle", "g/s", Signal.MAF_GS, Subsystem.AIR_INTAKE, "warm_idle"),
    ECT_WARM_IDLE("Coolant at warm idle", "°C", Signal.ECT, Subsystem.COOLING, "warm_idle"),
    BATT_CHARGING("Charging voltage", "V", Signal.BATT_V_ADC, Subsystem.BATTERY, "engine_running"),
    BATT_REST("Resting voltage", "V", Signal.BATT_V_ADC, Subsystem.BATTERY, "engine_off");

    fun contextValue(stat: SessionStatEntity): Pair<Float, Int>? = when (this) {
        LTFT_WARM_IDLE, STFT_WARM_IDLE, MAF_WARM_IDLE, ECT_WARM_IDLE ->
            stat.warmIdleMean?.let { it to stat.warmIdleCount }
        BATT_CHARGING -> stat.engineRunningMean?.let { it to stat.engineRunningCount }
        BATT_REST -> stat.engineOffMean?.let { it to stat.engineOffCount }
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
            if (count < HealthTuning.CONTEXT_MIN_SAMPLES) return@mapNotNull null
            MetricPoint(
                sessionId = session.id,
                endedAtEpochMs = session.endedAtEpochMs,
                value = value,
                eligible = stat.worstLevel != "RED" && !session.hasStoredDtc,
            )
        }
    }

    /** The absolute band this metric is judged against — same parsed JSON the
     *  dashboard uses; one source of truth (spec §5.1). */
    fun bandFor(metric: MetricId, refs: ReferenceRepository): Band? =
        refs.bandsFor(metric.signal).firstOrNull { it.context == metric.bandContext }
}
