package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.health.HealthTuning

// Spec §6.4: notify when DUE_SOON/OVERDUE and (never notified OR last
// notified >= RENOTIFY_DAYS ago). Disabled items never notify. Marking a
// service done clears lastNotifiedAtEpochMs (repository), so the next
// due cycle notifies fresh.
object ReminderPolicy {
    data class Decision(val item: MaintenanceItemEntity, val info: DueInfo)

    fun itemsToNotify(evaluations: List<Decision>, nowMs: Long): List<Decision> =
        evaluations.filter { (item, info) ->
            item.enabled &&
                (info.state == DueState.DUE_SOON || info.state == DueState.OVERDUE) &&
                (item.lastNotifiedAtEpochMs == null ||
                    nowMs - item.lastNotifiedAtEpochMs >= HealthTuning.RENOTIFY_DAYS * DAY_MS)
        }

    const val DAY_MS = 86_400_000L
}
