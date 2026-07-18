package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderPolicyTest {

    private fun item(id: Long, enabled: Boolean = true, lastNotified: Long? = null) =
        MaintenanceItemEntity(
            id = id, name = "Item $id", category = "ENGINE", intervalKm = 10_000,
            intervalMonths = null, note = "", confidence = "Best estimate",
            builtin = true, enabled = enabled, lastNotifiedAtEpochMs = lastNotified,
        )

    private fun info(state: DueState) = DueInfo(state, remainingKm = 0, remainingDays = null, kmUntracked = false)

    @Test
    fun notifiesDueSoonAndOverdue_skipsOkAndDisabled() {
        val decisions = listOf(
            ReminderPolicy.Decision(item(1), info(DueState.DUE_SOON)),
            ReminderPolicy.Decision(item(2), info(DueState.OVERDUE)),
            ReminderPolicy.Decision(item(3), info(DueState.OK)),
            ReminderPolicy.Decision(item(4, enabled = false), info(DueState.OVERDUE)),
            ReminderPolicy.Decision(item(5), info(DueState.NEVER_LOGGED)),
            ReminderPolicy.Decision(item(6), info(DueState.KM_UNTRACKED)),
        )
        val notify = ReminderPolicy.itemsToNotify(decisions, nowMs = 0)
        assertEquals(listOf(1L, 2L), notify.map { it.item.id })
    }

    @Test
    fun renotify_gate_isSevenDays() {
        val now = 100L * ReminderPolicy.DAY_MS
        val sixDaysAgo = now - 6 * ReminderPolicy.DAY_MS
        val sevenDaysAgo = now - 7 * ReminderPolicy.DAY_MS
        val decisions = listOf(
            ReminderPolicy.Decision(item(1, lastNotified = sixDaysAgo), info(DueState.OVERDUE)),
            ReminderPolicy.Decision(item(2, lastNotified = sevenDaysAgo), info(DueState.OVERDUE)),
            ReminderPolicy.Decision(item(3, lastNotified = null), info(DueState.OVERDUE)),
        )
        val notify = ReminderPolicy.itemsToNotify(decisions, nowMs = now)
        assertEquals(listOf(2L, 3L), notify.map { it.item.id })
    }
}
