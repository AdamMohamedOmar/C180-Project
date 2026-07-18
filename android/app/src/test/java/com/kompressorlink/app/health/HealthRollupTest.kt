package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRollupTest {

    private fun cards(
        levels: Map<Signal, GaugeLevel> = emptyMap(),
        warnings: List<WarningEntity> = emptyList(),
        dtc: DtcReport? = null,
        drift: Map<MetricId, Drift.Result> = emptyMap(),
        baseline: Map<MetricId, Baseline.Result> = emptyMap(),
    ) = HealthRollup.cards(levels, warnings, dtc, drift, baseline)

    private fun card(cards: List<SubsystemCard>, s: Subsystem) = cards.first { it.subsystem == s }

    @Test
    fun allQuiet_everyCardOk_otherHidden() {
        val result = cards()
        assertEquals(6, result.size)  // OTHER hidden when empty
        assertTrue(result.all { it.status == HealthStatus.OK && it.reason == "Looks normal" })
        assertEquals(Subsystem.IGNITION_FOOTNOTE, card(result, Subsystem.IGNITION).footnote)
        assertNull(card(result, Subsystem.FUELING).footnote)
    }

    @Test
    fun storedDtc_forcesAttention_onItsSubsystem() {
        val result = cards(dtc = DtcReport(stored = listOf("P0171"), pending = emptyList()))
        val fueling = card(result, Subsystem.FUELING)
        assertEquals(HealthStatus.ATTENTION, fueling.status)
        assertTrue(fueling.reason.contains("P0171"))
    }

    @Test
    fun unmappedDtc_createsOtherCard() {
        val result = cards(dtc = DtcReport(stored = emptyList(), pending = listOf("P1570")))
        val other = card(result, Subsystem.OTHER)
        assertEquals(HealthStatus.WATCH, other.status)
        assertTrue(other.reason.contains("P1570"))
    }

    @Test
    fun liveLevels_mapToStatuses_noDataIsNeverAFault() {
        val result = cards(levels = mapOf(
            Signal.LTFT1 to GaugeLevel.RED,
            Signal.MAF_GS to GaugeLevel.AMBER,
            Signal.ECT to GaugeLevel.UNAVAILABLE,
            Signal.BATT_V_ADC to GaugeLevel.NEUTRAL,
        ))
        assertEquals(HealthStatus.ATTENTION, card(result, Subsystem.FUELING).status)
        assertEquals(HealthStatus.WATCH, card(result, Subsystem.AIR_INTAKE).status)
        assertEquals(HealthStatus.OK, card(result, Subsystem.COOLING).status)
        assertEquals(HealthStatus.OK, card(result, Subsystem.BATTERY).status)
    }

    @Test
    fun drift_setsArrowAndWatch() {
        val drifting = Drift.Result.Drifting(
            slopePerWeek = 0.7f, weeksToEdge = 3, rising = true, message = "msg",
        )
        val result = cards(drift = mapOf(MetricId.LTFT_WARM_IDLE to drifting))
        val fueling = card(result, Subsystem.FUELING)
        assertEquals(HealthStatus.WATCH, fueling.status)
        assertEquals('↗', fueling.driftArrow)
        assertTrue(fueling.reason.contains("~3 weeks"))
    }

    @Test
    fun drift_oppositeDirectionsOnSameSubsystem_arrowMatchesReportedReason() {
        // FUELING owns two driftable metrics (LTFT_WARM_IDLE, STFT_WARM_IDLE).
        // If they drift in opposite directions, driftArrow must reflect
        // whichever metric's text actually won the `reason` field — never a
        // different metric's direction (that would show e.g. a down-arrow
        // next to "... drifting up ...").
        val rising = Drift.Result.Drifting(
            slopePerWeek = 0.7f, weeksToEdge = 3, rising = true, message = "msg-rising",
        )
        val falling = Drift.Result.Drifting(
            slopePerWeek = -0.5f, weeksToEdge = 5, rising = false, message = "msg-falling",
        )
        val result = cards(drift = mapOf(
            MetricId.LTFT_WARM_IDLE to rising,
            MetricId.STFT_WARM_IDLE to falling,
        ))
        val fueling = card(result, Subsystem.FUELING)
        assertEquals(HealthStatus.WATCH, fueling.status)

        val reasonSaysUp = fueling.reason.contains("drifting up")
        val reasonSaysDown = fueling.reason.contains("drifting down")
        assertTrue(reasonSaysUp != reasonSaysDown)  // exactly one, never both/neither
        assertEquals(if (reasonSaysUp) '↗' else '↘', fueling.driftArrow)
    }

    @Test
    fun baselineGate_rendersCollectingText() {
        val result = cards(baseline = mapOf(
            MetricId.MAF_WARM_IDLE to Baseline.Result.Gated(eligibleCount = 5, needed = 8),
        ))
        assertEquals("Collecting baseline · 5 of 8 drives",
            card(result, Subsystem.AIR_INTAKE).baselineGate)
        assertNull(card(result, Subsystem.COOLING).baselineGate)
    }

    @Test
    fun overall_isWorstOf_neverAnAverage() {
        val quiet = cards()
        assertEquals(HealthStatus.OK, HealthRollup.overall(quiet).status)
        assertEquals("All systems look normal", HealthRollup.overall(quiet).headline)

        val oneBad = cards(dtc = DtcReport(stored = listOf("P0171"), pending = emptyList()))
        val verdict = HealthRollup.overall(oneBad)
        assertEquals(HealthStatus.ATTENTION, verdict.status)
        assertEquals("Needs a look", verdict.headline)
        assertEquals("1 system needs attention — Fueling", verdict.subline)
    }
}
