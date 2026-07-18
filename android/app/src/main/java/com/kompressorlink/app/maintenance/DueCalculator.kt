package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import com.kompressorlink.app.health.HealthTuning
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Spec §6.3's due states, in exact precedence order.
enum class DueState { NEVER_LOGGED, OVERDUE, DUE_SOON, KM_UNTRACKED, OK }

data class DueInfo(
    val state: DueState,
    val remainingKm: Int?,     // null when the km side is incomputable
    val remainingDays: Long?,  // null when no month interval / never logged
    val kmUntracked: Boolean,  // a km interval exists but can't be computed
)

object DueCalculator {
    /**
     * Month math is calendar-correct (LocalDate.plusMonths), not day
     * approximations (spec §6.3). Dual-interval items are due at whichever
     * dimension comes first; a dual item with an incomputable km side falls
     * back to its date side and flags kmUntracked for the small UI hint.
     */
    fun evaluate(
        item: MaintenanceItemEntity,
        lastLog: ServiceLogEntity?,
        estimatedKm: Int?,
        nowMs: Long,
        zone: ZoneId,
    ): DueInfo {
        if (lastLog == null) {
            return DueInfo(DueState.NEVER_LOGGED, null, null, item.intervalKm != null)
        }
        val remainingKm = if (item.intervalKm != null && lastLog.km != null && estimatedKm != null) {
            lastLog.km + item.intervalKm - estimatedKm
        } else null
        val kmUntracked = item.intervalKm != null && remainingKm == null

        val remainingDays = if (item.intervalMonths != null) {
            val lastDate = Instant.ofEpochMilli(lastLog.epochMs).atZone(zone).toLocalDate()
            val dueDate = lastDate.plusMonths(item.intervalMonths.toLong())
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            ChronoUnit.DAYS.between(today, dueDate)
        } else null

        val state = when {
            (remainingKm != null && remainingKm < 0) ||
                (remainingDays != null && remainingDays < 0) -> DueState.OVERDUE
            (remainingKm != null && remainingKm <= HealthTuning.DUE_SOON_KM) ||
                (remainingDays != null && remainingDays <= HealthTuning.DUE_SOON_DAYS) -> DueState.DUE_SOON
            kmUntracked && remainingDays == null -> DueState.KM_UNTRACKED
            else -> DueState.OK
        }
        return DueInfo(state, remainingKm, remainingDays, kmUntracked)
    }
}
