package com.kompressorlink.app.dtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DtcScreenState(
    val stored: List<DtcExplanation>,
    val pending: List<DtcExplanation>,
    val hasReport: Boolean,          // false until the first DTC frame arrives
    val expandedCodes: Set<String>,
)

class DtcViewModel(
    source: TelemetrySource,
    private val repo: DtcRepository,
) : ViewModel() {

    private val expanded = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<DtcScreenState> =
        combine(source.dtcReport, expanded) { report, expandedCodes ->
            build(report, expandedCodes, repo)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            build(null, emptySet(), repo))

    // Keyed the same way DtcScreen's LazyColumn items are keyed ("s-"/"p-"
    // prefix), not the bare code — the same DTC can legitimately appear in
    // both stored and pending at once (pending on this drive cycle, already
    // stored from a prior one), and a bare-code key would make both cards
    // expand/collapse together.
    fun toggle(key: String) {
        expanded.value = if (key in expanded.value) expanded.value - key else expanded.value + key
    }

    companion object {
        fun build(report: DtcReport?, expandedCodes: Set<String>, repo: DtcRepository): DtcScreenState {
            if (report == null) return DtcScreenState(emptyList(), emptyList(), hasReport = false, expandedCodes = expandedCodes)
            return DtcScreenState(
                stored = report.stored.map(repo::explain),
                pending = report.pending.map(repo::explain),
                hasReport = true,
                expandedCodes = expandedCodes,
            )
        }
    }
}
