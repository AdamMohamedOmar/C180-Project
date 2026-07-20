package com.kompressorlink.app.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// Which telemetry source drives the app. Persisted choice; default is
// SIMULATED_HEALTHY on a fresh install — there is no ESP32 until the
// final hardware session (spec §4.1).
enum class SourceChoice(val displayName: String) {
    SIMULATED_HEALTHY("Simulated: healthy"),
    SIMULATED_FAULT("Simulated: fault (P0171)"),
    SIMULATED_SPARSE("Simulated: sparse ECU"),
    REAL_BLE("KompressorLink device (BLE)"),
}

interface SourceChoiceStore {
    val choice: Flow<SourceChoice>
    suspend fun set(choice: SourceChoice)
}

// Itself a TelemetrySource: delegates every flow to whichever source the
// persisted choice selects. ViewModels depend on THIS and never notice a
// switch (spec §4.1).
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSwitcher(
    scope: CoroutineScope,
    private val store: SourceChoiceStore,
    initialChoice: SourceChoice = SourceChoice.SIMULATED_HEALTHY,
    private val sourceFor: (SourceChoice) -> TelemetrySource,
) : TelemetrySource {

    private val active: StateFlow<TelemetrySource> = store.choice
        .map { sourceFor(it) }
        .stateIn(scope, SharingStarted.Eagerly, sourceFor(initialChoice))

    override val connectionState: StateFlow<ConnectionState> = active
        .flatMapLatest { it.connectionState }
        .stateIn(scope, SharingStarted.Eagerly, active.value.connectionState.value)

    override val telemetry: Flow<TelemetrySnapshot> = active.flatMapLatest { it.telemetry }

    override val dtcReport: StateFlow<DtcReport?> = active
        .flatMapLatest { it.dtcReport }
        .stateIn(scope, SharingStarted.Eagerly, active.value.dtcReport.value)

    override suspend fun sendTimeSync() = active.value.sendTimeSync()

    override suspend fun requestWifiSync(): Boolean = active.value.requestWifiSync()
}
