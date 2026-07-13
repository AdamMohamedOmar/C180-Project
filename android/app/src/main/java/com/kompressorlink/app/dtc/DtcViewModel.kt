package com.kompressorlink.app.dtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DtcUi(
    val code: String,
    val title: String?,          // null = no W203-specific interpretation
    val causes: List<String>,    // ordered likely-cause chain
    val note: String?,
    val confidence: String?,
)

data class DtcScreenState(
    val stored: List<DtcUi>,
    val pending: List<DtcUi>,
    val hasReport: Boolean,      // false until the first DTC frame arrives
)

class DtcViewModel(
    source: TelemetrySource,
    private val refs: ReferenceRepository,
) : ViewModel() {

    val uiState: StateFlow<DtcScreenState> = source.dtcReport
        .map { build(it, refs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(null, refs))

    companion object {
        fun build(report: DtcReport?, refs: ReferenceRepository): DtcScreenState {
            if (report == null) return DtcScreenState(emptyList(), emptyList(), hasReport = false)
            fun enrich(code: String): DtcUi {
                val entry = refs.dtcFor(code)
                return DtcUi(
                    code = code,
                    title = entry?.title,
                    causes = entry?.likely_causes ?: emptyList(),
                    note = entry?.note,
                    confidence = entry?.confidence,
                )
            }
            return DtcScreenState(
                stored = report.stored.map(::enrich),
                pending = report.pending.map(::enrich),
                hasReport = true,
            )
        }
    }
}
