package com.kompressorlink.app.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.data.MaintenanceRepository
import com.kompressorlink.app.data.OdometerRepository
import com.kompressorlink.app.data.SessionRepository
import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OdometerUi(
    val estimatedKm: Int?,        // null -> "Enter your odometer"
    val anchorKm: Int?,
    val anchorEpochMs: Long?,
    val estimatedDeltaKm: Int,
)

data class MaintenanceItemUi(
    val item: MaintenanceItemEntity,
    val info: DueInfo,
    val lastLog: ServiceLogEntity?,
)

data class MaintenanceUiState(
    val odometer: OdometerUi,
    val items: List<MaintenanceItemUi>,
    val message: String?,          // validation errors surfaced to the UI
)

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceViewModel(
    private val maintenance: MaintenanceRepository,
    private val odometer: OdometerRepository,
    private val sessions: SessionRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val refresh = MutableStateFlow(0)  // bumped after writes that flows don't carry

    val uiState: StateFlow<MaintenanceUiState> =
        combine(maintenance.observeItems(), odometer.observeLatestAnchor(), message, refresh) {
                items, anchor, msg, _ ->
            Triple(items, anchor, msg)
        }.mapLatest { (items, anchor, msg) ->
            val distance = anchor?.let { sessions.realDistanceSince(it.epochMs) } ?: 0f
            val estimate = OdometerEstimator.estimate(anchor, distance)
            val nowMs = now()
            val rows = items.map { item ->
                val lastLog = maintenance.latestLogFor(item.id)
                MaintenanceItemUi(item, DueCalculator.evaluate(item, lastLog, estimate?.km, nowMs, zone), lastLog)
            }.sortedWith(
                compareBy<MaintenanceItemUi> { stateOrder(it.info.state) }.thenBy { it.item.name }
            )
            MaintenanceUiState(
                odometer = OdometerUi(
                    estimatedKm = estimate?.km,
                    anchorKm = estimate?.anchorKm,
                    anchorEpochMs = estimate?.anchorEpochMs,
                    estimatedDeltaKm = estimate?.estimatedDeltaKm ?: 0,
                ),
                items = rows,
                message = msg,
            )
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            MaintenanceUiState(OdometerUi(null, null, null, 0), emptyList(), null),
        )

    // Spec §8.5 display order.
    private fun stateOrder(state: DueState): Int = when (state) {
        DueState.OVERDUE -> 0
        DueState.DUE_SOON -> 1
        DueState.NEVER_LOGGED -> 2
        DueState.KM_UNTRACKED -> 3
        DueState.OK -> 4
    }

    fun updateOdometer(km: Int) {
        viewModelScope.launch {
            odometer.addAnchor(km, now())
                .onSuccess { message.value = null }
                .onFailure { message.value = it.message }
        }
    }

    fun logService(itemId: Long, epochMs: Long, km: Int?, note: String?) {
        viewModelScope.launch {
            maintenance.logService(itemId, epochMs, km, note)
            refresh.value++
        }
    }

    fun updateItem(item: MaintenanceItemEntity) {
        viewModelScope.launch {
            maintenance.updateItem(item).onFailure { message.value = it.message }
        }
    }

    fun addCustomItem(name: String, category: String, intervalKm: Int?, intervalMonths: Int?, note: String) {
        viewModelScope.launch {
            maintenance.addCustomItem(
                MaintenanceItemEntity(
                    name = name, category = category, intervalKm = intervalKm,
                    intervalMonths = intervalMonths, note = note, confidence = "Best estimate",
                    builtin = false, enabled = true, lastNotifiedAtEpochMs = null,
                )
            ).onFailure { message.value = it.message }
        }
    }

    fun deleteCustomItem(id: Long) {
        viewModelScope.launch { maintenance.deleteCustomItem(id) }
    }

    fun historyFor(itemId: Long) = maintenance.observeLogsFor(itemId)

    fun clearMessage() {
        message.value = null
    }
}
