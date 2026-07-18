package com.kompressorlink.app.health

import com.kompressorlink.app.data.SessionRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.WarningRepository
import com.kompressorlink.app.data.WarningSource
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.reference.ReferenceRepository
import java.util.Locale

// Runs after every persisted session (spec §5.2/§5.3): re-evaluates each
// metric's personal baseline (envelope from PRIOR history, judging the
// just-closed session) and drift, raising WATCH warnings through the
// deduping repository. Source honesty: a sim session is evaluated against
// sim history and raises SIM-tagged warnings — visible only in Demo mode.
class PostSessionEvaluator(
    private val sessions: SessionRepository,
    private val warnings: WarningRepository,
    private val refs: ReferenceRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun onSessionClosed(sessionId: Long, source: SessionSource) {
        val sourceSet = if (source.isReal) SessionSource.REAL else SessionSource.SIM
        val warningSource = WarningSource.from(source)
        val history = sessions
            .recent(sourceSet, HealthTuning.BASELINE_WINDOW + 10)
            .sortedBy { it.endedAtEpochMs }
        if (history.isEmpty()) return
        val stats = sessions.statsForSessions(history.map { it.id })
        val atMs = now()

        for (metric in MetricId.entries) {
            val band = MetricSeries.bandFor(metric, refs) ?: continue
            val points = MetricSeries.build(metric, history, stats)
            if (points.isEmpty()) continue

            val currentPoint = points.lastOrNull { it.sessionId == sessionId }
            if (currentPoint != null) {
                val prior = points.filterNot { it.sessionId == sessionId }
                val baseline = Baseline.evaluate(prior, band)
                if (baseline is Baseline.Result.Active &&
                    Baseline.isDeviation(currentPoint.value, baseline.envelope, band)
                ) {
                    val env = baseline.envelope
                    val spread = env.hi - env.median
                    warnings.raise(WarningEntity(
                        createdAtEpochMs = atMs, lastSeenAtEpochMs = atMs, sessionId = sessionId,
                        subsystem = metric.subsystem.name, signal = metric.signal.name,
                        level = "WATCH", kind = "BASELINE_DEVIATION",
                        title = "${metric.displayName} ${if (currentPoint.value > env.median) "above" else "below"} your car's usual",
                        // Normative template (spec §5.2).
                        detail = String.format(Locale.US,
                            "%s is outside your car's usual range (%.1f %s vs typical %.1f ± %.1f %s) — inside absolute limits, worth watching.",
                            metric.displayName, currentPoint.value, metric.unit,
                            env.median, spread, metric.unit),
                        acknowledged = false, source = warningSource.name,
                        dedupeKey = "BASELINE_DEVIATION:${warningSource.name}:${metric.subsystem.name}:${metric.name}",
                    ))
                }
            }

            val drift = Drift.evaluate(metric, points, band)
            if (drift is Drift.Result.Drifting) {
                warnings.raise(WarningEntity(
                    createdAtEpochMs = atMs, lastSeenAtEpochMs = atMs, sessionId = sessionId,
                    subsystem = metric.subsystem.name, signal = metric.signal.name,
                    level = "WATCH", kind = "DRIFT",
                    title = "${metric.displayName} drifting ${if (drift.rising) "up" else "down"}",
                    detail = drift.message,
                    acknowledged = false, source = warningSource.name,
                    dedupeKey = "DRIFT:${warningSource.name}:${metric.subsystem.name}:${metric.name}",
                ))
            }
        }
    }
}
