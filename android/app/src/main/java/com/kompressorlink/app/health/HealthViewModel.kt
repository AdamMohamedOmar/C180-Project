package com.kompressorlink.app.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.SessionRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.WarningRepository
import com.kompressorlink.app.data.WarningSource
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WarningUi(
    val id: Long,
    val title: String,
    val detail: String,
    val level: String,        // "WATCH" | "ATTENTION"
    val timeText: String,     // "Jul 13 18:42"
    val acknowledged: Boolean,
)

data class HealthUiState(
    val demoMode: Boolean,
    val verdict: OverallVerdict,
    val cards: List<SubsystemCard>,
    val warnings: List<WarningUi>,
    val driftGateFooter: String?,  // spec §8.3 footer while drift is globally gated
    val emptyState: String?,       // zero sessions in the selected mode
)

// Health screen state (spec §8.3). Demo mode swaps every DB query to
// SIM-sourced rows under the loud ribbon; the live layer (levels + DTC
// report) always reflects the active source. Not persisted — defaults off.
@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModel(
    private val sessions: SessionRepository,
    private val warningRepo: WarningRepository,
    liveLevels: StateFlow<Map<Signal, GaugeLevel>>,
    source: TelemetrySource,
    private val refs: ReferenceRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val demo = MutableStateFlow(false)

    fun setDemoMode(on: Boolean) {
        demo.value = on
    }

    fun acknowledge(id: Long) {
        viewModelScope.launch { warningRepo.acknowledge(id) }
    }

    val uiState: StateFlow<HealthUiState> = demo.flatMapLatest { demoOn ->
        val sourceSet = if (demoOn) SessionSource.SIM else SessionSource.REAL
        val warningSource = if (demoOn) WarningSource.SIM else WarningSource.REAL
        combine(
            sessions.observeRecent(sourceSet, HealthTuning.BASELINE_WINDOW + 10),
            warningRepo.observe(warningSource, 50),
            liveLevels,
            source.dtcReport,
        ) { sessionRows, warningRows, levels, dtc ->
            Inputs(demoOn, sessionRows, warningRows, levels, dtc)
        }
    }.mapLatest { inputs ->
        build(inputs)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        HealthUiState(
            demoMode = false,
            verdict = OverallVerdict(HealthStatus.OK, "All systems look normal", "No warnings from your car's data"),
            cards = emptyList(), warnings = emptyList(),
            driftGateFooter = null, emptyState = null,
        ),
    )

    private data class Inputs(
        val demo: Boolean,
        val sessions: List<com.kompressorlink.app.data.db.SessionEntity>,
        val warnings: List<WarningEntity>,
        val levels: Map<Signal, GaugeLevel>,
        val dtc: com.kompressorlink.app.telemetry.DtcReport?,
    )

    private suspend fun build(inputs: Inputs): HealthUiState {
        val ordered = inputs.sessions.sortedBy { it.endedAtEpochMs }
        val stats = sessions.statsForSessions(ordered.map { it.id })

        val driftMap = HashMap<MetricId, Drift.Result>()
        val baselineMap = HashMap<MetricId, Baseline.Result>()
        for (metric in MetricId.entries) {
            // band is null for baseline-only metrics (2026-07-17 enhancement
            // plan: ECT_WARMUP_RATE, MAF_HIGH_LOAD, O2_ACTIVITY_ONSET carry no
            // w203_bands.json entry). Baseline.evaluate handles a null band;
            // Drift still requires a real edge to project toward, so it only
            // runs where an absolute band exists (deliberate scope honesty —
            // no fabricated "weeks to edge" without a real edge to cross).
            val band = MetricSeries.bandFor(metric, refs)
            val points = MetricSeries.build(metric, ordered, stats)
            baselineMap[metric] = Baseline.evaluate(points, band)
            if (band != null) {
                driftMap[metric] = Drift.evaluate(metric, points, band)
            }
        }

        val openWarnings = inputs.warnings.filter { !it.acknowledged }
        val cards = HealthRollup.cards(inputs.levels, openWarnings, inputs.dtc, driftMap, baselineMap)
        val verdict = HealthRollup.overall(cards)

        val anyDrifting = driftMap.values.any { it is Drift.Result.Drifting }
        val anyGated = driftMap.values.any { it is Drift.Result.Gated }
        val footer = if (!anyDrifting && anyGated) {
            "Drift forecasts unlock after ${HealthTuning.DRIFT_MIN_POINTS} drives across 2 weeks."
        } else null

        val formatter = DateTimeFormatter.ofPattern("MMM d HH:mm")
        val warningUis = inputs.warnings.map { w ->
            WarningUi(
                id = w.id, title = w.title, detail = w.detail, level = w.level,
                timeText = Instant.ofEpochMilli(w.lastSeenAtEpochMs).atZone(zone).format(formatter),
                acknowledged = w.acknowledged,
            )
        }

        val empty = if (inputs.sessions.isEmpty()) {
            if (inputs.demo) {
                "No simulated sessions yet — pick a simulated source on the Dashboard and let it run for a minute or two."
            } else {
                "No real drives recorded yet. Health fills in once the device streams from the car — until then, Demo mode previews everything with simulated data."
            }
        } else null

        return HealthUiState(inputs.demo, verdict, cards, warningUis, footer, empty)
    }
}
