package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DueCalculatorTest {

    private val zone = ZoneId.of("UTC")

    private fun item(km: Int?, months: Int?) = MaintenanceItemEntity(
        id = 1, name = "Oil", category = "ENGINE", intervalKm = km, intervalMonths = months,
        note = "", confidence = "Best estimate", builtin = true, enabled = true,
        lastNotifiedAtEpochMs = null,
    )

    private fun log(date: LocalDate, km: Int?) = ServiceLogEntity(
        id = 1, itemId = 1, epochMs = date.atStartOfDay(zone).toInstant().toEpochMilli(), km = km, note = null,
    )

    private fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun neverLogged() {
        val info = DueCalculator.evaluate(item(10_000, 12), null, 187_000, ms(LocalDate.of(2026, 7, 13)), zone)
        assertEquals(DueState.NEVER_LOGGED, info.state)
    }

    @Test
    fun overdueByKm() {
        val info = DueCalculator.evaluate(
            item(10_000, 12), log(LocalDate.of(2026, 5, 1), 176_000),
            estimatedKm = 187_000, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        assertEquals(DueState.OVERDUE, info.state)
        assertEquals(-1_000, info.remainingKm)
    }

    @Test
    fun overdueByCalendarMonths_endOfMonthEdge() {
        // Jan 31 + 1 month = Feb 28 (2026 not a leap year); Mar 1 is 1 day over.
        val info = DueCalculator.evaluate(
            item(null, 1), log(LocalDate.of(2026, 1, 31), null),
            estimatedKm = null, nowMs = ms(LocalDate.of(2026, 3, 1)), zone = zone,
        )
        assertEquals(DueState.OVERDUE, info.state)
        assertEquals(-1L, info.remainingDays)
    }

    @Test
    fun dueSoon_byKmOrDays() {
        val byKm = DueCalculator.evaluate(
            item(10_000, null), log(LocalDate.of(2026, 1, 1), 177_450),
            estimatedKm = 187_000, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        assertEquals(DueState.DUE_SOON, byKm.state)
        assertEquals(450, byKm.remainingKm)

        val byDays = DueCalculator.evaluate(
            item(null, 12), log(LocalDate.of(2025, 8, 1), null),
            estimatedKm = null, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        assertEquals(DueState.DUE_SOON, byDays.state)
        assertEquals(19L, byDays.remainingDays)
    }

    @Test
    fun kmOnlyItem_withoutOdometer_isKmUntracked() {
        val info = DueCalculator.evaluate(
            item(60_000, null), log(LocalDate.of(2026, 1, 1), 180_000),
            estimatedKm = null, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        assertEquals(DueState.KM_UNTRACKED, info.state)
        assertEquals(true, info.kmUntracked)
    }

    @Test
    fun dualItem_withoutOdometer_fallsBackToDateSide_withHint() {
        val info = DueCalculator.evaluate(
            item(10_000, 12), log(LocalDate.of(2026, 6, 1), null),
            estimatedKm = 187_000, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        // km side incomputable (log has no km) -> date side says OK (11 months left)
        assertEquals(DueState.OK, info.state)
        assertEquals(true, info.kmUntracked)
        assertEquals(null, info.remainingKm)
    }

    @Test
    fun healthy_ok() {
        val info = DueCalculator.evaluate(
            item(10_000, 12), log(LocalDate.of(2026, 6, 1), 182_500),
            estimatedKm = 187_000, nowMs = ms(LocalDate.of(2026, 7, 13)), zone = zone,
        )
        assertEquals(DueState.OK, info.state)
        assertEquals(5_500, info.remainingKm)
    }
}
