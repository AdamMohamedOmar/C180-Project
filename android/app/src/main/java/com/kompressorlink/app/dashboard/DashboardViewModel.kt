package com.kompressorlink.app.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val gauges: List<GaugeUiState>,
    val connectionLabel: String,
)

class DashboardViewModel(
    source: TelemetrySource,
    private val refs: ReferenceRepository,
) : ViewModel() {

    // Sparkline ring buffers: 120 samples at 2 Hz = last 60 s.
    private val histories =
        DashboardLogic.DASHBOARD_SIGNALS.associateWith { ArrayDeque<Float>(HISTORY_CAP) }

    // The history mutation must run exactly once per genuine telemetry
    // emission — never once per combine() invocation. onEach() runs its
    // side effect once per emission from source.telemetry specifically,
    // upstream of and independent from combine()'s re-invocation on a
    // connectionState-only change, so a Connecting->Ready->Disconnected
    // transition (which ticks connectionState without a new telemetry
    // frame) re-derives gauges/connectionLabel from the already-mutated
    // histories without double-appending to them.
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
        ) { snapshot, conn ->
            DashboardUiState(
                gauges = DashboardLogic.DASHBOARD_SIGNALS.map { signal ->
                    DashboardLogic.gaugeFor(signal, snapshot, refs,
                                            histories.getValue(signal).toList())
                },
                connectionLabel = label(conn, snapshot.isDemo, snapshot.klineConnected),
            )
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            DashboardUiState(emptyList(), "Waiting for data…"),
        )

    private fun label(conn: ConnectionState, demo: Boolean, kline: Boolean): String = when (conn) {
        is ConnectionState.Simulated -> "Simulated: ${conn.scenarioName}"
        is ConnectionState.Ready -> when {
            demo -> "Demo device"
            kline -> "Live · K-line up"
            else -> "Live · K-line down"
        }
        ConnectionState.Connecting -> "Connecting…"
        ConnectionState.Disconnected -> "Disconnected"
        ConnectionState.NotAssociated -> "No device associated"
        ConnectionState.ProtocolMismatch -> "Protocol mismatch — update app/firmware"
    }

    private companion object {
        const val HISTORY_CAP = 120
    }
}
