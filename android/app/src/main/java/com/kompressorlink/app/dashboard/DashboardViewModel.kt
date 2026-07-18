package com.kompressorlink.app.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val gauges: List<GaugeUiState>,
    val connection: ConnectionState,
    val warmIdle: Boolean,
)

class DashboardViewModel(
    source: TelemetrySource,
    private val refs: ReferenceRepository,
    // NOTE: `levels` (populated by LiveWarningMonitor's own, independent
    // `source.telemetry.collect{...}`) and `source.telemetry` (collected
    // separately below) are two independent subscriptions to the same
    // underlying source — not a single shared pipeline. On real BLE this is
    // fine: BleTelemetrySource.telemetry is a genuinely shared
    // MutableSharedFlow, so every collector sees identical frames. In
    // simulated mode, FakeTelemetrySource.telemetry is a cold flow whose
    // seq/tMs restart per subscription, so this ViewModel's `snapshot` and
    // the monitor's `levelMap` are not strictly guaranteed to describe the
    // same instant — most visibly after WhileSubscribed(5_000) lets this
    // ViewModel's subscription idle out and restart while LiveWarningMonitor's
    // (started once, at AppContainer construction) keeps running. Currently
    // harmless, because every fake-scenario banded signal is time-constant
    // and RPM's variance never crosses the warm-idle boundary — but worth
    // knowing before adding a time-varying banded fake signal, which could
    // otherwise produce a silently-wrong BandBar color with no obvious cause.
    levels: StateFlow<Map<Signal, GaugeLevel>>,
) : ViewModel() {

    // Sparkline ring buffers: 120 samples at 2 Hz = last 60 s.
    private val histories =
        DashboardLogic.DASHBOARD_SIGNALS.associateWith { ArrayDeque<Float>(HISTORY_CAP) }

    // History mutation runs exactly once per telemetry emission (onEach is
    // upstream of combine), so a connectionState- or levels-only re-combine
    // re-derives gauges without double-appending — same reasoning as Phase 4.
    val uiState: StateFlow<DashboardUiState> =
        combine(
            source.telemetry.onEach { snapshot ->
                for (signal in DashboardLogic.DASHBOARD_SIGNALS) {
                    val history = histories.getValue(signal)
                    snapshot.value(signal)?.let {
                        if (history.size == HISTORY_CAP) history.removeFirst()
                        history.addLast(it)
                    }
                }
            },
            source.connectionState,
            levels,
        ) { snapshot, conn, levelMap ->
            DashboardUiState(
                gauges = DashboardLogic.DASHBOARD_SIGNALS.map { signal ->
                    DashboardLogic.gaugeFor(
                        signal, snapshot, refs,
                        histories.getValue(signal).toList(),
                        displayedLevel = levelMap[signal],
                    )
                },
                connection = conn,
                warmIdle = DashboardLogic.isWarmIdle(snapshot),
            )
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            DashboardUiState(emptyList(), ConnectionState.Disconnected, warmIdle = false),
        )

    private companion object {
        const val HISTORY_CAP = 120
    }
}
